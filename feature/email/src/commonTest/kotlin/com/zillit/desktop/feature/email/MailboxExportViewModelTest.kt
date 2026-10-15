package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.MailboxExportUpdate
import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportDownload
import com.zillit.desktop.feature.email.domain.MailboxExportPart
import com.zillit.desktop.feature.email.domain.MailboxExportRepository
import com.zillit.desktop.feature.email.domain.MailboxExportStatus
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.StartedMailboxExport
import com.zillit.desktop.feature.email.ui.settings.MailboxExportEffect
import com.zillit.desktop.feature.email.ui.settings.MailboxExportEvent
import com.zillit.desktop.feature.email.ui.settings.MailboxExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export mailbox, as the screen runs it (contract §5–§7): the list on open,
 * one export at a time, the socket keeping it current, the poll standing in
 * when the socket is down, and the links fetched only on the click.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailboxExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeExports(
        initial: List<MailboxExport> = emptyList(),
        private val started: StartedMailboxExport? = null,
    ) : MailboxExportRepository {
        var rows = initial.toMutableList()
        var deleted = mutableListOf<String>()
        var linkCalls = 0
        var statusCalls = 0
        var links = MailboxExportDownload(
            exportId = "e1",
            expiresInSeconds = 3600,
            parts = listOf(
                MailboxExportPart(1, "a.zip", 10, 2, "https://s3/a"),
                MailboxExportPart(2, "b.zip", 20, 3, "https://s3/b"),
            ),
        )

        override suspend fun start(): ZillitResult<StartedMailboxExport> =
            started?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Success(StartedMailboxExport(export("new", MailboxExportStatus.Queued)))

        override suspend fun exports() = ZillitResult.Success(rows.toList())

        override suspend fun export(exportId: String): ZillitResult<MailboxExport> {
            statusCalls++
            return rows.firstOrNull { it.exportId == exportId }
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Http(404, "email_export_not_found"))
        }

        override suspend fun downloadLinks(exportId: String): ZillitResult<MailboxExportDownload> {
            linkCalls++
            return ZillitResult.Success(links)
        }

        override suspend fun delete(exportId: String): ZillitResult<MailboxExport> {
            deleted += exportId
            val before = rows.first { it.exportId == exportId }
            val after = before.copy(
                status = if (before.isInProgress) MailboxExportStatus.Cancelled else MailboxExportStatus.Expired,
            )
            rows.replaceAll { if (it.exportId == exportId) after else it }
            return ZillitResult.Success(after)
        }
    }

    @Test
    fun `the list draws the screen and the running export leads it`() = runTest(dispatcher) {
        val repo = FakeExports(
            listOf(
                export("done", MailboxExportStatus.Completed),
                export("running", MailboxExportStatus.Processing),
            ),
        )
        val vm = MailboxExportViewModel(repo)

        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        assertEquals("running", vm.state.value.latest?.exportId)
        assertEquals(listOf("done"), vm.state.value.earlier.map { it.exportId })
        assertFalse(vm.state.value.canStart, "one export per mailbox at a time")
        vm.stopWatching()
    }

    @Test
    fun `an export already running is shown with the server's words, not an error`() = runTest(dispatcher) {
        val running = export("running", MailboxExportStatus.Processing)
        val repo = FakeExports(
            started = StartedMailboxExport(
                export = running,
                alreadyRunning = true,
                message = "email_export_already_in_progress",
            ),
        )
        val vm = MailboxExportViewModel(repo)

        vm.onEvent(MailboxExportEvent.Start)
        runCurrent()

        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.info)
        assertEquals("running", vm.state.value.latest?.exportId, "the running export is shown instead")
        vm.stopWatching()
    }

    @Test
    fun `a socket update replaces the export it names and is ignored for the other mailbox`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Processing, exported = 100, total = 400)))
        val updates = MutableSharedFlow<MailboxExportUpdate>(extraBufferCapacity = 4)
        val vm = MailboxExportViewModel(repo, updates = updates, scope = MailboxScope.Personal)
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        updates.emit(
            MailboxExportUpdate(
                export = export("e1", MailboxExportStatus.Processing, exported = 300, total = 400),
                isAccountsMailbox = false,
            ),
        )
        runCurrent()
        assertEquals(300, vm.state.value.latest?.exportedMessages)

        // The shared mailbox's export, arriving at a personal-mailbox screen.
        updates.emit(
            MailboxExportUpdate(
                export = export("shared", MailboxExportStatus.Completed),
                isAccountsMailbox = true,
            ),
        )
        runCurrent()
        assertEquals(listOf("e1"), vm.state.value.exports.map { it.exportId })
        vm.stopWatching()
    }

    @Test
    fun `a retry that steps the count back is not an error`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Processing, exported = 900, total = 4000)))
        val updates = MutableSharedFlow<MailboxExportUpdate>(extraBufferCapacity = 4)
        val vm = MailboxExportViewModel(repo, updates = updates)
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        // The server lost its connection, put the export back to `queued` and
        // resumed a little behind where it was.
        updates.emit(
            MailboxExportUpdate(export("e1", MailboxExportStatus.Queued, exported = 850, total = 4000), false),
        )
        runCurrent()

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.latest?.isInProgress == true, "queued and processing are one state")
        vm.stopWatching()
    }

    @Test
    fun `the poll runs only while the socket is down and an export is unfinished`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Processing)))
        var connected = true
        val vm = MailboxExportViewModel(
            repo,
            isSocketConnected = { connected },
            pollMillis = 1_000,
        )
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(0, repo.statusCalls, "the socket is up; the pushes are better than a poll")

        connected = false
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(repo.statusCalls > 0, "with the socket down the export is polled")

        // Once it finishes, the loop stops asking.
        repo.rows.replaceAll { it.copy(status = MailboxExportStatus.Completed) }
        advanceTimeBy(1_100)
        runCurrent()
        val settled = repo.statusCalls
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(settled, repo.statusCalls, "a finished export is not polled")
        vm.stopWatching()
    }

    @Test
    fun `cancelling a running export needs no confirmation`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Processing)))
        val vm = MailboxExportViewModel(repo)
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        vm.onEvent(MailboxExportEvent.Cancel("e1"))
        runCurrent()

        assertEquals(listOf("e1"), repo.deleted)
        assertEquals(MailboxExportStatus.Cancelled, vm.state.value.latest?.status)
        assertTrue(vm.state.value.canStart, "a cancelled export frees the mailbox for another")
    }

    @Test
    fun `removing a completed export asks first and can be called off`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Completed)))
        val vm = MailboxExportViewModel(repo)
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        vm.onEvent(MailboxExportEvent.AskRemove(vm.state.value.latest!!))
        runCurrent()
        assertNotNull(vm.state.value.pendingRemoval)
        assertTrue(repo.deleted.isEmpty(), "nothing is deleted while the question is on screen")

        vm.onEvent(MailboxExportEvent.DismissRemove)
        runCurrent()
        assertNull(vm.state.value.pendingRemoval)
        assertTrue(repo.deleted.isEmpty())

        vm.onEvent(MailboxExportEvent.AskRemove(vm.state.value.latest!!))
        vm.onEvent(MailboxExportEvent.ConfirmRemove)
        runCurrent()
        assertEquals(listOf("e1"), repo.deleted)
        assertEquals(MailboxExportStatus.Expired, vm.state.value.latest?.status)
    }

    @Test
    fun `links are fetched on the click, one download per part`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Completed, parts = 2)))
        val vm = MailboxExportViewModel(repo)
        val opened = mutableListOf<MailboxExportEffect>()
        val collector = launch { vm.effects.toList(opened) }

        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()
        assertEquals(0, repo.linkCalls, "nothing is asked for on load — a link lives an hour")

        vm.onEvent(MailboxExportEvent.Download("e1", partNo = 2))
        runCurrent()
        assertEquals(1, repo.linkCalls)
        assertEquals(
            listOf(MailboxExportEffect.OpenDownload("https://s3/b", "b.zip")),
            opened.toList(),
            "only the part whose button was pressed",
        )

        vm.onEvent(MailboxExportEvent.Download("e1", MailboxExportEvent.ALL_PARTS))
        runCurrent()
        assertEquals(
            listOf("https://s3/b", "https://s3/a", "https://s3/b"),
            opened.map { (it as MailboxExportEffect.OpenDownload).url },
            "ALL_PARTS means every part; the user needs all of them",
        )
        collector.cancel()
    }

    @Test
    fun `a part with no link reports it instead of doing nothing`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", MailboxExportStatus.Completed, parts = 2)))
        // The server answered, but with a link for part 1 only — part 2's was
        // blank, so the wire layer dropped it.
        repo.links = MailboxExportDownload(
            exportId = "e1",
            expiresInSeconds = 3600,
            parts = listOf(MailboxExportPart(1, "a.zip", 10, 2, "https://s3/a")),
        )
        val vm = MailboxExportViewModel(repo)
        val opened = mutableListOf<MailboxExportEffect>()
        val collector = launch { vm.effects.toList(opened) }
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        vm.onEvent(MailboxExportEvent.Download("e1", partNo = 2))
        runCurrent()

        assertTrue(opened.isEmpty(), "nothing to open")
        assertNotNull(vm.state.value.error, "a button that flashes and does nothing is worse than a message")
        assertEquals("", vm.state.value.linkingExportId, "and the button is usable again")
        collector.cancel()
    }

    @Test
    fun `a reload drops everything that belonged to the previous mailbox`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("personal", MailboxExportStatus.Completed)))
        val vm = MailboxExportViewModel(repo)
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        // Mid-question when the switcher flips: the host re-sends Load.
        vm.onEvent(MailboxExportEvent.AskRemove(vm.state.value.latest!!))
        runCurrent()
        assertNotNull(vm.state.value.pendingRemoval)

        repo.rows = mutableListOf(export("shared", MailboxExportStatus.Processing))
        vm.onEvent(MailboxExportEvent.Load)
        runCurrent()

        assertEquals(listOf("shared"), vm.state.value.exports.map { it.exportId })
        assertNull(vm.state.value.pendingRemoval, "the other mailbox's export must not stay queued for deletion")
        assertEquals("", vm.state.value.busyExportId)
        assertEquals("", vm.state.value.linkingExportId)
        vm.stopWatching()
    }

    @Test
    fun `a refusal is surfaced with the server's message key`() = runTest(dispatcher) {
        val repo = object : MailboxExportRepository {
            override suspend fun start() =
                ZillitResult.Failure(ZillitError.Http(400, "email_credentials_not_available"))
            override suspend fun exports() = ZillitResult.Success(emptyList<MailboxExport>())
            override suspend fun export(exportId: String) =
                ZillitResult.Failure(ZillitError.Http(404, "email_export_not_found"))
            override suspend fun downloadLinks(exportId: String) =
                ZillitResult.Failure(ZillitError.Http(400, "email_export_not_ready"))
            override suspend fun delete(exportId: String) =
                ZillitResult.Failure(ZillitError.Http(404, "email_export_not_found"))
        }
        val vm = MailboxExportViewModel(repo)

        vm.onEvent(MailboxExportEvent.Start)
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isStarting)
    }

    private companion object {
        fun export(
            id: String,
            status: MailboxExportStatus,
            exported: Int = 0,
            total: Int = 0,
            parts: Int = 0,
        ) = MailboxExport(
            exportId = id,
            status = status,
            mailboxEmail = "jane@prod.com",
            totalMessages = total,
            exportedMessages = exported,
            parts = (1..parts).map { MailboxExportPart(it, "part$it.zip", 10, 2) },
            requestedOnMillis = 1_760_000_000_000,
        )
    }
}
