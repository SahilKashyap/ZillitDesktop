package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import kotlinx.coroutines.Job
import kotlin.time.Clock

/**
 * Cash Recon's edit view — the web's `PCCashReconPage` count.
 *
 * A period opens with the safe's opening balance, a month and a currency, and a
 * blank count. The book balance for that opening balance and month comes from
 * the server and is re-asked whenever either changes; the variance is what the
 * count and the reconciling items leave. A signed-off period is read-only.
 */
internal class ReconDesk(private val host: CashHost) {

    private var bookJob: Job? = null

    /** The New Reconciliation dialog's Start: creates the period and opens its count. */
    fun create(prompt: CashPrompt.NewReconciliation): Boolean {
        if (!host.state.viewer.isAccountant) {
            host.noRights()
            return true
        }
        val opening = prompt.openingBalance.trim().toDoubleOrNull()
        if (opening == null || opening <= 0) {
            host.refuse(str(S.desktop_ce_enter_opening_balance))
            return false
        }
        val draft = ReconDraft(
            id = null,
            currency = prompt.currency.trim().ifBlank { null },
            openingBalance = prompt.openingBalance.trim(),
            year = prompt.year,
            month = prompt.month,
            denominations = ReconDraft.seedDenominations(Clock.System.now().toEpochMilliseconds()),
        )
        host.work {
            host.update { copy(busy = true) }
            when (val created = host.repository.createReconciliation(draft)) {
                is ZillitResult.Success -> {
                    host.update {
                        copy(
                            busy = false,
                            notice = str(S.desktop_ce_reconciliation_started),
                            recon = draft.copy(
                                id = created.data.id,
                                status = created.data.status.ifBlank { draft.status },
                            ),
                        )
                    }
                    refreshBook()
                }

                is ZillitResult.Failure -> {
                    host.update { copy(busy = false) }
                    host.report(created.error)
                }
            }
        }
        return true
    }

    /** Opens a saved period — fetched in full, since the list may not carry the count. */
    fun open(id: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        val listed = host.state.reconciliations.firstOrNull { it.id == id }
        host.work {
            val recon = (host.repository.reconciliation(id) as? ZillitResult.Success)?.data ?: listed ?: return@work
            host.update { copy(recon = ReconDraft.of(recon, CashDates.recentMonths(1).first())) }
            if (!recon.isSignedOff) refreshBook()
        }
    }

    fun edit(draft: ReconDraft) {
        val before = host.state.recon ?: return
        if (before.isSignedOff) return
        host.update { copy(recon = draft) }
        // The book only depends on the opening balance and the month.
        if (draft.openingBalance != before.openingBalance || draft.year != before.year || draft.month != before.month) {
            refreshBook()
        }
    }

    fun close() {
        bookJob?.cancel()
        host.update { copy(recon = null) }
    }

    fun save() = write(str(S.desktop_ce_reconciliation_saved), closeAfter = false) { draft ->
        host.repository.updateReconciliation(draft).let { result ->
            when (result) {
                is ZillitResult.Success -> {
                    host.update { copy(recon = recon?.copy(status = result.data.status.ifBlank { draft.status })) }
                    ZillitResult.Success(Unit)
                }

                is ZillitResult.Failure -> result
            }
        }
    }

    /** Submit for Review is the non-senior's step; a senior signs off instead. */
    fun submit() {
        if (host.state.viewer.isSenior) return host.noRights()
        write(str(S.desktop_sent_for_review), closeAfter = false) { draft ->
            host.repository.submitReconciliationForReview(draft).also { result ->
                if (result is ZillitResult.Success) {
                    host.update { copy(recon = recon?.copy(status = ReconDraft.UNDER_REVIEW)) }
                }
            }
        }
    }

    /** Sign Off Period — a senior's, and final. */
    fun signOff() {
        if (!host.state.viewer.isSenior) return host.noRights()
        write(str(S.desktop_br_signed_off), closeAfter = true) { host.repository.signOffReconciliation(it) }
    }

    private fun write(success: String, closeAfter: Boolean, block: suspend (ReconDraft) -> ZillitResult<Unit>) {
        val draft = host.state.recon ?: return
        if (!host.state.viewer.isAccountant) return host.noRights()
        if (draft.id == null || draft.isSignedOff) return
        host.act(success, onSuccess = { if (closeAfter) copy(recon = null) else this }) { block(draft) }
    }

    private fun refreshBook() {
        val draft = host.state.recon ?: return
        bookJob?.cancel()
        host.update { copy(recon = recon?.copy(computedBook = null)) }
        if (draft.opening <= 0) return
        bookJob = host.work {
            val book = (host.repository.computeBookBalance(draft) as? ZillitResult.Success)?.data
            host.update {
                val open = recon ?: return@update this
                val same = open.id == draft.id && open.year == draft.year && open.month == draft.month &&
                    open.openingBalance == draft.openingBalance
                if (same) copy(recon = open.copy(computedBook = book)) else this
            }
        }
    }
}
