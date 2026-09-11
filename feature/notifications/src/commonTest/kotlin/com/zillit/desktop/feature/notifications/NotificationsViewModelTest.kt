package com.zillit.desktop.feature.notifications

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.notifications.domain.ActivityBanner
import com.zillit.desktop.feature.notifications.domain.NotificationTarget
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.notifications.domain.ProjectNotification
import com.zillit.desktop.feature.notifications.ui.NotificationsConfirm
import com.zillit.desktop.feature.notifications.ui.NotificationsEffect
import com.zillit.desktop.feature.notifications.ui.NotificationsEvent
import com.zillit.desktop.feature.notifications.ui.NotificationsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Paging, the read after every page, and the two deletes behind their confirmations, on a fake service. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository : NotificationsRepository {
        val rows = mutableListOf<ProjectNotification>()
        val pageCursors = mutableListOf<Long>()
        val reads = mutableListOf<Pair<String, Long>>()
        val deletes = mutableListOf<String>()
        var deletedAll = false
        var pageSize = 50
        var failDelete = false

        // The list screen under test never decodes socket frames.
        override fun banner(payload: kotlinx.serialization.json.JsonElement?): ActivityBanner? = null

        override suspend fun page(beforeMillis: Long, newest: Boolean): ZillitResult<List<ProjectNotification>> {
            pageCursors += beforeMillis
            return ZillitResult.Success(
                rows.filter { it.updatedMillis < beforeMillis }.sortedByDescending { it.updatedMillis }.take(pageSize),
            )
        }

        override suspend fun markRead(segment: String, timestampMillis: Long): ZillitResult<Unit> {
            reads += segment to timestampMillis
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(notificationId: String, timestampMillis: Long): ZillitResult<Unit> {
            if (failDelete) return ZillitResult.Failure(ZillitError.Unknown("nope"))
            deletes += notificationId
            rows.removeAll { it.id == notificationId }
            return ZillitResult.Success(Unit)
        }

        override suspend fun deleteAll(): ZillitResult<Unit> {
            deletedAll = true
            rows.clear()
            return ZillitResult.Success(Unit)
        }
    }

    private fun row(id: String, at: Long, global: Boolean = true) = ProjectNotification(
        id = id,
        uuid = "u-$id",
        text = "text $id",
        pathLabel = "Tools : Call Sheet",
        target = NotificationTarget(path = "{tools_label/call_sheet_label}", tool = "call_sheet_label"),
        createdMillis = at,
        updatedMillis = at,
        read = false,
        isGlobal = global,
    )

    private var listReads = 0

    private fun viewModel(
        repository: FakeRepository,
        now: Long = 1_000L,
        arrivals: Flow<Unit> = emptyFlow(),
    ) = NotificationsViewModel(
        repository = repository,
        nowMillis = { now },
        onListRead = { listReads++ },
        pageLimit = repository.pageSize,
        arrivals = arrivals,
    )

    /**
     * A notification arriving while the bell page is open.
     *
     * The badge store folded these into the count all along; the list under
     * it did not move, which is the one moment a notification list is being
     * watched.
     */
    @Test
    fun `an arriving notification re-reads the open list`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { rows += row("first", 100) }
        val arrivals = MutableSharedFlow<Unit>()
        val model = viewModel(repository, arrivals = arrivals).also { it.start() }
        runCurrent()
        val afterOpen = repository.pageCursors.size

        repository.rows += row("second", 200)
        arrivals.emit(Unit)
        runCurrent()

        assertEquals(afterOpen + 1, repository.pageCursors.size, "the arrival re-reads the newest page")
        assertTrue(model.state.value.rows.any { it.id == "second" }, "the new row is shown")
    }

    /** A page nobody has opened is not fetched by an arrival. */
    @Test
    fun `an arrival before the list is opened fetches nothing`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { rows += row("first", 100) }
        val arrivals = MutableSharedFlow<Unit>()
        viewModel(repository, arrivals = arrivals)
        runCurrent()

        arrivals.emit(Unit)
        runCurrent()

        assertTrue(repository.pageCursors.isEmpty(), "nothing was asked for")
    }

    @Test
    fun `the first page is asked from now, shown newest first without non-global rows, then the segment is read`() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply {
                rows += row("old", 100)
                rows += row("new", 200)
                rows += row("badge-only", 300, global = false)
            }
            val viewModel = viewModel(repository)

            viewModel.start()
            advanceUntilIdle()

            assertEquals(listOf(1_000L), repository.pageCursors)
            assertEquals(listOf("new", "old"), viewModel.currentState.rows.map { it.id })
            assertTrue(viewModel.currentState.loaded)
            assertFalse(viewModel.currentState.hasMore)
            assertEquals(listOf("global_label" to 1_000L), repository.reads)
            assertEquals(1, listReads)
        }

    @Test
    fun `show older cursors on the oldest row shown and appends without duplicates`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            pageSize = 2
            rows += row("a", 400)
            rows += row("b", 300)
            rows += row("c", 200)
            rows += row("d", 100)
        }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.currentState.rows.map { it.id })
        assertTrue(viewModel.currentState.hasMore)

        viewModel.onEvent(NotificationsEvent.LoadOlder)
        advanceUntilIdle()

        assertEquals(listOf(1_000L, 300L), repository.pageCursors)
        assertEquals(listOf("a", "b", "c", "d"), viewModel.currentState.rows.map { it.id })
        assertEquals(2, repository.reads.size)
    }

    @Test
    fun `deleting one row waits for the confirmation, then removes it locally`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { rows += row("a", 400); rows += row("b", 300) }
        val viewModel = viewModel(repository)
        val notices = mutableListOf<String>()
        val collector = launch {
            viewModel.effects.collect { if (it is NotificationsEffect.Notice) notices += it.message }
        }
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(NotificationsEvent.AskDelete("a"))
        assertEquals(NotificationsConfirm.DeleteOne("a"), viewModel.currentState.confirm)
        viewModel.onEvent(NotificationsEvent.CancelDelete)
        assertNull(viewModel.currentState.confirm)
        assertTrue(repository.deletes.isEmpty())

        viewModel.onEvent(NotificationsEvent.AskDelete("a"))
        viewModel.onEvent(NotificationsEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf("a"), repository.deletes)
        assertEquals(listOf("b"), viewModel.currentState.rows.map { it.id })
        assertEquals(listOf("Notification deleted"), notices)
        assertFalse(viewModel.currentState.busy)
        collector.cancel()
    }

    @Test
    fun `a failed delete keeps the row and surfaces the error`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { rows += row("a", 400); failDelete = true }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(NotificationsEvent.AskDelete("a"))
        viewModel.onEvent(NotificationsEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf("a"), viewModel.currentState.rows.map { it.id })
        assertEquals("Something went wrong.", viewModel.currentState.error)
    }

    @Test
    fun `delete all clears the list once the service agrees`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { rows += row("a", 400); rows += row("b", 300) }
        val viewModel = viewModel(repository)
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(NotificationsEvent.AskDeleteAll)
        assertEquals(NotificationsConfirm.DeleteAll, viewModel.currentState.confirm)
        viewModel.onEvent(NotificationsEvent.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(repository.deletedAll)
        assertTrue(viewModel.currentState.rows.isEmpty())
        assertFalse(viewModel.currentState.hasMore)
    }

    @Test
    fun `a row click raises the target for the host and nothing else`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { rows += row("a", 400) }
        val viewModel = viewModel(repository)
        val opened = mutableListOf<NotificationTarget>()
        val collector = launch {
            viewModel.effects.collect { if (it is NotificationsEffect.Open) opened += it.target }
        }
        viewModel.start()
        advanceUntilIdle()

        viewModel.onEvent(NotificationsEvent.Open("a"))
        advanceUntilIdle()

        assertEquals(listOf("call_sheet_label"), opened.map { it.tool })
        collector.cancel()
    }
}
