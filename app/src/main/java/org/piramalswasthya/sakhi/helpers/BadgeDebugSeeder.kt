package org.piramalswasthya.sakhi.helpers

import org.piramalswasthya.sakhi.database.room.InAppDb
import org.piramalswasthya.sakhi.model.BadgeAwardCache
import org.piramalswasthya.sakhi.model.BadgeIds
import org.piramalswasthya.sakhi.model.BadgeObservationCache

/**
 * Demo-timeline fabricator (S7). Writes ONLY badge-owned tables, and only from
 * BuildConfig.DEBUG call sites — production never deletes or fabricates badge
 * data. Exists so a tier ceremony can be demoed today instead of four weeks
 * from now: it plants qualifying-sync observations in the past and lets the REAL
 * evaluator (engine + repository, the same code path production runs) do the
 * closing and awarding.
 */
object BadgeDebugSeeder {

    private const val DAY_MILLIS = 86_400_000L

    private suspend fun reset(database: InAppDb, userId: Int) {
        database.badgeDao.debugClearAwards(userId)
        database.badgeDao.debugClearWindows(userId)
        database.badgeDao.debugClearObservations(userId)
        database.badgeDao.debugClearMonthClaims(userId)
    }

    private fun syncDay(userId: Int, day: Long, now: Long) = BadgeObservationCache(
        userId = userId,
        observationDay = day,
        observedAt = now,
        gateEnabled = true,
        manualSyncCount = 1,
        qualifyingSync = true,
        backlogNonzeroSeen = true,
        backlogZeroSeen = false,
    )

    /**
     * Mid-journey state: weekly syncs 24, 17 and 10 days ago. Windows 1–3 close as
     * ADVANCE; window 4 is still open → ring shows 3 of 4 weeks, no ceremony.
     */
    suspend fun seedThreeOfFourWeeks(database: InAppDb, userId: Int) {
        reset(database, userId)
        val now = System.currentTimeMillis()
        val today = now / DAY_MILLIS
        listOf(24L, 17L, 10L).forEach { daysAgo ->
            database.badgeDao.upsertObservation(syncDay(userId, today - daysAgo, now))
        }
    }

    /**
     * Tier-earning state: [weeksOfSyncs] weekly syncs whose final window closes
     * exactly today, so the next evaluation awards every tier the count reaches —
     * 4 → Tier 1 ceremony, 26 → the full ladder with the Tier 4 ceremony.
     */
    suspend fun seedEarnTierNow(database: InAppDb, userId: Int, weeksOfSyncs: Int) {
        reset(database, userId)
        val now = System.currentTimeMillis()
        val today = now / DAY_MILLIS
        val runStart = today - 7L * weeksOfSyncs
        repeat(weeksOfSyncs) { week ->
            database.badgeDao.upsertObservation(
                syncDay(userId, runStart + 7L * week, now)
            )
        }
    }

    /**
     * Complete Worker demo. Unlike the Sync seeders, this inserts the AWARD rather
     * than the evidence: the evidence lives in clinical tables (INFANT_REG,
     * PREGNANCY_*, IMMUNIZATION, ELIGIBLE_COUPLE_TRACKING, LEPROSY_*) which the
     * badge module is forbidden to write. The real evaluator is exercised by the
     * unit tests and by real UAT data instead.
     *
     * [quarterNumber] 1..4 selects which quarter's artwork appears.
     */
    suspend fun seedCompleteWorkerQuarter(
        database: InAppDb,
        userId: Int,
        year: Int,
        quarterNumber: Int,
    ) {
        val now = System.currentTimeMillis()
        database.badgeDao.debugInsertAward(
            BadgeAwardCache(
                userId = userId,
                badgeId = BadgeIds.COMPLETE_WORKER,
                tier = quarterNumber,
                occurrenceKey = year.toString() + "Q" + quarterNumber,
                earnedAt = now,
            )
        )
    }
}
