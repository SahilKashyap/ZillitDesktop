package com.zillit.desktop.feature.timecard.ui

import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardBadges
import com.zillit.desktop.feature.timecard.domain.TimecardUnread
import com.zillit.desktop.feature.timecard.domain.TimecardUnreadRow
import com.zillit.desktop.feature.timecard.domain.weekKey

/**
 * Which timecard rows each page clears, and when — iOS `TimecardBadgeViewModel`
 * and its callers, one record or one week at a time. No page reads its tabs
 * whole any more: the desktop used to read every unit a page was filed under
 * as it came on screen, and again whenever a row landed, so no row ever kept a
 * dot.
 */
internal class TimecardReads(private val badges: TimecardBadges) {

    /**
     * A week opened from a list — what iOS reads as each surface opens one:
     * My Timecards its timecard in its week (`MyTimecardsModule.swift:959-968`)
     * and the Review Inbox's rows naming it (`CrewReviewModule.swift:594-599`),
     * the Crew Disputes list its dispute (`readCrewTimecardDispute`,
     * `TimecardBadgeViewModel.swift:474-481`). The Approval Queue reads nothing on
     * opening — "merely viewing the timecard must not mark it read"
     * (`TCApprovalQueuePage.swift:786-789`); it reads on the decision ([decided]).
     */
    fun opened(destination: TimecardDestination, card: Timecard, unread: TimecardUnread) {
        when (destination) {
            TimecardDestination.MyWeeks -> openedMyWeek(card, unread)
            TimecardDestination.Processing -> readRecord(unread, TimecardBadges.CREW_DISPUTES, card.id)
            TimecardDestination.ApprovalQueue, TimecardDestination.Edit, TimecardDestination.Outstanding -> Unit
        }
    }

    /**
     * Weeks approved or rejected — their Approval Queue rows read once the decision
     * lands, singly or in a batch (`TCApprovalQueuePage.swift:470-481, 1691-1697,
     * 1955-1971`): approving straight from the list, without opening, must clear
     * the dot as well.
     */
    fun decided(ids: Collection<String>, unread: TimecardUnread) {
        ids.forEach { readRecord(unread, TimecardBadges.APPROVAL_QUEUE, it) }
    }

    /**
     * The rows a page entered can never draw on a row — read, so its tab can fall.
     *
     * iOS draws every timecard unit on a screen of its own; the desktop has none
     * for Assigned Timecards (a completer's slots, keyed by assignment) or the My
     * Timecards dispute bucket (keyed by dispute), and its lists hold only what
     * they hold — a week another approver has decided leaves the queue, a dispute
     * on another pay period is not on Processing. Those rows used to clear with
     * the desktop's whole-tab read; read row by row instead, they would be lit for
     * ever. So when a page is entered and its list has arrived, every row of its
     * units that names nothing on that list is read, at its own coordinates.
     *
     * Only rows written before the list was asked for, less [ARRIVAL_GRACE_MILLIS]
     * for the clocks: a row that lands while the list is in flight may describe a
     * week the list could not contain yet, and is left for the next entry.
     */
    fun sweep(
        destination: TimecardDestination,
        loaded: List<Timecard>,
        unread: TimecardUnread,
        askedAt: Long,
    ) {
        val units = destination.badgeKeys.toSet()
        if (units.isEmpty()) return
        val ids = loaded.mapTo(HashSet()) { it.id }
        val weeks = loaded.mapNotNullTo(HashSet()) { it.weekKey }
        unread.rows
            .filter { it.unit in units && it.created < askedAt - ARRIVAL_GRACE_MILLIS }
            .filterNot { it.drawnOn(destination, ids, weeks) }
            .distinctBy { Triple(it.unit, it.level1, it.level3) }
            .forEach { row ->
                when {
                    row.level3.isNotBlank() -> badges.readRecord(row.unit, row.level3, row.level1.ifBlank { null })
                    row.level1.isNotBlank() -> badges.readWeek(row.unit, row.level1)
                    // A row naming neither a record nor a week: only a whole-unit read
                    // reaches it, and that is the read this replaced. Left alone.
                    else -> Unit
                }
            }
    }

    /** Whether a row has a dot to show on [destination]'s list — see [TimecardUiState.rowUnread]. */
    private fun TimecardUnreadRow.drawnOn(
        destination: TimecardDestination,
        ids: Set<String>,
        weeks: Set<String>,
    ): Boolean = when (destination) {
        TimecardDestination.MyWeeks -> when (unit) {
            TimecardBadges.MY_TIMECARDS -> level3 in ids || level1 in weeks
            TimecardBadges.RECEIVED_INBOX -> level3 in ids
            else -> false
        }
        TimecardDestination.ApprovalQueue, TimecardDestination.Processing -> level3 in ids
        TimecardDestination.Edit, TimecardDestination.Outstanding -> true
    }

    /**
     * My Timecards: the week's bucket (`level_1 = <Monday>`, every row of which
     * names this week's timecard), the timecard's rows filed under no week or
     * another, and the Review Inbox's rows naming it — what [TimecardUnread.myWeek]
     * counts, so the dot goes as the week opens.
     */
    private fun openedMyWeek(card: Timecard, unread: TimecardUnread) {
        val week = card.weekKey
        if (week != null && unread.week(TimecardBadges.MY_TIMECARDS, week) > 0) {
            badges.readWeek(TimecardBadges.MY_TIMECARDS, week)
        }
        val elsewhere = unread.rows.any {
            it.unit == TimecardBadges.MY_TIMECARDS && it.level3 == card.id && it.level1 != week
        }
        if (elsewhere) badges.readRecord(TimecardBadges.MY_TIMECARDS, card.id)
        readRecord(unread, TimecardBadges.RECEIVED_INBOX, card.id)
    }

    /** One record in one unit, only while it has something to read. */
    private fun readRecord(unread: TimecardUnread, unit: String, recordId: String) {
        if (unread.record(unit, recordId) > 0) badges.readRecord(unit, recordId)
    }

    companion object {
        /** How far the service's clock and this one may disagree — the mail sync's allowance. */
        const val ARRIVAL_GRACE_MILLIS = 2 * 60 * 1000L
    }
}
