package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * What the view model lends its collaborators.
 *
 * The desks — batches, floats, reconciliation, settings, exports — each own
 * one area's handlers and their rights checks, so the view model stays a
 * dispatcher and no single class carries the whole module. They reach state
 * and effects only through this.
 */
internal interface CashHost {
    val state: CashUiState
    val repository: CashRepository

    fun update(reducer: CashUiState.() -> CashUiState)

    /** A refusal the person can act on, shown as a toast. */
    fun refuse(message: String)

    fun report(error: ZillitError)

    fun work(block: suspend CoroutineScope.() -> Unit): Job

    /**
     * Runs a mutation, then reloads the page it happened on — see the view
     * model's own `act`. [onSuccess] applies only once the server has said yes.
     */
    fun act(
        success: String,
        onSuccess: CashUiState.() -> CashUiState = { this },
        block: suspend () -> ZillitResult<Unit>,
    ): Job

    /** Whether the host can save a file — the exports need it. */
    val files: CashFiles?

    /** The refusal every rights check shares. */
    fun noRights() = refuse(str(S.desktop_po_no_rights_on_project))
}

/**
 * Saves an export where the person will find it, and opens it.
 *
 * A host seam — this module has no file layer — as Bank Reconciliation's
 * `BankRecFiles` is. Absent, an export says it cannot save rather than
 * vanishing.
 */
fun interface CashFiles {
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
