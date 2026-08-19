package com.zillit.desktop.feature.notifications.domain

import com.zillit.desktop.core.common.ZillitResult

/** The notification service, as the list screen needs it. */
interface NotificationsRepository {

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
