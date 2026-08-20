package org.piramalswasthya.sakhi.database.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.piramalswasthya.sakhi.model.BadgeAwardCache
import org.piramalswasthya.sakhi.model.BadgeMonthClaimCache
import org.piramalswasthya.sakhi.model.BadgeObservationCache
import org.piramalswasthya.sakhi.model.BadgeStreakWindowCache
import org.piramalswasthya.sakhi.model.MonthlyClaimRow

/**
 * Badges (read-only gamification): every query is scoped to the badge-owned tables
 * BADGE_AWARD / BADGE_STREAK_WINDOW / BADGE_OBSERVATION. Clinical tables are never
 * touched from here — same isolation rule the Monthly Recap follows.
 */
@Dao
interface BadgeDao {

    // ─── Awards (insert-only: an earned badge is never revoked) ───────────────

    /**
     * IGNORE + the unique index on (userId, badgeId, tier, occurrenceKey) makes the
     * award idempotent: re-evaluating the same history can never duplicate a badge.
     * Returns -1 when the award already existed, the new rowId otherwise.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAward(award: BadgeAwardCache): Long

    @Query("SELECT * FROM BADGE_AWARD WHERE userId = :userId ORDER BY earnedAt")
    suspend fun getAwards(userId: Int): List<BadgeAwardCache>

    @Query("SELECT * FROM BADGE_AWARD WHERE userId = :userId ORDER BY earnedAt")
    fun observeAwards(userId: Int): Flow<List<BadgeAwardCache>>

    @Query(
        "SELECT MAX(tier) FROM BADGE_AWARD WHERE userId = :userId AND badgeId = :badgeId"
    )
    suspend fun getHighestTier(userId: Int, badgeId: String): Int?

    /**
     * Award keys already persisted for a badge — the dedup set for quarterly and
     * once-per-case badges. Returned as a list so the caller decides on a Set.
     */
    @Query(
        "SELECT occurrenceKey FROM BADGE_AWARD WHERE userId = :userId AND badgeId = :badgeId"
    )
    suspend fun getOccurrenceKeys(userId: Int, badgeId: String): List<String>

    // ─── Streak windows (write-once rows of CLOSED windows) ──────────────────

    @Upsert
    suspend fun upsertWindows(windows: List<BadgeStreakWindowCache>)

    /** Latest closed window across runs — the resume point for evaluation. */
    @Query(
        "SELECT * FROM BADGE_STREAK_WINDOW WHERE userId = :userId AND badgeId = :badgeId " +
                "ORDER BY closedAt DESC, windowIndex DESC LIMIT 1"
    )
    suspend fun getLatestWindow(userId: Int, badgeId: String): BadgeStreakWindowCache?

    @Query(
        "SELECT * FROM BADGE_STREAK_WINDOW WHERE userId = :userId AND badgeId = :badgeId " +
                "AND streakRunId = :runId ORDER BY windowIndex"
    )
    suspend fun getWindowsForRun(
        userId: Int,
        badgeId: String,
        runId: String
    ): List<BadgeStreakWindowCache>

    // ─── Daily observations (the evidence ledger) ─────────────────────────────

    @Upsert
    suspend fun upsertObservation(observation: BadgeObservationCache)

    @Query(
        "SELECT * FROM BADGE_OBSERVATION WHERE userId = :userId AND observationDay = :day"
    )
    suspend fun getObservation(userId: Int, day: Long): BadgeObservationCache?

    @Query(
        "SELECT * FROM BADGE_OBSERVATION WHERE userId = :userId " +
                "AND observationDay >= :fromDay ORDER BY observationDay"
    )
    suspend fun getObservationsFrom(userId: Int, fromDay: Long): List<BadgeObservationCache>

    @Query(
        "SELECT * FROM BADGE_OBSERVATION WHERE userId = :userId " +
                "ORDER BY observationDay DESC LIMIT 1"
    )
    suspend fun getLatestObservation(userId: Int): BadgeObservationCache?

    /** Evidence older than the retention floor is pruned; awards stay forever. */
    @Query(
        "DELETE FROM BADGE_OBSERVATION WHERE userId = :userId AND observationDay < :cutoffDay"
    )
    suspend fun pruneObservations(userId: Int, cutoffDay: Long)

    // ─── Timely Reporter: monthly incentive claims ────────────────────────────

    /**
     * READ-ONLY roll-up of INCENTIVE_RECORD into one row per calendar month.
     *
     * Ownership is `ashaId`, which the server populates — the strongest evidence
     * class in the badge set (no `createdBy` string matching needed). Months are
     * bucketed on `createdDate`, the same field `IncentivesViewModel` itself uses
     * to group the incentive screen by month.
     *
     * A month counts as claimed when ANY of its records is claimed: the app claims
     * a whole month in one action and its own UI reads record[0] as representative.
     * MIN(calimedDate) picks the earliest non-empty claim date present, which is
     * the closest the raw table can get to "the original submission"; the
     * BADGE_MONTH_CLAIM ledger is what actually preserves it across a re-claim.
     */
    @Query(
        """
        SELECT CAST(strftime('%Y%m', createdDate / 1000, 'unixepoch') AS INTEGER) AS yearMonth,
               COUNT(*) AS recordCount,
               MAX(CASE WHEN isClaimed THEN 1 ELSE 0 END) AS anyClaimed,
               MIN(NULLIF(calimedDate, '')) AS earliestClaimDate
        FROM INCENTIVE_RECORD
        WHERE ashaId = :ashaId AND createdDate > 0
        GROUP BY yearMonth
        ORDER BY yearMonth
        """
    )
    suspend fun getMonthlyClaimRollup(ashaId: Int): List<MonthlyClaimRow>

    /** IGNORE: the first observed claim date for a month is never overwritten. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstSeenClaim(claim: BadgeMonthClaimCache): Long

    @Query("SELECT * FROM BADGE_MONTH_CLAIM WHERE userId = :userId")
    suspend fun getFirstSeenClaims(userId: Int): List<BadgeMonthClaimCache>

    // ─── Complete Worker: per-domain activity probes ──────────────────────────
    //
    // Each probe answers ONE question: did this ASHA have any attributable
    // activity in this domain inside [startInclusive, endExclusive)?
    //
    // Deliberately LIMIT 1 / EXISTS-shaped: the badge needs a boolean, never a
    // count, so SQLite can stop at the first matching row instead of scanning the
    // rest of the table. Combined with the once-a-day evaluation cap this keeps
    // the whole badge well under a millisecond of real work in steady state.
    //
    // Ownership predicates are the ones the Monthly Recap already verified
    // against this schema; every probe excludes rows whose owner or date is
    // missing rather than assuming they belong to the current user.

    /** Child Health — INFANT_REG has non-null createdBy + createdDate. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM INFANT_REG
            WHERE createdBy = :userName
              AND createdDate >= :startInclusive AND createdDate < :endExclusive
            LIMIT 1
        )
        """
    )
    suspend fun hasChildHealthActivity(
        userName: String,
        startInclusive: Long,
        endExclusive: Long
    ): Boolean

    /**
     * Maternal Health — any of the five maternal touchpoints. Each arm is its own
     * indexed lookup on benId; the OR-of-EXISTS shape lets SQLite stop as soon as
     * the first arm matches, so a busy maternal ASHA costs one probe, not five.
     */
    @Query(
        """
        SELECT (
            EXISTS(SELECT 1 FROM PREGNANCY_REGISTER WHERE createdBy = :userName
                   AND dateOfRegistration >= :startInclusive AND dateOfRegistration < :endExclusive LIMIT 1)
            OR EXISTS(SELECT 1 FROM PREGNANCY_ANC WHERE createdBy = :userName
                   AND ancDate >= :startInclusive AND ancDate < :endExclusive LIMIT 1)
            OR EXISTS(SELECT 1 FROM PMSMA WHERE createdBy = :userName
                   AND visitDate IS NOT NULL
                   AND visitDate >= :startInclusive AND visitDate < :endExclusive LIMIT 1)
            OR EXISTS(SELECT 1 FROM DELIVERY_OUTCOME WHERE createdBy = :userName
                   AND dateOfDelivery IS NOT NULL
                   AND dateOfDelivery >= :startInclusive AND dateOfDelivery < :endExclusive LIMIT 1)
            OR EXISTS(SELECT 1 FROM PNC_VISIT WHERE createdBy = :userName
                   AND pncDate >= :startInclusive AND pncDate < :endExclusive LIMIT 1)
        )
        """
    )
    suspend fun hasMaternalHealthActivity(
        userName: String,
        startInclusive: Long,
        endExclusive: Long
    ): Boolean

    /** Immunization — doses she recorded. `date` is nullable; NULLs excluded. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM IMMUNIZATION
            WHERE createdBy = :userName
              AND date IS NOT NULL
              AND date >= :startInclusive AND date < :endExclusive
            LIMIT 1
        )
        """
    )
    suspend fun hasImmunizationActivity(
        userName: String,
        startInclusive: Long,
        endExclusive: Long
    ): Boolean

    /**
     * Family Planning — eligible-couple tracking. Windowed on visitDate when set,
     * falling back to createdDate, because visitDate defaults to 0 rather than
     * NULL in this entity and a 0 must never be read as "January 1970".
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM ELIGIBLE_COUPLE_TRACKING
            WHERE createdBy = :userName
              AND (
                    (visitDate > 0 AND visitDate >= :startInclusive AND visitDate < :endExclusive)
                 OR (visitDate <= 0 AND createdDate >= :startInclusive AND createdDate < :endExclusive)
              )
            LIMIT 1
        )
        """
    )
    suspend fun hasFamilyPlanningActivity(
        userName: String,
        startInclusive: Long,
        endExclusive: Long
    ): Boolean

    /**
     * Disease Control — Leprosy only, and knowingly so: of the disease screening
     * tables only Leprosy's save paths populate createdBy, so malaria / AES /
     * filaria work cannot be attributed to an individual ASHA. Under-credits
     * rather than mis-credits. Fixing this needs createdBy on those payloads.
     */
    @Query(
        """
        SELECT (
            EXISTS(SELECT 1 FROM LEPROSY_SCREENING WHERE createdBy = :userName
                   AND homeVisitDate >= :startInclusive AND homeVisitDate < :endExclusive LIMIT 1)
            OR EXISTS(SELECT 1 FROM LEPROSY_FOLLOW_UP WHERE createdBy = :userName
                   AND createdDate >= :startInclusive AND createdDate < :endExclusive LIMIT 1)
        )
        """
    )
    suspend fun hasDiseaseControlActivity(
        userName: String,
        startInclusive: Long,
        endExclusive: Long
    ): Boolean

    // ─── Debug seeder support ─────────────────────────────────────────────────
    // Production code never deletes badge data (awards are permanent by product
    // rule). These exist ONLY for the debug demo seeder, which fabricates timelines
    // and must reset between scenarios. Call sites are gated on BuildConfig.DEBUG.

    @Query("DELETE FROM BADGE_AWARD WHERE userId = :userId")
    suspend fun debugClearAwards(userId: Int)

    @Query("DELETE FROM BADGE_STREAK_WINDOW WHERE userId = :userId")
    suspend fun debugClearWindows(userId: Int)

    @Query("DELETE FROM BADGE_OBSERVATION WHERE userId = :userId")
    suspend fun debugClearObservations(userId: Int)

    /**
     * Debug-only: Complete Worker is driven by CLINICAL tables, which badges must
     * never write to. So instead of fabricating clinical rows, the demo seeds the
     * AWARD directly. This is the one badge whose seeded demo therefore does NOT
     * exercise the real evaluator — call that out when demoing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun debugInsertAward(award: BadgeAwardCache): Long

    @Query("DELETE FROM BADGE_MONTH_CLAIM WHERE userId = :userId")
    suspend fun debugClearMonthClaims(userId: Int)

    // ─── Atomic evaluation commit ─────────────────────────────────────────────

    /**
     * One evaluation = one transaction: the closed windows and any tiers they earned
     * land together or not at all, so a crash can never award without recording the
     * window that justified it (or vice versa).
     */
    @Transaction
    suspend fun commitEvaluation(
        windows: List<BadgeStreakWindowCache>,
        awards: List<BadgeAwardCache>
    ): List<Long> {
        if (windows.isNotEmpty()) upsertWindows(windows)
        return awards.map { insertAward(it) }
    }
}
