package com.zillit.desktop

import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageWidgetTest {
    private fun toast(id: String, conversation: String) =
        MessageToast(
            id = id,
            sender = "A",
            preview = "p",
            conversationId = conversation,
            senderId = "u",
            senderName = "A",
            isGroup = false,
        )

    private fun message(body: String, group: Boolean = false, attachment: ChatAttachment? = null) = ChatMessage(
        id = "m", uniqueId = "", senderId = "sam", receiverId = if (group) "room" else "me",
        body = body, timestampMillis = 0L, isMine = false, isGroup = group, attachment = attachment,
    )
    private val names: (String) -> String? = { id -> mapOf("sam" to "Samsung Device", "pat" to "Pat Lee")[id] }
    private val rooms: (String) -> String? = { id -> if (id == "room") "Team Leads" else null }
    private fun attachmentNamed(name: String) = ChatAttachment(media = "m", name = name)

    @Test
    fun `a burst from one conversation keeps one card, newest first`() {
        val stack = mutableListOf<MessageToast>()
        stack.admit(toast("1", "sam"))
        stack.admit(toast("2", "sam"))
        stack.admit(toast("3", "pat"))
        assertEquals(listOf("3", "2"), stack.map { it.id })
    }

    @Test
    fun `three cards at most, the oldest making room`() {
        val stack = mutableListOf<MessageToast>()
        (1..5).forEach { stack.admit(toast("$it", "c$it")) }
        assertEquals(listOf("5", "4", "3"), stack.map { it.id })
    }

    @Test
    fun `the window grows by one card and one gap`() {
        assertEquals(84.dp, stackHeight(1))
        assertEquals((84 * 3 + 8 * 2).dp, stackHeight(3))
    }

    @Test
    fun `the call card names the kind, then the room`() {
        assertEquals("Voice call", describeCall("", hasVideo = false))
        assertEquals("Video call · Camera unit", describeCall("Camera unit", hasVideo = true))
    }

    @Test
    fun `a group card is headed by the room and the person`() {
        assertEquals("Team Leads · Samsung Device", message("hi", group = true).toToast(names, rooms).sender)
        assertEquals("Team Leads", message("hi", group = true).toToast({ null }, rooms).sender)
        assertEquals("New group message", message("hi", group = true).toToast({ null }, { null }).sender)
    }

    @Test
    fun `a direct card is headed by the person, or says what it is`() {
        assertEquals("Samsung Device", message("hi").toToast(names, rooms).sender)
        assertEquals("New message", message("hi").toToast({ null }, rooms).sender)
    }

    @Test
    fun `mentions read as names, and a nameless one as someone`() {
        val toast = message("@{{pat}} and @{{ghost}} what is the status").toToast(names, rooms)
        assertEquals("@Pat Lee and @someone what is the status", toast.preview)
    }

    @Test
    fun `an attachment names the file and a blank body says a message was sent`() {
        val withFile = message("", attachment = attachmentNamed("plan.pdf"))
        assertEquals("Sent plan.pdf", withFile.toToast(names, rooms).preview)
        assertEquals("Sent a message", message("   ").toToast(names, rooms).preview)
    }
}
