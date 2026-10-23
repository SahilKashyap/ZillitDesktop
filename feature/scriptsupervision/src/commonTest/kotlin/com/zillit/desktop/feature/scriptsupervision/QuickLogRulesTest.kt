package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.QuickLog
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The quick log's keystroke-saving rules.
 *
 * All of them are used while a take is running, where a wrong one is worse
 * than none: a slate that steps to the wrong number, or a lens length carried
 * as 0mm, is written into the record and read back weeks later in the edit.
 */
class QuickLogRulesTest {

    @Test
    fun `a numbered slate steps to the next number`() {
        assertEquals("26", QuickLog.nextSlate("25"))
        assertEquals("2", QuickLog.nextSlate("1"))
        assertEquals("101", QuickLog.nextSlate("100"))
    }

    @Test
    fun `a lettered slate steps to the next letter`() {
        assertEquals("25B", QuickLog.nextSlate("25A"))
        assertEquals("25C", QuickLog.nextSlate("25B"))
        // Lower case is a slate too; it steps in upper case, as the web's does.
        assertEquals("25B", QuickLog.nextSlate("25a"))
    }

    @Test
    fun `a slate the production numbers its own way is left to the user`() {
        // Z has no letter after it, which is why the web stops at Y.
        assertNull(QuickLog.nextSlate("25Z"))
        assertNull(QuickLog.nextSlate("25AA"))
        assertNull(QuickLog.nextSlate("A25"))
        assertNull(QuickLog.nextSlate("12-3"))
        assertNull(QuickLog.nextSlate(""))
        assertNull(QuickLog.nextSlate(null))
    }

    @Test
    fun `the camera settings carry from the last take, and its zeros do not`() {
        val last = Rec(
            buildJsonObject {
                put("slate_number", "25A")
                put("camera_roll", "A001")
                put("lens_mm", 35)
                // 0 is what the service stores for "nobody entered one".
                put("shutter_angle", 0)
                put("iso_ei", 800)
                put("color_temp", "")
                put("t_stop", "T2.8")
                // Not a carried field: this take's own note.
                put("notes", "boom dipped")
            },
        )

        val carried = QuickLog.carriedFrom(last)

        assertEquals("25A", carried["slate_number"])
        assertEquals("35", carried["lens_mm"])
        assertEquals("800", carried["iso_ei"])
        assertEquals("T2.8", carried["t_stop"])
        // A 0mm lens and a blank colour temperature are not settings.
        assertNull(carried["shutter_angle"])
        assertNull(carried["color_temp"])
        // Only the carried fields travel.
        assertNull(carried["notes"])
    }

    @Test
    fun `no last take carries nothing`() {
        assertEquals(emptyMap(), QuickLog.carriedFrom(null))
        assertEquals(emptyMap(), QuickLog.carriedFrom(Rec(JsonObject(emptyMap()))))
    }

    @Test
    fun `a duration is typed as digits and the colon puts itself in`() {
        assertEquals("1", QuickLog.maskClock("1"))
        assertEquals("15", QuickLog.maskClock("15"))
        assertEquals("1:52", QuickLog.maskClock("152"))
        assertEquals("12:34", QuickLog.maskClock("1234"))
        // Four digits is the most it takes, so a fifth is dropped.
        assertEquals("12:34", QuickLog.maskClock("12345"))
        // Anything that is not a digit is ignored, colon included.
        assertEquals("1:52", QuickLog.maskClock("1:52"))
        assertEquals("1:52", QuickLog.maskClock("1m52s"))
        assertEquals("", QuickLog.maskClock("abc"))
    }

    @Test
    fun `a duration converts to the service's seconds and back`() {
        assertEquals(112, QuickLog.toSeconds("1:52"))
        assertEquals(754, QuickLog.toSeconds("12:34"))
        // No colon means seconds alone, as typed mid-take.
        assertEquals(45, QuickLog.toSeconds("45"))
        assertEquals(0, QuickLog.toSeconds(""))

        assertEquals("1:52", QuickLog.fromSeconds(112))
        assertEquals("12:34", QuickLog.fromSeconds(754))
        // The seconds are padded, or 1:05 would read as 1:5.
        assertEquals("1:05", QuickLog.fromSeconds(65))
        // 0 is the service's "not set", so it reads blank rather than 0:00.
        assertEquals("", QuickLog.fromSeconds(0))
    }

    @Test
    fun `the script lines offered are the dialogue, not the slug lines or cues`() {
        val scene = Rec(
            buildJsonObject {
                put(
                    "script_text",
                    buildJsonArray {
                        add(JsonPrimitive("INT. KITCHEN - DAY"))
                        add(JsonPrimitive("RAVI"))
                        add(JsonPrimitive("I told you I would come back."))
                        add(JsonPrimitive("  She does not look up.  "))
                        // The same line twice: offered once.
                        add(JsonPrimitive("I told you I would come back."))
                        add(JsonPrimitive(""))
                    },
                )
            },
        )

        assertEquals(
            listOf("I told you I would come back.", "She does not look up."),
            QuickLog.scriptLines(scene),
        )
    }

    /**
     * ⚠ Parity, not an endorsement: the web's caps test allows no colon
     * (`^[A-Z][A-Z\s\-'.#0-9()]+$`), so a transition line survives it and is
     * offered as a script line there too.
     *
     * Kept the web's way on purpose — a record written from either client has
     * to read the same — and pinned here so that if the web ever tightens the
     * test, this fails and says why rather than drifting quietly.
     */
    @Test
    fun `a transition survives the caps test, exactly as it does on the web`() {
        val scene = Rec(
            buildJsonObject {
                put(
                    "script_text",
                    buildJsonArray {
                        add(JsonPrimitive("CUT TO:"))
                        add(JsonPrimitive("DISSOLVE TO:"))
                        // No colon, so these two ARE filtered.
                        add(JsonPrimitive("FADE OUT"))
                        add(JsonPrimitive("INT. KITCHEN - DAY"))
                    },
                )
            },
        )

        assertEquals(listOf("CUT TO:", "DISSOLVE TO:"), QuickLog.scriptLines(scene))
    }

    @Test
    fun `a scene with no script offers no lines`() {
        assertEquals(emptyList(), QuickLog.scriptLines(null))
        assertEquals(emptyList(), QuickLog.scriptLines(Rec(JsonObject(emptyMap()))))
    }

    @Test
    fun `the shot-size key spells out the one code that is not a key`() {
        assertEquals("shot_size_two_shot", QuickLog.shotSizeKey("2-Shot"))
        assertEquals("shot_size_mcu", QuickLog.shotSizeKey("MCU"))
        assertEquals("shot_size_establishing", QuickLog.shotSizeKey("Establishing"))
    }
}
