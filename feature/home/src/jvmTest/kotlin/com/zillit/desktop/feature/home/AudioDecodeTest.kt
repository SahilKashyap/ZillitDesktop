package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.decodeToPcm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The decode half of playback — the part with interesting failure modes,
 * separated from the player precisely so it can run without an audio device.
 */
class AudioDecodeTest {

    /** A quarter second of silence in exactly the format the recorder writes. */
    private fun recorderWav(): ByteArray {
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val pcm = ByteArray((16_000 * 2 / 4))
        val out = ByteArrayOutputStream()
        AudioInputStream(
            ByteArrayInputStream(pcm),
            format,
            (pcm.size / format.frameSize).toLong(),
        ).use { stream -> AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out) }
        return out.toByteArray()
    }

    @Test
    fun `our own recordings decode`() {
        // The round trip that matters most: what JvmAudioRecorder writes,
        // ClipAudioPlayer must read.
        assertNotNull(decodeToPcm(recorderWav()))
    }

    @Test
    fun `compressed formats fail cleanly rather than throwing`() {
        // A fake AAC-ish blob: the phones' uploads look like this to the JVM.
        // Null is the contract — the UI answers with the open-externally chip.
        assertNull(decodeToPcm(ByteArray(512) { (it * 7).toByte() }))
        assertNull(decodeToPcm(ByteArray(0)))
        assertNull(decodeToPcm("not audio at all".encodeToByteArray()))
    }
}
