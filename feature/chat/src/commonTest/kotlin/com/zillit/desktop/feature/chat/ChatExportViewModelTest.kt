package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatExportUpdate
import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.ChatExportDownload
import com.zillit.desktop.feature.chat.domain.ChatExportRepository
import com.zillit.desktop.feature.chat.domain.ChatExportStatus
import com.zillit.desktop.feature.chat.domain.StartedChatExport
import com.zillit.desktop.feature.chat.ui.export.ChatExportEffect
import com.zillit.desktop.feature.chat.ui.export.ChatExportEvent
import com.zillit.desktop.feature.chat.ui.export.ChatExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
 * Export chats, as the dialog runs it (contract §1–§5): the list when it
 * opens, one export at a time, the socket keeping it current, the poll
 * standing in when the socket is down, and the link fetched only on the
 * click.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeExports(
        initial: List<ChatExport> = emptyList(),
        private val started: StartedChatExport? = null,
    ) : ChatExportRepository {
        var rows = initial.toMutableList()
        val deleted = mutableListOf<String>()
        val mediaAsked = mutableListOf<Boolean>()
        var linkCalls = 0
        var statusCalls = 0
        var link = ChatExportDownload(
            exportId = "e1",
            fileName = "zillit-chats-night-shoot-2026-10-14.zip",
            sizeBytes = 48_213_377,
            expiresInSeconds = 3600,
            url = "https://bucket.s3/one?X-Amz-Signature=a",
        )

        override suspend fun start(includeMedia: Boolean): ZillitResult<StartedChatExport> {
            mediaAsked += includeMedia
            return started?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Success(
                    StartedChatExport(export("new", ChatExportStatus.Queued, includeMedia = includeMedia)),
                )
        }

        override suspend fun exports() = ZillitResult.Success(rows.toList())

        override suspend fun export(exportId: String): ZillitResult<ChatExport> {
            statusCalls++
            return rows.firstOrNull { it.exportId == exportId }
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Http(404, "cnc_chat_export_not_found"))
        }

        override suspend fun downloadLink(exportId: String): ZillitResult<ChatExportDownload> {
            linkCalls++
            return ZillitResult.Success(link)
        }

        override suspend fun delete(exportId: String): ZillitResult<ChatExport> {
            deleted += exportId
            val before = rows.first { it.exportId == exportId }
            val after = before.copy(
                status = if (before.isInProgress) ChatExportStatus.Cancelled else ChatExportStatus.Expired,
            )
            rows.replaceAll { if (it.exportId == exportId) after else it }
            return ZillitResult.Success(after)
        }
    }

    @Test
    fun `the list draws the dialog and the running export leads it`() = runTest(dispatcher) {
        val repo = FakeExports(
            listOf(
                export("done", ChatExportStatus.Completed),
                export("running", ChatExportStatus.Processing),
            ),
        )
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()

        assertEquals("running", vm.state.value.latest?.exportId)
        assertEquals(listOf("done"), vm.state.value.earlier.map { it.exportId })
        assertFalse(vm.state.value.canStart, "one export per user per production at a time")
        vm.stopWatching()
    }

    @Test
    fun `the media choice reaches the server and nothing else does`() = runTest(dispatcher) {
        val repo = FakeExports()
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.IncludeMedia(false))
        vm.onEvent(ChatExportEvent.Start)
        runCurrent()

        assertEquals(listOf(false), repo.mediaAsked)
        assertFalse(vm.state.value.latest!!.includeMedia, "the new export is shown as the text-only one it is")
        vm.stopWatching()
    }

    @Test
    fun `an export already running is shown with the server's words, not an error`() = runTest(dispatcher) {
        val running = export("running", ChatExportStatus.Processing)
        val repo = FakeExports(
            started = StartedChatExport(
                export = running,
                alreadyRunning = true,
                message = "cnc_chat_export_already_in_progress",
            ),
        )
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.Start)
        runCurrent()

        assertNull(vm.state.value.error, "a second request is answered, not refused")
        assertNotNull(vm.state.value.info)
        assertEquals("running", vm.state.value.latest?.exportId, "the running one is what is shown")
        vm.stopWatching()
    }

    @Test
    fun `a socket push replaces the export in place`() = runTest(dispatcher) {
        val updates = MutableSharedFlow<ChatExportUpdate>(extraBufferCapacity = 4)
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Processing, exported = 2, total = 40)))
        val vm = ChatExportViewModel(repo, updates = updates, isSocketConnected = { true })

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        updates.emit(
            ChatExportUpdate(
                export = export("e1", ChatExportStatus.Processing, exported = 31, total = 40),
                projectId = "p1",
            ),
        )
        runCurrent()

        assertEquals(1, vm.state.value.exports.size, "the same export, not a second row")
        assertEquals(31, vm.state.value.latest?.exportedConversations)
        vm.stopWatching()
    }

    @Test
    fun `a retry pushing the export back to queued is not an error and not a step back`() = runTest(dispatcher) {
        val updates = MutableSharedFlow<ChatExportUpdate>(extraBufferCapacity = 4)
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Processing, exported = 31, total = 40)))
        val vm = ChatExportViewModel(repo, updates = updates, isSocketConnected = { true })

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        updates.emit(
            ChatExportUpdate(export("e1", ChatExportStatus.Queued, exported = 28, total = 40), projectId = "p1"),
        )
        runCurrent()

        val export = vm.state.value.latest!!
        assertEquals(ChatExportStatus.Queued, export.status)
        assertTrue(export.isInProgress, "queued after processing is a retry, still being built")
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.canStart, "and still no room for a second export")
        vm.stopWatching()
    }

    @Test
    fun `the poll stands in only while the socket is down`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Processing)))
        var connected = true
        val vm = ChatExportViewModel(repo, isSocketConnected = { connected }, pollMillis = 1_000)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(0, repo.statusCalls, "the socket's own pushes are better than a poll")

        connected = false
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(repo.statusCalls > 0, "with the socket down, something has to ask")
        vm.stopWatching()
    }

    @Test
    fun `the poll stops as soon as the export settles`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Processing)))
        val vm = ChatExportViewModel(repo, isSocketConnected = { false }, pollMillis = 1_000)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        val whileRunning = repo.statusCalls
        assertTrue(whileRunning > 0)

        repo.rows = mutableListOf(export("e1", ChatExportStatus.Completed))
        advanceTimeBy(1_500)
        runCurrent()
        val afterFinishing = repo.statusCalls

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(afterFinishing, repo.statusCalls, "a finished export is not polled for ever")
        assertTrue(vm.state.value.canStart, "and a new export may be started")
        vm.stopWatching()
    }

    @Test
    fun `the link is fetched on the click, never on load`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Completed)))
        val vm = ChatExportViewModel(repo)
        val opened = mutableListOf<ChatExportEffect>()
        val collector = launch { vm.effects.toList(opened) }

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        assertEquals(0, repo.linkCalls, "a link lives an hour — asking on load wastes it")

        vm.onEvent(ChatExportEvent.Download("e1"))
        runCurrent()
        assertEquals(1, repo.linkCalls)
        assertEquals(
            listOf<ChatExportEffect>(ChatExportEffect.OpenDownload(repo.link.url, repo.link.fileName)),
            opened.toList(),
            "the browser is handed the link; the app never fetches the ZIP",
        )

        // And again an hour later: a second click asks for a second link
        // rather than reusing the first.
        vm.onEvent(ChatExportEvent.Download("e1"))
        runCurrent()
        assertEquals(2, repo.linkCalls)

        collector.cancel()
        vm.stopWatching()
    }

    @Test
    fun `cancelling a running export stops it, with nothing to confirm`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Processing)))
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        vm.onEvent(ChatExportEvent.Cancel("e1"))
        runCurrent()

        assertEquals(listOf("e1"), repo.deleted)
        assertEquals(ChatExportStatus.Cancelled, vm.state.value.latest?.status)
        assertTrue(vm.state.value.canStart, "a cancelled export leaves room for another")
        vm.stopWatching()
    }

    @Test
    fun `removing a finished export asks first, and the question can be dropped`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Completed)))
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        vm.onEvent(ChatExportEvent.AskRemove(vm.state.value.latest!!))
        runCurrent()
        assertNotNull(vm.state.value.pendingRemoval)
        assertTrue(repo.deleted.isEmpty(), "asking is not doing")

        vm.onEvent(ChatExportEvent.DismissRemove)
        runCurrent()
        assertNull(vm.state.value.pendingRemoval)
        assertTrue(repo.deleted.isEmpty())

        vm.onEvent(ChatExportEvent.AskRemove(vm.state.value.latest!!))
        vm.onEvent(ChatExportEvent.ConfirmRemove)
        runCurrent()
        assertEquals(listOf("e1"), repo.deleted)
        assertEquals(ChatExportStatus.Expired, vm.state.value.latest?.status)
        vm.stopWatching()
    }

    @Test
    fun `re-opening the dialog mid-export restores it from the list`() = runTest(dispatcher) {
        val repo = FakeExports(listOf(export("e1", ChatExportStatus.Processing, exported = 9, total = 40)))
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()
        // A stale question and a stale complaint from the last time it was
        // open must not come back with it.
        vm.onEvent(ChatExportEvent.AskRemove(vm.state.value.latest!!))
        vm.onEvent(ChatExportEvent.Load)
        runCurrent()

        assertNull(vm.state.value.pendingRemoval)
        assertEquals(9, vm.state.value.latest?.exportedConversations)
        assertEquals(0.225f, assertNotNull(vm.state.value.latest?.progress), "9 of 40 conversations")
        vm.stopWatching()
    }

    @Test
    fun `a failure is shown and leaves the dialog usable`() = runTest(dispatcher) {
        val repo = object : ChatExportRepository by FakeExports() {
            override suspend fun exports(): ZillitResult<List<ChatExport>> =
                ZillitResult.Failure(ZillitError.Http(400, "cnc_chat_export_storage_unresolved"))
        }
        val vm = ChatExportViewModel(repo)

        vm.onEvent(ChatExportEvent.Load)
        runCurrent()

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.canStart, "nothing is running, so Export is still offered")
        vm.stopWatching()
    }
}

private fun export(
    id: String,
    status: ChatExportStatus,
    exported: Int = 0,
    total: Int = 0,
    includeMedia: Boolean = true,
) = ChatExport(
    exportId = id,
    status = status,
    includeMedia = includeMedia,
    totalConversations = total,
    exportedConversations = exported,
    requestedOnMillis = 1_791_975_262_243,
)
