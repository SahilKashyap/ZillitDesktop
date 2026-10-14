package com.zillit.desktop.feature.timecard.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread ledger row of the timecard tool, as far as its screens need it.
 *
 * The wire (iOS `TimecardBadgeViewModel.swift:10-26`, `constants.js:107-116`):
 * `tool=timecard_label`, the tab in `unit`, the week's Monday in `level_1`
 * (epoch millis as a string) under `my_timecards` and `assigned_timecards`,
 * the literal `timecard_dispute` in `level_1` for a My Timecards dispute, and
 * the record in `level_3` — a timecard, an assignment slot on Assigned
 * Timecards, a dispute on the dispute bucket.
 */
data class TimecardUnreadRow(
    val unit: String,
    val level1: String = "",
    val level3: String = "",
    /** When the service wrote it — what tells a row older than a list from one the list could not hold yet. */
    val created: Long = 0L,
)

/**
 * The timecard tool's unread rows and its reads — iOS `TimecardBadgeViewModel`.
 *
 * Every read is one record or one week, never a tab: iOS reads one timecard as
 * it is opened or decided (`readTimecardBadges(unit:timecardId:weekStartMs:)`,
 * 311-388), one assigned week (`readAssignedWeek`, 393-407) or one dispute
 * (`readMyTimecardDispute`, `readCrewTimecardDispute`, 416-486).
 */
interface TimecardBadges {
    /** Every unread row under `timecard_label`, again whenever the ledger changes. */
    val rows: Flow<List<TimecardUnreadRow>> get() = emptyFlow()

    /** One record's rows in one unit — `level_3`, and the week's `level_1` when given. */
    fun readRecord(unit: String, recordId: String, week: String? = null) {}

    /** One week's bucket in one unit — `level_1` alone. */
    fun readWeek(unit: String, week: String) {}

    companion object {
        val None: TimecardBadges = object : TimecardBadges {}

        const val MY_TIMECARDS = "my_timecards"
        const val ASSIGNED_TIMECARDS = "assigned_timecards"
        const val RECEIVED_INBOX = "received_inbox"
        const val APPROVAL_QUEUE = "approval_queue_label"
        const val CREW_DISPUTES = "crew_timecard_dispute"

        /** The My Timecards dispute bucket's `level_1` — a literal, not a week. */
        const val DISPUTE_BUCKET = "timecard_dispute"
    }
}

/**
 * The timecard rows cut the way the screens ask: per tab (unit), per record,
 * per week — iOS `badgeCount(forTimecardId:unit:)`, `badgeCount(forAssignedWeekStartMs:)`
 * and the `assignedByWeek` / `myTimecardsByWeek` maps (`TimecardBadgeViewModel.swift:199-306`).
 */
data class TimecardUnread(val rows: List<TimecardUnreadRow> = emptyList()) {

    /** Unread per unit — the tabs' chips. */
    val byUnit: Map<String, Int> get() = rows.groupingBy { it.unit }.eachCount()

    fun unit(unit: String): Int = rows.count { it.unit == unit }

    /** One record's rows in one unit, by `level_3`. */
    fun record(unit: String, recordId: String): Int =
        if (recordId.isBlank()) 0 else rows.count { it.unit == unit && it.level3 == recordId }

    /** One week's bucket in one unit, by `level_1`. */
    fun week(unit: String, week: String): Int =
        if (week.isBlank()) 0 else rows.count { it.unit == unit && it.level1 == week }

    /**
     * A week of the viewer's own on My Timecards — its timecard's rows wherever
     * they are bucketed, its week's bucket, and the review rows naming it.
     * Counted once each: a row matching both the timecard and the week is one row.
     */
    fun myWeek(card: Timecard): Int {
        val week = card.weekKey
        return rows.count { row ->
            when (row.unit) {
                TimecardBadges.MY_TIMECARDS ->
                    row.level3 == card.id || (week != null && row.level1 == week)
                TimecardBadges.RECEIVED_INBOX -> row.level3 == card.id
                else -> false
            }
        }
    }

    companion object {
        val None = TimecardUnread()
    }
}

/**
 * The week's `level_1` key — its Monday as epoch millis, the way iOS passes
 * `weekStartMs` (`String(ms)`, `TimecardBadgeViewModel.swift:327-330`).
 */
val Timecard.weekKey: String? get() = weekStarting?.takeIf { it > 0 }?.toString()
