package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardPick
import com.zillit.desktop.core.workspace.forward.ForwardReach
import com.zillit.desktop.core.workspace.forward.ForwardSection
import com.zillit.desktop.core.workspace.forward.ForwardStep
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forwarding out of a thread, with the host's reach wired: the chat's own
 * people and rooms go through the thread's own send, everything else through
 * the host, and both halves of one Send are honoured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatForwardReachTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Records every send the thread made, destination and all. */
    private class SendSpy(val base: FakeChatRepository = FakeChatRepository()) : ChatRepository by base {
        val sends = mutableListOf<Triple<String, Boolean, String>>()

        override suspend fun send(
            receiverId: String,
            body: String,
            uniqueId: String,
            nowMillis: Long,
            isGroup: Boolean,
            attachment: ChatAttachment?,
            location: com.zillit.desktop.feature.chat.domain.ChatLocation?,
        ): ZillitResult<Unit> {
            sends += Triple(receiverId, isGroup, body)
            return base.send(receiverId, body, uniqueId, nowMillis, isGroup, attachment, location)
        }
    }

    private class FakeReach : ForwardReach {
        val asked = mutableListOf<Pair<ForwardKind, String?>>()
        val delivered = mutableListOf<List<String>>()
        val opened = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun sections(item: ForwardItem, from: String?): List<ForwardSection> {
            asked += item.kind to from
            return listOf(
                ForwardSection("Notices", listOf(ForwardPick(id = "board:home:u1", label = "General"))),
                ForwardSection("Email", listOf(ForwardStep(id = "email", label = "Forward as email"))),
            )
        }

        override suspend fun deliver(targetIds: List<String>, item: ForwardItem): ZillitResult<Unit> {
            delivered += targetIds
            return ZillitResult.Success(Unit)
        }

        override suspend fun open(targetId: String, item: ForwardItem): ForwardStepOutcome {
            opened += targetId
            return ForwardStepOutcome.Handled
        }

        override suspend fun submit(
            targetId: String,
            item: ForwardItem,
            answers: Map<String, String>,
        ): ZillitResult<Unit> {
            submitted += targetId to answers
            return ZillitResult.Success(Unit)
        }
    }

    @Test
    fun `the host is asked what else this line can reach, and told who is asking`() = runTest(dispatcher) {
        val reach = FakeReach()
        val model = viewModel(SendSpy(), reach)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Call sheet is out")))

        model.onEvent(ChatEvent.StartForward("srv-1"))
        advanceUntilIdle()

        assertEquals(ForwardKind.Text to "chat", reach.asked.single())
        assertEquals(2, model.currentState.forwardSections.size)
    }

    @Test
    fun `one Send splits between the thread's own route and the host's`() = runTest(dispatcher) {
        val repository = SendSpy()
        val reach = FakeReach()
        val model = viewModel(repository, reach)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Call sheet is out")))

        model.onEvent(ChatEvent.StartForward("srv-1"))
        model.onEvent(
            ChatEvent.ForwardPicked(listOf("user:u-vivek", "room:room-1", "board:home:u1")),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("u-vivek" to false, "room-1" to true),
            repository.sends.map { it.first to it.second },
        )
        assertEquals(listOf(listOf("board:home:u1")), reach.delivered)
        assertNull(model.currentState.forwarding, "the picker closed")
    }

    @Test
    fun `a host-only Send still closes the picker and says so`() = runTest(dispatcher) {
        val repository = SendSpy()
        val reach = FakeReach()
        val model = viewModel(repository, reach)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Call sheet is out")))

        model.onEvent(ChatEvent.StartForward("srv-1"))
        model.onEvent(ChatEvent.ForwardPicked(listOf("board:home:u1")))
        advanceUntilIdle()

        assertTrue(repository.sends.isEmpty(), "nothing went through the thread")
        assertEquals(listOf(listOf("board:home:u1")), reach.delivered)
        assertNull(model.currentState.forwarding)
    }

    @Test
    fun `an entry that asks something first hands over to the host`() = runTest(dispatcher) {
        val repository = SendSpy()
        val reach = FakeReach()
        val model = viewModel(repository, reach)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Call sheet is out")))

        model.onEvent(ChatEvent.StartForward("srv-1"))
        model.onEvent(ChatEvent.ForwardStepPicked("email"))
        advanceUntilIdle()

        assertEquals(listOf("email"), reach.opened)
        assertTrue(repository.sends.isEmpty())
        assertNull(model.currentState.forwarding)
    }

    private fun viewModel(repository: ChatRepository, reach: ForwardReach?) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${counter++}" },
        isAdmin = { false },
        reach = reach,
    )

    private var counter = 1

    private fun mine(id: String, body: String) = ChatMessage(
        id = id, uniqueId = "w-$id", senderId = "me", receiverId = "u-aisha",
        body = body, timestampMillis = NOW - 60_000L, isMine = true,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
