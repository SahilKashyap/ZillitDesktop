package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.PayrollCurrencyRates
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.RunRow
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.run.moneyTotal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SummaryStrip`'s currency-aware totals — the exact live bug: a £259.56 row
 * and a ¥103.51 row used to face-value-sum to "£363.07" (adding pounds to
 * yen) instead of converting into the project default and summing there.
 */
class RunSummaryMoneyTest {

    private fun row(id: String, currency: String?) = RunRow(
        timecard = PayrollTimecard(id = id, userId = "u-$id", status = TimecardStatus.Locked, currency = currency),
        name = id,
        role = "",
        department = "",
    )

    /** The test's own amount table, keyed by row id — stands in for `RunRow.gross` etc. */
    private fun amounts(vararg pairs: Pair<String, Double>): (RunRow) -> Double {
        val byId = pairs.toMap()
        return { row -> byId.getValue(row.timecard.id) }
    }

    @Test
    fun `a single currency sums in that currency`() {
        val rates = PayrollCurrencyRates(defaultCode = "GBP")
        val rows = listOf(row("1", "GBP"), row("2", "GBP"))
        val (total, code) = RunRow.moneyTotal(rows, rates, amounts("1" to 100.0, "2" to 50.0))
        assertEquals(150.0, total, 0.001)
        assertEquals("GBP", code)
    }

    @Test
    fun `several currencies convert into the default through exr and sum`() {
        // exr is foreign-per-default: 1 CNY = 9.074 GBP here (GBP is default).
        val rates = PayrollCurrencyRates(defaultCode = "GBP", rates = mapOf("CNY" to 9.074))
        val rows = listOf(row("gbp1", "GBP"), row("cny1", "CNY"))
        val (total, code) = RunRow.moneyTotal(rows, rates, amounts("gbp1" to 259.56, "cny1" to 103.51))
        // 259.56 + (103.51 / 9.074) ~= 270.97 — NOT 363.07, the old face-value bug's answer.
        assertEquals(259.56 + 103.51 / 9.074, total, 0.01)
        assertEquals("GBP", code)
    }

    @Test
    fun `a zero-amount row never flips a single currency to mixed`() {
        val rates = PayrollCurrencyRates(defaultCode = "GBP")
        val rows = listOf(row("gbp1", "GBP"), row("usd0", "USD"))
        val (total, code) = RunRow.moneyTotal(rows, rates, amounts("gbp1" to 100.0, "usd0" to 0.0))
        assertEquals(100.0, total, 0.001)
        assertEquals("GBP", code)
    }

    @Test
    fun `a currency with no rate is added at face value, not dropped`() {
        val rates = PayrollCurrencyRates(defaultCode = "GBP")
        val rows = listOf(row("gbp1", "GBP"), row("eur1", "EUR"))
        val (total, code) = RunRow.moneyTotal(rows, rates, amounts("gbp1" to 100.0, "eur1" to 50.0))
        assertEquals(150.0, total, 0.001)
        assertEquals("GBP", code)
    }
}
