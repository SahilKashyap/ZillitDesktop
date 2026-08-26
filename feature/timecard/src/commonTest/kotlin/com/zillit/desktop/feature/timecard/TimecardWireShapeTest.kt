@file:Suppress("MaxLineLength") // Pinned wire fixtures are byte-exact, single-line.

package com.zillit.desktop.feature.timecard

import com.zillit.desktop.feature.timecard.data.AllowancesRentalsDto
import com.zillit.desktop.feature.timecard.data.DayDto
import com.zillit.desktop.feature.timecard.data.MySummaryDto
import com.zillit.desktop.feature.timecard.data.TimecardDto
import com.zillit.desktop.feature.timecard.data.TimecardMetadataDto
import com.zillit.desktop.feature.timecard.data.createBody
import com.zillit.desktop.feature.timecard.data.hhmmToUtcEpoch
import com.zillit.desktop.feature.timecard.data.payrollPeriodStart
import com.zillit.desktop.feature.timecard.data.updateBody
import com.zillit.desktop.feature.timecard.data.wireTimeOfDay
import com.zillit.desktop.feature.timecard.domain.Allowance
import com.zillit.desktop.feature.timecard.domain.AllowanceBasis
import com.zillit.desktop.feature.timecard.domain.AllowanceScope
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import kotlinx.datetime.TimeZone
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

    // -- ownership ----------------------------------------------------------

    /**
     * The slim `my-summary` row has NO owner field at all — live evidence: my
     * own Draft week rendered without its Submit action because the DTO's
     * blank `user_id` never matched the viewer. Ownership rides the route.
     */
    @Test
    fun `my-summary rows are the viewer's own, with no user id to compare`() {
        val body = """{"weeks":[{"_id":"tc2","week_starting":1780597800000,"status":"draft","total_days":5}],"current_week":null,"days_worked":5}"""
        val week = json.decodeFromString(MySummaryDto.serializer(), body).ownedWeeks().single()
        assertEquals("", week.userId, "the projection genuinely has no owner field")
        assertTrue(week.ownedByViewer)
    }

    // -- metadata -----------------------------------------------------------

    /**
     * `timecards/metadata` answers the `TimecardMetadataContext` defaults —
     * `is_approver` / `is_accountant` / `is_completer` + `pay_period`
     * (`TimecardMetadataContext.jsx:25-43`); `is_final_approver` only ever
     * comes from the payroll metadata read and is merged in by the caller.
     */
    @Test
    fun `timecard metadata carries the pay period and the accountant flag`() {
        val body = """{"is_approver":true,"is_accountant":true,"is_completer":false,"pay_period":{"start_day_of_week":5,"end_day_of_week":4}}"""
        val metadata = json.decodeFromString(TimecardMetadataDto.serializer(), body)
            .toDomain(isFinalApprover = true)
        assertTrue(metadata.isApprover)
        assertTrue(metadata.isAccountant)
        assertTrue(metadata.isFinalApprover)
        assertEquals(5, metadata.payPeriodStartDay)
    }

    // -- the day on the wire ------------------------------------------------

    /** Codes decode to buckets; labels saved by older rows still decode. */
    @Test
    fun `day types read the web's codes and pass-through labels`() {
        assertEquals(DayType.Worked, DayType.from("SWD"))
        assertEquals(DayType.Worked, DayType.from("HALF_DAY"))
        assertEquals(DayType.Sick, DayType.from("SICK_SSP"))
        assertEquals(DayType.Sick, DayType.from("Sick (Paid)"))
        assertEquals(DayType.Idle, DayType.from("Idle Day"))
        assertEquals(DayType.Rest, DayType.from("REST"))
    }

    /**
     * A saved day as `serializeDayToServer` wrote it: epoch times pinned to
     * UTC wall-clock, hours in `minutes_worked`, OT minutes on the non-basic
     * hourly `rates_ots` rows, claims under `identifier`/`rate_amount`/`qty`.
     */
    @Test
    fun `a saved day reads times, hours and claims from the web's fields`() {
        val body = """{"date":1786924800000,"day_number":1,"day_type":"SWD","call_time":1786951800000,"wrap_time":1786989600000,"timezone":"UTC","minutes_worked":630,"basic_hours":10,"rates_ots":[{"identifier":"basic","basis":"day","work_duration":600},{"identifier":"camot","basis":"hour","work_duration":90}],"allowances":[{"identifier":"MP","label":"Meal penalty","rate_type":"flat","rate_amount":12.5,"qty":1}]}"""
        val day = json.decodeFromString(DayDto.serializer(), body).toDomain()
        assertEquals(DayType.Worked, day.dayType)
        assertEquals("07:30", day.callTime)
        assertEquals("18:00", day.wrapTime)
        assertEquals(10.5, day.workedHours)
        assertEquals(1.5, day.overtimeHours)
        val claim = day.allowances.single()
        assertEquals("MP", claim.code)
        assertEquals(12.5, claim.amount)
        assertEquals(1.0, claim.quantity)
    }

    /** `notes` is `[{note, added_at}]` on the full document; the last row is newest. */
    @Test
    fun `the notes array reads its latest entry`() {
        val body = """{"_id":"tc9","week_starting":1786924800000,"status":"draft","notes":[{"note":"first","added_at":1},{"note":"latest","added_at":2}]}"""
        assertEquals("latest", json.decodeFromString(TimecardDto.serializer(), body).toDomain()?.notes)
    }

    // -- the save bodies ----------------------------------------------------

    /** Monday 17 Aug 2026, midnight UTC. */
    private val monday = 1_786_924_800_000L

    private fun draft() = TimecardDraft(
        timecardId = "tc-1",
        weekStarting = monday,
        days = listOf(
            TimecardDay(
                date = monday,
                dayType = DayType.Worked,
                callTime = "07:30",
                wrapTime = "18:00",
                workedHours = 10.5,
                allowances = listOf(Allowance("MP", "Meal penalty", 12.5)),
                note = null,
            ),
            TimecardDay(date = monday + DAY, dayType = DayType.Rest, callTime = null, wrapTime = null, note = null),
        ),
        notes = "left early",
        departmentId = "department_camera",
    )

    /**
     * The PATCH body, byte-exact: `buildSavePayload`'s day shape
     * (`WeeklyTimecardModule.jsx:566-821`, `4749-4771`) with no invented
     * keys — no `notes`, no `worked_hours`, no `break_minutes` — and no money
     * totals, which stay untouched on the PATCH.
     */
    @Test
    fun `the update body is the web's day shape, byte for byte`() {
        val workedDay = """{"date":1786924800000,"day_number":1,"day_type":"SWD","call_time":1786951800000,"wrap_time":1786989600000,"login_details":{"time":1786951800000,"timezone":"UTC"},"logout_details":{"time":1786989600000,"timezone":"UTC"},"timezone":"UTC","night_shoot":false,"ndm":false,"ndm_start_time":null,"ndm_end_time":null,"basic_hours":10.5,"minutes_worked":630,"rates_ots":[],"allowances":[{"identifier":"MP","label":"Meal penalty","raw_label":"Meal penalty","rate_type":"flat","rate_amount":12.5,"qty":1,"basis":null,"currency":null,"work_duration":0,"is_rental":false}],"additional_fees":[],"meals":[],"annotations":[]}"""
        val restDay = """{"date":1787011200000,"day_number":2,"day_type":"REST","call_time":null,"wrap_time":null,"login_details":{},"logout_details":{},"timezone":"UTC","night_shoot":false,"ndm":false,"ndm_start_time":null,"ndm_end_time":null,"basic_hours":0.0,"minutes_worked":0,"rates_ots":[],"allowances":[],"additional_fees":[],"meals":[],"annotations":[]}"""
        assertEquals(
            """{"days":[$workedDay,$restDay],"total_days":1,"total_hours":10.5,"total_allowances":12.5}""",
            draft().updateBody().toString(),
        )
    }

    /**
     * The CREATE body: `ensureTimecardId`'s skeleton
     * (`WeeklyTimecardModule.jsx:4785-4800`) — identity, the creator's IANA
     * timezone and department, and one identity row per day.
     */
    @Test
    fun `the create body is the web's skeleton, byte for byte`() {
        assertEquals(
            """{"week_starting":1786924800000,"timezone":"Europe/London","department_id":"department_camera","phase":"Production","days":[{"date":1786924800000,"day_number":1,"day_type":"SWD","timezone":"UTC","call_time":1786951800000,"wrap_time":1786989600000},{"date":1787011200000,"day_number":2,"day_type":"REST","timezone":"UTC","call_time":null,"wrap_time":null}]}""",
            draft().createBody(timezone = "Europe/London").toString(),
        )
    }

    // -- time and week arithmetic -------------------------------------------

    /** `hhmmToUtcEpoch` (`tzDate.js:212-218`): the wall-clock pinned to UTC. */
    @Test
    fun `worked times are stored as UTC wall-clock epochs`() {
        assertEquals(monday + 7 * HOUR + 30 * MINUTE, hhmmToUtcEpoch(monday, "07:30"))
        assertNull(hhmmToUtcEpoch(monday, null))
        assertNull(hhmmToUtcEpoch(null, "07:30"))
        assertNull(hhmmToUtcEpoch(monday, "25:00"), "not a clock time")
    }

    @Test
    fun `stored epochs read back as the same UTC wall-clock, strings pass through`() {
        assertEquals("07:30", wireTimeOfDay("1786951800000"))
        assertEquals("07:30", wireTimeOfDay("07:30"))
        assertNull(wireTimeOfDay(null))
        assertNull(wireTimeOfDay(" "))
    }

    /**
     * `startOfPeriodTz(now, tz, startDayOfWeek)` (`tzDate.js:106-129`): the
     * payroll listing's path segment. Wednesday 19 Aug 2026, 15:00 UTC.
     */
    @Test
    fun `the payroll week starts at the period start day's local midnight`() {
        val wednesday = monday + 2 * DAY + 15 * HOUR
        assertEquals(monday, payrollPeriodStart(wednesday, startDayOfWeek = 1, zone = TimeZone.UTC))
        // A Friday-anchored pay period reaches back into last week.
        assertEquals(monday - 3 * DAY, payrollPeriodStart(wednesday, startDayOfWeek = 5, zone = TimeZone.UTC))
        // The viewer's zone anchors midnight, exactly as browserTz() does.
        assertEquals(
            monday - IST_OFFSET,
            payrollPeriodStart(wednesday, startDayOfWeek = 1, zone = TimeZone.of("Asia/Kolkata")),
        )
        // Out-of-range config falls back to Monday, the web's `|| 1`.
        assertEquals(monday, payrollPeriodStart(wednesday, startDayOfWeek = 0, zone = TimeZone.UTC))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        /** IST is UTC+05:30, so IST midnight sits 5h30m before UTC midnight. */
        const val IST_OFFSET = 5 * HOUR + 30 * MINUTE
    }
}
