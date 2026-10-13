package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.QuickEntryType
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceRows
import kotlin.math.abs

/**
 * The drawer beside the workspace: add a line to the ledger and match it in
 * one step, or post a line's FX variance.
 *
 * "Add & Match" posts the line through its **exception** when it has one, else
 * by its own transaction id (a Suggested line has no exception): the service
 * resolves either on the same route, accepts the fraud alert on a fraud line,
 * and refuses a line that is already matched. So it needs only a line picked
 * with Quick Add, and an amount.
 */
internal class QuickEntryActions(private val vm: BankRecViewModel) {

    private val workspace: WorkspaceState get() = vm.ui.workspace

    private val entry: QuickEntryState get() = workspace.quickEntry

    private fun edit(reducer: QuickEntryState.() -> QuickEntryState) =
        vm.update { copy(workspace = workspace.copy(quickEntry = workspace.quickEntry.reducer())) }

    @Suppress("CyclomaticComplexMethod") // One branch per action; each delegates.
    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.SetQuickEntryType -> edit { copy(type = event.type) }
            is BankRecEvent.OpenQuickAdd -> openQuickAdd(event.transactionId)
            BankRecEvent.ClearQuickAdd -> edit { copy(transactionId = null, form = QuickAddForm()) }
            is BankRecEvent.EditQuickEntry -> edit { copy(form = event.form) }
            is BankRecEvent.EditQuickEntryFraud -> edit {
                copy(fraudReason = event.reason, fraudPriority = event.priority)
            }
            BankRecEvent.AddAndMatch -> addAndMatch()
            is BankRecEvent.OpenFxEntry -> openFx(event.transactionId)
            is BankRecEvent.EditFxEntry -> edit {
                copy(fxCurrency = event.currency, fxForeignAmount = event.foreignAmount, fxBankRate = event.bankRate)
            }

            BankRecEvent.PostWorkspaceFx -> postFx()
            else -> return false
        }
        return true
    }

    /**
     * Fills the general form from a bank line.
     *
     * The tile follows the line's exception type, else its wording. And the
     * drawer opens if it was closed: filling a form nobody can see is a click
     * that does nothing on screen, which the web shipped once and fixed.
     */
    private fun openQuickAdd(transactionId: String) {
        val txn = workspace.transactions.firstOrNull { it.id == transactionId } ?: return
        vm.update { copy(workspace = workspace.copy(showQuickEntry = true)) }
        edit {
            copy(
                type = WorkspaceRows.quickEntryTypeFor(txn),
                transactionId = txn.id,
                fx = null,
                fxPosted = false,
                form = QuickAddForm(
                    effectiveDate = BankRecFormat.isoDate(txn.transactionDateMillis),
                    description = txn.exceptionTitle.ifBlank { txn.displayName },
                    amount = txn.amount.takeIf { it != 0.0 }?.let { amountText(abs(it)) }.orEmpty(),
                    // The shared nominal and cost centre start clean for a new line.
                    nominal = "",
                    costCentre = "",
                ),
            )
        }
    }

    private fun openFx(transactionId: String) {
        val fx = workspace.transactions.firstOrNull { it.id == transactionId }?.fx ?: return
        vm.update { copy(workspace = workspace.copy(showQuickEntry = true)) }
        edit {
            copy(
                type = QuickEntryType.FxPayment,
                transactionId = null,
                fx = fx,
                fxPosted = false,
                fxCurrency = fx.currency.ifBlank { "EUR" },
                fxForeignAmount = fx.foreignAmount.takeIf { it != 0.0 }?.let(::amountText).orEmpty(),
                fxBudgetRate = fx.budgetRate.takeIf { it != 0.0 }?.toString().orEmpty(),
                fxBankRate = fx.bankRate.takeIf { it != 0.0 }?.toString().orEmpty(),
            )
        }
    }

    private fun addAndMatch() {
        val txn = workspace.transactions.firstOrNull { it.id == entry.transactionId } ?: return
        val form = entry.form
        if (entry.adding) return
        val postId = txn.exceptionId.ifBlank { txn.id }
        val refusal = lockProblem(form.effectiveDate, vm.ui.lookups.lockedThrough)
            ?: str(S.desktop_br_enter_amount).takeIf { form.amountValue == null }
        if (refusal != null) return vm.refuse(refusal)
        edit { copy(adding = true) }
        vm.runResult({ vm.repo.quickAddException(postId, form, fromWorkspace = true) }, {
            vm.update {
                copy(
                    workspace = workspace.copy(
                        transactions = workspace.transactions.map {
                            if (it.id == txn.id) it.matchedByQuickAdd() else it
                        },
                        quickEntry = workspace.quickEntry.copy(
                            adding = false,
                            transactionId = null,
                            form = QuickAddForm(),
                        ),
                    ),
                )
            }
            vm.notify(str(S.desktop_br_added_and_matched))
            vm.workspaceActions.refresh()
            vm.loadPeriods()
            vm.loadExceptions()
        }, { error ->
            edit { copy(adding = false) }
            vm.report(error)
        })
    }

    /**
     * Posts the variance the drawer was opened on.
     *
     * At the shared nominal, else the FX default, and with no rates in the body
     * — the web's drawer posts exactly that; the FX tab's dialog is where rates
     * are supplied.
     */
    private fun postFx() {
        val fx = entry.fx ?: return
        val varianceId = fx.varianceId.ifBlank { return }
        if (entry.fxPosting || fx.isPosted) return
        val posting = FxPosting(
            nominalCode = entry.form.nominal.ifBlank { FxPosting.DEFAULT_NOMINAL },
            costCentre = entry.form.costCentre,
        )
        edit { copy(fxPosting = true) }
        vm.runResult({ vm.repo.postFxVariance(varianceId, posting) }, {
            val posted = fx.copy(varianceStatus = "posted")
            vm.update {
                copy(
                    workspace = workspace.copy(
                        transactions = workspace.transactions.map { txn ->
                            if (txn.fx?.varianceId == varianceId) txn.copy(fx = posted) else txn
                        },
                        quickEntry = workspace.quickEntry.copy(fxPosting = false, fxPosted = true, fx = posted),
                    ),
                )
            }
            vm.after(POSTED_MILLIS) { edit { copy(fxPosted = false) } }
            vm.loadFxVariances()
        }, { error ->
            edit { copy(fxPosting = false) }
            vm.report(error)
        })
    }

    private companion object {
        const val POSTED_MILLIS = 1_500L
    }
}

/**
 * The line as it reads once a quick add has matched it. The service accepts a
 * fraud alert on the line too, so the red bar goes with the suggestion.
 */
private fun BankTransaction.matchedByQuickAdd(): BankTransaction = copy(
    status = TxnStatus.Matched,
    matchConfidence = null,
    fraudStatus = if (fraudType != null) FraudStatus.Accepted else fraudStatus,
)

/** An amount as a form field holds it: `1234.5`, never `1.2345E3`. */
internal fun amountText(value: Double): String {
    val cents = kotlin.math.round(value * CENTS).toLong()
    val whole = cents / CENTS
    val fraction = abs(cents % CENTS)
    return if (fraction == 0L) "$whole.00" else "$whole.${fraction.toString().padStart(2, '0')}"
}

/**
 * Why an effective date cannot be used, or null when it can.
 *
 * On or before the cost report's lock is refused — the server refuses it too,
 * and the web caps its date picker at the day after. Both are `YYYY-MM-DD`, so
 * they compare as text.
 */
internal fun lockProblem(effectiveDate: String, lockedThrough: String?): String? {
    val locked = lockedThrough?.takeIf { it.isNotBlank() } ?: return null
    val date = effectiveDate.trim().ifBlank { return null }
    return if (date <= locked) str(S.desktop_br_effective_after_lock, locked) else null
}

private const val CENTS = 100L
