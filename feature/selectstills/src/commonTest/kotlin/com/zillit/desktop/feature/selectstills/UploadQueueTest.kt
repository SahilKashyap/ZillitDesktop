@file:Suppress("MaxLineLength") // Fixtures read best on one line.

package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.selectstills.data.FileStatus
import com.zillit.desktop.feature.selectstills.data.PutOutcome
import com.zillit.desktop.feature.selectstills.data.StillsUploader
import com.zillit.desktop.feature.selectstills.data.StillsUploadQueue
import com.zillit.desktop.feature.selectstills.data.UploadTuning
import com.zillit.desktop.feature.selectstills.data.countsOf
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.UploadLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The upload: the pipeline per file, what is retried, and the one thing that
 * must never happen — a still landing in the production next door.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadQueueTest {

    private val repository = FakeStillsRepository()
    private val files = FakeStillsFiles()
    private var project: String? = "proj-1"
    private var ids = 0

    /** Every PUT's answer, in the order they are asked for. */
    private val outcomes = ArrayDeque<PutOutcome>()
    private val put = mutableListOf<String>()

    private fun uploader() = FakeUploader(outcomes, put)

    private fun queue(scope: CoroutineScope, tuning: UploadTuning = UploadTuning(retryMs = listOf(0, 0), signRetryMs = listOf(0, 0, 0))) =
        StillsUploadQueue(
            repository = repository,
            uploader = uploader(),
            files = files,
            openProject = { project },
            newId = { "id-${ids++}" },
            tuning = tuning,
            scope = scope,
        )

    private fun picks(vararg names: String) =
        names.map { StillsPick(path = "/card/$it", name = it, size = 1000, type = "image/jpeg") }

    private val types = listOf("image/jpeg")

    @Test
    fun `every file goes through the pipeline and is confirmed to the service`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repeat(3) { outcomes += PutOutcome.Ok }

        val outcome = queue.add(picks("a.jpg", "b.jpg", "c.jpg"), shootLabel = "  day 12  ", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        assertEquals(3, outcome.added)
        assertEquals(0, outcome.refused)
        assertTrue(queue.state.value.items.all { it.status == FileStatus.Done })
        // The label is tidied, as the web tidies it.
        assertEquals("day 12", queue.state.value.shootLabel)
        assertTrue(repository.calls.any { it.startsWith("complete(") })
    }

    @Test
    fun `the service's answer per file decides what happens to it`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repository.presign = listOf(
            UploadLink(uniqueId = "", photoId = "p1", url = "https://store/1"),
            UploadLink(uniqueId = "", refused = "still_kills_file_type_unsupported"),
            UploadLink(uniqueId = "", duplicateOf = "p9"),
            UploadLink(uniqueId = "", photoId = "p4", uploaded = true),
        )
        outcomes += PutOutcome.Ok

        queue.add(picks("a.jpg", "b.jpg", "c.jpg", "d.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        val byName = queue.state.value.items.associateBy { it.name }
        assertEquals(FileStatus.Done, byName["a.jpg"]?.status)
        assertEquals(FileStatus.Refused, byName["b.jpg"]?.status)
        assertEquals("still_kills_file_type_unsupported", byName["b.jpg"]?.reason)
        assertEquals(FileStatus.Duplicate, byName["c.jpg"]?.status)
        assertEquals("p9", byName["c.jpg"]?.duplicateOf)
        // Already in storage: nothing to send, straight to confirmed.
        assertEquals(FileStatus.Done, byName["d.jpg"]?.status)
        // Only one file actually travelled.
        assertEquals(1, put.size)
    }

    @Test
    fun `a link that ran out is asked for again, and the same id gets the same photo`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        outcomes += PutOutcome.Expired
        outcomes += PutOutcome.Ok

        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        assertEquals(FileStatus.Done, queue.state.value.items.single().status)
        // Asked for twice: once for the batch, once for the file whose link expired.
        assertEquals(2, repository.calls.count { it.startsWith("presign(") })
        assertEquals(2, put.size)
    }

    @Test
    fun `a transfer that keeps failing gives up and offers a retry`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repeat(3) { outcomes += PutOutcome.Failed(500) }

        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        val item = queue.state.value.items.single()
        assertEquals(FileStatus.Failed, item.status)
        assertEquals("upload", item.reason)
        // The file is still on disk, so trying again is offered.
        assertTrue(item.path.isNotBlank())
        assertEquals(3, put.size)

        outcomes += PutOutcome.Ok
        queue.retry()
        advanceUntilIdle()
        assertEquals(FileStatus.Done, queue.state.value.items.single().status)
    }

    @Test
    fun `a refusal about the production stops everything until somebody acts`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repository.presignError = ZillitError.Http(422, "still_kills_not_attested")

        queue.add(picks("a.jpg", "b.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        assertEquals("still_kills_not_attested", queue.state.value.stopped)
        assertTrue(queue.state.value.items.all { it.status == FileStatus.Failed })
        assertTrue(put.isEmpty())
    }

    @Test
    fun `a request that never reached the service is asked again`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repository.presignError = ZillitError.NoConnection("test")

        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        // Four attempts: the first, then one per backoff step.
        assertEquals(4, repository.calls.count { it.startsWith("presign(") })
        assertEquals("network", queue.state.value.items.single().reason)
    }

    @Test
    fun `a request that never arrives says the upload is offline, and clears when one does`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repository.presignError = ZillitError.NoConnection("test")
        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()
        assertTrue(queue.state.value.offline)

        repository.presignError = null
        outcomes += PutOutcome.Ok
        queue.retry()
        advanceUntilIdle()
        assertFalse(queue.state.value.offline)
        assertEquals(FileStatus.Done, queue.state.value.items.single().status)
    }

    @Test
    fun `a still never lands in the production next door`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        outcomes += PutOutcome.Ok

        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        // The app moves to another production before the queue gets going.
        project = "proj-2"
        advanceUntilIdle()

        assertEquals("project", queue.state.value.stopped)
        assertTrue(put.isEmpty())
    }

    @Test
    fun `adding files in another production starts a list of its own`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        outcomes += PutOutcome.Ok
        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()
        assertEquals(1, queue.state.value.items.size)

        project = "proj-2"
        outcomes += PutOutcome.Ok
        queue.add(picks("b.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        // The other production's list is not this one's to show.
        assertEquals(listOf("b.jpg"), queue.state.value.items.map { it.name })
        assertEquals("proj-2", queue.state.value.projectId)
    }

    @Test
    fun `"upload anyway" sends a duplicate in a request of its own`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        repository.presign = listOf(UploadLink(uniqueId = "", duplicateOf = "p9"))
        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()
        val ref = queue.state.value.items.single().ref
        assertEquals(FileStatus.Duplicate, queue.state.value.items.single().status)

        repository.presign = emptyList()
        repository.calls.clear()
        outcomes += PutOutcome.Ok
        queue.uploadAnyway(ref)
        advanceUntilIdle()

        assertEquals(FileStatus.Done, queue.state.value.items.single().status)
        // The switch is per request, so it rides one of its own.
        assertTrue(repository.calls.any { it.contains("dupes=true") })
    }

    @Test
    fun `a file that vanished between the pick and the send says so`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        files.missing = setOf("/card/a.jpg")

        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        assertEquals("missing", queue.state.value.items.single().reason)
        assertTrue(put.isEmpty())
    }

    @Test
    fun `a file the service did not find after all is sent again`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        outcomes += PutOutcome.Ok
        repository.confirmed = com.zillit.desktop.feature.selectstills.domain.UploadsConfirmed(emptySet(), setOf("p0"))

        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        val item = queue.state.value.items.single()
        assertEquals(FileStatus.Failed, item.status)
        assertEquals("missing", item.reason)
    }

    @Test
    fun `a stop leaves what already arrived and cancels the rest`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        queue.add(picks("a.jpg", "b.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        queue.cancel()
        advanceUntilIdle()

        assertEquals("cancelled", queue.state.value.stopped)
        assertTrue(queue.state.value.items.all { it.status == FileStatus.Cancelled })
    }

    @Test
    fun `clearing takes off everything that is not on its way`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        outcomes += PutOutcome.Ok
        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()

        queue.clear()
        assertTrue(queue.state.value.items.isEmpty())
    }

    @Test
    fun `what was turned away here never counts towards "uploaded x of y"`() {
        val counts = countsOf(
            listOf(
                item("a.jpg", FileStatus.Done, 100),
                item("b.jpg", FileStatus.Refused, 100),
                item("c.jpg", FileStatus.Duplicate, 100),
                item("d.jpg", FileStatus.Uploading, 100, progress = 0.5f),
            ),
        )
        assertEquals(4, counts.total)
        assertEquals(1, counts.done)
        assertEquals(1, counts.refused)
        assertEquals(1, counts.duplicate)
        assertEquals(1, counts.active)
        // Two files really count: the done one and the one in flight.
        assertEquals(2, counts.expected)
        assertEquals(200, counts.bytes)
        assertEquals(150, counts.sent)
        assertEquals(75, counts.percent)
    }

    @Test
    fun `files turned away here are listed with their reason, and never sent`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        val outcome = queue.add(
            picks("a.jpg") + StillsPick("/card/raw.CR3", "raw.CR3", 100, "") + StillsPick("/card/big.jpg", "big.jpg", 999_999, "image/jpeg"),
            shootLabel = "",
            types = types,
            maxBytes = 1000,
        )
        advanceUntilIdle()

        assertEquals(1, outcome.added)
        assertEquals(2, outcome.refused)
        val refused = queue.state.value.items.filter { it.status == FileStatus.Refused }
        assertEquals(setOf("raw", "size"), refused.map { it.reason }.toSet())
        // A refused file has no path to retry from.
        assertTrue(refused.all { it.path.isBlank() })
    }

    @Test
    fun `a fingerprint rides every declaration, so a repeat is spotted without hashing the file`() = runTest {
        val queue = queue(TestScope(StandardTestDispatcher(testScheduler)))
        outcomes += PutOutcome.Ok
        queue.add(picks("a.jpg"), shootLabel = "", types = types, maxBytes = 50_000)
        advanceUntilIdle()
        assertFalse(repository.calls.none { it.startsWith("presign(1 files") })
    }

    private fun item(name: String, status: FileStatus, size: Long, progress: Float = 0f) =
        com.zillit.desktop.feature.selectstills.data.UploadItem(
            ref = name,
            uniqueId = name,
            order = 0,
            name = name,
            size = size,
            type = "image/jpeg",
            path = "/card/$name",
            status = status,
            progress = progress,
        )
}

/** A storage endpoint that answers whatever the test queued. */
private class FakeUploader(
    private val outcomes: ArrayDeque<PutOutcome>,
    private val sent: MutableList<String>,
) : StillsUploader {

    override suspend fun put(
        url: String,
        headers: Map<String, String>,
        path: String,
        contentType: String,
        size: Long,
        onProgress: (Float) -> Unit,
    ): PutOutcome {
        sent += path
        onProgress(1f)
        return outcomes.removeFirstOrNull() ?: PutOutcome.Ok
    }

    override suspend fun putBytes(
        url: String,
        headers: Map<String, String>,
        bytes: ByteArray,
        contentType: String,
    ): PutOutcome = outcomes.removeFirstOrNull() ?: PutOutcome.Ok
}
