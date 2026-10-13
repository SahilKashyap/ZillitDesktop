package com.zillit.desktop.core.media

import java.io.File
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
    fun `a video over 100 MB is compressed even when nobody was asked`() {
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
    fun `ffmpeg hands H264 to the system encoder, never a bundled one`() {
        val mac = ffmpegCommand("/app/ffmpeg", "/tmp/in.mov", "/tmp/out.mp4", FfmpegTarget.MacAppleSilicon)
        assertEquals("/app/ffmpeg", mac.first())
        assertEquals("/tmp/out.mp4", mac.last())
        assertEquals("h264_videotoolbox", mac[mac.indexOf("-c:v") + 1])
        assertEquals("65", mac[mac.indexOf("-q:v") + 1])
        assertEquals("videotoolbox", mac[mac.indexOf("-hwaccel") + 1])
        assertEquals("+faststart", mac[mac.indexOf("-movflags") + 1])
        assertTrue(mac[mac.indexOf("-vf") + 1].startsWith(ffmpegScale(1920)))
        // An audio-less clip must not fail the map.
        assertTrue("0:a:0?" in mac)

        val intel = ffmpegCommand("ffmpeg", "in", "out", FfmpegTarget.MacIntel)
        assertEquals("10M", intel[intel.indexOf("-b:v") + 1])
        assertFalse("-hwaccel" in intel)

        val windows = ffmpegCommand("ffmpeg.exe", "in", "out", FfmpegTarget.Windows)
        assertEquals("h264_mf", windows[windows.indexOf("-c:v") + 1])
        assertEquals("aac", windows[windows.indexOf("-c:a") + 1])

        listOf(mac, intel, windows).forEach { command ->
            assertFalse(command.any { it == "libx264" || it == "libopenh264" }, "no bundled H.264 encoder")
        }
    }

    @Test
    fun `SD caps at 480p and spends a bitrate, HD keeps 1080p and a quality`() {
        val sd = ffmpegCommand("ffmpeg", "in", "out", FfmpegTarget.MacAppleSilicon, VideoSend.Sd)
        assertTrue(sd[sd.indexOf("-vf") + 1].startsWith(ffmpegScale(854)))
        assertEquals("1500k", sd[sd.indexOf("-b:v") + 1])
        assertEquals("128k", sd[sd.indexOf("-b:a") + 1])
        // A constant quality at 480p lands wherever the picture leads, and the
        // sender was shown a figure.
        assertFalse("-q:v" in sd)

        val hd = ffmpegCommand("ffmpeg", "in", "out", FfmpegTarget.MacAppleSilicon, VideoSend.Hd)
        assertTrue(hd[hd.indexOf("-vf") + 1].startsWith(ffmpegScale(1920)))
        assertEquals("65", hd[hd.indexOf("-q:v") + 1])
        assertEquals("160k", hd[hd.indexOf("-b:a") + 1])

        // Where no quality mode exists, both tiers name their own bitrate.
        val windowsSd = ffmpegCommand("ffmpeg.exe", "in", "out", FfmpegTarget.Windows, VideoSend.Sd)
        assertEquals("1500k", windowsSd[windowsSd.indexOf("-b:v") + 1])
        val windowsHd = ffmpegCommand("ffmpeg.exe", "in", "out", FfmpegTarget.Windows, VideoSend.Hd)
        assertEquals("10M", windowsHd[windowsHd.indexOf("-b:v") + 1])
    }

    @Test
    fun `apple's converter takes the preset nearest the tier`() {
        val sd = Avconvert(VideoSend.Sd).command(File("in.mov"), File("out.mp4"))
        assertEquals("Preset640x480", sd[sd.indexOf("--preset") + 1])
        val hd = Avconvert(VideoSend.Hd).command(File("in.mov"), File("out.mp4"))
        assertEquals("Preset1920x1080", hd[hd.indexOf("--preset") + 1])
    }

    @Test
    fun `asked for the original, nothing is encoded at all`() = runTest {
        val clip = checkNotNull(javaClass.getResourceAsStream("/mp4/rot0.mp4")).use { it.readBytes() }
        assertNull(VideoCompressor.compress("rot0.mp4", clip, quality = VideoSend.Original))
    }

    @Test
    fun `an ordinary H264 clip is not flagged as unplayable`() {
        val clip = checkNotNull(javaClass.getResourceAsStream("/mp4/rot0.mp4")).use { it.readBytes() }
        assertNull(VideoCompressor.unplayableFormat(clip))
        assertEquals("AV1", VideoCompressor.UNPLAYABLE_FORMATS["av01"])
    }

    @Test
    fun `a file no encoder can read answers null, so the original is sent`() = runTest {
        val notAVideo = ByteArray(4096) { it.toByte() }
        assertNull(VideoCompressor.compress("broken.mov", notAVideo))
    }
}
