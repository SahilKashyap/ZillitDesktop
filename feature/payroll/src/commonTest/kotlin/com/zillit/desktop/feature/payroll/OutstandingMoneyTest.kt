package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.OutstandingRow
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.ProcessingRow
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `OutstandingRow`'s per-user totals — unlike the summary strip and
 * Processing's KPI tiles (which aggregate ACROSS crew and convert through
 * `exr`), the web's `aggregateOutstandingByUser` (`payrollData.js` 980-991)
 * sums one crew member's OWN weeks at face value with a plain `+=`, no
 * conversion. A live bug found and fixed the same day this was pinned: an
 * earlier revision of this code ran every Outstanding total through the
 * summary-strip's converter, turning red ios's ¥4,023.6 face-value total
 * into a wrong ¥15,033.87 by converting two already-correct GBP weeks into
 * CNY a second time. The fix this test pins is the label, not the sum: each
 * row now reads its OWN first week's currency instead of one currency
 * picked for the whole grid (`ProcessingGrid.kt`'s old
 * `firstNotNullOfOrNull`).
 */
class OutstandingMoneyTest {

    private fun week(id: String, currency: String, basic: Double) = ProcessingRow(
        PayrollTimecard(id = id, userId = "u1", status = TimecardStatus.Locked, currency = currency, basicPay = basic),
        weekStarting = 0L,
    )

    @Test
    fun `mixed-currency weeks sum at face value, labelled with the first week's currency`() {
        val row = OutstandingRow(
            userId = "u1",
            weeks = listOf(week("1", "GBP", 401.8), week("2", "CNY", 1740.0)),
        )
        val (total, code) = row.basicMoney
        assertEquals(401.8 + 1740.0, total, 0.001)
        assertEquals("GBP", code)
    }

    @Test
    fun `a single-currency member's weeks sum in that currency`() {
        val row = OutstandingRow(
            userId = "u2",
            weeks = listOf(week("1", "GBP", 100.0), week("2", "GBP", 50.0)),
        )
        val (total, code) = row.basicMoney
        assertEquals(150.0, total, 0.001)
        assertEquals("GBP", code)
    }
}
