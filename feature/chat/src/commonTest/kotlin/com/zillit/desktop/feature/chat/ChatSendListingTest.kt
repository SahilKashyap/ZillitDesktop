package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.ConversationBacklog
import com.zillit.desktop.feature.chat.data.ReadReceipt
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recents shelf when *we* are the one who spoke.
 *
 * Found live: after sending, the listing kept the peer's last line and its
 * old clock — previews and activity only ever learned from arrivals, and the
 * repository never cached our own sends at all, so every wholesale recompute
 * snapped the shelf back to what the peer said last.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendListingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    private fun viewModel(repository: FakeChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${repository.sent.size}" },
    )

    @Test
    fun `the shelf follows the send, before the server answers`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.DraftChanged("On the way up"))
        model.onEvent(ChatEvent.Send)

        // No advanceUntilIdle: the ack has not resolved, and the listing must
        // already have moved — that is what optimistic means for the shelf.
        assertEquals("On the way up", model.currentState.previews[aisha.userId])
        assertEquals(NOW, model.currentState.activity[aisha.userId])
        assertEquals(aisha.userId, model.currentState.recents.first())
    }

    @Test
    fun `a wholesale recompute keeps the sent line, because the ack cached it`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository()
            val model = viewModel(repository)
            model.onEvent(ChatEvent.OpenThread(aisha))
            advanceUntilIdle()

            model.onEvent(ChatEvent.DraftChanged("On the way up"))
            model.onEvent(ChatEvent.Send)
            advanceUntilIdle()

            // Someone *else* speaks, which recomputes the whole listing from
            // the repository — the exact path that used to snap the shelf
            // back to the peer's old line.
            val fromOther = ChatMessage(
                id = "m-other",
                uniqueId = "m-other",
                senderId = "u-vivek",
                receiverId = "me",
                body = "sound rolling",
                timestampMillis = NOW + 1,
                isMine = false,
            )
            repository.rememberArrival(fromOther)
            model.onEvent(ChatEvent.Arrived(fromOther))

            assertEquals("On the way up", model.currentState.previews[aisha.userId])
            assertEquals(NOW, model.currentState.activity[aisha.userId])
        }

    @Test
    fun `a deleted line stops being the preview`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.DraftChanged("Withdraw me"))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()
        val sent = model.currentState.messages.last()

        model.onEvent(ChatEvent.Delete(sent.id))
        advanceUntilIdle()

        // The bubble is gone and the shelf no longer echoes the withdrawn
        // line — it recomputes from the caches, which forgot it first.
        assertEquals(0, model.currentState.messages.count { it.id == sent.id })
        assertEquals(null, model.currentState.previews[aisha.userId])
    }

    @Test
    fun `a refused send still shows the attempt on the shelf`() = runTest(dispatcher) {
        val repository = FakeChatRepository(sendFails = true)
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.DraftChanged("On the way up"))
        model.onEvent(ChatEvent.Send)
        advanceUntilIdle()

        // The bubble says "Not sent"; the shelf still reflects the attempt —
        // the newest thing in that conversation is the failure, not the
        // peer's old line.
        assertEquals("On the way up", model.currentState.previews[aisha.userId])
        assertEquals(
            ChatSendState.Failed,
            model.currentState.messages.last().sendState,
        )
    }

    private companion object {
        const val NOW = 1_786_507_000_000L
    }
}

/**
 * The repository as the view model needs it, mirroring the real contract:
 * [send] caches the message once acked — [newestActivity] and [lastMessageOf]
 * include our own sends from that moment — and an arrival is cached by the
 * caller through [rememberArrival], as the socket path does with `remember`.
 */
internal class FakeChatRepository(
    private val sendFails: Boolean = false,
    /** Answers "socket is not connected" until flipped — the offline tests' lever. */
    var socketDown: Boolean = false,
) : ChatRepository {

    val sent = mutableListOf<String>()
    private val activityByPeer = mutableMapOf<String, Long>()
    private val lastByPeer = mutableMapOf<String, ChatMessage>()

    fun rememberArrival(message: ChatMessage) {
        val peer = if (message.isMine) message.receiverId else message.senderId
        activityByPeer[peer] = message.timestampMillis
        lastByPeer[peer] = message
    }

    override val incoming: Flow<ChatMessage> = emptyFlow()
    override val deletions: Flow<List<String>> = emptyFlow()
    override val connections: Flow<Unit> = emptyFlow()
    override val typing: Flow<Pair<String, Boolean>> = emptyFlow()
    override val receipts: Flow<ReadReceipt> = emptyFlow()
    override val edits: Flow<ChatMessage> = emptyFlow()
    override val selfReads: Flow<String> = emptyFlow()

    override fun selfId(): String? = "me"
    override suspend fun sendReaction(messageId: String, emoji: String): ChatMessage? = null
    override suspend fun join() = Unit
    /** What the socket's user list would answer while it is up. */
    var recents: List<String> = emptyList()

    /** A specific failure for the next sends — the handler tests' lever. */
    var sendError: ZillitError? = null

    override suspend fun recentPeers(): ZillitResult<List<String>> =
        if (socketDown) {
            ZillitResult.Failure(ZillitError.NoConnection("socket is not connected"))
        } else {
            ZillitResult.Success(recents)
        }

    /** What the notification backlog would say — counts and stamps per conversation. */
    var backlog: ConversationBacklog = ConversationBacklog()

    override suspend fun conversationBacklog(): ZillitResult<ConversationBacklog> =
        if (socketDown) {
            ZillitResult.Failure(ZillitError.NoConnection("no network"))
        } else {
            ZillitResult.Success(backlog)
        }

    override fun cached(otherUserId: String): List<ChatMessage>? = null
    override fun lastMessageOf(otherUserId: String): ChatMessage? = lastByPeer[otherUserId]
    /** Both watermark rungs, recorded so a test can tell which one went. */
    val reads = mutableListOf<Pair<String, String>>()
    val delivered2 = mutableListOf<Pair<String, String>>()

    override suspend fun markRead(peerId: String, messageId: String, isGroup: Boolean) {
        reads += peerId to messageId
    }

    override suspend fun markDelivered(peerId: String, messageId: String, isGroup: Boolean) {
        delivered2 += peerId to messageId
    }
    override fun markThreadRead(peerId: String, uptoMillis: Long) = Unit
    override fun unreadCounts(floor: Map<String, Long>): Map<String, Int> = emptyMap()
    override fun newestActivity(): Map<String, Long> = activityByPeer.toMap()
    override suspend fun sendTyping(receiverId: String, started: Boolean) = Unit

    override suspend fun history(
        otherUserId: String,
        nowMillis: Long,
        isGroup: Boolean,
    ): ZillitResult<List<ChatMessage>> = ZillitResult.Success(emptyList())

    /** Every message this fake accepted, in order — the location tests read it. */
    val delivered = mutableListOf<ChatMessage>()

    @Suppress("LongParameterList") // Mirrors ChatRepository.send exactly.
    override suspend fun send(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: ChatAttachment?,
        location: ChatLocation?,
    ): ZillitResult<Unit> {
        if (sendFails) return ZillitResult.Failure(ZillitError.Unknown("refused"))
        sendError?.let { return ZillitResult.Failure(it) }
        if (socketDown) return ZillitResult.Failure(ZillitError.NoConnection("socket is not connected"))
        sent += uniqueId
        val saved = ChatMessage(
            id = uniqueId,
            uniqueId = uniqueId,
            senderId = "me",
            receiverId = receiverId,
            body = body,
            timestampMillis = nowMillis,
            isMine = true,
            sendState = ChatSendState.Sent,
            attachment = attachment,
            location = location,
        )
        delivered += saved
        rememberArrival(saved)
        return ZillitResult.Success(Unit)
    }

    override suspend fun rooms(): ZillitResult<List<GroupRoom>> =
        ZillitResult.Success(emptyList())

    override suspend fun deleteMessages(
        messageIds: List<String>,
        isGroup: Boolean,
    ): ZillitResult<List<String>> {
        // Mirrors the real contract: the caches forget before the answer.
        val gone = messageIds.toSet()
        lastByPeer.entries.removeAll { it.value.id in gone }
        // Activity is recomputed from the surviving newest rows.
        activityByPeer.clear()
        lastByPeer.forEach { (peer, message) -> activityByPeer[peer] = message.timestampMillis }
        return ZillitResult.Success(messageIds)
    }
}
