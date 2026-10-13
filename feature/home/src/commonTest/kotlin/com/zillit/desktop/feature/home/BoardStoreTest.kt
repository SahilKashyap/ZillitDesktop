package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeStore
import com.zillit.desktop.feature.home.domain.NoticeWire
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board's offline copy, through the repository.
 *
 * What the store buys over the `cacheAs` row Phase 1 reads: every page that
 * has been read rather than only the last newest window, and a copy that keeps
 * up with the socket instead of going stale the moment a post arrives or — the
 * visible one — the moment a post is deleted.
 */
class BoardStoreTest {

    private val plain = object : NoticeDecryptor {
        override fun decryptFromHex(cipherHex: String): ZillitResult<String> = ZillitResult.Success(cipherHex)
        override fun encryptToHex(plaintext: String): ZillitResult<String> = ZillitResult.Success(plaintext)
    }

    /** A store in a map, so a test can see exactly what was filed. */
    private class RecordingStore : NoticeStore {
        private val units = linkedMapOf<String, MutableMap<String, NoticeWire>>()
        val saves = mutableListOf<List<String>>()

        override suspend fun newest(unitId: String): List<String> = sorted(unitId).map { it.wire }

        override suspend fun save(unitId: String, rows: List<NoticeWire>) {
            saves += rows.map { it.noticeId }
            if (rows.isEmpty()) return
            val unit = units.getOrPut(unitId) { linkedMapOf() }
            rows.forEach { unit[it.noticeId] = it }
        }

        override suspend fun put(unitId: String, row: NoticeWire) {
            units.getOrPut(unitId) { linkedMapOf() }[row.noticeId] = row
        }

        override suspend fun forget(unitId: String?, noticeId: String) {
            if (unitId == null) units.values.forEach { it.remove(noticeId) } else units[unitId]?.remove(noticeId)
        }

        fun ids(unitId: String): List<String> = sorted(unitId).map { it.noticeId }

        private fun sorted(unitId: String) =
            units[unitId]?.values?.sortedByDescending { it.updatedAtMillis }.orEmpty()
    }

    private fun post(id: String, updated: Long, deleted: Boolean = false) =
        """{"_id":"$id","message":"$id","name":"Sam","created":$updated,"updated":$updated""" +
            (if (deleted) ""","deleted":true""" else "") + "}"

    private fun repository(
        store: NoticeStore?,
        rows: String,
        fileCabinet: Boolean = false,
    ): HomeFeedRepositoryImpl {
        val engine = MockEngine {
            respond(
                """{"status":1,"message":"ok","data":[$rows]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HomeFeedRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ StoreEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            decrypt = plain,
            isAdmin = { false },
            nowMillis = { 1_000 },
            fileCabinet = fileCabinet,
            store = store,
        )
    }

    @Test
    fun `reading the newest page files it`() = runTest {
        val store = RecordingStore()

        val read = repository(store, post("n1", 100)).loadNotices("u1", 9_000)

        assertEquals(listOf("n1"), (read as ZillitResult.Success).data.map { it.id })
        assertEquals(listOf("n1"), store.ids("u1"))
    }

    /**
     * The page the `cacheAs` row never held — it keeps one window. Before the
     * store, scrolling back and relaunching started from the newest page again.
     */
    @Test
    fun `an older page is filed too`() = runTest {
        val store = RecordingStore()

        repository(store, post("old", 10)).loadOlderNotices("u1", 50)

        assertEquals(listOf("old"), store.ids("u1"))
    }

    @Test
    fun `a filed page comes back as posts, read by the one parser`() = runTest {
        val store = RecordingStore()
        store.save("u1", listOf(NoticeWire("n1", post("n1", 100), 100)))

        val cached = repository(store, post("ignored", 1)).cachedNotices("u1")

        assertEquals(listOf("n1"), cached?.map { it.id })
        assertEquals("n1", cached?.single()?.body, "the body came back undecrypted")
    }

    @Test
    fun `a board with no store still works, and files nothing`() = runTest {
        val read = repository(store = null, rows = post("n1", 100)).loadNotices("u1", 9_000)

        assertEquals(listOf("n1"), (read as ZillitResult.Success).data.map { it.id })
    }

    /**
     * A row the reader refuses is a row the board would never draw; filing it
     * would only mean drawing it on the next cold open.
     */
    @Test
    fun `a deleted row is not filed`() = runTest {
        val store = RecordingStore()

        repository(store, post("gone", 100, deleted = true)).loadNotices("u1", 9_000)

        assertTrue(store.ids("u1").isEmpty())
    }

    @Test
    fun `the cabinet's records never touch the live board's copy`() = runTest {
        val store = RecordingStore()
        val cabinet = repository(store, post("archived", 100), fileCabinet = true)

        cabinet.loadNotices("u1", 9_000)

        assertTrue(store.saves.isEmpty(), "the archive was filed as the live board")
        assertNull(cabinet.cachedNotices("u1"), "and the cabinet reads nothing back")
    }

    // -- socket write-through ------------------------------------------------

    @Test
    fun `a post arriving over the socket is filed`() = runTest {
        val store = RecordingStore()
        val repo = repository(store, post("n1", 100))

        repo.rememberNotice(
            "u1",
            Notice(id = "n2", body = "n2", authorName = "Sam", updatedAtMillis = 200),
            post("n2", 200),
        )

        assertEquals(listOf("n2"), store.ids("u1"))
    }

    /** An event with no row has nothing to file; the next read catches it. */
    @Test
    fun `an arrival with no row is a no-op, not a crash`() = runTest {
        val store = RecordingStore()
        val repo = repository(store, post("n1", 100))

        repo.rememberNotice("u1", Notice(id = "n2", body = "n2", authorName = "Sam"), wire = null)
        repo.rememberNotice(null, Notice(id = "n3", body = "n3", authorName = "Sam"), post("n3", 1))

        assertTrue(store.ids("u1").isEmpty())
    }

    /**
     * The visible one: without this a post deleted while somebody watched
     * comes back from disk on the next cold open.
     */
    @Test
    fun `a deleted post is forgotten on disk`() = runTest {
        val store = RecordingStore()
        store.save("u1", listOf(NoticeWire("n1", post("n1", 100), 100)))

        repository(store, post("n1", 100)).forgetNotice("u1", "n1")

        assertTrue(store.ids("u1").isEmpty())
    }

    @Test
    fun `a delete that names no unit still lands`() = runTest {
        val store = RecordingStore()
        store.save("u1", listOf(NoticeWire("n1", post("n1", 100), 100)))

        repository(store, post("n1", 100)).forgetNotice(unitId = null, noticeId = "n1")

        assertTrue(store.ids("u1").isEmpty())
    }

    /** The source has to hand the row along, or the write-through has nothing. */
    @Test
    fun `the realtime events carry the server's row`() {
        val added = HomeRealtimeEvent.NoticeAdded(
            "u1",
            Notice(id = "n1", body = "n1", authorName = "Sam"),
            wire = post("n1", 100),
        )

        assertTrue(added.wire.orEmpty().contains("\"_id\":\"n1\""))
    }
}

private class StoreEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
