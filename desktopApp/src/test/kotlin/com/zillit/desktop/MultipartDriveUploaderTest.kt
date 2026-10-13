package com.zillit.desktop

import com.sun.net.httpserver.HttpServer
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.UploadCheckpoint
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.UploadSession
import com.zillit.desktop.feature.drive.ui.PickedFile
import com.zillit.desktop.feature.drive.ui.UploadTarget
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The multipart uploader against a real local HTTP server standing in for S3,
 * and a scripted repository standing in for the API: retries, what a failure
 * leaves behind, and the three ways a resume can go.
 */
class MultipartDriveUploaderTest {

    private val plan = UploadPlan(fileSizeBytes = 30, chunkSizeBytes = 10, totalParts = 3)
    private val bytes = ByteArray(30) { it.toByte() }
    private lateinit var file: File
    private lateinit var server: HttpServer
    private lateinit var base: String

    /** (part, body) of every PUT the "S3" server received, in arrival order. */
    private val puts = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    /** Status for the n-th attempt (1-based) at a part; 200 unless a test says otherwise. */
    private var statusFor: (part: Int, attempt: Int) -> Int = { _, _ -> 200 }
    private val attempts = mutableMapOf<Int, Int>()

    private val initiated = mutableListOf<String>()
    private val aborted = CopyOnWriteArrayList<String>()
    private val completions = CopyOnWriteArrayList<Pair<String, Map<Int, String?>>>()

    /** The `file_name` each completion carried, in order. */
    private val completedAs = CopyOnWriteArrayList<String>()
    private val sessionsToOpen = ArrayDeque<UploadSession>()
    private var resumeAnswer: (String) -> ZillitResult<UploadSession> =
        { ZillitResult.Failure(ZillitError.Unknown("gone")) }
    private var completeAnswer: (Int) -> ZillitResult<DriveItem> = { done }

    private val done = ZillitResult.Success(DriveItem(id = "file-1", kind = DriveItemKind.File, name = "a.bin"))
    private val naps = mutableListOf<Long>()

    @BeforeTest
    fun setUp() {
        file = File.createTempFile("upload", ".bin").also { it.writeBytes(bytes); it.deleteOnExit() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/p/") { exchange ->
            val part = exchange.requestURI.path.substringAfterLast('/').toInt()
            val body = exchange.requestBody.readBytes()
            val attempt = synchronized(attempts) { attempts.merge(part, 1, Int::plus)!! }
            val status = statusFor(part, attempt)
            if (status == 200) {
                puts += part to body
                exchange.responseHeaders.add("ETag", "\"etag$part\"")
            }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun session(id: String, chunk: Long = 10, parts: List<Int> = listOf(1, 2, 3)) = UploadSession(
        uploadId = id,
        fileName = "a.bin",
        plan = UploadPlan(30, chunk, 3),
        parts = parts.map { UploadPart(it, "$base/p/$it") },
    )

    private val repository: DriveRepository = Proxy.newProxyInstance(
        DriveRepository::class.java.classLoader,
        arrayOf(DriveRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "initiateUpload" -> {
                initiated += (args[0] as com.zillit.desktop.feature.drive.domain.UploadRequest).fileName
                ZillitResult.Success(sessionsToOpen.removeFirst())
            }
            "resumeUpload" -> resumeAnswer(args[0] as String)
            "abortUpload" -> ZillitResult.Success(Unit).also { aborted += args[0] as String }
            "completeUpload" -> {
                @Suppress("UNCHECKED_CAST")
                val parts = args[1] as List<UploadPart>
                completions += (args[0] as String) to parts.associate { it.partNumber to it.etag }
                completedAs += args[2] as String
                completeAnswer(completions.size)
            }
            else -> error("unexpected call: ${method.name}")
        }
    } as DriveRepository

    private fun uploader() = MultipartDriveUploader(repository, HttpClient(), pause = { naps += it })

    private fun upload(
        resume: UploadCheckpoint? = null,
        checkpoints: MutableList<UploadCheckpoint> = mutableListOf(),
        named: String = "a.bin",
    ): ZillitResult<DriveItem> = runBlocking {
        val pick = PickedFile(file.absolutePath, named, 30, "application/octet-stream")
        uploader().upload(pick, UploadTarget(null), resume, { checkpoints += it }, { _, _ -> })
    }

    private val nameTaken = ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = "duplicate_file_name"))

    // -- a fresh upload ------------------------------------------------------

    @Test
    fun `a fresh upload sends every part's own bytes and completes with their etags`() {
        sessionsToOpen += session("s1")
        val checkpoints = mutableListOf<UploadCheckpoint>()

        val result = upload(checkpoints = checkpoints)

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf(1, 2, 3), puts.map { it.first })
        puts.forEach { (part, body) ->
            assertContentEquals(bytes.copyOfRange((part - 1) * 10, part * 10), body, "part $part is its own slice")
        }
        assertEquals(mapOf(1 to "etag1", 2 to "etag2", 3 to "etag3"), completions.single().second)
        assertEquals(
            listOf(0, 1, 2, 3),
            checkpoints.map { it.uploadedParts },
            "told the session at once, then after each part",
        )
    }

    // -- retries -------------------------------------------------------------

    @Test
    fun `a part the server fails once is tried again after a pause`() {
        sessionsToOpen += session("s1")
        statusFor = { part, attempt -> if (part == 2 && attempt == 1) 503 else 200 }

        val result = upload()

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf(650L), naps, "one backoff, the web's 650 ms")
        assertEquals(2, attempts[2])
    }

    @Test
    fun `backoff doubles up to three attempts then gives up and keeps the session`() {
        sessionsToOpen += session("s1")
        statusFor = { part, _ -> if (part == 2) 500 else 200 }
        val checkpoints = mutableListOf<UploadCheckpoint>()

        val result = upload(checkpoints = checkpoints)

        assertIs<ZillitResult.Failure>(result)
        assertEquals(3, attempts[2])
        assertEquals(listOf(650L, 1300L), naps)
        assertTrue(aborted.isEmpty(), "the session stays open for Retry")
        assertTrue(completions.isEmpty())
        assertEquals(setOf(1), checkpoints.last().etags.keys, "part 1 is what a retry need not resend")
    }

    @Test
    fun `a refusal that waiting cannot fix is not retried`() {
        sessionsToOpen += session("s1")
        statusFor = { part, _ -> if (part == 1) 403 else 200 }

        val result = upload()

        assertIs<ZillitResult.Failure>(result)
        assertEquals(1, attempts[1])
        assertTrue(naps.isEmpty())
    }

    // -- resuming ------------------------------------------------------------

    @Test
    fun `a resume sends only the missing parts and completes with all the etags`() {
        resumeAnswer = { ZillitResult.Success(session(it)) }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1"))

        val result = upload(resume = checkpoint)

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf(2, 3), puts.map { it.first }, "part 1 is already at S3")
        assertEquals("s1", completions.single().first, "the same session, not a new one")
        assertEquals(mapOf(1 to "etag1", 2 to "etag2", 3 to "etag3"), completions.single().second)
        assertTrue(initiated.isEmpty() && aborted.isEmpty())
    }

    @Test
    fun `a resume takes the fresh urls the server offers`() {
        // The server only lists what is left, on new addresses.
        resumeAnswer = { ZillitResult.Success(session(it, parts = listOf(3))) }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1", 2 to "etag2"))

        val result = upload(resume = checkpoint)

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf(3), puts.map { it.first })
    }

    @Test
    fun `a session the server has forgotten starts again from nothing`() {
        resumeAnswer = { ZillitResult.Failure(ZillitError.Unknown("session not found")) }
        sessionsToOpen += session("s2")
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1", 2 to "etag2"))

        val result = upload(resume = checkpoint)

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf("s1"), aborted.toList(), "the dead session is dropped")
        assertEquals(listOf(1, 2, 3), puts.map { it.first }, "every part again — those etags were for s1")
        assertEquals("s2", completions.single().first)
    }

    @Test
    fun `a session that would slice the file differently is not resumed`() {
        resumeAnswer = { ZillitResult.Success(session(it, chunk = 15)) }
        sessionsToOpen += session("s2")
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1"))

        val result = upload(resume = checkpoint)

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals("s2", completions.single().first)
        assertEquals(listOf("s1"), aborted.toList())
    }

    @Test
    fun `a completion refused for missing parts restarts once with a new session`() {
        resumeAnswer = { ZillitResult.Success(session(it)) }
        sessionsToOpen += session("s2")
        completeAnswer = { call ->
            if (call == 1) {
                ZillitResult.Failure(ZillitError.Unknown("One or more parts could not be found: entity tag mismatch"))
            } else {
                done
            }
        }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1"))

        val result = upload(resume = checkpoint)

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf("s1", "s2"), completions.map { it.first })
        assertEquals(listOf("s1"), aborted.toList())
    }

    @Test
    fun `any other completion failure after a resume is left for Retry`() {
        resumeAnswer = { ZillitResult.Success(session(it)) }
        completeAnswer = { ZillitResult.Failure(ZillitError.Unknown("server busy")) }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1"))

        val result = upload(resume = checkpoint)

        assertIs<ZillitResult.Failure>(result)
        assertEquals(1, completions.size, "no second session behind the user's back")
        assertTrue(initiated.isEmpty() && aborted.isEmpty())
    }

    @Test
    fun `the refusal wording that means parts are lost`() {
        assertTrue("The specified upload does not exist (NoSuchUpload)".namesLostParts())
        assertTrue("One or more of the specified parts could not be found".namesLostParts())
        assertTrue("entity tag may not match".namesLostParts())
        assertTrue(!"Request timed out".namesLostParts())
    }

    // -- renaming after a duplicate -----------------------------------------------

    @Test
    fun `a session remembers the name it was opened under`() {
        sessionsToOpen += session("s1")
        val checkpoints = mutableListOf<UploadCheckpoint>()

        upload(checkpoints = checkpoints, named = "first.bin")

        assertTrue(checkpoints.all { it.fileName == "first.bin" })
    }

    @Test
    fun `a refused name is returned as it came, with the session left open for the rename`() {
        sessionsToOpen += session("s1")
        completeAnswer = { nameTaken }

        val result = upload(named = "a.bin")

        assertIs<ZillitResult.Failure>(result)
        assertTrue((result.error as ZillitError.Http).serverMessage == "duplicate_file_name")
        assertTrue(aborted.isEmpty(), "the session stays")
        assertEquals(1, initiated.size, "and no second one is opened behind the user's back")
    }

    @Test
    fun `a renamed resume completes under the new name without sending a byte`() {
        resumeAnswer = { ZillitResult.Success(session(it, parts = emptyList())) }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1", 2 to "etag2", 3 to "etag3"), "old.bin")

        val result = upload(resume = checkpoint, named = "new.bin")

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf("new.bin"), completedAs.toList())
        assertTrue(puts.isEmpty(), "the bytes were already there")
        assertTrue(initiated.isEmpty() && aborted.isEmpty())
    }

    @Test
    fun `a renamed resume the server still calls a duplicate opens a session under the new name`() {
        resumeAnswer = { ZillitResult.Success(session(it, parts = emptyList())) }
        sessionsToOpen += session("s2")
        var calls = 0
        completeAnswer = { if (++calls == 1) nameTaken else done }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1", 2 to "etag2", 3 to "etag3"), "old.bin")

        val result = upload(resume = checkpoint, named = "new.bin")

        assertIs<ZillitResult.Success<DriveItem>>(result)
        assertEquals(listOf("s1"), aborted.toList(), "the session holding the old name is dropped")
        assertEquals(listOf("new.bin"), initiated.toList())
        assertEquals(listOf(1, 2, 3), puts.map { it.first }, "uploaded again, this time under the new name")
        assertEquals(listOf("new.bin", "new.bin"), completedAs.toList())
    }

    @Test
    fun `a duplicate on a resume that kept its name is not retried as a fresh session`() {
        resumeAnswer = { ZillitResult.Success(session(it, parts = emptyList())) }
        completeAnswer = { nameTaken }
        val checkpoint = UploadCheckpoint("s1", plan, mapOf(1 to "etag1", 2 to "etag2", 3 to "etag3"), "a.bin")

        val result = upload(resume = checkpoint, named = "a.bin")

        assertIs<ZillitResult.Failure>(result)
        assertTrue(initiated.isEmpty() && aborted.isEmpty())
    }
}
