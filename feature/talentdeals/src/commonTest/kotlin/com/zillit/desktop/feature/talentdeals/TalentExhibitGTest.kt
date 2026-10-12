@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.domain.SagFacts
import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.SigningRow
import com.zillit.desktop.feature.talentdeals.domain.TalentExhibitG
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhibit G — the AD dashboard's per-day SAG sheet, for one talent: reading the
 * signing rows (three shapes), and pricing a day's facts at the contract's day
 * rate. Ports the web's `exhibitGSigning.test.jsx`.
 */
class TalentExhibitGTest {

    private fun days(json: String) = TalentExhibitG.normalizeSigningDays(Json.parseToJsonElement(json))

    private val utc5Jan = 1767571200000L

    @Test
    fun `a per-day reports list reads in date order, rows in sheet order`() {
        val out = days(
            """{"reports":[
              {"report_id":"r2","shoot_date":"2026-01-07","rows":[{"cell_id":"c","row_id":"x","row_index":1,"signed":false}]},
              {"report_id":"r1","shoot_date":"2026-01-05","rows":[
                 {"cell_id":"c","row_id":"b","row_index":4,"signed":true,"signed_at":1,"signature_file":{"media":"sig.png","name":"s.png"}},
                 {"cell_id":"c","row_id":"a","row_index":2,"signed":false}]}]}""",
        )
        assertEquals(listOf("r1", "r2"), out.map { it.reportId })
        assertEquals(listOf("a", "b"), out[0].rows.map { it.rowId })
        assertTrue(out[0].rows[1].signed)
        assertEquals("sig.png", out[0].rows[1].signatureFile!!.media)
    }

    @Test
    fun `the all-days route reads epoch shoot dates and each row's own person`() {
        val out = days(
            """{"reports":[
              {"report_id":"r8","shoot_date":1767830400000,"rows":[
                 {"cell_id":"c","row_id":"x","row_index":0,"person_id":"Rudra Pratap","person_type":"name","name":"Rudra Pratap","signed":false}]},
              {"report_id":"r5","shoot_date":$utc5Jan,"rows":[
                 {"cell_id":"c","row_id":"y","row_index":2,"person_id":"u1","person_type":"user","name":"Rudra Pratap","signed":true,"signed_at":5},
                 {"cell_id":"c","row_id":"z","row_index":3,"person_id":"a1","person_type":"artiste","name":"Rudra PRATAP","signed":false}]}]}""",
        )
        assertEquals(listOf("r5", "r8"), out.map { it.reportId })
        assertEquals(utc5Jan, out[0].shootDate)
        assertEquals(
            listOf(Triple("u1", "user", "Rudra Pratap"), Triple("a1", "artiste", "Rudra PRATAP"), Triple("Rudra Pratap",
                "name", "Rudra Pratap")),
            out.flatMap { d -> d.rows.map { Triple(it.personId, it.personType, it.name) } },
        )
    }

    @Test
    fun `each day's columns and each row's values come through as strings`() {
        val out = days(
            """{"reports":[{"report_id":"r1","shoot_date":$utc5Jan,
               "columns":[{"label":"Cast","type":"users"},{"label":"Minor?","type":"yesno"}],
               "rows":[{"cell_id":"c","row_id":"a","row_index":0,"name":"Jane Doe","values":["Jane Doe","N",null,3],"signed":false}]}]}""",
        )
        assertEquals(listOf("Cast", "Minor?"), out[0].columns.map { it.label })
        assertEquals(listOf("users", "yesno"), out[0].columns.map { it.type })
        assertEquals(listOf("Jane Doe", "N", "", "3"), out[0].rows[0].values)

        val one = days("""{"report_id":"r9","columns":[{"label":"Cast","type":"users"}],"rows":[{"cell_id":"c","row_id":"b","row_index":0}]}""")
        assertEquals(listOf("Cast"), one[0].columns.map { it.label })
        assertEquals(emptyList(), one[0].rows[0].values)
    }

    @Test
    fun `a row's SAG facts are camel-cased, and null when the API sends none`() {
        val out = days(
            """{"reports":[{"report_id":"r1","shoot_date":$utc5Jan,"rows":[
              {"cell_id":"c","row_id":"a","row_index":0,"signed":false,"sag":{
                "worked_minutes":480,"meal_lateness":[4],"meal_penalties":1,"short_meal_break":true,
                "overtime_15_minutes":0,"overtime_2_minutes":0,"golden_hours":0,"night_10_minutes":240,"night_20_minutes":0,
                "turnaround_minutes":570,"turnaround_short":true,"forced_call":true,"holiday":false,"consecutive_day":6}},
              {"cell_id":"c","row_id":"b","row_index":1,"signed":false}]}]}""",
        )
        assertEquals(
            SagFacts(
                workedMinutes = 480, mealLateness = listOf(4), mealPenalties = 1, shortMealBreak = true,
                night10 = 240, turnaroundMinutes = 570, turnaroundShort = true, forcedCall = true, consecutiveDay = 6,
            ),
            out[0].rows[0].sag,
        )
        assertNull(out[0].rows[1].sag)
    }

    @Test
    fun `flat rows tagged with report_id group into days`() {
        val out = days(
            """{"rows":[
              {"report_id":"r1","shoot_date":"2026-01-05","cell_id":"c","row_id":"a","row_index":0,"signed":true},
              {"report_id":"r2","shoot_date":"2026-01-06","cell_id":"c","row_id":"b","row_index":0,"signed":false},
              {"report_id":"r1","shoot_date":"2026-01-05","cell_id":"c","row_id":"c","row_index":1,"signed":false}]}""",
        )
        assertEquals(listOf("r1" to 2, "r2" to 1), out.map { it.reportId to it.rows.size })
    }

    @Test
    fun `the per-report route reads as one day`() {
        val out = days(
            """{"report_id":"r9","rows":[{"cell_id":"cell91","row_id":"r117","row_index":3,"person_id":"u1","person_type":"user","name":"Jane Doe","signed":true,"signed_at":1750000000000}]}""",
        )
        assertEquals(1, out.size)
        assertEquals("r9", out[0].reportId)
        assertEquals(SigningRow(cellId = "cell91", rowId = "r117", rowIndex = 3, personId = "u1", personType = "user",
            name = "Jane Doe", signed = true, signedAt = 1750000000000L), out[0].rows[0])
    }

    @Test
    fun `an undated day sorts last, and nothing is nothing`() {
        val out = days("""{"reports":[{"report_id":"u","rows":[{"row_id":"a"}]},{"report_id":"d","shoot_date":$utc5Jan,"rows":[{"row_id":"b"}]}]}""")
        assertEquals(listOf("d", "u"), out.map { it.reportId })
        assertEquals(emptyList(), TalentExhibitG.normalizeSigningDays(null))
        assertEquals(emptyList(), days("""{"rows":[]}"""))
        assertEquals(emptyList(), days("""{"reports":[{"report_id":"e","rows":[]}]}"""))
    }

    // -- pricing a day -----------------------------------------------------------------

    private val rate = 1283.0
    @Suppress("LongParameterList") // A fixture builder: every fact has a default.
    private fun sag(
        worked: Int? = 480,
        meal: List<Int> = emptyList(),
        ot15: Int = 0,
        ot2: Int = 0,
        golden: Int = 0,
        night10: Int = 0,
        turnaround: Int? = null,
        forced: Boolean = false,
        holiday: Boolean = false,
        day: Int = 1,
    ) = SagFacts(
        workedMinutes = worked, mealLateness = meal, mealPenalties = meal.size, overtime15 = ot15, overtime2 = ot2,
        goldenHours = golden, night10 = night10, turnaroundMinutes = turnaround, forcedCall = forced,
        holiday = holiday, consecutiveDay = day,
    )

    private fun priced(lines: List<com.zillit.desktop.feature.talentdeals.domain.DayLine>) =
        lines.map { Triple(it.key, it.detail, TalentExhibitG.round3(it.amount)) }

    @Test
    fun `the design's lines are priced at the contract's rate`() {
        val lines = TalentExhibitG.dayLines(
            sag(worked = 600, ot15 = 120, meal = listOf(2), turnaround = 570, forced = true, day = 6),
            rate,
        )
        assertEquals(
            listOf(
                Triple("basic", "8h", 1283.0),
                Triple("ot15", "2h", 481.125), // 2h × 1283/8 × 1.5
                Triple("meal", "2 × 30m late", 60.0), // $25 + $35
                Triple("forced", "9h 30m turnaround", 900.0), // min(rate, $900) for a day performer
                Triple("day6", "150% of rate", 641.5),
            ),
            priced(lines),
        )
    }

    @Test
    fun `each late meal starts on its own ladder`() {
        // 2 half-hours then 1: (25 + 35) + 25 — a running ladder would charge the second meal's half-hour at $50.
        assertEquals(85.0, TalentExhibitG.dayLines(sag(meal = listOf(2, 1)), rate).single { it.key == "meal" }.amount)
        // Five half-hours and more reach the 2026 $75 step.
        assertEquals(
            (25 + 35 + 50 + 50 + 75 + 75).toDouble(),
            TalentExhibitG.dayLines(sag(meal = listOf(6)), rate).single { it.key == "meal" }.amount,
        )
    }

    @Test
    fun `holiday and seventh-day premiums, and double time past 10h`() {
        val lines = TalentExhibitG.dayLines(sag(worked = 660, ot15 = 120, ot2 = 60, holiday = true, day = 7), rate)
        assertEquals(
            listOf(
                Triple("ot2", "1h", 320.75), // 1h × 1283/8 × 2
                Triple("holiday", "Worked holiday", 1283.0),
                Triple("day7", "200% of rate", 1283.0),
            ),
            priced(lines).filter { it.first in setOf("ot2", "holiday", "day7") },
        )
    }

    @Test
    fun `a day with no report or dismiss times prices nothing`() {
        assertEquals(emptyList(), TalentExhibitG.dayLines(sag(worked = null), rate))
        assertEquals(emptyList(), TalentExhibitG.dayLines(null, rate))
    }

    @Test
    fun `background's golden time and night premium belong to background alone`() {
        val facts = sag(worked = 1040, ot15 = 120, ot2 = 444, golden = 2, night10 = 240)
        assertTrue("golden" !in TalentExhibitG.dayLines(facts, rate).map { it.key })
        val bg = TalentExhibitG.dayLines(facts, 231.0, "background")
        assertEquals(listOf("basic", "ot15", "ot2", "golden", "night"), bg.map { it.key })
        // Background double time stops at 16h: 600 → 960 is 6h.
        assertEquals("6h", bg.single { it.key == "ot2" }.detail)
    }

    @Test
    fun `durations read the way the design writes them`() {
        assertEquals("9h", TalentExhibitG.fmtDuration(540))
        assertEquals("9h 30m", TalentExhibitG.fmtDuration(570))
        assertEquals("1h 05m", TalentExhibitG.fmtDuration(65))
    }

    // -- the day rate ------------------------------------------------------------------

    @Test
    fun `the fee works back to a day rate over the weeks, else scale stands in`() {
        val (derived, basis) = TalentExhibitG.dayRate(fee = 50000.0, weeks = 10.0)
        assertEquals(1000.0, derived)
        assertEquals("fee ÷ 10 weeks ÷ 5 days", basis)
        assertEquals("fee ÷ 1 week ÷ 5 days", TalentExhibitG.dayRate(5000.0, 1.0).second)

        val (scale, why) = TalentExhibitG.dayRate(fee = 50000.0, weeks = 0.0)
        assertEquals(1283.0, scale)
        assertEquals("SAG day-performer scale", why)
        assertEquals(1283.0, TalentExhibitG.dayRate(fee = 0.0, weeks = 4.0).first)
    }

    // -- which row stands for a day ----------------------------------------------------

    private fun row(id: String, name: String) = SigningRow(cellId = "c", rowId = id, personId = id, name = name)

    @Test
    fun `with several matching rows the picked one stands, else the exact name, else the first`() {
        val day = SigningDay("r", utc5Jan, emptyList(), listOf(row("a", "Rudra Pratap"), row("b", "Rudra PRATAP"),
            row("c", "rudra pratap")))
        assertEquals("b", TalentExhibitG.chooseRow(day, picked = "c:b:b", performer = "x").rowId)
        // Spacing is noise; case is not — the server matched them all ignoring case.
        assertEquals("b", TalentExhibitG.chooseRow(day, picked = null, performer = "  Rudra   PRATAP ").rowId)
        assertEquals("a", TalentExhibitG.chooseRow(day, picked = null, performer = "Nobody").rowId)
        assertEquals("rudra pratap", TalentExhibitG.nameKey("  Rudra   Pratap "))
    }

    @Test
    fun `a sheet laid out any other way shows no cells rather than misplaced ones`() {
        val odd = row("a", "X").copy(values = listOf("a", "b", "c"))
        assertEquals("", TalentExhibitG.sheetCell(odd, 2))
        val full = row("a", "X").copy(values = List(TalentExhibitG.GRID_COLUMNS) { "v$it" })
        assertEquals("v2", TalentExhibitG.sheetCell(full, 2))
    }
}
