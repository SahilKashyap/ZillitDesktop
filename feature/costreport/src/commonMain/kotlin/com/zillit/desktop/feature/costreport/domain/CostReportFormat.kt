package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.common.Money
import kotlin.math.abs

/** The grouped header the eleven numeric columns sit under. */
enum class CrColumnGroup(val label: String) {
    Actuals("Actuals"),
    Commitments("Commitments"),
    Forecast("Forecast"),
    Budget(""),
    Analysis("Analysis"),
}

/** The eleven numeric columns, in worksheet order (spec §2.2). */
enum class CrColumn(
    val group: CrColumnGroup,
    val line1: String,
    val line2: String,
    val key: String,
    /** The one-line name — the sort chip and the mode strip (the web's `COL_LABELS`). */
    val label: String,
) {
    Atp(CrColumnGroup.Actuals, "Actuals", "This Period", "atp", "Actuals This Period"),
    Atd(CrColumnGroup.Actuals, "Actuals", "To Date", "atd", "Actuals to Date"),
    Po(CrColumnGroup.Commitments, "PO", "Commits", "po", "PO Commits"),
    Card(CrColumnGroup.Commitments, "Card", "Commits", "card", "Card Commits"),
    Cash(CrColumnGroup.Commitments, "Cash", "Commits", "cash", "Cash Commits"),
    Pr(CrColumnGroup.Commitments, "Payroll", "Commits", "pr", "Payroll Commits"),
    Etc(CrColumnGroup.Forecast, "Estimate", "To Complete", "etc", "ETC"),
    Efc(CrColumnGroup.Forecast, "Estimated", "Final Cost", "efc", "EFC"),
    Bud(CrColumnGroup.Budget, "Budget", "", "bud", "Budget"),
    Tv(CrColumnGroup.Analysis, "Total", "Variance", "tv", "Total Variance"),
    Vtp(CrColumnGroup.Analysis, "Variance", "This Period", "vtp", "Variance This Period"),
    ;

    val isVariance: Boolean get() = this == Tv || this == Vtp

    /** ETC, EFC and VTP: the three an accountant types into on the worksheet. */
    val isEditable: Boolean get() = this == Etc || this == Efc || this == Vtp

    /** What a drill into this cell's ledger is called — the cell's hover hint. */
    val drillHint: String?
        get() = when {
            isActuals -> "View actuals detail"
            isCommits -> "View ${ledgerSource} commitment detail"
            else -> null
        }
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
 * The worksheet's number formatting (`WsNum` over `wsFmt`), en-GB grouping:
 * null → `—`; an exact zero → `—` in every column but Budget, where a zero
 * budget is a measured value and reads `£0`; a negative in a variance column
 * → `(£1,234)` and elsewhere `−£1,234`.
 */
object CrFormat {
    const val DASH = "—"
    private const val MINUS = "−"
    private const val GRID_DP = 0
    private const val MONEY_DP = 2

    fun grid(value: Double?, symbol: String, column: CrColumn, decimals: Int = GRID_DP): String {
        if (value == null || value.isNaN()) return DASH
        if (value == 0.0 && column != CrColumn.Bud) return DASH
        val body = symbol + Money.group(abs(value), decimals)
        return when {
            value >= 0 -> body
            column.isVariance -> "($body)"
            else -> MINUS + body
        }
    }

    /**
     * ATD as a share of budget, sized to be informative at every stage of a
     * shoot: `0`, two decimals under 1 %, one under 10 %, whole above — a
     * multi-million budget against a few thousand of spend otherwise reads
     * "0%" for weeks.
     */
    fun percentOfBudget(actuals: Double, budget: Double): String {
        val raw = if (budget > 0) actuals / budget * PERCENT else 0.0
        return when {
            raw == 0.0 -> "0"
            raw < 1 -> Money.group(raw, 2)
            raw < PERCENT_ONE_DECIMAL_BELOW -> Money.group(raw, 1)
            else -> Money.group(raw, 0)
        }
    }

    private const val PERCENT = 100.0
    private const val PERCENT_ONE_DECIMAL_BELOW = 10.0

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
}
