package com.zillit.desktop.feature.esignature.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Every envelope-lifecycle event the backend emits, by its wire name —
 * the web subscribes to all seven (`listenerSocket.js:27-33`) and funnels
 * them into one "something about an envelope changed" custom event that
 * `DocuSignObservers.jsx:42-52` applies. The web patches its Redux slice
 * in place; here the same events trigger a refetch of whichever envelope
 * list is on screen, so a signature landing on another device updates an
 * open tracker.
 */
val ESIGN_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("esignature:envelope:delivered"),
    SocketEventName("esignature:envelope:signed"),
    SocketEventName("esignature:envelope:declined"),
    SocketEventName("esignature:envelope:completed"),
    SocketEventName("esignature:envelope:voided"),
    SocketEventName("esignature:envelope:updated"),
    SocketEventName("esignature:envelope:deleted"),
)
