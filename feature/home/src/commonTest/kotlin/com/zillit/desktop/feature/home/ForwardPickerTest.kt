package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardPick
import com.zillit.desktop.core.workspace.forward.ForwardReach
import com.zillit.desktop.core.workspace.forward.ForwardSection
import com.zillit.desktop.core.workspace.forward.ForwardStep
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forward picker as the web's modal behaves: this board's units first,
 * then everywhere the host can reach; any number ticked, one copy each; a
 * destination that refuses does not stop the others; and an entry that asks
 * something first hands over to the host instead of sending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardPickerTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val post = Notice(
        id = "n1",
        body = "the crew call moved to seven",
        authorName = "Sam",
    )

    private class FakeBoard(
        val units: List<HomeUnit> = listOf(
            HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true),
            HomeUnit("u2", "camera_tool", "camera_label", canView = true, canPost = true),
            HomeUnit("u3", "sound_tool", "sound_label", canView = true, canPost = false),
        ),
        var notices: List<Notice> = emptyList(),
    ) : HomeFeedRepository {
        val forwarded = mutableListOf<String>()

        override suspend fun loadUnits() = ZillitResult.Success(units)

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(notices)

        override suspend fun forwardNotice(
            unitId: String,
            notice: Notice,
            localId: String,
        ): ZillitResult<Unit> {
            forwarded += unitId
            return ZillitResult.Success(Unit)
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) = ZillitResult.Success(Notice(id = "server", body = text, authorName = "You"))

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)
    }

    /** A host that offers one chat person and one tool that asks a question. */
    private class FakeReach(private val refuseDelivery: Boolean = false) : ForwardReach {
        val asked = mutableListOf<Pair<ForwardKind, String?>>()
        val delivered = mutableListOf<List<String>>()
        val opened = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun sections(item: ForwardItem, from: String?): List<ForwardSection> {
            asked += item.kind to from
            return listOf(
                ForwardSection("People", listOf(ForwardPick(id = "chatuser:u-aisha", label = "Aisha Khan"))),
                ForwardSection("Email", listOf(ForwardStep(id = "email", label = "Forward as email"))),
            )
        }

        override suspend fun deliver(targetIds: List<String>, item: ForwardItem): ZillitResult<Unit> {
            delivered += targetIds
            return if (refuseDelivery) {
                ZillitResult.Failure(ZillitError.NoConnection())
            } else {
                ZillitResult.Success(Unit)
            }
        }

        override suspend fun open(targetId: String, item: ForwardItem): ForwardStepOutcome {
            opened += targetId
            return ForwardStepOutcome.Handled
        }

        override suspend fun submit(
            targetId: String,
            item: ForwardItem,
            answers: Map<String, String>,
        ): ZillitResult<Unit> {
            submitted += targetId to answers
            return ZillitResult.Success(Unit)
        }
    }

    @Test
    fun `the picker offers this board's units and everywhere the host can reach`() = runTest(dispatcher) {
        val reach = FakeReach()
        val model = viewModel(FakeBoard(notices = listOf(post)), reach)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        // The units are there before the reach answers — they are already in state.
        assertEquals(1, model.state.value.forwardSections.size)
        advanceUntilIdle()

        val sections = model.state.value.forwardSections
        assertEquals(3, sections.size, "the board's units, then the host's two")
        assertEquals(
            listOf("unit:u1", "unit:u2", "unit:u3"),
            sections.first().entries.map { it.id },
        )
        // The reach is told what is travelling and who is asking, so it can
        // leave this board out and gate tools by the message's kind.
        assertEquals(ForwardKind.Text to "home", reach.asked.single())
    }

    @Test
    fun `Send posts one copy per ticked unit and says so once`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(post))
        val model = viewModel(board, FakeReach())
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardToAll(listOf("unit:u1", "unit:u2")))
        advanceUntilIdle()

        assertEquals(listOf("u1", "u2"), board.forwarded)
        assertNull(model.state.value.forwarding, "the picker closes with the Send")
        assertEquals("Forwarded to 2 destinations", model.state.value.info)
        assertNull(model.state.value.error)
    }

    @Test
    fun `a unit that refuses does not stop the others`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(post))
        val model = viewModel(board, FakeReach())
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        // u3 has no posting rights; u1 does.
        model.onEvent(HomeFeedEvent.ForwardToAll(listOf("unit:u3", "unit:u1")))
        advanceUntilIdle()

        assertEquals(listOf("u1"), board.forwarded)
        assertTrue(model.state.value.error.orEmpty().isNotBlank(), "the refusal is said out loud")
        assertEquals("Message forwarded successfully", model.state.value.info)
    }

    @Test
    fun `a destination the host owns is delivered by the host`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(post))
        val reach = FakeReach()
        val model = viewModel(board, reach)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardToAll(listOf("unit:u1", "chatuser:u-aisha")))
        advanceUntilIdle()

        assertEquals(listOf("u1"), board.forwarded, "the board's own route took its own unit")
        assertEquals(listOf(listOf("chatuser:u-aisha")), reach.delivered)
        assertEquals("Forwarded to 2 destinations", model.state.value.info)
    }

    @Test
    fun `a refused delivery is reported and the board's copies still land`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(post))
        val reach = FakeReach(refuseDelivery = true)
        val model = viewModel(board, reach)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardToAll(listOf("unit:u1", "chatuser:u-aisha")))
        advanceUntilIdle()

        assertEquals(listOf("u1"), board.forwarded)
        assertTrue(model.state.value.error.orEmpty().isNotBlank())
    }

    @Test
    fun `an entry that asks something first hands over to the host`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(post))
        val reach = FakeReach()
        val model = viewModel(board, reach)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardStepPicked("email"))
        advanceUntilIdle()

        assertEquals(listOf("email"), reach.opened)
        assertTrue(board.forwarded.isEmpty(), "nothing was posted")
        assertNull(model.state.value.forwarding, "the picker closed")
    }

    @Test
    fun `an unsent post has nothing to forward`() = runTest(dispatcher) {
        val pending = post.copy(
            id = "local-9",
            sendState = com.zillit.desktop.feature.home.domain.NoticeSendState.Sending,
        )
        val reach = FakeReach()
        val model = viewModel(FakeBoard(notices = listOf(pending)), reach)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("local-9"))
        advanceUntilIdle()

        assertNull(model.state.value.forwarding)
        assertTrue(reach.asked.isEmpty(), "the host was never asked")
    }

    @Test
    fun `without a host the picker is this board alone`() = runTest(dispatcher) {
        val model = viewModel(FakeBoard(notices = listOf(post)), reach = null)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        advanceUntilIdle()

        assertEquals(1, model.state.value.forwardSections.size)
        assertEquals(3, model.state.value.forwardSections.single().entries.size)
    }

    private fun viewModel(board: FakeBoard, reach: ForwardReach?) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
        reach = reach,
        boardKey = "home",
    ).also { it.onEvent(HomeFeedEvent.Load) }
}
