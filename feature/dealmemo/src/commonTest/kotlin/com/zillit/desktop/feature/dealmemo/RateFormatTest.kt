package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.RateTierEntry
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rate and agreement formatters against the web's own rules
 * (`deal-memo/utils/rateFormat.js`, `DMConfigPage.jsx:59-233`) — the strings
 * every rate on a deal is read off.
 */
class RateFormatTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // -- money and symbols ---------------------------------------------------------------

    @Test
    fun `known codes take their symbol, unknown ones are glued on bare`() {
        assertEquals("£", RateFormat.currencySymbol("gbp"))
        assertEquals("CHF ", RateFormat.currencySymbol("CHF"), "Swiss francs keep the web's trailing space")
        assertEquals("MAD", RateFormat.currencySymbol("MAD"))
        assertEquals("", RateFormat.currencySymbol(null))
    }

    @Test
    fun `amounts group en-GB with at most two places, rounding the shortest decimal half up`() {
        assertEquals("1,250.5", RateFormat.groupAmountAuto(1250.5))
        assertEquals("25", RateFormat.groupAmountAuto(25.0))
        assertEquals("2.28", RateFormat.groupAmountAuto(2.275))
        assertEquals("1.01", RateFormat.groupAmountAuto(1.005), "toLocaleString rounds 1.005 up, not to 1.00")
        assertEquals("1,234,567.89", RateFormat.groupAmountAuto(1234567.891))
        assertEquals("-1,250", RateFormat.groupAmountAuto(-1250.0))
        assertEquals("1,250.00", RateFormat.groupAmount(1250.0))
    }

    // -- tiers ----------------------------------------------------------------------------

    @Test
    fun `a tier is its base, with the envelope only when it adds something`() {
        assertEquals("£429", RateFormat.formatTier(RateTierEntry(baseRate = 429.0), "GBP"))
        assertEquals("£429 (£380 – £500)", RateFormat.formatTier(RateTierEntry(429.0, 380.0, 500.0), "GBP"))
        assertEquals("£429", RateFormat.formatTier(RateTierEntry(429.0, 429.0, 429.0), "GBP"))
        assertEquals("≥ £380", RateFormat.formatTier(RateTierEntry(minRate = 380.0), "GBP"))
        assertEquals("≤ £500", RateFormat.formatTier(RateTierEntry(maxRate = 500.0), "GBP"))
        assertNull(RateFormat.formatTier(RateTierEntry(workHours = 10.0), "GBP"))
        assertEquals("—", RateFormat.formatRange(null, "GBP"))
    }

    @Test
    fun `rates carry their hours, and a day-type-only entry still prints`() {
        assertEquals(
            "£600/10hrs",
            RateFormat.formatRateWithHours(RateTierEntry(baseRate = 600.0, workHours = 10.0), "GBP"),
        )
        assertEquals("10hrs", RateFormat.formatRateWithHours(RateTierEntry(workHours = 10.0), "GBP"))
        val tiers = listOf(
            RateTierEntry(baseRate = 540.0, workHours = 10.0, dayType = "CWD"),
            RateTierEntry(baseRate = 594.0, workHours = 11.0, dayType = "SWD"),
            RateTierEntry(),
            RateTierEntry(dayType = "SCWD"),
        )
        assertEquals("£540/10hrs (CWD) · £594/11hrs (SWD) · — (SCWD)", RateFormat.formatTierArray(tiers, "GBP"))
        assertEquals("—", RateFormat.formatTierArray(listOf(RateTierEntry()), "GBP"))
        assertEquals("—", RateFormat.formatTierArray(emptyList(), "GBP"))
    }

    @Test
    fun `budgets abbreviate like the web, keep the trailing zero, and are strict below an open top`() {
        assertEquals("£2.50m – £30m", RateFormat.formatBudgetRange(2_500_000.0, 30_000_000.0, "GBP"))
        assertEquals("≥ £12.3k", RateFormat.formatBudgetRange(12_345.0, null, "GBP"))
        assertEquals("< £4m", RateFormat.formatBudgetRange(null, 4_000_000.0, "GBP"))
        assertEquals("£500 – £999", RateFormat.formatBudgetRange(500.0, 999.0, "GBP"))
        assertEquals(
            "£1.00m",
            RateFormat.formatBudgetRange(1_005_000.0, null, "GBP").removePrefix("≥ "),
            "toFixed rounds the binary value",
        )
        assertEquals("—", RateFormat.formatBudgetRange(null, null, "GBP"))
    }

    @Test
    fun `experience ranges print whole years`() {
        assertEquals("2–5y", RateFormat.formatExpRange(2.0, 5.0))
        assertEquals("3+y", RateFormat.formatExpRange(3.0, null))
        assertEquals("<2y", RateFormat.formatExpRange(null, 2.0))
    }

    @Test
    fun `production types go through the translations first`() {
        val translations = mapOf("feature_label" to "Feature Film")
        assertEquals("Feature Film", RateFormat.productionTypeLabel("feature_label", translations::get))
        assertEquals("Television", RateFormat.productionTypeLabel("television", translations::get))
        assertEquals("tvc_label", RateFormat.productionTypeLabel("tvc_label", translations::get))
        assertEquals("", RateFormat.productionTypeLabel(null, translations::get))
    }

    // -- agreement rules --------------------------------------------------------------------

    @Test
    fun `triggers read the way the web writes them`() {
        assertEquals("After 10h – 12h", AgreementFormat.trigger(json("""{"after":600,"before":720}""")).main)
        assertEquals("After 23:00", AgreementFormat.trigger(json("""{"clock":true,"after":1380}""")).main)
        assertEquals("Meal not within 5h 30m", AgreementFormat.trigger(json("""{"meal":true,"after":330}""")).main)
        assertEquals(
            "6th consecutive day",
            AgreementFormat.trigger(json("""{"day_number":6,"consecutive":true}""")).main,
        )
        assertEquals("First 2h", AgreementFormat.trigger(json("""{"always":true,"before":120}""")).main)
        assertEquals(
            "Bank holiday · As called",
            AgreementFormat.trigger(json("""{"day_kind":"bank_holiday","on_call":true}""")).main,
        )
        assertEquals(
            "4h guarantee · max 2/wk · 15-min billing",
            AgreementFormat.trigger(json("""{"guarantee":240,"max_occurrences":2,"increment":15}""")).meta,
        )
        assertEquals("—", AgreementFormat.trigger(null).main)
    }

    @Test
    fun `the primary trigger is the standard day's, then an untyped one, then the first`() {
        val row = json("""{"triggers":[{"day_type":"CWD","after":540},{"after":600},{"day_type":"SWD","after":660}]}""")
        assertEquals("After 11h", AgreementFormat.trigger(AgreementFormat.primaryTrigger(row)).main)
        val untyped = json("""{"triggers":[{"day_type":"CWD","after":540},{"after":600}]}""")
        assertEquals("After 10h", AgreementFormat.trigger(AgreementFormat.primaryTrigger(untyped)).main)
    }

    @Test
    fun `compensation prints the currency code on flats, and an absent basis reads event`() {
        assertEquals(
            "GBP25/day",
            AgreementFormat.compensation(json("""{"rate_type":"flat","rate_amount":25,"basis":"day"}"""), "GBP"),
        )
        assertEquals(
            "GBP25/day",
            AgreementFormat.compensation(json("""{"rate_type":"fixed","rate_amount":25,"basis":"day"}"""), "GBP"),
        )
        assertEquals(
            "12% of event",
            AgreementFormat.compensation(json("""{"rate_type":"percentage","rate_amount":12}"""), "GBP"),
        )
        assertEquals("×1.5T", AgreementFormat.compensation(json("""{"multiplier":1.5}"""), "GBP"))
        assertEquals(
            "+0.5T",
            AgreementFormat.compensation(
                json("""{"rate_type":"multiplier","rate_amount":0.5,"is_enhancement":true}"""),
                "GBP",
            ),
        )
        assertEquals("OT rate", AgreementFormat.compensation(json("""{"use_ot_rate":true,"multiplier":2}"""), "GBP"))
        assertEquals("Actuals", AgreementFormat.compensation(json("""{"rate_type":"actuals"}"""), "GBP"))
        assertEquals(
            "Class",
            AgreementFormat.compensation(json("""{"rate_type":"class","basis":"club_class"}"""), "GBP"),
        )
        assertEquals("—", AgreementFormat.compensation(json("""{}"""), "GBP"))
    }

    @Test
    fun `caps are always per hour and use the symbol`() {
        assertEquals("£40–£60/hr", AgreementFormat.cap(json("""{"min":40,"max":60}"""), "GBP"))
        assertEquals("≤ £60/hr", AgreementFormat.cap(json("""{"max":60}"""), "GBP"))
        assertEquals("≥ CAD40/hr".replace("CAD", "C$"), AgreementFormat.cap(json("""{"min":40}"""), "CAD"))
        assertNull(AgreementFormat.cap(json("""{}"""), "GBP"))
        assertEquals("weekly_rate_x", AgreementFormat.basisLookup("weekly_rate_x"))
        assertEquals("Weekly gross", AgreementFormat.basisLookup("weekly_gross"))
        assertEquals("club class", AgreementFormat.basisLabel("club class"))
    }

    @Test
    fun `effective ranges name months in the reader's zone`() {
        val april = 1_711_929_600_000L // 1 Apr 2024 00:00 UTC
        val march = 1_774_915_200_000L // 31 Mar 2026 00:00 UTC
        assertEquals("Apr 2024 – Mar 2026", AgreementFormat.effectiveRange(april, march, TimeZone.UTC))
        assertEquals("from Apr 2024", AgreementFormat.effectiveRange(april, null, TimeZone.UTC))
        assertEquals("until Mar 2026", AgreementFormat.effectiveRange(null, march, TimeZone.UTC))
        assertEquals("", AgreementFormat.effectiveRange(null, null, TimeZone.UTC))
    }
}
