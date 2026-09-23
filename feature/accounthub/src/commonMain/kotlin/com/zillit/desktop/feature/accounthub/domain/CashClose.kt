package com.zillit.desktop.feature.accounthub.domain

/**
 * The Weekly Close Command Centre — `GET /api/v2/invoices/analytics/cash-close`.
 *
 * Read as the web renders it: the server pre-shapes every panel, down to a
 * bar's height and a cell's tint, so this is a faithful holder rather than a
 * model that recomputes anything. The checklist is ticked locally only.
 */
data class CashCloseDashboard(
    val progressPercent: Int = 0,
    val progressTotal: Int = 0,
    val waterfall: List<WaterfallBar> = emptyList(),
    val heatRows: List<HeatRow> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val recon: List<ReconRow> = emptyList(),
    val weeks: List<CommitmentWeek> = emptyList(),
) {
    val isEmpty: Boolean
        get() = waterfall.isEmpty() && heatRows.isEmpty() && checklist.isEmpty() && recon.isEmpty() && weeks.isEmpty()
}

/** One bar of the cash-flow forecast; [type] is `pos`, `neg` or `net`, [amount] the figure printed above it. */
data class WaterfallBar(
    val label: String = "",
    val type: String = "",
    val height: Int = 0,
    val marginBottom: Int = 0,
    val amount: String = "",
)

/** One department's row of the commitment heatmap. */
data class HeatRow(val label: String = "", val cells: List<HeatCell> = emptyList())

data class HeatCell(val value: String = "", val background: String = "", val color: String = "")

/** One close task; [meta] is its second line, [badge] and [badgeTone] what the pill prints until it is done. */
data class ChecklistItem(
    val label: String = "",
    val done: Boolean = false,
    val badge: String = "",
    val badgeTone: String = "",
    val meta: String = "",
)

/** Supplier Reconciliation Status — a supplier, a tone and a detail line. */
data class ReconRow(val supplier: String = "", val status: String = "", val detail: String = "")

/** Upcoming Commitments (Next 4 Weeks) — a week, its amount, a bar and a note. */
data class CommitmentWeek(
    val label: String = "",
    val amount: String = "",
    val percent: Int = 0,
    val color: String = "",
    val detail: String = "",
    /** The week spend peaks — the web prints "PEAK WEEK" after the note. */
    val peak: Boolean = false,
    /** A studio advance falls due this week — "Netflix advance due". */
    val netflix: Boolean = false,
)

/** The heatmap's week headers — fixed on the web too. */
val HEAT_WEEKS: List<String> = listOf("W7", "W8", "W9", "W10", "W11", "W12", "W13", "W14")
