package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.abs

/** The grouped header the eleven numeric columns sit under. */
enum class CrColumnGroup(private val labelKey: String?) {
    Actuals(S.desktop_actuals),
    Commitments(S.desktop_cr_commitments),
    Forecast(S.desktop_cr_forecast),
    Budget(null),
    Analysis(S.desktop_cr_analysis),
    ;

    val label: String get() = labelKey?.let { str(it) } ?: ""
}

/** The eleven numeric columns, in worksheet order (spec §2.2). */
enum class CrColumn(
    val group: CrColumnGroup,
    private val line1Key: String,
    private val line2Key: String?,
    val key: String,
    /** The one-line name — the sort chip and the mode strip (the web's `COL_LABELS`). */
    private val labelKey: String,
) {
    Atp(CrColumnGroup.Actuals, S.desktop_actuals, S.desktop_this_period, "atp", S.desktop_cr_actuals_this_period),
    Atd(CrColumnGroup.Actuals, S.desktop_actuals, S.desktop_cr_to_date, "atd", S.cr_kpi_actuals_to_date),
    Po(CrColumnGroup.Commitments, S.desktop_po, S.desktop_cr_commits, "po", S.desktop_cr_po_commits),
    Card(CrColumnGroup.Commitments, S.ah_my_cards, S.desktop_cr_commits, "card", S.desktop_cr_card_commits),
    Cash(CrColumnGroup.Commitments, S.desktop_cr_cash, S.desktop_cr_commits, "cash", S.desktop_cr_cash_commits),
    Pr(CrColumnGroup.Commitments, S.dm_section_payroll, S.desktop_cr_commits, "pr", S.desktop_cr_payroll_commits),
    Etc(CrColumnGroup.Forecast, S.desktop_cr_estimate, S.desktop_cr_to_complete, "etc", S.desktop_cr_etc),
    Efc(CrColumnGroup.Forecast, S.desktop_cr_estimated, S.desktop_cr_final_cost, "efc", S.desktop_cr_efc),
    Bud(CrColumnGroup.Budget, S.budget_text, null, "bud", S.budget_text),
    Tv(CrColumnGroup.Analysis, S.asset_total, S.desktop_variance, "tv", S.desktop_cr_total_variance),
    Vtp(CrColumnGroup.Analysis, S.desktop_variance, S.desktop_this_period, "vtp", S.desktop_cr_variance_this_period),
    ;

    val line1: String get() = str(line1Key)

    val line2: String get() = line2Key?.let { str(it) } ?: ""

    val label: String get() = str(labelKey)

    val isVariance: Boolean get() = this == Tv || this == Vtp

    /** ETC, EFC and VTP: the three an accountant types into on the worksheet. */
    val isEditable: Boolean get() = this == Etc || this == Efc || this == Vtp

    /** What a drill into this cell's ledger is called — the cell's hover hint. */
    val drillHint: String?
        get() = when {
            isActuals -> str(S.desktop_cr_view_actuals_detail)
            isCommits -> str(S.desktop_cr_view_commit_detail, ledgerSource.orEmpty())
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

    /** `↑ £12.00 vs last` / `↓ £12.00 vs last`; [caps] for the timeline's upper-case tail. */
    fun delta(value: Double, symbol: String, caps: Boolean = false): String {
        val arrow = if (value >= 0) "↑" else "↓"
        val money = symbol + Money.group(abs(value), MONEY_DP)
        return str(if (caps) S.desktop_cr_delta_vs_last_caps else S.desktop_cr_delta_vs_last, arrow, money)
    }
}
