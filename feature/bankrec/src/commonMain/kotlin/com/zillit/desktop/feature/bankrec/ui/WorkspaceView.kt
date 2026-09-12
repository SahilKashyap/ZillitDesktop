package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRow
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.LedgerRow
import com.zillit.desktop.feature.bankrec.domain.PanelTotal
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceCounts
import com.zillit.desktop.feature.bankrec.domain.WorkspaceRows
import kotlin.math.roundToLong

/**
 * Everything the workspace draws, derived once from the state.
 *
 * Kept out of the composables so the sign-off dialog and the summary bar read
 * the same difference, and the filter pills the same counts, as the view
 * model's own checks do.
 */
data class WorkspaceView(
    val period: BankPeriod?,
    val account: BankAccountRef?,
    /** What every bank-side figure is in: the account's currency, else the project's. */
    val statementCurrency: String,
    /** What the difference and both sums are in — each side converted to it. */
    val projectCurrency: String,
    val bankRows: List<BankRow>,
    val ledgerRows: List<LedgerRow>,
    val visibleBank: List<BankRow>,
    val visibleLedger: List<LedgerRow>,
    val counts: WorkspaceCounts,
    val bankTotal: PanelTotal,
    val ledgerTotal: PanelTotal,
    /** Both sides in the project's currency, for the sign-off and the summary bar. */
    val bankSum: Double,
    val ledgerSum: Double,
    val difference: Double,
    /** Rows lit because they are linked to the selected one. */
    val linked: Set<String>,
    val openExceptions: Int,
) {
    val unmatchedLedger: List<LedgerRow> get() = ledgerRows.filter { it.status == TxnStatus.Unmatched }

    /** Anything that makes a sign-off one "with exceptions" — and so needs a note. */
    val hasIssues: Boolean
        get() = counts.fraud > 0 || counts.unmatched > 0 || counts.suggested > 0 || openExceptions > 0 ||
            difference != 0.0

    fun bankRow(id: String?): BankRow? = id?.let { wanted -> bankRows.firstOrNull { it.id == wanted } }

    fun ledgerRow(id: String?): LedgerRow? = id?.let { wanted -> ledgerRows.firstOrNull { it.id == wanted } }

    /** A ledger row by its record id or its own — suggestions name the record. */
    fun invoice(invoiceId: String): LedgerRow? =
        ledgerRows.firstOrNull { it.entry.entityId == invoiceId } ?: ledgerRow(invoiceId)
}

fun BankRecUiState.workspaceView(): WorkspaceView {
    val period = workspacePeriod
    val account = account(period?.bankAccountId)
    val accountCurrency = account?.currencyCode?.takeIf { it.isNotBlank() }
    val statementCurrency = accountCurrency ?: projectCurrency
    val ws = workspace

    val bankRows = ws.transactions.map { WorkspaceRows.bankRow(it, ws.ledger, accountCurrency) }
    val ledgerRows = ws.ledger.map { WorkspaceRows.ledgerRow(it, lookups.departments) }

    val bankAmounts = bankRows.map { it.amount to (it.amountCurrency ?: projectCurrency) }
    val ledgerAmounts = ledgerRows.map { (it.amount ?: 0.0) to (it.entry.currency ?: projectCurrency) }
    val bankSum = rates.sumInDefault(bankAmounts).first
    val ledgerSum = rates.sumInDefault(ledgerAmounts).first

    return WorkspaceView(
        period = period,
        account = account,
        statementCurrency = statementCurrency,
        projectCurrency = projectCurrency,
        bankRows = bankRows,
        ledgerRows = ledgerRows,
        visibleBank = bankRows.filter { ws.filter.accepts(it.status) },
        visibleLedger = ledgerRows.filter { ws.filter.accepts(it.status) },
        counts = WorkspaceRows.counts(bankRows, ledgerRows),
        bankTotal = WorkspaceRows.panelTotal(
            bankRows.map { it.amount to it.amountCurrency },
            statementCurrency,
            rates,
        ),
        ledgerTotal = WorkspaceRows.panelTotal(
            ledgerRows.map { (it.amount ?: 0.0) to it.entry.currency },
            projectCurrency,
            rates,
        ),
        bankSum = bankSum,
        ledgerSum = ledgerSum,
        difference = ((bankSum - ledgerSum) * CENTS).roundToLong() / CENTS.toDouble(),
        linked = WorkspaceRows.linked(ws.selectedId, bankRows, ledgerRows),
        // This period's, not every period's: a note demanded by an exception
        // in another month is a note nobody can write sensibly.
        openExceptions = exceptions.count { it.periodId == ws.periodId && it.status == ExceptionStatus.Open },
    )
}

private const val CENTS = 100
