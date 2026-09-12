package com.zillit.desktop.feature.invoices.domain

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
enum class HoldReason(val label: String) {
    AdjustmentRequired("Invoice Adjustment Required"),
    AwaitingCreditNote("Awaiting Credit Note"),
    PoAmendmentNeeded("PO Amendment Needed"),
    QueryingAmount("Querying Amount with Vendor"),
    MissingDocumentation("Missing Supporting Documentation"),
    TaxQuery("Tax Query"),
    DuplicateCheck("Duplicate Invoice Check"),
    AwaitingHodConfirmation("Awaiting HoD Confirmation"),
    Other("Other (specify in notes)"),
    ;

    /** The web refuses "Other" with nothing written down. */
    fun needsNotes(): Boolean = this == Other
}

object InvoiceRules {

    /** The "Approval" column of the department tables. */
    fun approvalBadge(invoice: Invoice, tiers: List<ResolvedTier>): InvoiceBadge = when {
        invoice.payMethod.isUrgent -> urgentBadge(invoice)
        invoice.isApproved -> InvoiceBadge("Approved", BadgeTone.Approved)
        invoice.isRejected -> InvoiceBadge("Rejected", BadgeTone.Rejected)
        invoice.status == InvoiceStatus.Approval && tiers.isNotEmpty() ->
            InvoiceBadge("Pending (${invoice.approvedCount}/${tiers.size})", BadgeTone.Pending)
        else -> InvoiceBadge("Pending", BadgeTone.Neutral)
    }

    /** The accountant Approval Queue's variant: override rows read as such. */
    fun queueBadge(invoice: Invoice, tiers: List<ResolvedTier>): InvoiceBadge = when {
        invoice.status == InvoiceStatus.Override && invoice.payMethod.isUrgent -> urgentBadge(invoice)
        invoice.status == InvoiceStatus.Override -> InvoiceBadge("Override", BadgeTone.Override)
        invoice.payMethod.isUrgent -> urgentBadge(invoice)
        invoice.isApproved -> InvoiceBadge("Approved", BadgeTone.Approved)
        invoice.isRejected -> InvoiceBadge("Rejected", BadgeTone.Rejected)
        else -> InvoiceBadge("Pending (${invoice.approvedCount}/${tiers.size})", BadgeTone.Pending)
    }

    fun urgentBadge(invoice: Invoice): InvoiceBadge {
        val request = if (invoice.payMethod == PayMethod.Cheque) "Cheque Request" else "Urgent Wire Request"
        val prefix = if (invoice.hasPo) "" else "No PO · "
        return InvoiceBadge(prefix + request, BadgeTone.Rejected)
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
