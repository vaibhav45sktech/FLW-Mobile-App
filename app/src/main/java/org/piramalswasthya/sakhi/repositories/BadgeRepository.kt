package org.piramalswasthya.sakhi.repositories

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.piramalswasthya.sakhi.database.room.InAppDb
import org.piramalswasthya.sakhi.database.room.SyncState
import org.piramalswasthya.sakhi.database.shared_preferences.PreferenceDao
import org.piramalswasthya.sakhi.helpers.SteadySyncerStreakEngine
import org.piramalswasthya.sakhi.helpers.SteadySyncerStreakEngine.DayFacts
import org.piramalswasthya.sakhi.helpers.SteadySyncerStreakEngine.RunState
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine.HealthDomain
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine.Quarter
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine.QuarterActivity
import org.piramalswasthya.sakhi.helpers.TimelyReporterEngine
import org.piramalswasthya.sakhi.helpers.TimelyReporterEngine.MonthFacts
import org.piramalswasthya.sakhi.model.BadgeAwardCache
import org.piramalswasthya.sakhi.model.BadgeIds
import org.piramalswasthya.sakhi.model.BadgeMonthClaimCache
import org.piramalswasthya.sakhi.model.BadgeObservationCache
import org.piramalswasthya.sakhi.model.BadgeStreakWindowCache
import org.piramalswasthya.sakhi.model.BadgeWindowState
import org.piramalswasthya.sakhi.network.getLongFromDateMultipleSupport
import org.piramalswasthya.sakhi.work.WorkerUtils
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Badges evidence + evaluation (S3/S4). READ-ONLY on every clinical table — the only
 * clinical-adjacent read is SyncDao's aggregate backlog count, and its failure
 * direction is safe by design: an under-counted backlog can only make a week
 * SUSPENDED (neutral), never BREAK (punishing).
 *
 * Writes go exclusively to BADGE_OBSERVATION / BADGE_STREAK_WINDOW / BADGE_AWARD.
 * Nothing here touches processed/syncState, hooks a form save, or adds to any push.
 */
@Singleton
class BadgeRepository @Inject constructor(
    private val database: InAppDb,
    private val preferenceDao: PreferenceDao,
    @ApplicationContext private val context: Context,
) {

    /** A tier the latest evaluation NEWLY persisted — drives the award ceremony. */
    data class NewTierAward(val badgeId: String, val tier: Int, val earnedAtMillis: Long)

    companion object {
        private const val DAY_MILLIS = 86_400_000L

        /** Clock-rollback tolerance before observations are refused (guard, not punish). */
        private const val CLOCK_SKEW_TOLERANCE_MILLIS = 6 * 60 * 60 * 1000L

        /** A pending manual-sync outcome older than this resolves as not-qualifying. */
        private const val PENDING_OUTCOME_TTL_MILLIS = 24 * 60 * 60 * 1000L

        /** All timestamps are reduced to UTC epoch-days: no locale, no DST edges. */
        fun epochDay(millis: Long): Long = millis / DAY_MILLIS
    }

    private val evaluationMutex = Mutex()
    private val config = SteadySyncerStreakEngine.SteadySyncerConfig()

    private fun currentUserId(): Int? = preferenceDao.getLoggedInUser()?.userId

    // ─── Backlog + chain state (evidence inputs) ──────────────────────────────

    private suspend fun currentBacklog(): Int = try {
        // SyncDao's maintained aggregate (the sync dashboard's own source): rows of
        // (table, syncState, count). Anything not yet SYNCED is pending work.
        database.syncDao.getSyncStatus().first()
            .filter { it.syncState != SyncState.SYNCED }
            .sumOf { it.count }
    } catch (e: Exception) {
        // Fail toward 0: a missed backlog reading degrades to SUSPENDED, which
        // neither earns nor punishes. Never let a read failure invent a backlog.
        Timber.e(e, "BadgeRepository: backlog read failed, treating as 0")
        0
    }

    /**
     * True when the PUSH-TO-AMRIT chain most recently finished without failure.
     * Used only for the confirmed-nothing-pending ADVANCE, so the conservative
     * default on any error is false.
     */
    private fun pushChainFinishedClean(): Boolean = try {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkerUtils.pushWorkerUniqueName)
            .get()
        infos.isNotEmpty() &&
                infos.all { it.state.isFinished } &&
                infos.none { it.state == WorkInfo.State.FAILED }
    } catch (e: Exception) {
        Timber.e(e, "BadgeRepository: WorkInfo read failed")
        false
    }

    // ─── S3: evidence capture ────────────────────────────────────────────────

    /**
     * Called from the manual "Sync Records" drawer action, strictly BELOW its
     * 15-minute debounce — only taps that actually launched the chain count.
     * Records the intent + a backlog snapshot; the outcome is resolved later by
     * [onAppOpened] (backlog delta, or clean-chain for the nothing-pending case).
     */
    suspend fun onManualSyncFired() = withContext(Dispatchers.IO) {
        val userId = currentUserId() ?: return@withContext
        val now = System.currentTimeMillis()
        val today = epochDay(now)
        val backlogBefore = currentBacklog()
        val existing = database.badgeDao.getObservation(userId, today)
        if (rolledBack(existing?.observedAt, now)) return@withContext

        database.badgeDao.upsertObservation(
            (existing ?: blankObservation(userId, today, now)).copy(
                observedAt = now,
                gateEnabled = true, // this path only runs when the gate is on
                manualSyncCount = (existing?.manualSyncCount ?: 0) + 1,
                backlogNonzeroSeen = (existing?.backlogNonzeroSeen ?: false) || backlogBefore > 0,
                backlogZeroSeen = (existing?.backlogZeroSeen ?: false) || backlogBefore == 0,
                pendingSyncRequestAt = now,
                pendingBacklogBefore = backlogBefore,
            )
        )
    }

    /**
     * App-open tick (S3+S4): resolve any pending manual-sync outcome, write today's
     * observation (INCLUDING gate-off days — FROZEN windows are reconstructed from
     * that flag), then — only while the gate is on — roll the streak forward and
     * return any newly persisted tiers for the ceremony.
     */
    suspend fun onAppOpened(gateEnabled: Boolean): List<NewTierAward> =
        withContext(Dispatchers.IO) {
            val userId = currentUserId() ?: return@withContext emptyList()
            evaluationMutex.withLock {
                val now = System.currentTimeMillis()
                val today = epochDay(now)
                val latest = database.badgeDao.getLatestObservation(userId)
                if (rolledBack(latest?.observedAt, now)) {
                    Timber.w("BadgeRepository: clock rollback detected, skipping tick")
                    return@withLock emptyList()
                }

                resolvePendingOutcomes(userId, now)

                val backlogNow = currentBacklog()
                val todayRow = database.badgeDao.getObservation(userId, today)
                database.badgeDao.upsertObservation(
                    (todayRow ?: blankObservation(userId, today, now)).copy(
                        observedAt = now,
                        gateEnabled = gateEnabled,
                        backlogNonzeroSeen =
                            (todayRow?.backlogNonzeroSeen ?: false) || backlogNow > 0,
                        backlogZeroSeen =
                            (todayRow?.backlogZeroSeen ?: false) || backlogNow == 0,
                    )
                )

                if (!gateEnabled) return@withLock emptyList()

                val newAwards = evaluateSteadySyncer(userId, today) +
                        evaluateTimelyReporter(userId, now) +
                        evaluateCompleteWorker(userId, now)
                database.badgeDao.pruneObservations(
                    userId, today - BadgeObservationCache.OBSERVATION_RETENTION_DAYS
                )
                newAwards
            }
        }

    /**
     * Locked outcome rules: backlog DECREASED since the tap → ≥1 record uploaded
     * (partial success qualifies); tapped with ZERO pending and the chain finished
     * clean → confirmed nothing-pending. Anything else resolves not-qualifying once
     * the chain is terminal or the request ages out.
     */
    private suspend fun resolvePendingOutcomes(userId: Int, now: Long) {
        val recent = database.badgeDao.getObservationsFrom(userId, epochDay(now) - 3)
        val pendingRows = recent.filter { it.pendingSyncRequestAt != null }
        if (pendingRows.isEmpty()) return
        val backlogNow = currentBacklog()
        val chainClean = pushChainFinishedClean()

        pendingRows.forEach { row ->
            val before = row.pendingBacklogBefore
            val qualified = when {
                before != null && backlogNow < before -> true
                before == 0 && chainClean -> true
                else -> false
            }
            val expired = now - (row.pendingSyncRequestAt ?: now) > PENDING_OUTCOME_TTL_MILLIS
            if (qualified || chainClean || expired) {
                database.badgeDao.upsertObservation(
                    row.copy(
                        observedAt = now,
                        qualifyingSync = row.qualifyingSync || qualified,
                        pendingSyncRequestAt = null,
                        pendingBacklogBefore = null,
                    )
                )
            }
        }
    }

    // ─── S4: evaluation + award persistence ──────────────────────────────────

    private suspend fun evaluateSteadySyncer(userId: Int, today: Long): List<NewTierAward> {
        val badgeId = BadgeIds.STEADY_SYNCER
        val latestWindow = database.badgeDao.getLatestWindow(userId, badgeId)
        val (priorRun, resumeDay) = reconstructRunState(userId, badgeId, latestWindow)

        val facts = database.badgeDao.getObservationsFrom(userId, resumeDay).map {
            DayFacts(
                day = it.observationDay,
                gateEnabled = it.gateEnabled,
                qualifyingSync = it.qualifyingSync,
                backlogNonzeroSeen = it.backlogNonzeroSeen,
            )
        }
        if (facts.isEmpty() && priorRun == null) return emptyList()

        val earnedMax = database.badgeDao.getHighestTier(userId, badgeId) ?: 0
        val result = SteadySyncerStreakEngine.evaluate(
            priorRun, facts, today, earnedMax, config
        ) { UUID.randomUUID().toString() }
        if (result.closedWindows.isEmpty() && result.tierAwards.isEmpty()) return emptyList()

        val windowRows = result.closedWindows.map {
            BadgeStreakWindowCache(
                userId = userId,
                badgeId = badgeId,
                streakRunId = it.runId,
                windowIndex = it.windowIndex,
                windowStartMillis = it.startDay * DAY_MILLIS,
                windowEndMillis = it.endDay * DAY_MILLIS,
                state = it.state.name,
                graceAllowanceAfter = it.graceLeftAfter,
                advanceCountAfter = it.advanceCountAfter,
                closedAt = System.currentTimeMillis(),
            )
        }
        val awardRows = result.tierAwards.map {
            BadgeAwardCache(
                userId = userId,
                badgeId = badgeId,
                tier = it.tier,
                streakRunId = it.runId,
                earnedAt = it.earnedOnDay * DAY_MILLIS,
            )
        }
        val insertIds = database.badgeDao.commitEvaluation(windowRows, awardRows)
        return awardRows.filterIndexed { index, _ -> insertIds[index] != -1L }
            .map { NewTierAward(it.badgeId, it.tier, it.earnedAt) }
    }

    /**
     * Rebuilds the engine's resume point from the last closed window. A BREAK — or
     * a suspension tally at the cap — means no live run: the engine then re-anchors
     * on the next qualifying sync, fed only observations AFTER that window, so old
     * evidence can never be replayed into a phantom second run.
     */
    private suspend fun reconstructRunState(
        userId: Int,
        badgeId: String,
        latest: BadgeStreakWindowCache?,
    ): Pair<RunState?, Long> {
        if (latest == null) return null to 0L
        val resumeDay = epochDay(latest.windowEndMillis)
        if (latest.state == BadgeWindowState.BREAK.name) return null to resumeDay

        val runWindows = database.badgeDao
            .getWindowsForRun(userId, badgeId, latest.streakRunId)
        var trailingSuspended = 0
        for (window in runWindows.sortedByDescending { it.windowIndex }) {
            when (window.state) {
                BadgeWindowState.SUSPENDED.name -> trailingSuspended++
                BadgeWindowState.FROZEN.name -> continue // excluded, not a reset
                else -> break
            }
        }
        if (trailingSuspended >= config.suspendedCap) return null to resumeDay

        val startDay = epochDay(latest.windowStartMillis)
        return RunState(
            runId = latest.streakRunId,
            runStartDay = startDay - (latest.windowIndex - 1) * config.windowDays,
            nextWindowIndex = latest.windowIndex + 1,
            advanceCount = latest.advanceCountAfter,
            graceLeft = latest.graceAllowanceAfter,
            consecutiveSuspended = trailingSuspended,
        ) to resumeDay
    }

    // ─── Timely Reporter (S-TR) ──────────────────────────────────────────────

    private val timelyConfig = TimelyReporterEngine.TimelyReporterConfig()

    /**
     * Rolls up INCENTIVE_RECORD by month, preserves the first-seen claim date, and
     * evaluates the month streak. Read-only on the clinical side: the only writes
     * are to BADGE_MONTH_CLAIM and BADGE_AWARD.
     */
    private suspend fun evaluateTimelyReporter(userId: Int, now: Long): List<NewTierAward> {
        val badgeId = BadgeIds.TIMELY_REPORTER
        val rollup = database.badgeDao.getMonthlyClaimRollup(userId)
        if (rollup.isEmpty()) return emptyList()

        // Record the FIRST claim date we ever see for a month. IGNORE keeps the
        // original even if the server later overwrites calimedDate on a re-claim.
        rollup.filter { it.anyClaimed == 1 }.forEach { row ->
            val parsed = getLongFromDateMultipleSupport(row.earliestClaimDate)
            if (parsed != null && parsed > 0) {
                database.badgeDao.insertFirstSeenClaim(
                    BadgeMonthClaimCache(
                        userId = userId,
                        yearMonth = row.yearMonth,
                        firstClaimAtMillis = parsed,
                        observedAt = now,
                    )
                )
            }
        }
        val firstSeen = database.badgeDao.getFirstSeenClaims(userId)
            .associate { it.yearMonth to it.firstClaimAtMillis }

        // Judge only months whose deadline has already passed — an in-flight month
        // still legitimately awaiting a claim must never read as MISSED.
        val facts = rollup.mapNotNull { row ->
            val deadline = deadlineMillisFor(row.yearMonth) ?: return@mapNotNull null
            if (deadline > now) return@mapNotNull null
            MonthFacts(
                yearMonth = row.yearMonth,
                hasIncentiveRecords = row.recordCount > 0,
                isClaimed = row.anyClaimed == 1,
                // Prefer the ledger's original date over the (mutable) server value.
                claimedAtMillis = firstSeen[row.yearMonth]
                    ?: getLongFromDateMultipleSupport(row.earliestClaimDate)?.takeIf { it > 0 },
                deadlineMillis = deadline,
            )
        }
        if (facts.isEmpty()) return emptyList()

        val earnedMax = database.badgeDao.getHighestTier(userId, badgeId) ?: 0
        val result = TimelyReporterEngine.evaluate(facts, earnedMax, timelyConfig)
        if (result.tierAwards.isEmpty()) return emptyList()

        val awardRows = result.tierAwards.map {
            BadgeAwardCache(
                userId = userId,
                badgeId = badgeId,
                tier = it.tier,
                earnedAt = now,
            )
        }
        val insertIds = database.badgeDao.commitEvaluation(emptyList(), awardRows)
        return awardRows.filterIndexed { index, _ -> insertIds[index] != -1L }
            .map { NewTierAward(it.badgeId, it.tier, it.earnedAt) }
    }

    /**
     * Last instant of the deadline day in the FOLLOWING month — end of the 5th by
     * default, inclusive. Device-local time by design: "the 5th" means the 5th
     * where she is.
     */
    private fun deadlineMillisFor(yearMonth: Int): Long? {
        val year = yearMonth / 100
        val month = yearMonth % 100
        if (month !in 1..12) return null
        return Calendar.getInstance().apply {
            clear()
            // Calendar months are 0-based; `month` (1-based) therefore already
            // points at the FOLLOWING month, and rolls the year over on its own.
            set(year, month, timelyConfig.deadlineDayOfNextMonth, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    data class TimelyReporterStatus(
        val highestTierEarned: Int,
        val currentStreak: Int,
        val nextTierThreshold: Int?,
    )

    suspend fun getTimelyReporterStatus(): TimelyReporterStatus = withContext(Dispatchers.IO) {
        val userId = currentUserId()
            ?: return@withContext TimelyReporterStatus(0, 0, timelyConfig.tierThresholds.first())
        val highest = database.badgeDao.getHighestTier(userId, BadgeIds.TIMELY_REPORTER) ?: 0
        val now = System.currentTimeMillis()
        val firstSeen = database.badgeDao.getFirstSeenClaims(userId)
            .associate { it.yearMonth to it.firstClaimAtMillis }
        val facts = database.badgeDao.getMonthlyClaimRollup(userId).mapNotNull { row ->
            val deadline = deadlineMillisFor(row.yearMonth) ?: return@mapNotNull null
            if (deadline > now) return@mapNotNull null
            MonthFacts(
                yearMonth = row.yearMonth,
                hasIncentiveRecords = row.recordCount > 0,
                isClaimed = row.anyClaimed == 1,
                claimedAtMillis = firstSeen[row.yearMonth]
                    ?: getLongFromDateMultipleSupport(row.earliestClaimDate)?.takeIf { it > 0 },
                deadlineMillis = deadline,
            )
        }
        val result = TimelyReporterEngine.evaluate(facts, highest, timelyConfig)
        TimelyReporterStatus(highest, result.currentStreak, result.nextThreshold)
    }

    // --- Complete Worker (S-CW) ---------------------------------------------

    private val completeWorkerConfig = CompleteWorkerEngine.CompleteWorkerConfig()

    /**
     * Probes each health domain for attributable activity in each examined
     * calendar quarter, then awards any quarter that reached the domain
     * threshold. Read-only on every clinical table; writes only BADGE_AWARD.
     *
     * Resilience notes, since this runs unattended on every app open forever:
     *  - Each domain probe is wrapped: one failing table (a migration quirk, a
     *    column renamed by some future upstream merge) degrades that ONE domain
     *    to "not active" instead of discarding the whole evaluation. Under-credit
     *    beats a crash, and beats mis-credit.
     *  - Award identity is the quarter key, so re-running this a thousand times
     *    can never double-award, and an edited clinical record can never revoke.
     *  - Quarters already earned are filtered out before any probing, so the
     *    steady-state cost after first launch is only the current quarter.
     */
    private suspend fun evaluateCompleteWorker(userId: Int, now: Long): List<NewTierAward> {
        val badgeId = BadgeIds.COMPLETE_WORKER
        val userName = preferenceDao.getLoggedInUser()?.userName ?: return emptyList()

        val currentQuarter = quarterOf(now)
        val alreadyEarned = database.badgeDao.getOccurrenceKeys(userId, badgeId).toSet()

        // Only probe quarters we could still award. The current quarter is always
        // probed so the progress read-model stays live even once it is earned.
        val quarters = CompleteWorkerEngine
            .quartersToExamine(currentQuarter, completeWorkerConfig)
            .filter { it.key !in alreadyEarned || it == currentQuarter }

        val activity = quarters.map { q ->
            val bounds = quarterBounds(q)
            QuarterActivity(q, activeDomainsIn(userName, bounds.first, bounds.second))
        }

        val result = CompleteWorkerEngine.evaluate(
            activityByQuarter = activity,
            currentQuarter = currentQuarter,
            alreadyEarnedQuarters = alreadyEarned,
            config = completeWorkerConfig,
        )
        if (result.newAwards.isEmpty()) return emptyList()

        val awardRows = result.newAwards.map {
            BadgeAwardCache(
                userId = userId,
                badgeId = badgeId,
                // Quarterly badge: no tier ladder. The quarter NUMBER selects the
                // artwork (Q1..Q4), so it is stored here instead of a milestone.
                tier = it.quarter.quarter,
                occurrenceKey = it.quarter.key,
                earnedAt = now,
            )
        }
        val insertIds = database.badgeDao.commitEvaluation(emptyList(), awardRows)
        return awardRows.filterIndexed { i, _ -> insertIds[i] != -1L }
            .map { NewTierAward(it.badgeId, it.tier, it.earnedAt) }
    }

    /**
     * Runs all five domain probes for one window. Each is individually guarded:
     * a single broken probe must not be able to void an otherwise valid quarter.
     */
    private suspend fun activeDomainsIn(
        userName: String,
        startInclusive: Long,
        endExclusive: Long,
    ): Set<HealthDomain> {
        val dao = database.badgeDao
        val domains = mutableSetOf<HealthDomain>()

        suspend fun probe(domain: HealthDomain, query: suspend () -> Boolean) {
            try {
                if (query()) domains.add(domain)
            } catch (e: Exception) {
                Timber.e(e, "CompleteWorker: probe failed for " + domain.name + ", treating as inactive")
            }
        }

        probe(HealthDomain.CHILD_HEALTH) {
            dao.hasChildHealthActivity(userName, startInclusive, endExclusive)
        }
        probe(HealthDomain.MATERNAL_HEALTH) {
            dao.hasMaternalHealthActivity(userName, startInclusive, endExclusive)
        }
        probe(HealthDomain.IMMUNIZATION) {
            dao.hasImmunizationActivity(userName, startInclusive, endExclusive)
        }
        probe(HealthDomain.FAMILY_PLANNING) {
            dao.hasFamilyPlanningActivity(userName, startInclusive, endExclusive)
        }
        probe(HealthDomain.DISEASE_CONTROL) {
            dao.hasDiseaseControlActivity(userName, startInclusive, endExclusive)
        }
        return domains
    }

    /** The calendar quarter a timestamp falls in, in device-local time. */
    private fun quarterOf(millis: Long): Quarter {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return Quarter.of(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    /**
     * Half-open bounds of a calendar quarter: from the start of its first month to
     * the start of the next quarter's first month. Half-open so an activity landing
     * exactly on a boundary belongs to exactly one quarter, never two.
     */
    private fun quarterBounds(quarter: Quarter): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            clear()
            set(quarter.year, quarter.firstMonthIndex, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 3) }
        return start.timeInMillis to end.timeInMillis
    }

    data class CompleteWorkerStatus(
        val currentQuarterKey: String,
        val currentQuarterNumber: Int,
        val domainsActive: Int,
        val domainsRequired: Int,
        val earnedThisQuarter: Boolean,
        val quartersEarnedTotal: Int,
    )

    suspend fun getCompleteWorkerStatus(): CompleteWorkerStatus = withContext(Dispatchers.IO) {
        val required = completeWorkerConfig.domainsRequired
        val userId = currentUserId()
        val userName = preferenceDao.getLoggedInUser()?.userName
        val now = System.currentTimeMillis()
        val q = quarterOf(now)
        if (userId == null || userName == null) {
            return@withContext CompleteWorkerStatus(q.key, q.quarter, 0, required, false, 0)
        }
        val earnedKeys = database.badgeDao.getOccurrenceKeys(userId, BadgeIds.COMPLETE_WORKER)
        val bounds = quarterBounds(q)
        val active = activeDomainsIn(userName, bounds.first, bounds.second).size
        CompleteWorkerStatus(
            currentQuarterKey = q.key,
            currentQuarterNumber = q.quarter,
            domainsActive = active,
            domainsRequired = required,
            earnedThisQuarter = q.key in earnedKeys,
            quartersEarnedTotal = earnedKeys.size,
        )
    }

    // ─── UI read model ───────────────────────────────────────────────────────

    data class SteadySyncerStatus(
        val highestTierEarned: Int,
        val advanceCount: Int,
        val nextTierThreshold: Int?,
        val graceLeft: Int,
        val runAlive: Boolean,
    )

    suspend fun getSteadySyncerStatus(): SteadySyncerStatus = withContext(Dispatchers.IO) {
        val userId = currentUserId()
            ?: return@withContext SteadySyncerStatus(0, 0, config.tierThresholds.first(), 0, false)
        val highest = database.badgeDao.getHighestTier(userId, BadgeIds.STEADY_SYNCER) ?: 0
        val latest = database.badgeDao.getLatestWindow(userId, BadgeIds.STEADY_SYNCER)
        val (run, _) = reconstructRunState(userId, BadgeIds.STEADY_SYNCER, latest)
        val advance = run?.advanceCount ?: 0
        SteadySyncerStatus(
            highestTierEarned = highest,
            advanceCount = advance,
            nextTierThreshold = config.tierThresholds.firstOrNull { it > advance },
            graceLeft = run?.graceLeft ?: config.graceAllowance,
            runAlive = run != null,
        )
    }

    /** Debug seeder support only — call sites are gated on BuildConfig.DEBUG. */
    fun requireUserIdForDebug(): Int =
        currentUserId() ?: error("No logged-in user for badge demo seeding")

    /**
     * Debug-panel evidence readout (BuildConfig.DEBUG call sites only): lets the
     * real-flow UAT test be verified on-screen — tap Sync Records, reopen, watch
     * qualifyingSync flip to true — without needing adb.
     */
    suspend fun getDebugEvidenceSummary(): String = withContext(Dispatchers.IO) {
        val userId = currentUserId() ?: return@withContext "no logged-in user"
        val today = epochDay(System.currentTimeMillis())
        val row = database.badgeDao.getObservation(userId, today)
        val latestWindow = database.badgeDao.getLatestWindow(userId, BadgeIds.STEADY_SYNCER)
        val backlog = currentBacklog()
        buildString {
            append("user=").append(userId)
            append("  day=").append(today)
            append("  backlogNow=").append(backlog)
            append('\n')
            if (row == null) {
                append("today: no observation yet")
            } else {
                append("today: taps=").append(row.manualSyncCount)
                append("  qualifying=").append(row.qualifyingSync)
                append("  backlogSeen=").append(row.backlogNonzeroSeen)
                append("  zeroSeen=").append(row.backlogZeroSeen)
                append("  pending=").append(row.pendingSyncRequestAt != null)
                row.pendingBacklogBefore?.let { append(" (before=").append(it).append(')') }
            }
            append('\n')
            if (latestWindow == null) {
                append("windows: none closed yet")
            } else {
                append("last window #").append(latestWindow.windowIndex)
                append(' ').append(latestWindow.state)
                append("  advances=").append(latestWindow.advanceCountAfter)
            }
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun blankObservation(userId: Int, day: Long, now: Long) =
        BadgeObservationCache(
            userId = userId,
            observationDay = day,
            observedAt = now,
            gateEnabled = true,
        )

    private fun rolledBack(lastObservedAt: Long?, now: Long): Boolean =
        lastObservedAt != null && now < lastObservedAt - CLOCK_SKEW_TOLERANCE_MILLIS
}
