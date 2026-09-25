package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchEdits
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.PostBatchRequest
import kotlinx.coroutines.Job

/**
 * The open batch: its receipts, and every action the web's batch views take.
 *
 * Post & Ledger, Audit, Coding, Sign-off and the Approval Queue all open one
 * batch and act on it. Each handler here checks the same rule the screen draws
 * from — [CashRules] — so an action the screen hides cannot be reached by an
 * event either.
 */
@Suppress("TooManyFunctions") // One handler per batch action.
internal class BatchDesk(private val host: CashHost) {

    private var loadJob: Job? = null
    private var queryJob: Job? = null

    /** Editing, saving and splitting the open batch's receipts — see [BatchWorkDesk]. */
    private val work = BatchWorkDesk(host)

    /** The batch view's own events — see [BatchEvent]. */
    fun handle(event: BatchEvent) = when (event) {
        is BatchEvent.SendForApproval -> sendForApproval(event.batchId)
        is BatchEvent.SubmitForReview -> submitForReview(event.batchId)
        is BatchEvent.ForwardCoded -> forwardCoded(event.batchId)
        else -> work.handle(event)
    }

    /**
     * Opens [batchId] in the detail pane and fetches its receipts.
     *
     * A Post & Ledger row assigned to someone else is locked, as on the web:
     * opening it is refused here as well as not offered on screen.
     */
    fun open(batchId: String?) {
        loadJob?.cancel()
        queryJob?.cancel()
        if (batchId == null) {
            host.update { copy(selectedBatchId = null, panel = null) }
            return
        }
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId }
            ?: state.myBatches.firstOrNull { it.id == batchId }
        // The Audit Queue locks its rows the same way (`PCAuditPage.jsx:189-191`).
        if (batch != null && state.destination.locksToAssignee && !CashRules.canOpenPostRow(state.viewer, batch)) {
            host.refuse(str(S.desktop_ce_batch_locked_to_assignee))
            return
        }
        // Sign-off takes the batch's own date or none: the senior picks one
        // before posting (`SeniorBatchItem.jsx:34, 64-67`).
        val seeded = CashDates.utcYmd(batch?.effectiveDate)
            ?: if (state.destination.isSignOff) "" else CashDates.defaultEffective(state.lockedThrough)
        host.update {
            copy(selectedBatchId = batchId, panel = BatchPanel(batchId = batchId, effectiveDate = seeded))
        }
        // Opening a batch reads its receipt rows on this page's key — never
        // its query thread's, which stay lit until the thread is opened.
        state.destination.batchBadgeKey?.let { host.readEntity(it, batchId, CashBadges.KIND_RECEIPT) }
        loadJob = host.work {
            when (val fetched = host.repository.batch(batchId)) {
                is ZillitResult.Success -> host.update {
                    val open = panel?.takeIf { it.batchId == batchId } ?: return@update this
                    copy(
                        panel = open.copy(
                            claims = fetched.data.claims,
                            selectedClaimIds = fetched.data.claims.map { it.id }.toSet(),
                        ),
                    )
                }

                // The row's own receipts, if it carried any, stand in; the
                // pane says it could not load rather than showing none.
                is ZillitResult.Failure -> host.update {
                    val open = panel?.takeIf { it.batchId == batchId } ?: return@update this
                    copy(panel = open.copy(failed = true))
                }
            }
        }
    }

    /** Re-reads the open batch's receipts, keeping what has been typed beside them. */
    fun refresh() {
        val batchId = host.state.panel?.batchId ?: return
        host.work {
            val fetched = (host.repository.batch(batchId) as? ZillitResult.Success)?.data ?: return@work
            editPanel(batchId) {
                val kept = selectedClaimIds?.intersect(fetched.claims.map { it.id }.toSet())
                copy(
                    // Receipts edited and not saved stay as typed — a socket
                    // refresh must not throw away someone's coding.
                    claims = if (dirty) claims else fetched.claims,
                    failed = false,
                    selectedClaimIds = kept ?: fetched.claims.map { it.id }.toSet(),
                )
            }
        }
    }

    fun editDate(ymd: String) = editPanel { copy(effectiveDate = ymd) }

    fun editNotes(text: String) = editPanel { copy(seniorNotes = text) }

    fun toggleClaim(claimId: String) = editPanel {
        val chosen = selectedClaimIds.orEmpty()
        copy(selectedClaimIds = if (claimId in chosen) chosen - claimId else chosen + claimId)
    }

    fun selectAll(selected: Boolean) = editPanel {
        copy(selectedClaimIds = if (selected) claims.orEmpty().map { it.id }.toSet() else emptySet())
    }

    // -- audit ------------------------------------------------------------------

    /** The auditor's per-receipt Verify, saved with the batch's claims as they stand. */
    @Suppress("ReturnCount") // One early return per precondition, as the web's handler has them.
    fun toggleVerify(claimId: String) {
        val state = host.state
        val batch = state.selectedBatch ?: return
        val claims = state.panel?.claims ?: return
        val claim = claims.firstOrNull { it.id == claimId } ?: return
        if (state.destination != CashDestination.AuditQueue || !state.mayWorkOn(batch)) return host.noRights()
        if (state.selectedLocked) return host.refuse(lockedMessage())
        val next = !claim.isVerified
        editPanel { copy(verifying = claimId) }
        host.work {
            // Every receipt as it stands, as the web's verify sends its whole
            // payload — so a Verify also keeps what was typed.
            val wire = BatchEdits.forWire(claims, host.state::wrapNominal)
            val saved = host.repository.saveClaims(batch.id, wire, mapOf(claimId to next))
            host.update {
                val open = panel?.takeIf { it.batchId == batch.id } ?: return@update this
                copy(
                    panel = open.copy(
                        verifying = null,
                        dirty = open.dirty && saved !is ZillitResult.Success,
                        claims = if (saved is ZillitResult.Success) {
                            open.claims?.map { if (it.id == claimId) it.copy(isVerified = next) else it }
                        } else {
                            open.claims
                        },
                    ),
                )
            }
            (saved as? ZillitResult.Failure)?.let { host.report(it.error) }
        }
    }

    /**
     * Send for Approval: every receipt verified and coded.
     *
     * The web's button waits on verification and its handler on coding
     * (`PCPostLedgerPage.jsx:977-1003`); both are checked here.
     */
    @Suppress("ReturnCount") // One refusal per rule, in the web's order.
    fun sendForApproval(batchId: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        if (state.destination != CashDestination.AuditQueue || !state.mayWorkOn(batch)) return host.noRights()
        val claims = readyClaims(batchId) ?: return
        if (state.selectedLocked) return host.refuse(lockedMessage())
        if (!CashRules.allVerified(claims)) return host.refuse(str(S.desktop_ce_verify_every_receipt))
        if (!CashRules.allCoded(claims)) {
            val missing = CashRules.missingNominals(claims)
            return host.refuse(
                if (missing > 0) {
                    str(S.desktop_ce_lines_need_nominal_approval, missing)
                } else {
                    str(S.desktop_ce_code_every_receipt)
                },
            )
        }
        host.act(str(S.ah_verified), onSuccess = { closed() }) {
            host.repository.saveAndVerify(batchId, BatchEdits.forWire(claims, host.state::wrapNominal))
        }
    }

    /** The coordinator's Forward to Accounts: nothing leaves the coding queue uncoded. */
    fun forwardCoded(batchId: String) {
        if (!host.state.viewer.isCoordinator) return host.noRights()
        val claims = readyClaims(batchId) ?: return
        if (!CashRules.allCoded(claims)) return host.refuse(str(S.desktop_ce_code_before_forward))
        host.act(str(S.desktop_ce_coding_submitted), onSuccess = { closed() }) {
            host.repository.saveAndSubmitCoded(batchId, BatchEdits.forWire(claims, host.state::wrapNominal))
        }
    }

    // -- post & ledger, sign-off ---------------------------------------------------

    /**
     * Post & Ledger's Post to Ledger, or the senior sign-off's Approve & Post.
     *
     * The same guards the web applies before its call: rights, the lock, a
     * ledger date inside the allowed range, and — on Post & Ledger — coding
     * that reaches the batch total with a nominal on every line.
     */
    @Suppress("ReturnCount") // One refusal per guard, in the order the web applies them.
    fun post(batchId: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        val signOff = state.destination.isSignOff
        val panel = state.panel?.takeIf { it.batchId == batchId }
        if (!mayPost(state, batch, signOff)) return host.noRights()
        if (CashDates.isLocked(batch.effectiveDate, state.lockedThrough)) return host.refuse(lockedMessage())
        val ymd = panel?.effectiveDate.orEmpty()
        val date = CashDates.utcMillis(ymd)
        if (date == null) return host.refuse(str(S.desktop_po_effective_date_before_posting))
        if (!CashDates.isPostable(ymd, state.lockedThrough)) return host.refuse(str(S.desktop_ce_date_outside_range))
        val request = if (signOff) {
            PostBatchRequest(effectiveDate = date, seniorNotes = panel?.seniorNotes)
        } else {
            val claims = readyClaims(batchId) ?: return
            if (CashRules.amountMismatch(claims, batch.totalGross)) {
                return host.refuse(str(S.desktop_ce_coded_must_match_total))
            }
            val missing = CashRules.missingNominals(claims)
            if (missing > 0) return host.refuse(str(S.desktop_ce_lines_need_nominal_post, missing))
            PostBatchRequest(effectiveDate = date, claims = BatchEdits.forWire(claims, host.state::wrapNominal))
        }
        host.act(str(S.desktop_ce_batch_posted), onSuccess = { closed() }) {
            host.repository.postBatch(batchId, request)
        }
    }

    /** Sign-off's Return to Accounts — an escalated batch only. */
    fun returnToAccounts(batchId: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        if (!state.viewer.canSeeSignOff || !CashRules.canReturnToAccounts(batch)) return host.noRights()
        if (CashDates.isLocked(batch.effectiveDate, state.lockedThrough)) return host.refuse(lockedMessage())
        host.act(str(S.desktop_ce_returned_to_accounts), onSuccess = { closed() }) {
            host.repository.deescalateBatch(batchId)
        }
    }

    fun submitForReview(batchId: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        val allowed = state.destination.isPostLedger && state.mayWorkOn(batch) &&
            CashRules.canSubmitForReview(state.viewer, batch, state.panelClaims)
        if (!allowed) return host.noRights()
        if (CashDates.isLocked(batch.effectiveDate, state.lockedThrough)) return host.refuse(lockedMessage())
        host.act(str(S.desktop_sent_for_review), onSuccess = { closed() }) {
            host.repository.submitBatchForReview(batchId)
        }
    }

    fun escalate(batchId: String, reason: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        val allowed = state.destination.isPostLedger && state.mayWorkOn(batch) &&
            CashRules.canEscalate(state.viewer, batch)
        if (!allowed) return host.noRights()
        host.act(str(S.ah_escalated), onSuccess = { closed() }) { host.repository.escalateBatch(batchId, reason) }
    }

    // -- approval queue -------------------------------------------------------------

    /**
     * Approves the ticked receipts at the level this viewer signs.
     *
     * Every receipt ticked sends `claim_ids: null`, the whole batch; fewer is a
     * partial approval of those (`PCApprovalPage.jsx:648-666`).
     */
    fun approve(batchId: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        if (!CashRules.mayApprove(state.viewer, batch)) return host.noRights()
        val claimIds = chosenClaims(batchId) ?: return
        val step = CashRules.approvalStep(state.viewer, batch.departmentId, batch.totalGross, batch.approvals)
        host.act(str(S.ah_batch_approved_toast), onSuccess = { closed() }, after = { readApproval(batchId) }) {
            host.repository.approveBatch(batchId, step, claimIds)
        }
    }

    /**
     * A batch decided on the approval queue is read, once the server agreed —
     * partial approvals included (`PCApprovalPage.jsx:1097-1110`).
     */
    fun readApproval(batchId: String) =
        host.readEntity(CashBadges.RECEIPT_APPROVAL, batchId, CashBadges.KIND_RECEIPT)

    fun reject(batchId: String, reason: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        // The approval queue's reject, by whoever may approve the batch. The
        // web's audit has no reject — a batch goes back from there by query.
        val allowed = state.destination == CashDestination.ApprovalQueue && CashRules.mayApprove(state.viewer, batch)
        if (!allowed) return host.noRights()
        val claimIds = chosenClaims(batchId) ?: return
        host.act(str(S.ah_batch_rejected_toast), onSuccess = { closed() }, after = { readApproval(batchId) }) {
            host.repository.rejectBatch(batchId, reason, claimIds)
        }
    }

    // -- history and queries ------------------------------------------------------

    fun showHistory(open: Boolean) {
        val batchId = host.state.panel?.batchId ?: return
        editPanel { copy(historyOpen = open) }
        if (!open) return
        host.work {
            when (val history = host.repository.batchHistory(batchId)) {
                is ZillitResult.Success -> editPanel(batchId) { copy(history = history.data) }
                is ZillitResult.Failure -> {
                    editPanel(batchId) { copy(historyOpen = false) }
                    host.report(history.error)
                }
            }
        }
    }

    fun showQuery(open: Boolean) {
        val state = host.state
        val batch = state.selectedBatch ?: return
        queryJob?.cancel()
        if (!open) return editPanel { copy(query = null) }
        if (!state.viewer.isAccountant || !CashRules.canQuery(batch)) return host.noRights()
        editPanel { copy(query = QueryPanel()) }
        queryJob = host.work {
            val thread = host.repository.queryThread(batch.id)
            if (thread is ZillitResult.Success) readQuery(batch.id)
            editPanel(batch.id) {
                val panelQuery = query ?: return@editPanel this
                copy(query = panelQuery.copy(loading = false, thread = (thread as? ZillitResult.Success)?.data))
            }
            (thread as? ZillitResult.Failure)?.let { host.report(it.error) }
        }
    }

    fun editQuery(text: String) = editPanel { copy(query = query?.copy(draft = text)) }

    fun sendQuery() {
        val state = host.state
        val batch = state.selectedBatch ?: return
        val query = state.panel?.query ?: return
        val text = query.draft.trim()
        if (text.isEmpty() || query.sending) return
        if (!state.viewer.isAccountant || !CashRules.canQuery(batch)) return host.noRights()
        editPanel { copy(query = query.copy(sending = true)) }
        host.work {
            when (val sent = host.repository.sendQuery(batch.id, query.thread?.id, text)) {
                is ZillitResult.Success -> {
                    editPanel(batch.id) {
                        copy(query = this.query?.copy(sending = false, draft = "", thread = sent.data))
                    }
                    // The panel is open and the thread just refreshed: what
                    // landed in it is read (ZL-20548).
                    readQuery(batch.id)
                }

                is ZillitResult.Failure -> {
                    editPanel(batch.id) { copy(query = this.query?.copy(sending = false)) }
                    host.report(sent.error)
                }
            }
        }
    }

    // -- plumbing -----------------------------------------------------------------

    /** The open thread's `query_chat` rows on this page's key, and only those. */
    private fun readQuery(batchId: String) {
        val key = host.state.destination.batchBadgeKey ?: return
        host.readEntity(key, batchId, CashBadges.KIND_QUERY)
    }

    private fun mayPost(state: CashUiState, batch: ClaimBatch, signOff: Boolean): Boolean = when {
        signOff -> state.viewer.canSeeSignOff
        !state.destination.isPostLedger || !state.viewer.isAccountant -> false
        !CashRules.canOpenPostRow(state.viewer, batch) -> false
        else -> CashRules.canPost(state.viewer, state.panelClaims)
    }

    /** The open batch's fetched receipts, or null — with the reason said — when they are not in yet. */
    private fun readyClaims(batchId: String) =
        host.state.panel?.takeIf { it.batchId == batchId }?.claims
            ?: null.also { host.refuse(str(S.desktop_ce_receipts_still_loading)) }

    /** Null (every receipt) or the ticked subset; refuses when none is ticked. */
    private fun chosenClaims(batchId: String): List<String>? {
        val claims = readyClaims(batchId) ?: return null
        val chosen = host.state.panel?.selectedClaimIds ?: claims.map { it.id }.toSet()
        if (chosen.isEmpty()) {
            host.refuse(str(S.desktop_ce_tick_a_receipt))
            return null
        }
        return if (chosen.size == claims.size) null else claims.map { it.id }.filter { it in chosen }
    }

    private fun lockedMessage(): String = str(S.desktop_ce_batch_in_locked_period, host.state.lockedThrough.orEmpty())

    private fun CashUiState.closed(): CashUiState = copy(selectedBatchId = null, panel = null)

    private fun editPanel(batchId: String? = null, change: BatchPanel.() -> BatchPanel) = host.update {
        val open = panel?.takeIf { batchId == null || it.batchId == batchId } ?: return@update this
        copy(panel = open.change())
    }
}

/** The two Post & Ledger pages. */
internal val CashDestination.isPostLedger: Boolean
    get() = this == CashDestination.PostLedger || this == CashDestination.OutOfPocketPost

/** The two senior sign-off pages. */
internal val CashDestination.isSignOff: Boolean
    get() = this == CashDestination.PettyCashSignOff || this == CashDestination.OutOfPocketSignOff
