package org.piramalswasthya.sakhi.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.piramalswasthya.sakhi.helpers.TimelyReporterEngine.MonthFacts
import org.piramalswasthya.sakhi.helpers.TimelyReporterEngine.MonthState

/**
 * The locked Timely Reporter spec, executable. One test per product rule —
 * if a rule changes, exactly one test should break.
 *
 * Deadline is passed in as millis (the engine stays free of calendar maths), so
 * these tests use a simple synthetic scale: deadline = 1000 for every month, and
 * claim times sit either side of it.
 */
class TimelyReporterEngineTest {

    private val deadline = 1_000L

    private fun onTime(ym: Int, at: Long = 900L) = MonthFacts(
        yearMonth = ym, hasIncentiveRecords = true, isClaimed = true,
        claimedAtMillis = at, deadlineMillis = deadline
    )

    private fun late(ym: Int, at: Long = 1_100L) = MonthFacts(
        yearMonth = ym, hasIncentiveRecords = true, isClaimed = true,
        claimedAtMillis = at, deadlineMillis = deadline
    )

    private fun neverClaimed(ym: Int) = MonthFacts(
        yearMonth = ym, hasIncentiveRecords = true, isClaimed = false,
        claimedAtMillis = null, deadlineMillis = deadline
    )

    private fun noWork(ym: Int) = MonthFacts(
        yearMonth = ym, hasIncentiveRecords = false, isClaimed = false,
        claimedAtMillis = null, deadlineMillis = deadline
    )

    private fun claimedButNoDate(ym: Int) = MonthFacts(
        yearMonth = ym, hasIncentiveRecords = true, isClaimed = true,
        claimedAtMillis = null, deadlineMillis = deadline
    )

    private fun evaluate(months: List<MonthFacts>, earned: Int = 0) =
        TimelyReporterEngine.evaluate(months, earned)

    private fun states(months: List<MonthFacts>) =
        evaluate(months).months.map { it.state }

    // ── Classification ───────────────────────────────────────────────────────

    @Test
    fun `claim before the deadline is ON TIME`() {
        assertEquals(listOf(MonthState.ON_TIME), states(listOf(onTime(202601))))
    }

    @Test
    fun `claim exactly on the deadline instant is ON TIME - boundary is inclusive`() {
        assertEquals(
            listOf(MonthState.ON_TIME),
            states(listOf(onTime(202601, at = deadline)))
        )
    }

    @Test
    fun `one millisecond past the deadline is LATE`() {
        assertEquals(
            listOf(MonthState.LATE),
            states(listOf(late(202601, at = deadline + 1)))
        )
    }

    @Test
    fun `records exist but never claimed is MISSED, not LATE`() {
        assertEquals(listOf(MonthState.MISSED), states(listOf(neverClaimed(202601))))
    }

    @Test
    fun `month with no incentive records is SUSPENDED - nothing to be late for`() {
        assertEquals(listOf(MonthState.SUSPENDED), states(listOf(noWork(202601))))
    }

    @Test
    fun `claimed but date unparseable is SUSPENDED - never punish on bad data`() {
        assertEquals(listOf(MonthState.SUSPENDED), states(listOf(claimedButNoDate(202601))))
    }

    // ── Streak behaviour ─────────────────────────────────────────────────────

    @Test
    fun `consecutive on-time months build the streak`() {
        val r = evaluate(listOf(onTime(202601), onTime(202602), onTime(202603)))
        assertEquals(3, r.currentStreak)
    }

    @Test
    fun `a late month resets the streak to zero - there is no grace`() {
        val r = evaluate(listOf(onTime(202601), onTime(202602), late(202603)))
        assertEquals(0, r.currentStreak)
    }

    @Test
    fun `a missed month resets the streak to zero`() {
        val r = evaluate(listOf(onTime(202601), neverClaimed(202602)))
        assertEquals(0, r.currentStreak)
    }

    @Test
    fun `a quiet month neither advances nor breaks the streak`() {
        // Jan on time, Feb no work at all, Mar on time -> streak of 2, unbroken.
        val r = evaluate(listOf(onTime(202601), noWork(202602), onTime(202603)))
        assertEquals(2, r.currentStreak)
        assertEquals(
            listOf(MonthState.ON_TIME, MonthState.SUSPENDED, MonthState.ON_TIME),
            r.months.map { it.state }
        )
    }

    @Test
    fun `streak rebuilds from zero after a break`() {
        val r = evaluate(
            listOf(onTime(202601), late(202602), onTime(202603), onTime(202604))
        )
        assertEquals(2, r.currentStreak)
    }

    // ── Tier ladder ──────────────────────────────────────────────────────────

    @Test
    fun `tiers land exactly at 2 4 6 and 12 on-time months`() {
        val months = (1..12).map { onTime(202600 + it) }
        val r = evaluate(months)
        assertEquals(listOf(1, 2, 3, 4), r.tierAwards.map { it.tier })
        assertEquals(
            listOf(202602, 202604, 202606, 202612),
            r.tierAwards.map { it.earnedForYearMonth }
        )
    }

    @Test
    fun `no tier before the second on-time month`() {
        assertTrue(evaluate(listOf(onTime(202601))).tierAwards.isEmpty())
    }

    @Test
    fun `already earned tiers are not re-awarded on replay`() {
        val months = (1..4).map { onTime(202600 + it) }
        assertEquals(listOf(2), evaluate(months, earned = 1).tierAwards.map { it.tier })
    }

    @Test
    fun `replaying identical evidence yields identical output`() {
        val months = listOf(onTime(202601), late(202602), onTime(202603), onTime(202604))
        assertEquals(evaluate(months), evaluate(months))
    }

    @Test
    fun `a break after tier one keeps the tier but restarts the count`() {
        // Tier 1 at Feb, then a late month, then one on-time month.
        val r = evaluate(listOf(onTime(202601), onTime(202602), late(202603), onTime(202604)))
        assertEquals(listOf(1), r.tierAwards.map { it.tier })
        assertEquals(1, r.currentStreak)
    }

    // ── Ordering and next-threshold ──────────────────────────────────────────

    @Test
    fun `months are evaluated chronologically regardless of input order`() {
        val jumbled = listOf(onTime(202603), onTime(202601), late(202602))
        val r = evaluate(jumbled)
        assertEquals(listOf(202601, 202602, 202603), r.months.map { it.yearMonth })
        assertEquals(1, r.currentStreak) // Mar only, Feb broke it
    }

    @Test
    fun `next threshold reflects the live streak`() {
        assertEquals(2, evaluate(listOf(onTime(202601))).nextThreshold)
        assertEquals(4, evaluate(listOf(onTime(202601), onTime(202602))).nextThreshold)
    }

    @Test
    fun `next threshold is null once every tier is earned`() {
        val months = (1..12).map { onTime(202600 + it) }
        assertNull(evaluate(months).nextThreshold)
    }

    @Test
    fun `empty input produces no awards and a zero streak`() {
        val r = evaluate(emptyList())
        assertTrue(r.months.isEmpty())
        assertTrue(r.tierAwards.isEmpty())
        assertEquals(0, r.currentStreak)
    }

    // ── The rejection rule ───────────────────────────────────────────────────

    @Test
    fun `original claim date wins - a later re-claim cannot turn an on-time month late`() {
        // Caller supplies the FIRST-SEEN date from BADGE_MONTH_CLAIM; even though
        // the server's calimedDate may now read a post-deadline re-claim, the
        // month must still count as on time.
        val original = onTime(202601, at = 900L)
        assertEquals(listOf(MonthState.ON_TIME), states(listOf(original)))
    }
}
