package com.zillit.desktop.feature.location

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.location.data.LOCATION_DISCUSSION_EVENTS
import com.zillit.desktop.feature.location.data.LOCATION_SYNC_EVENTS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The location record's thread, kept live.
 *
 * The web ignores these events — its handlers only refetch badges for a chat
 * rail this module does not carry — but **both phones drive the thread from
 * them**, and the desktop has the thread the web lacks. So the phones are the
 * reference here, and the web's silence is not evidence of absence.
 */
class LocationDiscussionSyncTest {

    private val names = LOCATION_DISCUSSION_EVENTS.map(SocketEventName::value)

    @Test
    fun `the thread listens for the six-event family plus read-by`() {
        listOf(
            "location:message:added",
            "location:message:edited",
            "location:message:deleted:multiple",
            "location:message:comment:added",
            "location:message:comment:edited",
            "location:message:comment:deleted",
            "location:message:readby:update",
        ).forEach { assertTrue(it in names, it) }
    }

    @Test
    fun `the singular delete is not on this wire`() {
        assertFalse("location:message:deleted" in names)
    }

    @Test
    fun `the record list and the thread stay separate subscriptions`() {
        // They reload different things: the shortlist does not move when a
        // note is left on one record, and a note does not reorder the list.
        val records = LOCATION_SYNC_EVENTS.map(SocketEventName::value)
        assertTrue(records.none { it.contains(":message:") })
        assertTrue(names.all { it.contains(":message:") })
        assertTrue(records.intersect(names.toSet()).isEmpty())
    }
}
