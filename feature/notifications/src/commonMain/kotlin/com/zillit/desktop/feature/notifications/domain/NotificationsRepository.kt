package com.zillit.desktop.feature.notifications.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.serialization.json.JsonElement

/**
 * One `notification:save` record, ready to show as an OS banner.
 *
 * [area] is the decoded path label ("Tools : Call Sheet") — the closest thing
 * the wire has to a title — and [body] the decoded sentence. [section] is the
 * raw wire section (`cnc_label`, `tools_label`, ...) so the caller can leave
 * out the areas a dedicated alert already covers.
 */
data class ActivityBanner(
    val id: String,
    val section: String,
    val area: String,
    val body: String,
)

/** The notification service, as the list screen and the alert tray need it. */
interface NotificationsRepository {

    /**
     * Decodes one `notification:save` socket frame into the banner the phones
     * would have shown for it — or null for a frame that must stay silent.
     *
     * Null when the record says so itself: `reference_data.ignore` and
     * `reference_data.self`, the two drops iOS's socket handler applies
     * (ProjectObserver.swift:6787-6791), plus `silent`, which iOS drops on
     * its banner paths (AppDelegate.swift:1620, the notification extension's
     * shouldCountForBadge). Also null when the envelope does not hold a
     * record, and when decoding leaves no words to show. `is_global` is
     * deliberately NOT consulted: it places a row on the global bell page,
     * while the phones banner every non-silent push regardless of it — the
     * server's banner switch is `silent`, and that is the one honoured here.
     * On the phones this event only feeds badges and FCM carries the banner;
     * the desktop has no push channel, so this is where its banners come from.
     */
    fun banner(payload: JsonElement?): ActivityBanner?


    /**
     * The page of rows older than [beforeMillis].
     *
     * [newest] marks the first page — the one asked for from "now" — so the
     * transport can keep it under a stable name for offline viewing.
     */
    suspend fun page(beforeMillis: Long, newest: Boolean): ZillitResult<List<ProjectNotification>>

    /** Marks a whole segment read up to [timestampMillis] — `global_label` for this list. */
    suspend fun markRead(segment: String, timestampMillis: Long): ZillitResult<Unit>

    /** Deletes one row by its `_id`. */
    suspend fun delete(notificationId: String, timestampMillis: Long): ZillitResult<Unit>

    /** Deletes every row on the global list. */
    suspend fun deleteAll(): ZillitResult<Unit>
}
