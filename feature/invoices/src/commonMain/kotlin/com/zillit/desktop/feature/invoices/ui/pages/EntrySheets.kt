// The sheets the coding screen and the entry queue raise: Quick Entry, a query thread, the history.
package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.EntryLedger
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.QueryView
import com.zillit.desktop.feature.invoices.ui.QuickEntryDraft

/**
 * Quick Entry — the web's floating panel on Invoice Entry: a reference, a
 * vendor, a nominal, a cost centre, a net and a tax, straight to ready-to-pay.
 */
@Composable
internal fun QuickEntrySheet(state: InvoicesUiState, draft: QuickEntryDraft, onEvent: (InvoicesEvent) -> Unit) {
    val edit: (QuickEntryDraft) -> Unit = { onEvent(EntryEvent.EditQuick(it)) }
    ZillitDialogShell(
        title = str(S.desktop_br_quick_entry),
        subtitle = str(S.desktop_inv_quick_entry_hint),
        visible = true,
        onDismiss = { if (!draft.busy) onEvent(EntryEvent.CancelQuick) },
        icon = ZillitIcons.Ledger,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(EntryEvent.CancelQuick) },
                variant = ButtonVariant.Tertiary,
                enabled = !draft.busy,
            )
            ZillitButton(
                text = str(S.ah_post_btn),
                onClick = { onEvent(EntryEvent.PostQuick) },
                enabled = draft.isReady && draft.netValue != null && !draft.busy,
                loading = draft.busy,
            )
        },
    ) {
        FieldRow {
            ZillitTextField(
                value = draft.reference,
                onValueChange = { edit(draft.copy(reference = it)) },
                label = str(S.ah_run_detail_col_invoice),
                placeholder = "INV-001",
                enabled = !draft.busy,
                modifier = Modifier.weight(1f),
            )
            LabelledPicker(
                label = str(S.ah_lbl_vendor),
                value = draft.vendorId,
                options = listOf("") + state.vendors.values.sortedBy { it.name.lowercase() }.map { it.id },
                text = { id -> state.vendors[id]?.name ?: str(S.desktop_inv_select_vendor) },
                enabled = !draft.busy,
            ) { edit(draft.copy(vendorId = it)) }
        }
        FieldRow {
            ZillitTextField(
                value = draft.nominal,
                onValueChange = { edit(draft.copy(nominal = it)) },
                label = str(S.dm_rule_nominal),
                enabled = !draft.busy,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.costCentre,
                onValueChange = { edit(draft.copy(costCentre = it)) },
                label = str(S.desktop_cost_centre),
                enabled = !draft.busy,
                modifier = Modifier.weight(1f),
            )
        }
        QuickAmounts(state, draft, edit)
    }
}

@Composable
private fun QuickAmounts(state: InvoicesUiState, draft: QuickEntryDraft, edit: (QuickEntryDraft) -> Unit) {
    FieldRow {
        ZillitTextField(
            value = draft.net,
            onValueChange = { edit(draft.copy(net = it)) },
            label = str(S.desktop_net),
            placeholder = "0.00",
            enabled = !draft.busy,
            modifier = Modifier.weight(1f),
        )
        LabelledPicker(
            label = str(S.ah_lbl_vat),
            value = draft.taxType,
            options = listOf("") + state.taxTypes.filter { it.rate != null }.map { it.identifier },
            text = { id ->
                state.taxTypes.firstOrNull { it.identifier == id }?.optionLabel ?: str(S.desktop_inv_no_tax)
            },
            enabled = !draft.busy,
        ) { edit(draft.copy(taxType = it)) }
        ZillitDateField(
            value = draft.effectiveDate,
            onValueChange = { edit(draft.copy(effectiveDate = it)) },
            label = str(S.ah_lbl_eff_date),
            enabled = !draft.busy,
            errorText = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough)
                .takeIf { state.periodLock.isLocked(draft.effectiveDate) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * An invoice's query thread — the hub's `QueryPanel`: the messages, each with
 * who asked and when, mine on the right, and a box to send the next one.
 */
@Composable
internal fun QueryPanelSheet(state: InvoicesUiState, view: QueryView, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.ah_query_label),
        subtitle = view.subtitle,
        visible = true,
        onDismiss = { onEvent(QueryEvent.Close) },
        icon = ZillitIcons.Chat,
        width = QUERY_WIDTH,
        actions = {
            ZillitTextField(
                value = view.draft,
                onValueChange = { onEvent(QueryEvent.Draft(it)) },
                placeholder = str(S.type_a_message),
                enabled = !view.sending,
                onImeAction = { onEvent(QueryEvent.Send) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(QueryEvent.Send) },
                leadingIcon = ZillitIcons.Send,
                enabled = view.draft.isNotBlank() && !view.sending,
                loading = view.sending,
            )
        },
    ) {
        when {
            view.loading -> LoadingRow()
            view.thread.messages.isEmpty() -> MutedLine(str(S.ah_no_queries_yet))
            else -> view.thread.messages.forEach { message ->
                QueryBubble(
                    text = message.text,
                    who = state.userNames[message.by] ?: message.by.ifBlank { str(S.desktop_unknown) },
                    at = InvoiceFormat.dateTime(message.atMs),
                    mine = message.by == state.viewer.userId,
                )
            }
        }
    }
}

@Composable
private fun QueryBubble(text: String, who: String, at: String, mine: Boolean) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(text = who, style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        Row(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX)
                .background(if (mine) colors.accent else colors.surfaceSunken, ZillitTheme.shapes.large)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = text,
                style = ZillitTheme.typography.bodySmall,
                color = if (mine) colors.textOnAccent else colors.textPrimary,
            )
        }
        ZillitText(text = at, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
    }
}

/** The coding screen's history — the shared sheet, with names from the production's directory. */
@Composable
internal fun LedgerHistorySheet(state: InvoicesUiState, ledger: EntryLedger, onEvent: (InvoicesEvent) -> Unit) {
    HistorySheet(
        invoiceNumber = ledger.invoice.displayNumber,
        rows = ledger.history,
        loading = ledger.historyLoading,
        nameOf = { id -> state.userNames[id] ?: id.ifBlank { str(S.desktop_unknown) } },
        onClose = { onEvent(EntryEvent.HideHistory) },
    )
}

private val QUERY_WIDTH = 560.dp
private val BUBBLE_MAX = 420.dp
