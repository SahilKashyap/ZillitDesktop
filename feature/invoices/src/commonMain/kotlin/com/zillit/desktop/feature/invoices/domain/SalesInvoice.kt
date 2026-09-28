package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A sales invoice — money owed *to* the production, on
 * `/invoices/sales-invoices` (the web's `SalesPage`).
 *
 * The only record in this module that faces outward: it is raised against a
 * client, marked sent, and later settled.
 */
data class SalesInvoice(
    val id: String,
    val reference: String = "",
    val clientName: String = "",
    val description: String = "",
    val grossAmount: Double = 0.0,
    val currency: String = "",
    val dueDateMs: Long? = null,
    val createdAtMs: Long? = null,
    val status: SalesInvoiceStatus = SalesInvoiceStatus.Draft,
    val invoiceDateMs: Long? = null,
    val lineItems: List<CodedLine> = emptyList(),
    /** `client_address` — an object, or the same object JSON-encoded (`SalesPage.jsx:1071-1085`). */
    val clientAddress: ClientAddress = ClientAddress(),
    /** A legacy plain-text address, shown when no structured one was stored. */
    val clientAddressText: String = "",
    /** `pay_terms`; the web never sends one, so the preview reads "30 days" when blank. */
    val payTerms: String = "",
    /** `user_id || created_by` — the preview's Created By. */
    val createdBy: String = "",
    val updatedBy: String = "",
    val updatedAtMs: Long? = null,
    /** Each line's stored `tax_amount`, by line id — the preview prints it, never recomputes it. */
    val lineTaxAmounts: Map<String, Double> = emptyMap(),
    /** `line_items` exactly as stored, so an edit keeps the layers and tags this client does not show. */
    val lineItemsJson: String = "",
) {
    /** `reference || id.slice(0, 6)` — the list's Invoice column (`SalesPage.jsx:969`). */
    val listRef: String get() = reference.ifBlank { id.take(LIST_ID_CHARS) }

    /** Sent and past its due date — the web's virtual Overdue (`SalesPage.jsx:472-476, 1009-1013`). */
    fun isOverdue(nowMs: Long): Boolean =
        status == SalesInvoiceStatus.Sent && dueDateMs != null && dueDateMs < nowMs

    /** The preview's pill: Overdue when [isOverdue], the stored status otherwise. */
    fun shownStatus(nowMs: Long): SalesInvoiceStatus = if (isOverdue(nowMs)) SalesInvoiceStatus.Overdue else status

    /** The billed-to line: the structured address joined, else a plain legacy string. */
    val addressLine: String
        get() = clientAddress.text.ifBlank { clientAddressText.takeUnless { it.trim().startsWith("{") }.orEmpty() }

    /** What the preview's Terms says — `pay_terms || "30 days"`. */
    val termsLabel: String get() = payTerms.ifBlank { SalesTerms.Days30.label }

    private companion object {
        const val LIST_ID_CHARS = 6
    }
}

/** The structured address the form writes as `client_address`. */
data class ClientAddress(
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val state: String = "",
    val country: String = "",
    val postalCode: String = "",
) {
    /** `line1, line2, city, state, postal_code, country`, the blanks dropped (`SalesPage.jsx:1081-1084`). */
    val text: String
        get() = listOf(line1, line2, city, state, postalCode, country).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * What raising or updating a sales invoice sends — `SalesPage`'s
 * `handleCreate` payload (`SalesPage.jsx:407-441`). [reference] is set on a
 * create only; an update leaves the stored one alone.
 */
data class SalesInvoiceWrite(
    val clientName: String,
    val clientAddress: ClientAddress,
    val currency: String,
    val invoiceDate: String,
    val dueDate: String,
    val lines: List<CodedLine>,
    val reference: String? = null,
    /** The saved `line_items`, so an edit keeps layers and tags. */
    val savedLinesJson: String = "",
) {
    val gross: Double get() = EntryCoding.totals(lines).gross
}

/** A sales invoice's life: drafted, sent to the client, paid. */
enum class SalesInvoiceStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    Sent("sent", S.cs_sent),
    Paid("paid", S.desktop_paid),
    Overdue("overdue", S.desktop_overdue),
    Cancelled("cancelled", S.cancelled),
    ;

    val label: String get() = str(labelKey)

    /** Mark Sent is for a draft — the web shows it, Edit and Delete for drafts only. */
    val canSend: Boolean get() = this == Draft

    /** Settling is for one already out; the web offers no button for it. */
    val canMarkPaid: Boolean get() = this == Sent || this == Overdue

    companion object {
        fun from(wire: String?): SalesInvoiceStatus = entries.firstOrNull { it.wire == wire } ?: Draft
    }
}

/** The form's Payment Terms select — the web's `TERM_DAYS` (`SalesPage.jsx:97-103`), in its order. */
enum class SalesTerms(val wire: String, private val labelKey: String, val days: Int) {
    Days30("30 days", S.drive_expiry_30d, 30),
    Days14("14 days", S.desktop_14_days, 14),
    Days7("7 days", S.drive_expiry_7d, 7),
    OnReceipt("On receipt", S.desktop_inv_terms_on_receipt, 0),
    Days60("60 days", S.desktop_60_days, 60),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** An unknown term counts as thirty days — `TERM_DAYS[payTerms] ?? 30`. */
        fun from(wire: String?): SalesTerms = entries.firstOrNull { it.wire == wire } ?: Days30

        /** `invoiceDate + TERM_DAYS[terms]` as `YYYY-MM-DD` — the web's due-date effect (`SalesPage.jsx:263-269`). */
        fun dueDate(invoiceDate: String, terms: SalesTerms): String = InvoiceFormat.plusDays(invoiceDate, terms.days)
    }
}

/** The list's status chips — All / Draft / Sent / Paid / Overdue (`SalesPage.jsx:115-121`). */
enum class SalesFilter(private val labelKey: String) {
    All(S.all),
    Draft(S.draft),
    Sent(S.cs_sent),
    Paid(S.desktop_paid),
    Overdue(S.desktop_overdue),
    ;

    val label: String get() = str(labelKey)

    /** Overdue is sent and past due; the rest match the stored status (`SalesPage.jsx:472-479`). */
    fun keeps(invoice: SalesInvoice, nowMs: Long): Boolean = when (this) {
        All -> true
        Draft -> invoice.status == SalesInvoiceStatus.Draft
        Sent -> invoice.status == SalesInvoiceStatus.Sent
        Paid -> invoice.status == SalesInvoiceStatus.Paid
        Overdue -> invoice.isOverdue(nowMs)
    }
}

object SalesInvoices {
    /**
     * The list as the web shows it (`SalesPage.jsx:469-523`): the status chip,
     * then the search over reference, client, description and the formatted
     * amount, newest created first.
     */
    fun shown(
        invoices: List<SalesInvoice>,
        filter: SalesFilter,
        search: String,
        projectCurrency: String,
        nowMs: Long,
    ): List<SalesInvoice> {
        val needle = search.trim().lowercase()
        return invoices
            .filter { filter.keeps(it, nowMs) }
            .filter { invoice ->
                needle.isEmpty() ||
                    invoice.reference.lowercase().contains(needle) ||
                    invoice.clientName.lowercase().contains(needle) ||
                    invoice.description.lowercase().contains(needle) ||
                    InvoiceFormat.money(invoice.grossAmount, projectCurrency).lowercase().contains(needle)
            }
            .sortedByDescending { it.createdAtMs ?: 0L }
    }

    /** `DD Mon YYYY | hh:mm AM` — the preview's audit stamp (`SalesPage.jsx:83-87`). */
    fun stamp(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (ms == null || ms <= 0) return "—"
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        val hour12 = (t.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
        val half = if (t.hour < HALF_DAY) str(S.desktop_inv_time_am) else str(S.desktop_inv_time_pm)
        val day = t.day.toString().padStart(2, '0')
        val clock = "${hour12.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')} $half"
        return "$day ${str(MONTHS[t.month.ordinal])} ${t.year} | $clock"
    }

    private const val HALF_DAY = 12

    private val MONTHS = listOf(
        S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar,
        S.desktop_month_short_apr, S.desktop_month_short_may, S.desktop_month_short_jun,
        S.desktop_month_short_jul, S.desktop_month_short_aug, S.desktop_month_short_sep,
        S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
    )
}

/** A country the address picker offers — a row of the core `preset/isd-codes` (`useIsdCodes`). */
data class ClientCountry(val name: String, val code: String)

/** Where a postcode is — the preset geonames lookup's first match; blanks when it found none. */
data class PostcodeMatch(val city: String = "", val state: String = "")
