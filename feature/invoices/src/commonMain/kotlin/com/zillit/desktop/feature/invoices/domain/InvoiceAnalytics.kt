package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The analytics page — the web's `AnalyticsPage`, fed by
 * `GET /invoices/analytics`.
 *
 * Like the dashboard, the server has already done the arithmetic and the
 * formatting; every amount here is the string to print.
 */
data class InvoiceAnalytics(
    val stats: AnalyticsStats = AnalyticsStats(),
    val summary: AnalyticsSummary = AnalyticsSummary(),
    /** `depts` — the Cost Report Impact of AP table. */
    val departments: List<DepartmentSpend> = emptyList(),
    val vendors: List<VendorSpend> = emptyList(),
    /** `departments` — Spend by Department, a share bar per department, as the vendors have. */
    val departmentSpend: List<VendorSpend> = emptyList(),
    val totals: SpendTotals = SpendTotals(),
)

/** The four tiles across the top, each with the server's own subtitle. */
data class AnalyticsStats(
    val totalApSpend: String = "",
    val totalApSubtitle: String = "",
    val averageInvoice: String = "",
    val averageInvoiceSubtitle: String = "",
    val onTimePayment: String = "",
    val onTimeSubtitle: String = "",
    val apDays: String = "",
    val apDaysSubtitle: String = "",
)

/** What is posted, pending, unattributed and projected. */
data class AnalyticsSummary(
    val posted: String = "",
    val postedSubtitle: String = "",
    val pending: String = "",
    val pendingSubtitle: String = "",
    val unknown: String = "",
    val unknownSubtitle: String = "",
    val projected: String = "",
    val projectedSubtitle: String = "",
)

/**
 * One department's spend. [variance] is the server's own string — a per cent,
 * a money figure or a dash — so it is shown, never parsed.
 */
data class DepartmentSpend(
    val code: String,
    val name: String,
    val posted: String,
    val pending: String,
    val unknown: String,
    val projected: String,
    val variance: String,
    /** The server's flag that this row is over its budget. */
    val isOver: Boolean,
)

/** One vendor's share of the spend; [percent] is the bar's width. */
data class VendorSpend(val name: String, val amount: String, val percent: Double)

data class SpendTotals(
    val posted: String = "",
    val pending: String = "",
    val unknown: String = "",
    val projected: String = "",
)

/**
 * How old an unpaid invoice is — the web's three creditor buckets.
 *
 * Measured from the invoice date, not the due date: the creditors report asks
 * how long the production has owed the money, not how late it is.
 */
enum class AgeingBucket(private val labelKey: String) {
    Current(S.dv_current),
    Days30(S.desktop_ageing_30_days),
    Days60(S.desktop_ageing_60_plus_days),
    ;

    val label: String get() = str(labelKey)

    companion object {
        const val DAYS_30 = 30
        const val DAYS_60 = 60
        private const val DAY_MS = 86_400_000L

        /** The bucket for an invoice dated [invoiceDateMs]; an undated one is current. */
        fun of(invoiceDateMs: Long?, nowMs: Long): AgeingBucket {
            val age = invoiceDateMs?.let { ((nowMs - it) / DAY_MS).toInt() } ?: 0
            return when {
                age >= DAYS_60 -> Days60
                age >= DAYS_30 -> Days30
                else -> Current
            }
        }
    }
}

/** What a production owes one vendor, split by age — a row of the creditors report. */
data class CreditorRow(
    val vendor: String,
    val terms: String,
    val current: Double,
    val days30: Double,
    val days60: Double,
    val currency: String,
) {
    val total: Double get() = current + days30 + days60
}

object Creditors {
    /** The statuses that count as owed — the web's `CREDITOR_STATUSES`. */
    val OPEN_STATUSES: List<InvoiceStatus> = listOf(
        InvoiceStatus.Approved,
        InvoiceStatus.Override,
        InvoiceStatus.Entry,
        InvoiceStatus.ReadyToPay,
    )

    /**
     * Groups what is owed by vendor and by age.
     *
     * A vendor whose invoices are in several currencies keeps the first one
     * seen rather than adding pounds to rupees; the web converts, which this
     * client has no rate table to do.
     */
    fun rows(
        invoices: List<Invoice>,
        nowMs: Long,
        nameOf: (Invoice) -> String,
        /** The vendor's terms; the invoice does not carry them on this client. */
        termsOf: (Invoice) -> String?,
    ): List<CreditorRow> =
        invoices.groupBy(nameOf).map { (vendor, rows) ->
            var current = 0.0
            var days30 = 0.0
            var days60 = 0.0
            rows.forEach { invoice ->
                val amount = invoice.grossAmount
                when (AgeingBucket.of(invoice.invoiceDateMs, nowMs)) {
                    AgeingBucket.Current -> current += amount
                    AgeingBucket.Days30 -> days30 += amount
                    AgeingBucket.Days60 -> days60 += amount
                }
            }
            CreditorRow(
                vendor = vendor,
                terms = rows.firstNotNullOfOrNull { termsOf(it)?.takeIf(String::isNotBlank) } ?: DEFAULT_TERMS,
                current = current,
                days30 = days30,
                days60 = days60,
                currency = rows.firstNotNullOfOrNull { it.currency.takeIf(String::isNotBlank) }.orEmpty(),
            )
        }.sortedByDescending { it.total }

    /**
     * Six weekly snapshots of what was owed, and how old it was at the time.
     *
     * Each bar is the ledger as it stood that week — an invoice raised after
     * a snapshot is not in it, and one raised long before has aged into a
     * later bucket. That is what makes the trend readable: the same debt
     * moving right is a production falling behind.
     */
    fun trend(invoices: List<Invoice>, nowMs: Long): List<AgeingWeek> = (WEEKS_SHOWN - 1 downTo 0).map { back ->
        val weekEnd = nowMs - back * WEEK_MS
        var current = 0.0
        var days30 = 0.0
        var days60 = 0.0
        invoices.forEach { invoice ->
            val raised = invoice.invoiceDateMs ?: invoice.createdAtMs ?: return@forEach
            if (raised > weekEnd) return@forEach
            when (AgeingBucket.of(raised, weekEnd)) {
                AgeingBucket.Current -> current += invoice.grossAmount
                AgeingBucket.Days30 -> days30 += invoice.grossAmount
                AgeingBucket.Days60 -> days60 += invoice.grossAmount
            }
        }
        AgeingWeek(
            label = "W" + ((weekEnd / WEEK_MS) % WEEKS_IN_YEAR + 1),
            current = current,
            days30 = days30,
            days60 = days60,
            isNow = back == 0,
        )
    }

    private const val DEFAULT_TERMS = "30 days"
    private const val WEEKS_SHOWN = 6
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val WEEKS_IN_YEAR = 52
}

/** One bar of the aged-debt trend: what was owed that week, by age. */
data class AgeingWeek(
    val label: String,
    val current: Double,
    val days30: Double,
    val days60: Double,
    val isNow: Boolean = false,
) {
    val total: Double get() = current + days30 + days60

    /** The tallest single bucket in this week — what the bars are scaled against. */
    val tallest: Double get() = maxOf(current, days30, days60)
}
