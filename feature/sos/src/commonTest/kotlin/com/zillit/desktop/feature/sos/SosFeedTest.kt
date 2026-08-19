package com.zillit.desktop.feature.sos

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.sos.domain.SosFix
import com.zillit.desktop.feature.sos.domain.SosViewer
import com.zillit.desktop.feature.sos.ui.SosConfirm
import com.zillit.desktop.feature.sos.ui.SosEffect
import com.zillit.desktop.feature.sos.ui.SosEvent
import com.zillit.desktop.feature.sos.ui.SosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The alert feed: paging, the read after every page, the alarm, and both deletes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosFeedTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = 1_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: FakeSosRepository,
        fix: SosFix? = SosFix(lat = 51.5, long = -0.1),
    ) = SosViewModel(
        repository = repository,
        nowMillis = { now },
        viewer = { SosViewer(userId = "u-me", isAdmin = false, phone = "7700900000") },
        crew = { emptyList() },
        locationFix = { fix },
        pageLimit = repository.pageSize,
    )

    @Test
    fun `the first page is asked from now, newest first, tombstones dropped, then the segment is read`() =
        runTest(dispatcher) {
            val repository = FakeSosRepository().apply {
                alerts += alert("old", 100)
                alerts += alert("new", 200)
                alerts += alert("gone", 300, deleted = true)
            }
            val viewModel = viewModel(repository)

            viewModel.start()
            advanceUntilIdle()

            assertEquals(listOf(now to true), repository.alertCursors)
            assertEquals(listOf("new", "old"), viewModel.currentState.alerts.map { it.id })
            assertTrue(viewModel.currentState.loaded)
            assertFalse(viewModel.currentState.hasMore)
            assertEquals(listOf(now), repository.reads)
        }

    @Test
    fun `show older cursors on the oldest row shown and appends without duplicates`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            pageSize = 2
            alerts += alert("a", 400)
            alerts += alert("b", 300)
            alerts += alert("c", 200)
            alerts += alert("d", 100)
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.currentState.alerts.map { it.id })
        assertTrue(viewModel.currentState.hasMore)

        viewModel.onEvent(SosEvent.LoadOlder)
        advanceUntilIdle()

        assertEquals(listOf(now to true, 300L to false), repository.alertCursors)
        assertEquals(listOf("a", "b", "c", "d"), viewModel.currentState.alerts.map { it.id })
        assertEquals(2, repository.reads.size)
    }

    @Test
    fun `sending waits for the confirmation, carries the fix, then re-reads the feed`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val viewModel = viewModel(repository)
        val notices = mutableListOf<String>()
        val collector = launch { viewModel.effects.collect { if (it is SosEffect.Notice) notices += it.message } }
        viewModel.start()
        advanceUntilIdle()
        val pagesBefore = repository.alertCursors.size

        viewModel.onEvent(SosEvent.AskSendAlert)
        assertEquals(SosConfirm.SendAlert, viewModel.currentState.confirm)
        viewModel.onEvent(SosEvent.CancelConfirm)
        advanceUntilIdle()
        assertNull(viewModel.currentState.confirm)
        assertTrue(repository.sent.isEmpty())

        viewModel.onEvent(SosEvent.AskSendAlert)
        viewModel.onEvent(SosEvent.ConfirmAction)
        advanceUntilIdle()

        assertEquals(listOf<SosFix?>(SosFix(lat = 51.5, long = -0.1)), repository.sent)
        assertEquals(listOf("SOS sent to your receivers."), notices)
        assertEquals(pagesBefore + 1, repository.alertCursors.size)
        assertFalse(viewModel.currentState.busy)
        collector.cancel()
    }

    @Test
    fun `a machine with no location still raises the alarm`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val viewModel = viewModel(repository, fix = null)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.AskSendAlert)
        viewModel.onEvent(SosEvent.ConfirmAction)
        advanceUntilIdle()

        assertEquals(listOf<SosFix?>(null), repository.sent)
    }

    @Test
    fun `deleting one alert asks first, then removes it locally`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            alerts += alert("a", 400)
            alerts += alert("b", 300)
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.AskDeleteAlert("a"))
        assertEquals(SosConfirm.DeleteAlert("a"), viewModel.currentState.confirm)
        viewModel.onEvent(SosEvent.ConfirmAction)
        advanceUntilIdle()

        assertEquals(listOf("a"), repository.deletedAlerts)
        assertEquals(listOf("b"), viewModel.currentState.alerts.map { it.id })
    }

    @Test
    fun `clear all empties the list and stops paging once the service agrees`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            pageSize = 1
            alerts += alert("a", 400)
            alerts += alert("b", 300)
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()
        assertTrue(viewModel.currentState.hasMore)

        viewModel.onEvent(SosEvent.AskDeleteAllAlerts)
        viewModel.onEvent(SosEvent.ConfirmAction)
        advanceUntilIdle()

        assertEquals(now, repository.deletedAllAt)
        assertTrue(viewModel.currentState.alerts.isEmpty())
        assertFalse(viewModel.currentState.hasMore)
    }

    @Test
    fun `a refused delete keeps the row and surfaces the error`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            alerts += alert("a", 400)
            failNext = ZillitError.Unknown("nope")
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.AskDeleteAlert("a"))
        viewModel.onEvent(SosEvent.ConfirmAction)
        advanceUntilIdle()

        assertEquals(listOf("a"), viewModel.currentState.alerts.map { it.id })
        assertEquals("Something went wrong.", viewModel.currentState.error)
    }

    @Test
    fun `the map link is handed to the host, and a linkless alert says so instead`() = runTest(dispatcher) {
        val repository = FakeSosRepository().apply {
            alerts += alert("a", 400)
            alerts += alert("b", 300).copy(mapsUrl = "")
        }
        val viewModel = viewModel(repository)
        val links = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val collector = launch {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SosEffect.OpenLink -> links += effect.url
                    is SosEffect.Notice -> notices += effect.message
                }
            }
        }
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(SosEvent.OpenMap("a"))
        viewModel.onEvent(SosEvent.OpenMap("b"))
        advanceUntilIdle()

        assertEquals(listOf("https://maps.google.com/?q=1,2"), links)
        assertEquals(listOf("This alert carries no location."), notices)
        collector.cancel()
    }
}
