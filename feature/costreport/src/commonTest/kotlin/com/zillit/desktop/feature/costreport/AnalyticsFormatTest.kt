package com.zillit.desktop.feature.costreport

import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.ForecastData
import com.zillit.desktop.feature.costreport.domain.analytics.ForecastView
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every expectation here is what a browser's en-GB `Intl.NumberFormat` prints
 * for the web's `money` / `intFmt` / `pctFmt` / `deltaStr` — captured from
 * Node (ICU 78, CLDR 48), the same data Chrome ships.
 */
class AnalyticsFormatTest {

    private fun gbp(value: Double) = AnalyticsFormat.money(value, "GBP")

    @Test
    fun moneyIsCompactWithOneDecimalUnderAMillionAndTwoAbove() {
        assertEquals("£0", gbp(0.0))
        assertEquals("£0.1", gbp(0.05))
        assertEquals("£12.5", gbp(12.5))
        assertEquals("£999", gbp(999.0))
        assertEquals("£1.2k", gbp(1234.56))
        assertEquals("£12.3k", gbp(12345.0))
        assertEquals("£54.2k", gbp(54200.0))
        assertEquals("£950k", gbp(950000.0))
        assertEquals("£1.23m", gbp(1234567.0))
        assertEquals("£1.91m", gbp(1905000.0))
        assertEquals("£12.35m", gbp(12345678.0))
        assertEquals("£123.46m", gbp(123456789.0))
        assertEquals("£1.5tn", gbp(1.5e12))
    }

    @Test
    fun moneyRoundsTheDecimalItPrintsAndCarriesIntoTheNextTier() {
        assertEquals("£10", gbp(9.95))
        assertEquals("£1k", gbp(999.95))
        assertEquals("£999.9k", gbp(999949.0))
        assertEquals("£1m", gbp(999999.0))
        assertEquals("£1m", gbp(999950.0))
        assertEquals("£1.26m", gbp(1255000.0))
        assertEquals("£1.27m", gbp(1265000.0))
        assertEquals("£1.13m", gbp(1125000.0))
        assertEquals("£999.99m", gbp(999994999.0))
        assertEquals("£1bn", gbp(999995000.0))
        assertEquals("£0.2", gbp(0.15))
        assertEquals("£1.3k", gbp(1250.0))
    }

    @Test
    fun moneyKeepsTheSignAndGroupsOnlyLongTierNumbers() {
        assertEquals("-£1.5k", gbp(-1500.0))
        assertEquals("-£1.23m", gbp(-1234567.0))
        assertEquals("-£0", gbp(-0.04))
        assertEquals("£1000tn", gbp(1e15))
        assertEquals("£10,000tn", gbp(1e16))
    }

    @Test
    fun moneySpellsEachCurrencyTheWayEnGbDoes() {
        assertEquals("US$54.2k", AnalyticsFormat.money(54200.0, "USD"))
        assertEquals("€1.23m", AnalyticsFormat.money(1234567.0, "EUR"))
        assertEquals("₹999", AnalyticsFormat.money(999.0, "INR"))
        assertEquals("CA$1k", AnalyticsFormat.money(1000.0, "CAD"))
        assertEquals("CHF 12.3k", AnalyticsFormat.money(12345.0, "CHF"))
        assertEquals("-SEK 1.5k", AnalyticsFormat.money(-1500.0, "SEK"))
        assertEquals("£1.2k", AnalyticsFormat.money(1234.5, "gbp"))
        assertEquals("£1.2k", AnalyticsFormat.money(1234.5, null))
        assertEquals("£1.2k", AnalyticsFormat.money(1234.5, ""))
        // Not a currency code: the web's catch prints the code and a rounded whole number.
        assertEquals("POUND 1,235", AnalyticsFormat.money(1234.5, "POUND"))
        assertEquals("£ -1,234", AnalyticsFormat.money(-1234.5, "£"))
        assertEquals("—", AnalyticsFormat.money(null, "GBP"))
        assertEquals("—", AnalyticsFormat.money(Double.NaN, "GBP"))
    }

    @Test
    fun intPctAndDeltaMatchTheWeb() {
        assertEquals("1,234", AnalyticsFormat.int(1234.0))
        assertEquals("1,234.568", AnalyticsFormat.int(1234.5678))
        assertEquals("-1,234.5", AnalyticsFormat.int(-1234.5))
        assertEquals("0.002", AnalyticsFormat.int(0.0015))
        assertEquals("1,000", AnalyticsFormat.int(999.9995))
        assertEquals("—", AnalyticsFormat.int(null))

        assertEquals("12.3%", AnalyticsFormat.pct(12.34))
        assertEquals("12.4%", AnalyticsFormat.pct(12.35))
        assertEquals("0%", AnalyticsFormat.pct(-0.05))
        assertEquals("-2.5%", AnalyticsFormat.pct(-2.55))
        assertEquals("100%", AnalyticsFormat.pct(100.0))

        assertEquals("+12.5%", AnalyticsFormat.delta(12.5, "pct", null, "GBP"))
        assertEquals("−3%", AnalyticsFormat.delta(-3.0, null, null, "GBP"))
        assertEquals("0%", AnalyticsFormat.delta(0.0, "pct", null, "GBP"))
        assertEquals("+£1.5k", AnalyticsFormat.delta(1500.0, "abs", "money", "GBP"))
        assertEquals("−1,500", AnalyticsFormat.delta(-1500.0, "abs", "int", "GBP"))
        // Only a money figure's absolute delta is money — an unset fmt is not.
        assertEquals("−1,500", AnalyticsFormat.delta(-1500.0, "abs", null, "GBP"))
        assertEquals(null, AnalyticsFormat.delta(null, "abs", "money", "GBP"))
    }

    @Test
    fun valueFollowsItsFmtVocabulary() {
        assertEquals("£1.2k", AnalyticsFormat.value("1234", null, "GBP"))
        assertEquals("1,234", AnalyticsFormat.value("1234", "int", "GBP"))
        assertEquals("42.5%", AnalyticsFormat.value("42.5", "pct", "GBP"))
        assertEquals("2.5×", AnalyticsFormat.value("2.5", "x", "GBP"))
        assertEquals("—", AnalyticsFormat.value(null, "x", "GBP"))
        assertEquals("7/12", AnalyticsFormat.value("7", "ratio", "GBP", ratioOf = 12.0))
        assertEquals("On track", AnalyticsFormat.value("On track", "text", "GBP"))
        assertEquals("—", AnalyticsFormat.value("n/a", "money", "GBP"))
        assertEquals("34%", AnalyticsFormat.share(34.0, 100.0))
        assertEquals("", AnalyticsFormat.share(34.0, 0.0))
    }

    @Test
    fun jsNumberPrintsLikeJavaScript() {
        assertEquals("0.30000000000000004", AnalyticsFormat.jsNumber(0.1 + 0.2))
        assertEquals("123456789012", AnalyticsFormat.jsNumber(123456789012.0))
        assertEquals("2.5", AnalyticsFormat.jsNumber(2.5))
        assertEquals("12345678.5", AnalyticsFormat.jsNumber(12345678.5))
        assertEquals("0", AnalyticsFormat.jsNumber(-0.0))
    }

    @Test
    fun forecastRescalesOnceAndDerivesVarianceFromItsSign() {
        val view = ForecastView.of(
            ForecastData(
                tone = "blue",
                cum = listOf(100_000.0, 250_000.0, 400_000.0),
                proj = listOf(400_000.0, 700_000.0, 1_090_000.0),
                budget = 1_050_000.0,
                actual = 400_000.0,
                committed = 250_000.0,
                etc = 440_000.0,
                total = 6,
                wrap = "14 Nov",
            ),
            "GBP",
        )
        assertEquals(true, view.inMillions)
        assertEquals(1.09, view.efc, 1e-9)
        assertEquals(1.05, view.budget, 1e-9)
        assertEquals("£1.09m", view.efcText)
        assertEquals("+£40k", view.varianceText)
        assertEquals("+3.8%", view.variancePercentText)
        assertEquals("over budget", view.varianceLabel)
        assertEquals("red", view.varianceTone)
        assertEquals(3, view.currentWeek)
        assertEquals(listOf("W1", "W2", "W3", "W4", "W5", "W6"), view.labels)
        assertEquals(4, view.projectionEnd)

        val under = ForecastView.of(ForecastData(actual = 20_000.0, budget = 50_000.0), "USD")
        assertEquals(false, under.inMillions)
        assertEquals("−US$30k", under.varianceText)
        assertEquals("−60.0%", under.variancePercentText)
        assertEquals("under budget", under.varianceLabel)
        assertEquals(1, under.totalWeeks)
        assertEquals("amber", under.tone)
    }
}
