// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.callsheet.ui.pages

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.approvalCount
import com.zillit.desktop.feature.callsheet.domain.commentAllowed
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.domain.isInternalOnly
import com.zillit.desktop.feature.callsheet.domain.shootDayLabel
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.ListView
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetList
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.ActionMenu
import com.zillit.desktop.feature.callsheet.ui.components.CardApprovals
import com.zillit.desktop.feature.callsheet.ui.components.CardGrid
import com.zillit.desktop.feature.callsheet.ui.components.CardLink
import com.zillit.desktop.feature.callsheet.ui.components.CardTabItem
import com.zillit.desktop.feature.callsheet.ui.components.CardTabs
import com.zillit.desktop.feature.callsheet.ui.components.CscIconButton
import com.zillit.desktop.feature.callsheet.ui.components.InlineCount
import com.zillit.desktop.feature.callsheet.ui.components.MenuEntry
import com.zillit.desktop.feature.callsheet.ui.components.MetaCell
import com.zillit.desktop.feature.callsheet.ui.components.SheetEmptyState
import com.zillit.desktop.feature.callsheet.ui.components.SheetErrorLine
import com.zillit.desktop.feature.callsheet.ui.components.SheetGridCard
import com.zillit.desktop.feature.callsheet.ui.components.SheetTable
import com.zillit.desktop.feature.callsheet.ui.components.StatusBadge
import com.zillit.desktop.feature.callsheet.ui.components.TableColumn
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * Approvals — the signature phase seen from either end, and the sheets
 * finally approved (`ApprovalsTab.jsx`): Sent · Received · Finalized for
 * posting users (Received only for the project's final approvers), Received ·
 * Finalized for everyone else.
 */
@Composable
internal fun ApprovalsPage(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long) {
    val colors = SheetTheme.colors
    val section = state.activeSection
    Column {
        CardTabs(
            items = state.sections.map { entry ->
                when (entry) {
                    ApprovalSection.Sent ->
                        CardTabItem(entry, entry.label, ZillitIcons.Send, colors.dsPrimary, state.sectionBadge(entry))
                    ApprovalSection.Received ->
                        CardTabItem(entry, entry.label, ZillitIcons.Edit, colors.dsInfo, state.sectionBadge(entry))
                    ApprovalSection.Finalized ->
                        CardTabItem(entry, entry.label, ZillitIcons.Check, colors.dsSuccess, state.sectionBadge(entry))
                }
            },
            selected = section,
            onSelect = { onEvent(ListEvent.OpenSection(it)) },
        )
        // Only posting users choose a view; everyone else reads the table.
        val cards = state.isPoster && state.approvalsView == ListView.Cards
        if (state.isPoster) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                ViewToggle(state.approvalsView) { onEvent(ListEvent.SetApprovalsView(it)) }
            }
        }
        when (section) {
            ApprovalSection.Sent -> SentSection(state, onEvent, nowMillis, cards)
            ApprovalSection.Received -> ReceivedSection(state, onEvent, nowMillis, cards)
            ApprovalSection.Finalized -> FinalizedSection(state, onEvent, nowMillis, cards)
        }
    }
}

// Sent ---------------------------------------------------------------------------------------------------------

@Composable
private fun SentSection(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long, cards: Boolean) {
    val list = state.lists.sent
    ListFrame(list, "No sent approvals.", "Loading sent approvals…", onEvent) { rows ->
        if (cards) {
            CardGrid(rows) { row, modifier ->
                val entries = sentMenu(state, row, onEvent)
                val (approved, total) = approvalCount(row.status, row.approvals)
                SheetGridCard(
                    row = row,
                    creatorName = creatorName(state, row),
                    creatorDesignation = state.member(row.createdById)?.designation.orEmpty(),
                    nowMillis = nowMillis,
                    approvals = if (row.approvalsIncluded) CardApprovals(approved, total, null) else null,
                    links = cardLinks(entries, listOf("view", "history")) +
                        statusLink(state, row, onEvent) +
                        cardLinks(entries, listOf("comment", "sendChat", "docdist")),
                    pills = cardPills(entries, listOf("approve", "edit", "remind", "signature", "delete")),
                    reportBadge = state.unreadReports(row.id),
                    modifier = modifier,
                )
            }
        } else {
            SheetTable(
                columns = approvalColumns(approval = true),
                rows = rows,
                minWidth = 1120.dp,
            ) { row, column, _ ->
                when (column) {
                    in 0..4 -> CommonCell(state, row, column)
                    5 -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(row.status, row.statusLabel)
                        val unread = state.unreadComments(row.id)
                        if (commentAllowed(row.status, unread)) {
                            CscIconButton(
                                icon = SheetIcons.Comment,
                                description = "Comments",
                                onClick = { onEvent(ListEvent.OpenComments(row, readOnly = false)) },
                                badge = unread,
                            )
                        }
                    }
                    6 -> ApprovalLink(
                        text = when {
                            state.statusLoadingId == row.id -> "Loading…"
                            row.approvalsIncluded -> approvedText(row)
                            else -> "View status"
                        },
                        enabled = state.statusLoadingId == null,
                    ) { onEvent(ListEvent.OpenApprovalStatus(row)) }
                    else -> ActionMenu(sentMenu(state, row, onEvent), badge = state.unreadComments(row.id))
                }
            }
        }
    }
}

private fun statusLink(state: SheetUiState, row: CallSheetSummary, onEvent: (SheetEvent) -> Unit) = CardLink(
    label = if (state.statusLoadingId == row.id) "Loading…" else "Approval status",
    enabled = state.statusLoadingId == null,
    onClick = { onEvent(ListEvent.OpenApprovalStatus(row)) },
)

// Received -----------------------------------------------------------------------------------------------------

@Composable
private fun ReceivedSection(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long, cards: Boolean) {
    val list = state.lists.received
    ListFrame(list, "No received approvals.", "Loading received approvals…", onEvent) { rows ->
        if (cards) {
            CardGrid(rows) { row, modifier ->
                val entries = receivedMenu(state, row, onEvent)
                val (approved, total) = approvalCount(row.status, row.approvals)
                SheetGridCard(
                    row = row,
                    creatorName = creatorName(state, row),
                    creatorDesignation = state.member(row.createdById)?.designation.orEmpty(),
                    nowMillis = nowMillis,
                    approvals = if (isInternalOnly(row, state.me)) null else CardApprovals(approved, total, null),
                    links = cardLinks(entries, listOf("view", "history", "comment", "sendChat", "reminder")),
                    pills = cardPills(entries, listOf("reject", "approve")),
                    reportBadge = state.unreadReports(row.id),
                    modifier = modifier,
                )
            }
        } else {
            val showApproval = !rows.all { isInternalOnly(it, state.me) }
            SheetTable(
                columns = approvalColumns(approval = showApproval),
                rows = rows,
                minWidth = 1060.dp,
            ) { row, column, _ ->
                when {
                    column <= 4 -> CommonCell(state, row, column)
                    column == 5 -> StatusBadge(row.status, row.statusLabel)
                    column == 6 && showApproval -> if (!isInternalOnly(row, state.me)) {
                        ApprovalLink(approvedText(row), enabled = true) { onEvent(ListEvent.OpenApprovalStatus(row)) }
                    }
                    else -> ActionMenu(receivedMenu(state, row, onEvent), badge = state.unreadComments(row.id))
                }
            }
        }
    }
}

// Finalized ----------------------------------------------------------------------------------------------------

@Composable
private fun FinalizedSection(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long, cards: Boolean) {
    val list = state.lists.finalized
    ListFrame(list, "No finalized call sheets.", "Loading finalized call sheets…", onEvent) { rows ->
        if (cards) {
            CardGrid(rows) { row, modifier ->
                val entries = finalizedMenu(state, row, onEvent)
                SheetGridCard(
                    row = row,
                    creatorName = creatorName(state, row),
                    creatorDesignation = state.member(row.createdById)?.designation.orEmpty(),
                    nowMillis = nowMillis,
                    approvals = null,
                    links = cardLinks(entries, listOf("view", "history", "comment")),
                    pills = cardPills(entries, listOf("publish")),
                    reportBadge = state.unreadReports(row.id),
                    modifier = modifier,
                )
            }
        } else {
            SheetTable(
                columns = approvalColumns(approval = false),
                rows = rows,
                minWidth = 980.dp,
            ) { row, column, _ ->
                when (column) {
                    in 0..4 -> CommonCell(state, row, column)
                    5 -> StatusBadge(row.status, row.statusLabel)
                    else -> ActionMenu(finalizedMenu(state, row, onEvent), badge = state.unreadComments(row.id))
                }
            }
        }
    }
}

// Shared pieces --------------------------------------------------------------------------------------------------

private fun approvalColumns(approval: Boolean): List<TableColumn> = listOfNotNull(
    TableColumn("S.No", width = 72.dp),
    TableColumn("Day", width = 100.dp),
    TableColumn("Created By", weight = 1f),
    TableColumn("Created At", width = 180.dp),
    TableColumn("Updated At", width = 180.dp),
    TableColumn("Status", width = if (approval) 220.dp else 190.dp),
    TableColumn("Approval", width = 130.dp).takeIf { approval },
    TableColumn("Actions", width = 84.dp, alignment = Alignment.End),
)

@Composable
private fun CommonCell(state: SheetUiState, row: CallSheetSummary, column: Int) {
    when (column) {
        // The unread REPORT number beside the serial — a comment count sits on the kebab instead.
        0 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MetaCell(row.serialNo.ifBlank { "-" })
            InlineCount(state.unreadReports(row.id))
        }
        1 -> MetaCell(shootDayLabel(row.shared))
        2 -> CreatorCell(state, row)
        3 -> MetaCell(formatDateTime(row.createdOn))
        else -> MetaCell(formatDateTime(row.updatedOn))
    }
}

private fun creatorName(state: SheetUiState, row: CallSheetSummary): String =
    state.member(row.createdById)?.fullName ?: row.createdBy

private fun approvedText(row: CallSheetSummary): String {
    val (approved, total) = approvalCount(row.status, row.approvals)
    return "$approved/$total approved"
}

/** `.wa-approval-link`: 12 px underlined blue. */
@Composable
private fun ApprovalLink(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Text(
        text,
        style = sheetText(12.sp).copy(textDecoration = TextDecoration.Underline),
        color = when {
            !enabled -> colors.textMuted
            hovered -> if (colors.isDark) Color(0xFF93C5FD) else Color(0xFF1849A9)
            else -> colors.blue
        },
        modifier = Modifier.hoverable(source).plainClick(enabled = enabled, source = source, onClick = onClick),
    )
}

/** The error line, the first-load state, the empty state, or the rows. */
@Composable
private fun ListFrame(
    list: SheetList,
    emptyText: String,
    loadingText: String,
    onEvent: (SheetEvent) -> Unit,
    content: @Composable (List<CallSheetSummary>) -> Unit,
) {
    list.error?.let { error ->
        Box(Modifier.padding(bottom = 12.dp)) { SheetErrorLine(error) { onEvent(ListEvent.Retry) } }
    }
    when {
        !list.loaded -> LoadingBlock(loadingText)
        list.rows.isEmpty() -> SheetEmptyState(emptyText)
        else -> content(list.rows)
    }
}

/** A menu's actions as a flat list — the render tests read the labels through this. */
internal fun List<MenuEntry>.labels(): List<String> = filterIsInstance<MenuEntry.Action>().map { it.label }
