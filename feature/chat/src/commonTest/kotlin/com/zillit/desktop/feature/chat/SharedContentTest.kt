package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.neighboursOf
import com.zillit.desktop.feature.chat.domain.sharedContent
import com.zillit.desktop.feature.chat.ui.byteLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Media, links and docs": what a conversation's messages sort into. No
 * endpoint lists a conversation's files, so this sort is the whole feature.
 */
class SharedContentTest {

    private fun message(
        id: String,
        at: Long,
        body: String = "",
        file: ChatAttachment? = null,
        location: ChatLocation? = null,
    ) = ChatMessage(
        id = id,
        uniqueId = "u-$id",
        senderId = "them",
        receiverId = "me",
        body = body,
        timestampMillis = at,
        isMine = false,
        attachment = file,
        location = location,
    )

    private fun file(name: String, type: String) = ChatAttachment(media = "chat/$name", name = name, contentType = type)

    @Test
    fun `pictures and clips are media, everything else with a file is a doc`() {
        val shared = sharedContent(
            listOf(
                message("1", 1, file = file("still.jpg", "image/jpeg")),
                message("2", 2, file = file("take.mp4", "video/mp4")),
                message("3", 3, file = file("sheet.pdf", "application/pdf")),
                message("4", 4, file = file("note.m4a", "audio/mp4")),
            ),
        )

        assertEquals(listOf("take.mp4", "still.jpg"), shared.media.map { it.file.name })
        assertEquals(listOf("note.m4a", "sheet.pdf"), shared.docs.map { it.file.name })
        assertEquals(4, shared.total)
    }

    @Test
    fun `newest first, whatever order the thread holds them in`() {
        val shared = sharedContent(
            listOf(
                message("old", 10, file = file("a.jpg", "image/jpeg")),
                message("new", 30, file = file("c.jpg", "image/jpeg")),
                message("mid", 20, file = file("b.jpg", "image/jpeg")),
            ),
        )

        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), shared.media.map { it.file.name })
    }

    @Test
    fun `web addresses are links, email addresses are not`() {
        val shared = sharedContent(
            listOf(
                message(
                    "1",
                    1,
                    body = "Recce photos at https://example.com/recce and www.zillit.com, ask ops@zillit.com",
                ),
            ),
        )

        assertEquals(listOf("https://example.com/recce", "https://www.zillit.com"), shared.links.map { it.url })
        assertTrue(shared.links.all { it.body.startsWith("Recce photos") })
    }

    @Test
    fun `a shared place and a file still uploading are not listed`() {
        val shared = sharedContent(
            listOf(
                message("1", 1, file = file("map.png", "image/png"), location = ChatLocation(lat = 1.0, lng = 2.0)),
                message("2", 2, file = ChatAttachment(media = "", name = "pending.jpg", contentType = "image/jpeg")),
            ),
        )

        assertEquals(0, shared.total)
    }

    @Test
    fun `the same message held twice is listed once`() {
        val twice = message("1", 1, file = file("still.jpg", "image/jpeg"))
        assertEquals(1, sharedContent(listOf(twice, twice)).media.size)
    }

    @Test
    fun `sizes read as WhatsApp prints them`() {
        assertEquals("512 B", byteLabel(512))
        assertEquals("113 kB", byteLabel(113 * 1024L))
        assertEquals("2.4 MB", byteLabel(2_516_582L))
    }

    @Test
    fun `the viewer's arrows step through the conversation's media, left older and right newer`() {
        val shared = sharedContent(
            listOf(
                message("1", 1, file = file("a.jpg", "image/jpeg")),
                message("2", 2, file = file("b.pdf", "application/pdf")),
                message("3", 3, file = file("c.mp4", "video/mp4")),
                message("4", 4, file = file("d.jpg", "image/jpeg")),
            ),
        )

        val middle = shared.neighboursOf("chat/c.mp4")
        assertEquals("a.jpg", middle.older?.name)
        assertEquals("d.jpg", middle.newer?.name)
        assertNull(shared.neighboursOf("chat/a.jpg").older)
        assertNull(shared.neighboursOf("chat/d.jpg").newer)
        // A document is not on the run: no arrows rather than a wrong one.
        assertNull(shared.neighboursOf("chat/b.pdf").newer)
    }
}
