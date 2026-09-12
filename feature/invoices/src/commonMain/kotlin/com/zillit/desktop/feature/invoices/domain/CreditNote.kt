package com.zillit.desktop.feature.invoices.domain

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
    /** The invoice it is raised against, by reference. */
    val againstInvoice: String = "",
    val effectiveDateMs: Long? = null,
    val status: CreditNoteStatus = CreditNoteStatus.Pending,
    val notes: String = "",
)

enum class CreditNoteType(val wire: String, val label: String) {
    CreditNote("credit_note", "Credit Note"),
    Dispute("dispute", "Dispute"),
    ;

    companion object {
        fun from(wire: String?): CreditNoteType = entries.firstOrNull { it.wire == wire } ?: CreditNote
    }
}

/**
 * Where a credit note stands, and what can be done to it next — the web's
 * `STATUS_MAP`, whose `action` is the button each row offers.
 */
enum class CreditNoteStatus(val wire: String, val label: String, val action: String) {
    Pending("pending", "Pending", "Apply"),
    Applied("applied", "Applied", "View"),
    Disputed("disputed", "Disputed", "Resolve"),
    Resolved("resolved", "Resolved", "View"),
    ;

    /** Whether the row's button writes something, rather than just opening it. */
    val isActionable: Boolean get() = this == Pending || this == Disputed

    companion object {
        fun from(wire: String?): CreditNoteStatus = entries.firstOrNull { it.wire == wire } ?: Pending
    }
}

/** The filter chips over the credit note list — the web's four. */
enum class CreditNoteFilter(val label: String, val status: CreditNoteStatus?) {
    All("All", null),
    Pending("Pending", CreditNoteStatus.Pending),
    Applied("Applied", CreditNoteStatus.Applied),
    Disputed("Disputed", CreditNoteStatus.Disputed),
    ;

    fun keeps(note: CreditNote): Boolean = status == null || note.status == status
}
