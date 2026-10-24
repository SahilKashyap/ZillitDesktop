package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.Condition
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateField
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateForm
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Validation
import com.zillit.desktop.feature.scriptsupervision.domain.toLogBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Logging through a template.
 *
 * This is the arithmetic a shooting day runs on: a slate that increments
 * wrongly, or a field that carries over when it should have cleared, puts
 * bad data in a log nobody re-reads until the cutting room does.
 */
class TemplateFormTest {

    private fun field(key: String, type: String = "text", label: String = key) =
        TemplateField(key = key, label = label, type = type)

    @Test
    fun `a slate steps its number, or its letter when it has one`() {
        assertEquals("26", TemplateForm.incrementSlate("25"))
        assertEquals("25B", TemplateForm.incrementSlate("25A"))
        assertEquals("25Z", TemplateForm.incrementSlate("25Y"))
        // Nothing yet: a slate starts at 1.
        assertEquals("1", TemplateForm.incrementSlate(""))
        assertEquals("1", TemplateForm.incrementSlate("   "))
    }

    @Test
    fun `a slate in a scheme of its own is left alone, not mangled`() {
        // A production that slates "14-2" or "A14" keeps its own scheme;
        // guessing at it would renumber a day's takes.
        assertEquals("14-2", TemplateForm.incrementSlate("14-2"))
        assertEquals("A14", TemplateForm.incrementSlate("A14"))
        assertEquals("25ab", TemplateForm.incrementSlate("25ab"))
    }

    @Test
    fun `the time mask keeps the colon two from the right`() {
        assertEquals("", TemplateForm.maskTime(""))
        assertEquals("1", TemplateForm.maskTime("1"))
        assertEquals("13", TemplateForm.maskTime("13"))
        assertEquals("1:30", TemplateForm.maskTime("130"))
        assertEquals("01:30", TemplateForm.maskTime("0130"))
        // Four digits at most, and anything else dropped as it is typed.
        assertEquals("12:34", TemplateForm.maskTime("123456"))
        assertEquals("1:30", TemplateForm.maskTime("1m30s"))
    }

    @Test
    fun `a duration field takes the mask and a time-of-day field does not`() {
        assertTrue(TemplateForm.isTimeLike(field("duration_display")))
        assertTrue(TemplateForm.isTimeLike(field("anything", type = "time")))
        assertTrue(TemplateForm.isTimeLike(field("x", label = "Run time (MM:SS)")))
        // ⚠ The two the web deliberately leaves free text: a mask of four
        // digits would mangle a time of day and a timecode alike.
        assertEquals(false, TemplateForm.isTimeLike(field("x", label = "Time")))
        assertEquals(false, TemplateForm.isTimeLike(field("x", label = "Timecode")))
    }

    @Test
    fun `a field shows when its rules hold, and when it has none`() {
        val values = mapOf("record_type" to "sync", "notes" to "")

        assertTrue(TemplateForm.conditionsHold(emptyList(), values), "no rules means always")
        assertTrue(TemplateForm.conditionsHold(listOf(Condition("record_type", "equals", "sync")), values))
        assertEquals(
            false,
            TemplateForm.conditionsHold(listOf(Condition("record_type", "equals", "wild")), values),
        )
        assertTrue(TemplateForm.conditionsHold(listOf(Condition("record_type", "not_equals", "wild")), values))
        assertTrue(TemplateForm.conditionsHold(listOf(Condition("record_type", "is_set", "")), values))
        assertTrue(TemplateForm.conditionsHold(listOf(Condition("notes", "is_not_set", "")), values))
        // An operator the service invents later shows the field rather than
        // hiding it: a field nobody can see is the worse failure.
        assertTrue(TemplateForm.conditionsHold(listOf(Condition("record_type", "sounds_like", "x")), values))
    }

    @Test
    fun `every rule of a set must hold, not just one`() {
        val rules = listOf(
            Condition("a", "equals", "1"),
            Condition("b", "equals", "2"),
        )

        assertTrue(TemplateForm.conditionsHold(rules, mapOf("a" to "1", "b" to "2")))
        assertEquals(false, TemplateForm.conditionsHold(rules, mapOf("a" to "1", "b" to "9")))
    }

    @Test
    fun `a calculated field works its value out, or stays blank`() {
        assertEquals("1:30", TemplateForm.computeFormula("duration_diff", listOf("1:00", "2:30")))
        // Order does not matter: it is a gap, not a signed difference.
        assertEquals("1:30", TemplateForm.computeFormula("duration_diff", listOf("2:30", "1:00")))
        assertEquals("", TemplateForm.computeFormula("duration_diff", listOf("1:00", "")))
        assertEquals("A B", TemplateForm.computeFormula("concat", listOf("A", "", "B")))
        assertEquals("2", TemplateForm.computeFormula("count", listOf("A", "", "B")))
        assertEquals("", TemplateForm.computeFormula("invented", listOf("A")))
    }

    @Test
    fun `only the per-take fields clear, and only the kinds that can`() {
        val take = TemplateSection(
            key = "take",
            label = "Take",
            fields = listOf(
                field("is_circled", "switch"),
                field("notes", "textarea"),
                field("duration_display"),
                // NOT in the reset list: the same setup, so it carries over.
                field("camera_roll"),
                field("lens_mm", "select"),
                // In the reset list but a select: nothing sensible to clear to.
                field("ng_reason", "select"),
            ),
        )

        val reset = TemplateForm.resetAfterTake(take)

        assertEquals(setOf("is_circled", "notes", "duration_display"), reset.keys)
        assertEquals("false", reset["is_circled"])
        assertEquals("", reset["notes"])
    }

    @Test
    fun `a template's defaults cover only the fields that have one`() {
        val sections = listOf(
            TemplateSection(
                key = "take",
                label = "Take",
                fields = listOf(
                    TemplateField("fps", "FPS", "select", defaultValue = JsonPrimitive("24")),
                    TemplateField("is_circled", "Circled", "switch", defaultValue = JsonPrimitive(true)),
                    field("notes", "textarea"),
                ),
            ),
        )

        val defaults = TemplateForm.templateDefaults(sections)

        // Not a blank for `notes`: an empty default would be saved as
        // deliberate emptiness.
        assertEquals(mapOf("fps" to "24", "is_circled" to "true"), defaults["take"])
    }

    @Test
    fun `a divider starts a run and takes the flags that follow it`() {
        // The take section's own shape: fields, a Status divider, then the
        // flags. The divider must TITLE the flags rather than sit above an
        // empty run with the flags in another.
        val take = TemplateSection(
            key = "take",
            label = "Take",
            fields = listOf(
                field("slate_number"),
                field("status_divider", "divider"),
                field("is_circled", "switch"),
                field("is_ng", "switch"),
                field("notes", "textarea"),
            ),
        )

        val runs = TemplateForm.fieldRuns(take)

        assertEquals(3, runs.size)
        assertEquals(listOf("slate_number"), runs[0].fields.map { it.key })
        assertEquals(false, runs[0].flags)
        assertEquals("status_divider", runs[1].divider?.key)
        assertTrue(runs[1].flags, "the divider's own run carries the flags")
        assertEquals(listOf("is_circled", "is_ng"), runs[1].fields.map { it.key })
        assertEquals(listOf("notes"), runs[2].fields.map { it.key })
    }

    @Test
    fun `a switch outside the take section is not a flag toggle`() {
        val sound = TemplateSection(
            key = "sound_report",
            label = "Sound",
            fields = listOf(field("adr_needed", "switch")),
        )

        val runs = TemplateForm.fieldRuns(sound)

        assertEquals(1, runs.size)
        assertEquals(false, runs[0].flags)
    }

    @Test
    fun `a value is refused for the reason its own settings give`() {
        val say = { key: String, arg: String -> "$key:$arg" }
        val required = TemplateField("a", "Slate", "text", required = true)

        assertEquals("field_required:Slate", TemplateForm.validate(required, "", say))
        assertNull(TemplateForm.validate(required, "14", say))

        val ranged = TemplateField(
            "n", "Channels", "number",
            validation = Validation(min = 1.0, max = 8.0),
        )
        assertEquals("rule_min:1", TemplateForm.validate(ranged, "0", say))
        assertEquals("rule_max:8", TemplateForm.validate(ranged, "9", say))
        assertNull(TemplateForm.validate(ranged, "4", say))
        // A blank optional field breaks no rule.
        assertNull(TemplateForm.validate(ranged, "", say))
    }

    @Test
    fun `a pattern that is not an expression checks nothing instead of refusing everything`() {
        val say = { key: String, arg: String -> "$key:$arg" }
        val broken = TemplateField("p", "Code", "text", validation = Validation(pattern = "a[("))

        // The web swallows the bad expression; refusing every value would
        // make the field impossible to fill.
        assertNull(TemplateForm.validate(broken, "anything", say))
    }

    @Test
    fun `a log body is typed, and an off switch is still sent`() {
        val sections = listOf(
            TemplateSection(
                key = "take",
                label = "Take",
                fields = listOf(
                    field("take_number", "number"),
                    field("is_circled", "switch"),
                    field("is_ng", "switch"),
                    field("slate_number"),
                    field("status_divider", "divider"),
                ),
            ),
        )
        val values = mapOf(
            "take" to mapOf(
                "take_number" to "3",
                "is_circled" to "true",
                "is_ng" to "false",
                "slate_number" to "",
                "status_divider" to "",
            ),
        )

        val body = values.toLogBody(sections).jsonObject["take"]!!.jsonObject

        // ⚠ Booleans, not the strings "true"/"false": most readers treat a
        // non-empty string as true, so "false" would come back circled.
        assertEquals(true, body["is_circled"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["is_ng"]?.jsonPrimitive?.booleanOrNull, "an off flag is a fact about the take")
        assertEquals(3.0, body["take_number"]?.jsonPrimitive?.content?.toDouble())
        // Blank text is dropped; a divider never travels at all.
        assertEquals(false, body.containsKey("slate_number"))
        assertEquals(false, body.containsKey("status_divider"))
    }

    @Test
    fun `a section with nothing filled is left out of the body entirely`() {
        val sections = listOf(
            TemplateSection(key = "sound_report", label = "Sound", fields = listOf(field("timecode"))),
        )

        val body = mapOf("sound_report" to mapOf("timecode" to "")).toLogBody(sections)

        assertEquals(false, body.containsKey("sound_report"))
    }
}
