package com.zillit.desktop.feature.productionreport.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.approvalCount
import com.zillit.desktop.feature.productionreport.domain.approvalStatusEntries
import com.zillit.desktop.feature.productionreport.domain.approverIdsOf
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
import com.zillit.desktop.feature.productionreport.domain.isInternalOnly
import com.zillit.desktop.feature.productionreport.domain.shootDayLabel
import com.zillit.desktop.feature.productionreport.domain.stageForStatus
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ListView
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.components.ActionMenu
import com.zillit.desktop.feature.productionreport.ui.components.CardApprovals
import com.zillit.desktop.feature.productionreport.ui.components.CardGrid
import com.zillit.desktop.feature.productionreport.ui.components.Face
import com.zillit.desktop.feature.productionreport.ui.components.HoverCard
import com.zillit.desktop.feature.productionreport.ui.components.MenuEntry
import com.zillit.desktop.feature.productionreport.ui.components.MetaCell
import com.zillit.desktop.feature.productionreport.ui.components.ReportEmptyState
import com.zillit.desktop.feature.productionreport.ui.components.ReportErrorLine
import com.zillit.desktop.feature.productionreport.ui.components.ReportGridCard
import com.zillit.desktop.feature.productionreport.ui.components.ReportTable
import com.zillit.desktop.feature.productionreport.ui.components.StatusBadge
import com.zillit.desktop.feature.productionreport.ui.components.TabBadge
import com.zillit.desktop.feature.productionreport.ui.components.TableColumn
import com.zillit.desktop.feature.productionreport.ui.components.UnderlineTab
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * Approvals — the signature phase: one flat row Sent | Received | Finalized
 * (`ApprovalsTab.jsx`). The row appears once rights are known, so it never
 * changes under the user.
 */
@Composable
internal fun ApprovalsPage(state: ReportUiState, onEvent: (ReportEvent) -> Unit, nowMillis: Long) {
    if (!state.viewer.ready) return
    val colors = ReportTheme.colors
    val section = state.activeSection
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                state.sections.forEach { key ->
                    UnderlineTab(key.label, active = key == section, badge = state.sectionBadge(key), strong = false) {
                        onEvent(ListEvent.OpenSection(key))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(bottom = 8.dp)) {
                    ViewToggle(state.approvalsView) { onEvent(ListEvent.SetApprovalsView(it)) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }
        val list = when (section) {
            ApprovalSection.Sent -> state.lists.sent
            ApprovalSection.Received -> state.lists.received
            ApprovalSection.Finalized -> state.lists.finalized
        }
        list.error?.let { ReportErrorLine(it) { onEvent(ListEvent.Retry) } }
        val rows = paneRows(state, section, list.rows)
        when {
            rows.isEmpty() -> ReportEmptyState(emptyText(section, list.loaded))
            state.approvalsView == ListView.Table -> SectionTable(state, section, rows, onEvent)
            else -> CardGrid(rows) { row, modifier -> SectionCard(state, section, row, nowMillis, onEvent, modifier) }
        }
    }
}

private fun paneRows(
    state: ReportUiState,
    section: ApprovalSection,
    rows: List<ReportSummary>,
): List<ReportSummary> = when (section) {
    ApprovalSection.Sent -> rows.filter { it.status != ReportStatus.ApprovedForPublish }
    ApprovalSection.Received -> if (state.isPoster) {
        rows.filter { it.status != ReportStatus.ApprovedForPublish }
    } else {
        rows.filter { it.status !in setOf(ReportStatus.ApprovedForPublish, ReportStatus.Published, ReportStatus.Draft) }
    }
    ApprovalSection.Finalized -> rows
}

private fun emptyText(section: ApprovalSection, loaded: Boolean): String = when (section) {
    ApprovalSection.Sent ->
        if (loaded) str(S.desktop_no_sent_approvals) else str(S.desktop_loading_sent_approvals)
    ApprovalSection.Received ->
        if (loaded) str(S.desktop_no_received_approvals) else str(S.desktop_loading_received_approvals)
    ApprovalSection.Finalized ->
        if (loaded) str(S.desktop_pr_no_finalized) else str(S.desktop_pr_loading_finalized)
}

@Composable
private fun SectionTable(
    state: ReportUiState,
    section: ApprovalSection,
    rows: List<ReportSummary>,
    onEvent: (ReportEvent) -> Unit,
) {
    val showApproval = section == ApprovalSection.Received && !rows.all { isInternalOnly(it, state.me) }
    val keyed = buildList {
        add("#" to TableColumn("#", width = 56.dp))
        add("Day" to TableColumn(str(S.bs_day), width = 100.dp))
        add("Created By" to TableColumn(str(S.pr_created_by), weight = 1.2f))
        add("Created At" to TableColumn(str(S.ah_lbl_created_at), width = 190.dp))
        add("Updated At" to TableColumn(str(S.ah_lbl_updated_at), width = 190.dp))
        add("Status" to TableColumn(str(S.status), width = 170.dp))
        if (showApproval) add("Approval" to TableColumn(str(S.ah_step_approval), width = 130.dp))
        add("Actions" to TableColumn(str(S.dd_actions), width = 90.dp, alignment = Alignment.End))
    }
    val columns = keyed.map { it.second }
    ReportTable(columns = columns, rows = rows, minWidth = 1000.dp) { row, column, index ->
        val key = keyed[column].first
        when (key) {
            // The tables name no report, so the unread REPORT count sits by the serial.
            "#" -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = reportText(12.sp, FontWeight.Medium),
                    color = ReportTheme.colors.textTertiary,
                )
                TabBadge(reportUnreadFor(state, row))
            }
            "Day" -> MetaCell(shootDayLabel(row.shared))
            "Created By" -> CreatorCell(state, row)
            "Created At" -> MetaCell(formatDateTime(row.createdOn))
            "Updated At" -> MetaCell(formatDateTime(row.updatedOn))
            "Status" -> if (section == ApprovalSection.Sent) SentStatusPill(
                state,
                row,
            ) else StatusBadge(row.status, row.statusLabel)
            "Approval" -> if (!isInternalOnly(row, state.me)) ApprovalLink(row)
            else -> ActionMenu(menuFor(state, section, row, onEvent), badge = unreadFor(state, row))
        }
    }
}

private fun menuFor(
    state: ReportUiState,
    section: ApprovalSection,
    row: ReportSummary,
    onEvent: (ReportEvent) -> Unit,
): List<MenuEntry> =
    when (section) {
        ApprovalSection.Sent -> sentMenu(state, row, onEvent)
        ApprovalSection.Received -> receivedMenu(state, row, onEvent)
        ApprovalSection.Finalized -> finalizedMenu(state, row, onEvent)
    }

/** Sent's status: the approval popover when the row carries requests, else a names tooltip. */
@Composable
private fun SentStatusPill(state: ReportUiState, row: ReportSummary) {
    val (_, total) = approvalCount(row.status, row.approvals)
    if (total > 0) {
        HoverCard(str(S.onboarding_status), trigger = { StatusBadge(row.status, row.statusLabel) }) {
            ApprovalStatusList(state, row)
        }
        return
    }
    val names = approverIdsOf(row).mapNotNull { id -> state.member(id)?.fullName?.takeIf { it.isNotBlank() } }
    if (names.isEmpty()) {
        StatusBadge(row.status, row.statusLabel)
    } else {
        val joined = names.joinToString(", ")
        val tip = if (names.size == 1) str(S.desktop_approver_line, joined) else str(S.desktop_approvers_line, joined)
        ZillitTooltip(tip) {
            StatusBadge(row.status, row.statusLabel)
        }
    }
}

@Composable
private fun ApprovalLink(row: ReportSummary) {
    val (approved, total) = approvalCount(row.status, row.approvals)
    HoverCard(
        str(S.onboarding_status),
        trigger = {
            Text(
                str(S.av_approval_progress, approved, total),
                style = reportText(12.sp).copy(textDecoration = TextDecoration.Underline),
                color = ReportTheme.colors.blue,
            )
        },
    ) { ApprovalStatusList(null, row) }
}

/** The "Approval Status" popover body — the current round, pending first. */
@Composable
internal fun ApprovalStatusList(state: ReportUiState?, row: ReportSummary) {
    val colors = ReportTheme.colors
    val entries = approvalStatusEntries(row.approvals, stageForStatus(row.status))
    if (entries.isEmpty()) {
        Text(str(S.desktop_no_approvers_dot), style = reportText(12.sp), color = colors.textMuted)
        return
    }
    Column(
        Modifier.heightIn(max = 360.dp).zillitVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Face(entry.userId, state?.member(entry.userId)?.fullName ?: entry.name, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = reportText(14.sp, FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    Text(entry.role, style = reportText(12.sp), color = colors.textTertiary, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                        Text("${entry.stage} •", style = reportText(11.sp), color = colors.textMuted)
                        Text(
                            entry.status,
                            style = reportText(11.sp, FontWeight.SemiBold),
                            color = statusColor(entry.status),
                        )
                        if (entry.actedOn != null) Text(
                            "• ${formatDateTime(entry.actedOn)}",
                            style = reportText(11.sp),
                            color = colors.textMuted,
                        )
                    }
                    if (entry.reason.isNotBlank()) {
                        Text(
                            str(S.docusign_recipient_timeline_declined_reason, entry.reason),
                            style = reportText(11.sp).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = colors.red,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: String) = when (status) {
    "APPROVED" -> ReportTheme.colors.green
    "REJECTED" -> ReportTheme.colors.red
    "PENDING" -> androidx.compose.ui.graphics.Color(0xFFB54708)
    else -> ReportTheme.colors.textTertiary
}

@Composable
private fun SectionCard(
    state: ReportUiState,
    section: ApprovalSection,
    row: ReportSummary,
    nowMillis: Long,
    onEvent: (ReportEvent) -> Unit,
    modifier: Modifier,
) {
    val member = state.member(row.createdById)
    val entries = menuFor(state, section, row, onEvent)
    val primary = when (section) {
        ApprovalSection.Sent -> setOf("remind", "delete")
        ApprovalSection.Received -> setOf("reminder", "approve", "reject")
        ApprovalSection.Finalized -> setOf("publish")
    }
    val approvals = if (section == ApprovalSection.Finalized) {
        null
    } else {
        val (approved, total) = approvalCount(row.status, row.approvals)
        CardApprovals(approved, total) { ApprovalStatusList(state, row) }
    }
    ReportGridCard(
        row = row,
        creatorName = row.createdBy.ifBlank { member?.fullName.orEmpty() },
        creatorDesignation = member?.designation.orEmpty(),
        nowMillis = nowMillis,
        approvals = approvals,
        links = cardLinks(entries, primary).filterNot { it.label == str(S.pr_send_reminder) },
        pills = cardPills(entries, primary),
        modifier = modifier,
        nameBadge = reportUnreadFor(state, row),
    )
}
