package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.calendar.CalendarRepositoryImpl
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The events read walks three query shapes because the server has been seen
 * refusing some of them. What it must not walk is a verdict about who is
 * asking: every shape gets the same answer, so the extra two are the same
 * question refused twice more — and in token mode each of them used to spend
 * a device-session rotation on the way. One production log caught all three
 * variants refused inside 0.9 seconds with three rotations between them
 * (2026-10-21 04:23:34–35), for a calendar that was empty either way.
 */
class CalendarRefusalTest {

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun repository(engine: MockEngine) = CalendarRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ Factory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = config,
    )

    @Test
    fun `a refused credential stops the walk at the first shape`() = runTest {
        val engine = MockEngine { respond(REFUSED, HttpStatusCode.Unauthorized, JSON) }

        val outcome = repository(engine).events(FROM, TO)

        assertEquals(1, engine.requestHistory.size, "the same question is not asked two more times")
        val error = (outcome as ZillitResult.Failure).error
        assertIs<ZillitError.Unauthorized>(error)
        assertEquals("libs_invalid_token_scope", error.technical, "the server's own key, reported as it came")
    }

    /** And a shape the server really cannot read still falls through to the next one. */
    @Test
    fun `a shape the server cannot read still falls back`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.parameters.contains("start_date")) {
                respond(CAST_FAILED, HttpStatusCode.InternalServerError, JSON)
            } else {
                respond(NO_EVENTS, HttpStatusCode.OK, JSON)
            }
        }

        val outcome = repository(engine).events(FROM, TO)

        assertTrue(outcome.isSuccess, "$outcome")
        assertEquals(2, engine.requestHistory.size, "the first shape was refused, the second answered")
    }

    private companion object {
        const val FROM = 1_792_000_000_000L
        const val TO = 1_792_600_000_000L
        const val REFUSED = """{"status":0,"message":"libs_invalid_token_scope"}"""
        /** The 500 the windowed shape has really answered, and the reason the walk exists. */
        const val CAST_FAILED =
            """{"status":0,"message":"Cast to Number failed at path \"end_datetime\""}"""
        const val NO_EVENTS = """{"status":1,"message":"ok","data":[]}"""
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
