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
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceLabels
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.EntryLedger
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.QueryView
import com.zillit.desktop.feature.invoices.ui.QuickEntryDraft

/**
 * Quick Entry — the web's floating panel on Invoice Entry: a reference, a
 * vendor (picked, or typed and created on Post), a nominal off the chart, a
 * cost centre, a net, a tax, an effective date and tags, straight to
 * ready-to-pay (`EntryPage.jsx:640-720`).
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
            QuickVendor(state, draft, edit, Modifier.weight(1f))
        }
        FieldRow {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                FieldLabel(str(S.dm_rule_nominal))
                EntryNominalField(
                    accounts = state.entryRefs.accounts,
                    chart = state.chart,
                    code = draft.nominal,
                    enabled = !draft.busy,
                    placeholder = str(S.desktop_pc_enter_code),
                ) { edit(draft.copy(nominal = it)) }
            }
            ZillitTextField(
                value = draft.costCentre,
                onValueChange = { edit(draft.copy(costCentre = it)) },
                label = str(S.desktop_cost_centre),
                enabled = !draft.busy,
                modifier = Modifier.weight(1f),
            )
        }
        QuickAmounts(state, draft, edit)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldLabel(str(S.drive_tags))
            EntryTagsField(
                options = state.entryRefs.assetTags,
                selected = draft.tags,
                enabled = !draft.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { edit(draft.copy(tags = it)) }
        }
    }
}

/**
 * The vendor — searched, or typed and offered as "Create …": a new one is
 * only a pending name until Post creates it (`usePendingVendor`).
 */
@Composable
private fun QuickVendor(
    state: InvoicesUiState,
    draft: QuickEntryDraft,
    edit: (QuickEntryDraft) -> Unit,
    modifier: Modifier,
) {
    val pending = draft.pendingVendorName?.let { PENDING_PREFIX + it }
    val options = state.vendors.values.sortedBy { it.name.lowercase() }.map { it.id } + listOfNotNull(pending)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldLabel(str(S.ah_lbl_vendor))
        ZillitSearchSelect(
            value = pending ?: draft.vendorId.ifBlank { null },
            options = options,
            onSelect = { id ->
                if (id == pending) return@ZillitSearchSelect
                edit(draft.copy(vendorId = id, pendingVendorName = null))
            },
            label = { id ->
                if (id == pending) {
                    str(S.desktop_inv_vendor_new, draft.pendingVendorName.orEmpty())
                } else {
                    state.vendors[id]?.name ?: id
                }
            },
            placeholder = str(S.desktop_inv_search_or_add_vendor),
            enabled = !draft.busy,
            searchText = { id ->
                val vendor = state.vendors[id]
                listOfNotNull(vendor?.name ?: draft.pendingVendorName, vendor?.email, vendor?.contactPerson)
                    .joinToString(" ")
            },
            onCreate = { name -> edit(draft.copy(vendorId = "", pendingVendorName = name)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
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
            // `min={effMin}` (`EntryPage.jsx:700`): the closed period cannot be picked.
            minDate = lockMinDate(state.periodLock),
            errorText = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough)
                .takeIf { draft.effectiveDate.length == DATE_LENGTH && state.periodLock.isLocked(draft.effectiveDate) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * An invoice's query thread — the hub's `QueryPanel`: the messages, each with
 * who asked (and their designation) and when, mine on the right, and a box
 * to send the next one. It re-reads itself while open when someone else
 * writes on it.
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
                    role = view.roles[message.by].orEmpty(),
                    at = InvoiceFormat.dateTime(message.atMs),
                    mine = message.by == state.viewer.userId,
                )
            }
        }
    }
}

@Composable
private fun QueryBubble(text: String, who: String, role: String, at: String, mine: Boolean) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = who, style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            if (role.isNotBlank()) {
                ZillitText(text = role, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
        }
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

/**
 * The coding screen's history — the shared sheet, read afresh each time it
 * opens. Who did it reads "Name (Designation)" when the team list knows
 * their role, and a system transition reads "System" (`HistoryPanel.jsx:49-58`).
 */
@Composable
internal fun LedgerHistorySheet(state: InvoicesUiState, ledger: EntryLedger, onEvent: (InvoicesEvent) -> Unit) {
    HistorySheet(
        invoiceNumber = ledger.invoice.displayNumber,
        rows = ledger.history,
        loading = ledger.historyLoading,
        nameOf = { id -> historyActor(state, id) },
        onClose = { onEvent(EntryEvent.HideHistory) },
    )
}

private fun historyActor(state: InvoicesUiState, id: String): String {
    if (id.isBlank() || id == SYSTEM_ACTOR) return str(S.desktop_language_system_short)
    val name = state.userNames[id] ?: return id
    val role = state.assignees.firstOrNull { it.id == id }?.role?.takeIf { it.isNotBlank() }
        ?: return name
    return str(S.desktop_inv_name_with_designation, name, InvoiceLabels.format(role))
}

/** The pending vendor's slot in the vendor list — the web's `pending-vendor:` id. */
private const val PENDING_PREFIX = "pending-vendor:"
private const val SYSTEM_ACTOR = "system"
private const val DATE_LENGTH = 10
private val QUERY_WIDTH = 560.dp
private val BUBBLE_MAX = 420.dp
