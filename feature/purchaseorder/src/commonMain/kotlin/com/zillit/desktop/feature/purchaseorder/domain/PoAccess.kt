package com.zillit.desktop.feature.purchaseorder.domain

/**
 * Who may do what to a purchase order.
 *
 * A port of the web's `accountHub/utils/po-permissions.js`, kept in the domain
 * rather than spread through the screens for the reason that file gives twice:
 * every one of these rules fails *silently* when it is wrong. A button simply
 * never appears, on an order that should have had it, and nothing says why.
 *
 * The two shapes matter. A **denylist** (process, reassign) keeps working when
 * the server adds an in-flight status; an **allowlist** (email) mirrors a server
 * rule that refuses everything else, so a new status has to be opted into on
 * both sides.
 */
object PoAccess {

    /**
     * Statuses from which an order may not be processed.
     *
     * Two groups, and the web spells out both: pre-approval (`draft`,
     * `pending`) has not cleared the chain yet, and `rejected`/`closed` are
     * terminal. `posted` is deliberately absent — the Posted tab's own Process
     * button has always routed a posted order to the processing page, so
     * listing it here would only make the two surfaces disagree.
     */
    private val NON_PROCESSABLE = setOf(PoStatus.Draft, PoStatus.AwaitingApproval, PoStatus.Rejected, PoStatus.Closed)

    /**
     * Statuses from which the vendor email may be sent — the server's own
     * emailable set (`APPROVED`, `ACCT_ENTERED`, `POSTED`, `CLOSED`).
     *
     * Note it includes `closed`, unlike [canProcess], which treats closed as
     * terminal.
     */
    private val EMAILABLE = setOf(
        PoStatus.Approved,
        PoStatus.AccountsEntered,
        PoStatus.Queued,
        PoStatus.Posted,
        PoStatus.Closed,
    )

    /** Every approval tier has passed. */
    private val FULLY_APPROVED =
        setOf(PoStatus.Approved, PoStatus.Posted, PoStatus.Queued, PoStatus.AccountsEntered)

    /**
     * May [viewer] process (code and post) this order?
     *
     * Senior accountants always; otherwise only the accountant it is assigned
     * to. An approver who is not the assignee may open an order to approve or
     * reject it, and must not be able to process it.
     *
     * Console only: the department module hands its detail modal no `onProcess`
     * (`DepartmentPOModule.jsx:1636`), so an accountant who opened the tool from
     * the Film Tools tile has no Process anywhere, whatever they hold.
     */
    fun canProcess(order: PurchaseOrder, viewer: PoViewer): Boolean {
        if (!viewer.isAccountant) return false
        if (order.status in NON_PROCESSABLE) return false
        if (viewer.isSeniorAccountant) return true
        return order.assignedTo != null && order.assignedTo == viewer.userId
    }

    /**
     * May [viewer] email this order to its vendor?
     *
     * Three independent conditions: the status is emailable, the viewer holds
     * the same rights the process gate wants, and it has not already been sent.
     *
     * [allowResend] drops the last condition **and nothing else**. It is passed
     * on the processing page, where the order is corrected and the vendor needs
     * the amended copy; the read-only detail stays one-shot, because a send
     * from there could only ever duplicate the same document.
     */
    fun canSendVendorEmail(order: PurchaseOrder, viewer: PoViewer, allowResend: Boolean = false): Boolean {
        // Console only, for the reason given at [canProcess]: no `onSendVendorEmail` either.
        if (!viewer.isAccountant || order.status !in EMAILABLE) return false
        if (order.emailed && !allowResend) return false
        return viewer.isSeniorAccountant || (order.assignedTo != null && order.assignedTo == viewer.userId)
    }

    /**
     * May this order be (re)assigned to another accountant?
     *
     * Not while it is pre-approval (`pending`) or dead (`rejected`). Posted and
     * closed orders stay reassignable, which is the web's own decision.
     *
     * By the senior designation in the console only (`canReassignPO(po,
     * isAccountant)` with the module's `isAccountant`, [PoViewer.isConsoleSenior]):
     * an accounts assistant sees the assignee, and cannot click it.
     */
    fun canReassign(order: PurchaseOrder, viewer: PoViewer): Boolean {
        if (!viewer.isConsoleSenior) return false
        return order.status != PoStatus.AwaitingApproval && order.status != PoStatus.Rejected
    }

    /**
     * May [viewer] open Edit on this order?
     *
     * Before full approval the raiser may always edit. After it, it depends on
     * the production's `allow_amend_after_approval` — except `posted`, which is
     * in the ledger and where amending would desync the books whatever the
     * setting says.
     *
     * Amendments are paused ([AMENDMENTS_ENABLED]), so today the
     * last line is the whole answer for a fully-approved order: no Edit button.
     * The wire key is still read and written, so a production that had turned
     * them on keeps its choice for when the flag flips.
     */
    fun canEdit(order: PurchaseOrder, viewer: PoViewer, allowAmendAfterApproval: Boolean): Boolean {
        val isCreator = order.raisedBy != null && order.raisedBy == viewer.userId
        if (!isCreator) return false
        if (order.status !in FULLY_APPROVED) return true
        if (order.status == PoStatus.Posted) return false
        return AMENDMENTS_ENABLED && allowAmendAfterApproval
    }

    /**
     * Does editing this order count as an amendment rather than ordinary
     * editing? True once every tier has passed — the edit discards that chain
     * and sends the order back through it, which is why it is confirmed first.
     */
    fun isAmendment(status: PoStatus): Boolean = status in FULLY_APPROVED && status != PoStatus.Posted

    /**
     * PA/FC in the console, or the accountant this order is assigned to — the
     * web's `isPOAssigneeOrSenior`.
     *
     * These are the people working the order, and they delete it on the
     * raiser's terms (web PR #6542). The assignee branch is console-only for
     * the same reason the senior branch is: `assignedTo` names an accountant,
     * and the department module offers none of these actions.
     */
    fun isAssigneeOrSenior(order: PurchaseOrder, viewer: PoViewer): Boolean {
        if (viewer.isConsoleSenior) return true
        return viewer.isAccountant && order.assignedTo != null && order.assignedTo == viewer.userId
    }

    /**
     * Approved but not yet posted — the statuses the server will take back
     * through the chain (`PO_RETURNABLE_STATUSES`).
     *
     * `rejected` is deliberately out: the server allows it, but the action is
     * for un-approving (user, 2026-10-21). Posted and closed are refused
     * server-side (`po_cannot_return_to_approval`): posting has downstream
     * data a status flip would strand.
     */
    private val RETURNABLE = setOf(PoStatus.Approved, PoStatus.Queued, PoStatus.AccountsEntered)

    /**
     * May [viewer] send this order back through its approval chain —
     * `POST /:id/return-to-approval`, the web's `canReturnPOToApproval`?
     *
     * Whoever may Process it: PA/FC or the assignee, the people working the
     * order (web PR #6519).
     */
    fun canReturnToApproval(order: PurchaseOrder, viewer: PoViewer): Boolean =
        order.status in RETURNABLE && canProcess(order, viewer)

    /**
     * May [viewer] delete this order?
     *
     * The raiser, at **any status and in any period** — posted and closed
     * included: the backend reverses whatever was posted, to the ledger, the
     * cost report and the bank rec (web PR #6519, user 2026-10-21; it was
     * "before full approval, never rejected" until then). On the accountant
     * side PA/FC and the order's assignee get exactly the same
     * ([isAssigneeOrSenior], web PR #6542).
     *
     * The same answer wherever it is asked: the detail modal and the
     * processing page agree, which is why there is no longer a page to pass.
     */
    fun canDelete(order: PurchaseOrder, viewer: PoViewer): Boolean {
        val isCreator = order.raisedBy != null && order.raisedBy == viewer.userId
        return isCreator || isAssigneeOrSenior(order, viewer)
    }

    /**
     * Purchase-order amendments after approval: built, wired, and hidden.
     *
     * The web keeps the same flag (`AMENDMENTS_ENABLED` in
     * `data/purchase-orders.js`) for the same reason — flipping one constant
     * restores the whole feature, and the wire key keeps round-tripping
     * meanwhile so nobody's saved choice is lost.
     */
    const val AMENDMENTS_ENABLED = false
}
