package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.designsystem.component.DroppedFile
import com.zillit.desktop.core.media.PreviewResult
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
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
        // A null pick is the dialog dismissed, as it was before the seam
        // learned to say why nothing came back.
        pickAttachment = {
            pending?.let { com.zillit.desktop.feature.chat.domain.ChatPick.Ready(it) }
                ?: com.zillit.desktop.feature.chat.domain.ChatPick.Cancelled
        },
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

    /** A model whose uploader notes which file each upload was named for. */
    private fun dropModel(repository: FakeChatRepository, uploaded: MutableList<String>): ChatViewModel {
        var next = 0
        return ChatViewModel(
            repository = repository,
            nowMillis = { NOW },
            // A drop puts several bubbles up before any send lands, so the ids
            // cannot lean on the send count the other tests use.
            newUniqueId = { "unique-${next++}" },
            uploadMedia = { name, type, _, _ ->
                uploaded += name
                ChatAttachment(media = "s3/$name", name = name, contentType = type)
            },
        )
    }

    private val picture = DroppedFile("set.jpg", "image/jpeg", byteArrayOf(1))
    private val pages = DroppedFile("sides.pdf", "application/pdf", byteArrayOf(2))

    @Test
    fun `dropped files share one preview and send a message each, the caption on the first`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val uploaded = mutableListOf<String>()
        val model = dropModel(repository, uploaded)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.FilesDropped(listOf(picture, pages)))
        advanceUntilIdle()
        assertEquals("set.jpg", model.currentState.pendingPreview?.name)
        assertEquals(listOf("sides.pdf"), model.currentState.droppedAlong.map { it.name })
        assertTrue(uploaded.isEmpty(), "nothing uploads before Send")

        model.onEvent(
            ChatEvent.PreviewSend(
                PreviewResult("set.jpg", "image/jpeg", byteArrayOf(1)),
                caption = "Today's pages",
                more = listOf(PreviewResult("sides.pdf", "application/pdf", byteArrayOf(2))),
            ),
        )
        advanceUntilIdle()

        assertEquals(setOf("set.jpg", "sides.pdf"), uploaded.toSet())
        assertEquals(2, repository.sent.size, "one message per file")
        assertEquals(listOf("Today's pages", ""), model.currentState.messages.map { it.body })
        assertNull(model.currentState.pendingPreview)
        assertTrue(model.currentState.droppedAlong.isEmpty())
    }

    @Test
    fun `a dropped file kept alone in the preview uploads under its own name`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val uploaded = mutableListOf<String>()
        val model = dropModel(repository, uploaded)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.FilesDropped(listOf(picture, pages)))
        advanceUntilIdle()

        // The picture was removed in the preview; only the PDF comes back.
        model.onEvent(ChatEvent.PreviewSend(PreviewResult("sides.pdf", "application/pdf", byteArrayOf(2)), ""))
        advanceUntilIdle()

        assertEquals(listOf("sides.pdf"), uploaded)
        assertEquals("sides.pdf", model.currentState.messages.single().attachment?.name)
    }

    @Test
    fun `a dropped file over the ceiling is refused by its size and the rest still preview`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = dropModel(repository, mutableListOf())
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        // Past the ceiling the drop hands the file over unread, with its size.
        val rushes = DroppedFile(
            name = "rushes.mov",
            contentType = "video/quicktime",
            bytes = ByteArray(0),
            sizeBytes = ChatComposerRules.MAX_ATTACHMENT_BYTES + 1,
        )
        model.onEvent(ChatEvent.FilesDropped(listOf(rushes, picture)))
        advanceUntilIdle()

        assertEquals(ChatComposerRules.ATTACHMENT_TOO_LARGE, model.currentState.error)
        assertEquals("set.jpg", model.currentState.pendingPreview?.name)
        assertTrue(model.currentState.droppedAlong.isEmpty())
        assertTrue(repository.sent.isEmpty())
    }
}

private const val NOW = 1_700_000_000_000L
