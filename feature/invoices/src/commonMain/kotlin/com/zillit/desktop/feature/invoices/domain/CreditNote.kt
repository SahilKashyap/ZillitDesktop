package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A credit note or a dispute raised against a vendor — the web's
 * `CreditsPage`, on `/invoices/credit-notes`.
 *
 * The two are one record with a [type]: a credit note reduces what is owed
 * once applied, a dispute holds the argument until it is resolved.
 */
data class CreditNote(
    val id: String,
    val reference: String = "",
    val type: CreditNoteType = CreditNoteType.CreditNote,
    val vendorId: String = "",
    val vendorName: String = "",
    val reason: String = "",
    val description: String = "",
    val grossAmount: Double = 0.0,
    val currency: String = "",
    /** The invoice it is raised against, by number — the number IS the link. */
    val againstInvoice: String = "",
    val invoiceId: String = "",
    val effectiveDateMs: Long? = null,
    val status: CreditNoteStatus = CreditNoteStatus.Pending,
    val notes: String = "",
    /** Credit notes only; a dispute carries just its amount. */
    val lineItems: List<CodedLine> = emptyList(),
    val attachments: List<CreditAttachment> = emptyList(),
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAtMs: Long? = null,
    val updatedAtMs: Long? = null,
    /** The saved `line_items` as sent, so layers and tags survive an edit. */
    val lineItemsJson: String = "",
    /** The type as stored — the preview names `refund` and `write_off` too (`CreditsPage.jsx:910`). */
    val typeRaw: String = "",
    /** Each line's stored `tax_amount`, by line id — the preview prints it rather than recomputing. */
    val lineTaxAmounts: Map<String, Double> = emptyMap(),
) {
    /** "Credit Note", "Refund", "Write Off", "Dispute" — else the stored word, else Credit Note. */
    val typeLabel: String
        get() = when (typeRaw) {
            "", CreditNoteType.CreditNote.wire -> CreditNoteType.CreditNote.label
            CreditNoteType.Dispute.wire -> CreditNoteType.Dispute.label
            "refund" -> str(S.desktop_inv_credit_type_refund)
            "write_off" -> str(S.desktop_inv_credit_type_write_off)
            else -> typeRaw
        }

    /** The web's `cn.reference || cn.id.slice(0, 8).toUpperCase()`. */
    val displayRef: String get() = reference.ifBlank { id.take(REF_CHARS).uppercase() }

    /** Pending or disputed: still open, so Edit, Delete and Apply/Resolve are offered. */
    val isOpen: Boolean get() = status.isActionable

    private companion object {
        const val REF_CHARS = 8
    }
}

/**
 * A file on a credit note: one already stored (kept as it came, so the keys
 * the desktop does not model go back untouched), or one picked and waiting
 * for the save to put it in storage.
 */
data class CreditAttachment(
    val name: String,
    val sizeBytes: Long? = null,
    val stored: InvoiceAttachment? = null,
    /** The stored model's own JSON; blank for a file not yet uploaded. */
    val storedJson: String = "",
    val file: PickedInvoiceFile? = null,
)

enum class CreditNoteType(val wire: String, private val labelKey: String) {
    CreditNote("credit_note", S.desktop_credit_note),
    Dispute("dispute", S.desktop_dispute),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): CreditNoteType = entries.firstOrNull { it.wire == wire } ?: CreditNote
    }
}

/**
 * Where a credit note stands, and what can be done to it next — the web's
 * `STATUS_MAP`, whose `action` is the button each row offers.
 */
enum class CreditNoteStatus(val wire: String, private val labelKey: String, private val actionKey: String) {
    Pending("pending", S.pending, S.dm_filter_apply),
    Applied("applied", S.desktop_applied, S.view),
    Disputed("disputed", S.desktop_disputed, S.desktop_resolve),
    Resolved("resolved", S.ah_alert_filter_resolved, S.view),
    ;

    val label: String get() = str(labelKey)
    val action: String get() = str(actionKey)

    /** Whether the row's button writes something, rather than just opening it. */
    val isActionable: Boolean get() = this == Pending || this == Disputed

    companion object {
        fun from(wire: String?): CreditNoteStatus = entries.firstOrNull { it.wire == wire } ?: Pending
    }
}

/** The filter chips over the credit note list — the web's five, Resolved included. */
enum class CreditNoteFilter(private val labelKey: String, val status: CreditNoteStatus?) {
    All(S.all, null),
    Pending(S.pending, CreditNoteStatus.Pending),
    Applied(S.desktop_applied, CreditNoteStatus.Applied),
    Disputed(S.desktop_disputed, CreditNoteStatus.Disputed),
    Resolved(S.ah_alert_filter_resolved, CreditNoteStatus.Resolved),
    ;

    val label: String get() = str(labelKey)

    fun keeps(note: CreditNote): Boolean = status == null || note.status == status
}

/**
 * A list's Date filter — the credit notes' on the effective date, the
 * register's on the invoice date. The web counts back from now, so "This
 * Month" is the last 30 days, a future date always passes, and so does a row
 * with no date.
 */
enum class DateWindow(private val labelKey: String, val days: Int?) {
    All(S.all, null),
    Week(S.desktop_cr_this_week, WEEK),
    Month(S.desktop_drive_bucket_this_month, MONTH),
    Last30(S.dd_range_30, MONTH),
    Last90(S.dd_range_90, QUARTER),
    ;

    val label: String get() = str(labelKey)

    fun keeps(dateMs: Long?, nowMs: Long): Boolean {
        val days = days ?: return true
        val date = dateMs ?: return true
        return nowMs - date <= days * DAY_MS
    }
}

/** The list's Sort — the web's five. */
enum class CreditNoteSort(private val labelKey: String) {
    Newest(S.desktop_inv_newest_first),
    Oldest(S.ah_oldest_first),
    AmountHigh(S.desktop_inv_amount_high),
    AmountLow(S.desktop_inv_amount_low),
    Vendor(S.ah_sort_vendor_asc),
    ;

    val label: String get() = str(labelKey)

    fun sort(notes: List<CreditNote>): List<CreditNote> = when (this) {
        Newest -> notes.sortedByDescending { it.effectiveDateMs ?: 0L }
        Oldest -> notes.sortedBy { it.effectiveDateMs ?: 0L }
        AmountHigh -> notes.sortedByDescending { it.grossAmount }
        AmountLow -> notes.sortedBy { it.grossAmount }
        Vendor -> notes.sortedBy { it.vendorName.lowercase() }
    }
}

/** What a credit note or dispute save sends — `handleCreate`'s payload, before the wire. */
data class CreditNoteWrite(
    val type: CreditNoteType,
    val vendorId: String,
    val vendorName: String,
    val invoiceReference: String,
    val invoiceId: String,
    val reason: String,
    val effectiveDate: String,
    val currency: String,
    val disputeAmount: Double,
    val notes: String,
    val lines: List<CodedLine>,
    val attachments: List<CreditAttachment>,
    /** The record's saved lines, for the layers and tags an edit must keep. */
    val savedLinesJson: String = "",
)

object CreditNotes {
    /** The web's `CN_ATTACH_EXT`: images, PDFs, Word, CSV and Excel. */
    val ATTACHMENT_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "pdf", "doc", "docx", "csv", "xls", "xlsx")

    /**
     * The invoices a credit note may be raised against — `creditableInvoiceSuggestions`:
     * only numbered ones (the number is the link), never one ready to pay,
     * matched on number, supplier or description, at most thirty.
     */
    fun creditable(invoices: List<Invoice>, query: String, vendorName: (Invoice) -> String): List<Invoice> {
        val q = query.trim().lowercase()
        return invoices.asSequence()
            .filter { it.invoiceNumber.isNotBlank() && it.status != InvoiceStatus.ReadyToPay }
            .filter {
                q.isEmpty() || it.invoiceNumber.lowercase().contains(q) ||
                    vendorName(it).lowercase().contains(q) || it.description.lowercase().contains(q)
            }
            .take(SUGGESTIONS)
            .toList()
    }

    /** The search box: reference, vendor, reason, description, the invoice it is against, the amount. */
    fun matches(note: CreditNote, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(note.reference, note.vendorName, note.reason, note.description, note.againstInvoice)
            .any { it.lowercase().contains(q) } || InvoiceFormat.plain(note.grossAmount).contains(q)
    }

    /** What a credit note saves as its gross: its parent lines plus their tax; a dispute, its amount. */
    fun gross(write: CreditNoteWrite): Double = if (write.type == CreditNoteType.Dispute) {
        write.disputeAmount
    } else {
        write.lines.filter { !it.isSplit }.sumOf { it.amount + LineItems.taxOf(it) }
    }

    private const val SUGGESTIONS = 30
}

private const val WEEK = 7
private const val MONTH = 30
private const val QUARTER = 90
private const val DAY_MS = 86_400_000L
