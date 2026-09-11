package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceFilter
import com.zillit.desktop.feature.bankrec.ui.shortDayLabel

/**
 * The reconciliation itself: the statement on the left, the ledger on the right.
 *
 * Matching is the only act here that is invisible afterwards — once two records
 * are reconciled, both read as matched and nothing says they do not belong
 * together. So a match is chosen on one side, proposed against the other, and
 * confirmed by name.
 */
@Composable
fun WorkspacePage(
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace

    Column(modifier) {
        Toolbar(state, onEvent)
        ZillitDivider()

        when {
            workspace.loading && workspace.transactions.isEmpty() -> ZillitSpinner()
            workspace.periodId.isBlank() -> ZillitEmptyState(
                title = "No period open",
                message = "Import a statement to start a reconciliation.",
                icon = ZillitIcons.Bank,
            )

            else -> Panels(state, onEvent, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun Toolbar(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val workspace = state.workspace
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitSelect(
                value = workspace.periodId,
                options = state.openPeriods.map { it.id }.ifEmpty { listOf(workspace.periodId) },
                onSelect = { onEvent(BankRecEvent.OpenPeriod(it)) },
                label = { id -> state.periodLabelFor(id) },
                modifier = Modifier.padding(end = ZillitTheme.spacing.xs),
            )
            ZillitStatusPill(
                label = "${workspace.matchedCount} of ${workspace.transactions.size} matched",
                tone = StatusTone.Done,
            )
            if (workspace.fraudCount > 0) {
                ZillitStatusPill(
                    label = "${workspace.fraudCount} flagged",
                    tone = StatusTone.Escalated,
                    dot = true,
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Run matching again",
                onClick = { onEvent(BankRecEvent.RerunAutoMatch) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = workspace.rerunning,
            )
            ZillitButton(
                text = "Sign off period",
                onClick = { onEvent(BankRecEvent.AskSignOff) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Tick,
                enabled = workspace.periodId.isNotBlank(),
            )
        }

        ZillitTabStrip(
            tabs = WorkspaceFilter.entries.map { ZillitTab(it.slug, it.label) },
            activeId = workspace.filter.slug,
            onSelect = { slug ->
                WorkspaceFilter.entries.firstOrNull { it.slug == slug }
                    ?.let { onEvent(BankRecEvent.FilterWorkspace(it)) }
            },
        )
    }
}

@Composable
private fun Panels(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit, modifier: Modifier) {
    val workspace = state.workspace
    val bankCurrency = state.currencyOf(state.periods.firstOrNull { it.id == workspace.periodId })

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ZillitSectionLabel(
                text = "Bank statement · ${workspace.visible.size} line(s)",
                modifier = Modifier.padding(ZillitTheme.spacing.md),
            )
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                if (workspace.visible.isEmpty()) {
                    ZillitEmptyState(
                        title = "Nothing under this filter",
                        message = "Every line in this period is somewhere else.",
                        icon = ZillitIcons.Filter,
                    )
                }
                workspace.visible.forEach { txn ->
                    BankLine(txn, bankCurrency, workspace.selected?.id == txn.id, onEvent)
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            LedgerHeader(state)
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                LedgerBody(state, onEvent)
            }
        }
    }
}

@Composable
private fun LedgerHeader(state: BankRecUiState) {
    val selected = state.workspace.selected
    ZillitSectionLabel(
        text = if (selected == null) {
            "Zillit ledger · ${state.workspace.unmatchedLedger.size} unmatched"
        } else {
            "Match ${selected.vendorName.ifBlank { "this line" }} to…"
        },
        modifier = Modifier.padding(ZillitTheme.spacing.md),
    )
}

@Composable
private fun ColumnScope.LedgerBody(
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
) {
    val workspace = state.workspace
    val selected = workspace.selected
    val rows = if (selected == null) workspace.ledger else workspace.matchCandidates

    if (selected != null) {
        ZillitNotice(
            text = "Choose the ledger entry this payment answers. Nothing is reconciled until " +
                "you confirm it.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
            action = {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(BankRecEvent.SelectTransaction(null)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        )
    }

    if (rows.isEmpty()) {
        ZillitEmptyState(
            title = "Nothing to match against",
            message = "Every ledger entry for this period is already reconciled.",
            icon = ZillitIcons.Ledger,
        )
        return
    }

    rows.forEach { entry ->
        LedgerLine(entry, state.projectCurrency, selected, onEvent)
    }
}

@Composable
private fun BankLine(
    txn: BankTransaction,
    bankCurrency: String,
    selected: Boolean,
    onEvent: (BankRecEvent) -> Unit,
) {
    val status = txn.effectiveStatus
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitText(
                text = shortDayLabel(txn.transactionDateMillis),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = txn.vendorName.ifBlank { "—" },
                    style = ZillitTheme.typography.bodyMedium,
                )
                ZillitText(
                    text = txn.reference.ifBlank { txn.description },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitText(
                // The line's own currency, never the project's: a euro
                // statement's figures are euros whatever the ledger is in.
                text = signedMoney(txn.amount, txn.currency ?: bankCurrency),
                style = ZillitTheme.typography.bodyMedium,
            )
            ZillitStatusPill(label = status.label, tone = status.tone(), dot = true)
        }

        Suggestion(txn, bankCurrency, selected, onEvent)
        ZillitDivider()
    }
}

@Composable
private fun ColumnScope.Suggestion(
    txn: BankTransaction,
    bankCurrency: String,
    selected: Boolean,
    onEvent: (BankRecEvent) -> Unit,
) {
    val fraud = txn.fraudType
    if (fraud != null && txn.hasActiveFraud) {
        ZillitNotice(
            text = fraud.explanation,
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Siren,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    txn.fx?.let { fx ->
        ZillitNotice(
            text = "${money(fx.foreignAmount, fx.currency)} at ${fx.bankRate} against a budget " +
                "rate of ${fx.budgetRate} — ${signedMoney(fx.gain, bankCurrency)}" +
                if (fx.isPosted) ", posted." else ", not yet posted.",
            tone = if (fx.isPosted) StatusTone.Done else StatusTone.InTransit,
            icon = ZillitIcons.BarChart,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (txn.effectiveStatus == TxnStatus.Matched) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        txn.matchConfidence?.takeIf { txn.matchedInvoiceIds.isNotEmpty() }?.let { confidence ->
            ZillitButton(
                text = "Accept suggestion · $confidence%",
                onClick = { onEvent(BankRecEvent.AcceptSuggestion(txn)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        ZillitButton(
            text = if (selected) "Choosing…" else "Match by hand",
            onClick = { onEvent(BankRecEvent.SelectTransaction(if (selected) null else txn)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun LedgerLine(
    entry: LedgerEntry,
    projectCurrency: String,
    selected: BankTransaction?,
    onEvent: (BankRecEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitText(
                text = shortDayLabel(entry.dateMillis),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            Column(Modifier.weight(1f)) {
                ZillitText(text = entry.title.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium)
                ZillitText(
                    text = entry.reference.ifBlank { entry.kind.wire },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitText(
                text = signedMoney(entry.amount, entry.currency ?: projectCurrency),
                style = ZillitTheme.typography.bodyMedium,
            )
            if (entry.isMatched) {
                ZillitStatusPill(label = "Matched", tone = StatusTone.Done)
            } else if (selected != null) {
                ZillitButton(
                    text = "Match",
                    onClick = { onEvent(BankRecEvent.ProposeMatch(selected, entry)) },
                    size = ButtonSize.Small,
                )
            }
        }
        ZillitDivider()
    }
}
