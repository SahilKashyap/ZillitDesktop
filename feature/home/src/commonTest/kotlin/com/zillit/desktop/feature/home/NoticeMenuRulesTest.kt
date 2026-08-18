package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.ModifyVerdict
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.asWebHref
import com.zillit.desktop.feature.home.domain.editVerdict
import com.zillit.desktop.feature.home.domain.firstUrlIn
import com.zillit.desktop.feature.home.domain.isEditableBy
import com.zillit.desktop.feature.home.domain.toLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The post menu's rules, as Android's `Home.kt` has them: who may edit a
 * post, and what the Gallery sorts the board into.
 */
class NoticeMenuRulesTest {

    private val now = 1_800_000_000_000L
    private val mine = Notice(id = "n1", body = "words", authorName = "Me", authorId = "me", createdAtMillis = now)
    private val theirs = mine.copy(id = "n2", authorId = "them")

    /**
     * The server's rule for `PUT home/chat/{id}` (edit and pin): the author,
     * whatever their rights — an admin on someone else's post is refused, the
     * author's own old post is taken (found live). Thirty minutes is the
     * client's clock, as on both phones.
     */
    @Test
    fun `a post is editable by its author alone, within the window`() {
        assertEquals(ModifyVerdict.Allowed, mine.editVerdict("me", nowMillis = now))
        assertEquals(ModifyVerdict.NotOwner, theirs.editVerdict("me", nowMillis = now))
        assertEquals(ModifyVerdict.NotOwner, theirs.editVerdict("admin", nowMillis = now))

        val old = now - 31 * 60 * 1000L
        assertEquals(ModifyVerdict.WindowClosed, mine.copy(createdAtMillis = old).editVerdict("me", now))

        val inFlight = mine.copy(sendState = NoticeSendState.Sending)
        assertEquals(ModifyVerdict.NotSent, inFlight.editVerdict("me", nowMillis = now))

        // The menu item — Edit and Pin — follows the same person, untimed.
        assertTrue(mine.isEditableBy("me"))
        assertFalse(theirs.isEditableBy("me"))
        assertFalse(inFlight.isEditableBy("me"))
    }

    /** Android `HomeChatLibraryActivity.scanList`: three buckets, newest first. */
    @Test
    fun `the gallery sorts the board into media, docs and links, newest first`() {
        val file = NoticeAttachment(media = "k/1.jpg", fileName = "1.jpg", bucket = "b", region = "r")
        val clip = file.copy(media = "2.mp4")
        val pdf = file.copy(media = "3.pdf")
        val board = listOf(
            mine.copy(id = "img", kind = NoticeKind.Image, attachment = file, createdAtMillis = 10),
            mine.copy(id = "vid", kind = NoticeKind.Video, attachment = clip, createdAtMillis = 30),
            mine.copy(id = "doc", kind = NoticeKind.Document, attachment = pdf, createdAtMillis = 20),
            mine.copy(id = "voice", kind = NoticeKind.Audio, attachment = file.copy(media = "k/4.m4a")),
            mine.copy(id = "link", body = "see https://zillit.com/docs. now", createdAtMillis = 5),
            mine.copy(id = "www", body = "www.example.com/x)", createdAtMillis = 6),
            mine.copy(id = "plain", body = "no link here"),
            mine.copy(id = "unsent", kind = NoticeKind.Image, attachment = file, sendState = NoticeSendState.Sending),
            mine.copy(id = "nofile", kind = NoticeKind.Image, attachment = null),
        )

        val library = board.toLibrary()

        assertEquals(listOf("vid", "img"), library.media.map { it.notice.id })
        assertTrue(library.media.first().isVideo)
        assertEquals(listOf("doc"), library.docs.map { it.notice.id })
        assertEquals(listOf("www", "link"), library.links.map { it.notice.id })
        assertEquals(listOf("www.example.com/x", "https://zillit.com/docs"), library.links.map { it.url })
        assertFalse(library.isEmpty)
    }

    @Test
    fun `a link is found as Android finds it, and www gets its scheme`() {
        assertEquals("https://a.b/c", firstUrlIn("go to https://a.b/c, please"))
        assertEquals("www.a.b", firstUrlIn("(www.a.b)"))
        assertNull(firstUrlIn("nothing to click"))
        assertEquals("https://www.a.b", "www.a.b".asWebHref())
        assertEquals("http://a.b", "http://a.b".asWebHref())
    }
}
