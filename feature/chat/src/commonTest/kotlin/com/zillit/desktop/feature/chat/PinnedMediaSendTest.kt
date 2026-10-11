package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.MediaSendTracker
import com.zillit.desktop.core.media.PreviewResult
import com.zillit.desktop.core.media.SendStatus
import com.zillit.desktop.core.media.VideoSend
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.PinnedChatMedia
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

/**
 * A media send stays with the production it was made in. Before the pin, a
 * send that outlived a project switch was written into the NEW production's
 * chat — the old production's peer, under the new production's id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinnedMediaSendTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakePin(override val projectId: String) : PinnedChatMedia {
        override val projectName = "Production $projectId"
        val bytesArrive = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var videoSend: VideoSend? = null

        override suspend fun upload(
            name: String,
            contentType: String,
            bytes: ByteArray,
            videoSend: VideoSend?,
            onProgress: (Int) -> Unit,
        ): ChatAttachment {
            this.videoSend = videoSend
            onProgress(40)
            bytesArrive.await()
            return ChatAttachment(media = "s3/$name", name = name, contentType = contentType)
        }

        override suspend fun send(
            peerId: String,
            body: String,
            uniqueId: String,
            timestampMillis: Long,
            isGroup: Boolean,
            attachment: ChatAttachment,
        ): ZillitResult<Unit> {
            sent += peerId
            return ZillitResult.Success(Unit)
        }
    }

    @Test
    fun `a send that outlives a project switch lands in the production it was made in`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val tracker = MediaSendTracker()
        var open = "A"
        val pinA = FakePin("A")
        val model = ChatViewModel(
            repository = repository,
            nowMillis = { 1_000L },
            newUniqueId = { "unique-1" },
            presenceProjectId = { open },
            pinMedia = { FakePin(open).takeIf { open != "A" } ?: pinA },
            sendTracker = tracker,
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.ImagePasted("rushes.mov", "video/quicktime", byteArrayOf(1)))
        advanceUntilIdle()
        model.onEvent(
            ChatEvent.PreviewSend(
                PreviewResult("rushes.mov", "video/quicktime", byteArrayOf(1), videoSend = VideoSend.Original),
                caption = "",
            ),
        )
        advanceUntilIdle()
        assertEquals("Production A", tracker.sends.value.single().projectName)
        assertEquals(40, tracker.sends.value.single().progress)

        // The user moves to production B while the video is still uploading.
        open = "B"
        model.onEvent(ChatEvent.ProjectChanged)
        advanceUntilIdle()
        pinA.bytesArrive.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("u-aisha"), pinA.sent, "written into A, where it was sent")
        assertTrue(repository.sent.isEmpty(), "never through the repository that now follows B")
        assertEquals(VideoSend.Original, pinA.videoSend, "the preview's answer reached the upload")
        assertEquals(SendStatus.Done, tracker.sends.value.single().status)
        assertTrue(model.currentState.messages.isEmpty(), "B's screen never shows A's bubble")
    }

    @Test
    fun `cancel from the tray stops the send and removes its bubble`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val tracker = MediaSendTracker()
        val pin = FakePin("A")
        val model = ChatViewModel(
            repository = repository,
            nowMillis = { 1_000L },
            newUniqueId = { "unique-1" },
            presenceProjectId = { "A" },
            pinMedia = { pin },
            sendTracker = tracker,
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.ImagePasted("rushes.mov", "video/quicktime", byteArrayOf(1)))
        advanceUntilIdle()
        model.onEvent(ChatEvent.PreviewSend(PreviewResult("rushes.mov", "video/quicktime", byteArrayOf(1)), ""))
        advanceUntilIdle()

        tracker.cancel("unique-1")
        pin.bytesArrive.complete(Unit)
        advanceUntilIdle()

        assertTrue(pin.sent.isEmpty())
        assertTrue(tracker.sends.value.isEmpty())
        assertTrue(model.currentState.messages.isEmpty())
    }
}
