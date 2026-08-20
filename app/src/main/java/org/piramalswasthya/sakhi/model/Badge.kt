package org.piramalswasthya.sakhi.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Badge ids used across the gamification badge mechanics. String constants (not an
 * enum) so a future badge added via config cannot crash deserialization of old rows.
 */
object BadgeIds {
    const val STEADY_SYNCER = "STEADY_SYNCER"
    const val TIMELY_REPORTER = "TIMELY_REPORTER"
    const val COMPLETE_WORKER = "COMPLETE_WORKER"
}

/**
 * Terminal state of one closed 7-day streak window. Stored on
 * [BadgeStreakWindowCache.state] as the enum name.
 *
 * ADVANCE   – a qualifying manual sync happened in the window; counts toward tiers.
 * GRACE     – no qualifying sync while backlog existed; protected by the allowance.
 * SUSPENDED – no qualifying sync and no backlog (nothing to punish, nothing earned).
 * FROZEN    – badges_enabled gate was off; excluded from every counter.
 * BREAK     – backlog existed, no sync, allowance exhausted; the run ends here.
 */
enum class BadgeWindowState {
    ADVANCE,
    GRACE,
    SUSPENDED,
    FROZEN,
    BREAK,
}

/**
 * One earned badge tier. NEVER deleted or updated once inserted — the concept note
 * forbids revoking an earned badge, so this table is insert-only (OnConflict IGNORE).
 *
 * [occurrenceKey] is NOT NULL with '' default deliberately: SQLite treats NULLs as
 * DISTINCT inside UNIQUE indexes, so a nullable key would let the same award insert
 * twice. Tiered badges use '' (dedup on userId+badgeId+tier); once-per-case badges
 * put the case identity here (dedup on the occurrence).
 */
@Entity(
    tableName = "BADGE_AWARD",
    indices = [Index(
        name = "index_BADGE_AWARD_userId_badgeId_tier_occurrenceKey",
        value = ["userId", "badgeId", "tier", "occurrenceKey"],
        unique = true
    )]
)
data class BadgeAwardCache(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Int,
    val badgeId: String,
    /** Milestone index 1..4 for laddered badges, 0 for one-time recognition. */
    val tier: Int = 0,
    val occurrenceKey: String = "",
    /** The streak run this award was earned in (streak badges only, else ''). */
    val streakRunId: String = "",
    val earnedAt: Long,
    val createdAt: Long = earnedAt,
)

/**
 * One CLOSED 7-day window of a streak run. Only closed windows are persisted —
 * the in-progress window is derived at evaluation time, so [state] is always final
 * and rows are write-once (idempotent re-close via upsert of identical content).
 *
 * Windows are anchored to the run start, not the calendar: window N spans
 * [runStart + 7(N-1) days, runStart + 7N days). No locale/timezone week bucketing.
 */
@Entity(
    tableName = "BADGE_STREAK_WINDOW",
    primaryKeys = ["userId", "badgeId", "streakRunId", "windowIndex"]
)
data class BadgeStreakWindowCache(
    val userId: Int,
    val badgeId: String,
    /** UUID minted when a run starts; a BREAK or 4-SUSPENDED close mints a new one. */
    val streakRunId: String,
    /** 1-based position of this window inside its run. */
    val windowIndex: Int,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    /** [BadgeWindowState].name — kept TEXT so old rows survive future states. */
    val state: String,
    /** Grace windows remaining AFTER this window closed (restored to 2 on tier-up). */
    val graceAllowanceAfter: Int,
    /** ADVANCE windows accumulated in this run AFTER this window closed. */
    val advanceCountAfter: Int,
    val closedAt: Long,
)

/**
 * One calendar month of INCENTIVE_RECORD, rolled up by the DAO. Not a table —
 * a read-only projection, so badges stay read-only on clinical data.
 *
 * [earliestClaimDate] is the raw server string (formats vary: `yyyy-MM-dd`,
 * `yyyy-MM-dd HH:mm:ss`, `MMM dd, yyyy hh:mm:ss a`), so it must be parsed with
 * `getLongFromDateMultipleSupport`, which returns null rather than throwing.
 */
data class MonthlyClaimRow(
    val yearMonth: Int,
    val recordCount: Int,
    val anyClaimed: Int,
    val earliestClaimDate: String?,
)

/**
 * First-seen monthly claim ledger for Timely Reporter.
 *
 * The server exposes a single `calimedDate` per incentive record and OVERWRITES it
 * when a rejected month is re-claimed. The locked product rule is that timeliness
 * is judged on the ORIGINAL submission, so the first time an evaluation observes a
 * month as claimed it records that date here and never updates it — a later
 * re-claim cannot rewrite history that has already been witnessed.
 *
 * Limitation, by construction: this only protects months first observed after the
 * badge module is installed. For months already claimed before that, the only
 * available date is whatever `calimedDate` currently holds.
 */
@Entity(
    tableName = "BADGE_MONTH_CLAIM",
    primaryKeys = ["userId", "yearMonth"]
)
data class BadgeMonthClaimCache(
    val userId: Int,
    /** Calendar month as yyyyMM (e.g. 202608) — sortable, timezone-free. */
    val yearMonth: Int,
    /** Epoch millis of the FIRST claim date this module ever saw for the month. */
    val firstClaimAtMillis: Long,
    /** When we recorded it (diagnostics only; never used in the streak decision). */
    val observedAt: Long,
)

/**
 * Daily evidence ledger for badge evaluation, one row per user per day
 * (PK userId+observationDay makes writes idempotent). Badges own this table;
 * clinical tables are never written. Pruned past OBSERVATION_RETENTION_DAYS.
 */
@Entity(
    tableName = "BADGE_OBSERVATION",
    primaryKeys = ["userId", "observationDay"]
)
data class BadgeObservationCache(
    val userId: Int,
    /** Epoch day (UTC millis / 86_400_000) — day-keyed, not timestamp-keyed. */
    val observationDay: Long,
    /** Last time this row was updated; monotonic guard rejects rollbacks. */
    val observedAt: Long,
    /** badges_enabled gate state when observed — FROZEN windows reconstruct from this. */
    val gateEnabled: Boolean,
    /** How many times the manual Sync Records action actually fired today. */
    val manualSyncCount: Int = 0,
    /**
     * True once ≥1 manual sync today ended qualifying: ≥1 record uploaded
     * (partial success counts) OR confirmed nothing-pending. Drives ADVANCE.
     */
    val qualifyingSync: Boolean = false,
    /** True if any check today saw unsynced records — drives GRACE/BREAK. */
    val backlogNonzeroSeen: Boolean = false,
    /** True if any check today confirmed a zero backlog — drives SUSPENDED. */
    val backlogZeroSeen: Boolean = false,
    /** Set at the manual-sync click; cleared when the outcome is resolved. */
    val pendingSyncRequestAt: Long? = null,
    /** Backlog count snapshotted at the click, for the backlog-delta fallback. */
    val pendingBacklogBefore: Int? = null,
) {
    companion object {
        const val OBSERVATION_RETENTION_DAYS = 400L
    }
}
