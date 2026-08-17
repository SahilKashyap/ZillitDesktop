package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.data.readNotice
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.canBeModifiedBy
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.PickedMedia
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
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replies: reading them off the wire, and writing one.
 *
 * The behaviour worth pinning on the write side is what does *not* happen —
 * no optimistic copy (a reply that failed quietly would sit inside someone
 * else's bubble looking delivered), and no lost words on failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticeReplyTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- reading -----------------------------------------------------------

    private fun read(json: String) = readNotice(Json.parseToJsonElement(json)) { "plain:$it" }

    @Test
    fun `replies are read with their sender, time and kind`() {
        val notice = read(
            """{"_id":"n1","comments":[
                {"_id":"c1","message":"6d73g","sender":"u9","created":1700000000000,
                 "message_type":"text"}]}""",
        )!!

        val comment = notice.comments.single()
        assertEquals("c1", comment.id)
        assertEquals("plain:6d73g", comment.body)
        assertEquals("u9", comment.authorId)
        assertEquals(NoticeKind.Text, comment.kind)
    }

    @Test
    fun `a deleted reply is dropped, as the web drops it`() {
        val notice = read(
            """{"_id":"n1","comments":[
                {"_id":"c1","message":"x","deleted":1700000000000},
                {"_id":"c2","message":"y"}]}""",
        )!!

        assertEquals(listOf("c2"), notice.comments.map { it.id })
    }

    @Test
    fun `a reply can carry an attachment`() {
        val notice = read(
            """{"_id":"n1","comments":[
                {"_id":"c1","message_type":"image","attachment":{
                    "media":"chat/reply.jpg","bucket":"b","region":"r"}}]}""",
        )!!

        val comment = notice.comments.single()
        assertEquals(NoticeKind.Image, comment.kind)
        assertEquals("chat/reply.jpg", comment.attachment?.media)
    }

    @Test
    fun `a malformed reply costs itself, not its siblings`() {
        val notice = read(
            """{"_id":"n1","comments":[{"no_id":true},{"_id":"c2","message":"y"},"junk"]}""",
        )!!

        assertEquals(listOf("c2"), notice.comments.map { it.id })
    }

    @Test
    fun `the count the footer once showed is now just the list's size`() {
        assertEquals(0, read("""{"_id":"n1"}""")!!.commentCount)
        assertEquals(1, read("""{"_id":"n1","comments":[{"_id":"c1"}]}""")!!.commentCount)
    }

    // -- writing -----------------------------------------------------------

    private class FakeBoard(var failComments: Boolean = false) : HomeFeedRepository {
        val commented = mutableListOf<Triple<String, String, String>>()
        var serverComments = listOf(NoticeComment(id = "c-server", body = "merged"))

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(
                listOf(
                    Notice(
                        id = "n1",
                        body = "parent",
                        authorName = "Sam",
                        comments = listOf(
                            NoticeComment(id = "c1", body = "original words", authorId = "me"),
                        ),
                    ),
                ),
            )

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) = ZillitResult.Success(Notice(id = "server", body = text, authorName = "You"))

        override suspend fun postComment(
            noticeId: String,
            unitId: String,
            text: String,
        ): ZillitResult<List<NoticeComment>> {
            if (failComments) return ZillitResult.Failure(ZillitError.NoConnection())
            commented += Triple(noticeId, unitId, text)
            return ZillitResult.Success(serverComments)
        }

        val edited = mutableListOf<Triple<String, String, String>>()
        var failEdits = false
        var editReturns: NoticeComment? = NoticeComment(id = "c1", body = "server copy")

        override suspend fun editComment(
            noticeId: String,
            commentId: String,
            text: String,
        ): ZillitResult<NoticeComment?> {
            if (failEdits) return ZillitResult.Failure(ZillitError.NoConnection())
            edited += Triple(noticeId, commentId, text)
            return ZillitResult.Success(editReturns)
        }

        val deleted = mutableListOf<Pair<String, String>>()
        var failDeletes = false

        override suspend fun deleteComment(
            noticeId: String,
            commentId: String,
        ): ZillitResult<Unit> {
            if (failDeletes) return ZillitResult.Failure(ZillitError.NoConnection())
            deleted += noticeId to commentId
            return ZillitResult.Success(Unit)
        }

        val editedNotices = mutableListOf<Pair<String, String>>()
        var editNoticeReturns: Notice? = null

        override suspend fun editNotice(noticeId: String, text: String): ZillitResult<Notice?> {
            editedNotices += noticeId to text
            return ZillitResult.Success(editNoticeReturns)
        }

        val deletedNotices = mutableListOf<String>()
        var failNoticeDeletes = false

        override suspend fun deleteNotice(noticeId: String): ZillitResult<Unit> {
            if (failNoticeDeletes) return ZillitResult.Failure(ZillitError.NoConnection())
            deletedNotices += noticeId
            return ZillitResult.Success(Unit)
        }

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) =
            ZillitResult.Success(com.zillit.desktop.feature.home.domain.ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)
    }

    private fun viewModel(board: FakeBoard = FakeBoard()) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
        media = com.zillit.desktop.feature.home.ui.MediaCapture(
            pick = { PickedMedia("set.jpg", "image/jpeg", ByteArray(4)) },
            upload = { _, _ -> ZillitResult.Failure(ZillitError.NoConnection()) },
        ),
    ).also {
        it.onEvent(HomeFeedEvent.Load)
    }

    @Test
    fun `a reply goes to the parent and the server's list replaces the thread`() =
        runTest(dispatcher) {
            val board = FakeBoard()
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.StartReply("n1"))
            model.onEvent(HomeFeedEvent.DraftChanged("on my way"))
            model.onEvent(HomeFeedEvent.Send)
            advanceUntilIdle()

            assertEquals(Triple("n1", "u1", "on my way"), board.commented.single())
            assertEquals(
                listOf("c-server"),
                model.state.value.notices.single().comments.map { it.id },
            )
            assertNull(model.state.value.replyTo, "reply mode ends with the send")
        }

    @Test
    fun `no optimistic reply appears while the server is thinking`() = runTest(dispatcher) {
        val board = FakeBoard(failComments = true)
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartReply("n1"))
        model.onEvent(HomeFeedEvent.DraftChanged("on my way"))
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        // Only the seed reply the board loaded with — nothing was added locally.
        assertEquals(
            listOf("c1"),
            model.state.value.notices.single().comments.map { it.id },
        )
    }

    @Test
    fun `a failed reply keeps the words and the reply target`() = runTest(dispatcher) {
        val board = FakeBoard(failComments = true)
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartReply("n1"))
        model.onEvent(HomeFeedEvent.DraftChanged("on my way"))
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals("on my way", model.state.value.draft.text)
        assertEquals("n1", model.state.value.replyTo?.id)
        assertTrue(model.state.value.error != null)
    }

    @Test
    fun `entering reply mode drops an attached file, visibly`() = runTest(dispatcher) {
        // Replies are text on this endpoint. Silently posting the file as its
        // own notice would be the worse surprise.
        val model = viewModel()
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartReply("n1"))
        advanceUntilIdle()

        assertNull(model.state.value.draft.media)
    }

    @Test
    fun `cancelling the reply returns the composer to posting`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.StartReply("n1"))

        model.onEvent(HomeFeedEvent.CancelReply)
        advanceUntilIdle()

        assertNull(model.state.value.replyTo)
    }

    @Test
    fun `switching unit abandons the reply target`() = runTest(dispatcher) {
        // The parent lives on the unit being left; keeping the target would
        // send the reply to a post the user can no longer see.
        val model = viewModel()
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.StartReply("n1"))

        model.onEvent(HomeFeedEvent.SelectUnit("u1"))
        advanceUntilIdle()

        assertNull(model.state.value.replyTo)
    }

    // -- editing -----------------------------------------------------------

    @Test
    fun `editing loads the reply's words and saves through the composer`() =
        runTest(dispatcher) {
            val board = FakeBoard()
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.StartEditComment("n1", "c1"))
            assertEquals("original words", model.state.value.draft.text)

            model.onEvent(HomeFeedEvent.DraftChanged("corrected words"))
            model.onEvent(HomeFeedEvent.Send)
            advanceUntilIdle()

            assertEquals(Triple("n1", "c1", "corrected words"), board.edited.single())
            // The server's copy replaces the local one.
            assertEquals(
                "server copy",
                model.state.value.notices.single().comments.single().body,
            )
            assertNull(model.state.value.editing)
        }

    @Test
    fun `starting an edit stashes the half-typed post and cancel restores it`() =
        runTest(dispatcher) {
            // Borrowing the composer must not steal a post being written.
            val model = viewModel()
            advanceUntilIdle()
            model.onEvent(HomeFeedEvent.DraftChanged("half a notice"))

            model.onEvent(HomeFeedEvent.StartEditComment("n1", "c1"))
            assertEquals("original words", model.state.value.draft.text)

            model.onEvent(HomeFeedEvent.CancelEditComment)
            advanceUntilIdle()

            assertEquals("half a notice", model.state.value.draft.text)
            assertNull(model.state.value.editing)
        }

    @Test
    fun `a failed edit keeps the rewrite in the composer`() = runTest(dispatcher) {
        val board = FakeBoard().apply { failEdits = true }
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.StartEditComment("n1", "c1"))
        model.onEvent(HomeFeedEvent.DraftChanged("corrected words"))

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals("corrected words", model.state.value.draft.text)
        assertEquals("n1", model.state.value.editing?.noticeId)
        // The board still shows the original until the save lands.
        assertEquals(
            "original words",
            model.state.value.notices.single().comments.single().body,
        )
    }

    @Test
    fun `starting an edit ends reply mode, and vice versa`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartReply("n1"))
        model.onEvent(HomeFeedEvent.StartEditComment("n1", "c1"))
        assertNull(model.state.value.replyTo)

        model.onEvent(HomeFeedEvent.StartReply("n1"))
        assertNull(model.state.value.editing)
    }

    // -- deleting ----------------------------------------------------------

    @Test
    fun `delete removes the reply at once`() = runTest(dispatcher) {
        // Waiting for the server after a confirmed click reads as a broken
        // button on a desktop.
        val board = FakeBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.DeleteComment("n1", "c1"))

        assertTrue(model.state.value.notices.single().comments.isEmpty())
        advanceUntilIdle()
        assertEquals("n1" to "c1", board.deleted.single())
    }

    @Test
    fun `a failed delete puts the reply back`() = runTest(dispatcher) {
        val board = FakeBoard().apply { failDeletes = true }
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.DeleteComment("n1", "c1"))
        advanceUntilIdle()

        assertEquals(
            listOf("c1"),
            model.state.value.notices.single().comments.map { it.id },
        )
        assertTrue(model.state.value.error != null)
    }

    // -- who may ------------------------------------------------------------

    @Test
    fun `the rule is the web's rule`() {
        val now = 1_800_000_000_000L
        val fresh = NoticeComment(id = "c", body = "b", authorId = "me", createdAtMillis = now)
        val stale = fresh.copy(createdAtMillis = now - 31 * 60 * 1000L)
        val someoneElses = fresh.copy(authorId = "them")

        // Own and fresh: yes. Own and old: no. Someone else's: no.
        assertTrue(fresh.canBeModifiedBy("me", isAdmin = false, nowMillis = now))
        assertTrue(!stale.canBeModifiedBy("me", isAdmin = false, nowMillis = now))
        assertTrue(!someoneElses.canBeModifiedBy("me", isAdmin = false, nowMillis = now))

        // An admin may touch anything, any age.
        assertTrue(stale.canBeModifiedBy("admin", isAdmin = true, nowMillis = now))
        assertTrue(someoneElses.canBeModifiedBy("admin", isAdmin = true, nowMillis = now))

        // Nobody signed in, nobody may.
        assertTrue(!fresh.canBeModifiedBy(null, isAdmin = false, nowMillis = now))
    }

    // -- editing and deleting the post itself ------------------------------

    @Test
    fun `editing a post saves through the composer and keeps its replies`() =
        runTest(dispatcher) {
            val board = FakeBoard().apply {
                editNoticeReturns = Notice(id = "n1", body = "server copy", authorName = "Sam")
            }
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.StartEditNotice("n1"))
            assertEquals("parent", model.state.value.draft.text)

            model.onEvent(HomeFeedEvent.DraftChanged("parent, corrected"))
            model.onEvent(HomeFeedEvent.Send)
            advanceUntilIdle()

            assertEquals("n1" to "parent, corrected", board.editedNotices.single())
            val notice = model.state.value.notices.single()
            assertEquals("server copy", notice.body)
            // The edit response carries no replies; the ones on screen survive.
            assertEquals(listOf("c1"), notice.comments.map { it.id })
        }

    @Test
    fun `deleting a post removes it at once and restores it on failure`() =
        runTest(dispatcher) {
            val board = FakeBoard().apply { failNoticeDeletes = true }
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.DeleteNotice("n1"))
            assertTrue(model.state.value.notices.isEmpty())

            advanceUntilIdle()
            assertEquals(listOf("n1"), model.state.value.notices.map { it.id })
            assertTrue(model.state.value.error != null)
        }

    @Test
    fun `the post rule needs a server-taken post`() {
        val now = 1_800_000_000_000L
        val sent = Notice(id = "n", body = "b", authorName = "A", authorId = "me", createdAtMillis = now)
        val inFlight = sent.copy(sendState = NoticeSendState.Sending)

        assertTrue(sent.canBeModifiedBy("me", isAdmin = false, nowMillis = now))
        // An optimistic card has no server id to address; retry is its path.
        assertTrue(!inFlight.canBeModifiedBy("me", isAdmin = false, nowMillis = now))
        assertTrue(!inFlight.canBeModifiedBy("admin", isAdmin = true, nowMillis = now))
    }

    @Test
    fun `switching edit targets keeps the original stash`() = runTest(dispatcher) {
        // Post edit, then comment edit, then cancel — the words typed before
        // any edit began are what comes back.
        val model = viewModel()
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.DraftChanged("half a notice"))

        model.onEvent(HomeFeedEvent.StartEditNotice("n1"))
        model.onEvent(HomeFeedEvent.StartEditComment("n1", "c1"))
        model.onEvent(HomeFeedEvent.CancelEditComment)
        advanceUntilIdle()

        assertEquals("half a notice", model.state.value.draft.text)
    }
}
