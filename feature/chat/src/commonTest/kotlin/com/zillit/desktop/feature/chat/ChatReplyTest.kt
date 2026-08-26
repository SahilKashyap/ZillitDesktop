package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.ReplyAwareChatRepository
import com.zillit.desktop.feature.chat.data.readReplyRef
import com.zillit.desktop.feature.chat.data.sendEnvelope
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatReplyRef
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * QA #4: Reply quotes a message — the composer shows the quote, the send
 * carries Android's `Reply_chat` reference (`ChatAndGroupVM.kt:451-459`),
 * and the bubble renders what it quotes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReplyTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha =
        com.zillit.desktop.feature.chat.domain.CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The fake with the reply capability bolted on, recording what it carried. */
    private class ReplyFake(
        val base: FakeChatRepository = FakeChatRepository(),
    ) : ChatRepository by base, ReplyAwareChatRepository {
        val replies = mutableListOf<ChatReplyRef>()

        @Suppress("LongParameterList")
        override suspend fun sendWithReply(
            receiverId: String,
            body: String,
            uniqueId: String,
            nowMillis: Long,
            isGroup: Boolean,
            attachment: ChatAttachment?,
            replyTo: ChatReplyRef,
            location: com.zillit.desktop.feature.chat.domain.ChatLocation?,
        ): ZillitResult<Unit> {
            replies += replyTo
            return base.send(receiverId, body, uniqueId, nowMillis, isGroup, attachment, location)
        }
    }

    private fun viewModel(repository: ChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { 2_000L },
        newUniqueId = { "unique-1" },
    )

    private val parent = ChatMessage(
        id = "srv-9",
        uniqueId = "w-9",
        senderId = "u-aisha",
        receiverId = "me",
        body = "Where do we park the vans?",
        timestampMillis = 1_500L,
        isMine = false,
    )

    @Test
    fun `Reply quotes the parent and the send carries its reference`() = runTest(dispatcher) {
        val repository = ReplyFake()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(parent))

        model.onEvent(ChatEvent.StartReply("srv-9"))
        assertEquals(parent.body, model.currentState.replyTo?.body, "the composer quotes the parent")

        model.onEvent(ChatEvent.DraftChanged("Behind the church"))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()

        val carried = repository.replies.single()
        assertEquals("srv-9", carried.messageId, "the parent's server id rides the wire")
        assertEquals("u-aisha", carried.senderId)
        assertEquals(parent.body, carried.body)
        assertNull(model.currentState.replyTo, "the reply bar clears after the send")
        val bubble = model.currentState.messages.last()
        assertEquals("srv-9", bubble.replyTo?.messageId, "the sent bubble renders its quote")
    }

    @Test
    fun `cancel returns the composer to a plain line`() = runTest(dispatcher) {
        val repository = ReplyFake()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(parent))

        model.onEvent(ChatEvent.StartReply("srv-9"))
        model.onEvent(ChatEvent.CancelReply)
        model.onEvent(ChatEvent.DraftChanged("Plain"))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()

        assertEquals(0, repository.replies.size, "no reference travelled")
        assertEquals(1, repository.base.sent.size)
    }

    @Test
    fun `a repository without the capability still sends, plainly`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(parent))

        model.onEvent(ChatEvent.StartReply("srv-9"))
        model.onEvent(ChatEvent.DraftChanged("Behind the church"))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()

        assertEquals(1, repository.sent.size, "the message still went")
        assertNotNull(model.currentState.messages.last().replyTo, "the local bubble keeps its quote")
    }

    // -- the wire shape, against Android's field names ---------------------------

    @Test
    fun `the envelope carries the parent id as a string under reply`() {
        // The web sends `reply: parentChatId` (`cncUtil.js:197`); the object
        // form is what the server RETURNS. Sending the object was refused
        // live with `cnc_invalid_message_id` (2026-08-22).
        val envelope = sendEnvelope(
            projectId = "p1",
            uniqueId = "u1",
            senderId = "me",
            receiverId = "peer",
            cipherBody = "cipher",
            nowMillis = 1_000L,
            replyToId = "656565656565656565656565",
        )
        assertEquals(
            "656565656565656565656565",
            (envelope["reply"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }

    @Test
    fun `readReplyRef decrypts the quote and names a file-only parent`() {
        val message = buildJsonObject {
            put(
                "reply",
                buildJsonObject {
                    put("message_id", "srv-3")
                    put("message", "enc:the words")
                    put("message_type", "image")
                    put("sender", "u-7")
                    put("attachment", buildJsonObject { put("name", "slate.jpg") })
                },
            )
        }
        val ref = readReplyRef(message) { it.removePrefix("enc:") }
        assertNotNull(ref)
        assertEquals("srv-3", ref.messageId)
        assertEquals("the words", ref.body)
        assertEquals("image", ref.kind)
        assertEquals("u-7", ref.senderId)
        assertEquals("slate.jpg", ref.attachmentName)

        assertNull(readReplyRef(buildJsonObject {}) { it }, "no reply object, no reference")
    }
}
