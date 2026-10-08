package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.documentdistribution.data.DocDistRepositoryImpl
import com.zillit.desktop.feature.documentdistribution.domain.DocDistTransfer
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.UploadBatch
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an upload puts on the wire: the batch that groups one action's requests
 * into one notification, a PDF's cover, and a folder created for an upload.
 */
class UploadWireTest {

    private class Transfer(var coverFails: Boolean = false) : DocDistTransfer {
        val puts = mutableListOf<String>()
        val multipart = mutableListOf<Map<String, String>>()
        override suspend fun putObject(key: String, contentType: String, bytes: ByteArray):
            ZillitResult<DocumentStorage> {
            if (coverFails && "thumbnails" in key) return ZillitResult.Failure(ZillitError.Validation("denied"))
            puts += key
            return ZillitResult.Success(DocumentStorage(key = key, bucket = "bkt", region = "eu"))
        }
        override suspend fun postMultipart(
            url: String,
            fields: Map<String, String>,
            file: LocalFile,
        ): ZillitResult<kotlinx.serialization.json.JsonElement> {
            multipart += fields
            return ZillitResult.Success(Json.parseToJsonElement("""{"_id":"m1","original_name":"${file.name}"}"""))
        }
    }

    private class Seen(val path: String, val body: JsonObject?)

    private fun repo(
        transfer: Transfer,
        s3: Boolean = true,
        answer: (Int) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to DOC },
    ): Pair<DocDistRepositoryImpl, MutableList<Seen>> {
        val seen = mutableListOf<Seen>()
        val engine = MockEngine { request: HttpRequestData ->
            seen += Seen(
                request.url.encodedPath,
                (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject },
            )
            val (status, body) = answer(seen.size)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return DocDistRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ Factory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            transfer = transfer,
            isS3Storage = { s3 },
            newUniqueId = { "uid" },
        ) to seen
    }

    private fun pdf() = LocalFile("a.pdf", "application/pdf", ByteArray(4))
    private val cover = LocalFile("a_thumb.jpg", "image/jpeg", ByteArray(2))

    // -- the batch -------------------------------------------------------------------------------------------

    @Test
    fun `a document carries its batch as a json id and a number`() = runTest {
        val (repo, seen) = repo(Transfer())
        val batch = UploadBatch.create(7)

        repo.uploadDocument(pdf(), "f1", "2026-10-07", batch, thumbnail = null)

        val body = seen.single().body!!
        assertEquals(batch.id, body["upload_id"]!!.jsonPrimitive.content)
        // A number, not text: the JSON path and the multipart path differ here.
        assertEquals(7, body["upload_total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the batch fields come after the document ones`() = runTest {
        val (repo, seen) = repo(Transfer())
        repo.uploadDocument(pdf(), "f1", "2026-10-07", UploadBatch.create(1), null)
        // `document_date` must lead (a leading JSON null trips the body hash); the batch trails.
        val keys = seen.single().body!!.keys.toList()
        assertEquals("document_date", keys.first())
        assertEquals(listOf("upload_id", "upload_total"), keys.takeLast(2))
    }

    @Test
    fun `a request with no batch sends neither field`() = runTest {
        val (repo, seen) = repo(Transfer())
        repo.uploadDocument(pdf(), "f1", null, batch = null, thumbnail = null)
        val body = seen.single().body!!
        assertNull(body["upload_id"])
        assertNull(body["upload_total"])
    }

    @Test
    fun `a local production sends the batch as form text`() = runTest {
        val transfer = Transfer()
        val (repo, _) = repo(transfer, s3 = false)
        val batch = UploadBatch.create(3)

        repo.uploadDocument(pdf(), "f1", "2026-10-07", batch, thumbnail = cover)

        val fields = transfer.multipart.single()
        assertEquals(batch.id, fields["upload_id"])
        assertEquals("3", fields["upload_total"])
        assertTrue("thumbnail" !in fields, "the multipart endpoint takes no cover")
    }

    // -- the cover -------------------------------------------------------------------------------------------

    @Test
    fun `a pdf's cover is uploaded beside it and named on the registration`() = runTest {
        val transfer = Transfer()
        val (repo, seen) = repo(transfer)

        repo.uploadDocument(pdf(), "f1", null, null, cover)

        assertEquals(
            setOf("document-distribution/uid/a.pdf", "document-distribution/thumbnails/uid/a_thumb.jpg"),
            transfer.puts.toSet(),
        )
        assertEquals(
            "document-distribution/thumbnails/uid/a_thumb.jpg",
            seen.single().body!!["thumbnail"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a pdf with no cover registers with a blank one`() = runTest {
        val (repo, seen) = repo(Transfer())
        repo.uploadDocument(pdf(), "f1", null, null, thumbnail = null)
        assertEquals("", seen.single().body!!["thumbnail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an image is its own cover`() = runTest {
        val (repo, seen) = repo(Transfer())
        repo.uploadDocument(LocalFile("p.png", "image/png", ByteArray(4)), "f1", null, null, null)
        assertEquals("document-distribution/uid/p.png", seen.single().body!!["thumbnail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a cover that fails to upload never costs the document`() = runTest {
        val transfer = Transfer(coverFails = true)
        val (repo, seen) = repo(transfer)

        val answer = repo.uploadDocument(pdf(), "f1", null, null, cover)

        assertIs<ZillitResult.Success<LibraryDocument>>(answer)
        assertEquals("", seen.single().body!!["thumbnail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a registration the server refuses is retried once without the cover`() = runTest {
        val (repo, seen) = repo(Transfer()) { call ->
            if (call == 1) HttpStatusCode.InternalServerError to "{}" else HttpStatusCode.OK to DOC
        }

        val answer = repo.uploadDocument(pdf(), "f1", null, null, cover)

        assertIs<ZillitResult.Success<LibraryDocument>>(answer)
        assertEquals(2, seen.size)
        assertTrue(seen[0].body!!["thumbnail"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("", seen[1].body!!["thumbnail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a refusal with no cover is not retried`() = runTest {
        val (repo, seen) = repo(Transfer()) { _ -> HttpStatusCode.InternalServerError to "{}" }
        val answer = repo.uploadDocument(pdf(), "f1", null, null, thumbnail = null)
        assertIs<ZillitResult.Failure>(answer)
        assertEquals(1, seen.size)
    }

    // -- a folder made for an upload ----------------------------------------------------------------------------

    @Test
    fun `a created folder answers its id and carries the batch`() = runTest {
        val (repo, seen) = repo(Transfer()) { _ ->
            HttpStatusCode.OK to """{"status":1,"data":{"_id":"f9","name":"Docs"}}"""
        }
        val batch = UploadBatch.create(5)

        val answer = repo.createFolderForUpload("Docs", parentId = null, folderDate = "2026-10-07", batch = batch)

        assertEquals("f9", assertIs<ZillitResult.Success<String>>(answer).data)
        val body = seen.single().body!!
        assertEquals("Docs", body["name"]!!.jsonPrimitive.content)
        // Null, not omitted: null is what this service reads as "at the root".
        assertEquals(JsonNull, body["parent_id"])
        assertEquals("2026-10-07", body["folder_date"]!!.jsonPrimitive.content)
        assertEquals(5, body["upload_total"]!!.jsonPrimitive.int)
        assertEquals(batch.id, body["upload_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a folder under another names its parent`() = runTest {
        val (repo, seen) = repo(Transfer()) { _ -> HttpStatusCode.OK to """{"status":1,"data":{"_id":"f10"}}""" }
        repo.createFolderForUpload("Sub", "f9", "2026-10-07", null)
        assertEquals(JsonPrimitive("f9"), seen.single().body!!["parent_id"])
        assertNull(seen.single().body!!["upload_id"])
    }

    @Test
    fun `a soft refusal on a 200 is a failure with the server's words`() = runTest {
        val (repo, _) = repo(Transfer()) { _ -> HttpStatusCode.OK to """{"status":0,"message":"folder_name_exists"}""" }
        val answer = assertIs<ZillitResult.Failure>(repo.createFolderForUpload("Docs", null, "2026-10-07", null))
        assertTrue("exists" in answer.error.userMessage.lowercase(), answer.error.userMessage)
    }

    @Test
    fun `an answer with no id is a failure, never an empty parent`() = runTest {
        val (repo, _) = repo(Transfer()) { _ -> HttpStatusCode.OK to """{"status":1,"data":{}}""" }
        assertIs<ZillitResult.Failure>(repo.createFolderForUpload("Docs", null, "2026-10-07", null))
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val DOC = """{"status":1,"data":{"_id":"d1","original_name":"a.pdf"}}"""
    }
}
