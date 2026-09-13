package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateResolve
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Rates step's tier picking, auto-apply and day/weekly coupling, as `Step5Rates.jsx` runs them. */
class RateResolveTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun form(text: String) = DealForm(json(text))

    private val agreement = json(
        """{"basic_rate_details":{
             "daily":{"base_rate":300,"work_hrs":10},
             "weekly":{"base_rate":1500,"work_hrs":50}}}""",
    )

    private val chosen = json(
        """{"_id":"rate-1","daily":[{"base_rate":320,"work_hrs":10}],"weekly":[{"base_rate":1600,"work_hrs":50}]}""",
    )

    private val role = """"union":"pact","designation":"focus_puller""""

    // -- tiers ---------------------------------------------------------------------------------------

    @Test
    fun `a tier picks the entry on the agreement's hours, else the untyped one, else the first`() {
        val tier = Json.parseToJsonElement(
            """[{"day_type":"CWD","work_hrs":10,"base_rate":1},{"day_type":null,"work_hrs":9,"base_rate":2},
               {"day_type":"SWD","work_hrs":11,"base_rate":3}]""",
        )
        assertEquals("3", RateResolve.pickTierEntry(tier, JsonPrimitive("11"))?.get("base_rate").toString())
        assertEquals("2", RateResolve.pickTierEntry(tier, JsonPrimitive(12))?.get("base_rate").toString())
        val typedOnly =
            Json.parseToJsonElement("""[{"day_type":"CWD","base_rate":1},{"day_type":"SWD","base_rate":3}]""")
        assertEquals("1", RateResolve.pickTierEntry(typedOnly, null)?.get("base_rate").toString())
        assertNull(RateResolve.pickTierEntry(JsonArray(emptyList()), null))
        assertNull(RateResolve.pickTierEntry(json("""{"base_rate":1}"""), null), "a tier that isn't a list is nothing")
    }

    @Test
    fun `a tier the card leaves empty borrows the agreement's figures and says so`() {
        val effective = RateResolve.effective(json("""{"_id":"rate-2","daily":[{"work_hrs":10}]}"""), agreement)
        val daily = assertNotNull(effective.daily)
        assertEquals("300", daily.base.toString())
        assertTrue(daily.baseFromUnion)
        assertTrue(effective.usedUnionFallback)
        assertEquals(5.0, effective.daysPerWeek)
    }

    // -- auto-apply ----------------------------------------------------------------------------------

    @Test
    fun `the published scale fills both rates once per tuple`() {
        val effective = RateResolve.effective(chosen, agreement)
        val blank = form("""{$role,"dayRate":"","weeklyRate":""}""")
        val applied = assertNotNull(RateResolve.autoApplied(blank, effective, chosen))

        assertEquals("320", applied.text("dayRate"))
        assertEquals("1600", applied.text("weeklyRate"))
        assertEquals("320", applied.text("rateApiDay"))
        assertEquals("1600", applied.text("rateApiWeekly"))
        assertEquals("pact|focus_puller|||rate-1", applied.text("rateAutoKey"))
        assertNull(RateResolve.autoApplied(applied, effective, chosen), "the same tuple never applies twice")
    }

    @Test
    fun `rates typed in the same context survive the next answer`() {
        val effective = RateResolve.effective(chosen, agreement)
        val applied = assertNotNull(RateResolve.autoApplied(form("""{$role}"""), effective, chosen))
        val typed = RateResolve.withDayRate(applied, JsonPrimitive("350"), effective.daysPerWeek)

        val next = assertNotNull(RateResolve.autoApplied(typed, effective, chosen))
        assertEquals("350", next.text("dayRate"), "the context is unchanged, so the typed rate stays")
        assertEquals("1750.00", next.text("weeklyRate"))
        assertEquals("320", next.text("rateApiDay"), "the published baseline still updates")
        assertTrue(RateResolve.isManual(next))
    }

    @Test
    fun `a new designation replaces typed rates with its own scale`() {
        val effective = RateResolve.effective(chosen, agreement)
        val applied = assertNotNull(RateResolve.autoApplied(form("""{$role}"""), effective, chosen))
        val typed = RateResolve.withDayRate(applied, JsonPrimitive("350"), effective.daysPerWeek)
            .with("designation", "gaffer")

        val next = assertNotNull(RateResolve.autoApplied(typed, effective, chosen))
        assertEquals("320", next.text("dayRate"))
        assertEquals("1600", next.text("weeklyRate"))
        assertFalse(RateResolve.isManual(next))
    }

    @Test
    fun `a tuple that publishes nothing never blanks the agreed rates`() {
        val typed = form("""{$role,"dayRate":"410","weeklyRate":"2050","rateApiDay":"320","rateAutoKey":"x|y|||r"}""")

        val next = assertNotNull(RateResolve.autoApplied(typed, RateResolve.effective(null, null), null))
        assertEquals("410", next.text("dayRate"))
        assertEquals("2050", next.text("weeklyRate"))
        assertEquals("", next.text("rateApiDay"))
        assertEquals("pact|focus_puller|||", next.text("rateAutoKey"))
    }

    @Test
    fun `a missing tier is derived from the other through the hours ratio`() {
        val dayOnly = json("""{"_id":"r","daily":[{"base_rate":320,"work_hrs":10}]}""")
        val (day, week) = RateResolve.effective(dayOnly, agreement).let { RateResolve.apiRates(it.daily, it.weekly) }
        assertEquals("320" to "1500", day to week, "the agreement's weekly base still counts as published")

        val noWeeklyBase = json("""{"basic_rate_details":{"daily":{"work_hrs":10},"weekly":{"work_hrs":50}}}""")
        val derived = RateResolve.effective(dayOnly, noWeeklyBase)
        assertEquals("320" to "1600.00", RateResolve.apiRates(derived.daily, derived.weekly))

        val weeklyCard = json("""{"_id":"r","weekly":[{"base_rate":1600,"work_hrs":50}]}""")
        val weekOnly = RateResolve.effective(weeklyCard, noWeeklyBase)
        assertEquals("320.00" to "1600", RateResolve.apiRates(weekOnly.daily, weekOnly.weekly))
    }

    // -- editing -------------------------------------------------------------------------------------

    @Test
    fun `each rate drives the other at the days per week, stamped with the bare context`() {
        val start = form("""{$role,"pactBand":"b2"}""")

        val byDay = RateResolve.withDayRate(start, JsonPrimitive("300"), 5.5)
        assertEquals("1650.00", byDay.text("weeklyRate"))
        assertEquals("pact|focus_puller|b2|", byDay.text("rateAutoKey"))

        val byWeek = RateResolve.withWeeklyRate(start, JsonPrimitive("1000"), 3.0)
        assertEquals("333.33", byWeek.text("dayRate"))
        assertEquals("0.00", RateResolve.withDayRate(start, JsonPrimitive("abc"), 5.0).text("weeklyRate"))
    }

    @Test
    fun `manual compares numbers, so a reformatted baseline is not an override`() {
        fun rates(day: String, api: String) =
            form("""{"dayRate":"$day","rateApiDay":"$api","weeklyRate":"","rateApiWeekly":""}""")
        assertFalse(RateResolve.isManual(rates(day = "780.00", api = "780")))
        assertTrue(RateResolve.isManual(rates(day = "781", api = "780")))
        assertFalse(
            RateResolve.isManual(form("""{"dayRate":"500","rateApiDay":"","weeklyRate":"","rateApiWeekly":""}""")),
            "nothing was published, so there is nothing to differ from",
        )
    }

    @Test
    fun `reset puts the scale back and clears the key so the next answer applies`() {
        val effective = RateResolve.effective(chosen, agreement)
        val reset = RateResolve.resetToScale(form("""{$role,"dayRate":"999","rateAutoKey":"k"}"""), effective)
        assertEquals("320", reset.text("dayRate"))
        assertEquals("1600", reset.text("rateApiWeekly"))
        assertEquals("", reset.text("rateAutoKey"))
        assertNotNull(RateResolve.autoApplied(reset, effective, chosen))
    }

    @Test
    fun `a banded agreement with no band picked holds the rate lookup back`() {
        val banded = json("""{"pact":{"bands":[{"id":"b1"}]}}""")
        assertTrue(RateResolve.missingBand(form("""{$role}"""), banded))
        assertFalse(RateResolve.missingBand(form("""{$role,"pactBand":"b1"}"""), banded))
        assertFalse(RateResolve.missingBand(form("""{$role}"""), agreement))
    }

    @Test
    fun `several schedules pick the chosen key and label themselves by hours`() {
        val rates = listOf(
            json("""{"schedule_key":"10hr","daily":{"work_hrs":10},"weekly":{"work_hrs":50}}"""),
            json("""{"schedule_key":"11hr","daily":{"work_hrs":11}}"""),
        )
        assertEquals(rates[1], RateResolve.chosenRate(rates, form("""{"selectedScheduleKey":"11hr"}""")))
        assertEquals(rates[0], RateResolve.chosenRate(rates, form("""{"selectedScheduleKey":"12hr"}""")))
        assertEquals("10hr daily / 50hr weekly", RateResolve.scheduleLabel(rates[0]))
        assertEquals("Default schedule", RateResolve.scheduleLabel(json("{}")))
    }
}
