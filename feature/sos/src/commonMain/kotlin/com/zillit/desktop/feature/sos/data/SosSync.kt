package com.zillit.desktop.feature.sos.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * The alarm, arriving live.
 *
 * One event, and the most time-critical one in the product: somebody on the
 * unit has raised an SOS. All three other clients listen for it — Android
 * `_isSOSAlertSent`, iOS `.sosNotification`, the web an alert banner — and the
 * desktop listened for it nowhere at all until now (found 2026-09-07).
 *
 * `projectsos:` is the server's prefix; it is not a typo for `project:sos:`.
 */
val SOS_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("projectsos:alert:sent"),
)
