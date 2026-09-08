package com.zillit.desktop.feature.calls

import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ringtones ship as assets, and `CallRinger` loops each file WHOLE — so the
 * file length IS the cadence. A clip regenerated at the wrong period would ring
 * to the wrong rhythm with nothing else failing, which is what these pin.
 *
 * The periods are the web client's (`callSounds.ts`): a 2 s two-tone ring and a
 * 3 s ringback. Regenerate with `scripts/make-call-sounds.py`.
 */
class CallSoundsTest {

    private fun seconds(resource: String): Double {
        val stream = javaClass.getResourceAsStream(resource)
        assertNotNull(stream, "missing $resource")
        AudioSystem.getAudioInputStream(BufferedInputStream(stream)).use { audio ->
            return audio.frameLength / audio.format.frameRate.toDouble()
        }
    }

    @Test
    fun `the incoming ring loops on web's two second cadence`() {
        assertEquals(2.0, seconds("/callsounds/incoming.wav"), 0.001)
    }

    @Test
    fun `the outgoing ringback loops on web's three second cadence`() {
        assertEquals(3.0, seconds("/callsounds/outgoing.wav"), 0.001)
    }

    @Test
    fun `both tones are in a format the sound system can actually play`() {
        // CallRinger reads them through javax.sound.sampled with no engine and
        // no Chromium up; a format Clip cannot open would ring silently in the
        // field, and the only symptom would be a line in the log.
        listOf("/callsounds/incoming.wav", "/callsounds/outgoing.wav").forEach { resource ->
            val stream = javaClass.getResourceAsStream(resource)
            assertNotNull(stream, "missing $resource")
            AudioSystem.getAudioInputStream(BufferedInputStream(stream)).use { audio ->
                assertTrue(
                    AudioSystem.isLineSupported(DataLine.Info(Clip::class.java, audio.format)),
                    "$resource is in a format Clip cannot play",
                )
            }
        }
    }
}
