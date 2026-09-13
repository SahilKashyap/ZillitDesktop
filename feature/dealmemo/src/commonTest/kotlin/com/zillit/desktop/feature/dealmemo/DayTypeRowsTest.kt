package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRow
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The project's Day Types table (`DayTypesEditor.jsx`): seeding, the save body, validation and the hint. */
class DayTypeRowsTest {

    private var ids = 0

    private fun newId() = "dt-${++ids}"

    private fun rows(text: String) =
        DayTypeRows.rows(Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }, ::newId)

    private fun row(code: String, work: String = "600", meal: String = "", label: String = "") =
        DayTypeRow(newId(), code, work, meal, label, isDefault = false)

    @Test
    fun `the three defaults lead, a saved override replacing its default, then the saved custom codes`() {
        val seeded = rows(
            """[{"id":"s1","day_type":"SWD","work_min":660,"meal_break_min":60,"label":"Long day"},
               {"day_type":"NIGHT","work_min":600,"meal_break_min":null,"label":null},
               {"day_type":"","work_min":1}]""",
        )

        assertEquals(listOf("SWD", "CWD", "SCWD", "NIGHT"), seeded.map { it.code })
        assertEquals(listOf(true, true, true, false), seeded.map { it.isDefault })
        val swd = seeded.first()
        assertEquals("s1", swd.id, "a saved id is kept")
        assertEquals("660", swd.workMin)
        assertEquals("Long day", swd.label)
        assertEquals("540", seeded[1].workMin, "an unsaved default keeps its own values")
        assertEquals("", seeded[3].mealBreakMin, "a null meal break reads as blank")
        assertTrue(seeded.drop(1).all { it.id.startsWith("dt-") })
    }

    @Test
    fun `the save body trims codes, sends minutes as numbers, and nulls a blank meal break and label`() {
        val body = DayTypeRows.wire(listOf(row(" LATE ", work = "630", meal = "", label = "  ")))
        val sent = body.single().jsonObject

        assertEquals("LATE", sent["day_type"]?.jsonPrimitive?.content)
        assertEquals("630", sent["work_min"].toString())
        assertEquals(JsonNull, sent["meal_break_min"])
        assertEquals(JsonNull, sent["label"])
        assertEquals(JsonNull, sent["note"])
        assertEquals(setOf("day_type", "work_min", "meal_break_min", "label", "note"), sent.keys)
    }

    @Test
    fun `validation reports the first problem in the web's words`() {
        assertNull(DayTypeRows.validate(listOf(row("SWD"), row("NIGHT", meal = "30"))))
        assertEquals("Every day type needs a code (e.g. CWD, SWD).", DayTypeRows.validate(listOf(row("  "))))
        assertEquals(
            "Duplicate day type \"SWD\" — codes must be unique.",
            DayTypeRows.validate(listOf(row("SWD"), row(" SWD"))),
        )
        assertEquals("\"LATE\": working minutes is required.", DayTypeRows.validate(listOf(row("LATE", work = ""))))
        val range = "\"LATE\": working minutes must be a whole number between 0 and 1440."
        assertEquals(range, DayTypeRows.validate(listOf(row("LATE", work = "600.5"))))
        assertEquals(range, DayTypeRows.validate(listOf(row("LATE", work = "1441"))))
        assertEquals(range, DayTypeRows.validate(listOf(row("LATE", work = "ten"))))
        assertEquals(
            "\"LATE\": meal break must be a whole number of minutes (or blank).",
            DayTypeRows.validate(listOf(row("LATE", meal = "-5"))),
        )
        assertEquals(DayTypeRows.TOO_MANY, DayTypeRows.validate(List(DayTypeRows.MAX_ROWS + 1) { row("C$it") }))
    }

    @Test
    fun `the minutes hint reads hours and minutes, and nothing for blank or non-positive values`() {
        assertEquals("= 10h", DayTypeRows.minutesHint("600"))
        assertEquals("= 10h 30m", DayTypeRows.minutesHint("630"))
        assertEquals("= 45m", DayTypeRows.minutesHint("45"))
        assertEquals("", DayTypeRows.minutesHint(""))
        assertEquals("", DayTypeRows.minutesHint("0"))
        assertEquals("", DayTypeRows.minutesHint("-30"))
        assertEquals("", DayTypeRows.minutesHint("abc"))
    }
}
