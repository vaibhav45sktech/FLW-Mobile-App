package org.piramalswasthya.sakhi.helpers

import org.piramalswasthya.sakhi.model.BadgeWindowState

/**
 * Pure, deterministic streak engine for the Steady Syncer badge. No Android, no
 * clock, no I/O — everything it knows arrives as input, so every product rule is
 * unit-testable on the JVM and a replay of the same evidence always produces the
 * same result (which is what makes award writes idempotent).
 *
 * Locked product rules implemented here (B1 spec, 2026-08-11):
 *  - A window ADVANCEs only on a QUALIFYING MANUAL sync: the ASHA tapped Sync
 *    Records and ≥1 record uploaded (partial success counts) OR the app confirmed
 *    nothing was pending. Passive zero backlog never advances.
 *  - Windows are 7 consecutive days anchored to the run start (no calendar weeks,
 *    no locale/timezone bucketing). A run starts on the day of the first
 *    qualifying sync. Tier credit lands only when a window CLOSES.
 *  - Grace: [SteadySyncerConfig.graceAllowance] windows per tier journey, spent
 *    only when backlog existed without a qualifying sync, restored to the full
 *    allowance the moment a tier is earned. Grace protects the run; it never earns.
 *  - No sync + no backlog (or no evidence at all) = SUSPENDED: nothing earned,
 *    nothing spent. [SteadySyncerConfig.suspendedCap] consecutive SUSPENDED
 *    windows close the run — quiet periods cannot pause a streak forever.
 *  - badges_enabled off = FROZEN: excluded from tiers, grace AND the suspension
 *    counter. Time simply stops.
 *  - Tiers are permanent. The engine only emits tiers above [alreadyEarnedMaxTier];
 *    the DAO's INSERT-IGNORE is the second line of defence.
 */
object SteadySyncerStreakEngine {

    /** All thresholds in one place so a future Remote Config override is one call. */
    data class SteadySyncerConfig(
        /** ADVANCE windows needed per tier, ascending. */
        val tierThresholds: List<Int> = listOf(4, 8, 16, 26),
        /** GRACE windows available per tier journey (restored on tier-up). */
        val graceAllowance: Int = 2,
        /** Consecutive SUSPENDED windows that close the run. */
        val suspendedCap: Int = 4,
        /** Window length in days. */
        val windowDays: Long = 7,
    )

    /** One day of evidence, already reduced to the facts the engine needs. */
    data class DayFacts(
        val day: Long,
        val gateEnabled: Boolean,
        val qualifyingSync: Boolean,
        val backlogNonzeroSeen: Boolean,
    )

    /** Where an in-progress run stands after the last closed window. */
    data class RunState(
        val runId: String,
        /** Epoch day the run's window grid is anchored to. */
        val runStartDay: Long,
        /** 1-based index of the NEXT window to close. */
        val nextWindowIndex: Int,
        val advanceCount: Int,
        val graceLeft: Int,
        val consecutiveSuspended: Int,
    )

    data class ClosedWindow(
        val runId: String,
        val windowIndex: Int,
        /** Inclusive epoch day the window starts on. */
        val startDay: Long,
        /** Exclusive epoch day the window ends before. */
        val endDay: Long,
        val state: BadgeWindowState,
        val graceLeftAfter: Int,
        val advanceCountAfter: Int,
    )

    data class TierAward(
        val tier: Int,
        /** Epoch day the tier was earned (the closing day of its window). */
        val earnedOnDay: Long,
        val runId: String,
    )

    data class Result(
        val closedWindows: List<ClosedWindow>,
        val tierAwards: List<TierAward>,
        /** Null when no run is live (never started, or closed with no restart yet). */
        val runState: RunState?,
    )

    /**
     * Rolls the streak forward from [priorRun] using [observations], closing every
     * fully-elapsed window (endDay <= [todayDay] — today's own window stays open).
     *
     * [observations] may contain days from any range; the engine reads only what
     * each window needs. Days with no row at all are treated as
     * no-sync/no-backlog/gate-on — which classifies as SUSPENDED, so a phone that
     * was off simply pauses (and eventually closes) rather than breaking anything.
     *
     * [newRunId] is injected so production can mint UUIDs while tests stay
     * deterministic.
     */
    fun evaluate(
        priorRun: RunState?,
        observations: List<DayFacts>,
        todayDay: Long,
        alreadyEarnedMaxTier: Int,
        config: SteadySyncerConfig = SteadySyncerConfig(),
        newRunId: (Int) -> String,
    ): Result {
        val factsByDay = observations.associateBy { it.day }
        val closed = mutableListOf<ClosedWindow>()
        val awards = mutableListOf<TierAward>()
        var mintedRuns = 0
        var highestTier = alreadyEarnedMaxTier

        // No live run: a run can only begin at a qualifying sync.
        var run = priorRun ?: startRunAtNextQualifyingSync(
            observations, fromDay = Long.MIN_VALUE, todayDay, config
        ) { newRunId(mintedRuns++) } ?: return Result(emptyList(), emptyList(), null)

        while (true) {
            val startDay = run.runStartDay + (run.nextWindowIndex - 1) * config.windowDays
            val endDay = startDay + config.windowDays
            if (endDay > todayDay) break // window still open — nothing more to close

            val windowDays = (startDay until endDay).mapNotNull { factsByDay[it] }
            val anyQualifying = windowDays.any { it.qualifyingSync }
            val anyGateOff = windowDays.any { !it.gateEnabled }
            val anyBacklog = windowDays.any { it.backlogNonzeroSeen }

            when {
                // Evidence of the rewarded action always wins.
                anyQualifying -> {
                    val advance = run.advanceCount + 1
                    var graceLeft = run.graceLeft
                    val tierIndex = config.tierThresholds.indexOf(advance)
                    if (tierIndex >= 0) {
                        val tier = tierIndex + 1
                        if (tier > highestTier) {
                            awards += TierAward(tier, earnedOnDay = endDay, runId = run.runId)
                            highestTier = tier
                        }
                        // Tier earned: the allowance for the next journey is restored
                        // whether or not this tier was newly awarded (replay-safe).
                        graceLeft = config.graceAllowance
                    }
                    closed += ClosedWindow(
                        run.runId, run.nextWindowIndex, startDay, endDay,
                        BadgeWindowState.ADVANCE, graceLeft, advance
                    )
                    run = run.copy(
                        nextWindowIndex = run.nextWindowIndex + 1,
                        advanceCount = advance,
                        graceLeft = graceLeft,
                        consecutiveSuspended = 0,
                    )
                }

                // Gate off (and no qualifying evidence): time stops. Not a tier,
                // not grace, not suspension. Checked BEFORE backlog so a gated-off
                // week can never consume the allowance.
                anyGateOff -> {
                    closed += ClosedWindow(
                        run.runId, run.nextWindowIndex, startDay, endDay,
                        BadgeWindowState.FROZEN, run.graceLeft, run.advanceCount
                    )
                    run = run.copy(nextWindowIndex = run.nextWindowIndex + 1)
                }

                // Work was waiting and she did not sync: grace if any remains,
                // otherwise the run breaks.
                anyBacklog -> {
                    if (run.graceLeft > 0) {
                        val graceLeft = run.graceLeft - 1
                        closed += ClosedWindow(
                            run.runId, run.nextWindowIndex, startDay, endDay,
                            BadgeWindowState.GRACE, graceLeft, run.advanceCount
                        )
                        run = run.copy(
                            nextWindowIndex = run.nextWindowIndex + 1,
                            graceLeft = graceLeft,
                            consecutiveSuspended = 0,
                        )
                    } else {
                        closed += ClosedWindow(
                            run.runId, run.nextWindowIndex, startDay, endDay,
                            BadgeWindowState.BREAK, 0, run.advanceCount
                        )
                        val next = startRunAtNextQualifyingSync(
                            observations, fromDay = endDay, todayDay, config
                        ) { newRunId(mintedRuns++) }
                            ?: return Result(closed, awards, null)
                        run = next
                    }
                }

                // Nothing to sync and nothing synced (or no evidence at all):
                // neutral — until the cap says the run is over.
                else -> {
                    val consecutive = run.consecutiveSuspended + 1
                    closed += ClosedWindow(
                        run.runId, run.nextWindowIndex, startDay, endDay,
                        BadgeWindowState.SUSPENDED, run.graceLeft, run.advanceCount
                    )
                    if (consecutive >= config.suspendedCap) {
                        // Mechanically a break, narratively never framed as failure.
                        val next = startRunAtNextQualifyingSync(
                            observations, fromDay = endDay, todayDay, config
                        ) { newRunId(mintedRuns++) }
                            ?: return Result(closed, awards, null)
                        run = next
                    } else {
                        run = run.copy(
                            nextWindowIndex = run.nextWindowIndex + 1,
                            consecutiveSuspended = consecutive,
                        )
                    }
                }
            }
        }
        return Result(closed, awards, run)
    }

    /**
     * A new run anchors on the first qualifying-sync day at/after [fromDay] that has
     * already happened (day <= [todayDay]) — a streak begins with the action, never
     * with waiting.
     */
    private fun startRunAtNextQualifyingSync(
        observations: List<DayFacts>,
        fromDay: Long,
        todayDay: Long,
        config: SteadySyncerConfig,
        mintRunId: () -> String,
    ): RunState? {
        val anchor = observations
            .filter { it.qualifyingSync && it.day >= fromDay && it.day <= todayDay }
            .minByOrNull { it.day }
            ?: return null
        return RunState(
            runId = mintRunId(),
            runStartDay = anchor.day,
            nextWindowIndex = 1,
            advanceCount = 0,
            graceLeft = config.graceAllowance,
            consecutiveSuspended = 0,
        )
    }
}
