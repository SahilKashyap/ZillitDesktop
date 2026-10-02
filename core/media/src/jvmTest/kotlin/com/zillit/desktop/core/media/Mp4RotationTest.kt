package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rotation reader, against real files rather than hand-built boxes.
 *
 * The four fixtures were written by AVFoundation with
 * `AVAssetWriterInput.transform` set to each quarter turn — the same way a
 * phone writes one, which is the only thing that matters here: a parser that
 * agrees with a synthetic box and disagrees with a camera is no use.
 */
class Mp4RotationTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mp4/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun `every quarter turn is read back`() {
        assertEquals(0, Mp4Rotation.of(fixture("rot0.mp4")))
        assertEquals(90, Mp4Rotation.of(fixture("rot90.mp4")))
        assertEquals(180, Mp4Rotation.of(fixture("rot180.mp4")))
        assertEquals(270, Mp4Rotation.of(fixture("rot270.mp4")))
    }

    @Test
    fun `a truncated file is upright rather than wrong`() {
        // The viewer asks for a prefix of the object, and a clip whose moov
        // sits at the end will hand back bytes with no track header in them.
        // Nothing to read means leave the picture alone — a guess here shows
        // every upright video on its side.
        val head = fixture("rot90.mp4").copyOfRange(0, 64)
        assertEquals(0, Mp4Rotation.of(head))
        assertEquals(0, Mp4Rotation.of(ByteArray(0)))
        assertEquals(0, Mp4Rotation.of(ByteArray(9)))
    }

    @Test
    fun `an audio track first does not answer for the video`() {
        // The real defect this guards (dev app, 2026-10-01): a clip whose
        // sound track comes first read as upright and played on its side,
        // because an audio `tkhd` is zero by zero and always carries the
        // identity matrix. Only a track with a shape can be rotated.
        assertEquals(90, Mp4Rotation.of(fixture("audio-first-rot90.mp4")))
    }

    @Test
    fun `a tail range finds the moov it begins in the middle of`() {
        // What the viewer fetches when the head held no track header: the last
        // stretch of the object, which starts somewhere inside `mdat`.
        val whole = fixture("rot270.mp4")
        val tail = whole.copyOfRange(whole.size / 2, whole.size)
        assertEquals(270, Mp4Rotation.of(tail))
    }

    @Test
    fun `no track header in range is told apart from an upright one`() {
        // The caller needs the difference: null means fetch another range,
        // zero means stop and leave the picture alone.
        assertEquals(null, Mp4Rotation.find(fixture("rot90.mp4").copyOfRange(0, 64)))
        assertEquals(0, Mp4Rotation.find(fixture("rot0.mp4")))
    }

    @Test
    fun `junk is not mistaken for a rotation`() {
        assertEquals(0, Mp4Rotation.of(ByteArray(512) { 0xFF.toByte() }))
        assertEquals(0, Mp4Rotation.of("not an mp4 at all, just some text".toByteArray()))
    }
}
