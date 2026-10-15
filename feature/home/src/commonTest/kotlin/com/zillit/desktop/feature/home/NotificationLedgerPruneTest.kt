package com.zillit.desktop.feature.home

import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.InMemoryNotificationLedgerStore
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.NotificationLedgerSeeder
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The first seed of a newly installed version, which is the only seed allowed
 * to take rows away.
 *
 * A resync refreshes what the server still has and can never clear what it has
 * forgotten — a `badges:cleared:device` frame emitted while the app was closed
 * reaches nobody, and the seed that follows only ever adds. So the badge sat on
 * the rail with no screen able to clear it. The prune is the answer, and the
 * whole of its licence is that the listing is contiguous by `updated`: inside
 * the span its page covers, the page is the server's entire answer.
 */
class NotificationLedgerPruneTest {

    private val project = "p1"

    @Test
    fun `the first seed of a new version retires what the listing no longer serves`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        // Two rows this device was told about long ago, one of which the server
        // has since dropped without telling anyone.
        store.seed(listOf(record("stale", updated = 1_500L), record("live", updated = 1_600L)))
        assertEquals(2, store.counts.value.total)

        var pruned = false
        val asked = mutableListOf<String>()
        val seeder = seeder(
            store = store,
            // A listing with one row left in it: the page from the top carries
            // `live`, and the walk back past it runs out — which is what
            // entitles the prune to judge everything older.
            answer = { request ->
                asked += request.url.encodedPath
                if (asked.size == 1) page(row("live", updated = 1_600L)) else page()
            },
            pruneDue = { true },
            onPruned = { pruned = true },
        )

        val result = seeder.seed(project)

        assertTrue(result is ZillitResult.Success, "the page did not land: $result")
        assertTrue(asked.first().endsWith("/previous"), "a prune asks from the top: ${asked.first()}")
        assertEquals(2, asked.size, "and walks back until the listing runs out")
        assertNotNull(storage.row("live"), "the page's row did not parse, so nothing could be judged")
        assertTrue(pruned, "this version is recorded as pruned")
        assertNull(storage.row("stale"), "the row no page carried is gone from disk")
        assertEquals(1, store.counts.value.total, "and gone from the counts")
    }

    /**
     * An empty answer proves no span, so it judges nothing — and leaves the
     * version due, because the next seed (a reconnect, the minute tick) may
     * get a page.
     */
    @Test
    fun `an empty page prunes nothing and leaves the version due`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(record("mine", updated = 1_500L)))

        var pruned = false
        val seeder = seeder(
            store = store,
            answer = { page() },
            pruneDue = { true },
            onPruned = { pruned = true },
        )

        seeder.seed(project)

        assertEquals(1, store.counts.value.total, "a page that said nothing took nothing")
        assertNotNull(storage.row("mine"))
        assertTrue(!pruned, "still due, so the next seed tries again")
    }

    /**
     * The walk stops where the listing stops being contiguous with what we
     * hold: a row older than everything the walk reached is not judged, so a
     * server that pages slowly costs badges nothing.
     */
    @Test
    fun `a prune judges only as far back as it actually walked`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(record("ancient", updated = 10L), record("recent", updated = 1_500L)))

        var pages = 0
        val seeder = seeder(
            store = store,
            // Every ask answers with the same single row: no new ids, so the
            // walk gives up rather than loop, having covered only 1601 and up.
            answer = {
                pages++
                page(row("live", updated = 1_600L))
            },
            pruneDue = { true },
        )

        seeder.seed(project)

        assertTrue(pages >= 2, "it tried to walk back")
        assertNotNull(storage.row("ancient"), "nothing is concluded below the span")
        assertNotNull(storage.row("recent"), "nor for the row just under its edge")
    }

    /** Without the gate, a seed is additive — the rule the rest of the app relies on. */
    @Test
    fun `an ordinary seed never takes a row away`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(record("stale", updated = 1_500L)))

        seeder(store = store, answer = { page(row("other", updated = 1_600L)) }).seed(project)

        assertEquals(2, store.counts.value.total, "the page's row joined ours; nothing was judged")
        assertNotNull(storage.row("stale"))
    }

    private fun seeder(
        store: BadgeStore,
        answer: (HttpRequestData) -> String,
        pruneDue: suspend () -> Boolean = { false },
        onPruned: suspend () -> Unit = {},
    ) = NotificationLedgerSeeder(
        apiClient = client(answer),
        config = config(),
        store = store,
        nowMillis = { NOW },
        resyncDue = pruneDue,
        pruneDue = pruneDue,
        onPruned = onPruned,
    )

    private fun record(id: String, updated: Long) = NotificationRecord(
        id = id,
        projectId = project,
        section = "tools_label",
        tool = "sides_label",
        updated = updated,
        created = updated,
        fromApi = true,
        raw = """{"notification_uuid":"$id"}""",
    )

    private fun row(id: String, updated: Long) = """
        {"notification_uuid":"$id","project_id":"$project","section":"tools_label",
         "tool":"sides_label","unit":"","created":$updated,"updated":$updated,"message_read":false}
    """.trimIndent()

    /** The notification service's envelope around a listing page. */
    private fun page(vararg rows: String): String =
        """{"status":1,"message":"ok","messageElements":[],"data":[${rows.joinToString(",")}]}"""

    private fun client(answer: (HttpRequestData) -> String): ApiClient {
        val engine = MockEngine { request ->
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return ApiClient(
            httpClient = HttpClientFactory.create({ ScopeMockEngineFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
    }

    private fun config() = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private companion object {
        /** Well after every row's `updated`, so the span's upper bound is not what excludes them. */
        const val NOW = 9_000L
    }
}

private class ScopeMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
