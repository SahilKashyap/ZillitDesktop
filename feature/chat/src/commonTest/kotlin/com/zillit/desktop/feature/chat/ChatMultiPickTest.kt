package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.PreviewResult
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatPick
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.PendingChatUpload
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CLOCK = 1_786_507_000_000L

/**
 * WhatsApp's multi-select: several files from one pick fill one preview;
 * the preview's "+" adds more rather than replacing what is there; and on
 * Send each file goes as its own message with its own caption.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMultiPickTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun upload(name: String, type: String) = PendingChatUpload(name, type, byteArrayOf(1)) { _, _ ->
        ChatAttachment(media = "s3/$name", name = name, contentType = type)
    }

    private val photo = upload("set.jpg", "image/jpeg")
    private val sheet = upload("call-sheet.pdf", "application/pdf")
    private val clip = upload("blocking.mp4", "video/mp4")

    @Test
    fun `one pick of several opens them together, and plus adds to them`() = runTest(dispatcher) {
        val picks = ArrayDeque(listOf(ChatPick.Ready(photo, more = listOf(sheet)), ChatPick.Ready(clip)))
        val model = ChatViewModel(
            repository = FakeChatRepository(),
            nowMillis = { CLOCK },
            newUniqueId = { "uid" },
            pickAttachmentOf = { picks.removeFirst() },
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.AttachKind(PreviewKind.Image))
        advanceUntilIdle()
        assertEquals(photo, model.currentState.pendingPreview)
        assertEquals(listOf(sheet), model.currentState.droppedAlong)

        // The preview's "+": the same event while the preview is open.
        model.onEvent(ChatEvent.AttachKind(PreviewKind.Video))
        advanceUntilIdle()
        assertEquals(photo, model.currentState.pendingPreview, "what was there stays first")
        assertEquals(listOf(sheet, clip), model.currentState.droppedAlong)
    }

    @Test
    fun `each file goes as its own message with its own caption`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = ChatViewModel(
            repository = repository,
            nowMillis = { CLOCK },
            newUniqueId = { "uid-${repository.sent.size}-${System.nanoTime()}" },
            pickAttachmentOf = { ChatPick.Ready(photo, more = listOf(sheet)) },
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.AttachKind(PreviewKind.Image))
        advanceUntilIdle()

        model.onEvent(
            ChatEvent.PreviewSend(
                PreviewResult("set.jpg", "image/jpeg", byteArrayOf(1), caption = "Wide of the set"),
                caption = "Wide of the set",
                more = listOf(PreviewResult("call-sheet.pdf", "application/pdf", byteArrayOf(1), caption = "Day 12")),
            ),
        )
        advanceUntilIdle()

        val bubbles = model.currentState.messages.sortedBy { it.attachment?.name }
        assertEquals(listOf("call-sheet.pdf", "set.jpg"), bubbles.map { it.attachment?.name })
        assertEquals(listOf("Day 12", "Wide of the set"), bubbles.map { it.body })
    }
}
