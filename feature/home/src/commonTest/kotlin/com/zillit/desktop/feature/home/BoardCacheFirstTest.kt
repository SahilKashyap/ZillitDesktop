package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import kotlinx.coroutines.CompletableDeferred
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
 * The board draws what it already knows, then refreshes behind it.
 *
 * The rows were always written — `loadNotices` names the newest page with
 * `cacheAs` — but nothing ever read them back while the network was up, so
 * every arrival at Home sat on "Loading posts…" for a round trip with a
 * perfectly good page on disk. Two memories answer now: the session's own
 * per-tab map, and the kept page behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardCacheFirstTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun post(id: String, at: Long = 1_000) =
        Notice(id = id, body = id, authorName = "Sam", createdAtMillis = at, updatedAtMillis = at)

    private val units = listOf(
        HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true),
        HomeUnit("u2", "general_tool", "general_label", canView = true, canPost = true),
    )

    /**
     * A board with a disk behind it. [kept] is what was cached; [served] is
     * what the server answers. [gate], when set, holds the server's answer so
     * a test can look at the screen mid-read.
     */
    private class CachingBoard(
        val kept: Map<String, List<Notice>> = emptyMap(),
        val keptUnits: List<HomeUnit>? = null,
        val served: Map<String, List<Notice>> = emptyMap(),
        val units: List<HomeUnit>,
        val gate: CompletableDeferred<Unit>? = null,
        /** Holds a send, so a card can be caught still going up. */
        val postGate: CompletableDeferred<Unit>? = null,
        val failing: Boolean = false,
    ) : HomeFeedRepository {
        val noticeLoads = mutableListOf<String>()
        val peeks = mutableListOf<String>()

        override suspend fun loadUnits() = ZillitResult.Success(units)

        override suspend fun cachedUnits(): List<HomeUnit>? = keptUnits

        override suspend fun cachedNotices(unitId: String): List<Notice>? {
            peeks += unitId
            return kept[unitId]
        }

        override suspend fun loadNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            noticeLoads += unitId
            gate?.await()
            if (failing) return ZillitResult.Failure(ZillitError.NoConnection())
            return ZillitResult.Success(served[unitId].orEmpty())
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> {
            postGate?.await()
            return ZillitResult.Success(Notice(id = "server", body = text, authorName = "You"))
        }

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) = ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) = ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) = ZillitResult.Success(Unit)

        override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)
    }

    private fun viewModel(board: CachingBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 100_000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `a kept page is on screen before the server answers`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val board = CachingBoard(
            kept = mapOf("u1" to listOf(post("kept-a"), post("kept-b"))),
            served = mapOf("u1" to listOf(post("fresh-a"))),
            units = units,
            gate = gate,
        )
        val model = viewModel(board)
        advanceUntilIdle()

        // The read is still out, and the board is not a spinner.
        assertTrue(model.state.value.isLoadingNotices, "the real read should still be in flight")
        assertEquals(listOf("kept-a", "kept-b"), model.state.value.notices.map { it.id })

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isLoadingNotices)
        assertEquals(listOf("fresh-a"), model.state.value.notices.map { it.id }, "the server's page wins")
    }

    @Test
    fun `a tab nobody has read waits, as it must`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val board = CachingBoard(served = mapOf("u1" to listOf(post("a"))), units = units, gate = gate)
        val model = viewModel(board)
        advanceUntilIdle()

        assertTrue(model.state.value.isLoadingNotices)
        assertTrue(model.state.value.notices.isEmpty(), "nothing kept, so there is nothing to draw")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a"), model.state.value.notices.map { it.id })
    }

    @Test
    fun `going back to a tab this session read draws it with no disk read at all`() = runTest(dispatcher) {
        val board = CachingBoard(
            served = mapOf("u1" to listOf(post("a1")), "u2" to listOf(post("b1"))),
            units = units,
        )
        val model = viewModel(board)
        advanceUntilIdle()
        assertEquals(listOf("u1"), board.peeks, "a first view asks the disk")

        model.onEvent(HomeFeedEvent.SelectUnit("u2"))
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.SelectUnit("u1"))

        // Before anything is awaited: the page is already back on screen.
        assertEquals(listOf("a1"), model.state.value.notices.map { it.id })
        advanceUntilIdle()
        assertEquals(
            listOf("u1", "u2"),
            board.peeks,
            "u1 came out of memory the second time — the disk was not asked again",
        )
    }

    @Test
    fun `switching tabs never shows the previous tab's posts`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val board = CachingBoard(
            served = mapOf("u1" to listOf(post("a1"))),
            units = units,
            gate = gate,
        )
        val model = viewModel(board)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a1"), model.state.value.notices.map { it.id })

        model.onEvent(HomeFeedEvent.SelectUnit("u2"))

        // u2 has nothing anywhere, so it is empty — emphatically not u1's post.
        assertTrue(model.state.value.notices.isEmpty(), "u1's board leaked into u2")
    }

    /** The kept strip opens its tab, and the live strip does not read it twice. */
    @Test
    fun `a kept tab strip opens the board once, not twice`() = runTest(dispatcher) {
        val board = CachingBoard(
            keptUnits = units,
            kept = mapOf("u1" to listOf(post("kept"))),
            served = mapOf("u1" to listOf(post("fresh"))),
            units = units,
        )
        val model = viewModel(board)
        advanceUntilIdle()

        assertEquals(listOf("u1"), board.noticeLoads, "the seeded strip and the live one both opened the board")
        assertEquals(listOf("fresh"), model.state.value.notices.map { it.id })
    }

    /**
     * A post still going up is the one thing the server's answer cannot know
     * about. Replacing the list wholesale took the card out from under a
     * running upload — chat guards the same race the same way.
     */
    @Test
    fun `a fetched page does not drop a post still in flight`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val postGate = CompletableDeferred<Unit>()
        val board = CachingBoard(
            served = mapOf("u1" to listOf(post("fresh"))),
            units = units,
            gate = gate,
            postGate = postGate,
        )
        val model = viewModel(board)
        advanceUntilIdle()

        // A card the composer put up while the board read was still out. Its
        // send is held, so it is genuinely in flight when the page lands.
        model.onEvent(HomeFeedEvent.DraftChanged("hello"))
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()
        val going = model.state.value.notices.filter { it.sendState != NoticeSendState.Sent }
        assertEquals(1, going.size, "the composer should have put a card up")

        gate.complete(Unit)
        advanceUntilIdle()

        val ids = model.state.value.notices.map { it.id }
        assertTrue(ids.contains("fresh"), "the server's page should be in: $ids")
        assertTrue(
            model.state.value.notices.any { it.localId == going.single().localId },
            "the card was taken out from under a running upload: $ids",
        )
    }

    /** Offline, the kept page stays up and the error rides over it. */
    @Test
    fun `a failed read leaves the kept page on screen`() = runTest(dispatcher) {
        val board = CachingBoard(
            kept = mapOf("u1" to listOf(post("kept"))),
            units = units,
            failing = true,
        )
        val model = viewModel(board)
        advanceUntilIdle()

        assertEquals(listOf("kept"), model.state.value.notices.map { it.id })
        assertFalse(model.state.value.isLoadingNotices)
    }

    @Test
    fun `leaving a production forgets its boards`() = runTest(dispatcher) {
        val board = CachingBoard(served = mapOf("u1" to listOf(post("a1"))), units = units)
        val model = viewModel(board)
        advanceUntilIdle()
        assertEquals(listOf("a1"), model.state.value.notices.map { it.id })

        model.onEvent(HomeFeedEvent.ProjectChanged)
        assertTrue(model.state.value.notices.isEmpty())

        // The next production's board must not find the previous one's page
        // in memory: this open asks the disk afresh.
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        assertEquals(listOf("u1", "u1"), board.peeks, "the remembered page survived a production switch")
    }
}
