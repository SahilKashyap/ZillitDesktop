package com.zillit.desktop.feature.assetreport

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.assetreport.data.AssetRepositoryImpl
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the register asks the PO service for, and how it reads the answers. */
class AssetRepositoryTest {

    private data class Call(
        val method: HttpMethod,
        val host: String,
        val path: String,
        val query: String,
        val body: JsonObject?,
    )

    private val calls = mutableListOf<Call>()

    private val storedRecord = """
        {"status":1,"data":{"id":"a1","line_item_id":"l1","category":"Keep","comments":"On the truck",
          "comment_by":"u7","comment_at":1787140800000,
          "attachments":[{"media":"p/actual/dolly.jpg","bucket":"zillit-eu","region":"eu-west-2",
            "name":"dolly.jpg","content_type":"image","content_subtype":"jpg","uploaded_by":"u7"}]}}
    """.trimIndent()

    private fun repository(answer: (Call) -> String): AssetRepositoryImpl {
        val engine = MockEngine { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                ?.takeIf { it.isNotBlank() }
                ?.let { Json.parseToJsonElement(it).jsonObject }
            val call = Call(request.method, request.url.host, request.url.encodedPath, request.url.encodedQuery, body)
            calls += call
            respond(answer(call), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AssetRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ Factory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    private val line = AssetLine(lineItemId = "l1", poId = "p1", assetId = "a1")

    @Test
    fun `a known record is read by its id`() = runTest {
        val repo = repository { storedRecord }

        val record = (repo.record(line) as ZillitResult.Success).data!!

        assertEquals(1, calls.size)
        assertTrue(calls.single().path.endsWith("/purchase-orders/asset-register/a1"))
        assertEquals("On the truck", record.comments)
        assertEquals(AssetCategory.Keep, record.category)
        assertEquals("dolly.jpg", record.attachments.single().name)
    }

    @Test
    fun `an unknown record is discovered by line, then read in full`() = runTest {
        val repo = repository { call ->
            if (call.path.endsWith("/asset-register")) {
                // The list is SLIM: no note, no attachments — only the id.
                """{"status":1,"data":[{"id":"a1","line_item_id":"l1","category":"Keep"}]}"""
            } else {
                storedRecord
            }
        }

        val result = repo.record(line.copy(assetId = null))

        assertEquals(listOf("line_item_id=l1", ""), calls.map { it.query })
        assertTrue(calls[1].path.endsWith("/asset-register/a1"), "the full row, never the slim one")
        val record = (result as ZillitResult.Success).data
        assertEquals("On the truck", record?.comments)
    }

    @Test
    fun `no record yet is null, not an error`() = runTest {
        val repo = repository { """{"status":1,"data":[]}""" }
        val result = repo.record(line.copy(assetId = null))
        assertNull((result as ZillitResult.Success).data)
        assertEquals(1, calls.size)
    }

    @Test
    fun `the first write is one POST carrying everything`() = runTest {
        val repo = repository { storedRecord }
        val upload = AssetAttachment(
            media = "asset-register/x/shelf.png",
            bucket = "zillit-eu",
            region = "eu-west-2",
            name = "shelf.png",
            contentType = "image",
            contentSubtype = "png",
        )

        repo.create(line.copy(assetId = null), AssetCategory.Sell, "Wrap sale", listOf(upload))

        val sent = calls.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertTrue(sent.path.endsWith("/purchase-orders/asset-register"))
        val body = sent.body!!
        assertEquals("purchase_order", body["entity"]?.jsonPrimitive?.content)
        assertEquals("p1", body["po_id"]?.jsonPrimitive?.content)
        assertEquals("l1", body["line_item_id"]?.jsonPrimitive?.content)
        assertEquals("Sell", body["category"]?.jsonPrimitive?.content)
        assertEquals("Wrap sale", body["comments"]?.jsonPrimitive?.content)
        val attachment = (body["attachments"] as JsonArray).single().jsonObject
        assertEquals("asset-register/x/shelf.png", attachment["media"]?.jsonPrimitive?.content)
        assertEquals("image", attachment["content_type"]?.jsonPrimitive?.content)
        assertEquals("png", attachment["content_subtype"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a later write re-sends the stored objects as the server gave them`() = runTest {
        val repo = repository { storedRecord }
        val stored = (repo.record(line) as ZillitResult.Success).data!!.attachments.single()
        calls.clear()

        repo.update("a1", AssetCategory.None, listOf(stored))

        val sent = calls.single()
        assertEquals(HttpMethod.Patch, sent.method)
        assertTrue(sent.path.endsWith("/asset-register/a1"))
        assertEquals("", sent.body!!["category"]?.jsonPrimitive?.content, "an empty category clears it")
        assertTrue("comments" !in sent.body.keys, "PATCH /:id drops comments; it is never sent there")
        val attachment = (sent.body["attachments"] as JsonArray).single().jsonObject
        assertEquals(
            "u7",
            attachment["uploaded_by"]?.jsonPrimitive?.content,
            "a field this client does not model survives",
        )
    }

    @Test
    fun `the note writes only through its own route`() = runTest {
        val repo = repository { storedRecord }

        repo.updateComment("a1", "")

        val sent = calls.single()
        assertEquals(HttpMethod.Patch, sent.method)
        assertTrue(sent.path.endsWith("/asset-register/a1/comment"))
        assertEquals(setOf("comments"), sent.body!!.keys)
    }

    @Test
    fun `a 200 answering status 0 is a refusal`() = runTest {
        val repo = repository { """{"status":0,"message":"Line item is not eligible"}""" }
        val result = repo.update("a1", AssetCategory.Keep, emptyList())
        val failure = assertIs<ZillitResult.Failure>(result)
        assertTrue(failure.error.userMessage.contains("not eligible"))
    }

    @Test
    fun `lines and vendors read from bare arrays`() = runTest {
        val repo = repository { call ->
            when {
                call.path.endsWith("/line-items") ->
                    """{"status":1,"data":[{"id":"l1","description":"Dolly","po_vendor_id":"v1"}]}"""
                call.path.endsWith("/vendors") ->
                    """{"status":1,"data":[{"_id":"v1","name":"Grip Hire Ltd"},{"id":"v2","company_name":"Arri"},
                        {"id":"v3"}]}"""
                else -> """{"status":1,"data":[]}"""
            }
        }

        assertEquals("Dolly", (repo.lines() as ZillitResult.Success).data.single().description)
        assertTrue(calls.single().path.endsWith("/purchase-orders/line-items"))
        val vendors = (repo.vendors() as ZillitResult.Success).data
        assertEquals(
            mapOf("v1" to "Grip Hire Ltd", "v2" to "Arri"),
            vendors,
            "a vendor with no name is not listed as its id",
        )
        assertTrue(calls.last().path.endsWith("/vendors"))
        assertEquals("accounthub.test", calls.last().host, "vendors live on the account hub's host")
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }
}
