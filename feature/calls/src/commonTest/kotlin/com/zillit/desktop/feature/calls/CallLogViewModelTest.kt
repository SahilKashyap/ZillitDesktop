package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientEngineProvider
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.CallLogEvent
import com.zillit.desktop.feature.calls.ui.CallLogViewModel
import com.zillit.desktop.feature.calls.ui.matchingCounterpart
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Calls tab's search and its trash, against a recorded HTTP surface.
 *
 * Search is client-side by counterpart name (Android `RecentMissedVM.kt:265-285`);
 * the trash is one bodiless `DELETE` per view, missed first and — on the All
 * view — recent as well (`RecentCallFragment.kt:203-209`, `RecentMissedVM.kt:236-259`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLogViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Every request the view model made: "METHOD /path". */
    private val seen = mutableListOf<String>()

    /** What each `DELETE` answers; a status other than OK is a refusal. */
    private var deleteAnswers: (String) -> HttpStatusCode = { HttpStatusCode.OK }

    private val page = """
        {"status":1,"data":{"calls":[
          {"call_uuid":"c1","outgoingCall":true,"from_user_id":"me","to_user_id":"u-aisha",
           "call_mode":"private","call_type":"audio","start_time":300,"call_duration":5000},
          {"call_uuid":"c2","incomingCall":true,"from_user_id":"u-vivek","to_user_id":"me",
           "call_mode":"private","call_type":"video","start_time":200,"missedCall":true},
          {"call_uuid":"c3","call_mode":"group","chat_room_id":"r1","chat_room_name":"Camera dept",
           "start_time":100,"call_duration":9000}
        ]}}
    """.trimIndent()

    private fun api(): CallApi {
        // A plain mock engine, as `CallStatusPlaneTest` builds one: the
        // provider indirection is for the app's own client, not for a test.
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath}"
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.method.value) {
                "DELETE" -> respond("""{"status":1,"message":"ok"}""", deleteAnswers(request.url.encodedPath), json)
                else -> respond(page, HttpStatusCode.OK, json)
            }
        }
        return CallApi(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ MockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = mapOf(ZillitService.Calling to "https://calls.test"),
                realtime = emptyMap(),
            ),
        )
    }

    private fun viewModel() = CallLogViewModel(
        api = api(),
        selfUserId = { "me" },
        nowMillis = { 1_000L },
        onRedial = {},
    )

    /**
     * Drains the test scheduler *and* the client's own threads.
     *
     * The mock engine answers on a real dispatcher, so virtual time alone can
     * run out before the response lands — `advanceUntilIdle()` returns with
     * the page still in flight. A few real yields between drains let it
     * arrive; [condition] stops the wait as soon as it has.
     */
    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private val names = mapOf("u-aisha" to "Aisha Khan", "u-vivek" to "Vivek Mishra")

    @Test
    fun `search keeps the rows whose counterpart name contains the text`() {
        val rows = listOf(
            entry("c1", peer = "u-aisha"),
            entry("c2", peer = "u-vivek"),
            entry("c3", peer = "", mode = CallMode.Group, title = "Camera dept"),
        )
        assertEquals(rows, rows.matchingCounterpart("", names::get), "no text, no filter")
        assertEquals(listOf("c1"), rows.matchingCounterpart("aish", names::get).map { it.callUuid })
        assertEquals(listOf("c2"), rows.matchingCounterpart("  MISHRA ", names::get).map { it.callUuid })
        assertEquals(listOf("c3"), rows.matchingCounterpart("camera", names::get).map { it.callUuid })
        assertTrue(rows.matchingCounterpart("nobody", names::get).isEmpty())
    }

    @Test
    fun `the query lives in the state and survives the All-Missed switch`() = runTest(dispatcher) {
        val model = viewModel()
        settle { model.currentState.entries.isNotEmpty() }
        model.onEvent(CallLogEvent.Search("ais"))
        model.onEvent(CallLogEvent.ShowMissed)
        settle { !model.currentState.isLoading }
        assertEquals("ais", model.currentState.query)
        assertTrue(model.currentState.missedOnly)
    }

    @Test
    fun `on All, confirming the wipe deletes missed then recent and clears the list`() =
        runTest(dispatcher) {
            val model = viewModel()
            settle { model.currentState.entries.isNotEmpty() }
            assertEquals(3, model.currentState.entries.size, "the page loaded")

            model.onEvent(CallLogEvent.DeleteAll)
            assertTrue(model.currentState.confirmingDelete, "the trash asks first")
            model.onEvent(CallLogEvent.ConfirmDeleteAll)
            settle { !model.currentState.isDeleting && !model.currentState.confirmingDelete }

            assertEquals(
                listOf("DELETE /api/v2/call/missed", "DELETE /api/v2/call/recent"),
                seen.filter { it.startsWith("DELETE") },
            )
            assertTrue(model.currentState.entries.isEmpty())
            assertFalse(model.currentState.canLoadMore)
            assertTrue(model.currentState.deletedAll)
            assertFalse(model.currentState.confirmingDelete)
        }

    @Test
    fun `on Missed, the wipe deletes missed only`() = runTest(dispatcher) {
        val model = viewModel()
        settle { model.currentState.entries.isNotEmpty() }
        model.onEvent(CallLogEvent.ShowMissed)
        settle { !model.currentState.isLoading }

        model.onEvent(CallLogEvent.DeleteAll)
        model.onEvent(CallLogEvent.ConfirmDeleteAll)
        settle { !model.currentState.isDeleting && !model.currentState.confirmingDelete }

        assertEquals(listOf("DELETE /api/v2/call/missed"), seen.filter { it.startsWith("DELETE") })
        assertTrue(model.currentState.entries.isEmpty())
    }

    @Test
    fun `nothing to wipe means nothing to ask`() = runTest(dispatcher) {
        deleteAnswers = { HttpStatusCode.OK }
        val model = viewModel()
        settle { model.currentState.entries.isNotEmpty() }
        model.onEvent(CallLogEvent.DeleteAll)
        model.onEvent(CallLogEvent.ConfirmDeleteAll)
        settle { !model.currentState.isDeleting && !model.currentState.confirmingDelete }
        // Emptied by the first wipe; a second press must not even ask.
        model.onEvent(CallLogEvent.DeleteAll)
        assertFalse(model.currentState.confirmingDelete)
    }

    @Test
    fun `a refused leg keeps the rows, says so, and re-reads the server`() = runTest(dispatcher) {
        deleteAnswers = { path ->
            if (path.endsWith("/recent")) HttpStatusCode.InternalServerError else HttpStatusCode.OK
        }
        val model = viewModel()
        settle { model.currentState.entries.isNotEmpty() }
        val getsBefore = seen.count { it.startsWith("GET") }

        model.onEvent(CallLogEvent.DeleteAll)
        model.onEvent(CallLogEvent.ConfirmDeleteAll)
        settle { !model.currentState.isDeleting && !model.currentState.confirmingDelete }

        assertNotNull(model.currentState.error)
        assertEquals(3, model.currentState.entries.size, "the server's rows stand, not a guess")
        assertFalse(model.currentState.deletedAll)
        assertTrue(seen.count { it.startsWith("GET") } > getsBefore, "the list was re-read")
    }

    @Test
    fun `the info affordance opens and closes the detail`() = runTest(dispatcher) {
        val model = viewModel()
        settle { model.currentState.entries.isNotEmpty() }
        val row = model.currentState.entries.first()
        model.onEvent(CallLogEvent.ShowDetail(row))
        assertEquals(row, model.currentState.detail)
        model.onEvent(CallLogEvent.CloseDetail)
        assertEquals(null, model.currentState.detail)
    }

    private fun entry(
        uuid: String,
        peer: String,
        mode: CallMode = CallMode.Private,
        title: String = "",
    ) = CallLogEntry(
        callUuid = uuid,
        direction = CallLogDirection.Outgoing,
        mode = mode,
        type = CallType.Audio,
        missed = false,
        durationMillis = 0,
        startedAtMillis = 0,
        peerUserId = peer,
        title = title,
    )
}

/** Hands the same mock engine back to the app's client factory. */
private class MockEngineFactory(private val engine: MockEngine) :
    io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit) = engine
}

/** How many drain-and-yield rounds before giving up on the mock engine. */
private const val SETTLE_TRIES = 200

/** Real milliseconds per round — long enough to let a thread hand back. */
private const val SETTLE_STEP_MILLIS = 5L
