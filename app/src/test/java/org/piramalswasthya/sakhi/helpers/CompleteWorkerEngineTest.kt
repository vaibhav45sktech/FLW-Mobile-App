package org.piramalswasthya.sakhi.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine.HealthDomain
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine.Quarter
import org.piramalswasthya.sakhi.helpers.CompleteWorkerEngine.QuarterActivity

/**
 * The locked Complete Worker spec, executable. One test per product rule, plus a
 * block of adversarial cases for the "must not break in production" bar: replay,
 * out-of-order input, future quarters, year rollover, malformed keys.
 */
class CompleteWorkerEngineTest {

    private val CH = HealthDomain.CHILD_HEALTH
    private val MH = HealthDomain.MATERNAL_HEALTH
    private val IM = HealthDomain.IMMUNIZATION
    private val FP = HealthDomain.FAMILY_PLANNING
    private val DC = HealthDomain.DISEASE_CONTROL

    private val q3 = Quarter(2026, 3)

    private fun evaluate(
        activity: List<QuarterActivity>,
        current: Quarter = q3,
        earned: Set<String> = emptySet(),
    ) = CompleteWorkerEngine.evaluate(activity, current, earned)

    // ── Threshold: exactly 3 domains ─────────────────────────────────────────

    @Test
    fun `two domains earns nothing`() {
        val r = evaluate(listOf(QuarterActivity(q3, setOf(CH, MH))))
        assertTrue(r.newAwards.isEmpty())
        assertFalse(r.currentQuarterEarned)
        assertEquals(1, r.domainsStillNeeded)
    }

    @Test
    fun `exactly three domains earns the quarter`() {
        val r = evaluate(listOf(QuarterActivity(q3, setOf(CH, MH, IM))))
        assertEquals(1, r.newAwards.size)
        assertEquals(q3, r.newAwards.single().quarter)
        assertTrue(r.currentQuarterEarned)
        assertEquals(0, r.domainsStillNeeded)
    }

    @Test
    fun `more than three domains still earns exactly one award`() {
        val r = evaluate(listOf(QuarterActivity(q3, setOf(CH, MH, IM, FP, DC))))
        assertEquals(1, r.newAwards.size)
        assertEquals(5, r.newAwards.single().domainsActive)
    }

    @Test
    fun `zero domains earns nothing and needs all three`() {
        val r = evaluate(listOf(QuarterActivity(q3, emptySet())))
        assertTrue(r.newAwards.isEmpty())
        assertEquals(3, r.domainsStillNeeded)
    }

    @Test
    fun `any three domains qualify - disease control is not required`() {
        // Disease Control is under-detected, so a valid trio must never depend on it.
        val r = evaluate(listOf(QuarterActivity(q3, setOf(CH, IM, FP))))
        assertEquals(1, r.newAwards.size)
    }

    @Test
    fun `three domains including only-leprosy disease control still qualifies`() {
        val r = evaluate(listOf(QuarterActivity(q3, setOf(MH, FP, DC))))
        assertEquals(1, r.newAwards.size)
    }

    // ── Re-earned fresh each quarter ──────────────────────────────────────────

    @Test
    fun `each qualifying quarter is its own award`() {
        val q1 = Quarter(2026, 1)
        val q2 = Quarter(2026, 2)
        val r = evaluate(
            listOf(
                QuarterActivity(q1, setOf(CH, MH, IM)),
                QuarterActivity(q2, setOf(CH, MH, IM)),
                QuarterActivity(q3, setOf(CH, MH, IM)),
            )
        )
        assertEquals(listOf(q1, q2, q3), r.newAwards.map { it.quarter })
    }

    @Test
    fun `earning one quarter does not block the next`() {
        val r = evaluate(
            listOf(QuarterActivity(q3, setOf(CH, MH, IM))),
            earned = setOf("2026Q2"),
        )
        assertEquals(1, r.newAwards.size)
        assertEquals(q3, r.newAwards.single().quarter)
    }

    @Test
    fun `a quarter that fails the threshold does not block a later one`() {
        val q2 = Quarter(2026, 2)
        val r = evaluate(
            listOf(
                QuarterActivity(q2, setOf(CH)),           // fails
                QuarterActivity(q3, setOf(CH, MH, IM)),   // qualifies
            )
        )
        assertEquals(listOf(q3), r.newAwards.map { it.quarter })
    }

    // ── Never revoked, never double-awarded ──────────────────────────────────

    @Test
    fun `already earned quarter is never re-awarded`() {
        val r = evaluate(
            listOf(QuarterActivity(q3, setOf(CH, MH, IM, FP))),
            earned = setOf("2026Q3"),
        )
        assertTrue(r.newAwards.isEmpty())
        assertTrue(r.currentQuarterEarned) // still reported as earned
    }

    @Test
    fun `earned quarter stays earned even when activity later drops below threshold`() {
        // Simulates clinical records being edited/deleted after the award.
        val r = evaluate(
            listOf(QuarterActivity(q3, setOf(CH))),
            earned = setOf("2026Q3"),
        )
        assertTrue(r.currentQuarterEarned)
        assertEquals(0, r.domainsStillNeeded)
        assertTrue(r.newAwards.isEmpty())
    }

    @Test
    fun `replaying identical evidence yields identical output`() {
        val activity = listOf(
            QuarterActivity(Quarter(2026, 2), setOf(CH, MH)),
            QuarterActivity(q3, setOf(CH, MH, IM)),
        )
        assertEquals(evaluate(activity), evaluate(activity))
    }

    // ── Adversarial / production-resilience cases ────────────────────────────

    @Test
    fun `a future quarter is never awarded - guards a wrong device clock`() {
        val future = Quarter(2027, 1)
        val r = evaluate(
            listOf(QuarterActivity(future, setOf(CH, MH, IM))),
            current = q3,
        )
        assertTrue(r.newAwards.isEmpty())
    }

    @Test
    fun `awards come back chronologically regardless of input order`() {
        val q1 = Quarter(2026, 1)
        val q2 = Quarter(2026, 2)
        val jumbled = listOf(
            QuarterActivity(q3, setOf(CH, MH, IM)),
            QuarterActivity(q1, setOf(CH, MH, IM)),
            QuarterActivity(q2, setOf(CH, MH, IM)),
        )
        assertEquals(listOf(q1, q2, q3), evaluate(jumbled).newAwards.map { it.quarter })
    }

    @Test
    fun `empty input is safe`() {
        val r = evaluate(emptyList())
        assertTrue(r.newAwards.isEmpty())
        assertEquals(0, r.currentQuarterDomains)
        assertFalse(r.currentQuarterEarned)
    }

    @Test
    fun `current quarter absent from activity reports zero, not failure`() {
        val r = evaluate(listOf(QuarterActivity(Quarter(2026, 1), setOf(CH, MH, IM))))
        assertEquals(0, r.currentQuarterDomains)
        assertEquals(3, r.domainsStillNeeded)
    }

    // ── Quarter arithmetic ───────────────────────────────────────────────────

    @Test
    fun `month index maps to the right quarter across all twelve months`() {
        val expected = listOf(1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4)
        expected.forEachIndexed { monthIndex, q ->
            assertEquals(q, Quarter.of(2026, monthIndex).quarter)
        }
    }

    @Test
    fun `first month index is correct for each quarter`() {
        assertEquals(0, Quarter(2026, 1).firstMonthIndex)   // January
        assertEquals(3, Quarter(2026, 2).firstMonthIndex)   // April
        assertEquals(6, Quarter(2026, 3).firstMonthIndex)   // July
        assertEquals(9, Quarter(2026, 4).firstMonthIndex)   // October
    }

    @Test
    fun `previous quarter rolls the year back at Q1`() {
        assertEquals(Quarter(2025, 4), Quarter(2026, 1).previous())
        assertEquals(Quarter(2026, 2), Quarter(2026, 3).previous())
    }

    @Test
    fun `quarters sort chronologically across a year boundary`() {
        val sorted = listOf(Quarter(2026, 1), Quarter(2025, 4), Quarter(2026, 2)).sorted()
        assertEquals(listOf(Quarter(2025, 4), Quarter(2026, 1), Quarter(2026, 2)), sorted)
    }

    @Test
    fun `key round-trips through fromKey`() {
        val q = Quarter(2026, 3)
        assertEquals("2026Q3", q.key)
        assertEquals(q, Quarter.fromKey("2026Q3"))
    }

    @Test
    fun `fromKey rejects malformed keys instead of throwing`() {
        assertEquals(null, Quarter.fromKey(null))
        assertEquals(null, Quarter.fromKey(""))
        assertEquals(null, Quarter.fromKey("2026"))
        assertEquals(null, Quarter.fromKey("2026Q5"))
        assertEquals(null, Quarter.fromKey("2026Q0"))
        assertEquals(null, Quarter.fromKey("abcQ1"))
        assertEquals(null, Quarter.fromKey("2026QX"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructing an out-of-range quarter fails fast`() {
        Quarter(2026, 5)
    }

    @Test
    fun `quartersToExamine returns lookback plus current, oldest first`() {
        val quarters = CompleteWorkerEngine.quartersToExamine(q3)
        assertEquals(4, quarters.size) // default lookback 3 + current
        assertEquals(Quarter(2025, 4), quarters.first())
        assertEquals(q3, quarters.last())
        assertEquals(quarters.sorted(), quarters) // chronological
    }

    @Test
    fun `quartersToExamine crosses a year boundary correctly`() {
        val quarters = CompleteWorkerEngine.quartersToExamine(Quarter(2026, 1))
        assertEquals(
            listOf(Quarter(2025, 2), Quarter(2025, 3), Quarter(2025, 4), Quarter(2026, 1)),
            quarters
        )
    }

    // ── Configurability ─────────────────────────────────────────────────────

    @Test
    fun `threshold is configurable without touching the engine`() {
        val strict = CompleteWorkerEngine.CompleteWorkerConfig(domainsRequired = 4)
        val r = CompleteWorkerEngine.evaluate(
            listOf(QuarterActivity(q3, setOf(CH, MH, IM))),
            q3, emptySet(), strict
        )
        assertTrue(r.newAwards.isEmpty())
        assertEquals(1, r.domainsStillNeeded)
    }

    @Test
    fun `domain set is deduplicated by construction`() {
        // A Set means the same domain probed twice can never count twice.
        val r = evaluate(listOf(QuarterActivity(q3, setOf(CH, CH, MH))))
        assertTrue(r.newAwards.isEmpty())
        assertEquals(2, r.currentQuarterDomains)
        assertNotEquals(3, r.currentQuarterDomains)
    }
}
