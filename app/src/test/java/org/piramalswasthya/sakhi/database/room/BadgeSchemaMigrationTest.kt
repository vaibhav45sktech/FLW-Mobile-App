package org.piramalswasthya.sakhi.database.room

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs the EXACT SQL that [InAppDb]'s MIGRATION_64_65 executes (shared via companion
 * consts, the same pattern InAppDbMigrationTest uses for 60→61) against an in-memory
 * SQLite and proves the schema contract the badge mechanics rely on:
 *
 *  1. all three badge tables + the award dedup index exist,
 *  2. the migration is idempotent (IF NOT EXISTS — safe on re-run),
 *  3. a duplicate award (userId, badgeId, tier, occurrenceKey) is REJECTED —
 *     including the '' occurrenceKey case that a nullable column would let through,
 *  4. streak windows and observations enforce their composite primary keys.
 */
class BadgeSchemaMigrationTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        runMigrationSql()
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun runMigrationSql() {
        connection.createStatement().use { stmt ->
            stmt.execute(InAppDb.CREATE_BADGE_AWARD_SQL)
            stmt.execute(InAppDb.CREATE_BADGE_AWARD_INDEX_SQL)
            stmt.execute(InAppDb.CREATE_BADGE_STREAK_WINDOW_SQL)
            stmt.execute(InAppDb.CREATE_BADGE_OBSERVATION_SQL)
        }
    }

    private fun tableExists(name: String): Boolean {
        connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?"
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1) == 1
            }
        }
    }

    private fun insertAward(
        userId: Int = 1,
        badgeId: String = "STEADY_SYNCER",
        tier: Int = 1,
        occurrenceKey: String = ""
    ) {
        connection.prepareStatement(
            "INSERT INTO BADGE_AWARD " +
                    "(userId, badgeId, tier, occurrenceKey, streakRunId, earnedAt, createdAt) " +
                    "VALUES (?, ?, ?, ?, 'run-1', 1000, 1000)"
        ).use { ps ->
            ps.setInt(1, userId)
            ps.setString(2, badgeId)
            ps.setInt(3, tier)
            ps.setString(4, occurrenceKey)
            ps.executeUpdate()
        }
    }

    private fun countAwards(): Int {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM BADGE_AWARD").use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    // ── 1. Schema exists ─────────────────────────────────────────────────────

    @Test
    fun `migration creates all three badge tables`() {
        assertTrue(tableExists("BADGE_AWARD"))
        assertTrue(tableExists("BADGE_STREAK_WINDOW"))
        assertTrue(tableExists("BADGE_OBSERVATION"))
    }

    @Test
    fun `award dedup index exists with the exact room-expected name`() {
        connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?"
        ).use { ps ->
            ps.setString(1, "index_BADGE_AWARD_userId_badgeId_tier_occurrenceKey")
            ps.executeQuery().use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    // ── 2. Idempotency ───────────────────────────────────────────────────────

    @Test
    fun `running the migration twice is safe`() {
        // IF NOT EXISTS everywhere: a re-run (e.g. after a partial upgrade) must
        // neither throw nor clobber data.
        insertAward()
        runMigrationSql()
        assertEquals(1, countAwards())
    }

    // ── 3. Award uniqueness — the never-duplicate guarantee ─────────────────

    @Test
    fun `duplicate award with empty occurrenceKey is rejected`() {
        insertAward(tier = 1, occurrenceKey = "")
        try {
            insertAward(tier = 1, occurrenceKey = "")
            throw AssertionError("Second identical award must violate the unique index")
        } catch (expected: SQLException) {
            // constraint violation is the contract
        }
        assertEquals(1, countAwards())
    }

    @Test
    fun `insert or ignore silently keeps the first award`() {
        // Mirrors BadgeDao.insertAward's OnConflictStrategy.IGNORE.
        insertAward(tier = 2)
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT OR IGNORE INTO BADGE_AWARD " +
                        "(userId, badgeId, tier, occurrenceKey, streakRunId, earnedAt, createdAt) " +
                        "VALUES (1, 'STEADY_SYNCER', 2, '', 'run-2', 2000, 2000)"
            )
        }
        assertEquals(1, countAwards())
        // The surviving row is the ORIGINAL award (earnedAt 1000), not the retry.
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT earnedAt FROM BADGE_AWARD").use { rs ->
                rs.next()
                assertEquals(1000L, rs.getLong(1))
            }
        }
    }

    @Test
    fun `different tiers and different users do not collide`() {
        insertAward(userId = 1, tier = 1)
        insertAward(userId = 1, tier = 2)
        insertAward(userId = 2, tier = 1)
        assertEquals(3, countAwards())
    }

    @Test
    fun `distinct occurrenceKeys allow separate one-time awards`() {
        insertAward(tier = 0, occurrenceKey = "case-101")
        insertAward(tier = 0, occurrenceKey = "case-102")
        assertEquals(2, countAwards())
    }

    // ── 4. Composite primary keys ────────────────────────────────────────────

    private fun insertWindow(runId: String = "run-1", index: Int = 1) {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO BADGE_STREAK_WINDOW (userId, badgeId, streakRunId, " +
                        "windowIndex, windowStartMillis, windowEndMillis, state, " +
                        "graceAllowanceAfter, advanceCountAfter, closedAt) " +
                        "VALUES (1, 'STEADY_SYNCER', '$runId', $index, 0, 7, 'ADVANCE', 2, 1, 7)"
            )
        }
    }

    @Test
    fun `same window index in the same run is rejected`() {
        insertWindow(index = 1)
        try {
            insertWindow(index = 1)
            throw AssertionError("Duplicate (run, windowIndex) must violate the PK")
        } catch (expected: SQLException) {
        }
    }

    @Test
    fun `same index in a NEW run is allowed — runs re-anchor after a break`() {
        insertWindow(runId = "run-1", index = 1)
        insertWindow(runId = "run-2", index = 1)
    }

    @Test
    fun `one observation row per user per day`() {
        fun insertObservation(day: Long) {
            connection.createStatement().use { stmt ->
                stmt.executeUpdate(
                    "INSERT INTO BADGE_OBSERVATION (userId, observationDay, observedAt, " +
                            "gateEnabled, manualSyncCount, qualifyingSync, " +
                            "backlogNonzeroSeen, backlogZeroSeen) " +
                            "VALUES (1, $day, 1000, 1, 0, 0, 0, 0)"
                )
            }
        }
        insertObservation(day = 20_000)
        try {
            insertObservation(day = 20_000)
            throw AssertionError("Second row for the same user+day must violate the PK")
        } catch (expected: SQLException) {
        }
        insertObservation(day = 20_001)
    }

    // ── 5. Nullable columns accept NULL (pending-outcome fields) ─────────────

    @Test
    fun `pending sync fields are nullable and readable back`() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO BADGE_OBSERVATION (userId, observationDay, observedAt, " +
                        "gateEnabled, manualSyncCount, qualifyingSync, " +
                        "backlogNonzeroSeen, backlogZeroSeen, " +
                        "pendingSyncRequestAt, pendingBacklogBefore) " +
                        "VALUES (1, 30000, 1000, 1, 1, 0, 1, 0, 555, 12)"
            )
        }
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT pendingSyncRequestAt, pendingBacklogBefore FROM BADGE_OBSERVATION"
            ).use { rs ->
                rs.next()
                assertEquals(555L, rs.getLong(1))
                assertEquals(12, rs.getInt(2))
                assertNotEquals(0, rs.getLong(1))
            }
        }
    }
}
