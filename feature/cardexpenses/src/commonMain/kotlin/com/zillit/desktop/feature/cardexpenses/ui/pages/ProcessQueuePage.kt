package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode
import com.zillit.desktop.feature.cardexpenses.ui.ProcessTab
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * Process Card Expenses — the web's `ProcessPage`: two queues on tabs, the
 * approved receipts to process and — for a senior — the Posting Review queue
 * of what was handed up.
 *
 * No search and no status filter, as on the web. A row this accountant may
 * not work (not theirs, not a senior) is dimmed and does not open; the rest
 * open the full-page editor.
 */
@Composable
fun ProcessQueuePage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val viewer = state.viewer
    val senior = viewer.isAccountant && viewer.isSenior
    val approved = state.receipts.filter { it.status == CardWorkflowStatus.Approved }
    val review = state.receipts.filter {
        it.status == CardWorkflowStatus.UnderReview || it.status == CardWorkflowStatus.Escalated
    }
    val tab = if (senior) state.processTab else ProcessTab.Processing

    FixedPage {
        ZillitTabStrip(
            tabs = buildList {
                // The count beside the label, as plain figures — the tab's own
                // chip is an unread badge, which these are not.
                val processing = "${str(S.desktop_card_processing_queue)}  ${approved.size}"
                add(ZillitTab(ProcessTab.Processing.name, processing))
                if (senior) {
                    val posting = "${str(S.desktop_card_posting_review_queue)}  ${review.size}"
                    add(ZillitTab(ProcessTab.Review.name, posting))
                }
            },
            activeId = tab.name,
            onSelect = { id ->
                ProcessTab.entries.firstOrNull { it.name == id }?.let { onEvent(CardEvent.ShowProcessTab(it)) }
            },
        )
        ZillitSectionCard(padded = false, modifier = Modifier.weight(1f)) {
            if (tab == ProcessTab.Processing) {
                ZillitDataTable(
                    rows = approved,
                    columns = processingColumns(state, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { row ->
                        if (ProcessRules.canOpen(viewer, row)) {
                            onEvent(CardEvent.OpenProcess(row.id, ProcessMode.Process))
                        }
                    },
                    emptyTitle = str(S.desktop_ce_process_no_processing),
                )
            } else {
                ZillitDataTable(
                    rows = review,
                    columns = reviewColumns(state, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { row -> onEvent(CardEvent.OpenProcess(row.id, ProcessMode.Process)) },
                    emptyTitle = str(S.desktop_ce_process_no_review),
                )
            }
        }
    }
}

/** A cell dimmed to 60% on a row this accountant cannot open (`ProcessPage.jsx:580`). */
@Composable
private fun Dim(open: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (open) 1f else DIMMED)) { content() }
}

@Suppress("MagicNumber") // Column proportions.
private fun sharedColumns(state: CardUiState, review: Boolean): List<TableColumn<CardReceipt>> {
    val viewer = state.viewer
    fun open(row: CardReceipt) = review || ProcessRules.canOpen(viewer, row)
    return listOf(
        TableColumn(str(S.date), ColumnWidth.Fixed(110.dp)) { row -> Dim(open(row)) { DateCell(row) } },
        TableColumn(str(S.ah_receipt_details), ColumnWidth.Weight(2.2f)) { row ->
            Dim(open(row)) {
                ReceiptDetailsCell(
                    state,
                    row,
                    lastFour = if (review) row.transactionCardLastFour else row.cardLastFour,
                    // Both tabs chip the row under process_queue (`ProcessPage.jsx:596`, `:808`).
                    unread = state.unreadRow("process_queue", row.id),
                )
            }
        },
        TableColumn(str(S.desktop_card_card_holder), ColumnWidth.Weight(1.2f)) { row ->
            Dim(open(row)) { HolderCell(state, row.holderId, row.holderName) }
        },
        TableColumn(str(S.code), ColumnWidth.Fixed(110.dp)) { row ->
            Dim(open(row)) { CodeCell(row.nominalCode, row.episode, viewer.isTelevision) }
        },
        TableColumn(str(S.amount), ColumnWidth.Fixed(120.dp), numeric = true) { row ->
            Dim(open(row)) { AmountCell(row) }
        },
    )
}

@Suppress("MagicNumber") // Column proportions.
private fun processingColumns(state: CardUiState, onEvent: (CardEvent) -> Unit): List<TableColumn<CardReceipt>> =
    sharedColumns(state, review = false) + listOf(
        TableColumn(str(S.assigned), ColumnWidth.Fixed(150.dp)) { row ->
            val open = ProcessRules.canOpen(state.viewer, row)
            Dim(open) { AssignedPill(state, row, open) }
        },
        TableColumn("", ColumnWidth.Fixed(110.dp)) { row ->
            if (ProcessRules.canOpen(state.viewer, row)) {
                ZillitButton(
                    text = str(S.ah_process),
                    onClick = { onEvent(CardEvent.OpenProcess(row.id, ProcessMode.Process)) },
                    size = ButtonSize.Small,
                )
            }
        },
    )

@Suppress("MagicNumber") // Column proportions.
private fun reviewColumns(state: CardUiState, onEvent: (CardEvent) -> Unit): List<TableColumn<CardReceipt>> =
    sharedColumns(state, review = true) + listOf(
        TableColumn(str(S.status), ColumnWidth.Fixed(150.dp)) { row ->
            if (row.status == CardWorkflowStatus.Escalated) {
                ZillitStatusPill(label = str(S.ah_escalated), tone = StatusTone.Escalated)
            } else {
                ZillitStatusPill(label = str(S.ah_under_review), tone = StatusTone.Progress)
            }
        },
        TableColumn("", ColumnWidth.Fixed(110.dp)) { row ->
            ZillitButton(
                text = str(S.av_review),
                onClick = { onEvent(CardEvent.OpenProcess(row.id, ProcessMode.Process)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
            )
        },
    )

/** "Me", the assignee's name, or "Unassigned" — with a lock where this viewer has no access. */
@Composable
private fun AssignedPill(state: CardUiState, row: CardReceipt, open: Boolean) {
    val assignee = row.assignedTo?.takeIf { it.isNotBlank() }
    val label = when {
        assignee == null -> str(S.unassigned)
        assignee == state.viewer.userId -> str(S.txt_me)
        else -> state.personName(assignee)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!open) ZillitIcon(ZillitIcons.Lock, tint = ZillitTheme.colors.textMuted, size = LOCK_ICON)
        if (assignee != null && open) {
            ZillitStatusPill(label = label, tone = StatusTone.Pending)
        } else {
            ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        }
    }
}

private const val DIMMED = 0.6f
private val LOCK_ICON = 12.dp
