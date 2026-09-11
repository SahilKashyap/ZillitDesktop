package com.zillit.desktop.feature.recce

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.recce.data.RECCE_SYNC_EVENTS
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recce list's wire names.
 *
 * All three clients carry these three; the desktop carried none until
 * 2026-09-09, so a scout day added by the location manager stayed invisible
 * until the window was reopened.
 */
class RecceSyncTest {

    @Test
    fun `the three recce events are the ones the other clients carry`() {
        assertEquals(
            listOf("recce:created", "recce:updated", "recce:deleted"),
            RECCE_SYNC_EVENTS.map(SocketEventName::value),
        )
    }
}
