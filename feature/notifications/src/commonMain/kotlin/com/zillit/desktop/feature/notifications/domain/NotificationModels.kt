package com.zillit.desktop.feature.notifications.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * One row of the production's notification list, ready to draw.
 *
 * The wire row (Android `NotificationDataModel`, `model/NotificationDataModel.kt:17-54`)
 * carries a label key or an AES-encrypted body in `message`, a braced path in
 * `path` and the substitutions in `reference_data.messageElements`. All of that
 * is resolved in the data layer, so the screen never sees a key, a cipher or a
 * placeholder — [text] and [pathLabel] are what a person reads.
 */
data class ProjectNotification(
    val id: String,
    /** The server's per-delivery id (`notification_uuid`), when it sends one. */
    val uuid: String,
    /** The decoded, HTML-stripped message. */
    val text: String,
    /** The path as words — `Tools : Call Sheet` — or blank when there is none. */
    val pathLabel: String,
    val target: NotificationTarget,
    val createdMillis: Long,
    val updatedMillis: Long,
    val read: Boolean,
    /**
     * Whether the row belongs on the global list. Android's adapter keeps only
     * `is_global == true` rows (`NotificationAdapter.filterList`, line 104);
     * everything else on this feed is a badge-only bookkeeping row.
     */
    val isGlobal: Boolean,
) {
    /** `12 Aug 2026, 14:32` — the stamp under each row. */
    val timeLabel: String get() = createdMillis.toStampLabel()
}

/**
 * Where a notification points, verbatim from the wire.
 *
 * Android's row tap is a no-op (`NotificationActivity.onNotificationItemTap`),
 * so nothing here is interpreted; the web's `notificationRouteMatcher` maps
 * `section`/`tool`/`action` to a route, and a host that wants that can do it
 * from these fields.
 */
data class NotificationTarget(
    val path: String = "",
    val section: String = "",
    val tool: String = "",
    val unit: String = "",
    val action: String = "",
    val referenceId: String = "",
)

/**
 * The list as Android shows it: global rows only, one per id, newest first
 * (`NotificationAdapter.filterList`, lines 104-105).
 */
fun List<ProjectNotification>.forDisplay(): List<ProjectNotification> =
    filter { it.isGlobal }
        .distinctBy { it.id }
        .sortedByDescending { it.createdMillis }

/**
 * `12 Aug 2026, 14:32`, in the machine's zone. Blank for a missing stamp.
 *
 * Android renders `MMM dd, yyyy hh:mm a` (`Constants.DATE_FORMAT_TIME`); the
 * day-first, 24-hour form is the desktop's own convention (see the Home
 * board's read-receipt stamp), kept here so the two lists agree.
 */
fun Long.toStampLabel(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (this <= 0) return ""
    val moment = runCatching { Instant.fromEpochMilliseconds(this).toLocalDateTime(zone) }.getOrNull()
        ?: return ""
    val month = str(MONTHS[moment.month.ordinal])
    return "${moment.day.pad()} $month ${moment.year}, ${moment.hour.pad()}:${moment.minute.pad()}"
}

private fun Int.pad(): String = toString().padStart(2, '0')

private val MONTHS = listOf(
    S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar, S.desktop_month_short_apr,
    S.desktop_month_short_may, S.desktop_month_short_jun, S.desktop_month_short_jul, S.desktop_month_short_aug,
    S.desktop_month_short_sep, S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
)
