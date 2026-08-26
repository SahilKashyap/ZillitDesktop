package com.zillit.desktop.feature.sides.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * The one sides event, by wire name — the web subscribes it directly on
 * the raw socket, bridge-less (`sides/constants.js:7`,
 * `SidesPage.jsx:92-109`), and refetches its list the moment a generation
 * finishes on the backend. The 5-second poll while anything is
 * `generating` stays as the fallback for a missed push, exactly as on
 * the web (`SidesPage.jsx:112-119`).
 */
internal val SIDES_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("sides:generated"),
)
