package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealStructureRules
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deal Structure's advisory phase checks (`Step4DealStructure.jsx`), each only while the schedule is on. */
class DealStructureRulesTest {

    private fun schedule(vararg dates: Pair<String, String>, on: Boolean = true) = DealForm.INITIAL
        .with("schedOn", on)
        .with(dates.associate { (key, value) -> key to JsonPrimitive(value) })

    private fun errors(form: DealForm, phase: String) = DealStructureRules.phaseErrors(form).getValue(phase)

    @Test
    fun `nothing is checked while the schedule is off`() {
        val form = schedule("schedPrepStart" to "2026-09-10", "schedPrepEnd" to "2026-09-01", on = false)
        assertTrue(DealStructureRules.phaseErrors(form).values.all { it.isEmpty() })
    }

    @Test
    fun `a phase ending before it starts, and a phase starting inside the one before, are flagged on that phase`() {
        val form = schedule(
            "schedPrepStart" to "2026-09-01",
            "schedPrepEnd" to "2026-09-10",
            "schedShootStart" to "2026-09-10",
            "schedShootEnd" to "2026-09-05",
            "schedWrapStart" to "2026-09-04",
            "schedWrapEnd" to "2026-09-20",
        )
        assertEquals(emptyList(), errors(form, "Prep"))
        assertEquals(
            listOf("End date is before start date", "Overlaps with Prep — must start after prep ends"),
            errors(form, "Shoot"),
        )
        assertEquals(listOf("Overlaps with Shoot — must start after shoot ends"), errors(form, "Wrap"))
    }

    @Test
    fun `the first phase with a start is held against the deal's start`() {
        val early = listOf("dealStart" to "2026-09-05", "schedShootStart" to "2026-09-01")
        assertEquals(listOf("Starts before deal start date"), errors(schedule(*early.toTypedArray()), "Shoot"))

        val withPrep = schedule(*(early + ("schedPrepStart" to "2026-09-06")).toTypedArray())
        assertEquals(emptyList(), errors(withPrep, "Shoot"), "once prep has a start, only prep is compared")
        assertEquals(emptyList(), errors(withPrep, "Prep"))
    }

    @Test
    fun `the last phase with an end is held against the deal's end`() {
        val late = listOf("dealEnd" to "2026-10-01", "schedPrepEnd" to "2026-10-05")
        assertEquals(listOf("Ends after estimated deal end date"), errors(schedule(*late.toTypedArray()), "Prep"))

        val withShoot = schedule(*(late + ("schedShootEnd" to "2026-09-30")).toTypedArray())
        assertEquals(emptyList(), errors(withShoot, "Prep"), "a later phase with an end takes over the check")
        val withWrap = schedule(*(late + ("schedWrapEnd" to "2026-10-02")).toTypedArray())
        assertEquals(listOf("Ends after estimated deal end date"), errors(withWrap, "Wrap"))
    }
}
