package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** How a pill reads and colours. Kept UI-agnostic so the rules are testable. */
enum class BadgeTone { Neutral, Pending, Approved, Rejected, Override }

data class InvoiceBadge(val label: String, val tone: BadgeTone)

/**
 * The gating the web spreads over three pages, in one place. Every rule reads
 * exactly as `DepartmentInvoiceModule` / `ApprovalPage` / `InvoiceDetailModal`
 * do it — the server also enforces, but hiding a button the server would
 * accept is the more common bug.
 */
/**
 * Why an invoice was held for query — the web's `HOLD_REASONS`.
 *
 * The first entry is its placeholder, so a reason is a real choice; the last
 * demands the notes that explain it.
 */
enum class HoldReason(private val labelKey: String) {
    AdjustmentRequired(S.desktop_hold_invoice_adjustment_required),
    AwaitingCreditNote(S.desktop_hold_awaiting_credit_note),
    PoAmendmentNeeded("PO Amendment Needed"),
    QueryingAmount(S.desktop_hold_querying_amount_with_vendor),
    MissingDocumentation(S.desktop_hold_missing_supporting_documentation),
    TaxQuery(S.desktop_hold_tax_query),
    DuplicateCheck(S.desktop_hold_duplicate_invoice_check),
    AwaitingHodConfirmation(S.desktop_hold_awaiting_hod_confirmation),
    Other(S.desktop_other_specify_in_notes),
    ;

    val label: String get() = str(labelKey)

    /** The web refuses "Other" with nothing written down. */
    fun needsNotes(): Boolean = this == Other
}

object InvoiceRules {

    /** The "Approval" column of the department tables. */
    fun approvalBadge(invoice: Invoice, tiers: List<ResolvedTier>): InvoiceBadge = when {
        invoice.payMethod.isUrgent -> urgentBadge(invoice)
        invoice.isApproved -> InvoiceBadge(str(S.approved), BadgeTone.Approved)
        invoice.isRejected -> InvoiceBadge(str(S.rejected), BadgeTone.Rejected)
        invoice.status == InvoiceStatus.Approval && tiers.isNotEmpty() ->
            InvoiceBadge(str(S.ah_status_pending_progress, invoice.approvedCount, tiers.size), BadgeTone.Pending)
        else -> InvoiceBadge(str(S.pending), BadgeTone.Neutral)
    }

    /** The accountant Approval Queue's variant: override rows read as such. */
    fun queueBadge(invoice: Invoice, tiers: List<ResolvedTier>): InvoiceBadge = when {
        invoice.status == InvoiceStatus.Override && invoice.payMethod.isUrgent -> urgentBadge(invoice)
        invoice.status == InvoiceStatus.Override -> InvoiceBadge(str(S.dm_nom_table_override), BadgeTone.Override)
        invoice.payMethod.isUrgent -> urgentBadge(invoice)
        invoice.isApproved -> InvoiceBadge(str(S.approved), BadgeTone.Approved)
        invoice.isRejected -> InvoiceBadge(str(S.rejected), BadgeTone.Rejected)
        else -> InvoiceBadge(str(S.ah_status_pending_progress, invoice.approvedCount, tiers.size), BadgeTone.Pending)
    }

    fun urgentBadge(invoice: Invoice): InvoiceBadge {
        val request = if (invoice.payMethod == PayMethod.Cheque) {
            str(S.desktop_cheque_request)
        } else {
            str(S.desktop_urgent_wire_request)
        }
        val label = if (invoice.hasPo) request else str(S.desktop_inv_no_po_request, request)
        return InvoiceBadge(label, BadgeTone.Rejected)
    }

    /** Department view: only my own, still-pending uploads. */
    fun canDeleteOwn(invoice: Invoice, viewer: InvoiceViewer): Boolean =
        invoice.userId == viewer.userId && invoice.approvalStatus == ApprovalStatus.Pending &&
            invoice.status != InvoiceStatus.Approved && invoice.status != InvoiceStatus.Rejected

    /** Inbox: whoever entered it may delete it. */
    fun canDeleteInbox(invoice: Invoice, viewer: InvoiceViewer): Boolean = invoice.userId == viewer.userId

    /** Approve / Reject in the detail footer. */
    fun showApproveReject(invoice: Invoice, tiers: List<ResolvedTier>, viewer: InvoiceViewer): Boolean =
        ApprovalChain.canApprove(invoice, tiers, viewer.userId) && !invoice.payMethod.isUrgent

    /** Override (skip the chain): a senior who is not themselves on the chain. */
    fun showOverride(invoice: Invoice, tiers: List<ResolvedTier>, viewer: InvoiceViewer): Boolean =
        viewer.canOverride &&
            !ApprovalChain.isApprover(tiers, viewer.userId) &&
            !ApprovalChain.canApprove(invoice, tiers, viewer.userId) &&
            invoice.status == InvoiceStatus.Approval &&
            invoice.approvalStatus != ApprovalStatus.Approved &&
            !invoice.payMethod.isOverridePayable

    /** Override & Pay: an override row, or an urgent (wire/cheque/faster) one. */
    fun showOverrideAndPay(invoice: Invoice, viewer: InvoiceViewer): Boolean =
        viewer.canOverride && (invoice.status == InvoiceStatus.Override || invoice.payMethod.isOverridePayable)

    /** Chase: nudge the next approver when it is not me and nothing is decided. */
    fun showChase(invoice: Invoice, tiers: List<ResolvedTier>, viewer: InvoiceViewer): Boolean =
        !ApprovalChain.canApprove(invoice, tiers, viewer.userId) &&
            !invoice.isApproved &&
            invoice.status != InvoiceStatus.Rejected &&
            invoice.status != InvoiceStatus.Override &&
            invoice.approvalStatus != ApprovalStatus.Approved

    /** The approval chain panel only makes sense on chain-routed rows. */
    fun showChain(invoice: Invoice, tiers: List<ResolvedTier>): Boolean =
        tiers.isNotEmpty() && !invoice.payMethod.isUrgent &&
            invoice.status in setOf(InvoiceStatus.Approval, InvoiceStatus.Approved, InvoiceStatus.Rejected)

    /** Days overdue for the register's Due column; null when not overdue. */
    fun daysOverdue(invoice: Invoice, nowMs: Long): Long? {
        val due = invoice.dueDateMs ?: return null
        if (!invoice.isUnsettled || due >= nowMs) return null
        return (nowMs - due) / MILLIS_PER_DAY
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
