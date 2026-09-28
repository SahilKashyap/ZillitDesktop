package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** How a pill reads and colours. Kept UI-agnostic so the rules are testable. */
enum class BadgeTone {
    Neutral, Pending, Approved, Rejected, Override,

    /** The web's blue pill — a linked PO, a payment term. */
    Info,

    /** The web's purple pill — the Approval Queue's "Pending (x/y)". */
    Awaiting,

    /** The web's green pill — the Approval Queue's "Approved". */
    Success,
}

data class InvoiceBadge(val label: String, val tone: BadgeTone)

/**
 * Why an invoice was held for query — the web's `HOLD_REASONS`.
 *
 * [wire] is what the server stores: the web's fixed English wording
 * (`HoldForQueryModal.jsx`), whatever language the screen is in. Sending the
 * translated label would file the same reason under a different string per
 * language, and every other client reads the English back. The last reason
 * demands the notes that explain it.
 */
enum class HoldReason(private val labelKey: String, val wire: String) {
    AdjustmentRequired(S.desktop_hold_invoice_adjustment_required, "Invoice Adjustment Required"),
    AwaitingCreditNote(S.desktop_hold_awaiting_credit_note, "Awaiting Credit Note"),
    PoAmendmentNeeded(S.desktop_hold_po_amendment_needed, "PO Amendment Needed"),
    QueryingAmount(S.desktop_hold_querying_amount_with_vendor, "Querying Amount with Vendor"),
    MissingDocumentation(S.desktop_hold_missing_supporting_documentation, "Missing Supporting Documentation"),
    TaxQuery(S.desktop_hold_tax_query, "Tax Query"),
    DuplicateCheck(S.desktop_hold_duplicate_invoice_check, "Duplicate Invoice Check"),
    AwaitingHodConfirmation(S.desktop_hold_awaiting_hod_confirmation, "Awaiting HoD Confirmation"),
    Other(S.desktop_other_specify_in_notes, "Other (specify in notes)"),
    ;

    /** What the picker shows, in the reader's language. */
    val label: String get() = str(labelKey)

    /** The web refuses "Other" with nothing written down. */
    fun needsNotes(): Boolean = this == Other
}

/**
 * The gating the web spreads over three pages, in one place. Every rule reads
 * exactly as `DepartmentInvoiceModule` / `ApprovalPage` / `InvoiceDetailModal`
 * do it — the server also enforces, but hiding a button the server would
 * accept is the more common bug.
 */
object InvoiceRules {

    /**
     * The "Approval" column of the department tables. The "No PO · " prefix
     * reads the board's own `hasPO` — `linked_pos` or `po_id`, never a bare
     * typed `po_number` (`DepartmentInvoiceModule.jsx:924-925, 1206`).
     */
    fun approvalBadge(invoice: Invoice, tiers: List<ResolvedTier>): InvoiceBadge = when {
        invoice.payMethod.isUrgent -> urgentBadge(invoice, hasPo = invoice.hasMatchedPo)
        invoice.isApproved -> InvoiceBadge(str(S.approved), BadgeTone.Approved)
        invoice.isRejected -> InvoiceBadge(str(S.rejected), BadgeTone.Rejected)
        invoice.status == InvoiceStatus.Approval && tiers.isNotEmpty() ->
            InvoiceBadge(str(S.ah_status_pending_progress, invoice.approvedCount, tiers.size), BadgeTone.Pending)
        else -> InvoiceBadge(str(S.pending), BadgeTone.Neutral)
    }

    /** The accountant Approval Queue's variant: override rows read as such. */
    /**
     * The accountant Approval Queue's "Approval" column, in the web's order
     * (`ApprovalPage.jsx:593-607`):
     *
     * 1. an override row paid wire / cheque / faster — red, "No PO · " only
     *    when `linked_pos` is empty, then the request's name;
     * 2. any other override row — red "Override";
     * 3. a wire or cheque row — red, "No PO · " when neither `linked_pos`
     *    nor `po_id` is set;
     * 4. `status` approved — green "Approved";
     * 5. `status` rejected — red "Rejected";
     * 6. a chain — purple "Pending (x/y)"; no chain — purple "Pending".
     */
    fun queueBadge(invoice: Invoice, tiers: List<ResolvedTier>): InvoiceBadge = when {
        invoice.status == InvoiceStatus.Override && invoice.payMethod.isOverridePayable -> {
            val request = when (invoice.payMethodRaw.ifBlank { invoice.payMethod.wire }) {
                PayMethod.Wire.wire -> str(S.desktop_urgent_wire_request)
                PayMethod.Cheque.wire -> str(S.desktop_cheque_request)
                else -> str(S.desktop_faster_payment)
            }
            val label = if (invoice.linkedPos.isEmpty()) str(S.desktop_inv_no_po_request, request) else request
            InvoiceBadge(label, BadgeTone.Rejected)
        }
        invoice.status == InvoiceStatus.Override -> InvoiceBadge(str(S.dm_nom_table_override), BadgeTone.Rejected)
        invoice.isUrgentRaw -> urgentBadge(invoice, hasPo = invoice.hasMatchedPo)
        invoice.isApprovedStatus -> InvoiceBadge(str(S.approved), BadgeTone.Success)
        invoice.status == InvoiceStatus.Rejected -> InvoiceBadge(str(S.rejected), BadgeTone.Rejected)
        tiers.isNotEmpty() ->
            InvoiceBadge(str(S.ah_status_pending_progress, invoice.approvedCount, tiers.size), BadgeTone.Awaiting)
        else -> InvoiceBadge(str(S.pending), BadgeTone.Awaiting)
    }

    fun urgentBadge(invoice: Invoice, hasPo: Boolean = invoice.hasPo): InvoiceBadge {
        val request = if (invoice.payMethod == PayMethod.Cheque) {
            str(S.desktop_cheque_request)
        } else {
            str(S.desktop_urgent_wire_request)
        }
        val label = if (hasPo) request else str(S.desktop_inv_no_po_request, request)
        return InvoiceBadge(label, BadgeTone.Rejected)
    }

    /** Department view: only my own, still-pending uploads. */
    fun canDeleteOwn(invoice: Invoice, viewer: InvoiceViewer): Boolean =
        invoice.userId == viewer.userId && invoice.approvalStatus == ApprovalStatus.Pending &&
            invoice.status != InvoiceStatus.Approved && invoice.status != InvoiceStatus.Rejected

    /**
     * Delete from an approval queue — the web's `canDeleteInvoice`
     * (`utils/approval-helpers.js:404-418`), rule for rule. The delete is a
     * hard one, so the gate is tighter than "can see the row":
     *
     * 1. never in a closed cost-report period ([locked]);
     * 2. never once approved (`approval_status`), or approved / overridden (`status`);
     * 3. part-approved (any tier signed) — override rights only;
     * 4. untouched chain — override rights, the creator, or anyone configured
     *    on any tier of this invoice's chain ([tiers]).
     *
     * [canOverride] is the surface's own override right: the department
     * board passes [InvoiceViewer.serverOverride], the accountant queue
     * [InvoiceViewer.canOverride].
     */
    fun canDeleteFromQueue(
        invoice: Invoice,
        tiers: List<ResolvedTier>,
        viewer: InvoiceViewer,
        canOverride: Boolean,
        locked: Boolean,
    ): Boolean {
        if (locked) return false
        if (invoice.approvalStatus == ApprovalStatus.Approved) return false
        if (invoice.status == InvoiceStatus.Approved || invoice.status == InvoiceStatus.Override) return false
        if (invoice.approvedCount > 0) return canOverride
        val isCreator = viewer.userId.isNotBlank() && invoice.userId == viewer.userId
        return canOverride || isCreator || ApprovalChain.isApprover(tiers, viewer.userId)
    }

    /**
     * Inbox: whoever entered it may delete it — `item.user_id && item.user_id
     * === userId` (`InboxPage.jsx:470`), so a row with no creator is nobody's.
     */
    fun canDeleteInbox(invoice: Invoice, viewer: InvoiceViewer): Boolean =
        invoice.userId.isNotBlank() && invoice.userId == viewer.userId

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
            !invoice.isApprovedStatus &&
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

    /**
     * The Register's Due column (`RegisterPage.jsx:238-242, 562-564`): paid,
     * posted, cancelled and rejected rows are settled; otherwise the whole
     * days past due, and null — the plain due date — unless that is above 0.
     */
    fun registerOverdueDays(invoice: Invoice, nowMs: Long): Long? {
        val due = invoice.dueDateMs ?: return null
        if (invoice.status in REGISTER_SETTLED || due >= nowMs) return null
        return ((nowMs - due) / MILLIS_PER_DAY).takeIf { it > 0 }
    }

    private val REGISTER_SETTLED = setOf(
        InvoiceStatus.Paid,
        InvoiceStatus.Posted,
        InvoiceStatus.Cancelled,
        InvoiceStatus.Rejected,
    )

    private const val MILLIS_PER_DAY = 86_400_000L
}
