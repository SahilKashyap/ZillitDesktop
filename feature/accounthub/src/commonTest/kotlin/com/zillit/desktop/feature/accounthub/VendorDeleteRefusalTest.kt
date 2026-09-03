package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A vendor a purchase order still names cannot be deleted — and the server says
 * so with a 200 (ZL-21088). The transport succeeded; the envelope refused.
 */
class VendorDeleteRefusalTest {

    @Test
    fun `a status zero delete is a failure, not a deletion`() = runTest {
        val result = repository("""{"status":0,"message":"vendor_in_use_by_purchase_orders"}""")
            .deleteVendor("v-1")

        val refusal = (result as? ZillitResult.Failure)?.error as? ZillitError.Http
        assertTrue(refusal != null, "a refusal must not read as success")
        assertEquals("vendor_in_use_by_purchase_orders", refusal.serverMessage)
    }

    @Test
    fun `an accepted delete still succeeds`() = runTest {
        val result = repository("""{"status":1,"message":"vendor_deleted"}""").deleteVendor("v-1")

        assertTrue(result is ZillitResult.Success)
    }

    private fun repository(body: String): AccountHubRepositoryImpl {
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ MockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }
}

private class MockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
