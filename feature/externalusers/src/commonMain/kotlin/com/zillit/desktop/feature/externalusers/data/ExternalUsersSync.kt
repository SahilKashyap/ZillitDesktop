package com.zillit.desktop.feature.externalusers.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Live updates for the external-users directory.
 *
 * All three clients carry them — Android's `_externalUsesrAdd` / `Update` /
 * `Delete`, iOS's `.externalUser*`, the web's `external_user_*` aliases. The
 * desktop listened for none (audited 2026-09-07): a guest added by another
 * coordinator did not appear, and one removed stayed on the list, which on a
 * directory used to grant access is the wrong way round to be stale.
 */
val EXTERNAL_USERS_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("project:external:user:added"),
    SocketEventName("project:external:user:updated"),
    SocketEventName("project:external:user:deleted"),
)
