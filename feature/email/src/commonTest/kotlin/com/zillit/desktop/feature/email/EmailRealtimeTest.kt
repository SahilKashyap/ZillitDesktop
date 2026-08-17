package com.zillit.desktop.feature.email

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.data.toEmailEvent
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Turning mail socket traffic into something the mailbox can act on.
 *
 * The wire names matter more than usual here: a typo produces silence, not an
 * error, and the web app's `inbound_email_received` are aliases it re-emits
 * internally rather than anything the server sends.
 */
class EmailRealtimeTest {

    private fun eventFor(name: SocketEventName, payload: String?): EmailRealtimeEvent? =
        toEmailEvent(SocketMessage(name, payload?.let(Json::parseToJsonElement)))

    @Test
    fun `new mail refreshes the folder it named`() {
        val event = eventFor(
            ZillitSocketEvents.Email.InboundReceived,
            """{"data":{"folder_name":"INBOX","uid":42}}""",
        )

        assertEquals(EmailRealtimeEvent.FolderChanged("INBOX"), event)
    }

    @Test
    fun `a payload wrapped as a JSON string is still read`() {
        // The server does send `data` as a string rather than an object.
        val event = eventFor(
            ZillitSocketEvents.Email.InboundReceived,
            """{"data":"{\"folder_name\":\"Archive\"}"}""",
        )

        assertEquals(EmailRealtimeEvent.FolderChanged("Archive"), event)
    }

    @Test
    fun `sent mail falls back to the Sent folder when the payload is silent`() {
        // Guessing is safe: the worst case is refreshing a folder that had not
        // changed, and the event name already tells us where it landed.
        val event = eventFor(ZillitSocketEvents.Email.OutboundSent, """{}""")

        assertEquals(EmailRealtimeEvent.FolderChanged("Sent"), event)
    }

    @Test
    fun `received mail falls back to the inbox`() {
        assertEquals(
            EmailRealtimeEvent.FolderChanged("INBOX"),
            eventFor(ZillitSocketEvents.Email.InboundReceived, """{}"""),
        )
    }

    @Test
    fun `a move names no folder, so the open one is refreshed`() {
        // A move touches two folders and the payload names at most one.
        val event = eventFor(ZillitSocketEvents.Email.Moved, """{}""")

        assertEquals(EmailRealtimeEvent.FolderChanged(null), event)
    }

    @Test
    fun `a read elsewhere carries the uid, so no refetch is needed`() {
        val event = eventFor(ZillitSocketEvents.Email.Read, """{"data":{"uid":7}}""")

        assertEquals(EmailRealtimeEvent.ReadChanged(7), event)
    }

    @Test
    fun `a uid sent as a string is still read`() {
        assertEquals(
            EmailRealtimeEvent.ReadChanged(7),
            eventFor(ZillitSocketEvents.Email.Read, """{"data":{"uid":"7"}}"""),
        )
    }

    @Test
    fun `folder events invalidate the sidebar`() {
        listOf(
            ZillitSocketEvents.Email.FolderSaved,
            ZillitSocketEvents.Email.FolderUpdated,
            ZillitSocketEvents.Email.FolderDeleted,
        ).forEach { name ->
            assertIs<EmailRealtimeEvent.FoldersChanged>(eventFor(name, """{}"""), "$name")
        }
    }

    @Test
    fun `every deletion event refreshes something`() {
        listOf(
            ZillitSocketEvents.Email.InboundDeleted,
            ZillitSocketEvents.Email.OutboundDeleted,
            ZillitSocketEvents.Email.TrailDeleted,
            ZillitSocketEvents.Email.Deleted,
            ZillitSocketEvents.Email.TrashEmptied,
        ).forEach { name ->
            assertIs<EmailRealtimeEvent.FolderChanged>(eventFor(name, """{}"""), "$name")
        }
    }

    @Test
    fun `the wire names are colon-delimited, not the web's aliases`() {
        // `inbound_email_received` is what the web re-emits to its own
        // components. Subscribing to that would produce silence, not an error.
        assertEquals("inbound:email:received", ZillitSocketEvents.Email.InboundReceived.value)
        assertEquals("outbound:email:sent", ZillitSocketEvents.Email.OutboundSent.value)
        assertEquals("email:read", ZillitSocketEvents.Email.Read.value)
    }
}
