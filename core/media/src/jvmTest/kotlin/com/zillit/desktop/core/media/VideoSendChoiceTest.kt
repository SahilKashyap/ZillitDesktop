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
import kotlin.test.assertTrue

/**
 * Every video is offered a quality, Original is where it sits until the
 * sender says otherwise, and the answer rides the result to the uploader.
 */
class VideoSendChoiceTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    private val mb = 1024L * 1024

    @Test
    fun `every video is offered a quality, whatever it weighs`() {
        val session = MediaPreviewSession(emptyList())
        assertTrue(session.offersVideoChoice(PreviewItem("rushes.mov", "video/quicktime", ByteArray(1024))))
        assertTrue(session.offersVideoChoice(PreviewItem("clip.mp4", "video/mp4", ByteArray(8))))
        assertFalse(session.offersVideoChoice(PreviewItem("plates.zip", "application/zip", ByteArray(1024))))
        assertFalse(session.offersVideoChoice(PreviewItem("still.jpg", "image/jpeg", ByteArray(1024))))
    }

    @Test
    fun `a video sits at Original until the sender picks, and each answer rides its result`() {
        val first = PreviewItem("a.mov", "video/quicktime", ByteArray(64))
        val still = PreviewItem("b.jpg", "image/jpeg", ByteArray(8))
        val second = PreviewItem("c.mov", "video/quicktime", ByteArray(64))
        val session = MediaPreviewSession(listOf(first, still, second))

        assertEquals(VideoSend.Original, session.videoChoice(first), "nothing is re-encoded unasked")
        assertNull(session.videoChoice(still), "a photo has no quality to pick")
        session.chooseVideo(second, VideoSend.Sd)

        val results = assertNotNull(session.results(measurer))
        assertEquals(listOf(VideoSend.Original, null, VideoSend.Sd), results.map { it.videoSend })
    }

    @Test
    fun `a clip most players cannot draw starts on HD instead`() {
        val av1 = PreviewItem("clip.mp4", "video/mp4", ByteArray(1024))
        val session = MediaPreviewSession(listOf(av1))
        assertEquals(VideoSend.Original, session.videoChoice(av1), "before the header is read")

        session.videoInfos[av1] = VideoInfo(unplayableFormat = "AV1")
        assertEquals(VideoSend.Hd, session.videoChoice(av1), "sent as it is, most people would see nothing")
        assertEquals("AV1", session.unplayableFormat(av1))

        // The sender can still insist on the file as it was shot.
        session.chooseVideo(av1, VideoSend.Original)
        assertEquals(VideoSend.Original, session.videoChoice(av1))
    }

    @Test
    fun `a machine with no encoder offers only the original`() {
        val video = PreviewItem("a.mov", "video/quicktime", ByteArray(64))
        val session = MediaPreviewSession(listOf(video))
        session.videoInfos[video] = VideoInfo(unplayableFormat = "AV1", compressible = false)
        assertEquals(VideoSend.Original, session.videoChoice(video), "it is the only one on offer")
    }

    @Test
    fun `an estimate is bitrate times length, never more than the file itself`() {
        // 100 s of 4K: far above either tier, so both get their full bitrate.
        val uhd = VideoInfo(durationSeconds = 100.0, width = 3840, height = 2160)
        assertEquals(19_625_000L, VideoSend.Sd.estimatedBytes(500 * mb, uhd))
        assertEquals(91_250_000L, VideoSend.Hd.estimatedBytes(500 * mb, uhd))
        assertEquals(500 * mb, VideoSend.Original.estimatedBytes(500 * mb, uhd), "the original is known exactly")
    }

    @Test
    fun `a clip already smaller than the tier is not priced as if it were bigger`() {
        val sd = VideoInfo(durationSeconds = 60.0, width = 960, height = 540)
        // Half of 1080p's width is a quarter of its pixels, so a quarter of the bits.
        assertEquals(13_687_500L, VideoSend.Hd.estimatedBytes(30 * mb, sd))
        // 960 is above SD's 854, so SD still spends its own full bitrate.
        assertEquals(11_775_000L, VideoSend.Sd.estimatedBytes(30 * mb, sd))
    }

    @Test
    fun `an estimate is never larger than the original, and never zero`() {
        // An efficient 20 MB clip: either tier's bitrate would "cost" more
        // than the file, and a compressor that cannot shrink it sends it as is.
        val info = VideoInfo(durationSeconds = 120.0, width = 1920, height = 1080)
        assertEquals(20 * mb, VideoSend.Hd.estimatedBytes(20 * mb, info))
        assertEquals(20 * mb, VideoSend.Sd.estimatedBytes(20 * mb, info))
        // A clip of no length at all still shows something rather than "0 B".
        assertTrue(assertNotNull(VideoSend.Sd.estimatedBytes(mb, VideoInfo(durationSeconds = 0.001))) > 0)
    }

    @Test
    fun `a clip nobody can draw may be priced above the file it replaces`() {
        // Measured: a 894 KB AV1 clip, 6 s of 720p, converts to 1.0 MB at SD
        // and 2.3 MB at HD. Its H.264 is kept whatever it weighs, so clamping
        // the figure to the source would promise a size the send exceeds.
        val av1 = VideoInfo(durationSeconds = 6.0, width = 1280, height = 720, unplayableFormat = "AV1")
        val source = 894_455L
        assertEquals(1_177_500L, VideoSend.Sd.estimatedBytes(source, av1))
        assertEquals(2_433_333L, VideoSend.Hd.estimatedBytes(source, av1))

        // The same clip in a format that plays is clamped, because a result
        // no smaller than the original is thrown away and the original sent.
        val h264 = VideoInfo(durationSeconds = 6.0, width = 1280, height = 720)
        assertEquals(source, VideoSend.Hd.estimatedBytes(source, h264))
    }

    @Test
    fun `without a length there is no figure to show`() {
        assertNull(VideoSend.Sd.estimatedBytes(mb, null))
        assertNull(VideoSend.Hd.estimatedBytes(mb, VideoInfo(width = 1920, height = 1080)))
        // The original always has one: it is the file in hand.
        assertEquals(1024L, VideoSend.Original.estimatedBytes(1024L, null))
    }

    @Test
    fun `a removed video takes its answer and its header with it`() {
        val keep = PreviewItem("keep.jpg", "image/jpeg", ByteArray(8))
        val video = PreviewItem("a.mov", "video/quicktime", ByteArray(64))
        val session = MediaPreviewSession(listOf(keep, video))
        session.videoInfos[video] = VideoInfo(durationSeconds = 10.0)
        session.chooseVideo(video, VideoSend.Sd)
        session.select(1)
        session.removeCurrent()
        assertNull(session.videoInfo(video))
        assertEquals(VideoSend.Original, session.videoChoice(video), "its SD answer went with it")
    }
}
