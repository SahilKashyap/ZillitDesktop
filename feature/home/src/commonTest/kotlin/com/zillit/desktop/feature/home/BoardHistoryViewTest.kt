package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The board's History view — the call sheet's and the production report chat's.
 *
 * The record is what a replacement or a delete took off the board, read from
 * the server with `deleted=` rather than filtered out of what is on screen
 * (which is what the switch used to do: re-sort the live page and call it
 * history). So: the read, the posts it keeps, and the live board it must leave
 * exactly as it found it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardHistoryViewTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val reportChat = HomeUnit(
        id = "pr",
        identifier = "production_report_chat_tool",
        unitName = "production_report_tool_chat_label",
        canView = true,
        canPost = true,
    )

    private fun post(id: String, createdAt: Long) = Notice(
        id = id,
        body = "post $id",
        authorName = "AD",
        authorId = "ad",
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    /** A board whose live page and record are two different answers. */
    private class FakeBoard(
        val live: List<Notice>,
        val record: List<Notice>,
    ) : HomeFeedRepository {
        /** `beforeMillis` of every live read, and of every record read. */
        val liveReads = mutableListOf<Long>()
        val recordReads = mutableListOf<Long>()
        val olderReads = mutableListOf<Long>()

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(
                HomeUnit(
                    id = "pr",
                    identifier = "production_report_chat_tool",
                    unitName = "production_report_tool_chat_label",
                    canView = true,
                    canPost = true,
                ),
            ),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            liveReads += beforeMillis
            return ZillitResult.Success(live)
        }

        override suspend fun loadOlderNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            olderReads += beforeMillis
            return ZillitResult.Success(emptyList())
        }

        override suspend fun loadHistory(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            recordReads += beforeMillis
            return ZillitResult.Success(record)
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) = ZillitResult.Success(post(text, 0))

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) = ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) = ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        private fun post(id: String, createdAt: Long) =
            Notice(id = id, body = id, authorName = "AD", createdAtMillis = createdAt)
    }

    private fun viewModel(board: FakeBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { NOW },
        newLocalId = { "local-1" },
        isAdmin = { false },
        currentUserId = { "me" },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    private companion object {
        /** The clock every read asks with, so the record's cursor is readable in the assertions. */
        const val NOW = 10_000L
    }

    @Test
    fun `History reads the record, and leaving it puts the live board back`() = runTest(dispatcher) {
        val board = FakeBoard(live = listOf(post("live", 2_000)), record = listOf(post("gone", 1_000)))
        val model = viewModel(board)
        advanceUntilIdle()
        assertEquals(listOf("live"), model.state.value.notices.map { it.id })

        model.onEvent(HomeFeedEvent.ShowHistory(true))
        advanceUntilIdle()

        assertTrue(model.state.value.isHistory)
        assertEquals(listOf(NOW), board.recordReads)
        assertEquals(listOf("gone"), model.state.value.notices.map { it.id })
        // A record is read, never written to.
        assertFalse(model.state.value.showsComposer)

        model.onEvent(HomeFeedEvent.ShowHistory(false))

        // The live page is back before the read that refreshes it answers —
        // the board must not blink through the record on the way out.
        assertFalse(model.state.value.isHistory)
        assertEquals(listOf("live"), model.state.value.notices.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf("live"), model.state.value.notices.map { it.id })
        assertEquals(2, board.liveReads.size)
    }

    @Test
    fun `live traffic stays out of the record, and the board keeps its own page`() = runTest(dispatcher) {
        val board = FakeBoard(live = listOf(post("live", 2_000)), record = listOf(post("gone", 1_000)))
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.ShowHistory(true))
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.NoticeAdded(unitId = "pr", notice = post("new", 3_000))))
        advanceUntilIdle()

        assertEquals(listOf("gone"), model.state.value.notices.map { it.id })

        model.onEvent(HomeFeedEvent.ShowHistory(false))
        advanceUntilIdle()

        assertEquals(listOf("live"), model.state.value.notices.map { it.id })
    }

    @Test
    fun `paging the record asks for the record again, from the oldest post's created`() = runTest(dispatcher) {
        val board = FakeBoard(
            live = listOf(post("live", 2_000)),
            record = listOf(post("gone", 1_000), post("older", 400)),
        )
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.ShowHistory(true))
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.LoadOlder)
        advanceUntilIdle()

        assertEquals(listOf(NOW, 400), board.recordReads)
        assertEquals(emptyList(), board.olderReads)
    }

    @Test
    fun `switching tabs leaves History behind`() = runTest(dispatcher) {
        val board = FakeBoard(live = listOf(post("live", 2_000)), record = listOf(post("gone", 1_000)))
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.ShowHistory(true))
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectUnit(reportChat.id))
        advanceUntilIdle()

        assertFalse(model.state.value.isHistory)
        assertEquals(listOf("live"), model.state.value.notices.map { it.id })
    }

    // -- the wire -----------------------------------------------------------

    @Test
    fun `the record is the previous route with deleted on it, and keeps the posts it marks`() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.host}${request.url.encodedPath}" +
                request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
            respond(
                // `deleted` as a flag on the post (what the live reader
                // recognises) and as a stamp on its reply (what the reply
                // reader reads) — the record keeps both spellings.
                """{"status":1,"data":[
                    {"_id":"gone","message":"replaced","name":"AD","created":1000,"updated":1000,
                     "deleted":true,
                     "comments":[{"_id":"c1","message":"on the retired one","deleted":1700000000000}]}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val plain = object : NoticeDecryptor {
            override fun decryptFromHex(cipherHex: String) = ZillitResult.Success(cipherHex)
            override fun encryptToHex(plaintext: String) = ZillitResult.Success(plaintext)
        }
        val repository = HomeFeedRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ HistoryEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            decrypt = plain,
            isAdmin = { false },
            board = "production-report",
            service = ZillitService.ProductionReport,
            chatSegment = "",
        )

        val record = repository.loadHistory("u1", beforeMillis = 5)
        val live = repository.loadNotices("u1", beforeMillis = 5)

        assertEquals(
            listOf(
                "GET productionreport.test/api/v2/production-report/u1/5/previous?deleted=5",
                "GET productionreport.test/api/v2/production-report/u1/5/previous",
            ),
            calls,
        )
        // The record keeps what the live board drops — the post the server
        // stamped `deleted`, and the replies deleted along with it.
        val kept = (record as ZillitResult.Success).data
        assertEquals(listOf("gone"), kept.map { it.id })
        assertEquals(listOf("c1"), kept.single().comments.map { it.id })
        assertEquals(emptyList(), (live as ZillitResult.Success).data)
    }
}

private class HistoryEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
