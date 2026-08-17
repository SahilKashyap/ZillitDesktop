package com.zillit.desktop.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The wording of every banner the app posts. */
class NotificationCopyTest {

    @Test
    fun `a direct message is titled by its sender, a group message by both`() {
        assertEquals("Vivek Mishra", NotificationCopy.message("Vivek Mishra", "on my way").title)
        assertEquals(
            "Vivek Mishra · Camera Unit",
            NotificationCopy.message("Vivek Mishra", "on my way", room = "Camera Unit").title,
        )
    }

    @Test
    fun `an attachment with no words shows the filename, not a bare clip`() {
        assertEquals(
            "📎 callsheet-day12.pdf",
            NotificationCopy.message("Vivek", body = "  ", attachmentName = "callsheet-day12.pdf").body,
        )
        // Words win: the file is visible once the thread is open anyway.
        assertEquals(
            "here it is",
            NotificationCopy.message("Vivek", body = "here it is", attachmentName = "x.pdf").body,
        )
    }

    @Test
    fun `an attachment with neither words nor a name still says something`() {
        assertEquals("Sent an attachment", NotificationCopy.message("Vivek", body = "").body)
    }

    @Test
    fun `a nameless sender is named rather than left blank`() {
        assertEquals(NotificationCopy.UNKNOWN_SENDER, NotificationCopy.message("", "hello").title)
        assertEquals(NotificationCopy.UNKNOWN_SENDER, NotificationCopy.notice("   ", "posted").title)
    }

    @Test
    fun `an edit carries the new text, and says it is an edit`() {
        val note = NotificationCopy.messageEdited("Vivek", "call time is 0630", room = "Camera Unit")
        assertEquals("Vivek · Camera Unit (edited)", note.title)
        assertEquals("call time is 0630", note.body)
        // An edit that empties a message still has to read as something.
        assertEquals("Message updated", NotificationCopy.messageEdited("Vivek", "").body)
    }

    @Test
    fun `mail says only what the payload actually carries`() {
        assertEquals("In Inbox", NotificationCopy.mail("Inbox").body)
        assertEquals("You have new mail", NotificationCopy.mail(null).body)
    }

    @Test
    fun `a call names the caller, and the room only when it adds something`() {
        assertEquals("Incoming call", NotificationCopy.incomingCall("Vivek").title)
        assertEquals(
            "Incoming video call",
            NotificationCopy.incomingCall("Vivek", hasVideo = true).title,
        )
        assertEquals(
            "Vivek · Camera Unit",
            NotificationCopy.incomingCall("Vivek", room = "Camera Unit").body,
        )
        // A 1:1 call's "room" is the other person — printing them twice is noise.
        assertEquals("Vivek", NotificationCopy.incomingCall("Vivek", room = "Vivek").body)
    }

    @Test
    fun `a ring is louder than the rest`() {
        assertEquals(NotificationKind.Warning, NotificationCopy.incomingCall("Vivek").kind)
        assertEquals(NotificationKind.Info, NotificationCopy.message("Vivek", "hi").kind)
    }

    @Test
    fun `a notification never prints what it carries`() {
        val rendered = NotificationCopy.message("Vivek Mishra", "the twist is on page 40").toString()
        assertFalse(rendered.contains("Vivek"))
        assertFalse(rendered.contains("page 40"))
    }
}
