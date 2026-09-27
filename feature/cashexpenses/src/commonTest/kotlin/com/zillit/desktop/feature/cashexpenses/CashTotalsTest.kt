package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrency
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.pages.describeTotal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `describeTotal` — the Active Floats/Sign Off "outstanding" total across
 * currencies. It used to join one figure per currency ("¥146,294.00 +
 * $1,000.00") because no rate ever reached the module; now the same
 * convert-and-sum rule as Purchase Orders' `totalValue` applies.
 */
class CashTotalsTest {

    private val viewer =
        CashViewer(userId = "u", departmentIdentifier = "department_accounts", designationIdentifier = null)

    private fun state(currencies: CashCurrencies) = CashUiState(
        viewer = viewer,
        destination = CashDestination.PettyCashOverview,
        currencies = currencies,
    )

    @Test
    fun `a single currency sums in that currency`() {
        val yen = state(CashCurrencies(listOf(CashCurrency("JPY", "¥")), defaultCode = "JPY"))
        val total = yen.describeTotal(listOf("JPY" to 100_000.0, "JPY" to 46_294.0))
        assertEquals(yen.formatMoney(146_294.0, "JPY"), total)
    }

    @Test
    fun `several currencies convert into the default through exr and sum`() {
        // exr is foreign-per-default: 1 GBP = 146.5 JPY here, so $1,000 has no
        // rate of its own but JPY is the default and needs none.
        val currencies = CashCurrencies(
            currencies = listOf(CashCurrency("JPY", "¥"), CashCurrency("USD", "$")),
            defaultCode = "JPY",
            rates = mapOf("USD" to (1.0 / 6.76)),
        )
        val amounts = listOf("JPY" to 146_294.0, "USD" to 1_000.0)
        val converted = currencies.toDefault(1_000.0, "USD")
        assertEquals(6760.0, converted!!, 0.5)
        val total = state(currencies).describeTotal(amounts)
        assertEquals(state(currencies).formatMoney(146_294.0 + converted, "JPY"), total)
    }

    @Test
    fun `a currency with no rate is added at face value, not dropped`() {
        val currencies = CashCurrencies(
            currencies = listOf(CashCurrency("GBP", "£"), CashCurrency("EUR", "€")),
            defaultCode = "GBP",
        )
        val total = state(currencies).describeTotal(listOf("GBP" to 100.0, "EUR" to 50.0))
        assertEquals(state(currencies).formatMoney(150.0, "GBP"), total)
    }

    @Test
    fun `toDefault is null only when a non-default currency has no rate`() {
        val currencies = CashCurrencies(defaultCode = "GBP", rates = mapOf("EUR" to 1.17))
        assertEquals(100.0, currencies.toDefault(100.0, "GBP"))
        assertEquals(100.0 / 1.17, currencies.toDefault(100.0, "EUR")!!, 0.0001)
        assertNull(currencies.toDefault(100.0, "USD"))
    }

    @Test
    fun `no records at all reads as zero in the default currency`() {
        val currencies = CashCurrencies(defaultCode = "GBP")
        assertEquals(state(currencies).formatMoney(0.0, "GBP"), state(currencies).describeTotal(emptyList()))
    }
}
