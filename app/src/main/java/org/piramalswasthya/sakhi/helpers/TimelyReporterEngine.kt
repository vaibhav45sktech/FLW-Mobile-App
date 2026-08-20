package org.piramalswasthya.sakhi.helpers

/**
 * Pure, deterministic month-streak engine for the Timely Reporter badge. No
 * Android, no clock, no I/O — every input is passed in, so the locked product
 * rules are unit-testable on the JVM and replaying the same evidence always
 * produces the same result (which is what keeps award writes idempotent).
 *
 * Locked product rules (2026-08-17):
 *  - "Monthly incentive form submitted on time" = the app's monthly CLAIM on the
 *    Monthly Claim Summary screen. That is the only monthly submission the app
 *    has; there is no separate incentive form.
 *  - Deadline: the 5th of the FOLLOWING month, inclusive. A claim for August must
 *    land on or before 5 September.
 *  - Timeliness is judged on the ORIGINAL submission date, not on a re-claim
 *    after a supervisor rejection (the caller supplies the first-seen date).
 *  - NO grace period. One late or missed month ends the run.
 *  - A month with no incentive records at all is SUSPENDED: she had nothing to
 *    claim, so it neither earns credit nor breaks the run. Quiet months are never
 *    framed as failure.
 *  - Bad data (claimed but the date is missing/unparseable) also SUSPENDS rather
 *    than punishing — the badge never penalises her for a server gap.
 *  - Tiers are permanent; the engine only emits tiers above the highest already
 *    earned, and the DAO's INSERT-IGNORE is the second line of defence.
 */
object TimelyReporterEngine {

    /** Thresholds in one place so a future Remote Config override is one call. */
    data class TimelyReporterConfig(
        /** ON TIME months needed per tier, ascending. */
        val tierThresholds: List<Int> = listOf(2, 4, 6, 12),
        /** Day of the FOLLOWING month by which the claim must land, inclusive. */
        val deadlineDayOfNextMonth: Int = 5,
    )

    /** Outcome of a single calendar month. */
    enum class MonthState {
        /** Claimed on or before the deadline — counts toward the tiers. */
        ON_TIME,

        /** Claimed, but after the deadline — ends the run. */
        LATE,

        /** Incentive work existed but was never claimed — ends the run. */
        MISSED,

        /** Nothing to claim, or unusable data — neither counts nor breaks. */
        SUSPENDED,
    }

    /**
     * One month of evidence, already reduced to the facts the engine needs.
     *
     * @param yearMonth calendar month as yyyyMM (e.g. 202608).
     * @param hasIncentiveRecords whether ANY incentive record exists for the month.
     * @param isClaimed whether the month's records are marked claimed.
     * @param claimedAtMillis the ORIGINAL claim timestamp; null when unknown or
     *   unparseable, which the engine treats as unusable rather than late.
     * @param deadlineMillis last instant that still counts as on time, supplied by
     *   the caller so all calendar arithmetic stays outside this pure engine.
     */
    data class MonthFacts(
        val yearMonth: Int,
        val hasIncentiveRecords: Boolean,
        val isClaimed: Boolean,
        val claimedAtMillis: Long?,
        val deadlineMillis: Long,
    )

    data class EvaluatedMonth(
        val yearMonth: Int,
        val state: MonthState,
        /** ON_TIME months accumulated in the live run AFTER this month. */
        val streakAfter: Int,
    )

    data class TierAward(
        val tier: Int,
        /** The month whose evaluation crossed the threshold (yyyyMM). */
        val earnedForYearMonth: Int,
    )

    data class Result(
        val months: List<EvaluatedMonth>,
        val tierAwards: List<TierAward>,
        /** Consecutive ON_TIME months at the end of the evaluated range. */
        val currentStreak: Int,
        /** ON_TIME months needed for the next tier, or null once all are earned. */
        val nextThreshold: Int?,
    )

    /**
     * Evaluates [months] in chronological order and returns every tier newly
     * crossed above [alreadyEarnedMaxTier].
     *
     * The caller passes only months that are fully closed (i.e. whose deadline has
     * already passed) — an in-flight month must not be judged, or a claim still
     * legitimately pending would read as MISSED.
     */
    fun evaluate(
        months: List<MonthFacts>,
        alreadyEarnedMaxTier: Int,
        config: TimelyReporterConfig = TimelyReporterConfig(),
    ): Result {
        val evaluated = mutableListOf<EvaluatedMonth>()
        val awards = mutableListOf<TierAward>()
        var streak = 0
        var highestTier = alreadyEarnedMaxTier

        months.sortedBy { it.yearMonth }.forEach { month ->
            val state = classify(month)
            when (state) {
                MonthState.ON_TIME -> {
                    streak += 1
                    val tierIndex = config.tierThresholds.indexOf(streak)
                    if (tierIndex >= 0) {
                        val tier = tierIndex + 1
                        if (tier > highestTier) {
                            awards += TierAward(tier, month.yearMonth)
                            highestTier = tier
                        }
                    }
                }
                // No grace: a late or missed month ends the run outright.
                MonthState.LATE, MonthState.MISSED -> streak = 0
                // Neutral: the run neither advances nor breaks.
                MonthState.SUSPENDED -> Unit
            }
            evaluated += EvaluatedMonth(month.yearMonth, state, streak)
        }

        return Result(
            months = evaluated,
            tierAwards = awards,
            currentStreak = streak,
            nextThreshold = config.tierThresholds.firstOrNull { it > streak },
        )
    }

    private fun classify(month: MonthFacts): MonthState = when {
        // Nothing was claimable, so there is nothing to be late for.
        !month.hasIncentiveRecords -> MonthState.SUSPENDED
        !month.isClaimed -> MonthState.MISSED
        // Claimed, but the server gave us no usable date: refuse to guess, and
        // refuse to punish. Neutral is the only honest reading.
        month.claimedAtMillis == null -> MonthState.SUSPENDED
        month.claimedAtMillis <= month.deadlineMillis -> MonthState.ON_TIME
        else -> MonthState.LATE
    }
}
