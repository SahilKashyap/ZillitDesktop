package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.readEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading the guest list off an event row.
 *
 * Three clients write this array and the server hands it back in whichever
 * shape it was stored in, so the reader has to take all of them: rows under
 * either key, and bare ids. Reading only one shape cost the detail popover its
 * invitee card — the count came back zero and the card hides on zero, which
 * took the only route to the invitee list with it.
 */
class CalendarGuestWireTest {

    private fun row(guests: String) = Json.parseToJsonElement(
        """{"_id":"e1","title":"Unit call","start_datetime":1791174600000,
           "end_datetime":1791178200000$guests}""",
    )

    @Test
    fun `rows under either key are read, and the same person once`() {
        val event = readEvent(
            row(
                ""","invitees":[{"user_id":"u1","type":"project_user"}],
                   "invited_users":[{"user_id":"u1"},{"user_id":"u2"}]""",
            ),
        )!!

        assertEquals(setOf("u1", "u2"), event.inviteeIds)
        assertEquals(2, event.invitedCount)
    }

    @Test
    fun `bare ids count as much as rows do`() {
        val event = readEvent(row(""","invitees":["u1","u2"]"""))!!

        assertEquals(setOf("u1", "u2"), event.inviteeIds)
        assertEquals(2, event.invitedCount)
    }

    @Test
    fun `a bare address is an outside guest, not a crew id`() {
        val event = readEvent(row(""","invitees":["u1","guest@example.com"]"""))!!

        assertEquals(setOf("u1"), event.inviteeIds)
        assertEquals(listOf("guest@example.com"), event.externalEmails)
        assertEquals(2, event.invitedCount)
    }

    @Test
    fun `the expanded invitations array is read too`() {
        val event = readEvent(
            row(""","invitations":[{"user_id":"u1","status":"accepted"}]"""),
        )!!

        assertEquals(setOf("u1"), event.inviteeIds)
    }

    @Test
    fun `an outside guest row still travels under its type`() {
        val event = readEvent(
            row(""","invitees":[{"email":"guest@example.com","type":"external"}]"""),
        )!!

        assertEquals(emptySet(), event.inviteeIds)
        assertEquals(listOf("guest@example.com"), event.externalEmails)
    }

    @Test
    fun `an event with nobody on it and no type is somebody's own note`() {
        assertEquals(EventAudience.Personal, readEvent(row(""))!!.audience)
        assertEquals(EventAudience.Members, readEvent(row(""","invitees":["u1"]"""))!!.audience)
    }
}
