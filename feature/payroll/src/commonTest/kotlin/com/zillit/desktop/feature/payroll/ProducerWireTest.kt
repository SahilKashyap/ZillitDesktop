package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.payroll.data.PayrollBinaryTransport
import com.zillit.desktop.feature.payroll.data.payrollProducerSeams
import com.zillit.desktop.feature.payroll.domain.PayrollExportFile
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The URLs the producer surfaces call.
 *
 * Pinned because the web writes some of them with a DIFFERENT prefix: its
 * `/v2/production-reports/…` hides the `/api` inside its own base-URL
 * variable, and copying that spelling literally gives a 404 the source
 * swallows — every fill would say "no report data" with nothing to say why.
 * Probed against develop on 2026-09-27: bare `/v2` answers 404, `/api/v2`
 * answers 401.
 */
class ProducerWireTest {

    @Test
    fun `the production report is read on api v2, not the web's bare v2`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(
                """{"status":1,"data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val seams = payrollProducerSeams(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ProducerMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = CONFIG,
            transport = NoBundles,
            scriptHost = null,
        )
        seams.reports?.week(WEEK, "u1")
        seams.reports?.day("2026-05-04", WEEK, "u1")

        val report = urls.filter { "production-reports" in it }
        assertTrue(report.isNotEmpty(), "the production report was never read")
        assertTrue(
            report.all { "/api/v2/production-reports/day-times" in it },
            "production report must be read on /api/v2: $report",
        )
        // The wrap report is the AD dashboard's, on its own host and prefix.
        val wrap = urls.filter { "ad-shoot-days" in it }
        assertTrue(wrap.all { "/api/v2/ad-shoot-days/day-details" in it }, "wrap report path: $wrap")
        // Both sides are scoped to the crew member: without it the service
        // resolves the ACCOUNTANT's unit's report for everybody.
        assertTrue(
            (report + wrap).all { "user_id=u1" in it },
            "every report read is scoped to the crew: ${report + wrap}",
        )
    }

    @Test
    fun `a week is read as one range call, and a day as one dated call`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(
                """{"status":1,"data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val seams = payrollProducerSeams(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ProducerMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = CONFIG,
            transport = NoBundles,
            scriptHost = null,
        )
        seams.reports?.week(WEEK, "u1")
        // One round trip per source, not one per day of the week.
        assertTrue(urls.any { "day-times/range" in it && "start=2026-05-04" in it && "end=2026-05-10" in it }, "$urls")
        assertTrue(urls.any { "ad-shoot-days/day-details" in it && "week_start=$WEEK" in it }, "$urls")
        assertTrue(urls.size == 2, "a week should be two reads, was ${urls.size}: $urls")
    }

    @Test
    fun `the active deal is read from the deal-memo service`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(
                """{"status":1,"data":{}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val seams = payrollProducerSeams(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ProducerMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = CONFIG,
            transport = NoBundles,
            scriptHost = null,
        )
        seams.deals?.activeDeal("u1")
        assertTrue(urls.single().endsWith("/api/v2/deal-memo/deals/active/u1"), urls.toString())
    }

    private companion object {
        /** Monday 2026-05-04 00:00 UTC. */
        const val WEEK = 1_777_852_800_000L

        val CONFIG = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        )
    }
}

/** No engine bundle is fetched in these tests; only the report URLs are under test. */
private object NoBundles : PayrollBinaryTransport {
    override suspend fun post(url: String, body: JsonObject) = ZillitResult.Success(ByteArray(0))
    override suspend fun get(url: String) = ZillitResult.Success(ByteArray(0))
    override suspend fun postForFile(url: String, body: JsonObject, requestedFormat: String) =
        ZillitResult.Success(PayrollExportFile(ByteArray(0), requestedFormat))
}

private class ProducerMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
