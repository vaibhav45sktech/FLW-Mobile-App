package org.piramalswasthya.sakhi.helpers

/**
 * Pure, deterministic engine for the Complete Worker badge. No Android, no clock,
 * no I/O — every input is passed in, so the locked product rules are unit-testable
 * on the JVM and replaying the same evidence always produces the same result.
 * That determinism is what makes the award writes idempotent and the badge safe to
 * re-evaluate on every app open forever.
 *
 * Locked product rules (2026-08-19):
 *  - Trigger: active in **at least 3** of the 5 health domains within one quarter.
 *    (Lowered from the note's 4 by product decision, because Disease Control is
 *    under-detected — see [HealthDomain.DISEASE_CONTROL].)
 *  - Timeframe: a strict CALENDAR quarter — Q1 Jan-Mar, Q2 Apr-Jun, Q3 Jul-Sep,
 *    Q4 Oct-Dec. Not a rolling 90-day window.
 *  - Re-earned fresh each quarter: each quarter is its own award, keyed by that
 *    quarter, so earning Q3 never blocks earning Q4.
 *  - Earned the moment the 3rd domain appears — she is not made to wait for the
 *    quarter to close.
 *  - Permanent: once a quarter is earned it is never revoked, even if the
 *    underlying clinical records are later edited or deleted. The engine only
 *    ever emits quarters NOT already in [alreadyEarnedQuarters]; the DAO's
 *    INSERT-IGNORE on (userId, badgeId, tier, occurrenceKey) is the second line
 *    of defence.
 */
object CompleteWorkerEngine {

    /**
     * The five health domains from the concept note.
     *
     * [DISEASE_CONTROL] is knowingly under-detected: of the disease screening
     * tables, only Leprosy actually populates an owner field, so malaria / AES /
     * filaria work cannot be attributed to a specific ASHA and therefore cannot
     * count. The failure direction is deliberate — she may be under-credited,
     * never wrongly credited.
     */
    enum class HealthDomain {
        CHILD_HEALTH,
        MATERNAL_HEALTH,
        IMMUNIZATION,
        FAMILY_PLANNING,
        DISEASE_CONTROL,
    }

    /** Thresholds in one place so a future Remote Config override is one call. */
    data class CompleteWorkerConfig(
        /** Distinct domains needed within a single calendar quarter. */
        val domainsRequired: Int = 3,
        /**
         * How many past quarters to look back over on top of the current one.
         * Bounds the first-launch retroactive sweep so a long-serving ASHA does
         * not receive a decade of awards at once, and bounds the query cost.
         */
        val lookbackQuarters: Int = 3,
    )

    /**
     * A calendar quarter. [year] is the full year; [quarter] is 1..4.
     *
     * Comparable so quarters sort chronologically, and [key] is the stable
     * award identity persisted as `occurrenceKey`.
     */
    data class Quarter(val year: Int, val quarter: Int) : Comparable<Quarter> {
        init {
            require(quarter in 1..4) { "quarter must be 1..4, was $quarter" }
        }

        /** Stable, sortable, human-readable award key, e.g. "2026Q3". */
        val key: String get() = "${year}Q$quarter"

        /** 0-based first month of this quarter (Calendar.JANUARY == 0). */
        val firstMonthIndex: Int get() = (quarter - 1) * 3

        fun previous(): Quarter =
            if (quarter == 1) Quarter(year - 1, 4) else Quarter(year, quarter - 1)

        override fun compareTo(other: Quarter): Int =
            compareValuesBy(this, other, { it.year }, { it.quarter })

        companion object {
            /** From a 0-based month index (Calendar.MONTH). */
            fun of(year: Int, monthIndexZeroBased: Int): Quarter =
                Quarter(year, (monthIndexZeroBased / 3) + 1)

            /** Parses a [key] like "2026Q3"; null when malformed. */
            fun fromKey(key: String?): Quarter? {
                if (key == null) return null
                val parts = key.split("Q")
                if (parts.size != 2) return null
                val year = parts[0].toIntOrNull() ?: return null
                val q = parts[1].toIntOrNull() ?: return null
                if (q !in 1..4) return null
                return Quarter(year, q)
            }
        }
    }

    /** Which domains had attributable activity in one quarter. */
    data class QuarterActivity(
        val quarter: Quarter,
        val activeDomains: Set<HealthDomain>,
    )

    data class QuarterAward(
        val quarter: Quarter,
        /** Domains active when it was earned — for copy and diagnostics. */
        val domainsActive: Int,
    )

    data class Result(
        /** Quarters newly earned by this evaluation, chronological. */
        val newAwards: List<QuarterAward>,
        /** Distinct domains active in the CURRENT quarter, for the progress ring. */
        val currentQuarterDomains: Int,
        /** Domains still needed this quarter, 0 once earned. */
        val domainsStillNeeded: Int,
        /** True when the current quarter is earned (already or just now). */
        val currentQuarterEarned: Boolean,
    )

    /**
     * Evaluates [activityByQuarter] and returns quarters newly crossing the
     * threshold.
     *
     * @param activityByQuarter one entry per quarter examined. Quarters absent
     *   from this list are simply not evaluated — absence is never treated as
     *   failure, because a quarter with no rows is indistinguishable from a
     *   quarter we did not query.
     * @param currentQuarter the quarter "now" falls in, used for the progress
     *   read-model.
     * @param alreadyEarnedQuarters award keys already persisted. Anything here is
     *   skipped, which is what makes repeated evaluation a no-op.
     */
    fun evaluate(
        activityByQuarter: List<QuarterActivity>,
        currentQuarter: Quarter,
        alreadyEarnedQuarters: Set<String>,
        config: CompleteWorkerConfig = CompleteWorkerConfig(),
    ): Result {
        val newAwards = activityByQuarter
            .asSequence()
            // Defensive: never award a quarter in the future. A device clock set
            // backwards could otherwise surface a quarter we should not judge yet.
            .filter { it.quarter <= currentQuarter }
            .filter { it.quarter.key !in alreadyEarnedQuarters }
            .filter { it.activeDomains.size >= config.domainsRequired }
            .map { QuarterAward(it.quarter, it.activeDomains.size) }
            .sortedBy { it.quarter }
            .toList()

        val currentDomains = activityByQuarter
            .firstOrNull { it.quarter == currentQuarter }
            ?.activeDomains
            ?.size
            ?: 0

        val currentEarned = currentQuarter.key in alreadyEarnedQuarters ||
                newAwards.any { it.quarter == currentQuarter }

        return Result(
            newAwards = newAwards,
            currentQuarterDomains = currentDomains,
            domainsStillNeeded =
                if (currentEarned) 0
                else (config.domainsRequired - currentDomains).coerceAtLeast(0),
            currentQuarterEarned = currentEarned,
        )
    }

    /**
     * The quarters an evaluation should examine: the current one plus
     * [CompleteWorkerConfig.lookbackQuarters] before it, newest last.
     */
    fun quartersToExamine(
        currentQuarter: Quarter,
        config: CompleteWorkerConfig = CompleteWorkerConfig(),
    ): List<Quarter> {
        val out = ArrayList<Quarter>(config.lookbackQuarters + 1)
        var q = currentQuarter
        repeat(config.lookbackQuarters + 1) {
            out.add(q)
            q = q.previous()
        }
        return out.reversed()
    }
}
