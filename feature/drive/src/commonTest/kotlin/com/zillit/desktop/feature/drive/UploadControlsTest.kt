package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.CANCELLED
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.UploadCheckpoint
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.UploadState
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUploader
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.drive.ui.PickedFile
import com.zillit.desktop.feature.drive.ui.UploadTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the queue's row says about an upload, for tests that only care about the state. */
private fun QueuedUpload.stateName(): String = state::class.simpleName.orEmpty()

class UploadStateTest {

    private fun row(state: UploadState) = QueuedUpload("u", "a.txt", 10, null, state)

    @Test
    fun `only a waiting or sending upload can be paused`() {
        assertTrue(row(UploadState.Queued).canPause)
        assertTrue(row(UploadState.InProgress(1, 3)).canPause)
        assertFalse(row(UploadState.Paused(1, 3)).canPause)
        assertFalse(row(UploadState.Failed("x")).canPause)
        assertFalse(row(UploadState.Done("f")).canPause)
    }

    @Test
    fun `an upload on its completion call cannot be paused`() {
        assertTrue((UploadState.InProgress(3, 3)).isFinishing)
        assertFalse(row(UploadState.InProgress(3, 3)).canPause)
        assertFalse(row(UploadState.Completing).canPause)
    }

    @Test
    fun `a failure can be retried but a cancel cannot`() {
        assertTrue(row(UploadState.Failed("The connection dropped")).canRetry)
        assertFalse(row(UploadState.Failed(CANCELLED)).canRetry)
        assertFalse(row(UploadState.Paused(1, 3)).canRetry)
        assertFalse(row(UploadState.Done("f")).canRetry)
    }

    @Test
    fun `a paused upload keeps the share it had reached`() {
        assertEquals(0.5f, row(UploadState.Paused(2, 4)).fraction)
        assertTrue(row(UploadState.Paused(2, 4)).isPaused)
        assertFalse(row(UploadState.Paused(2, 4)).isSettled, "a paused upload is not finished")
    }
}

/** Pause, resume and retry as the user sees them — through the real queue and a scripted uploader. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadControlsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val plan = UploadPlan(fileSizeBytes = 30, chunkSizeBytes = 10, totalParts = 3)
    private fun checkpoint(vararg parts: Int, id: String = "s1") =
        UploadCheckpoint(id, plan, parts.associateWith { "etag$it" })

    private class Call(val file: PickedFile, val resume: UploadCheckpoint?)

    /** One scripted upload: what it was asked, a way to report a checkpoint and progress, and its outcome. */
    private fun interface Step {
        suspend operator fun invoke(
            call: Call,
            checkpointed: (UploadCheckpoint) -> Unit,
            progress: (Int, Int) -> Unit,
        ): ZillitResult<DriveItem>
    }

    /** Each call to [upload] runs the next script entry. */
    private class ScriptedUploader : DriveUploader {
        val calls = mutableListOf<Call>()
        val script = ArrayDeque<Step>()

        override suspend fun upload(
            file: PickedFile,
            target: UploadTarget,
            resume: UploadCheckpoint?,
            onCheckpoint: (UploadCheckpoint) -> Unit,
            onProgress: (Int, Int) -> Unit,
        ): ZillitResult<DriveItem> {
            val call = Call(file, resume).also(calls::add)
            return script.removeFirst().invoke(call, onCheckpoint, onProgress)
        }
    }

    private class RecordingRepo : FakeDriveRepository() {
        val aborted = mutableListOf<String>()
        override suspend fun abortUpload(uploadId: String): ZillitResult<Unit> {
            aborted += uploadId
            return ok()
        }
    }

    private val done = ZillitResult.Success(DriveItem(id = "new", kind = DriveItemKind.File, name = "a.txt"))
    private val admin = DriveViewer(
        userId = "u1", canView = true, canPost = true, canDownload = true, isAdmin = true, ready = true,
    )
    private fun file(name: String = "a.txt") = PickedFile("/tmp/$name", name, 30, "text/plain")

    private val repo = RecordingRepo()
    private val uploader = ScriptedUploader()

    /** Adds the next scripted upload. */
    private fun step(step: Step) = uploader.script.addLast(step)

    private fun TestScope.start(vararg files: PickedFile): DriveViewModel {
        val vm = DriveViewModel(repository = repo, viewer = { admin }, uploader = uploader)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.AddUploadFiles(files.toList()))
        runCurrent()
        return vm
    }

    private fun DriveViewModel.rows() = state.value.uploads

    @Test
    fun `pausing keeps the parts sent and resuming sends only the rest`() = runTest(dispatcher) {
        step { _, checkpointed, progress ->
            checkpointed(checkpoint(1))
            progress(1, 3)
            awaitCancellation()
        }
        step { call, _, progress ->
            assertEquals(checkpoint(1), call.resume, "the resume carries what S3 already holds")
            progress(3, 3)
            done
        }
        val vm = start(file())
        assertEquals("InProgress", vm.rows().single().stateName())

        vm.onEvent(DriveEvent.PauseUpload(vm.rows().single().id))
        runCurrent()
        assertEquals(UploadState.Paused(1, 3), vm.rows().single().state)

        vm.onEvent(DriveEvent.ResumeUpload(vm.rows().single().id))
        runCurrent()
        assertEquals("Done", vm.rows().single().stateName())
        assertEquals(2, uploader.calls.size)
        assertTrue(repo.aborted.isEmpty(), "a pause leaves the server's session alone")
    }

    @Test
    fun `a pause cannot be undone by progress the stopped coroutine still reports`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        step { _, checkpointed, progress ->
            checkpointed(checkpoint(1))
            progress(1, 3)
            // Past the point where cancellation is looked at: a part that finishes anyway.
            withContext(NonCancellable) {
                gate.await()
                progress(2, 3)
            }
            awaitCancellation()
        }
        val vm = start(file())

        vm.onEvent(DriveEvent.PauseUpload(vm.rows().single().id))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals("Paused", vm.rows().single().stateName())
    }

    @Test
    fun `an upload on its completion call is not paused`() = runTest(dispatcher) {
        step { _, checkpointed, progress ->
            checkpointed(checkpoint(1, 2, 3))
            progress(3, 3)
            awaitCancellation()
        }
        val vm = start(file())

        vm.onEvent(DriveEvent.PauseUpload(vm.rows().single().id))
        runCurrent()

        assertEquals(UploadState.InProgress(3, 3), vm.rows().single().state)
    }

    @Test
    fun `a failed upload retries from where it stopped`() = runTest(dispatcher) {
        step { _, checkpointed, progress ->
            checkpointed(checkpoint(1, 2))
            progress(2, 3)
            ZillitResult.Failure(ZillitError.Unknown("connection reset"))
        }
        step { call, _, progress ->
            assertEquals(checkpoint(1, 2), call.resume)
            progress(3, 3)
            done
        }
        val vm = start(file())
        assertTrue(vm.rows().single().canRetry)

        vm.onEvent(DriveEvent.RetryUpload(vm.rows().single().id))
        runCurrent()

        assertEquals("Done", vm.rows().single().stateName())
        assertEquals(2, uploader.calls.size)
    }

    @Test
    fun `a failure before any session opened retries from the start`() = runTest(dispatcher) {
        step { _, _, _ -> ZillitResult.Failure(ZillitError.Unknown("offline")) }
        step { call, _, _ ->
            assertNull(call.resume)
            done
        }
        val vm = start(file())

        vm.onEvent(DriveEvent.RetryUpload(vm.rows().single().id))
        runCurrent()

        assertEquals("Done", vm.rows().single().stateName())
    }

    @Test
    fun `cancelling aborts the session and cannot be retried`() = runTest(dispatcher) {
        step { _, checkpointed, _ ->
            checkpointed(checkpoint(1, id = "s9"))
            awaitCancellation()
        }
        val vm = start(file())

        vm.onEvent(DriveEvent.CancelUpload(vm.rows().single().id))
        runCurrent()
        vm.onEvent(DriveEvent.RetryUpload(vm.rows().single().id))
        runCurrent()

        assertEquals(UploadState.Failed(CANCELLED), vm.rows().single().state)
        assertEquals(listOf("s9"), repo.aborted)
        assertEquals(1, uploader.calls.size, "no second attempt")
    }

    @Test
    fun `cancelling an upload already on its completion call leaves the session`() = runTest(dispatcher) {
        step { _, checkpointed, progress ->
            checkpointed(checkpoint(1, 2, 3, id = "s7"))
            progress(3, 3)
            awaitCancellation()
        }
        val vm = start(file())

        vm.onEvent(DriveEvent.CancelUpload(vm.rows().single().id))
        runCurrent()

        assertTrue(repo.aborted.isEmpty(), "the file may already be assembled")
    }

    @Test
    fun `removing a failed row drops its session but a finished one has none`() = runTest(dispatcher) {
        step { _, checkpointed, _ ->
            checkpointed(checkpoint(1, id = "s2"))
            ZillitResult.Failure(ZillitError.Unknown("reset"))
        }
        step { _, checkpointed, _ ->
            checkpointed(checkpoint(1, 2, 3, id = "s3"))
            done
        }
        val vm = start(file("a.txt"), file("b.txt"))

        vm.onEvent(DriveEvent.ClearFinishedUploads)
        runCurrent()

        assertEquals(listOf("s2"), repo.aborted, "only the stalled session is dropped")
        assertTrue(vm.rows().isEmpty())
    }

    @Test
    fun `a paused row waiting for a slot does not start until resumed`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repeat(3) { step { _, _, _ -> gate.await(); done } }
        step { _, _, _ -> done }
        val vm = start(file("1.txt"), file("2.txt"), file("3.txt"), file("4.txt"))
        val fourth = vm.rows().last()
        assertEquals("Queued", fourth.stateName(), "three at a time")

        vm.onEvent(DriveEvent.PauseUpload(fourth.id))
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, uploader.calls.size, "the paused one never took a slot")
        assertEquals("Paused", vm.rows().last().stateName())

        vm.onEvent(DriveEvent.ResumeUpload(fourth.id))
        runCurrent()
        assertEquals("Done", vm.rows().last().stateName())
        assertEquals(4, uploader.calls.size)
    }
}
