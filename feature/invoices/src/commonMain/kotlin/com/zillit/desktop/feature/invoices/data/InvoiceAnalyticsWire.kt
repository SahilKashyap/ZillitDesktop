package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.AnalyticsStats
import com.zillit.desktop.feature.invoices.domain.AnalyticsSummary
import com.zillit.desktop.feature.invoices.domain.DepartmentSpend
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.domain.SpendTotals
import com.zillit.desktop.feature.invoices.domain.VendorSpend
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `GET /invoices/analytics`.
 *
 * CamelCase, like its sibling `analytics/overview`, and pre-formatted the same
 * way: every amount is the string to print. Snake_case is accepted too.
 */
internal fun parseAnalytics(data: JsonElement?): InvoiceAnalytics {
    val root = data as? JsonObject ?: return InvoiceAnalytics()
    val stats = root.child("stats")
    val summary = root.child("summary")
    val totals = root.child("totals")
    return InvoiceAnalytics(
        stats = AnalyticsStats(
            totalApSpend = stats.text("totalApSpend", "total_ap_spend"),
            totalApSubtitle = stats.text("totalApSubtitle", "total_ap_subtitle"),
            averageInvoice = stats.text("avgInvoice", "avg_invoice"),
            averageInvoiceSubtitle = stats.text("avgInvoiceSubtitle", "avg_invoice_subtitle"),
            onTimePayment = stats.text("onTimePayment", "on_time_payment"),
            onTimeSubtitle = stats.text("onTimeSubtitle", "on_time_subtitle"),
            apDays = stats.text("apDays", "ap_days"),
            apDaysSubtitle = stats.text("apDaysSubtitle", "ap_days_subtitle"),
        ),
        summary = AnalyticsSummary(
            posted = summary.text("postedAmount", "posted_amount"),
            postedSubtitle = summary.text("postedSubtitle", "posted_subtitle"),
            pending = summary.text("pendingAmount", "pending_amount"),
            pendingSubtitle = summary.text("pendingSubtitle", "pending_subtitle"),
            unknown = summary.text("unknownAmount", "unknown_amount"),
            unknownSubtitle = summary.text("unknownSubtitle", "unknown_subtitle"),
            projected = summary.text("projectedAmount", "projected_amount"),
            projectedSubtitle = summary.text("projectedSubtitle", "projected_subtitle"),
        ),
        departments = rowsOf(root["depts"]).map { row ->
            DepartmentSpend(
                code = row.text("code"),
                name = row.text("name"),
                posted = row.text("posted"),
                pending = row.text("pending"),
                unknown = row.text("unknown"),
                projected = row.text("projected"),
                variance = row.text("variance"),
                isOver = row.flag("over") == true || row.flag("highlight") == true,
            )
        },
        vendors = parseShares(root.firstOf("suppliers", "vendors")),
        departmentSpend = parseShares(root["departments"]),
        totals = SpendTotals(
            posted = totals.text("posted"),
            pending = totals.text("pending"),
            unknown = totals.text("unknown"),
            projected = totals.text("projected"),
        ),
    )
}

/** A nested object, or an empty one so the reads above stay flat. */
private fun JsonObject.child(vararg names: String): JsonObject =
    firstOf(*names) as? JsonObject ?: JsonObject(emptyMap())

/** `{name, amount, pct}` rows — a vendor's or a department's share of the spend. */
private fun parseShares(data: JsonElement?): List<VendorSpend> = rowsOf(data).map { row ->
    VendorSpend(
        name = row.text("name"),
        amount = row.text("amount"),
        percent = row.number("pct", "percent") ?: 0.0,
    )
}
