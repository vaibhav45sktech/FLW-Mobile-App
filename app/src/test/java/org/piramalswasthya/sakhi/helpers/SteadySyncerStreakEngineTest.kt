package org.piramalswasthya.sakhi.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.piramalswasthya.sakhi.helpers.SteadySyncerStreakEngine.DayFacts
import org.piramalswasthya.sakhi.helpers.SteadySyncerStreakEngine.SteadySyncerConfig
import org.piramalswasthya.sakhi.model.BadgeWindowState

/**
 * The locked B1 product spec, executable. Each test is one rule or one loophole
 * from the adversarial review — if a rule changes, exactly one test should break.
 *
 * Days are small epoch-day integers; window N of a run started on day D spans
 * [D + 7(N-1), D + 7N). A window closes once its end day has fully passed.
 */
class SteadySyncerStreakEngineTest {

    private val runIds = { i: Int -> "run-$i" }

    private fun sync(day: Long) =
        DayFacts(day, gateEnabled = true, qualifyingSync = true, backlogNonzeroSeen = false)

    private fun backlogIdle(day: Long) =
        DayFacts(day, gateEnabled = true, qualifyingSync = false, backlogNonzeroSeen = true)

    private fun zeroBacklogIdle(day: Long) =
        DayFacts(day, gateEnabled = true, qualifyingSync = false, backlogNonzeroSeen = false)

    private fun gatedOff(day: Long, backlog: Boolean = false) =
        DayFacts(day, gateEnabled = false, qualifyingSync = false, backlogNonzeroSeen = backlog)

    private fun evaluate(
        observations: List<DayFacts>,
        today: Long,
        prior: SteadySyncerStreakEngine.RunState? = null,
        earnedMaxTier: Int = 0,
        config: SteadySyncerConfig = SteadySyncerConfig(),
    ) = SteadySyncerStreakEngine.evaluate(
        prior, observations, today, earnedMaxTier, config, runIds
    )

    // ── Run creation ─────────────────────────────────────────────────────────

    @Test
    fun `no qualifying sync ever - no run exists`() {
        val result = evaluate(listOf(backlogIdle(0), zeroBacklogIdle(3)), today = 30)
        assertNull(result.runState)
        assertTrue(result.closedWindows.isEmpty())
        assertTrue(result.tierAwards.isEmpty())
    }

    @Test
    fun `run anchors on the FIRST qualifying sync day, not on idle days before it`() {
        val result = evaluate(listOf(zeroBacklogIdle(1), sync(5)), today = 5)
        assertEquals(5L, result.runState!!.runStartDay)
        assertEquals("run-0", result.runState!!.runId)
    }

    // ── ADVANCE ──────────────────────────────────────────────────────────────

    @Test
    fun `one qualifying sync per window advances the streak`() {
        val result = evaluate(listOf(sync(0), sync(7), sync(14)), today = 21)
        assertEquals(3, result.closedWindows.size)
        assertTrue(result.closedWindows.all { it.state == BadgeWindowState.ADVANCE })
        assertEquals(3, result.runState!!.advanceCount)
    }

    @Test
    fun `window still in progress does not close and does not credit`() {
        val result = evaluate(listOf(sync(0)), today = 6)
        assertTrue(result.closedWindows.isEmpty())
        assertEquals(1, result.runState!!.nextWindowIndex)
    }

    @Test
    fun `nothing-pending manual sync advances exactly like an upload`() {
        // qualifyingSync=true with zero backlog: she tapped, app confirmed clean.
        val facts = DayFacts(0, gateEnabled = true, qualifyingSync = true, backlogNonzeroSeen = false)
        val result = evaluate(listOf(facts), today = 7)
        assertEquals(BadgeWindowState.ADVANCE, result.closedWindows.single().state)
    }

    // ── Loophole 1: zero backlog must never earn ─────────────────────────────

    @Test
    fun `zero backlog without a manual tap is SUSPENDED, never ADVANCE`() {
        val result = evaluate(listOf(sync(0), zeroBacklogIdle(8)), today = 14)
        assertEquals(
            listOf(BadgeWindowState.ADVANCE, BadgeWindowState.SUSPENDED),
            result.closedWindows.map { it.state }
        )
        assertEquals(1, result.runState!!.advanceCount) // week 2 earned nothing
    }

    @Test
    fun `days with no observation at all are SUSPENDED, not a break`() {
        // Phone off for a week: no rows. Unknown must pause, never punish.
        val result = evaluate(listOf(sync(0), sync(15)), today = 21)
        assertEquals(
            listOf(BadgeWindowState.ADVANCE, BadgeWindowState.SUSPENDED, BadgeWindowState.ADVANCE),
            result.closedWindows.map { it.state }
        )
    }

    // ── Suspension cap ───────────────────────────────────────────────────────

    @Test
    fun `four consecutive suspended windows close the run`() {
        val result = evaluate(listOf(sync(0)), today = 36) // W1 advance, W2-W5 empty
        assertEquals(5, result.closedWindows.size)
        assertEquals(BadgeWindowState.SUSPENDED, result.closedWindows.last().state)
        assertNull(result.runState) // run closed, nothing to restart on
    }

    @Test
    fun `an advance resets the consecutive suspended counter`() {
        // 3 suspended, then a sync: counter must reset, run must survive well past
        // where 4 unbroken suspensions would have closed it.
        val result = evaluate(
            listOf(sync(0), sync(29), sync(36)), today = 43
        ) // W1 adv, W2-4 susp(3), W5 adv, W6 adv
        assertEquals(6, result.closedWindows.size)
        assertNotEquals(null, result.runState)
        assertEquals(3, result.runState!!.advanceCount)
    }

    @Test
    fun `after a suspension close, the next qualifying sync starts a fresh run`() {
        val result = evaluate(listOf(sync(0), sync(40)), today = 47)
        // W1 adv; W2-5 suspended -> close; new run anchors day 40 and closes W1 adv.
        assertEquals("run-1", result.runState!!.runId)
        assertEquals(40L, result.runState!!.runStartDay)
        assertEquals(1, result.runState!!.advanceCount)
        assertEquals(
            SteadySyncerConfig().graceAllowance,
            result.runState!!.graceLeft // fresh allowance, fresh narrative
        )
    }

    // ── Grace ────────────────────────────────────────────────────────────────

    @Test
    fun `backlog without sync consumes grace and earns nothing`() {
        val result = evaluate(listOf(sync(0), backlogIdle(8)), today = 14)
        val graceWindow = result.closedWindows[1]
        assertEquals(BadgeWindowState.GRACE, graceWindow.state)
        assertEquals(1, graceWindow.graceLeftAfter)
        assertEquals(1, graceWindow.advanceCountAfter) // unchanged — grace never earns
    }

    @Test
    fun `third unprotected miss breaks the run - allowance is two`() {
        val result = evaluate(
            listOf(sync(0), backlogIdle(8), backlogIdle(15), backlogIdle(22)),
            today = 28
        )
        assertEquals(
            listOf(
                BadgeWindowState.ADVANCE, BadgeWindowState.GRACE,
                BadgeWindowState.GRACE, BadgeWindowState.BREAK
            ),
            result.closedWindows.map { it.state }
        )
        assertNull(result.runState)
    }

    @Test
    fun `after a break, the next qualifying sync starts a new run in the same pass`() {
        val result = evaluate(
            listOf(sync(0), backlogIdle(8), backlogIdle(15), backlogIdle(22), sync(30)),
            today = 37
        )
        val last = result.closedWindows.last()
        assertEquals(BadgeWindowState.ADVANCE, last.state)
        assertEquals("run-1", last.runId)
        assertEquals(1, last.windowIndex) // windows re-anchor: index restarts at 1
        assertEquals(30L, result.runState!!.runStartDay)
    }

    // ── The product's own worked example ─────────────────────────────────────

    @Test
    fun `mentor example - grace week then tier one on the fifth week`() {
        // W1 sync, W2 sync, W3 pending-no-sync (grace), W4 sync, W5 sync -> Tier 1.
        val result = evaluate(
            listOf(sync(0), sync(7), backlogIdle(16), sync(21), sync(28)),
            today = 35
        )
        assertEquals(
            listOf(
                BadgeWindowState.ADVANCE, BadgeWindowState.ADVANCE, BadgeWindowState.GRACE,
                BadgeWindowState.ADVANCE, BadgeWindowState.ADVANCE
            ),
            result.closedWindows.map { it.state }
        )
        val award = result.tierAwards.single()
        assertEquals(1, award.tier)
        assertEquals(35L, award.earnedOnDay)
        // Tier earned -> allowance restored to two for the journey to tier 2.
        assertEquals(2, result.runState!!.graceLeft)
    }

    // ── Tier ladder ──────────────────────────────────────────────────────────

    @Test
    fun `tiers land exactly at 4 8 16 and 26 advances`() {
        val syncs = (0 until 26).map { sync(it * 7L) }
        val result = evaluate(syncs, today = 26 * 7L)
        assertEquals(listOf(1, 2, 3, 4), result.tierAwards.map { it.tier })
        assertEquals(
            listOf(4 * 7L, 8 * 7L, 16 * 7L, 26 * 7L),
            result.tierAwards.map { it.earnedOnDay }
        )
    }

    @Test
    fun `already earned tiers are not re-awarded on replay`() {
        val syncs = (0 until 8).map { sync(it * 7L) }
        val result = evaluate(syncs, today = 8 * 7L, earnedMaxTier = 1)
        assertEquals(listOf(2), result.tierAwards.map { it.tier })
    }

    @Test
    fun `replaying identical evidence yields identical output`() {
        val syncs = listOf(sync(0), sync(7), backlogIdle(16), sync(21), sync(28))
        val first = evaluate(syncs, today = 35)
        val second = evaluate(syncs, today = 35)
        assertEquals(first, second)
    }

    // ── FROZEN (Remote Config off) ───────────────────────────────────────────

    @Test
    fun `gated-off window is FROZEN and touches no counter`() {
        val result = evaluate(
            listOf(sync(0), gatedOff(8), sync(14)), today = 21
        )
        val frozen = result.closedWindows[1]
        assertEquals(BadgeWindowState.FROZEN, frozen.state)
        assertEquals(2, frozen.graceLeftAfter)      // grace untouched
        assertEquals(1, frozen.advanceCountAfter)   // progress untouched
        assertEquals(2, result.runState!!.advanceCount)
    }

    @Test
    fun `frozen window does not consume grace even when backlog existed`() {
        // Gate off AND records pending: freezing wins — she cannot be punished
        // while the feature was disabled.
        val result = evaluate(
            listOf(sync(0), gatedOff(8, backlog = true)), today = 14
        )
        assertEquals(BadgeWindowState.FROZEN, result.closedWindows[1].state)
        assertEquals(2, result.closedWindows[1].graceLeftAfter)
    }

    @Test
    fun `frozen does not count toward the suspension cap but does not reset it`() {
        // susp, susp, susp, FROZEN, susp -> the fourth suspension arrives at W6
        // and only then closes the run: frozen neither counted nor wiped the tally.
        val result = evaluate(
            listOf(sync(0), gatedOff(22)), today = 43
        ) // W1 adv, W2 susp, W3 susp, W4 frozen, W5 susp, W6 susp -> close
        assertEquals(
            listOf(
                BadgeWindowState.ADVANCE, BadgeWindowState.SUSPENDED,
                BadgeWindowState.SUSPENDED, BadgeWindowState.FROZEN,
                BadgeWindowState.SUSPENDED, BadgeWindowState.SUSPENDED
            ),
            result.closedWindows.map { it.state }
        )
        assertNull(result.runState)
    }

    @Test
    fun `a qualifying sync beats a partially gated window`() {
        // Gate flipped off some day in the window but she synced on another day:
        // positive evidence of the rewarded action always wins.
        val result = evaluate(
            listOf(sync(0), gatedOff(8), sync(12)), today = 14
        )
        assertEquals(BadgeWindowState.ADVANCE, result.closedWindows[1].state)
    }

    // ── Grace restoration on tier-up ─────────────────────────────────────────

    @Test
    fun `grace spent before a tier is restored when the tier lands`() {
        // Journey to tier 1 spends one grace; the moment tier 1 is earned the
        // allowance returns to 2 for the tier-2 journey.
        val result = evaluate(
            listOf(sync(0), backlogIdle(9), sync(14), sync(21), sync(28), sync(35)),
            today = 42
        ) // W2 grace; advances at W1,3,4,5,6 -> tier 1 on W5 close (4th advance)
        val tierWindow = result.closedWindows[4]
        assertEquals(4, tierWindow.advanceCountAfter)
        assertEquals(2, tierWindow.graceLeftAfter) // restored on the earning window
        assertEquals(1, result.tierAwards.single().tier)
    }

    @Test
    fun `unused grace does not stack above the allowance`() {
        // No grace used before tier 1: restoration must cap at 2, never 4.
        val syncs = (0 until 4).map { sync(it * 7L) }
        val result = evaluate(syncs, today = 28)
        assertEquals(2, result.closedWindows.last().graceLeftAfter)
    }

    // ── Window identity ──────────────────────────────────────────────────────

    @Test
    fun `windows are anchored to the run start, not to calendar weeks`() {
        // Run starts day 3; windows are [3,10), [10,17) — a day-9 sync belongs
        // to window 1, NOT to a Monday-anchored calendar week.
        val result = evaluate(listOf(sync(3), sync(9), sync(12)), today = 17)
        assertEquals(2, result.closedWindows.size)
        assertEquals(3L, result.closedWindows[0].startDay)
        assertEquals(10L, result.closedWindows[0].endDay)
        assertEquals(2, result.runState!!.advanceCount)
    }
}
