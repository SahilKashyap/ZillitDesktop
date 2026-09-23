package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.ClosingReport
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * Closing a period, driven through the console's own view model over a mock
 * engine — the web's `PeriodCloseModule`.
 *
 * The act is irreversible across every source module, so what matters here is
 * what the page proposes, what it sends, and what it says afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodCloseFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** What the POST carried, so a test can check `as_of` reached the server. */
    private val posted = mutableListOf<String>()

    private fun engine(lockedThrough: String, closeAnswer: String) = MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/lock-period") && request.method == HttpMethod.Post -> {
                posted += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                closeAnswer
            }
            path.endsWith("/lock-period") -> """{"status":1,"data":{"value":{"lockedDate":"$lockedThrough"}}}"""
            else -> """{"status":1,"data":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun viewModel(engine: MockEngine, today: Long): AccountHubViewModel {
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ LockMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return AccountHubViewModel(repository = repository, viewer = { accountant() }, clock = { today })
    }

    private fun accountant() = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = AccountHubViewer.TOOL_IDENTIFIER,
                    enabled = true,
                    canView = true,
                    canPost = true,
                    canDownload = true,
                ),
            ),
        ),
        "u1",
        isAccountant = true,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.opened(model: AccountHubViewModel): AccountHubViewModel {
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.PeriodClose))
        settle {
            val close = model.state.value.periodClose
            !close.loading && close.lock.lockedThrough.isNotBlank()
        }
        return model
    }

    private fun ms(iso: String): Long = IsoDate.toEpochMillis(iso)!!

    /**
     * The picker opens on the first date that can be closed — the day after
     * the lock when the lock is ahead of today, today otherwise. The web's
     * `lockedDefaultDateInput`: a "today" default behind the lock would be
     * unpickable and refused on post.
     */
    @Test
    fun `the picker opens on the day after a lock that is ahead of today`() = runTest(dispatcher) {
        val model = opened(viewModel(engine("2026-09-10", ""), today = ms("2026-09-01")))
        assertEquals("2026-09-11", model.state.value.periodClose.closeDateText)
    }

    @Test
    fun `the picker opens on today when the lock is behind it`() = runTest(dispatcher) {
        val model = opened(viewModel(engine("2026-08-30", ""), today = ms("2026-09-12")))
        assertEquals("2026-09-12", model.state.value.periodClose.closeDateText)
    }

    /**
     * Proposing only asks; confirming sends. The date goes as `as_of` epoch
     * millis inside the chosen UTC day, so the server resolves the week the
     * accountant picked whatever the machine's timezone.
     */
    @Test
    fun `a confirmed close sends the date and shows the new lock in words`() = runTest(dispatcher) {
        val model = opened(
            viewModel(
                engine("2026-08-30", """{"status":1,"data":{"value":{"last_cr_locked_date":"2026-09-06"}}}"""),
                today = ms("2026-09-01"),
            ),
        )
        val chosen = ms("2026-09-06")
        model.onEvent(AccountHubEvent.ProposePeriodClose(chosen))
        advanceUntilIdle()
        assertTrue(posted.isEmpty(), "proposing must not send — the act has no undo")
        assertNotNull(model.state.value.periodClose.pendingCloseMillis)

        model.onEvent(AccountHubEvent.ConfirmPeriodClose)
        settle { !model.state.value.periodClose.closing && model.state.value.periodClose.result != null }

        val asOf = Regex("\"as_of\":(\\d+)").find(posted.single())?.groupValues?.get(1)?.toLong()
        assertNotNull(asOf)
        assertTrue(asOf in chosen until chosen + 86_400_000L, "as_of stays inside the chosen UTC day")

        val close = model.state.value.periodClose
        assertEquals("2026-09-06", close.lock.lockedThrough)
        assertNull(close.pendingCloseMillis)
        val result = assertNotNull(close.result)
        assertTrue(result.ok)
        // Words, like the card above it — never the raw YYYY-MM-DD.
        assertFalse(result.message.contains("2026-09-06"), result.message)
        assertTrue(result.message.startsWith("Period closed."), result.message)
        // The picker moves on to the next closable day.
        assertEquals("2026-09-07", close.closeDateText)
    }

    /** A refusal — the usual one is unposted work — keeps the lock where it was and says why. */
    @Test
    fun `a refused close keeps the lock and shows the server's reason`() = runTest(dispatcher) {
        val model = opened(
            viewModel(
                engine("2026-08-30", """{"status":0,"message":"unposted transactions in period","data":null}"""),
                today = ms("2026-09-01"),
            ),
        )
        model.onEvent(AccountHubEvent.ProposePeriodClose(ms("2026-09-06")))
        model.onEvent(AccountHubEvent.ConfirmPeriodClose)
        settle { !model.state.value.periodClose.closing && model.state.value.periodClose.result != null }

        val close = model.state.value.periodClose
        assertEquals("2026-08-30", close.lock.lockedThrough)
        val result = assertNotNull(close.result)
        assertFalse(result.ok)
        // A `status: 0` over a 200 carries the only explanation there is.
        assertTrue(result.message.contains("unposted"), result.message)
    }

    /**
     * A reply that names no date still closed the period. The web falls back to
     * the chosen day (`… || target`); a blank lock here read "No period locked
     * yet" straight after a close and reset the picker's minimum.
     */
    @Test
    fun `a close answered without a date keeps the chosen day as the lock`() = runTest(dispatcher) {
        val model = opened(viewModel(engine("2026-08-30", """{"status":1,"data":{}}"""), today = ms("2026-09-01")))
        model.onEvent(AccountHubEvent.ProposePeriodClose(ms("2026-09-06") + 86_400_000L - 1))
        model.onEvent(AccountHubEvent.ConfirmPeriodClose)
        settle { !model.state.value.periodClose.closing && model.state.value.periodClose.result != null }

        assertEquals("2026-09-06", model.state.value.periodClose.lock.lockedThrough)
    }

    /**
     * A sent package is cleared, as the web does: leaving it in place with
     * Publish still lit e-mailed the same people the same reports on a second
     * click.
     */
    @Test
    fun `a published package is cleared so it cannot be sent twice`() = runTest(dispatcher) {
        val model = opened(viewModel(engine("2026-08-30", ""), today = ms("2026-09-01")))
        val pkg = ClosingPackage(
            id = 1,
            emails = listOf("producer@zillit.com"),
            reports = listOf(ClosingReport.CostReport),
        )
        model.onEvent(AccountHubEvent.EditPackages(listOf(pkg)))
        model.onEvent(AccountHubEvent.PublishPackages)
        settle { model.state.value.periodClose.publish.result != null }

        val publish = model.state.value.periodClose.publish
        assertTrue(assertNotNull(publish.result).ok)
        assertEquals(listOf(ClosingPackage(id = 1)), publish.packages)
        assertTrue(publish.validPackages.isEmpty())
    }
}

private class LockMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
