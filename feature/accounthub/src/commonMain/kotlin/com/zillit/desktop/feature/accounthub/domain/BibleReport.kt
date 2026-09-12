package com.zillit.desktop.feature.accounthub.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Where a posted transaction came from — the Source filter's vocabulary.
 *
 * `po` is on the wire but returns nothing: purchase orders are commitments and
 * are not posted to the general ledger. It is offered anyway, because the
 * filter is the ledger's own vocabulary and hiding a value the server accepts
 * would make this list disagree with the export's.
 */
enum class LedgerSource(val wire: String, val label: String) {
    PurchaseOrder("po", "Purchase Order"),
    Invoice("invoice", "Invoice"),
    Card("card", "Production Expense Cards"),
    Cash("cash", "Petty Cash Expenses"),
    Payroll("payroll", "Payroll"),

    /** An accountant's own entry — an accrual, reclass or correction. */
    ManualJournal("manual_je", "Manual Journal"),
    ;

    companion object {
        fun from(wire: String?): LedgerSource? = entries.firstOrNull { it.wire == wire }

        /** What to show for a source the server sent and this client does not model. */
        fun labelFor(wire: String): String =
            from(wire)?.label ?: wire.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/**
 * The short tag in a transaction's Src column — the web's `SRC_META`.
 *
 * The cost-report service writes these codes itself (`INV`, `CRED`, `PO`,
 * `CARD`, `CASH`, `PR`); the worksheet reads the same six. A filter wire name
 * is accepted too, so a server that answers in that vocabulary still gets its
 * colours rather than six grey tags.
 */
enum class SourceBadge(val code: String, val label: String) {
    Invoice("INV", "Invoice"),
    Credit("CRED", "Credit note"),
    PurchaseOrder("PO", "Purchase order"),
    Card("CARD", "Production expense card"),
    Cash("CASH", "Petty cash"),
    Payroll("PR", "Payroll"),
    ;

    companion object {
        fun from(src: String): SourceBadge? {
            val key = src.trim()
            return entries.firstOrNull { it.code.equals(key, ignoreCase = true) }
                ?: when (LedgerSource.from(key.lowercase())) {
                    LedgerSource.Invoice -> Invoice
                    LedgerSource.PurchaseOrder -> PurchaseOrder
                    LedgerSource.Card -> Card
                    LedgerSource.Cash -> Cash
                    LedgerSource.Payroll -> Payroll
                    LedgerSource.ManualJournal, null -> null
                }
        }

        /** The text on the tag: the known code, or what the server sent, as the web prints an unknown one. */
        fun textFor(src: String): String {
            val key = src.trim()
            return from(key)?.code
                ?: if (LedgerSource.from(key.lowercase()) == LedgerSource.ManualJournal) "JE" else key.ifBlank { "—" }
        }
    }
}

/** One choice in a filter whose options are fixed rather than read from the production. */
data class FilterOption(val value: String, val label: String)

/**
 * The Account Type filter's choices — the web's `TYPE_OPTS`.
 *
 * The chart's five classes plus `unclassified`, which the server files an
 * account with no class under. Sorted by label as the web sorts them, so the
 * list reads alphabetically rather than in the chart's balance-sheet order.
 */
val BibleAccountTypes: List<FilterOption> =
    (CoaCostType.entries.map { FilterOption(it.wire, it.label) } + FilterOption("unclassified", "Unclassified"))
        .sortedBy { it.label }

/**
 * The Tax filter's choices — the web's `mapTaxTypesToOptions`.
 *
 * The value is the identifier, the posting key a line item stores; the label
 * carries the rate. `other` is appended because a line taxed at a custom rate
 * is stored under it, and a filter that could not select those lines would
 * leave them unreachable from this report.
 */
fun bibleTaxOptions(taxTypes: List<TaxType>): List<FilterOption> =
    taxTypes.filter { it.identifier.isNotBlank() }.map { tax ->
        val rate = tax.rate
        FilterOption(tax.identifier, if (rate == null) tax.label else "${tax.label} · ${rate.asRateText()}%")
    } + FilterOption(OTHER_TAX, "Other")

private const val OTHER_TAX = "other"

/** `20.0` → `20`, `12.5` → `12.5`: the rate as the web's `parseFloat` prints it. */
private fun Double.asRateText(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

/** One posted transaction, as the closeout bible lists it. */
data class LedgerTransaction(
    /** The server's short source code — see [SourceBadge]. */
    val source: String = "",
    val effectiveDateMillis: Long? = null,
    val invoiceNumber: String = "",
    val purchaseOrderNumber: String = "",
    /** A vendor, or a person where the source is cash or payroll. */
    val party: String = "",
    val description: String = "",
    /** What the transaction was raised in, before conversion. */
    val originalCurrency: String = "",
    /** In the report's display currency, converted server-side. */
    val amount: Double = 0.0,
)

/**
 * One account's transactions, and what they come to.
 *
 * ## Buckets that are not chart codes
 *
 * The service files money it cannot put on a nominal under internal keys —
 * `__uncoded__`, `__payroll_unallocated__`, `__fringes_unallocated__`, and the
 * adapter's `__unallocated__` / `__unallocated__:<name>` — sent as both the code
 * and the name. Each is a real bucket carrying real money, so it is kept, but
 * never printed: the web's cost-report adapter calls rendering one "always a
 * bug" (`isInternalAccountKey`), and its Bible page still leaked
 * `__fringes_unallocated__` verbatim, seen live on 2026-09-13. `__uncoded__`
 * keeps the web Bible's own wording, "Uncoded"; the rest take the adapter's
 * `SYNTHETIC_NAMES`, and a key neither knows is spelled out in words.
 *
 * Only the double-underscore convention is masked. A mis-coded account such as
 * `art_4110` stays as it is, as the web insists: it is the thing an accountant
 * has to see in order to re-code it.
 */
data class BibleAccount(
    val code: String = "",
    val name: String = "",
    val total: Double = 0.0,
    val transactions: List<LedgerTransaction> = emptyList(),
) {
    val isUncoded: Boolean get() = code == UNCODED

    /** A service bucket rather than a code from the chart. */
    val isInternalKey: Boolean get() = isInternal(code)

    /** The code as printed: "Uncoded", nothing for another bucket, a dash for a missing code. */
    val displayCode: String
        get() = when {
            isUncoded -> "Uncoded"
            isInternalKey -> ""
            else -> code.ifBlank { "—" }
        }

    val displayName: String
        get() = when {
            name == UNCODED -> ""
            isInternal(name) -> nameFor(name)
            name.isBlank() && isInternalKey && !isUncoded -> nameFor(code)
            else -> name
        }

    /** "7100 · Camera hire", "Uncoded", "Fringes — Unallocated" — one line naming the account. */
    val title: String get() = listOf(displayCode, displayName).filter { it.isNotBlank() }.joinToString(" · ")

    /** "1 entry", "12 entries" — the web's count beside the account. */
    val entriesLabel: String
        get() = "${transactions.size} ${if (transactions.size == 1) "entry" else "entries"}"

    companion object {
        const val UNCODED = "__uncoded__"

        private const val UNALLOCATED = "__unallocated__"

        /** The web adapter's `SYNTHETIC_NAMES`, plus its null-account bucket. */
        private val NAMES = mapOf(
            UNCODED to "Uncoded",
            "__uncoded_budget__" to "Budget — Unallocated",
            "__payroll_unallocated__" to "Payroll — Unallocated",
            "__fringes_unallocated__" to "Fringes — Unallocated",
            UNALLOCATED to "Non-Allocated Items",
        )

        private fun isInternal(key: String): Boolean = key.startsWith("__")

        private fun nameFor(key: String): String {
            NAMES[key]?.let { return it }
            // `__unallocated__:Production Insurance` — the name after the key is the row's own.
            if (key.startsWith("$UNALLOCATED:")) {
                return key.removePrefix("$UNALLOCATED:").trim().ifBlank { NAMES.getValue(UNALLOCATED) }
            }
            val words = key.trim('_').replace('_', ' ').trim()
            return words.replaceFirstChar { it.uppercase() }.ifBlank { "Unallocated" }
        }
    }
}

/**
 * The filters the server says it applied, echoed back with the report.
 *
 * The banner above the table reads these rather than what was asked for, as
 * the web's does: the server may clamp a window or ignore a range, and a
 * banner that restated the request would describe a report nobody ran.
 */
data class BibleEcho(
    val periodStartMillis: Long? = null,
    val periodEndMillis: Long? = null,
    val accountStart: String = "",
    val accountEnd: String = "",
    val includeOpenPurchaseOrders: Boolean? = null,
)

/**
 * Every transaction of the period, grouped by the account it posted to.
 *
 * [errors] is the server's per-bucket failures. A bucket that could not be
 * read is reported rather than silently missing: this report is what a
 * production closes its books against, and a total that quietly omits payroll
 * is worse than one that says payroll is missing.
 */
data class BibleReport(
    val accounts: List<BibleAccount> = emptyList(),
    val grandTotal: Double = 0.0,
    /** The currency the amounts were converted into. */
    val currencyCode: String = "",
    val generatedAtMillis: Long? = null,
    val errors: Map<String, String> = emptyMap(),
    val echo: BibleEcho? = null,
) {
    val transactionCount: Int get() = accounts.sumOf { it.transactions.size }
}

/**
 * The bible's filter bar, as typed — the web's `filters` state.
 *
 * Kept apart from [BibleQuery] because the period is not an instant until the
 * report runs: "Current Period" means the close boundary through *today*, and
 * today is whenever Run Report is pressed, not when the page was opened.
 */
data class BibleFilters(
    val periodMode: PeriodMode = PeriodMode.Current,
    /** The Date Range pickers, `YYYY-MM-DD` as typed. */
    val fromDate: String = "",
    val toDate: String = "",
    val accountStart: String = "",
    val accountEnd: String = "",
    /** Empty means every account type. */
    val accountTypes: List<String> = emptyList(),
    /** Empty means every source. */
    val sources: List<String> = emptyList(),
    val companyIds: List<String> = emptyList(),
    /**
     * One vendor, or blank for all.
     *
     * The one single choice in a bar of multi-selects: the wire value is a bare
     * id, and a comma-joined one would either be refused or match nothing.
     */
    val vendorId: String = "",
    val taxes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val currency: String = "",
    /** Commitments as well as postings. On by default, as the web opens. */
    val includeOpenPurchaseOrders: Boolean = true,
) {
    fun toQuery(period: ReportPeriod): BibleQuery = BibleQuery(
        periodStartMillis = period.startMillis,
        periodEndMillis = period.endMillis,
        accountStart = accountStart.trim(),
        accountEnd = accountEnd.trim(),
        accountTypes = accountTypes,
        sources = sources,
        companyIds = companyIds,
        vendorId = vendorId,
        taxes = taxes,
        tags = tags,
        currency = currency,
        includeOpenPurchaseOrders = includeOpenPurchaseOrders,
    )
}

/** What the report is asked for, with its period resolved to instants. */
data class BibleQuery(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val accountStart: String = "",
    val accountEnd: String = "",
    val accountTypes: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val companyIds: List<String> = emptyList(),
    val vendorId: String = "",
    val taxes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val currency: String = "",
    val includeOpenPurchaseOrders: Boolean = true,
)

/**
 * The web's two period modes, resolved.
 *
 * - **Current Period** runs from the close boundary — the locked date itself,
 *   as the web sends it — to the end of today. With nothing closed it runs
 *   from a floor of 1 January 2000, which is "everything so far".
 * - **Date Range** runs from the start of the first day to the end of the
 *   last, in the reader's own zone, as the web's date inputs do.
 *
 * Local days, not UTC ones: an accountant in Mumbai asking for 12 September
 * means their 12 September, and a UTC window would cut five and a half hours
 * off one end of it and add them to the other.
 */
object BiblePeriod {

    /** The Current Period's floor while nothing has been closed. */
    const val FLOOR = "2000-01-01"

    fun current(lockedThrough: String, today: LocalDate, zone: TimeZone): ReportPeriod {
        val from = parse(lockedThrough) ?: LocalDate(FLOOR_YEAR, 1, 1)
        return ReportPeriod(startMillis = from.startMillis(zone), endMillis = today.endMillis(zone))
    }

    /** Null while either end is missing or half-typed, or the range runs backwards. */
    fun custom(fromDate: String, toDate: String, zone: TimeZone): ReportPeriod? {
        val from = parse(fromDate) ?: return null
        val to = parse(toDate) ?: return null
        if (to < from) return null
        return ReportPeriod(startMillis = from.startMillis(zone), endMillis = to.endMillis(zone))
    }

    fun resolve(filters: BibleFilters, lockedThrough: String, today: LocalDate, zone: TimeZone): ReportPeriod? =
        when (filters.periodMode) {
            PeriodMode.Current -> current(lockedThrough, today, zone)
            PeriodMode.Custom -> custom(filters.fromDate, filters.toDate, zone)
        }

    /**
     * "6 Sep 2026 – 12 Sep 2026", or "Till 12 Sep 2026" while nothing is
     * closed — the filter bar's wording, and the export's `period_label`.
     */
    fun label(filters: BibleFilters, lockedThrough: String, today: LocalDate): String = when (filters.periodMode) {
        PeriodMode.Current -> parse(lockedThrough)
            ?.let { "${it.longText()} – ${today.longText()}" }
            ?: "Till ${today.longText()}"
        PeriodMode.Custom -> "${longText(filters.fromDate)} – ${longText(filters.toDate)}"
    }

    /** The Date Range the web opens on: the first of January to today. */
    fun defaultRange(today: LocalDate): Pair<String, String> =
        LocalDate(today.year, 1, 1).toString() to today.toString()

    /** Today in [zone], for a host clock reading. */
    fun today(nowMillis: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date

    /** `YYYY-MM-DD` strictly; anything else reads as not a date yet. */
    fun parse(text: String?): LocalDate? {
        val trimmed = text?.trim().orEmpty()
        if (!ISO_DAY.matches(trimmed)) return null
        return runCatching { LocalDate.parse(trimmed) }.getOrNull()
    }

    /** "12 Sep 2026" — the web's `fmtYmdLong`; a dash for a date that does not read. */
    fun longText(ymd: String): String = parse(ymd)?.longText() ?: "—"

    private fun LocalDate.longText(): String = "$day ${MONTHS[month.ordinal]} $year"

    private fun LocalDate.startMillis(zone: TimeZone): Long = atStartOfDayIn(zone).toEpochMilliseconds()

    private fun LocalDate.endMillis(zone: TimeZone): Long =
        plus(1, kotlinx.datetime.DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1

    private const val FLOOR_YEAR = 2000
    private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}

/** The bible's number and date formats — the web's `fmtAmount` and `epochToDisplay`. */
object BibleFormat {

    /**
     * `£1,234.50`, or `(£1,234.50)` for a negative — the symbol inside the
     * brackets, as an accountant writes a credit and as the web prints it.
     */
    fun money(amount: Double, symbol: String): String {
        val cents = kotlin.math.floor(kotlin.math.abs(amount) * CENTS + HALF).toLong()
        val whole = (cents / CENTS.toLong()).toString().reversed().chunked(THOUSANDS).joinToString(",").reversed()
        val figure = "$symbol$whole.${(cents % CENTS.toLong()).toString().padStart(2, '0')}"
        return if (amount < 0) "($figure)" else figure
    }

    /** `12/09/26`, in the reader's zone; a dash for a missing stamp. */
    fun shortDate(millis: Long?, zone: TimeZone): String {
        if (millis == null || millis <= 0) return "—"
        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
        val yy = (date.year % CENTURY).toString().padStart(2, '0')
        return "${date.day.toString().padStart(2, '0')}/${(date.month.ordinal + 1).toString().padStart(2, '0')}/$yy"
    }

    /** `2026-09-12_2317` — the web's `exportTs`, in local time, for export file names. */
    fun exportStamp(nowMillis: Long, zone: TimeZone): String {
        val moment = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
        return "${moment.date}_${moment.hour.toString().padStart(2, '0')}${moment.minute.toString().padStart(2, '0')}"
    }

    private const val CENTS = 100.0
    private const val HALF = 0.5
    private const val THOUSANDS = 3
    private const val CENTURY = 100
}
