package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.data.DRIVE_SYNC_EVENTS
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveViewModel
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
import kotlin.test.assertTrue

/**
 * A drive delete pulse refetches the open destination once — the web's
 * refetch on `drive_file_deleted`/`drive_folder_deleted`/`drive_bulk_deleted`
 * (`DriveManagement.jsx:1591-1596`, ZL-18490) as a targeted reload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(refreshes: Flow<Unit>) : FakeDriveRepository(refreshes)

    // -- which wire events are subscribed ----------------------------------

    /**
     * The list the browse view reloads on.
     *
     * The names are the wire's, not the underscore aliases the web's
     * components listen to — those are re-emits and exist only inside
     * `listenerSocket.js`. Getting one wrong is silent: the subscription
     * simply never fires and the list quietly goes stale.
     */
    @Test
    fun `the browse list reloads on every change another client can announce`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertTrue("drive:file:added" in names, "a colleague's upload must appear")
        assertTrue("drive:folder:created" in names)
        assertTrue("drive:file:updated" in names && "drive:folder:updated" in names)
        assertTrue("drive:folder:moved" in names)
        assertTrue("drive:file:deleted" in names && "drive:folder:deleted" in names)
        assertTrue("drive:bulk:deleted" in names)
    }

    /**
     * A file shared with this user belongs in "Shared with me" straight away.
     *
     * The web only badges these, which is why they were left out — but the
     * web has no shared listing to be wrong about. iOS reloads on them like
     * any other structural change (`HomeViewModel.swift:2018`).
     */
    @Test
    fun `sharing reloads the list`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertTrue("drive:file:shared" in names)
        assertTrue("drive:folder:shared" in names)
    }

    /** Comments belong to the open details panel, not to the listing. */
    @Test
    fun `comments do not reload the list`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertTrue(names.none { it.startsWith("drive:comment") })
    }

    @Test
    fun `every subscribed name is a drive event, spelt in wire form`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertEquals(names.size, names.toSet().size, "no duplicate subscriptions")
        assertTrue(names.all { it.startsWith("drive:") }, "no underscore aliases")
        assertTrue(names.none { it.contains('_') })
    }

    @Test
    fun `a delete pulse reloads the open destination once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeRepo(refreshes = events)
        val model = DriveViewModel(
            repository = repo,
            viewer = { DriveViewer(userId = "u1", ready = true) },
        )

        model.start()
        runCurrent()
        // The "Shared with me" landing, empty, falls back to My Drive
        // (ZL-21229) — so a cold start is two loads, never more.
        assertEquals(2, repo.browseLoads, "start loads shared, then falls back to My Drive")

        events.emit(Unit)
        runCurrent()
        assertEquals(3, repo.browseLoads, "the pulse re-runs exactly one load")

        model.onProjectChanged()
        runCurrent()
        assertEquals(5, repo.browseLoads)

        events.emit(Unit)
        runCurrent()
        assertEquals(6, repo.browseLoads, "a project switch must not stack a second collector")
    }
}
