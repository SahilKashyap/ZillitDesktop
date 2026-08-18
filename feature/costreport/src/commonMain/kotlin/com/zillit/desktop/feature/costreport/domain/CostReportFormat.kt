package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.common.Money
import kotlin.math.abs
import kotlin.math.roundToLong

/** The grouped header the eleven numeric columns sit under. */
enum class CrColumnGroup(val label: String) {
    Actuals("Actuals"),
    Commitments("Commitments"),
    Forecast("Forecast"),
    Budget(""),
    Analysis("Analysis"),
}

/** The eleven numeric columns, in worksheet order (spec §2.2). */
enum class CrColumn(val group: CrColumnGroup, val line1: String, val line2: String, val key: String) {
    Atp(CrColumnGroup.Actuals, "Actuals", "This Period", "atp"),
    Atd(CrColumnGroup.Actuals, "Actuals", "To Date", "atd"),
    Po(CrColumnGroup.Commitments, "PO", "Commits", "po"),
    Card(CrColumnGroup.Commitments, "Card", "Commits", "card"),
    Cash(CrColumnGroup.Commitments, "Cash", "Commits", "cash"),
    Pr(CrColumnGroup.Commitments, "Payroll", "Commits", "pr"),
    Etc(CrColumnGroup.Forecast, "Estimate", "To Complete", "etc"),
    Efc(CrColumnGroup.Forecast, "Estimated", "Final Cost", "efc"),
    Bud(CrColumnGroup.Budget, "Budget", "", "bud"),
    Tv(CrColumnGroup.Analysis, "Total", "Variance", "tv"),
    Vtp(CrColumnGroup.Analysis, "Variance", "This Period", "vtp"),
    ;

    val isVariance: Boolean get() = this == Tv || this == Vtp
    val isActuals: Boolean get() = group == CrColumnGroup.Actuals
    val isCommits: Boolean get() = group == CrColumnGroup.Commitments

    /** Set rows only carry actuals and commitments; forecast, budget and analysis read "—". */
    val onSets: Boolean get() = isActuals || isCommits

    /** The posted-snapshot table has no this-period, ETC or VTP figures. */
    val inSnapshot: Boolean get() = this != Atp && this != Etc && this != Vtp

    /** The ledger source the Commits cell drills into; null for the actuals and non-drillable columns. */
    val ledgerSource: String?
        get() = when (this) {
            Po -> "po"
            Card -> "card"
            Cash -> "cash"
            Pr -> "payroll"
            else -> null
        }

    val ledgerType: LedgerType?
        get() = when {
            isActuals -> LedgerType.Actuals
            isCommits -> LedgerType.Commits
            else -> null
        }
}

/**
 * The worksheet's number formatting (`wsFmt`), en-GB grouping:
 * null → `—`; zero → `—` except in Budget (`£0`); a negative in a variance
 * column → `(£1,234)` and elsewhere `−£1,234`.
 */
object CrFormat {
    const val DASH = "—"
    private const val MINUS = "−"
    private const val GRID_DP = 0
    private const val MONEY_DP = 2
    private const val TEN = 10.0

    fun grid(value: Double?, symbol: String, column: CrColumn, decimals: Int = GRID_DP): String {
        if (value == null || value.isNaN()) return DASH
        if (isZero(value, decimals)) return if (column == CrColumn.Bud) "${symbol}0" else DASH
        val body = symbol + Money.group(abs(value), decimals)
        return when {
            value >= 0 -> body
            column.isVariance -> "($body)"
            else -> MINUS + body
        }
    }

    /** `−£1,234.56` — the KPI, list and drawer precision. */
    fun money(value: Double?, symbol: String, decimals: Int = MONEY_DP): String {
        if (value == null || value.isNaN()) return DASH
        val body = symbol + Money.group(abs(value), decimals)
        return if (value < 0) MINUS + body else body
    }

    /** The posted list's variance figure: `£1,234.56`, negatives in parentheses. */
    fun variance(value: Double?, symbol: String): String {
        if (value == null || value.isNaN()) return DASH
        val body = symbol + Money.group(abs(value), MONEY_DP)
        return if (value < 0) "($body)" else body
    }

    /** `↑ £12.00 vs last` / `↓ £12.00 vs last`. */
    fun delta(value: Double, symbol: String): String {
        val arrow = if (value >= 0) "↑" else "↓"
        return "$arrow ${symbol}${Money.group(abs(value), MONEY_DP)} vs last"
    }

    private fun isZero(value: Double, decimals: Int): Boolean {
        var scale = 1.0
        repeat(decimals) { scale *= TEN }
        return (abs(value) * scale).roundToLong() == 0L
    }
}
