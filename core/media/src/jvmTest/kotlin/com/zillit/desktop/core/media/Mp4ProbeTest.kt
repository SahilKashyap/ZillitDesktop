package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The length and shape reader, against the same AVFoundation-written fixtures
 * the rotation reader uses — what the preview prices its quality options from.
 */
class Mp4ProbeTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mp4/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun `length and shape come off the movie and track headers`() {
        val facts = checkNotNull(Mp4Probe.of(fixture("rot0.mp4")))
        // 1800 ticks at a timescale of 600.
        assertEquals(3.0, facts.seconds)
        assertEquals(320, facts.width)
        assertEquals(240, facts.height)
    }

    @Test
    fun `the sound track's shape is not the clip's`() {
        // This one writes audio first: a 0×0 track header, then the picture.
        // Believing the first header read reports every such clip as shapeless.
        val facts = checkNotNull(Mp4Probe.of(fixture("audio-first-rot90.mp4")))
        assertEquals(1080, facts.width)
        assertEquals(1920, facts.height)
        assertNull(facts.seconds, "this fixture is a prefix, with no movie header in it")
    }

    @Test
    fun `a rotated clip reports the pixels as stored, which is all the encoder scales`() {
        val facts = checkNotNull(Mp4Probe.of(fixture("rot90.mp4")))
        assertEquals(320, facts.width)
        assertEquals(240, facts.height)
        assertEquals(320, facts.longEdgeOrNull())
    }

    @Test
    fun `nothing readable answers null rather than a figure`() {
        assertNull(Mp4Probe.of(ByteArray(0)))
        assertNull(Mp4Probe.of(ByteArray(9)))
        assertNull(Mp4Probe.of(ByteArray(4096) { it.toByte() }), "not an MP4 at all")
        // The head of a clip whose headers sit at the end of the file.
        assertNull(Mp4Probe.of(fixture("rot0.mp4").copyOfRange(0, 64)))
    }

    @Test
    fun `what the probe reads is what the preview prices`() {
        val clip = fixture("rot0.mp4")
        val facts = checkNotNull(Mp4Probe.of(clip))
        val info = VideoInfo(facts.seconds, facts.width, facts.height)
        assertEquals(320, info.longEdge)
        // 3 s of 320×240 needs a sliver of either tier, and in any case no
        // more than the 7 KB file it already is.
        assertEquals(clip.size.toLong(), VideoSend.Hd.estimatedBytes(clip.size.toLong(), info))
    }

    private fun Mp4Facts.longEdgeOrNull(): Int? = listOfNotNull(width, height).maxOrNull()
}
