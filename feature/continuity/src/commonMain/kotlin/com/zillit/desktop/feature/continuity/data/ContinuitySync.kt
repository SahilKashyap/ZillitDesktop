package com.zillit.desktop.feature.continuity.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * The four continuity events, by wire name (`listenerSocket.js:862-873`
 * subscribes them and re-emits under underscore aliases). Every web
 * surface refetches on them — `ContinuityModal.jsx:242/251/295/488`
 * refreshes the open scene list, `IntraDepartment.jsx:150/576/593/658`
 * the folder grid — so one pulse here reloads both the folder list and
 * any open folder.
 */
internal val CONTINUITY_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("continuity:scenes:created"),
    SocketEventName("continuity:scene:updated"),
    SocketEventName("continuity:scene:deleted"),
    SocketEventName("continuity:scene:shared"),
)
