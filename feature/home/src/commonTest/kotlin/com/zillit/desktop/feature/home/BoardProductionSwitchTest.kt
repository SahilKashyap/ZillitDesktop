package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.MediaCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Switching production and coming back.
 *
 * The board used to forget everything on a switch, so every return was the
 * tab strip from disk and then the posts from disk, each behind a spinner and
 * each queued behind the writes a switch makes. And a read still out when the
 * switch happened landed on the next production's board. Both are about the
 * same thing: what is on screen belongs to one production, and only ever comes
 * back under that production's name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardProductionSwitchTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Which production the host says is open — what the header carries. */
    private var open: String? = "p1"

    private fun post(id: String) =
        Notice(id = id, body = id, authorName = "Sam", createdAtMillis = 1_000, updatedAtMillis = 1_000)

    private fun unitOf(production: String, n: Int = 1) =
        HomeUnit("$production-u$n", "general_tool", "general_label", canView = true, canPost = true)

    /**
     * A server with two productions behind it, answering for whichever one
     * is open when it is asked — as the real one does, by the header. Nothing
     * on disk, so anything on screen before an answer came from memory.
     */
    private inner class TwoProductions : HomeFeedRepository {
        val unitLoads = mutableListOf<String?>()
        val noticeLoads = mutableListOf<String>()
        val peeks = mutableListOf<String>()

        /** How many tabs each production's strip has. */
        var tabs = 1

        /** Holds the next board answer, when set. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun loadUnits(): ZillitResult<List<HomeUnit>> {
            unitLoads += open
            return ZillitResult.Success(open?.let { p -> (1..tabs).map { unitOf(p, it) } }.orEmpty())
        }

        override suspend fun cachedNotices(unitId: String): List<Notice>? {
            peeks += unitId
            return null
        }

        override suspend fun loadNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            noticeLoads += unitId
            val asked = open
            gate?.await()
            return ZillitResult.Success(listOf(post("$asked-a"), post("$asked-b")))
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> = ZillitResult.Success(post("server"))

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

    private fun viewModel(board: TwoProductions, defaultUnitId: String? = null) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 100_000 },
        media = MediaCapture(currentProjectId = { open }),
        defaultUnitId = { defaultUnitId },
    )

    /** The switch as the host sends it: the header moves first, then the board is told and loaded. */
    private fun HomeFeedViewModel.switchTo(production: String) {
        open = production
        onEvent(HomeFeedEvent.ProjectChanged)
        onEvent(HomeFeedEvent.Load)
    }

    private val HomeFeedUiState.postIds get() = notices.map { it.id }

    @Test
    fun `coming back to a production draws its board before anything is read`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        model.switchTo("p2")
        advanceUntilIdle()

        board.gate = CompletableDeferred()
        model.switchTo("p1")

        // Synchronously, on the Load itself: no disk, no network, no spinner.
        val back = model.state.value
        assertEquals(listOf("p1-u1"), back.tabs.map { it.id })
        assertEquals(listOf("p1-a", "p1-b"), back.postIds, "p1's own board, from memory")
        assertFalse(back.isLoadingNotices && back.notices.isEmpty(), "no spinner over a board we already had")

        runCurrent()
        assertEquals("p1-u1", board.noticeLoads.last(), "and it is refreshed behind what is shown")
        board.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("p1-a", "p1-b"), model.state.value.postIds)
    }

    @Test
    fun `coming back to a production lands on its first tab, not the one left open`() = runTest(dispatcher) {
        val board = TwoProductions().apply { tabs = 2 }
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.SelectUnit("p1-u2"))
        advanceUntilIdle()
        assertEquals("p1-u2", model.state.value.selectedUnitId)

        model.switchTo("p2")
        advanceUntilIdle()
        model.switchTo("p1")

        assertEquals("p1-u1", model.state.value.selectedUnitId, "the tab left open came back")
        advanceUntilIdle()
        assertEquals("p1-u1", model.state.value.selectedUnitId, "the strip's own read moved it back")
    }

    @Test
    fun `coming back to a production lands on the profile's default tab`() = runTest(dispatcher) {
        val board = TwoProductions().apply { tabs = 3 }
        val model = viewModel(board, defaultUnitId = "p1-u2")
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.SelectUnit("p1-u3"))
        advanceUntilIdle()

        model.switchTo("p2")
        advanceUntilIdle()
        model.switchTo("p1")
        advanceUntilIdle()

        assertEquals("p1-u2", model.state.value.selectedUnitId)
    }

    @Test
    fun `a production's board never comes back under another's name`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()

        board.gate = CompletableDeferred()
        model.switchTo("p2")

        val arriving = model.state.value
        assertTrue(arriving.notices.isEmpty(), "p1's posts on p2's board: ${arriving.postIds}")
        assertTrue(arriving.tabs.none { it.id.startsWith("p1") }, "p1's tabs on p2's board")
        board.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("p2-a", "p2-b"), model.state.value.postIds)
    }

    @Test
    fun `a read still out when the production changes does not land on the next one`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        board.gate = CompletableDeferred()
        model.onEvent(HomeFeedEvent.Load)
        runCurrent()
        assertEquals(listOf("p1-u1"), board.noticeLoads, "p1's board is out")

        val p1Answer = board.gate!!
        board.gate = null
        model.switchTo("p2")
        advanceUntilIdle()
        // p1's answer finally arrives — after p2's board is up.
        p1Answer.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("p2-a", "p2-b"), model.state.value.postIds)
        assertEquals("p2-u1", model.state.value.selectedUnitId)
    }

    /**
     * Between the header moving and the board being told, the old connection
     * still delivers. A tab-strip refresh asked then would read the next
     * production's units into this production's board.
     */
    @Test
    fun `nothing is read for the board in the gap before it hears of the switch`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        val before = board.unitLoads.size

        open = "p2"
        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.UnitsChanged))
        advanceUntilIdle()

        assertEquals(before, board.unitLoads.size, "a read was asked for p1's board with p2 open")
        assertEquals(listOf("p1-u1"), model.state.value.tabs.map { it.id })
    }

    /**
     * The switch as the shell really sends it.
     *
     * The production is dropped the moment it is left — `AuthViewModel`
     * clears it before the picker is drawn — and the board is cleared there
     * too, with the picker on screen. It used to be cleared on arrival
     * instead, behind the new production's first frame, which put the
     * previous production's posts under the new production's name for as long
     * as the read took.
     */
    @Test
    fun `the board is emptied as the production is left`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        assertEquals(listOf("p1-a", "p1-b"), model.state.value.postIds)

        // Leaving: no production open, and nothing chosen yet.
        open = null
        model.onEvent(HomeFeedEvent.ProjectChanged)
        advanceUntilIdle()

        assertTrue(model.state.value.notices.isEmpty(), "the production left takes its posts with it")
        assertTrue(model.state.value.tabs.isEmpty(), "and its tab strip")
    }

    /**
     * And what was filed as it was left comes back when it is reopened — the
     * clear moving earlier must not cost the kept board, which is the whole
     * reason a switch files one.
     */
    @Test
    fun `a production left through the picker still draws its own board on return`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()

        open = null
        model.onEvent(HomeFeedEvent.ProjectChanged)
        advanceUntilIdle()
        open = "p2"
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        assertEquals(listOf("p2-a", "p2-b"), model.state.value.postIds)

        open = null
        model.onEvent(HomeFeedEvent.ProjectChanged)
        advanceUntilIdle()
        board.gate = CompletableDeferred()
        open = "p1"
        model.onEvent(HomeFeedEvent.Load)

        // Synchronously, before any answer: p1's own board, filed as it was left.
        assertEquals(listOf("p1-a", "p1-b"), model.state.value.postIds, "p1's own board, from memory")
        board.gate?.complete(Unit)
        advanceUntilIdle()
    }

    /** The gap between two productions: a socket arrival must not read into it. */
    @Test
    fun `a read asked between productions is not asked at all`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = viewModel(board)
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()
        val before = board.unitLoads.size

        open = null
        model.onEvent(HomeFeedEvent.ProjectChanged)
        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.UnitsChanged))
        advanceUntilIdle()

        assertEquals(before, board.unitLoads.size, "a read with no production open carries no production")
    }

    /** A host that does not say which production is open keeps nothing across a switch — as before. */
    @Test
    fun `without a production id nothing is kept`() = runTest(dispatcher) {
        val board = TwoProductions()
        val model = HomeFeedViewModel(repository = board, nowMillis = { 100_000 })
        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()

        board.gate = CompletableDeferred()
        model.onEvent(HomeFeedEvent.ProjectChanged)
        model.onEvent(HomeFeedEvent.Load)

        assertTrue(model.state.value.notices.isEmpty())
        board.gate?.complete(Unit)
        advanceUntilIdle()
    }
}
