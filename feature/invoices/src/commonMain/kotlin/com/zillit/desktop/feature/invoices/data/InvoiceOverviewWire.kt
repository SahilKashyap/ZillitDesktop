package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.ActivityRow
import com.zillit.desktop.feature.invoices.domain.CostReportImpact
import com.zillit.desktop.feature.invoices.domain.CostReportRow
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.InvoiceOverview
import com.zillit.desktop.feature.invoices.domain.OverviewStats
import com.zillit.desktop.feature.invoices.domain.PendingAction
import com.zillit.desktop.feature.invoices.domain.PipelineStage
import com.zillit.desktop.feature.invoices.domain.VendorAlert
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET /invoices/analytics/overview` and `GET /invoices/duplicates`.
 *
 * The analytics route answers **camelCase** — alone among this service's
 * routes, and the web reads it the same way (`data.statCards.awaitingMatch`).
 * Both spellings are accepted here anyway: a server that switches to the
 * house style should not blank the dashboard.
 */
internal fun parseOverview(data: JsonElement?): InvoiceOverview {
    val root = data as? JsonObject ?: return InvoiceOverview()
    val stats = root.objectField("statCards", "stat_cards")
    return InvoiceOverview(
        stats = OverviewStats(
            totalInvoices = stats.count("totalInvoices", "total_invoices"),
            awaitingMatch = stats.count("awaitingMatch", "awaiting_match"),
            awaitingMatchAmount = stats.text("awaitingMatchAmount", "awaiting_match_amount"),
            inApproval = stats.count("inApproval", "in_approval"),
            overdueCount = stats.count("overdueCount", "overdue_count"),
            overdueAmount = stats.text("overdueAmount", "overdue_amount"),
            readyToPay = stats.count("readyToPay", "ready_to_pay"),
            readyToPayAmount = stats.text("readyToPayAmount", "ready_to_pay_amount"),
            dueThisWeek = stats.text("dueThisWeek", "due_this_week"),
            dueThisWeekCount = stats.count("dueThisWeekCount", "due_this_week_count"),
            totalAP = stats.text("totalAP", "total_ap"),
            vendorCount = stats.count("supplierCount", "supplier_count", "vendorCount", "vendor_count"),
        ),
        pipeline = rowsOf(root["pipeline"]).map { row ->
            PipelineStage(
                id = row.text("id"),
                label = row.text("label"),
                count = row.count("count"),
                colour = row.text("color", "colour"),
            )
        },
        costReport = root.objectField("costReportSummary", "cost_report_summary").let { summary ->
            CostReportImpact(
                pendingCount = summary.count("pendingCount", "pending_count"),
                pendingNet = summary.text("pendingNet", "pending_net"),
                overBudgetDepts = summary.count("overBudgetDepts", "over_budget_depts"),
                underBudgetDepts = summary.count("underBudgetDepts", "under_budget_depts"),
                rows = rowsOf(summary["rows"]).map { row ->
                    CostReportRow(
                        departmentId = row.text("dept", "department", "department_id"),
                        budget = row.text("budgetFmt", "budget_fmt", "budget"),
                        pending = row.text("pendingFmt", "pending_fmt", "pending"),
                        projected = row.text("projectedFmt", "projected_fmt", "projected"),
                        variance = row.number("variance") ?: 0.0,
                    )
                },
            )
        },
        vendorAlerts = parseVendorAlerts(root),
        pendingActions = parsePendingActions(root),
        totalActions = root.count("totalActions", "total_actions"),
        recentActivity = parseActivity(root),
    )
}

private fun parseVendorAlerts(root: JsonObject): List<VendorAlert> =
    rowsOf(root.firstOf("supplierAlerts", "supplier_alerts", "vendorAlerts")).map { row ->
        VendorAlert(
            severity = row.text("severity"),
            title = row.text("title"),
            detail = row.text("sub", "detail"),
            urgency = row.text("urgency"),
            href = row.text("href"),
            buttonLabel = row.text("btnLabel", "btn_label"),
        )
    }

private fun parsePendingActions(root: JsonObject): List<PendingAction> =
    rowsOf(root.firstOf("pendingActions", "pending_actions")).map { row ->
        PendingAction(
            count = row.count("count"),
            label = row.text("label"),
            variant = row.text("variant"),
            href = row.text("href"),
        )
    }

private fun parseActivity(root: JsonObject): List<ActivityRow> =
    rowsOf(root.firstOf("recentActivity", "recent_activity")).map { row ->
        ActivityRow(
            status = row.text("status"),
            variant = row.text("variant"),
            vendor = row.text("supplier", "vendor"),
            reference = row.text("ref", "reference"),
            description = row.text("desc", "description"),
            amount = row.text("amount"),
        )
    }

/** The duplicate flags, which speak this service's usual snake_case. */
internal fun parseDuplicates(data: JsonElement?): List<DuplicateFlag> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    DuplicateFlag(
        id = id,
        status = row.text("status").ifBlank { DuplicateFlag.PENDING },
        similarityScore = row.count("similarity_score", "similarityScore"),
        invoiceRef = row.text("invoice_ref", "invoiceRef"),
        vendorName = row.text("supplier_name", "vendor_name", "supplierName"),
        invoiceAmount = row.number("invoice_amount", "invoiceAmount"),
        duplicateRef = row.text("duplicate_ref", "duplicateRef"),
        duplicateStatus = row.text("duplicate_status", "duplicateStatus"),
        duplicateDateMs = row.dateMs("duplicate_date", "duplicateDate"),
        matchReasons = row.arrayField("match_reasons", "matchReasons").mapNotNull { reason ->
            (reason as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        },
    )
}

/** A nested object under any of [names], or an empty one — never null, so the reads below stay flat. */
private fun JsonObject.objectField(vararg names: String): JsonObject = firstOf(*names) as? JsonObject ?: JsonObject(
    emptyMap(),
)

/** A whole number, however the server spelled it; absent is zero. */
private fun JsonObject.count(vararg names: String): Int = number(*names)?.toInt() ?: 0
