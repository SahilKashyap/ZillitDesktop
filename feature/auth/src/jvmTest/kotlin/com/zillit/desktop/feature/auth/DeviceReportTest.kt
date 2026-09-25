package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore
import com.zillit.desktop.feature.auth.data.AuthRepositoryImpl
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.DeviceReport
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PUT device`: the desktop describes itself to the server as the phones do
 * on every launch. It never did, so the backend's record of a Mac said
 * whatever it was created with — "Android" in the user's report (2026-09-24).
 */
class DeviceReportTest {

    private val sent = mutableListOf<HttpRequestData>()

    private val engine = MockEngine { request ->
        sent += request
        respond(
            """{"status":1,"message":"ok","data":{}}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private val report = DeviceReport(
        name = "Sahil's MacBook Pro",
        type = "desktop",
        osVersion = "macOS 26.5.1",
        appVersion = "1.0.6",
    )

    private fun repository(scope: kotlinx.coroutines.CoroutineScope? = null) = AuthRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ Factory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        secureStore = MemoryStore(),
        config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        ),
        deviceReport = { report },
        reportScope = scope,
    )

    @Test
    fun `the report is the phones' device update, with this machine's details`() = runTest {
        repository().reportDevice()

        val request = sent.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v2/device", request.url.encodedPath)
        val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
        assertEquals(
            mapOf(
                "device_name" to "Sahil's MacBook Pro",
                "device_type" to "desktop",
                "os_version" to "macOS 26.5.1",
                "app_version" to "1.0.6",
            ),
            body.mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `a linked device reports itself once a launch, after its id reaches the headers`() = runTest {
        val auth = repository(scope = backgroundScope)
        val identity = DeviceIdentity(deviceId = "device-1", email = "sahil@zillit.com", isPrimary = false)

        assertEquals(ZillitResult.Success(Unit), auth.rememberDevice(identity))
        auth.rememberDevice(identity)
        testScheduler.runCurrent()
        // Background work: wait for the one request rather than for idle.
        repeat(50) { if (sent.isEmpty()) Thread.sleep(10) }

        assertEquals(1, sent.count { it.method == HttpMethod.Put })
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private class MemoryStore : SecureStore {
        private val entries = mutableMapOf<SecureKey, ByteArray>()
        override suspend fun get(key: SecureKey): ZillitResult<ByteArray?> = ZillitResult.Success(entries[key])
        override suspend fun put(key: SecureKey, value: ByteArray): ZillitResult<Unit> {
            entries[key] = value
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(key: SecureKey): ZillitResult<Unit> {
            entries.remove(key)
            return ZillitResult.Success(Unit)
        }

        override suspend fun clear(): ZillitResult<Unit> {
            entries.clear()
            return ZillitResult.Success(Unit)
        }

        override suspend fun isAvailable(): Boolean = true
    }
}
