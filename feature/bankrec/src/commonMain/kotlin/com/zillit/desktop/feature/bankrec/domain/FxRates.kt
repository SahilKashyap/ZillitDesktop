package com.zillit.desktop.feature.bankrec.domain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a variance's budget rate came from. */
enum class FxRateSource {
    /** Production Setup → Project Currencies. Project configuration, so locked. */
    Settings,

    /** Nowhere: the currency is not configured, and the rate is typed at posting. */
    None,

    /** The rate the row was posted with. History, never re-derived. */
    Posted,
}

/**
 * One variance's two rates, and what may be done with them.
 *
 * [budgetEditable] is the whole point of the rule: a rate from Production Setup
 * is shown read-only, because changing it here would silently disagree with the
 * project. Only a *missing* rate may be typed, and only on an unposted row, so
 * it can be supplied at posting time. [ok] is whether the row can post
 * untouched.
 */
data class ResolvedFxRates(
    val isPosted: Boolean,
    val budget: Double?,
    val budgetSource: FxRateSource,
    val budgetEditable: Boolean,
    val bank: Double?,
) {
    val ok: Boolean get() = isPosted || (budget != null && bank != null)
}

/** A journal line of the FX preview. */
data class JournalLine(val nominal: String, val description: String, val debit: Double?, val credit: Double?)

/**
 * How an FX variance gets its rates — the web's `fxBudgetRate.js`.
 *
 * The budget rate comes from Production Setup → Project Currencies and
 * **nowhere else**. The service's own `budget_rate` column is deliberately not
 * a fallback on an unposted row: doing so replaces the dash the accountant is
 * meant to act on with a figure nobody agreed, and posts a variance off it. It
 * *is* the source for an already-posted row, which is history.
 *
 * Rates follow the project's `exr` semantics — foreign = default × rate — so
 * every amount derived from one divides.
 */
object FxRates {

    /** The rate for [invoiceCurrency] from the project's own currencies, and where it came from. */
    fun budgetRateFor(invoiceCurrency: String, rates: ProjectRates): Pair<Double?, FxRateSource> {
        val code = invoiceCurrency.trim().uppercase()
        // The project default is its own base: a rate of 1 by definition.
        if (code.isNotEmpty() && code == rates.defaultCode.trim().uppercase()) return 1.0 to FxRateSource.Settings
        val exr = rates.rateFor(code) ?: return null to FxRateSource.None
        return exr to FxRateSource.Settings
    }

    fun resolve(row: FxVariance, rates: ProjectRates): ResolvedFxRates {
        val bank = row.bankRate?.takeIf { it > 0 && it.isFinite() }
        if (row.isPosted) {
            return ResolvedFxRates(
                isPosted = true,
                budget = row.budgetRate?.takeIf { it > 0 && it.isFinite() },
                budgetSource = FxRateSource.Posted,
                budgetEditable = false,
                bank = bank,
            )
        }
        val (budget, source) = budgetRateFor(row.invoiceCurrency, rates)
        return ResolvedFxRates(
            isPosted = false,
            budget = budget,
            budgetSource = source,
            budgetEditable = budget == null,
            bank = bank,
        )
    }

    /** What the foreign amount is worth in the project's currency at [rate]; null without one. */
    fun converted(foreignAmount: Double, rate: Double?): Double? =
        rate?.takeIf { it > 0 }?.let { foreignAmount / it }

    /**
     * The journal the preview shows: two lines per variance.
     *
     * A loss debits FX (7850) and credits the bank (1200); a gain the reverse.
     * A variance of exactly zero reads as a gain, as the web reads it.
     */
    fun journal(rows: List<FxVariance>, defaultCode: String): List<JournalLine> = rows.flatMap { row ->
        val amount = abs(row.variance)
        val supplier = row.vendorName.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
            ?: row.invoiceCurrency.ifBlank { "FX" }
        if (row.variance < 0) {
            listOf(
                JournalLine(FX_NOMINAL, "FX Loss $supplier", debit = amount, credit = null),
                JournalLine(BANK_NOMINAL, "Bank $defaultCode variance", debit = null, credit = amount),
            )
        } else {
            listOf(
                JournalLine(BANK_NOMINAL, "FX Gain $supplier", debit = amount, credit = null),
                JournalLine(FX_NOMINAL, "FX Gain offset", debit = null, credit = amount),
            )
        }
    }

    /**
     * How full a row's bar is on the rates chart: the bank rate as a share of
     * the budget rate, capped at 100. Half a bar when there is no budget rate
     * to measure against, which is the web's neutral.
     */
    fun barPercent(resolved: ResolvedFxRates): Int {
        val budget = resolved.budget ?: return HALF
        if (budget == 0.0) return HALF
        return min(FULL, ((resolved.bank ?: 0.0) / budget * FULL).roundToInt())
    }

    /** Bank rate minus budget rate, as the chart prints it. */
    fun rateDelta(resolved: ResolvedFxRates): Double = (resolved.bank ?: 0.0) - (resolved.budget ?: 0.0)

    const val FX_NOMINAL = "7850"
    const val BANK_NOMINAL = "1200"
    private const val FULL = 100
    private const val HALF = 50
}
