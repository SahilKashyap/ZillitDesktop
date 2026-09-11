package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.CostCentre
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState

/** Everything the module asks before it writes. */
@Composable
fun BankRecDialogs(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    ImportDialog(state, onEvent)
    DeletePeriodsDialog(state, onEvent)
    MatchDialog(state, onEvent)
    SignOffDialog(state, onEvent)
    ExceptionNoteDialog(state, onEvent)
    QuickAddDialog(state, onEvent)
    EscalateDialog(state, onEvent)
    FxPostDialog(state, onEvent)
    PostAllFxDialog(state, onEvent)
    PortalLinkDialog(state, onEvent)
    RevokeLinkDialog(state, onEvent)
}

@Composable
private fun ImportDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val import = state.import
    ZillitDialogShell(
        title = "Import a statement",
        subtitle = "The file is stored first, then read by the reconciliation.",
        icon = ZillitIcons.Upload,
        visible = import.open,
        onDismiss = { onEvent(BankRecEvent.DismissImport) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissImport) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Choose a file",
                onClick = { onEvent(BankRecEvent.ChooseStatement) },
                loading = import.uploading,
                leadingIcon = ZillitIcons.Paperclip,
            )
        },
    ) {
        ZillitText(text = "Bank account", style = ZillitTheme.typography.label)
        ZillitSelect(
            value = import.bankAccountId,
            options = state.bankAccounts.map { it.id }.ifEmpty { listOf("") },
            onSelect = { onEvent(BankRecEvent.EditImport(it, import.periodId)) },
            label = { id -> state.account(id)?.name ?: "No bank accounts" },
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitText(text = "Period", style = ZillitTheme.typography.label)
        ZillitSelect(
            value = import.periodId,
            options = listOf(ALL_PERIODS) + state.openPeriods.map { it.id },
            onSelect = { onEvent(BankRecEvent.EditImport(import.bankAccountId, it)) },
            label = { id ->
                if (id == ALL_PERIODS) "Open a period from the statement" else state.periodLabelFor(id)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitNotice(
            text = "A statement spanning several months opens a period for each. Importing into " +
                "a month already signed off opens a fresh period alongside it.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The most destructive act in the module.
 *
 * A period takes its transactions, exceptions, fraud alerts and FX variances
 * with it, and un-matches every ledger entry that referenced them.
 */
@Composable
private fun DeletePeriodsDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val periods = state.deleting
    ZillitDialogShell(
        title = if (periods.size == 1) "Delete this period?" else "Delete ${periods.size} periods?",
        icon = ZillitIcons.Warning,
        visible = periods.isNotEmpty(),
        onDismiss = { onEvent(BankRecEvent.DismissDeletePeriods) },
        actions = {
            ZillitButton(
                text = "Keep them",
                onClick = { onEvent(BankRecEvent.DismissDeletePeriods) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(BankRecEvent.ConfirmDeletePeriods) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = periods.joinToString(", ") { state.periodLabel(it) },
            style = ZillitTheme.typography.bodyMedium,
        )
        ZillitNotice(
            text = "Everything the reconciliation produced goes with them: transactions, " +
                "exceptions, fraud alerts and FX variances. Ledger entries they matched are " +
                "un-matched and go back to being unreconciled.",
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The match confirmation.
 *
 * Names both sides, because once reconciled they both read as matched and
 * nothing afterwards says they do not belong together.
 */
@Composable
private fun MatchDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val pending = state.workspace.pending
    val bankCurrency = state.currencyOf(state.periods.firstOrNull { it.id == state.workspace.periodId })
    ZillitDialogShell(
        title = "Reconcile these two?",
        icon = ZillitIcons.Tick,
        visible = pending != null,
        onDismiss = { onEvent(BankRecEvent.DismissMatch) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissMatch) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Reconcile",
                onClick = { onEvent(BankRecEvent.ConfirmMatch) },
                loading = state.workspace.matching,
            )
        },
    ) {
        if (pending == null) return@ZillitDialogShell
        Line(
            "Bank",
            pending.transaction.vendorName.ifBlank { pending.transaction.description },
            signedMoney(pending.transaction.amount, pending.transaction.currency ?: bankCurrency),
        )
        Line(
            "Ledger",
            pending.entry.title,
            signedMoney(pending.entry.amount, pending.entry.currency ?: state.projectCurrency),
        )
        if (pending.wasSuggested) {
            ZillitNotice(
                text = "This pairing was suggested by the matching rules with " +
                    "${pending.transaction.matchConfidence ?: 0}% confidence. Accepting it is " +
                    "still your decision.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ColumnScope.Line(label: String, title: String, amount: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = title.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = amount, style = ZillitTheme.typography.bodyMedium)
    }
}

/**
 * Closing the period.
 *
 * The note is required when anything is still outstanding: signing off over
 * unmatched lines is a judgement somebody will be asked about later.
 */
@Composable
private fun SignOffDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val workspace = state.workspace
    val outstanding = workspace.transactions.count { it.effectiveStatus.wire != "matched" }
    ZillitDialogShell(
        title = "Sign off this period?",
        subtitle = state.periodLabelFor(workspace.periodId),
        icon = ZillitIcons.Tick,
        visible = workspace.confirmingSignOff,
        onDismiss = { onEvent(BankRecEvent.DismissSignOff) },
        actions = {
            ZillitButton(
                text = "Not yet",
                onClick = { onEvent(BankRecEvent.DismissSignOff) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Sign off",
                onClick = { onEvent(BankRecEvent.ConfirmSignOff) },
                loading = workspace.signingOff,
                enabled = outstanding == 0 || workspace.signOffNote.isNotBlank(),
            )
        },
    ) {
        ZillitText(
            text = "Signing off marks this period's invoices paid, computes the closing balance " +
                "and locks the period.",
            style = ZillitTheme.typography.bodyMedium,
        )
        if (outstanding > 0) {
            ZillitNotice(
                text = "$outstanding line(s) are still unreconciled. Say why before signing off.",
                tone = StatusTone.Escalated,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitTextField(
            value = workspace.signOffNote,
            onValueChange = { onEvent(BankRecEvent.EditSignOffNote(it)) },
            label = if (outstanding > 0) "Sign-off note (required)" else "Sign-off note",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExceptionNoteDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val exceptions = state.exceptions
    val target = exceptions.noting
    ZillitDialogShell(
        title = "Mark ${exceptions.noteStatus.label.lowercase()}",
        subtitle = target?.title?.takeIf { it.isNotBlank() } ?: target?.type?.label,
        icon = ZillitIcons.Edit,
        visible = target != null,
        onDismiss = { onEvent(BankRecEvent.DismissExceptionNote) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissExceptionNote) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(BankRecEvent.SaveExceptionNote) },
            )
        },
    ) {
        ZillitTextField(
            value = exceptions.noteText,
            onValueChange = { onEvent(BankRecEvent.EditExceptionNote(it)) },
            label = "Note",
            helperText = "What you found, or why this is being left alone.",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Posting an exception to the ledger — the one action here that writes a journal. */
@Composable
private fun QuickAddDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val exceptions = state.exceptions
    val target = exceptions.posting
    val form = exceptions.form
    ZillitDialogShell(
        title = "Post to the ledger",
        subtitle = target?.title?.takeIf { it.isNotBlank() } ?: target?.type?.label,
        icon = ZillitIcons.Ledger,
        visible = target != null,
        onDismiss = { onEvent(BankRecEvent.DismissQuickAdd) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissQuickAdd) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Post",
                onClick = { onEvent(BankRecEvent.SaveQuickAdd) },
                loading = exceptions.saving,
                enabled = form.problem == null,
            )
        },
    ) {
        ZillitTextField(
            value = form.nominalCode,
            onValueChange = { onEvent(BankRecEvent.EditQuickAdd(form.copy(nominalCode = it))) },
            label = "Account code",
            placeholder = "7900",
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitText(text = "Cost centre", style = ZillitTheme.typography.label)
        ZillitSelect(
            value = form.costCentre,
            options = listOf("") + CostCentre.entries.map { it.code },
            onSelect = { onEvent(BankRecEvent.EditQuickAdd(form.copy(costCentre = it))) },
            label = { code ->
                CostCentre.entries.firstOrNull { it.code == code }?.label ?: "None"
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = form.description,
            onValueChange = { onEvent(BankRecEvent.EditQuickAdd(form.copy(description = it))) },
            label = "Description",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EscalateDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val alert = state.fraud.escalating
    ZillitDialogShell(
        title = "Escalate this alert?",
        subtitle = alert?.title,
        icon = ZillitIcons.Siren,
        visible = alert != null,
        onDismiss = { onEvent(BankRecEvent.DismissEscalate) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissEscalate) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Escalate",
                onClick = { onEvent(BankRecEvent.ConfirmEscalate) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = "Escalating records that a payment on this production may be fraudulent and " +
                "raises it beyond this screen. It stays in the audit trail against your name.",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FxPostDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val fx = state.fx
    val target = fx.posting
    ZillitDialogShell(
        title = "Post this variance",
        subtitle = target?.let { "${it.invoiceCurrency} · ${signedMoney(it.variance, state.projectCurrency)}" },
        icon = ZillitIcons.BarChart,
        visible = target != null,
        onDismiss = { onEvent(BankRecEvent.DismissFxPosting) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissFxPosting) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Post",
                onClick = { onEvent(BankRecEvent.ConfirmFxPosting) },
                loading = fx.saving,
                enabled = fx.nominalCode.isNotBlank(),
            )
        },
    ) {
        ZillitTextField(
            value = fx.nominalCode,
            onValueChange = { onEvent(BankRecEvent.EditFxPosting(it, fx.costCentre)) },
            label = "Nominal code",
            helperText = "Where the gain or loss lands.",
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitText(text = "Cost centre", style = ZillitTheme.typography.label)
        ZillitSelect(
            value = fx.costCentre,
            options = listOf("") + CostCentre.entries.map { it.code },
            onSelect = { onEvent(BankRecEvent.EditFxPosting(fx.nominalCode, it)) },
            label = { code -> CostCentre.entries.firstOrNull { it.code == code }?.label ?: "None" },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PostAllFxDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val fx = state.fx
    ZillitDialogShell(
        title = "Post every unposted variance?",
        subtitle = "${fx.unposted.size} in this period",
        icon = ZillitIcons.Warning,
        visible = fx.confirmingPostAll,
        onDismiss = { onEvent(BankRecEvent.DismissPostAllFx) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissPostAllFx) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Post them all",
                onClick = { onEvent(BankRecEvent.ConfirmPostAllFx) },
                variant = ButtonVariant.Danger,
                loading = fx.saving,
            )
        },
    ) {
        ZillitText(
            text = "Each one writes its own journal at the service's default nominal. Reversing " +
                "them afterwards is a journal each.",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RevokeLinkDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val link = state.portal.revoking
    ZillitDialogShell(
        title = "Revoke this link?",
        subtitle = link?.recipientName,
        icon = ZillitIcons.Warning,
        visible = link != null,
        onDismiss = { onEvent(BankRecEvent.DismissRevokeLink) },
        actions = {
            ZillitButton(
                text = "Keep it",
                onClick = { onEvent(BankRecEvent.DismissRevokeLink) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Revoke",
                onClick = { onEvent(BankRecEvent.ConfirmRevokeLink) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = "The page stops opening immediately, including for somebody who has already " +
                "been through the emailed code. The record of what was shared stays.",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}
