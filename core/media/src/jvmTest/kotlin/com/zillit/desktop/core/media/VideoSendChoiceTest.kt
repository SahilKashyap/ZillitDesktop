package com.zillit.desktop.core.media

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A large video asks how it goes; Send waits for the answer, and the answer rides the result. */
class VideoSendChoiceTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    // One array for every large item: 100 MB a piece would be 100 MB a piece.
    private val large = ByteArray((LARGE_VIDEO_BYTES + 1).toInt())

    @Test
    fun `only a video over 100 MB asks`() {
        assertTrue(PreviewItem("rushes.mov", "video/quicktime", large).asksVideoChoice)
        assertFalse(PreviewItem("clip.mov", "video/quicktime", ByteArray(1024)).asksVideoChoice)
        assertFalse(PreviewItem("plates.zip", "application/zip", large).asksVideoChoice)
    }

    @Test
    fun `send waits for every large video, and each answer rides its result`() {
        val first = PreviewItem("a.mov", "video/quicktime", large)
        val small = PreviewItem("b.mov", "video/quicktime", ByteArray(8))
        val second = PreviewItem("c.mov", "video/quicktime", large)
        val session = MediaPreviewSession(listOf(first, small, second))

        assertSame(first, session.awaitingVideoChoice)
        session.chooseVideo(first, VideoSend.Original)
        assertSame(second, session.awaitingVideoChoice, "the small one is never asked about")
        session.chooseVideo(second, VideoSend.Compressed)
        assertNull(session.awaitingVideoChoice)

        val results = assertNotNull(session.results(measurer))
        assertEquals(listOf(VideoSend.Original, null, VideoSend.Compressed), results.map { it.videoSend })
    }

    @Test
    fun `a small video in a format most players cannot draw is asked about too`() {
        val av1 = PreviewItem("clip.mp4", "video/mp4", ByteArray(1024))
        val session = MediaPreviewSession(listOf(av1))
        assertNull(session.awaitingVideoChoice, "an ordinary small clip just goes")
        session.posters[av1] = PreviewPoster(jpegBytes = null, unplayableFormat = "AV1")
        assertSame(av1, session.awaitingVideoChoice)
        assertEquals("AV1", session.unplayableFormat(av1))
    }

    @Test
    fun `a removed video stops holding send back`() {
        val keep = PreviewItem("keep.jpg", "image/jpeg", ByteArray(8))
        val video = PreviewItem("a.mov", "video/quicktime", large)
        val session = MediaPreviewSession(listOf(keep, video))
        session.select(1)
        session.removeCurrent()
        assertNull(session.awaitingVideoChoice)
    }
}
