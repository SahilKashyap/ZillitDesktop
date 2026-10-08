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
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSize
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.ZipRecipient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /documents/watermark-merged`: the body the server expects (the web's
 * `serverMerge`), and what comes back being a PDF or an error.
 */
class MergeWireTest {

    private class Transfer(var answer: ZillitResult<ByteArray>) : DocDistTransfer {
        var url: String? = null
        var body: JsonObject? = null
        override suspend fun postBytes(url: String, body: JsonObject): ZillitResult<ByteArray> {
            this.url = url
            this.body = body
            return answer
        }
    }

    private fun repo(transfer: Transfer) = DocDistRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ Factory(MockEngine { respond("{}") }) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        ),
        transfer = transfer,
    )

    private val pdf = "%PDF-1.7 body".encodeToByteArray()

    @Test
    fun `a plain merge names the order and sends no recipients or style`() = runTest {
        val transfer = Transfer(ZillitResult.Success(pdf))

        val answer = repo(transfer).mergedPdf(listOf("d1", "d2", "d1"), recipients = null, style = WatermarkStyle())

        assertEquals(pdf.toList(), assertIs<ZillitResult.Success<ByteArray>>(answer).data.toList())
        assertTrue(transfer.url.orEmpty().endsWith("/api/v2/document-distribution/documents/watermark-merged"))
        val body = transfer.body!!
        // Distinct, in order — a document is merged once however it was listed.
        assertEquals(listOf("d1", "d2"), body["documentIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Stated rather than assumed: the server has no reason to default to the order the dialog promised.
        assertEquals("recipient-major", body["order"]!!.jsonPrimitive.content)
        assertNull(body["recipients"])
        assertNull(body["style"], "a plain merge has nothing to stamp")
    }

    @Test
    fun `a crew merge sends each recipient with their stamp text`() = runTest {
        val transfer = Transfer(ZillitResult.Success(pdf))

        repo(transfer).mergedPdf(
            documentIds = listOf("d1"),
            recipients = listOf(ZipRecipient("Rory", "rory@x.com", "Rory"), ZipRecipient("", "a@b.com", "a@b.com")),
            style = WatermarkStyle(size = WatermarkSize.Small, color = "#dc2626", opacity = 0.25),
        )

        val recipients = transfer.body!!["recipients"]!!.jsonArray
        assertEquals(2, recipients.size)
        val first = recipients[0].jsonObject
        assertEquals("Rory", first["name"]!!.jsonPrimitive.content)
        assertEquals("rory@x.com", first["email"]!!.jsonPrimitive.content)
        assertEquals("Rory", first["watermarkText"]!!.jsonPrimitive.content)
        val style = transfer.body!!["style"]!!.jsonObject
        // A NAME, never a number — the backend rejects a numeric size.
        assertEquals("small", style["size"]!!.jsonPrimitive.content)
        assertEquals("#dc2626", style["color"]!!.jsonPrimitive.content)
        assertEquals(0.25, style["opacity"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `style is left out when the project's settings have not loaded`() = runTest {
        val transfer = Transfer(ZillitResult.Success(pdf))

        repo(transfer).mergedPdf(listOf("d1"), listOf(ZipRecipient("Rory", "rory@x.com", "Rory")), style = null)

        // The server fills what it is NOT sent from the saved settings; the built-in
        // look would override them with a placeholder.
        assertNull(transfer.body!!["style"])
        assertTrue(transfer.body!!["recipients"] is JsonArray)
    }

    @Test
    fun `a 200 that is not a pdf is a failure, not a corrupt download`() = runTest {
        val transfer = Transfer(ZillitResult.Success("{\"status\":0}".encodeToByteArray()))

        val answer = repo(transfer).mergedPdf(listOf("d1"), null, null)

        assertIs<ZillitResult.Failure>(answer)
    }

    @Test
    fun `a gateway timeout reads as too large`() = runTest {
        val transfer = Transfer(ZillitResult.Failure(ZillitError.Timeout("raw call")))

        val answer = assertIs<ZillitResult.Failure>(repo(transfer).mergedPdf(listOf("d1"), null, null))

        assertIs<ZillitError.Validation>(answer.error)
        assertTrue("too large" in answer.error.userMessage.lowercase(), answer.error.userMessage)
    }

    @Test
    fun `the server's own refusal passes through to be translated`() = runTest {
        val refusal = ZillitError.Http(status = 400, serverMessage = "merged_pdf_too_large")
        val transfer = Transfer(ZillitResult.Failure(refusal))

        val answer = assertIs<ZillitResult.Failure>(repo(transfer).mergedPdf(listOf("d1"), null, null))

        assertEquals(refusal, answer.error)
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }
}
