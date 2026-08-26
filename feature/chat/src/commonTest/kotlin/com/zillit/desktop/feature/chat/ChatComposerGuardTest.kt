package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatComposerRules
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guards where the user meets them: a refusal has to both stop the send
 * and say something, or it reads as the app ignoring the click.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposerGuardTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Over the ceiling: nothing leaves, and the sender is told why. */
    @Test
    fun `too long a line is refused, not sent`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.DraftChanged("x".repeat(2001)))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()

        assertTrue(repository.delivered.isEmpty(), "nothing may reach the wire")
        assertEquals(ChatComposerRules.BODY_TOO_LONG, model.state.value.error)
    }

    /** The line that just fits still goes, ceiling included. */
    @Test
    fun `a line at the ceiling still sends`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.DraftChanged("x".repeat(2000)))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()

        assertEquals(1, repository.delivered.size)
        assertNull(model.state.value.error)
    }

    /**
     * The picker turned a file away for its size. Before, the dialog simply
     * closed and nothing happened; now the reason arrives.
     */
    @Test
    fun `a file refused by the picker says why`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository, pick = ChatPick.Refused(ChatComposerRules.ATTACHMENT_TOO_LARGE))
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        assertEquals(ChatComposerRules.ATTACHMENT_TOO_LARGE, model.state.value.error)
        assertNull(model.state.value.pendingPreview, "no preview for a refused file")
    }

    /** An executable never reaches the preview, whatever the picker allowed. */
    @Test
    fun `an executable is refused at the composer`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val exe = PendingChatUpload("setup.exe", "application/octet-stream", ByteArray(16)) { _, _ -> null }
        val model = viewModel(repository, pick = ChatPick.Ready(exe))
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        assertEquals(ChatComposerRules.ATTACHMENT_REFUSED_TYPE, model.state.value.error)
        assertNull(model.state.value.pendingPreview)
    }

    /** A cancelled dialog is not an error — it is a shrug. */
    @Test
    fun `a cancelled pick says nothing`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository, pick = ChatPick.Cancelled)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        assertNull(model.state.value.error)
        assertNull(model.state.value.pendingPreview)
    }

    /** An ordinary file still reaches the preview, guards and all. */
    @Test
    fun `an ordinary file still opens the preview`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val pdf = PendingChatUpload("callsheet.pdf", "application/pdf", ByteArray(64)) { _, _ -> null }
        val model = viewModel(repository, pick = ChatPick.Ready(pdf))
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.AttachFile)
        advanceUntilIdle()

        assertNotNull(model.state.value.pendingPreview)
        assertNull(model.state.value.error)
    }

    private fun viewModel(
        repository: FakeChatRepository,
        pick: ChatPick = ChatPick.Cancelled,
    ) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${repository.sent.size}" },
        pickAttachment = { pick },
    )

    private companion object {
        const val NOW = 1_786_507_000_000L
    }
}
