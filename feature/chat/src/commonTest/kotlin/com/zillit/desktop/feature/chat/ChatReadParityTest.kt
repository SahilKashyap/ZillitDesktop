package com.zillit.desktop.feature.chat

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
import kotlin.test.assertTrue

/**
 * Reads the way iOS makes them: on the open whatever the network says, kept
 * until the socket can take them, and missed calls read by looking at the
 * Missed list — not at the Calls tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReadParityTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** What the host saw: conversations read into the ledger, and missed-call reads emitted. */
    private class Host {
        val threadReads = mutableListOf<String>()
        var missedReads = 0

        /** The ledger's unread missed calls; a read clears them, as the host's ledger flip does. */
        var missedCalls = 0
    }

    private fun model(repository: FakeChatRepository, host: Host = Host()) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-1" },
        onThreadRead = { host.threadReads += it },
        sectionBadges = { mapOf("call_label" to host.missedCalls) },
        onCallsViewed = {
            host.missedReads++
            host.missedCalls = 0
        },
    )

    private fun line(id: String, at: Long, mine: Boolean = false) = ChatMessage(
        id = id, uniqueId = id, senderId = if (mine) "me" else aisha.userId,
        receiverId = if (mine) aisha.userId else "me", body = id, timestampMillis = at, isMine = mine,
    )

    // -- the open reads whatever history says ------------------------------

    /**
     * iOS flips the rows as the thread appears and emits the read with the
     * newest id its own store holds (`ChatingViewController.swift:527-561`,
     * `ChatViewModel.swift:424-454`). Here both waited for history, so a
     * thread opened offline kept its badge on the row, the rail and the ledger.
     */
    @Test
    fun `a thread whose history fails is still read from what this computer holds`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            historyFails = true
            cachedThreads = mapOf(
                aisha.userId to listOf(line("m-1", 1_000L), line("m-2", 2_000L), line("m-3", 3_000L, mine = true)),
            )
            backlog = com.zillit.desktop.feature.chat.data.ConversationBacklog(unread = mapOf(aisha.userId to 2))
            recents = listOf(aisha.userId)
        }
        val host = Host()
        val model = model(repository, host)
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        assertEquals(2, model.currentState.unread[aisha.userId], "the control: the row was badged")

        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        assertEquals(listOf(aisha.userId to "m-2"), repository.reads, "the newest line from someone else")
        assertTrue(aisha.userId in host.threadReads, "the ledger was told")
        assertEquals(null, model.currentState.unread[aisha.userId], "the row cleared")
        assertEquals(listOf(aisha.userId to 3_000L), repository.threadMarks, "read up to what is held")
    }

    /** No cache and no history: nothing to name, but the ledger and the row still move. */
    @Test
    fun `a thread opened with nothing held still clears its badge`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply { historyFails = true }
        val host = Host()
        val model = model(repository, host)

        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        assertTrue(repository.reads.isEmpty(), "no message to report a read up to")
        assertEquals(listOf(aisha.userId), host.threadReads)
    }

    // -- a read the socket refused is not lost ----------------------------

    /**
     * The read used to be marked sent before the emit, so one refused while
     * the socket was down was never tried again: the sender never saw it and
     * every other device kept the badge.
     */
    @Test
    fun `a read the socket refused goes when it reconnects`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            readsRefused = true
            history = listOf(line("m-1", 1_000L), line("m-2", 2_000L))
        }
        val model = model(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        assertEquals(listOf(aisha.userId to "m-2"), repository.refusedReads)
        assertTrue(repository.reads.isEmpty())

        repository.readsRefused = false
        repository.connected.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf(aisha.userId to "m-2"), repository.reads, "sent once the socket is back")
    }

    /** A refused read is not a sent one: the next look tries it again rather than deduplicating it away. */
    @Test
    fun `a refused read is tried again on the next look, and only once sent`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            readsRefused = true
            history = listOf(line("m-1", 1_000L))
        }
        val model = model(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        repository.readsRefused = false
        model.onEvent(ChatEvent.Watching(true))
        advanceUntilIdle()
        assertEquals(listOf(aisha.userId to "m-1"), repository.reads, "the look sent it")

        repository.connected.emit(Unit)
        advanceUntilIdle()
        assertEquals(1, repository.reads.size, "and the reconnect has nothing left to send")
    }

    /** A read waiting for the socket names the production it was made in. */
    @Test
    fun `a production switch drops the reads still waiting`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            readsRefused = true
            history = listOf(line("m-1", 1_000L))
        }
        val model = model(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.ProjectChanged)
        repository.readsRefused = false
        repository.connected.emit(Unit)
        advanceUntilIdle()

        assertTrue(repository.reads.isEmpty(), "never stamped onto the next production")
    }

    // -- missed calls are read on the Missed list -------------------------

    /**
     * iOS reads missed calls on the Missed segment only
     * (`MissedCallViewController.swift:376-378, 399`). The Calls tab used to
     * read them on any opening, its All view included.
     */
    @Test
    fun `missed calls are read on the Missed view, not on All`() = runTest(dispatcher) {
        val host = Host().apply { missedCalls = 2 }
        val model = model(FakeChatRepository(), host)

        model.onEvent(ChatEvent.MissedCallsShown(shown = false))
        advanceUntilIdle()
        assertEquals(0, host.missedReads, "All is on screen, not Missed")

        model.onEvent(ChatEvent.MissedCallsShown(shown = true))
        advanceUntilIdle()
        assertEquals(1, host.missedReads)
        assertEquals(0, model.currentState.callsBadge, "and the tab's count falls with them")
    }

    /**
     * iOS re-runs the current screen's read on every badge refresh, so a call
     * missed while the Missed list is up clears at once. Here the tab's count
     * was never refreshed while it stayed open, and nothing read it again.
     */
    @Test
    fun `a call missed while the Missed list is on screen is read at once`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val host = Host()
        val model = model(repository, host)
        model.onEvent(ChatEvent.MissedCallsShown(shown = true))
        advanceUntilIdle()
        assertEquals(0, host.missedReads, "nothing unread, nothing emitted")

        host.missedCalls = 1
        repository.ledgerMoved.emit(Unit)
        advanceUntilIdle()

        assertEquals(1, host.missedReads)
        assertEquals(0, model.currentState.callsBadge)
    }

    /** Off screen — another view, another tab, the window unfocused — a new missed call stays unread. */
    @Test
    fun `a call missed after the list left the screen stays unread`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val host = Host()
        val model = model(repository, host)
        model.onEvent(ChatEvent.MissedCallsShown(shown = true))
        model.onEvent(ChatEvent.MissedCallsShown(shown = false))
        advanceUntilIdle()

        host.missedCalls = 1
        repository.ledgerMoved.emit(Unit)
        advanceUntilIdle()

        assertEquals(0, host.missedReads)
        assertEquals(1, model.currentState.callsBadge, "the tab still says so")
    }

    /**
     * A host that cannot say which view the list is on keeps the old single
     * read as the tab comes up — and never re-reads behind a view that may
     * be All.
     */
    @Test
    fun `without a Missed view to go by, the tab reads once and no more`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val host = Host().apply { missedCalls = 1 }
        val model = model(repository, host)
        model.onEvent(ChatEvent.MissedCallsShown(shown = true, live = false))
        advanceUntilIdle()
        assertEquals(1, host.missedReads)

        host.missedCalls = 1
        repository.ledgerMoved.emit(Unit)
        advanceUntilIdle()

        assertEquals(1, host.missedReads, "a call missed behind the tab is not read for the user")
    }

    private companion object {
        const val NOW = 1_786_507_000_000L
    }
}
