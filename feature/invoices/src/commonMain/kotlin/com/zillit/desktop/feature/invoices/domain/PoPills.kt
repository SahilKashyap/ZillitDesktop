package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The PO and status pills of the three accountant tables, each exactly as its
 * web page builds it. They differ on purpose — the pages disagree about what
 * counts as "having a PO" — so each has its own rule rather than the shared
 * [Invoice.poLabel], which the other pages keep.
 */
object PoPills {

    /**
     * The Register's PO cell (`RegisterPage.jsx:211-223, 545-547`): an urgent
     * wire / cheque row is blue when anything is linked (`linked_pos` or
     * `po_id`); every other row only when `linked_pos` has something — a
     * typed `po_number` alone is "No PO".
     */
    fun register(invoice: Invoice): InvoiceBadge = when {
        invoice.isUrgentRaw -> if (invoice.hasMatchedPo) {
            InvoiceBadge(invoice.linkedPoLabel.orEmpty(), BadgeTone.Info)
        } else {
            noPo()
        }
        invoice.linkedPos.isNotEmpty() -> InvoiceBadge(invoice.linkedPoLabel ?: str(S.desktop_no_po), BadgeTone.Info)
        else -> noPo()
    }

    /**
     * The Approval Queue's PO cell (`ApprovalPage.jsx:453-463, 586-588`): as
     * the Register for urgent rows; otherwise red whenever `linked_pos` is
     * empty, but labelled with the typed `po_number` when there is one.
     */
    fun queue(invoice: Invoice): InvoiceBadge = when {
        invoice.isUrgentRaw -> register(invoice)
        else -> InvoiceBadge(
            invoice.linkedPoLabel ?: str(S.desktop_no_po),
            if (invoice.linkedPos.isNotEmpty()) BadgeTone.Info else BadgeTone.Rejected,
        )
    }

    /**
     * Pre-approval's PO cell (`MatchingPage.jsx:195-199, 695, 733-737`).
     *
     * A waiting row is blue when matched (`linked_pos` or `po_id`), amber for
     * a typed number nobody has confirmed, red otherwise. A held row is never
     * amber, and an urgent one with nothing linked says which request it is.
     */
    fun matching(invoice: Invoice): InvoiceBadge {
        val label = matchingLabel(invoice) ?: str(S.desktop_no_po)
        return when {
            invoice.status != InvoiceStatus.Held -> InvoiceBadge(
                label,
                when {
                    invoice.hasMatchedPo -> BadgeTone.Info
                    invoice.poNumber.isNotBlank() -> BadgeTone.Pending
                    else -> BadgeTone.Rejected
                },
            )
            invoice.isUrgentRaw && !invoice.hasMatchedPo ->
                InvoiceBadge(str(S.desktop_inv_no_po_request, urgentLabel(invoice)), BadgeTone.Rejected)
            else -> InvoiceBadge(label, if (invoice.hasMatchedPo) BadgeTone.Info else BadgeTone.Rejected)
        }
    }

    /**
     * Pre-approval's Status cell: a held row is amber "On Hold"; an urgent one
     * names its request ("No PO · " when nothing is linked); otherwise
     * Matched or No PO (`MatchingPage.jsx:697-700, 739`).
     */
    fun matchingStatus(invoice: Invoice): InvoiceBadge = when {
        invoice.status == InvoiceStatus.Held -> InvoiceBadge(str(S.desktop_on_hold_title), BadgeTone.Pending)
        invoice.isUrgentRaw -> urgentRequest(invoice, invoice.hasMatchedPo)
        invoice.hasMatchedPo -> InvoiceBadge(str(S.desktop_matched), BadgeTone.Approved)
        else -> noPo()
    }

    /** The Register's Status cell for an urgent, non-override row (`RegisterPage.jsx:552-554`). */
    fun registerUrgentStatus(invoice: Invoice): InvoiceBadge = urgentRequest(invoice, invoice.hasMatchedPo)

    /** "3 POs", the one linked order's number (or id), else the typed number; null for none. */
    fun matchingLabel(invoice: Invoice): String? = when {
        invoice.linkedPos.size > 1 -> str(S.desktop_po_count_pos, invoice.linkedPos.size)
        invoice.linkedPos.size == 1 -> invoice.linkedPos.first().let { it.poNumber.ifBlank { it.poId } }
        else -> invoice.poNumber.takeIf { it.isNotBlank() }
    }

    /** "Urgent Wire Request" or "Cheque Request", read off the stored pay method. */
    fun urgentLabel(invoice: Invoice): String =
        if (invoice.payMethodRaw.ifBlank { invoice.payMethod.wire } == PayMethod.Cheque.wire) {
            str(S.desktop_cheque_request)
        } else {
            str(S.desktop_urgent_wire_request)
        }

    private fun urgentRequest(invoice: Invoice, hasPo: Boolean): InvoiceBadge {
        val request = urgentLabel(invoice)
        return InvoiceBadge(if (hasPo) request else str(S.desktop_inv_no_po_request, request), BadgeTone.Rejected)
    }

    private fun noPo() = InvoiceBadge(str(S.desktop_no_po), BadgeTone.Rejected)
}
