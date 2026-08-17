package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.NoticeSendState
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Posting to the board.
 *
 * The card appears before the server has taken it, so the important behaviour
 * is what happens when the round trip fails: the post must stay on screen with
 * the user's words intact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(var failPosts: Boolean = false) : HomeFeedRepository {
        var posts = 0
            private set
        var lastText: String? = null
            private set

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(
                HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true),
                HomeUnit("u2", "readonly_tool", "notes_label", canView = true, canPost = false),
            ),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(emptyList<Notice>())

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) =
            if (failPosts) {
                ZillitResult.Failure(ZillitError.NoConnection())
            } else {
                posts++
                lastText = text
                ZillitResult.Success(
                    Notice(id = "server-$posts", body = text, authorName = "You", localId = localId),
                )
            }

        override suspend fun postComment(
            noticeId: String,
            unitId: String,
            text: String,
        ) = ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(
            noticeId: String,
            commentId: String,
            text: String,
        ) = ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) =
            ZillitResult.Success(com.zillit.desktop.feature.home.domain.ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

    }

    private fun viewModel(repository: FakeRepository, admin: Boolean = false): HomeFeedViewModel {
        var n = 0
        return HomeFeedViewModel(
            repository,
            nowMillis = { 1000 },
            newLocalId = { "local-${n++}" },
            isAdmin = { admin },
        )
    }

    private fun HomeFeedViewModel.loaded() = also {
        onEvent(HomeFeedEvent.Load)
    }

    @Test
    fun `a post appears before the server answers`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("Crew call 0600"))
        viewModel.onEvent(HomeFeedEvent.Send)

        // Before the coroutine runs: the card is already there.
        val shown = viewModel.currentState.notices.single()
        assertEquals("Crew call 0600", shown.body)
        assertEquals(NoticeSendState.Sending, shown.sendState)
    }

    @Test
    fun `the composer clears immediately`() = runTest(dispatcher) {
        // Waiting for a round trip before clearing makes typing feel unanswered.
        val viewModel = viewModel(FakeRepository()).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("Anything"))
        viewModel.onEvent(HomeFeedEvent.Send)

        assertEquals("", viewModel.currentState.draft.text)
    }

    @Test
    fun `the server copy replaces the optimistic one rather than doubling it`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository()).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("Once"))
        viewModel.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals(1, viewModel.currentState.notices.size, "the post was shown twice")
        assertEquals(NoticeSendState.Sent, viewModel.currentState.notices.single().sendState)
    }

    @Test
    fun `a failed post keeps the words on screen`() = runTest(dispatcher) {
        // Losing what someone wrote because the network blinked is the worst
        // outcome here.
        val viewModel = viewModel(FakeRepository(failPosts = true)).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("Important notice"))
        viewModel.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        val failed = viewModel.currentState.notices.single()
        assertEquals("Important notice", failed.body)
        assertEquals(NoticeSendState.Failed, failed.sendState)
    }

    @Test
    fun `a failed post can be retried`() = runTest(dispatcher) {
        val repository = FakeRepository(failPosts = true)
        val viewModel = viewModel(repository).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("Retry me"))
        viewModel.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        repository.failPosts = false
        viewModel.onEvent(HomeFeedEvent.Retry(viewModel.currentState.notices.single().localId!!))
        advanceUntilIdle()

        assertEquals(NoticeSendState.Sent, viewModel.currentState.notices.single().sendState)
        assertEquals("Retry me", repository.lastText)
    }

    @Test
    fun `an empty draft sends nothing`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("   "))
        viewModel.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals(0, repository.posts)
        assertTrue(viewModel.currentState.notices.isEmpty())
    }

    @Test
    fun `the composer is hidden where the user cannot post`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository()).loaded()
        advanceUntilIdle()

        assertTrue(viewModel.currentState.canCompose, "u1 grants posting")

        viewModel.onEvent(HomeFeedEvent.SelectUnit("u2"))
        advanceUntilIdle()

        assertFalse(viewModel.currentState.canCompose, "u2 is read-only")
    }

    @Test
    fun `an admin may post to a unit with no posting rights`() = runTest(dispatcher) {
        // The web gates on `!posting_access && !is_admin`. Without the bypass an
        // admin sees a read-only board on every unit — which is exactly what
        // shipped before this test existed.
        val viewModel = viewModel(FakeRepository(), admin = true).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.SelectUnit("u2")) // posting_access = false
        advanceUntilIdle()

        assertTrue(viewModel.currentState.canCompose, "an admin was locked out of posting")
    }

    @Test
    fun `a non-admin is still held to the unit's posting rights`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(), admin = false).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.SelectUnit("u2"))
        advanceUntilIdle()

        assertFalse(viewModel.currentState.canCompose)
    }

    @Test
    fun `the calendar never shows a composer, even for an admin`() = runTest(dispatcher) {
        // There is no board to post to.
        val calendarOnly = object : HomeFeedRepository by FakeRepository() {
            override suspend fun loadUnits() = ZillitResult.Success(
                listOf(HomeUnit("c1", "cal", "calendar_label", canView = true, canPost = true)),
            )
        }
        val viewModel = HomeFeedViewModel(calendarOnly, nowMillis = { 0 }, isAdmin = { true })
        viewModel.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()

        assertFalse(viewModel.currentState.canCompose)
    }

    @Test
    fun `the text sent up is trimmed`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository).loaded()
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("  padded  "))
        viewModel.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals("padded", repository.lastText)
    }
}
