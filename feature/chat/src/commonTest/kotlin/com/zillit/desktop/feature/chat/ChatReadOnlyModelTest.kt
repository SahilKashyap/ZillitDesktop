package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
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
import kotlin.test.assertTrue

/**
 * The File Cabinet's live conversation looks and writes nothing: opening a
 * thread or receiving into it sends no read receipt, `read_untill`, delivered
 * ack, local read mark or badge refresh. The same events on an ordinary model
 * do all of them, so the silence is the flag's doing and not the fake's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReadOnlyModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun line(n: Int, at: Long) = ChatMessage(
        id = "m-$n", uniqueId = "m-$n", senderId = "u-aisha", receiverId = "me",
        body = "line $n", timestampMillis = at, isMine = false,
    )

    /** History that has lines, and a record of every receipt-shaped write. */
    private class Recording(val base: FakeChatRepository = FakeChatRepository()) : ChatRepository by base {
        val writes = mutableListOf<String>()

        override suspend fun history(
            otherUserId: String,
            nowMillis: Long,
            isGroup: Boolean,
        ): ZillitResult<List<ChatMessage>> = ZillitResult.Success(
            listOf(
                ChatMessage("m-1", "m-1", "u-aisha", "me", "one", 1_000L, isMine = false, isGroup = isGroup),
                ChatMessage("m-2", "m-2", "u-aisha", "me", "two", 2_000L, isMine = false, isGroup = isGroup),
            ),
        )

        override suspend fun markRead(peerId: String, messageId: String, isGroup: Boolean): Boolean {
            writes += "markRead $peerId $messageId"
            return true
        }

        override suspend fun markDelivered(peerId: String, messageId: String, isGroup: Boolean) {
            writes += "markDelivered $peerId $messageId"
        }

        override fun markThreadRead(peerId: String, uptoMillis: Long) {
            writes += "markThreadRead $peerId"
        }
    }

    private fun model(repository: ChatRepository, readOnly: Boolean, onRead: MutableList<String>) = ChatViewModel(
        repository = repository,
        nowMillis = { 5_000L },
        newUniqueId = { "unique-1" },
        onThreadRead = { onRead += it },
        sectionBadges = { onRead += "badges"; emptyMap() },
        readOnly = readOnly,
    )

    @Test
    fun `an ordinary model reads the thread it opens, as the control`() = runTest(dispatcher) {
        val repository = Recording()
        val badges = mutableListOf<String>()
        val model = model(repository, readOnly = false, onRead = badges)

        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(line(3, 3_000L)))
        advanceUntilIdle()

        assertTrue(repository.writes.any { it.startsWith("markRead") }, "${repository.writes}")
        assertTrue(repository.writes.any { it.startsWith("markThreadRead") })
        assertTrue(badges.contains("u-aisha"))
    }

    @Test
    fun `a read-only model opens a person and a room and writes no receipt of any kind`() =
        runTest(dispatcher) {
            val repository = Recording()
            val badges = mutableListOf<String>()
            val model = model(repository, readOnly = true, onRead = badges)

            model.onEvent(ChatEvent.OpenThread(aisha))
            advanceUntilIdle()
            assertEquals(2, model.currentState.messages.size, "it still loads and shows the thread")

            model.onEvent(ChatEvent.OpenGroup(GroupRoom(id = "room-1", name = "Budget")))
            advanceUntilIdle()
            // A line arriving in the open thread, and one for a thread that is not open.
            model.onEvent(ChatEvent.Arrived(line(3, 3_000L)))
            model.onEvent(ChatEvent.Arrived(line(4, 4_000L).copy(senderId = "u-other")))
            advanceUntilIdle()

            assertEquals(emptyList<String>(), repository.writes, "no read, delivered or read-mark write")
            assertEquals(emptyList<String>(), badges, "no badge read or refresh")
        }
}
