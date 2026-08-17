package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
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

/**
 * The picker's recency memory: newest first, no repeats, capped, persisted.
 *
 * The order rule worth pinning is *re*-mention — picking a name already in
 * the memory must move it to the front, not duplicate it or leave it buried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentMentionsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeBoard : HomeFeedRepository {
        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(emptyList<Notice>())

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

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)
    }

    private fun viewModel(
        persisted: List<String> = emptyList(),
        saved: MutableList<List<String>> = mutableListOf(),
    ) = HomeFeedViewModel(
        repository = FakeBoard(),
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
        loadRecentMentions = { persisted },
        saveRecentMentions = { saved += it },
    )

    @Test
    fun `a pick goes to the front, and a repeat moves rather than duplicates`() = runTest(dispatcher) {
        val model = viewModel()

        model.onEvent(HomeFeedEvent.MentionPicked("Aisha Khan"))
        model.onEvent(HomeFeedEvent.MentionPicked("Vidya Pixel"))
        model.onEvent(HomeFeedEvent.MentionPicked("Aisha Khan"))
        advanceUntilIdle()

        assertEquals(listOf("Aisha Khan", "Vidya Pixel"), model.state.value.recentMentions)
    }

    @Test
    fun `the memory holds twelve and forgets the oldest`() = runTest(dispatcher) {
        val model = viewModel()

        repeat(13) { model.onEvent(HomeFeedEvent.MentionPicked("Crew $it")) }
        advanceUntilIdle()

        val recent = model.state.value.recentMentions
        assertEquals(12, recent.size)
        assertEquals("Crew 12", recent.first())
        assertEquals("Crew 1", recent.last(), "Crew 0 fell off the end")
    }

    @Test
    fun `persisted names load with the board`() = runTest(dispatcher) {
        val model = viewModel(persisted = listOf("Sunil k Gautam", "Aisha"))

        model.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()

        assertEquals(listOf("Sunil k Gautam", "Aisha"), model.state.value.recentMentions)
    }

    @Test
    fun `every pick reaches the store with the full new list`() = runTest(dispatcher) {
        val saved = mutableListOf<List<String>>()
        val model = viewModel(saved = saved)

        model.onEvent(HomeFeedEvent.MentionPicked("Aisha"))
        model.onEvent(HomeFeedEvent.MentionPicked("Vidya Pixel"))
        advanceUntilIdle()

        assertEquals(
            listOf(listOf("Aisha"), listOf("Vidya Pixel", "Aisha")),
            saved,
        )
    }
}
