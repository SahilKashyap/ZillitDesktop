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
    /** The invoice it is raised against, by reference. */
    val againstInvoice: String = "",
    val effectiveDateMs: Long? = null,
    val status: CreditNoteStatus = CreditNoteStatus.Pending,
    val notes: String = "",
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

/** The filter chips over the credit note list — the web's four. */
enum class CreditNoteFilter(private val labelKey: String, val status: CreditNoteStatus?) {
    All(S.all, null),
    Pending(S.pending, CreditNoteStatus.Pending),
    Applied(S.desktop_applied, CreditNoteStatus.Applied),
    Disputed(S.desktop_disputed, CreditNoteStatus.Disputed),
    ;

    val label: String get() = str(labelKey)

    fun keeps(note: CreditNote): Boolean = status == null || note.status == status
}
