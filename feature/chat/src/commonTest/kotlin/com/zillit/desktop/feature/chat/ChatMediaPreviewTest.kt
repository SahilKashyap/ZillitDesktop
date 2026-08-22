package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.media.PreviewResult
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatSendState
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA #2: a picked (or pasted) file goes to the preview dialog, not straight
 * to the wire — nothing uploads until the dialog's Send, the caption becomes
 * the message body, and Cancel drops the pick entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaPreviewTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val stored = ChatAttachment(media = "s3/key", name = "set.jpg", contentType = "image/jpeg")

    /** Records what the upload was handed, and answers with the stored file. */
    private class RecordingUpload {
        val uploads = mutableListOf<ByteArray>()
    }

    private fun pending(record: RecordingUpload, stored: ChatAttachment?) = PendingChatUpload(
        name = "set.jpg",
        contentType = "image/jpeg",
        bytes = byteArrayOf(1, 2, 3),
    ) { bytes, _ ->
        record.uploads += bytes
        stored
    }

    private fun viewModel(
        repository: FakeChatRepository,
        pending: PendingChatUpload?,
    ) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${repository.sent.size}" },
        pickAttachment = { pending },
    )

    @Test
    fun `the paperclip opens the preview and sends nothing yet`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val record = RecordingUpload()
        val model = viewModel(repository, pending(record, stored))
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        assertNotNull(model.currentState.pendingPreview, "the pick waits in the preview")
        assertEquals("set.jpg", model.currentState.pendingPreview?.name)
        assertTrue(repository.sent.isEmpty(), "nothing rode the socket")
        assertTrue(record.uploads.isEmpty(), "nothing uploaded")
        assertTrue(model.currentState.messages.isEmpty(), "no bubble before Send")
    }

    @Test
    fun `Send uploads the edited bytes and the caption becomes the body`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val record = RecordingUpload()
        val model = viewModel(repository, pending(record, stored))
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        // The editor re-encoded the picture: different bytes come back.
        val edited = byteArrayOf(9, 9, 9)
        model.onEvent(
            ChatEvent.PreviewSend(
                PreviewResult("set.jpg", "image/jpeg", edited),
                caption = "First setup of the day",
            ),
        )
        advanceUntilIdle()

        assertContentEquals(edited, record.uploads.single(), "the edit, not the original, uploaded")
        assertEquals(1, repository.sent.size)
        val bubble = model.currentState.messages.single()
        assertEquals("First setup of the day", bubble.body, "the caption rides as the body")
        assertEquals(stored, bubble.attachment, "the bubble wears the stored file")
        assertEquals(ChatSendState.Sent, bubble.sendState)
        assertNull(model.currentState.pendingPreview)
    }

    @Test
    fun `Cancel drops the pick without a bubble or an upload`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val record = RecordingUpload()
        val model = viewModel(repository, pending(record, stored))
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        model.onEvent(ChatEvent.PreviewCancelled)
        advanceUntilIdle()

        assertNull(model.currentState.pendingPreview)
        assertTrue(model.currentState.messages.isEmpty())
        assertTrue(record.uploads.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `a pasted image opens the preview and uploads through the host seam`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val pasted = mutableListOf<ByteArray>()
        val model = ChatViewModel(
            repository = repository,
            nowMillis = { NOW },
            newUniqueId = { "unique-${repository.sent.size}" },
            uploadMedia = { _, _, bytes, _ ->
                pasted += bytes
                stored
            },
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.ImagePasted("Pasted image.png", "image/png", byteArrayOf(7, 7)))
        advanceUntilIdle()
        assertEquals("Pasted image.png", model.currentState.pendingPreview?.name)

        model.onEvent(
            ChatEvent.PreviewSend(
                PreviewResult("Pasted image.png", "image/png", byteArrayOf(7, 7)),
                caption = "",
            ),
        )
        advanceUntilIdle()

        assertContentEquals(byteArrayOf(7, 7), pasted.single(), "the paste rode the uploadMedia seam")
        assertEquals(1, repository.sent.size)
        assertEquals(ChatSendState.Sent, model.currentState.messages.single().sendState)
    }
}

private const val NOW = 1_700_000_000_000L
