package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * When a video is compressed, how its encoder's progress is read, and that a
 * failed encode hands the original back rather than an error.
 */
class VideoCompressorTest {

    private val mb = 1024L * 1024

    @Test
    fun `only a video over 100 MB is compressed`() {
        assertFalse(VideoCompressor.wants("video/quicktime", 100 * mb))
        assertTrue(VideoCompressor.wants("video/quicktime", 100 * mb + 1))
        assertTrue(VideoCompressor.wants("VIDEO/MP4", 900 * mb))
        // Big, but not a video: it goes as it is.
        assertFalse(VideoCompressor.wants("application/zip", 900 * mb))
        assertFalse(VideoCompressor.wants("image/png", 900 * mb))
    }

    @Test
    fun `avconvert's newest percentage wins`() {
        val printed = "avconvert progress:  0.00% completeavconvert progress:  4.53% complete" +
            "avconvert progress:  45.17% complete"
        assertEquals(45, avconvertProgress(printed))
        assertNull(avconvertProgress("avconvert: starting"))
    }

    @Test
    fun `ffmpeg progress is the output time over the input's duration`() {
        val reader = FfmpegProgress()
        assertNull(reader.read("Input #0, mov,mp4 from 'source.mov':"))
        assertNull(reader.read("  Duration: 00:01:40.00, start: 0.000000, bitrate: 13214 kb/s\n"))
        assertEquals(25, reader.read("frame=1\nout_time_us=25000000\nprogress=continue\n"))
        // The duration is remembered once the buffer holding it is trimmed;
        // older builds spell the same microseconds `out_time_ms`.
        assertEquals(50, reader.read("out_time_ms=50000000\n"))
        assertEquals(50, reader.read("out_time_us=N/A\nout_time_ms=50000000\n"))
    }

    @Test
    fun `ffmpeg encodes H264 at constant quality, capped and fast-started`() {
        val command = ffmpegCommand("/opt/homebrew/bin/ffmpeg", "/tmp/in.mov", "/tmp/out.mp4")
        assertEquals("/opt/homebrew/bin/ffmpeg", command.first())
        assertEquals("/tmp/out.mp4", command.last())
        assertEquals("libx264", command[command.indexOf("-c:v") + 1])
        assertEquals("20", command[command.indexOf("-crf") + 1])
        assertEquals("+faststart", command[command.indexOf("-movflags") + 1])
        assertEquals(FFMPEG_SCALE, command[command.indexOf("-vf") + 1])
        // An audio-less clip must not fail the map.
        assertTrue("0:a:0?" in command)
    }

    @Test
    fun `a file no encoder can read answers null, so the original is sent`() = runTest {
        val notAVideo = ByteArray(4096) { it.toByte() }
        assertNull(VideoCompressor.compress("broken.mov", notAVideo))
    }
}
