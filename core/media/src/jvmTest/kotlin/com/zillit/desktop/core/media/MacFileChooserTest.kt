package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The native chooser's script and answer, and Windows' pattern — the two filters AWT actually honours. */
class MacFileChooserTest {

    @Test
    fun `a photo pick restricts the panel to images and allows several`() {
        val script = chooseFileScript(PreviewKind.Image, multiple = true)

        assertEquals(
            "set chosen to (choose file of type {\"public.image\"} with prompt \"Choose photos\" " +
                "with multiple selections allowed)",
            script.first(),
        )
        assertEquals("return out", script.last())
    }

    @Test
    fun `a single pick is wrapped in a list so the loop still runs`() {
        val script = chooseFileScript(PreviewKind.Video, multiple = false)

        assertEquals(
            "set chosen to {(choose file of type {\"public.movie\", \"public.video\"} with prompt \"Choose videos\")}",
            script.first(),
        )
    }

    @Test
    fun `a document pick is open, as on the phones`() {
        val script = chooseFileScript(PreviewKind.Document, multiple = true)

        assertTrue(script.first().startsWith("set chosen to (choose file with prompt"))
        assertNull(PreviewKind.Document.uniformTypes)
    }

    @Test
    fun `the panel's answer is one path per line`() {
        assertEquals(
            listOf("/Users/me/a photo.jpg", "/Users/me/b.png"),
            parseChosenPaths("/Users/me/a photo.jpg\n/Users/me/b.png\n\n"),
        )
        assertTrue(parseChosenPaths("").isEmpty())
    }

    @Test
    fun `windows gets the dialog's pattern, and documents none`() {
        assertEquals("*.mp3;*.wav;*.m4a;*.aac;*.ogg;*.flac;*.aiff;*.caf", PreviewKind.Audio.windowsFilePattern())
        assertNull(PreviewKind.Document.windowsFilePattern())
    }
}
