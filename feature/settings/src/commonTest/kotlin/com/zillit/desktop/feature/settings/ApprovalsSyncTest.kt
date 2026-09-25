package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.approvals.ApprovalsEvent
import com.zillit.desktop.feature.settings.approvals.ApprovalsRepository
import com.zillit.desktop.feature.settings.approvals.ApprovalsViewModel
import com.zillit.desktop.feature.settings.approvals.PendingApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The approval queues stay current: read on every visit, and re-read when the
 * socket says a request arrived or another admin decided one.
 *
 * Both queues used to be read once per session. An admin who left the page and
 * came back saw the list from their first visit, and one who stayed on it never
 * saw a new request until they pressed Refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun request(id: String, name: String = "Priya Nair") =
        PendingApproval(id = id, userId = "user-$id", fullName = name)

    /** Records which queue each read was for; a decision can be held open. */
    private class Repository(var listing: List<PendingApproval> = emptyList()) : ApprovalsRepository {
        val reads = mutableListOf<ApprovalQueue>()
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun pending(queue: ApprovalQueue): ZillitResult<List<PendingApproval>> {
            reads += queue
            return ZillitResult.Success(listing)
        }

        override suspend fun decide(
            queue: ApprovalQueue,
            request: PendingApproval,
            approved: Boolean,
        ): ZillitResult<Unit> {
            hold?.await()
            return ZillitResult.Success(Unit)
        }
    }

    /** Only `messages` matters: it is all SocketEventBus.onAny reads. */
    private class Socket : SocketClient {
        private val flow = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 16)
        override val connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("test"))
        override val messages: Flow<SocketMessage> = flow
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success(Unit)
        override suspend fun emit(event: SocketEventName) = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success<JsonElement>(JsonObject(emptyMap()))

        suspend fun say(event: String) = flow.emit(SocketMessage(SocketEventName(event), null))
    }

    private val socket = Socket()
    private fun viewModel(repository: Repository) =
        ApprovalsViewModel(repository, events = SocketEventBus(socket))

    @Test
    fun `a join request arriving re-reads the new crew queue`() = runTest {
        val repository = Repository(listOf(request("a")))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        repository.listing = listOf(request("a"), request("b", "Sam Reed"))
        socket.say("project:user:join:request:received")
        advanceUntilIdle()

        assertEquals(listOf(ApprovalQueue.NewCrew, ApprovalQueue.NewCrew), repository.reads)
        assertEquals(listOf("a", "b"), approvals.state.value.crew.items.map { it.id })
    }

    @Test
    fun `a profile change arriving re-reads only the profile queue`() = runTest {
        val repository = Repository()
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.ProfileChanges))
        advanceUntilIdle()
        repository.reads.clear()

        socket.say("project:user:profile:change:requested")
        advanceUntilIdle()

        assertEquals(listOf(ApprovalQueue.ProfileChanges), repository.reads)
    }

    @Test
    fun `a request another admin decided leaves the queue`() = runTest {
        val repository = Repository(listOf(request("a")))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        repository.listing = emptyList()
        socket.say("project:user:join:request:accepted")
        advanceUntilIdle()

        assertTrue(approvals.state.value.crew.items.isEmpty(), "must not be decided twice")
    }

    @Test
    fun `a queue nobody has opened is not fetched for an event`() = runTest {
        val repository = Repository()
        viewModel(repository)
        advanceUntilIdle()

        socket.say("project:user:join:request:received")
        advanceUntilIdle()

        assertTrue(repository.reads.isEmpty())
    }

    /** What the old read-once rule was protecting — now protected directly. */
    @Test
    fun `nothing is re-read while a decision is going through`() = runTest {
        val repository = Repository(listOf(request("a")))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        repository.reads.clear()

        repository.hold = CompletableDeferred()
        approvals.onEvent(ApprovalsEvent.Approve(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()
        socket.say("project:user:join:request:received")
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertTrue(repository.reads.isEmpty(), "a read racing the decision could put 'a' back")
        repository.hold?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `coming back keeps the admin's search`() = runTest {
        val repository = Repository(listOf(request("a"), request("b", "Sam Reed")))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        approvals.onEvent(ApprovalsEvent.SearchChanged(ApprovalQueue.NewCrew, "sam"))

        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertEquals("sam", approvals.state.value.crew.query)
        assertEquals(listOf("b"), approvals.state.value.crew.visible.map { it.id })
    }
}
