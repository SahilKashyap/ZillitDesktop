package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.data.PayrollMetadataDto
import com.zillit.desktop.feature.payroll.data.PostOutcomeDto
import com.zillit.desktop.feature.payroll.data.TolerantWeeklyQueue
import com.zillit.desktop.feature.payroll.data.WeeklyQueueDto
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire shapes, as the dev server actually answers them.
 *
 * ## Why these are captured verbatim
 *
 * Every payload below was copied out of a real `develop` response. Three of the
 * routes this module reads were originally implemented against a shape that
 * looked obvious and was wrong, and two of those failed **silently** — an empty
 * list where an object was expected reads as "no data", not as a bug, so
 * nothing surfaced until the traffic was inspected by hand.
 *
 * A test that constructs its own JSON cannot catch that class of mistake: it
 * only ever proves the parser agrees with the author. These do, because the
 * input is the server's.
 */
class PayrollWireShapeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * `/api/v2/payroll/weekly/processing` — an object, not an array.
     *
     * The sibling list routes answer with a bare array, which is what made the
     * mistake easy. Decoding this as one throws.
     */
    @Test
    fun `the weekly queue is an object carrying the week it answered for`() {
        val body = """
            {"week_starting":1785715200000,"timezone":"UTC","timecards":[]}
        """.trimIndent()

        val week = json.decodeFromString(WeeklyQueueDto.serializer(), body)
            .toDomain(fallbackWeekStarting = null)

        assertEquals(1_785_715_200_000, week.weekStarting)
        assertTrue(week.lines.isEmpty())
    }

    @Test
    fun `the week the server names wins over the week we asked for`() {
        // The unscoped route is asked for no week at all, so there is nothing to
        // fall back to; the scoped one must still take the server's answer, or
        // a pay period that does not start on a Monday drifts.
        val body = """{"week_starting":1785715200000,"timezone":"UTC","timecards":[]}"""
        val week = json.decodeFromString(WeeklyQueueDto.serializer(), body)
            .toDomain(fallbackWeekStarting = 1_754_000_000_000)
        assertEquals(1_785_715_200_000, week.weekStarting)
    }

    /**
     * The week-scoped route answers a bare array, the unscoped one an object.
     *
     * Same route name, different shape depending on whether the week is in the
     * path — which only shows up when a past week is actually clicked, because
     * the tool opens on the unscoped one.
     */
    @Test
    fun `the week-scoped route answers a bare array and still reads`() {
        val week = json.decodeFromString(TolerantWeeklyQueue, "[]")
            .toDomain(fallbackWeekStarting = 1_784_505_600_000)
        assertEquals(1_784_505_600_000, week.weekStarting)
        assertTrue(week.lines.isEmpty())
    }

    @Test
    fun `a bare array of timecards reads the same as a wrapped one`() {
        val body = """
            [{"_id":"tc1","user_id":"u1","status":"paid","currency":"GBP",
              "basic_pay":1000,"deductions":[],"total_pay":1000}]
        """.trimIndent()
        val week = json.decodeFromString(TolerantWeeklyQueue, body)
            .toDomain(fallbackWeekStarting = 1_784_505_600_000)
        assertEquals("tc1", week.lines.single().id)
        assertEquals(TimecardStatus.Paid, week.lines.single().status)
    }

    @Test
    fun `the object form still reads through the tolerant reader`() {
        val body = """{"week_starting":1785715200000,"timezone":"UTC","timecards":[]}"""
        val week = json.decodeFromString(TolerantWeeklyQueue, body).toDomain(null)
        assertEquals(1_785_715_200_000, week.weekStarting)
    }

    @Test
    fun `a queue with no week named falls back to the week we asked for`() {
        val week = json.decodeFromString(WeeklyQueueDto.serializer(), """{"timecards":[]}""")
            .toDomain(fallbackWeekStarting = 1_754_000_000_000)
        assertEquals(1_754_000_000_000, week.weekStarting)
    }

    /**
     * A full timecard, as the queue returns them.
     *
     * Note `deductions` is a **list of rows**, not a total: reading it as a
     * scalar would silently make every net equal its gross.
     */
    @Test
    fun `a timecard's deductions are rows to be summed, not a total`() {
        val body = """
            {"week_starting":1785715200000,"timezone":"UTC","timecards":[
              {"_id":"tc1","user_id":"u1","full_name":"Ada Lovelace",
               "department_id":"d1","designation":"Gaffer","status":"approved",
               "currency":"GBP","basic_pay":1400,"overtime_pay":220,
               "total_allowances":65,"additional_fees":0,
               "deductions":[{"amount":60},{"amount":40}],
               "total_pay":1685}
            ]}
        """.trimIndent()

        val line = json.decodeFromString(WeeklyQueueDto.serializer(), body)
            .toDomain(null).lines.single()

        assertEquals("tc1", line.id)
        assertEquals("u1", line.crewId)
        assertEquals(TimecardStatus.Approved, line.status)
        assertEquals(100.0, line.deductions)
        assertEquals(1_685.0, line.gross)
        assertEquals(1_585.0, line.net)
    }

    @Test
    fun `a draft with no persisted total falls back to the sum of its parts`() {
        // Amounts arrive as JSON numbers here and as strings elsewhere,
        // depending on the column; both must read.
        val body = """
            {"timecards":[
              {"_id":"tc2","user_id":"u2","status":"draft","currency":"GBP",
               "basic_pay":"1000","overtime_pay":"200","total_allowances":"50",
               "deductions":[],"total_pay":0}
            ]}
        """.trimIndent()

        val line = json.decodeFromString(WeeklyQueueDto.serializer(), body)
            .toDomain(null).lines.single()

        assertEquals(1_250.0, line.gross)
        assertEquals(1_250.0, line.net)
    }

    @Test
    fun `a timecard with no id is dropped rather than shown as a blank row`() {
        val body = """{"timecards":[{"user_id":"u1","status":"approved"}]}"""
        val week = json.decodeFromString(WeeklyQueueDto.serializer(), body).toDomain(null)
        assertTrue(week.lines.isEmpty())
    }

    /**
     * `/api/v2/payroll/metadata` — snake_case, unlike its own documentation.
     *
     * The web reference documents `{ isFinalApprover }`; dev answers
     * `is_final_approver`. Both are read, because a flag that silently stays
     * false takes away the post button with nothing to explain it.
     */
    @Test
    fun `the final-approver flag reads in either spelling`() {
        val snake = """{"is_final_approver":true,"pay_period":{"start_day_of_week":1}}"""
        val camel = """{"isFinalApprover":true}"""
        assertEquals(true, json.decodeFromString(PayrollMetadataDto.serializer(), snake).isFinalApprover)
        assertEquals(true, json.decodeFromString(PayrollMetadataDto.serializer(), camel).isFinalApprover)
        assertEquals(
            false,
            json.decodeFromString(PayrollMetadataDto.serializer(), """{"is_final_approver":false}""")
                .isFinalApprover,
        )
    }

    @Test
    fun `a batch post reports what it moved and what it skipped`() {
        val outcome = json.decodeFromString(PostOutcomeDto.serializer(), """{"marked":3,"skipped":2}""")
            .toDomain()
        assertEquals(3, outcome.marked)
        assertEquals(2, outcome.skipped)
        // A response that mentions neither moved nothing, which is worth saying
        // out loud rather than reading as success.
        assertEquals(0, json.decodeFromString(PostOutcomeDto.serializer(), "{}").toDomain().marked)
    }
}
