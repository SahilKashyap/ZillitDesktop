package com.zillit.desktop.feature.recce.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Live updates for the recce list.
 *
 * All three clients carry these: Android's `recceEventBus` inserts, patches
 * and removes rows; iOS posts `.recceCreated` / `.recceUpdated` /
 * `.recceDeleted`; the web's `ReccePage` upserts by `_id` and bails out of the
 * open detail when the recce being viewed is the one deleted.
 *
 * The desktop listened for none of them (audited 2026-09-07). A scout day
 * added by the location manager did not appear until the window was
 * reopened — which on a recce day is exactly when two people are editing.
 *
 * Coarse on purpose: the payloads carry one recce, but the list is filtered
 * and paged by unit and status, so a refetch is the merge that already works.
 * `recce:deleted` carries `{project_id, ids}` rather than a single row.
 */
val RECCE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("recce:created"),
    SocketEventName("recce:updated"),
    SocketEventName("recce:deleted"),
)
