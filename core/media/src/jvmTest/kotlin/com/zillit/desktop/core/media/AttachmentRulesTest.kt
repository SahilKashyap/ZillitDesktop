package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The attach sheet's vocabulary and the picker's rules.
 *
 * The rules are the phones': four kinds in their order, closed extension sets
 * for the three media kinds, an open one for documents, and a check *after*
 * the OS dialog — because that dialog's filter is advisory, and a `.txt`
 * renamed `.mp4` must not reach a board as a video.
 */
class AttachmentRulesTest {

    @Test
    fun `the sheet offers the phones' four kinds, in their order`() {
        assertEquals(
            listOf(PreviewKind.Image, PreviewKind.Video, PreviewKind.Document, PreviewKind.Audio),
            ALL_ATTACHMENT_KINDS,
        )
        assertEquals(listOf("Photo", "Video", "Document", "Audio"), ALL_ATTACHMENT_KINDS.map { it.label })
    }

    @Test
    fun `media kinds are closed sets and documents are not`() {
        assertTrue("jpg" in PreviewKind.Image.extensions!!)
        assertTrue("mov" in PreviewKind.Video.extensions!!)
        assertTrue("m4a" in PreviewKind.Audio.extensions!!)
        assertNull(PreviewKind.Document.extensions, "a call sheet is as likely .docx as .pdf")
    }

    @Test
    fun `a kind admits its own files by extension or by type`() {
        assertTrue(PreviewKind.Image.admits("door.JPG", "application/octet-stream"), "extension wins")
        assertTrue(PreviewKind.Image.admits("door", "image/png"), "type wins")
        assertTrue(PreviewKind.Document.admits("sheet.docx", "application/octet-stream"), "documents take anything")
    }

    @Test
    fun `a file picked under the wrong kind is refused`() {
        // The OS filter is advisory on macOS; this is the rule.
        assertFalse(PreviewKind.Video.admits("notes.txt", "text/plain"))
        assertFalse(PreviewKind.Image.admits("take.mp4", "video/mp4"))
        assertFalse(PreviewKind.Audio.admits("scan.pdf", "application/pdf"))
    }

    @Test
    fun `a type is read off the extension when the platform's table misses it`() {
        // Windows' table types a photo as octet-stream, which classifies it as
        // a document — no preview, no edit tools, a file chip on the board.
        assertEquals("image/heic", contentTypeFor("selfie.HEIC", null))
        assertEquals("video/quicktime", contentTypeFor("take.mov", null))
        assertEquals("application/pdf", contentTypeFor("sheet.pdf", null))
        assertEquals("text/csv", contentTypeFor("crew.csv", null))
    }

    @Test
    fun `the platform's answer is preferred when it has one`() {
        assertEquals("image/png", contentTypeFor("whatever.jpg", "image/png"))
    }

    @Test
    fun `an unknown extension is octet-stream, never a guess`() {
        assertEquals("application/octet-stream", contentTypeFor("mystery.zzz", null))
        assertEquals("application/octet-stream", contentTypeFor("noext", null))
    }

    @Test
    fun `a picked file classifies itself and never prints its bytes`() {
        val picked = PickedFile("door.jpg", "image/jpeg", ByteArray(4096))

        assertEquals(PreviewKind.Image, picked.kind)
        assertFalse(picked.toString().contains("["), picked.toString())
        assertTrue(picked.toString().contains("4096"))
    }
}
