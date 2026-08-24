package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.PresenceSource
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat header's green dot: one watcher per open 1:1 thread, and none the
 * moment the thread is not on screen — the lifecycle both reviews flagged,
 * matched to iOS's single-RTDB-observer model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPresenceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan", deviceId = "d-aisha")
    private val noDevice = CrewContact(userId = "u-bare", fullName = "No Device")

    /** Emits on demand and counts collectors, so leaks are visible. */
    private class FakePresence : PresenceSource {
        val emissions = MutableSharedFlow<Boolean>()
        var active = 0
            private set
        var watched = mutableListOf<Pair<String, String>>()

        override fun watch(deviceId: String, projectId: String): Flow<Boolean> {
            watched += deviceId to projectId
            return kotlinx.coroutines.flow.flow {
                active++
                try {
                    emissions.collect { emit(it) }
                } finally {
                    active--
                }
            }
        }
    }

    private fun viewModel(presence: FakePresence) = ChatViewModel(
        repository = FakeChatRepository(),
        nowMillis = { 1_000L },
        newUniqueId = { "id" },
        presence = presence,
        presenceProjectId = { "p1" },
    )

    @Test
    fun `opening a 1-1 thread watches the peer's device and paints the dot`() = runTest {
        val presence = FakePresence()
        val model = viewModel(presence)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        assertEquals(listOf("d-aisha" to "p1"), presence.watched)
        presence.emissions.emit(true)
        runCurrent()
        assertTrue(model.state.value.peerOnline)

        presence.emissions.emit(false)
        runCurrent()
        assertFalse(model.state.value.peerOnline)
    }

    @Test
    fun `closing the thread stops the watcher and clears the dot`() = runTest {
        val presence = FakePresence()
        val model = viewModel(presence)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        presence.emissions.emit(true)
        runCurrent()
        assertEquals(1, presence.active)

        model.onEvent(ChatEvent.CloseThread)
        runCurrent()
        assertEquals(0, presence.active)
        assertFalse(model.state.value.peerOnline)
    }

    @Test
    fun `a project switch stops the previous production's watcher`() = runTest {
        val presence = FakePresence()
        val model = viewModel(presence)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        assertEquals(1, presence.active)

        model.onEvent(ChatEvent.ProjectChanged)
        runCurrent()
        assertEquals(0, presence.active)
    }

    @Test
    fun `groups and device-less crew are never watched`() = runTest {
        val presence = FakePresence()
        val model = viewModel(presence)
        model.onEvent(ChatEvent.OpenThread(noDevice))
        runCurrent()
        assertTrue(presence.watched.isEmpty())

        model.onEvent(ChatEvent.OpenGroup(GroupRoom(id = "room-1", name = "Camera")))
        runCurrent()
        assertTrue(presence.watched.isEmpty())
        assertEquals(0, presence.active)
    }

    @Test
    fun `switching threads replaces the watcher rather than stacking one per visit`() = runTest {
        val presence = FakePresence()
        val model = viewModel(presence)
        val bela = CrewContact(userId = "u-bela", fullName = "Bela", deviceId = "d-bela")

        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        model.onEvent(ChatEvent.OpenThread(bela))
        runCurrent()

        assertEquals(1, presence.active)
        assertEquals(listOf("d-aisha" to "p1", "d-bela" to "p1"), presence.watched)
    }
}
