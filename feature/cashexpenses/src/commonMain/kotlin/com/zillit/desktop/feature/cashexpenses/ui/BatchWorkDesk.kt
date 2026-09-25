package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchEdits
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimField
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.LineExtras

/**
 * Editing the open batch's receipts and saving them whole.
 *
 * The web's batch view holds every receipt edited in memory — name, amount,
 * category, cost code, vendor, episode, split lines — and sends the lot on
 * Save, Verify, Send for Approval, Forward or Post. The same here: edits land
 * on [BatchPanel.claims], mark it [BatchPanel.dirty], and a refresh keeps
 * them until one of those saves.
 */
internal class BatchWorkDesk(private val host: CashHost) {

    fun handle(event: BatchEvent) {
        when (event) {
            is BatchEvent.EditClaim -> edit(event.claimId, event.field, event.value)
            is BatchEvent.OpenSplit -> openSplit(event.claimId)
            BatchEvent.ApplySplit -> applySplit()
            BatchEvent.AddTaxLine -> addTaxLine()
            is BatchEvent.Save -> save(event.batchId)
            // Routed by BatchDesk before they reach here.
            is BatchEvent.SendForApproval, is BatchEvent.SubmitForReview, is BatchEvent.ForwardCoded -> Unit
        }
    }

    private fun edit(claimId: String, field: ClaimField, value: String) {
        val state = host.state
        val batch = state.selectedBatch ?: return
        if (!state.mayEdit(batch)) return
        // Coding locks what the claimant entered (`lockClaimantFields`).
        val claimantField = field == ClaimField.Name || field == ClaimField.Amount || field == ClaimField.Category
        if (state.destination == CashDestination.CodingQueue && claimantField) return
        host.update {
            val open = panel?.takeIf { it.batchId == batch.id } ?: return@update this
            copy(
                panel = open.copy(
                    claims = open.claims?.map { if (it.id == claimId) BatchEdits.edit(it, field, value) else it },
                    dirty = true,
                ),
            )
        }
    }

    private fun openSplit(claimId: String) {
        val state = host.state
        val batch = state.selectedBatch ?: return
        if (!state.mayEdit(batch)) return host.noRights()
        val claim = state.panelClaims.firstOrNull { it.id == claimId } ?: return
        host.update {
            copy(
                coding = CodingDraft(
                    batchId = batch.id,
                    claimId = claimId,
                    receiptGross = claim.grossAmount,
                    currency = batch.currency,
                    lines = BatchEdits.seedLines(claim) { BatchEdits.newUuid() },
                ),
            )
        }
    }

    /** The split editor's lines onto their receipt, in memory — Save sends them. */
    private fun applySplit() {
        val state = host.state
        val draft = state.coding ?: return
        val batch = state.selectedBatch?.takeIf { it.id == draft.batchId } ?: return
        if (!state.mayEdit(batch)) return host.noRights()
        host.update {
            val open = panel?.takeIf { it.batchId == draft.batchId } ?: return@update copy(coding = null)
            copy(
                coding = null,
                panel = open.copy(
                    claims = open.claims?.map { claim ->
                        if (claim.id == draft.claimId) {
                            BatchEdits.withLines(claim, draft.lines) { BatchEdits.newUuid() }
                        } else {
                            claim
                        }
                    },
                    dirty = true,
                ),
            )
        }
    }

    /**
     * The receipt's reclaimable-tax line: a pure-tax row with its own nominal.
     *
     * The web derives it from the project's recoverable tax types; this
     * module has no tax-type list, so the line is added and typed by hand —
     * and a stored one is always kept, as the web keeps a persisted override.
     */
    private fun addTaxLine() = host.update {
        val draft = coding ?: return@update this
        if (draft.lines.any { it.extras.isTax }) return@update this
        copy(coding = draft.copy(lines = draft.lines + EditorLine(id = BatchEdits.newUuid(), extras = TAX_LINE)))
    }

    /**
     * Save / Save Progress / Save Draft (`PCPostLedgerPage.jsx:872-891`):
     * every receipt, and the ledger date except while coding. Only Post &
     * Ledger refuses a save whose coding does not reach the batch total;
     * coding and audit are legitimately mid-allocation.
     */
    @Suppress("ReturnCount") // One refusal per rule, in the web's order.
    fun save(batchId: String) {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == batchId } ?: return
        if (!state.mayWorkOn(batch)) return host.noRights()
        if (state.selectedLocked) return
        val panel = state.panel?.takeIf { it.batchId == batchId }
        val claims = panel?.claims ?: return host.refuse(str(S.desktop_ce_receipts_still_loading))
        if (state.destination.isPostLedger && CashRules.amountMismatch(claims, batch.totalGross)) {
            return host.refuse(str(S.desktop_pc_must_match_save))
        }
        val date = if (state.destination == CashDestination.CodingQueue) {
            null
        } else {
            CashDates.utcMillis(panel.effectiveDate)
        }
        host.act(
            str(S.desktop_pc_batch_saved),
            onSuccess = { copy(panel = this.panel?.copy(dirty = false)) },
        ) {
            host.repository.saveClaimsBatch(batchId, BatchEdits.forWire(claims, host.state::wrapNominal), date)
        }
    }

    private companion object {
        val TAX_LINE = LineExtras(isTax = true)
    }
}
