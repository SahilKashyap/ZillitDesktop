package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Instant

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

/**
 * What a production owes one vendor, split by age — a row of the creditors
 * report (`CreditorsPage.jsx:131-164`).
 *
 * Each bucket keeps its amounts with their own currencies, so it can be
 * totalled honestly — one currency in its own, several converted to the
 * project's (`describeAmountTotal`). The plain sums beside them are the web's
 * `currentVal` / `d30Val` / `d60Val`: for the filters, the sort and the
 * percentages only, never shown.
 */
data class CreditorRow(
    val vendor: String,
    val terms: String,
    val currentItems: List<Pair<Double, String>> = emptyList(),
    val days30Items: List<Pair<Double, String>> = emptyList(),
    val days60Items: List<Pair<Double, String>> = emptyList(),
    /** A CIS supplier — the first invoice's `cisApplies || cis`, as the web reads it. */
    val cis: Boolean = false,
) {
    val current: Double get() = currentItems.sumOf { it.first }
    val days30: Double get() = days30Items.sumOf { it.first }
    val days60: Double get() = days60Items.sumOf { it.first }
    val total: Double get() = current + days30 + days60

    /** Every amount the vendor is owed, for the row's Total. */
    val allItems: List<Pair<Double, String>> get() = currentItems + days30Items + days60Items
}

/** Creditors' vendor select — the web's `supplierFilter`. */
enum class CreditorFilter {
    All,
    WithBalance,
    Overdue,
    Cis,
    ;

    /** "All Vendors ({n})" carries the count of every balance row. */
    fun label(vendorCount: Int): String = when (this) {
        All -> str(S.desktop_inv_all_vendors_n, vendorCount)
        WithBalance -> str(S.desktop_inv_with_balance_only)
        Overdue -> str(S.desktop_overdue)
        Cis -> str(S.desktop_inv_cis_suppliers)
    }
}

/** Creditors' sort — the web's `sortBy`. */
enum class CreditorSort(private val labelKey: String) {
    BalanceDesc(S.desktop_inv_sort_balance_desc),
    BalanceAsc(S.desktop_inv_sort_balance_asc),
    VendorAz(S.desktop_inv_sort_vendor_az),
    OldestDebt(S.desktop_inv_sort_oldest_debt),
    ;

    val label: String get() = str(labelKey)
}

/** Creditors' ageing select — the web's `ageingFilter`. */
enum class CreditorAgeing(private val labelKey: String) {
    All(S.desktop_inv_all_ageing),
    CurrentOnly(S.desktop_inv_current_only),
    Over30(S.desktop_inv_30_plus_days),
    Over60(S.desktop_ageing_60_plus_days),
    ;

    val label: String get() = str(labelKey)
}

/** The four tiles' figures — the web's `computeStats`, over every open invoice. */
data class CreditorStats(
    val total: List<Pair<Double, String>>,
    val current: List<Pair<Double, String>>,
    val days30: List<Pair<Double, String>>,
    val days60: List<Pair<Double, String>>,
    val vendorCount: Int,
    val currentPercent: Int,
    val days30Percent: Int,
    val days60Percent: Int,
)

object Creditors {
    /** The statuses that count as owed — the web's `CREDITOR_STATUSES`. */
    val OPEN_STATUSES: List<InvoiceStatus> = listOf(
        InvoiceStatus.Approved,
        InvoiceStatus.Override,
        InvoiceStatus.Entry,
        InvoiceStatus.ReadyToPay,
    )

    /** What the page reads: the web's `perPage=500`. */
    const val PAGE_SIZE = 500

    /**
     * The creditor set, re-filtered defensively — a server that loosened the
     * `status` filter must not inflate the balances (`CreditorsPage.jsx:304`).
     */
    fun owed(invoices: List<Invoice>): List<Invoice> = invoices.filter { it.status in OPEN_STATUSES }

    /** The date an invoice ages from: its invoice date, else when it was created (`:103`). */
    fun agedFrom(invoice: Invoice): Long? = invoice.invoiceDateMs ?: invoice.createdAtMs

    /**
     * Groups what is owed by vendor and by age, biggest balance first.
     *
     * A vendor's terms and CIS flag are its first invoice's, as the web keeps
     * whatever the row was created with; no terms reads "30 days".
     */
    fun rows(
        invoices: List<Invoice>,
        nowMs: Long,
        nameOf: (Invoice) -> String,
        /** The terms on the invoice (`paymentTerms || terms`). */
        termsOf: (Invoice) -> String? = { it.paymentTerms },
    ): List<CreditorRow> =
        invoices.groupBy(nameOf).map { (vendor, rows) ->
            val byBucket = rows.groupBy { AgeingBucket.of(agedFrom(it), nowMs) }
            fun items(bucket: AgeingBucket) = byBucket[bucket].orEmpty().map { it.grossAmount to it.currency }
            val first = rows.first()
            CreditorRow(
                vendor = vendor,
                terms = termsOf(first)?.takeIf(String::isNotBlank) ?: str(S.drive_expiry_30d),
                currentItems = items(AgeingBucket.Current),
                days30Items = items(AgeingBucket.Days30),
                days60Items = items(AgeingBucket.Days60),
                cis = first.cis,
            )
        }.sortedByDescending { it.total }

    /**
     * The tiles: every bucket over the whole open set, and each bucket's share
     * of the raw sum, rounded (`Math.round`) — "0%" when nothing is owed.
     */
    fun stats(invoices: List<Invoice>, vendorCount: Int, nowMs: Long): CreditorStats {
        val byBucket = invoices.groupBy { AgeingBucket.of(agedFrom(it), nowMs) }
        fun items(bucket: AgeingBucket) = byBucket[bucket].orEmpty().map { it.grossAmount to it.currency }
        val current = items(AgeingBucket.Current)
        val days30 = items(AgeingBucket.Days30)
        val days60 = items(AgeingBucket.Days60)
        val grand = (current + days30 + days60).sumOf { it.first }
        fun share(part: List<Pair<Double, String>>): Int =
            if (grand == 0.0) 0 else (part.sumOf { it.first } / grand * PERCENT).roundToInt()
        return CreditorStats(
            total = invoices.map { it.grossAmount to it.currency },
            current = current,
            days30 = days30,
            days60 = days60,
            vendorCount = vendorCount,
            currentPercent = share(current),
            days30Percent = share(days30),
            days60Percent = share(days60),
        )
    }

    /**
     * The balance rows as the table shows them — the web's `filtered`: the
     * search on the vendor's name only, the vendor and ageing selects on the
     * raw sums, then the sort (`CreditorsPage.jsx:328-344`).
     */
    fun shown(
        rows: List<CreditorRow>,
        search: String,
        filter: CreditorFilter,
        ageing: CreditorAgeing,
        sort: CreditorSort,
    ): List<CreditorRow> {
        val needle = search.trim().lowercase()
        val kept = rows.filter { row ->
            val byName = needle.isEmpty() || row.vendor.lowercase().contains(needle)
            val byFilter = when (filter) {
                CreditorFilter.All -> true
                CreditorFilter.WithBalance -> row.total > 0
                CreditorFilter.Overdue -> row.days60 > 0
                CreditorFilter.Cis -> row.cis
            }
            val byAgeing = when (ageing) {
                CreditorAgeing.All -> true
                CreditorAgeing.CurrentOnly -> row.days30 <= 0 && row.days60 <= 0
                CreditorAgeing.Over30 -> row.days30 > 0 || row.days60 > 0
                CreditorAgeing.Over60 -> row.days60 > 0
            }
            byName && byFilter && byAgeing
        }
        return when (sort) {
            CreditorSort.BalanceDesc -> kept.sortedByDescending { it.total }
            CreditorSort.BalanceAsc -> kept.sortedBy { it.total }
            CreditorSort.VendorAz -> kept.sortedBy { it.vendor.lowercase() }
            CreditorSort.OldestDebt -> kept.sortedByDescending { it.days60 }
        }
    }

    /**
     * Six weekly snapshots of what was owed, and how old it was at the time.
     *
     * Each bar is the ledger as it stood that week — an invoice raised after
     * a snapshot is not in it, and one raised long before has aged into a
     * later bucket. The label is the web's week number:
     * `ceil((weekEnd − 1 Jan this year) / week)`, 1 January taken in the
     * reader's own zone (`CreditorsPage.jsx:205-238`).
     */
    fun trend(
        invoices: List<Invoice>,
        nowMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<AgeingWeek> {
        val year = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).year
        val newYear = LocalDate(year, 1, 1).atStartOfDayIn(zone).toEpochMilliseconds()
        return (WEEKS_SHOWN - 1 downTo 0).map { back ->
            val weekEnd = nowMs - back * WEEK_MS
            var current = 0.0
            var days30 = 0.0
            var days60 = 0.0
            invoices.forEach { invoice ->
                val raised = agedFrom(invoice) ?: return@forEach
                if (raised > weekEnd) return@forEach
                when (AgeingBucket.of(raised, weekEnd)) {
                    AgeingBucket.Current -> current += invoice.grossAmount
                    AgeingBucket.Days30 -> days30 += invoice.grossAmount
                    AgeingBucket.Days60 -> days60 += invoice.grossAmount
                }
            }
            AgeingWeek(
                label = "W" + ceil((weekEnd - newYear).toDouble() / WEEK_MS).toLong(),
                current = current,
                days30 = days30,
                days60 = days60,
                isNow = back == 0,
            )
        }
    }

    private const val WEEKS_SHOWN = 6
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val PERCENT = 100.0
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
