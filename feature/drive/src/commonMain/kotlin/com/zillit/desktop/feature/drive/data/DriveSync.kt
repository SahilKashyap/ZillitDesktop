package com.zillit.desktop.feature.drive.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * The delete events another client can announce, by wire name
 * (`listenerSocket.js:2612/2629/2635`; the web's `DriveManagement.jsx:1591-1596`
 * listens to their underscore re-emits and refetches the current view —
 * deletes run even before its own-events guard, ZL-18490, because the
 * owner deleting a shared item is exactly the case the receiver must see).
 *
 * The web also refreshes on the add/update/move/share family and on
 * `drive_access_changed`; the delete trio is what this port ships —
 * `drive_access_changed` has no `socket.on` emitter anywhere in the web
 * bridge, so there is no wire name to subscribe to.
 */
internal val DRIVE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("drive:file:deleted"),
    SocketEventName("drive:folder:deleted"),
    SocketEventName("drive:bulk:deleted"),
)
