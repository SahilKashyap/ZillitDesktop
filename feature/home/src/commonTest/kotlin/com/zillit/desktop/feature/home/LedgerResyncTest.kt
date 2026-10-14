package com.zillit.desktop.feature.home

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.NotificationRecord
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first launch after an update asks for the newest page again instead of
 * what changed since the watermark — and keeps what was read here.
 */
class LedgerResyncTest {

    private val asked = mutableListOf<String>()

    private val page = """{"status":1,"data":[
        {"notification_uuid":"a","project_id":"p1","section":"tools_label","tool":"sides_label",
         "message_read":false,"updated":500},
        {"notification_uuid":"b","project_id":"p1","section":"tools_label","tool":"sides_label",
         "message_read":false,"updated":400}
    ]}"""

    private fun seeder(store: BadgeStore, due: () -> Boolean, onDone: () -> Unit): NotificationLedgerSeeder {
        val engine = MockEngine { request ->
            asked += request.url.encodedPath
            respond(page, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return NotificationLedgerSeeder(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ SeedEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            store = store,
            nowMillis = { 9_000L },
            resyncDue = { due() },
            onResynced = { onDone() },
        )
    }

    private suspend fun ledgerWith(vararg rows: NotificationRecord) = BadgeStore().apply {
        open("p1")
        seed(rows.toList())
    }

    private fun row(id: String, updated: Long) = NotificationRecord(
        id = id, projectId = "p1", section = BadgeSections.TOOLS, tool = "sides_label",
        updated = updated, fromApi = true,
    )

    @Test
    fun `a due resync asks for the newest page, whatever the watermark`() = runTest {
        val store = ledgerWith(row("a", 300))
        store.markRead(LedgerRead.Reference("a"))
        var done = 0

        seeder(store, due = { done == 0 }, onDone = { done++ }).seed("p1")

        assertEquals(listOf("/api/v2/project/all/notifications/9000/previous"), asked)
        assertEquals(1, done)
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS), "the row read here stays read")
    }

    @Test
    fun `once it has landed the seed asks only for what changed`() = runTest {
        val store = ledgerWith(row("a", 300))
        var done = 0

        seeder(store, due = { false }, onDone = { done++ }).seed("p1")

        assertEquals(listOf("/api/v2/project/all/notifications/300/next"), asked)
        assertEquals(0, done)
    }
}

private class SeedEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
