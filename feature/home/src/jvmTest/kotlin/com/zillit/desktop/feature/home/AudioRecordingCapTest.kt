package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.JvmAudioRecorder
import com.zillit.desktop.feature.home.data.pcmDurationMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ceiling on a voice message, and the duration that goes out with it.
 *
 * The capture sink is held in memory and nothing but [JvmAudioRecorder.stop]
 * used to end it, so a recording left running grew the buffer until the array
 * could not grow again: `OutOfMemoryError: Required array length 2147483639 +
 * 9 is too large`, on the capture thread, about eighteen hours in
 * (2026-10-18). The loop itself needs a real microphone to exercise; the
 * arithmetic it stops on does not, and it is the half that can silently drift
 * away from the format beside it.
 */
class AudioRecordingCapTest {

    private val format = JvmAudioRecorder.FORMAT

    @Test
    fun `the cap is the stated number of minutes of this format`() {
        val seconds = JvmAudioRecorder.MAX_CAPTURE_BYTES / (format.frameSize * format.frameRate)

        assertEquals(
            JvmAudioRecorder.MAX_CAPTURE_MINUTES * 60f,
            seconds,
            "the byte ceiling and the minutes it is described as must not drift apart",
        )
    }

    /** And it stays a size the heap does not notice — the point of capping at all. */
    @Test
    fun `the cap is a fraction of the heap, not a multiple of it`() {
        assertTrue(
            JvmAudioRecorder.MAX_CAPTURE_BYTES in 1..MAX_REASONABLE_BYTES,
            "a ${JvmAudioRecorder.MAX_CAPTURE_BYTES}-byte ceiling is not a ceiling",
        )
    }

    @Test
    fun `duration is measured from the samples, so a capped recording is not overstated`() {
        val oneSecond = (format.frameSize * format.frameRate).toInt()

        assertEquals(0, pcmDurationMillis(0, format))
        assertEquals(1_000, pcmDurationMillis(oneSecond, format))
        assertEquals(
            JvmAudioRecorder.MAX_CAPTURE_MINUTES * 60 * 1_000L,
            pcmDurationMillis(JvmAudioRecorder.MAX_CAPTURE_BYTES, format),
            "a recording stopped at the cap reports the cap, not how long the window was open",
        )
    }

    private companion object {
        /** Generous, but far below the 256 MB a single full-size media fetch may hold. */
        const val MAX_REASONABLE_BYTES = 128 * 1024 * 1024
    }
}
