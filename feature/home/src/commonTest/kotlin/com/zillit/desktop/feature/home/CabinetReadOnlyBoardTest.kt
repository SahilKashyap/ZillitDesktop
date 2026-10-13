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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The File Cabinet's board is a record: whatever the user's rights on the live
 * board, nothing on it writes, replies or pins, and it reads and pages the way
 * the web's cabinet viewer does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CabinetReadOnlyBoardTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun post(id: String, created: Long, updated: Long = created, mine: Boolean = true) = Notice(
        id = id,
        body = id,
        authorName = "Sam",
        authorId = if (mine) "me" else "them",
        createdAtMillis = created,
        updatedAtMillis = updated,
    )

    /** Records every call that is not a read, and the cursor each older page was asked from. */
    private class Archive(val newest: List<Notice>, val older: List<Notice> = emptyList()) : HomeFeedRepository {
        val writes = mutableListOf<String>()
        val olderAsked = mutableListOf<Long>()

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "info_tool", "info_label", canView = true, canPost = false, canDownload = true)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) = ZillitResult.Success(newest)

        override suspend fun loadOlderNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            olderAsked += beforeMillis
            return ZillitResult.Success(older)
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> {
            writes += "post"
            return ZillitResult.Success(Notice(id = "server", body = text, authorName = "You"))
        }

        override suspend fun postComment(
            noticeId: String,
            unitId: String,
            text: String,
        ): ZillitResult<List<NoticeComment>> {
            writes += "comment"
            return ZillitResult.Success(emptyList())
        }

        override suspend fun editComment(
            noticeId: String,
            commentId: String,
            text: String,
        ): ZillitResult<NoticeComment?> {
            writes += "editComment"
            return ZillitResult.Success(null)
        }

        override suspend fun deleteComment(noticeId: String, commentId: String): ZillitResult<Unit> {
            writes += "deleteComment"
            return ZillitResult.Success(Unit)
        }

        override suspend fun editNotice(noticeId: String, text: String): ZillitResult<Notice?> {
            writes += "edit"
            return ZillitResult.Success(null)
        }

        override suspend fun deleteNotice(noticeId: String): ZillitResult<Unit> {
            writes += "delete"
            return ZillitResult.Success(Unit)
        }

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String): ZillitResult<Unit> {
            writes += "forward"
            return ZillitResult.Success(Unit)
        }

        override suspend fun readBy(noticeId: String): ZillitResult<ReadBy> {
            writes += "readBy"
            return ZillitResult.Success(ReadBy())
        }

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean): ZillitResult<Unit> {
            writes += "pin"
            return ZillitResult.Success(Unit)
        }

        override suspend fun notifyUnread(unitId: String, noticeId: String): ZillitResult<Unit> {
            writes += "notify"
            return ZillitResult.Success(Unit)
        }
    }

    private fun model(archive: Archive) = HomeFeedViewModel(
        repository = archive,
        nowMillis = { 100_000 },
        newLocalId = { "local-1" },
        // An admin and the author: every right the live board could give.
        isAdmin = { true },
        currentUserId = { "me" },
        readOnly = true,
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `no composer, no menu rights, no pinned banner - even for an admin who wrote the post`() = runTest(dispatcher) {
        val archive = Archive(listOf(post("n1", 1_000).copy(isPinned = true)))
        val model = model(archive)
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.showsComposer)
        assertFalse(state.canCompose)
        assertFalse(state.canAct(state.notices.first()))
        assertFalse(state.canEdit(state.notices.first()))
        assertTrue(state.pinnedBanner.isEmpty())
        assertTrue(state.canDownload, "files still download")
    }

    @Test
    fun `every write event is refused and reaches the repository as nothing`() = runTest(dispatcher) {
        val archive = Archive(listOf(post("n1", 1_000)))
        val model = model(archive)
        advanceUntilIdle()

        listOf(
            HomeFeedEvent.DraftChanged("hello"),
            HomeFeedEvent.Send,
            HomeFeedEvent.StartReply("n1"),
            HomeFeedEvent.StartEditNotice("n1"),
            HomeFeedEvent.DeleteNotice("n1"),
            HomeFeedEvent.DeleteComment("n1", "c1"),
            HomeFeedEvent.TogglePin("n1"),
            HomeFeedEvent.StartForward("n1"),
            HomeFeedEvent.ForwardTo("u1"),
            HomeFeedEvent.ShowReadBy("n1"),
            HomeFeedEvent.NotifyUnread,
            HomeFeedEvent.Attach,
            HomeFeedEvent.StartRecording,
            HomeFeedEvent.ShowHistory(true),
        ).forEach(model::onEvent)
        advanceUntilIdle()

        assertEquals(emptyList(), archive.writes)
        val state = model.state.value
        assertEquals(null, state.replyTo)
        assertEquals(null, state.editingPost)
        assertEquals(null, state.forwarding)
        assertEquals(null, state.readBy)
        assertEquals("", state.draft.text)
        assertFalse(state.isHistory)
        assertTrue(state.readOnly)
    }

    @Test
    fun `reading still works - paging, finding and the gallery`() = runTest(dispatcher) {
        val archive = Archive(
            newest = listOf(post("n2", 2_000), post("n1", 1_000)),
            older = listOf(post("o1", 500)),
        )
        val model = model(archive)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.LoadOlder)
        model.onEvent(HomeFeedEvent.OpenSearch)
        model.onEvent(HomeFeedEvent.SearchChanged("n1"))
        model.onEvent(HomeFeedEvent.ShowLibrary)
        advanceUntilIdle()

        assertEquals(setOf("n1", "n2", "o1"), model.state.value.notices.map { it.id }.toSet())
        assertEquals(listOf("n1"), model.state.value.searchMatches)
        assertTrue(model.state.value.libraryOpen)
    }

    @Test
    fun `an archive pages from created and reads in the order written`() = runTest(dispatcher) {
        // n3 was touched after the move: updated is far ahead of created. The
        // live board would order it last and page from its oldest updated.
        val archive = Archive(
            newest = listOf(post("n3", created = 3_000, updated = 90_000), post("n2", 2_000), post("n1", 1_000)),
            older = listOf(post("o1", 500)),
        )
        val model = model(archive)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.LoadOlder)
        advanceUntilIdle()

        assertEquals(listOf(1_000L), archive.olderAsked)
        val order = model.state.value.rows.filterIsInstance<com.zillit.desktop.feature.home.domain.BoardRow.Post>()
            .map { it.notice.id }
        assertEquals(listOf("o1", "n1", "n2", "n3"), order)
    }
}
