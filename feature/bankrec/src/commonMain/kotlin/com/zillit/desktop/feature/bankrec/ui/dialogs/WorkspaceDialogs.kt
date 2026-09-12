package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankRow
import com.zillit.desktop.feature.bankrec.domain.LedgerRow
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceView
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrBanner
import com.zillit.desktop.feature.bankrec.ui.components.BrFieldLabel
import com.zillit.desktop.feature.bankrec.ui.components.BrNoteField
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.workspaceView

/**
 * Accepting the engine's suggested match — confirmed, because a wrong match
 * is invisible once made. A flagged line says so first, and says that
 * accepting it is itself the review that goes on the record.
 */
@Suppress("LongMethod") // Both sides and the fraud warning belong on one card.
@Composable
internal fun ConfirmMatchDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val proposal = rememberLast(state.workspace.proposal) ?: return
    val view = state.workspaceView()
    val bank = view.bankRow(proposal.transactionId)
    val invoice = view.invoice(proposal.invoiceId)
    val txn = bank?.txn
    val fraud = txn?.fraudType != null
    val accepting = state.workspace.accepting
    ZillitDialogShell(
        title = if (fraud) "Fraud Alert — Confirm Match" else "Confirm Match",
        onDismiss = { if (!accepting) onEvent(BankRecEvent.DismissMatch) },
        visible = state.workspace.proposal != null,
        icon = if (fraud) ZillitIcons.Shield else ZillitIcons.Check,
        width = 560.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.DismissMatch) },
                variant = ButtonVariant.Tertiary,
                enabled = !accepting,
            )
            ZillitButton(
                text = when {
                    accepting -> "Matching…"
                    fraud -> "Accept & Match"
                    else -> "Confirm Match"
                },
                onClick = { onEvent(BankRecEvent.ConfirmMatch) },
                variant = if (fraud) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = accepting,
                enabled = bank != null,
            )
        },
    ) {
        val fraudType = txn?.fraudType
        if (txn != null && fraudType != null) {
            BrBanner(
                tone = BrTone.Red,
                icon = ZillitIcons.Shield,
                title = "${fraudType.shortLabel} — Risk Score: ${txn.fraudScore ?: BankRecFormat.DASH}",
                message = "This transaction has been flagged as a potential fraud. Accepting this match will be " +
                    "recorded in the audit logs of this record.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BankSide(bank, view, tone = null)
            if (invoice != null) {
                LedgerSide(invoice, view, heading = "Invoice", tone = null)
            } else {
                SideCard("Invoice", "Invoice details unavailable", "", "", null)
            }
        }
        if (!fraud) {
            ZillitText(
                "This will match the bank transaction to the invoice and mark both as reconciled.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * Choosing the ledger entry by hand: the unmatched entries first, then the
 * same two-sided confirmation the suggestion gets, with a way back to the list.
 */
@Composable
internal fun ManualMatchDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val transactionId = rememberLast(state.workspace.manualMatchId) ?: return
    val view = state.workspaceView()
    val bank = view.bankRow(transactionId)
    val chosen = view.ledgerRow(state.workspace.manualMatchEntryId)
    val matching = state.workspace.manualMatching
    ZillitDialogShell(
        title = if (chosen == null) "Manual Match" else "Confirm Manual Match",
        onDismiss = {
            when {
                matching -> Unit
                chosen != null -> onEvent(BankRecEvent.PickManualMatch(null))
                else -> onEvent(BankRecEvent.CloseManualMatch)
            }
        },
        visible = state.workspace.manualMatchId != null,
        icon = ZillitIcons.Ledger,
        width = 560.dp,
        actions = if (chosen == null) {
            null
        } else {
            {
                ZillitButton(
                    text = "Back",
                    onClick = { onEvent(BankRecEvent.PickManualMatch(null)) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.ChevronLeft,
                    enabled = !matching,
                )
                ZillitButton(
                    text = if (matching) "Matching…" else "Confirm Match",
                    onClick = { onEvent(BankRecEvent.ConfirmManualMatch) },
                    loading = matching,
                )
            }
        },
    ) {
        if (chosen == null) {
            PickEntry(bank, view, onEvent)
        } else {
            ZillitText(
                "You are about to match this bank transaction to the selected ledger entry. This action will mark " +
                    "both as matched.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BankSide(bank, view, tone = BrTone.Blue)
                LedgerSide(chosen, view, heading = "Ledger Entry", tone = BrTone.Green)
            }
        }
    }
}

@Composable
private fun PickEntry(bank: BankRow?, view: WorkspaceView, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    if (bank != null) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(BrTone.Blue.bg()).border(1.dp, BrTone.Blue.edge(), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ZillitText("MATCHING TRANSACTION", style = eyebrow(10.sp), color = BrTone.Blue.fg())
            ZillitText(
                bank.title,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            ZillitText(
                listOf(bank.reference, signed(bank.amount, bank.amountCurrency ?: view.statementCurrency))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = mono(11.5.sp),
                color = colors.textSecondary,
            )
        }
    }
    val entries = view.unmatchedLedger
    BrFieldLabel("Select a ledger entry to match (${entries.size})")
    if (entries.isEmpty()) {
        ZillitText(
            "No unmatched ledger entries available",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape)) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) ZillitDivider()
            EntryRow(entry, view) { onEvent(BankRecEvent.PickManualMatch(entry.id)) }
        }
    }
}

@Composable
private fun EntryRow(entry: LedgerRow, view: WorkspaceView, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            BankRecFormat.statementDay(entry.entry.displayDateMillis),
            style = mono(10.5.sp),
            color = colors.textMuted,
            modifier = Modifier.width(52.dp),
        )
        Column(Modifier.weight(1f)) {
            ZillitText(
                entry.title,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (entry.reference.isNotBlank()) {
                ZillitText(entry.reference, style = mono(10.5.sp), color = colors.textMuted, maxLines = 1)
            }
        }
        ZillitText(
            entry.amount?.let { signed(it, entry.entry.currency ?: view.projectCurrency) } ?: BankRecFormat.DASH,
            style = mono(12.5.sp),
        )
    }
}

@Composable
private fun RowScope.BankSide(bank: BankRow?, view: WorkspaceView, tone: BrTone?) {
    if (bank == null) {
        SideCard("Bank Transaction", "Transaction details unavailable", "", "", null, tone)
        return
    }
    SideCard(
        heading = "Bank Transaction",
        title = bank.title,
        reference = bank.reference,
        amount = signed(bank.amount, bank.amountCurrency ?: view.statementCurrency),
        date = BankRecFormat.statementDay(bank.txn.transactionDateMillis),
        tone = tone,
    )
}

@Composable
private fun RowScope.LedgerSide(
    entry: LedgerRow,
    view: WorkspaceView,
    heading: String,
    tone: BrTone?,
) {
    SideCard(
        heading = heading,
        title = entry.title,
        reference = entry.reference,
        amount = entry.amount?.let { signed(it, entry.entry.currency ?: view.projectCurrency) } ?: BankRecFormat.DASH,
        date = BankRecFormat.statementDay(entry.entry.displayDateMillis),
        tone = tone,
    )
}

private fun signed(amount: Double, currency: String): String = BankRecFormat.signedMoney(amount, currency)

/**
 * Closing the period — with every reason not to said first.
 *
 * A clean period signs off with an optional note. One with anything still
 * outstanding needs the note, and the button says what is being done:
 * "Sign Off with Exceptions".
 */
@Composable
internal fun SignOffDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val dialog = rememberLast(state.workspace.signOff) ?: return
    val view = state.workspaceView()
    val issues = view.hasIssues
    val canSubmit = !dialog.submitting && (!issues || dialog.note.isNotBlank())
    ZillitDialogShell(
        title = "Sign Off Reconciliation",
        subtitle = view.period?.let { BankRecFormat.fullPeriodLabel(it) },
        onDismiss = { if (!dialog.submitting) onEvent(BankRecEvent.CloseSignOff) },
        visible = state.workspace.signOff != null,
        icon = ZillitIcons.Check,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.CloseSignOff) },
                variant = ButtonVariant.Tertiary,
                enabled = !dialog.submitting,
            )
            ZillitButton(
                text = when {
                    dialog.submitting -> "Signing Off…"
                    issues -> "Sign Off with Exceptions"
                    else -> "Sign Off"
                },
                onClick = { onEvent(BankRecEvent.ConfirmSignOff) },
                variant = if (issues) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = dialog.submitting,
                enabled = canSubmit,
            )
        },
    ) {
        SignOffWarnings(view)
        DifferenceBox(view)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrFieldLabel("Sign-off note")
                if (issues) {
                    ZillitText("(required)", style = eyebrow(), color = ZillitTheme.colors.danger)
                }
            }
            BrNoteField(
                value = dialog.note,
                onValueChange = { onEvent(BankRecEvent.EditSignOffNote(it)) },
                placeholder = if (issues) {
                    "Explain why you are signing off with exceptions…"
                } else {
                    "Optional notes for this sign-off…"
                },
                enabled = !dialog.submitting,
            )
        }
    }
}

@Composable
private fun SignOffWarnings(view: WorkspaceView) {
    val counts = view.counts
    val unresolved = counts.unmatched + counts.suggested
    if (!view.hasIssues) {
        BrBanner(
            tone = BrTone.Green,
            icon = ZillitIcons.Check,
            title = "Ready to sign off",
            message = "All transactions matched, no outstanding items",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (counts.fraud > 0) {
        BrBanner(
            tone = BrTone.Red,
            icon = ZillitIcons.Shield,
            title = "${counts.fraud} fraud alert${plural(counts.fraud)} unresolved",
            message = "Active fraud flags require review before sign-off",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (unresolved > 0) {
        val parts = listOfNotNull(
            "${counts.unmatched} unmatched transaction${plural(counts.unmatched)}".takeIf { counts.unmatched > 0 },
            "${counts.suggested} pending suggestion${plural(counts.suggested)}".takeIf { counts.suggested > 0 },
        )
        BrBanner(
            tone = BrTone.Red,
            icon = ZillitIcons.Warning,
            title = "$unresolved item${plural(unresolved)} still unresolved",
            message = parts.joinToString(" and "),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (view.openExceptions > 0) {
        BrBanner(
            tone = BrTone.Amber,
            icon = BankRecIcons.Exclaim,
            title = "${view.openExceptions} open exception${plural(view.openExceptions)}",
            message = "Bank charges, payroll, or other items not yet posted",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DifferenceBox(view: WorkspaceView) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val code = view.projectCurrency
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ZillitText("UNRECONCILED DIFFERENCE", style = eyebrow(), color = colors.textMuted)
        ZillitText(
            BankRecFormat.money(view.difference, code),
            style = mono(24.sp, FontWeight.Bold),
            color = if (view.difference == 0.0) colors.success else colors.danger,
        )
        if (view.difference != 0.0) {
            ZillitText(
                "Bank total ${BankRecFormat.money(view.bankSum, code)} vs Zillit total " +
                    BankRecFormat.money(view.ledgerSum, code),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
