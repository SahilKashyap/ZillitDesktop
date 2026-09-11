package com.zillit.desktop.feature.drive.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * What another client can announce about the drive, by wire name.
 *
 * The web listens to the underscore re-emits of these in
 * `DriveManagement.jsx:1589-1596` and refetches the current view through one
 * shared handler. Deletes run even before its own-events guard (ZL-18490):
 * the owner deleting a shared item is exactly the case the receiver must see.
 *
 * The shared pair (`drive:file:shared`, `drive:folder:shared`) is here even
 * though the web routes it to a badge handler rather than a reload. The web
 * has no "Shared with me" list to be wrong; the desktop does
 * ([DriveListing.Shared]), and a file shared with this user belongs in it the
 * moment it is shared. iOS agrees and does exactly what the other names here
 * do — `invalidateAll()` then `forceLoadContents()`
 * (`HomeViewModel.swift:2018`). Added 2026-09-09, correcting an exclusion
 * that was right about the web and wrong here.
 *
 * The comment family is still left out: it belongs to the open details panel,
 * not to the list this reloads.
 *
 * `drive_access_changed` has no `socket.on` emitter anywhere in the web
 * bridge, so there is no wire name to subscribe to at all.
 *
 * A burst collapses to one refetch — see the `conflate()` on the flow, which
 * is the coalescing the web had to add by hand after one update produced
 * several refetches within milliseconds.
 */
internal val DRIVE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("drive:file:added"),
    SocketEventName("drive:file:updated"),
    SocketEventName("drive:file:deleted"),
    SocketEventName("drive:folder:created"),
    SocketEventName("drive:folder:updated"),
    SocketEventName("drive:folder:moved"),
    SocketEventName("drive:folder:deleted"),
    SocketEventName("drive:bulk:deleted"),
    SocketEventName("drive:file:shared"),
    SocketEventName("drive:folder:shared"),
)
