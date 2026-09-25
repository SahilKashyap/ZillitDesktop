package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ProcessPageEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * The accountant's Approval Queue — the web's `ApprovalQueuePage`.
 *
 * Approve shows only on a row whose next tier names this viewer; Override
 * only where that fails and the viewer may override receipts; otherwise the
 * row has nothing to do. Both act in one click. A row opens its detail, or —
 * while rows are ticked — ticks too. Rows dated in the closed period cannot
 * be ticked.
 */
@Suppress("LongMethod") // One page, top to bottom in the web's order.
@Composable
fun ApprovalQueuePage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.receipts.filter { it.matchesApproval(state.search) }
    val viewer = state.viewer
    val pages = state.processPages
    // `canAct`: an accountant bulk-approves only as an approver (`ApprovalQueuePage.jsx:80`).
    val canAct = viewer.isApprover
    val ticked = state.selection.intersect(rows.map { it.id }.toSet())

    Box(Modifier.fillMaxSize()) {
        FixedPage {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = str(S.desktop_ce_process_search_approval),
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitSectionCard(padded = false, modifier = Modifier.weight(1f)) {
                ZillitDataTable(
                    rows = rows,
                    columns = approvalColumns(state, rows, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    isSelected = { it.id in state.selection },
                    onRowClick = { row ->
                        if (state.selection.isNotEmpty()) {
                            onEvent(ProcessPageEvent.ToggleRow(row.id))
                        } else {
                            onEvent(ProcessPageEvent.OpenDetail(row.id))
                        }
                    },
                    emptyTitle = str(S.desktop_ce_process_no_approval_items),
                )
            }
            if ((canAct || viewer.canOverrideReceipt) && ticked.isNotEmpty()) {
                SelectionBar {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = str(S.dd_n_selected, ticked.size),
                            style = ZillitTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        if (canAct) {
                            ZillitButton(
                                text = str(S.desktop_approve_count, ticked.size),
                                onClick = { onEvent(ProcessPageEvent.BulkApproval(BulkAction.Approve)) },
                                size = ButtonSize.Small,
                                leadingIcon = ZillitIcons.Check,
                                loading = pages.bulkBusy,
                                enabled = !pages.bulkBusy,
                            )
                        }
                        if (viewer.canOverrideReceipt) {
                            ZillitButton(
                                text = str(S.desktop_card_override_count, ticked.size),
                                onClick = { onEvent(ProcessPageEvent.BulkApproval(BulkAction.Override)) },
                                size = ButtonSize.Small,
                                variant = ButtonVariant.Secondary,
                                leadingIcon = ZillitIcons.Siren,
                                loading = pages.bulkBusy,
                                enabled = !pages.bulkBusy,
                            )
                        }
                        ZillitButton(
                            text = str(S.ah_clear),
                            onClick = { onEvent(CardEvent.ClearSelection) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Close,
                        )
                    }
                }
            }
        }
        ApprovalDetailDialog(state, onEvent)
        RejectDialog(state, onEvent)
    }
}

/** Description or amount, as the web's search reads (`ApprovalQueuePage.jsx:211-217`). */
private fun CardReceipt.matchesApproval(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    val figure = if (amount == kotlin.math.floor(amount)) amount.toLong().toString() else amount.toString()
    return description.lowercase().contains(needle) || figure.contains(needle)
}

private fun CardUiState.isNextApprover(receipt: CardReceipt): Boolean =
    ApprovalTiers.isNextApprover(viewer.metadata.tierConfigs, receipt, viewer.userId)

@Suppress("MagicNumber", "LongMethod") // Column proportions; one table.
private fun approvalColumns(
    state: CardUiState,
    rows: List<CardReceipt>,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardReceipt>> {
    val lock = state.processPages.refs.lock
    return listOf(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(44.dp),
            headerContent = {
                val selectable = rows.filter { ProcessRules.approvalSelectable(it, lock) }.map { it.id }
                ZillitCheckbox(
                    checked = selectable.isNotEmpty() && state.selection.containsAll(selectable),
                    onCheckedChange = { onEvent(ProcessPageEvent.ToggleAllRows(rows.map { it.id })) },
                )
            },
        ) { row ->
            val locked = !ProcessRules.approvalSelectable(row, lock)
            val box = @Composable {
                ZillitCheckbox(
                    checked = row.id in state.selection,
                    onCheckedChange = { onEvent(ProcessPageEvent.ToggleRow(row.id)) },
                    enabled = !locked,
                )
            }
            if (locked) ZillitTooltip(str(S.desktop_inv_locked_period, lock.lockedThrough), box) else box()
        },
        TableColumn(str(S.date), ColumnWidth.Fixed(120.dp)) { DateCell(it) },
        TableColumn(str(S.ah_merchant), ColumnWidth.Weight(1.8f)) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = row.description.ifBlank { EM_DASH },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The row's own unread (`ApprovalQueuePage.jsx:313-316`).
                ZillitBadge(count = state.unreadRow("approval_queue", row.id))
                if (row.urgent) UrgentPill()
            }
        },
        TableColumn(str(S.desktop_card_card_holder), ColumnWidth.Weight(1.3f)) {
            HolderCell(state, it.holderId, it.holderName)
        },
        TableColumn(str(S.amount), ColumnWidth.Fixed(120.dp), numeric = true) { row ->
            ZillitText(
                text = money(row.amount, row.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        },
        TableColumn(str(S.code), ColumnWidth.Fixed(110.dp)) { CodeCell(it.nominalCode, null, television = false) },
        TableColumn(str(S.status), ColumnWidth.Fixed(160.dp)) {
            ZillitStatusPill(label = str(S.av_subtab_awaiting_approval), tone = StatusTone.Pending)
        },
        TableColumn("", ColumnWidth.Fixed(130.dp)) { row -> RowAction(state, row, onEvent) },
    )
}

/**
 * The row's one action: Approve for the next approver, Override for someone
 * who may override, a dash for everyone else (`ApprovalQueuePage.jsx:358-381`).
 */
@Composable
private fun RowAction(state: CardUiState, row: CardReceipt, onEvent: (CardEvent) -> Unit) {
    val acting = state.processPages.acting == row.id
    when {
        state.isNextApprover(row) -> ZillitButton(
            text = if (acting) "…" else str(S.approve),
            onClick = { onEvent(ProcessPageEvent.ApproveRow(row.id)) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Check,
            enabled = !acting,
        )

        state.viewer.canOverrideReceipt -> ZillitButton(
            text = if (acting) "…" else str(S.dm_nom_table_override),
            onClick = { onEvent(ProcessPageEvent.OverrideRow(row.id)) },
            size = ButtonSize.Small,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Siren,
            enabled = !acting,
        )

        else -> ZillitText(text = EM_DASH, color = ZillitTheme.colors.textMuted)
    }
}

/**
 * A row, opened: what the approver is signing off, read fresh from the
 * detail route, and the same decision as the row — Reject and Approve for the
 * next approver, Override for an override-holder, else nothing
 * (`ApprovalQueuePage.jsx:392-426`). No Query: this whole page is the queue.
 */
@Suppress("LongMethod") // One dialog, the web's order.
@Composable
private fun ApprovalDetailDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val receipt = state.processPages.detail ?: return
    val loading = state.processPages.detailLoading
    val person = state.people.firstOrNull { it.id == receipt.holderId }
    ZillitDialogShell(
        title = str(S.ah_receipt_details),
        subtitle = receipt.description.ifBlank { null },
        icon = ZillitIcons.Receipt,
        visible = true,
        width = DETAIL_WIDTH,
        onDismiss = { onEvent(ProcessPageEvent.CloseDetail) },
        actions = {
            Spacer(Modifier.weight(1f))
            DetailActions(state, receipt, loading, onEvent)
        },
    ) {
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md), Arrangement.Center) { ZillitSpinner() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            DetailFact(str(S.ah_merchant), receipt.description.ifBlank { EM_DASH }, Modifier.weight(1f))
            DetailFact(str(S.amount), money(receipt.amount, receipt.currency), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            DetailFact(str(S.date), date(receipt.date), Modifier.weight(1f))
            DetailFact(
                str(S.desktop_card_card_holder),
                listOfNotNull(
                    state.personName(receipt.holderId, receipt.holderName),
                    person?.designation?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            DetailFact(str(S.department), person?.department.orDash(), Modifier.weight(1f))
            DetailFact(str(S.ah_cost_code_label), receipt.nominalCode.orDash(), Modifier.weight(1f))
        }
        if (state.viewer.isTelevision && !receipt.episode.isNullOrBlank()) {
            DetailFact(str(S.episode), receipt.episode.orEmpty())
        }
        receipt.attachmentKey?.takeIf { it.isNotBlank() }?.let { key ->
            ZillitButton(
                text = str(S.desktop_card_view_receipt),
                onClick = { onEvent(CardEvent.ViewReceipt(key)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Eye,
            )
        } ?: ZillitText(
            text = str(S.desktop_ce_process_no_receipt),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ApprovalProgress(state, receipt)
    }
}

/** Reject and Approve for the next approver, Override for an override-holder, else a note. */
@Composable
private fun DetailActions(
    state: CardUiState,
    receipt: CardReceipt,
    loading: Boolean,
    onEvent: (CardEvent) -> Unit,
) {
    val idle = !loading && state.processPages.acting != receipt.id
    when {
        state.isNextApprover(receipt) -> {
            ZillitButton(
                text = str(S.reject),
                onClick = { onEvent(ProcessPageEvent.AskReject(receipt.id)) },
                variant = ButtonVariant.Danger,
                enabled = !loading,
            )
            ZillitButton(
                text = str(S.approve),
                onClick = { onEvent(ProcessPageEvent.ApproveRow(receipt.id)) },
                enabled = idle,
            )
        }

        state.viewer.canOverrideReceipt -> ZillitButton(
            text = str(S.dm_nom_table_override),
            onClick = { onEvent(ProcessPageEvent.OverrideRow(receipt.id)) },
            leadingIcon = ZillitIcons.Siren,
            enabled = idle,
        )

        else -> ZillitText(
            text = str(S.desktop_ce_process_no_actions),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** Who has signed so far, tier by tier. */
@Composable
private fun ApprovalProgress(state: CardUiState, receipt: CardReceipt) {
    if (receipt.approvals.isEmpty()) return
    FieldGroupLabel(str(S.desktop_po_approval_progress))
    receipt.approvals.forEach { approval ->
        val approver = state.people.firstOrNull { it.id == approval.userId }
        ZillitText(
            text = listOfNotNull(
                "✓ " + state.personName(approval.userId),
                approver?.designation?.takeIf { it.isNotBlank() }?.let { "($it)" },
                str(S.desktop_level_n, approval.tierNumber),
            ).joinToString(" "),
            style = ZillitTheme.typography.bodySmall,
        )
    }
}

private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: EM_DASH

@Composable
private fun DetailFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(label)
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium)
    }
}

/** "Reject Receipt": the reason is required; the button says so while it goes. */
@Composable
private fun RejectDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.processPages.reject ?: return
    val sending = state.processPages.acting == draft.receipt.id
    ZillitDialogShell(
        title = str(S.desktop_ce_process_reject_receipt),
        icon = ZillitIcons.Warning,
        visible = true,
        width = REJECT_WIDTH,
        onDismiss = { onEvent(ProcessPageEvent.CancelReject) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (sending) str(S.ah_run_detail_btn_rejecting) else str(S.reject),
                onClick = { onEvent(ProcessPageEvent.ConfirmReject) },
                variant = ButtonVariant.Danger,
                enabled = draft.reason.isNotBlank() && !sending,
            )
        },
    ) {
        ZillitText(
            text = str(
                S.desktop_ce_process_reject_prompt,
                draft.receipt.description.ifBlank { str(S.desktop_ce_process_this_receipt) },
            ),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = draft.reason,
            onValueChange = { onEvent(ProcessPageEvent.EditRejectReason(it)) },
            label = str(S.reason) + " *",
            placeholder = str(S.reason_for_rejection),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val DETAIL_WIDTH = 720.dp
private val REJECT_WIDTH = 480.dp
