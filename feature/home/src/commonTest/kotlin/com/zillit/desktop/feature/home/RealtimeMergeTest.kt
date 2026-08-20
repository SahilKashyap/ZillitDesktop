package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.rightsTargetUserId
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.applyRealtime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merging socket events into the board on screen.
 *
 * The server broadcasts a post back to the client that wrote it, so the
 * duplicate case is the one this exists for.
 */
class RealtimeMergeTest {

    private val unit = "u1"

    private fun notice(
        id: String,
        at: Long = 100,
        localId: String? = null,
        state: NoticeSendState = NoticeSendState.Sent,
    ) = Notice(
        id = id,
        body = "body-$id",
        authorName = "A",
        createdAtMillis = at,
        sendState = state,
        localId = localId,
    )

    @Test
    fun `a colleague's post appears`() {
        val board = listOf(notice("a", at = 100))

        val merged = board.applyRealtime(
            HomeRealtimeEvent.NoticeAdded(unit, notice("b", at = 200)),
            selectedUnitId = unit,
        )

        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun `my own post does not appear twice`() {
        // The optimistic card carries the `unique_id` we sent; the broadcast
        // carries it back. Without matching on it the author sees two cards.
        val optimistic = notice("local-1", localId = "local-1", state = NoticeSendState.Sending)
        val fromServer = notice("server-99", localId = "local-1")

        val merged = listOf(optimistic).applyRealtime(
            HomeRealtimeEvent.NoticeAdded(unit, fromServer),
            selectedUnitId = unit,
        )

        assertEquals(1, merged.size, "the author's own post was duplicated")
        assertEquals("server-99", merged.single().id, "the server copy should win")
    }

    @Test
    fun `the broadcast clears the sending state`() {
        val optimistic = notice("local-1", localId = "local-1", state = NoticeSendState.Sending)

        val merged = listOf(optimistic).applyRealtime(
            HomeRealtimeEvent.NoticeAdded(unit, notice("s1", localId = "local-1")),
            selectedUnitId = unit,
        )

        assertEquals(NoticeSendState.Sent, merged.single().sendState)
    }

    @Test
    fun `a post for another unit is ignored`() {
        // The board is refetched on tab change; merging would grow a list
        // nobody is looking at.
        val board = listOf(notice("a"))

        val merged = board.applyRealtime(
            HomeRealtimeEvent.NoticeAdded("other-unit", notice("b")),
            selectedUnitId = unit,
        )

        assertEquals(listOf("a"), merged.map { it.id })
    }

    @Test
    fun `an unscoped event is accepted`() {
        // Some events omit the unit id.
        val merged = listOf(notice("a")).applyRealtime(
            HomeRealtimeEvent.NoticeAdded(null, notice("b")),
            selectedUnitId = unit,
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `an incoming post lands in date order, not at the end`() {
        val board = listOf(notice("old", at = 100), notice("new", at = 300))

        val merged = board.applyRealtime(
            HomeRealtimeEvent.NoticeAdded(unit, notice("middle", at = 200)),
            selectedUnitId = unit,
        )

        assertEquals(listOf("old", "middle", "new"), merged.map { it.id })
    }

    @Test
    fun `an edit replaces in place`() {
        val board = listOf(notice("a"), notice("b"))
        val edited = notice("a").copy(body = "corrected", isEdited = true)

        val merged = board.applyRealtime(HomeRealtimeEvent.NoticeEdited(unit, edited), unit)

        assertEquals("corrected", merged.first().body)
        assertEquals(2, merged.size)
    }

    @Test
    fun `an edit to something not loaded does not insert it`() {
        // An edit is not an invitation to pull in a post from outside the page.
        val board = listOf(notice("a"))

        val merged = board.applyRealtime(HomeRealtimeEvent.NoticeEdited(unit, notice("z")), unit)

        assertEquals(listOf("a"), merged.map { it.id })
    }

    @Test
    fun `a delete removes the post`() {
        val board = listOf(notice("a"), notice("b"))

        val merged = board.applyRealtime(HomeRealtimeEvent.NoticeDeleted(unit, "a"), unit)

        assertEquals(listOf("b"), merged.map { it.id })
    }

    @Test
    fun `deleting something absent is harmless`() {
        val board = listOf(notice("a"))

        assertEquals(board, board.applyRealtime(HomeRealtimeEvent.NoticeDeleted(unit, "gone"), unit))
    }

    @Test
    fun `a units change leaves the board alone`() {
        // It triggers a refetch of the tabs; the posts on screen are unaffected.
        val board = listOf(notice("a"))

        assertEquals(board, board.applyRealtime(HomeRealtimeEvent.UnitsChanged, unit))
    }

    @Test
    fun `pinned order survives a merge`() {
        val board = listOf(notice("pinned", at = 10).copy(isPinned = true), notice("a", at = 100))

        val merged = board.applyRealtime(
            HomeRealtimeEvent.NoticeAdded(unit, notice("b", at = 200)),
            selectedUnitId = unit,
        )

        assertTrue(merged.first().isPinned, "a pinned notice should stay at the top")
    }

    @Test
    fun `an access-grid payload names its target, array or bare`() {
        // Android's trio of rights events: an array whose first element says
        // whose rights moved, as `user_id` or `_id`. Only that person's board
        // refetches — everyone else's rights are not this board's business.
        assertEquals(
            "me123",
            Json.parseToJsonElement("""[{"unit_id":"u1","user_id":"me123","enabled":false}]""")
                .rightsTargetUserId(),
        )
        assertEquals(
            "me123",
            Json.parseToJsonElement("""{"_id":"me123"}""").rightsTargetUserId(),
        )
        assertNull(Json.parseToJsonElement("\"noise\"").rightsTargetUserId())
    }
}
