package com.zillit.desktop.feature.invoices.domain

/**
 * The accountant's Overview — the web's `OverviewPage`, fed by
 * `GET /invoices/analytics/overview`.
 *
 * The server does the arithmetic and the formatting: money arrives as the
 * string it should be printed as, counts as numbers. Nothing here is
 * recomputed, so the desktop and the web cannot disagree about a total.
 */
data class InvoiceOverview(
    val stats: OverviewStats = OverviewStats(),
    val pipeline: List<PipelineStage> = emptyList(),
    val costReport: CostReportImpact = CostReportImpact(),
    val vendorAlerts: List<VendorAlert> = emptyList(),
    val pendingActions: List<PendingAction> = emptyList(),
    val totalActions: Int = 0,
    val recentActivity: List<ActivityRow> = emptyList(),
)

/** The six tiles across the top. Amounts are the server's strings. */
data class OverviewStats(
    val totalInvoices: Int = 0,
    val awaitingMatch: Int = 0,
    val awaitingMatchAmount: String = "",
    val inApproval: Int = 0,
    val overdueCount: Int = 0,
    val overdueAmount: String = "",
    val readyToPay: Int = 0,
    val readyToPayAmount: String = "",
    val dueThisWeek: String = "",
    val dueThisWeekCount: Int = 0,
    val totalAP: String = "",
    val vendorCount: Int = 0,
)

/**
 * One stage of the pipeline bar.
 *
 * [id] is the server's, and it names where clicking the stage goes — see
 * [AccountantPage.forPipelineStage]; [colour] is its palette key.
 */
data class PipelineStage(val id: String, val label: String, val count: Int, val colour: String)

/** What the unposted invoices will do to the cost report — the web's `costReportSummary`. */
data class CostReportImpact(
    val pendingCount: Int = 0,
    val pendingNet: String = "",
    val overBudgetDepts: Int = 0,
    val underBudgetDepts: Int = 0,
    val rows: List<CostReportRow> = emptyList(),
)

/** A department's week: its budget, what is pending, and where that lands. */
data class CostReportRow(
    val departmentId: String,
    val budget: String,
    val pending: String,
    val projected: String,
    /** Per cent over (positive) or under (negative) budget. */
    val variance: Double,
) {
    val isOver: Boolean get() = variance > 0
}

/** A vendor needing attention, with the button the server says to offer. */
data class VendorAlert(
    val severity: String,
    val title: String,
    val detail: String,
    val urgency: String,
    val href: String,
    val buttonLabel: String,
)

data class PendingAction(val count: Int, val label: String, val variant: String, val href: String)

data class ActivityRow(
    val status: String,
    val variant: String,
    val vendor: String,
    val reference: String,
    val description: String,
    val amount: String,
)

/**
 * An invoice the server thinks has been seen before.
 *
 * Confirming keeps the flag and marks it a duplicate; dismissing clears it.
 * Both are the accountant's call — the server only raises the question.
 */
data class DuplicateFlag(
    val id: String,
    val status: String,
    val similarityScore: Int,
    val invoiceRef: String,
    val vendorName: String,
    val invoiceAmount: Double?,
    val duplicateRef: String,
    val duplicateStatus: String,
    val duplicateDateMs: Long?,
    val matchReasons: List<String>,
) {
    val isConfirmed: Boolean get() = status.equals(CONFIRMED, ignoreCase = true)
    val isPending: Boolean get() = status.equals(PENDING, ignoreCase = true)

    companion object {
        const val CONFIRMED = "confirmed"
        const val PENDING = "pending"
    }
}
