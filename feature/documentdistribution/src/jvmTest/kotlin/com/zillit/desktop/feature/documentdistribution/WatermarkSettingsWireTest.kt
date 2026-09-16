package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.documentdistribution.data.DocDistRepositoryImpl
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettings
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettingsPatch
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSize
import com.zillit.desktop.core.network.HttpClientFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `/api/v2/document-distribution/watermark-settings` as the v2 spec states
 * it: `GET` answers the settings object, `PUT` takes only the fields being
 * changed and answers the object after the save, and the socket carries the
 * whole object with the saver's device so that device can drop its echo.
 */
class WatermarkSettingsWireTest {

    private data class Seen(val method: HttpMethod, val path: String, val body: JsonObject?)

    private fun repository(
        answer: String = SAVED,
        selfDeviceId: String? = null,
        bus: SocketEventBus? = null,
    ): Pair<DocDistRepositoryImpl, MutableList<Seen>> {
        val seen = mutableListOf<Seen>()
        val engine = MockEngine { request: HttpRequestData ->
            seen += Seen(
                request.method,
                request.url.encodedPath,
                (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject },
            )
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = DocDistRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ MockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            bus = bus,
            selfDeviceId = { selfDeviceId },
        )
        return repo to seen
    }

    @Test
    fun `an unsaved project reads the built-in defaults`() = runTest {
        val (repo, seen) = repository(answer = DEFAULTS)

        val answer = repo.watermarkSettings()
        val settings = assertIs<ZillitResult.Success<WatermarkSettings>>(answer, answer.toString()).data

        assertEquals("/api/v2/document-distribution/watermark-settings", seen.single().path)
        assertEquals(HttpMethod.Get, seen.single().method)
        assertEquals(WatermarkSettings.BuiltIn, settings, "the server's defaults are the client's")
        assertTrue(settings.isDefault)
    }

    @Test
    fun `a saved project reads the saved appearance and who saved it`() = runTest {
        val (repo, _) = repository(answer = SAVED)

        val settings = assertIs<ZillitResult.Success<WatermarkSettings>>(repo.watermarkSettings()).data

        assertEquals(WatermarkSize.Small, settings.size)
        assertEquals("#dc2626", settings.color, "held in the swatches' spelling")
        assertEquals(0.25, settings.opacity)
        assertEquals(false, settings.isDefault)
        assertEquals("u2", settings.updatedBy)
        assertEquals(1757900000000L, settings.updated)
    }

    /** A partial update: an absent key keeps the server's value, so only the change travels. */
    @Test
    fun `a save sends only the fields in the patch`() = runTest {
        val (repo, seen) = repository()

        val echo = repo.updateWatermarkSettings(WatermarkSettingsPatch(opacity = 0.25))

        val put = seen.single()
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("/api/v2/document-distribution/watermark-settings", put.path)
        assertEquals(setOf("opacity"), put.body?.keys, "size and colour stay the server's")
        assertEquals("0.25", put.body?.get("opacity").toString())
        assertEquals(WatermarkSize.Small, assertIs<ZillitResult.Success<WatermarkSettings>>(echo).data.size)
    }

    @Test
    fun `size goes on the wire by its lowercase name`() = runTest {
        val (repo, seen) = repository()

        repo.updateWatermarkSettings(WatermarkSettingsPatch(size = WatermarkSize.Medium, color = "#dc2626"))

        assertEquals("\"medium\"", seen.single().body?.get("size").toString())
        assertEquals("\"#dc2626\"", seen.single().body?.get("color").toString())
    }

    // -- the socket ----------------------------------------------------------

    private class StubSocket : SocketClient {
        val frames = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 8)
        override val connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
        override val messages: Flow<SocketMessage> = frames
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success(Unit)
        override suspend fun emit(event: SocketEventName) = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Unknown("unused"))
    }

    private fun frame(deviceId: String?, settings: String? = SAVED_OBJECT): SocketMessage = SocketMessage(
        SocketEventName("document_distribution:watermark_settings:updated"),
        Json.parseToJsonElement(
            buildString {
                append("""{"project_id":"p1"""")
                if (deviceId != null) append(""","device_id":"$deviceId"""")
                if (settings != null) append(""","watermark_settings":$settings""")
                append("}")
            },
        ),
    )

    /**
     * The saver's own device gets the event too — its PUT already answered
     * the same object — while the saver's *other* devices and everyone else
     * apply it. A frame without the object is dropped rather than read as
     * the built-in values, which would undo a real save.
     */
    @Test
    fun `another device's save arrives, this device's echo and a bare frame do not`() = runTest {
        val socket = StubSocket()
        val (repo, _) = repository(selfDeviceId = "dev-me", bus = SocketEventBus(socket))
        val received = mutableListOf<WatermarkSettings>()
        val collector = launch { repo.watermarkSettingsUpdates.collect { received += it } }
        testScheduler.runCurrent()

        socket.frames.emit(frame(deviceId = "dev-me"))
        socket.frames.emit(frame(deviceId = "dev-other"))
        socket.frames.emit(frame(deviceId = "dev-other", settings = null))
        socket.frames.emit(frame(deviceId = null))
        testScheduler.runCurrent()
        collector.cancel()

        assertEquals(2, received.size, "the other device's save and the device-less frame")
        assertEquals(WatermarkSize.Small, received.first().size)
        assertEquals("#dc2626", received.first().color)
    }

    @Test
    fun `without a bus the updates flow is empty`() = runTest {
        val (repo, _) = repository(bus = null)

        assertEquals(emptyList<WatermarkSettings>(), repo.watermarkSettingsUpdates.toList())
    }

    private companion object {
        const val DEFAULTS = """{"status":1,"message":"watermark_settings_fetched_successfully","data":{
            "size":"large","color":"#6B7280","opacity":0.4,"is_default":true,"updated_by":null,"updated":0}}"""
        const val SAVED_OBJECT = """{"size":"small","color":"#DC2626","opacity":0.25,"is_default":false,
            "updated_by":"u2","updated":1757900000000}"""
        const val SAVED = """{"status":1,"message":"watermark_settings_updated_successfully","data":$SAVED_OBJECT}"""
    }
}

/** The shared client, with its JSON plumbing, over a canned engine. */
private class MockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
