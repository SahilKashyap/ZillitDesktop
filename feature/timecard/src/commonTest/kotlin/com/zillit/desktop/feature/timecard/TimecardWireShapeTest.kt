package com.zillit.desktop.feature.timecard

import com.zillit.desktop.feature.timecard.data.AllowancesRentalsDto
import com.zillit.desktop.feature.timecard.data.MySummaryDto
import com.zillit.desktop.feature.timecard.domain.AllowanceBasis
import com.zillit.desktop.feature.timecard.domain.AllowanceScope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire shapes, as the dev server actually answers them.
 *
 * Both payloads below were copied out of a real `develop` response, and both
 * were originally read wrongly — `my-summary` as a bare array, and the
 * allowance catalogue off the payroll service, which does not publish one. See
 * the payroll module's equivalent for why verbatim capture is the point.
 */
class TimecardWireShapeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * `/payroll/timecards/weekly/my-summary` — an object, not an array.
     *
     * Alone among the list routes. Decoding it as an array throws.
     */
    @Test
    fun `my-summary wraps its weeks in an object`() {
        val body = """
            {"weeks":[],"current_week":null,"days_worked":0}
        """.trimIndent()
        val summary = json.decodeFromString(MySummaryDto.serializer(), body)
        assertTrue(summary.weeks.orEmpty().isEmpty())
    }

    @Test
    fun `the slim week projection carries an id, a week and its totals`() {
        // No days: the summary projection omits them, and opening a week
        // fetches the full card separately.
        val body = """
            {"weeks":[
              {"_id":"tc2","week_starting":1780597800000,"status":"paid",
               "total_days":5,"total_pay":586.53}
            ],"current_week":null,"days_worked":5}
        """.trimIndent()
        val weeks = json.decodeFromString(MySummaryDto.serializer(), body)
            .weeks.orEmpty().mapNotNull { it.toDomain() }
        val week = weeks.single()
        assertEquals("tc2", week.id)
        assertEquals(1_780_597_800_000, week.weekStarting)
        assertEquals(586.53, week.totalPay)
    }

    /**
     * `/account-hub/project-settings/allowances-rentals` — wrapped in `value`.
     *
     * The read is wrapped and the documented write is not. Reading
     * `data.allowances` finds nothing and fails **silently**, leaving an empty
     * catalogue rather than an error — which is why this is pinned.
     */
    @Test
    fun `the allowance catalogue sits under a value wrapper`() {
        val body = """{"value":{"allowances":[],"rentals":[]}}"""
        val slice = json.decodeFromString(AllowancesRentalsDto.serializer(), body)
        assertEquals(emptyList(), slice.allowances)
    }

    @Test
    fun `an unwrapped slice still reads, in case the read is squared with the write`() {
        val body = """{"allowances":[{"id":"MP","name":"Meal penalty","amount":12.5}]}"""
        val slice = json.decodeFromString(AllowancesRentalsDto.serializer(), body)
        assertEquals("MP", slice.allowances?.single()?.id)
    }

    @Test
    fun `an allowance row maps its real field names`() {
        val body = """
            {"value":{"allowances":[
              {"id":"MP","name":"Meal penalty","enable":true,"amount":12.5,
               "basis":"day","applies_to":"shoot","nominal_code":"7100"}
            ],"rentals":[]}}
        """.trimIndent()
        val type = json.decodeFromString(AllowancesRentalsDto.serializer(), body)
            .allowances.orEmpty().single().toDomain()!!

        assertEquals("MP", type.code)
        assertEquals("Meal penalty", type.label)
        assertEquals(12.5, type.defaultAmount)
        assertEquals(AllowanceBasis.Day, type.basis)
        assertEquals(AllowanceScope.Shoot, type.appliesTo)
        assertEquals("7100", type.nominalCode)
        // Once a day, so a second claim is a mistake rather than a quantity.
        assertTrue(!type.perUnit)
    }

    @Test
    fun `the legacy rate and on spellings still read`() {
        // Saved documents predate `amount` and `enable`; a strict read would
        // hide allowances the crew is still owed.
        val body = """
            {"value":{"allowances":[
              {"id":"KIT","name":"Kit hire","on":true,"rate":40,"basis":"week"}
            ]}}
        """.trimIndent()
        val row = json.decodeFromString(AllowancesRentalsDto.serializer(), body)
            .allowances.orEmpty().single()
        assertEquals(true, row.enable)
        assertEquals(40.0, row.toDomain()!!.defaultAmount)
    }

    @Test
    fun `a retired basis degrades to daily rather than dropping the row`() {
        // Per Hour, Per Night and Per Event were retired in July 2026 and saved
        // rows still carry them.
        assertEquals(AllowanceBasis.Day, AllowanceBasis.from("hour"))
        assertEquals(AllowanceBasis.Mile, AllowanceBasis.from("mile"))
        assertEquals(AllowanceScope.Any, AllowanceScope.from(null))
    }

    @Test
    fun `an allowance with no set amount is stated by the claimant, not worth nothing`() {
        val body = """{"value":{"allowances":[{"id":"PD","name":"Per diem","enable":true}]}}"""
        val type = json.decodeFromString(AllowancesRentalsDto.serializer(), body)
            .allowances.orEmpty().single().toDomain()!!
        assertNull(type.defaultAmount)
    }
}
