package com.zillit.desktop.feature.productionreport.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.domain.DraftChip
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate
import com.zillit.desktop.feature.productionreport.domain.filterDrafts
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
import com.zillit.desktop.feature.productionreport.domain.shootDayLabel
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ListView
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.components.ActionMenu
import com.zillit.desktop.feature.productionreport.ui.components.CardGrid
import com.zillit.desktop.feature.productionreport.ui.components.MetaCell
import com.zillit.desktop.feature.productionreport.ui.components.PersonCell
import com.zillit.desktop.feature.productionreport.ui.components.ReportEmptyState
import com.zillit.desktop.feature.productionreport.ui.components.ReportErrorLine
import com.zillit.desktop.feature.productionreport.ui.components.ReportGridCard
import com.zillit.desktop.feature.productionreport.ui.components.ReportIconButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportTable
import com.zillit.desktop.feature.productionreport.ui.components.Segmented
import com.zillit.desktop.feature.productionreport.ui.components.StatusBadge
import com.zillit.desktop.feature.productionreport.ui.components.TableColumn
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * Drafts — the pre-signature phase and the project's shared templates
 * (`DraftTab.jsx`). Authors see everything and every action; a view-only
 * user reaches it read-only, to comment.
 */
@Composable
internal fun DraftsPage(state: ReportUiState, onEvent: (ReportEvent) -> Unit, nowMillis: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.isPoster && state.savedTemplates.isNotEmpty()) {
            TemplateGroup(state, onEvent)
        }
        DraftsCard(state, onEvent, nowMillis)
    }
}

@Composable
private fun TemplateGroup(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Drafts Template",
                style = reportText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Shared with everyone who can create production reports",
                style = reportText(12.sp),
                color = colors.textTertiary,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        ReportTable(
            columns = listOf(
                TableColumn("#", width = 44.dp),
                TableColumn("Template Name", weight = 1.4f),
                TableColumn("Created By", width = 190.dp),
                TableColumn("Updated", width = 190.dp),
                TableColumn("Actions", width = 110.dp, alignment = Alignment.End),
            ),
            rows = state.savedTemplates,
            roomy = true,
            minWidth = 640.dp,
        ) { template, column, index ->
            TemplateCell(state, template, column, index, onEvent)
        }
    }
}

@Composable
private fun TemplateCell(
    state: ReportUiState,
    template: SavedTemplate,
    column: Int,
    index: Int,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    when (column) {
        0 -> MetaCell("${index + 1}")
        1 -> Column(Modifier.plainClick { onEvent(DialogEvent.OpenSavedTemplate(template)) }) {
            Text(
                template.name.ifBlank { "Untitled" },
                style = reportText(13.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            if (template.createdOn != null) Text(
                formatDateTime(template.createdOn),
                style = reportText(11.5.sp),
                color = colors.textTertiary,
            )
        }
        2 -> {
            val member = state.member(template.createdById)
                ?: state.members.firstOrNull { it.fullName == template.createdBy.trim() }
            PersonCell(member?.fullName ?: template.createdBy.ifBlank { "—" }, member?.designation.orEmpty())
        }
        3 -> MetaCell(if (template.updatedOn != null) formatDateTime(template.updatedOn) else "—")
        else -> ReportIconButton(
            icon = ZillitIcons.Trash,
            description = "Delete template",
            onClick = { onEvent(DialogEvent.DeleteSavedTemplate(template)) },
            tint = colors.textSecondary,
            hoverTint = Color(0xFFDC2626),
        )
    }
}

@Composable
private fun DraftsCard(state: ReportUiState, onEvent: (ReportEvent) -> Unit, nowMillis: Long) {
    val colors = ReportTheme.colors
    val list = state.lists.drafts
    val filtered = filterDrafts(list.rows, state.draftChip)
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.draftChip, filtered.size) { if (page * PAGE_SIZE >= filtered.size) page = 0 }
    Column(
        Modifier.fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    ) {
        if (state.isPoster) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DraftChip.entries.forEach { chip ->
                    Chip(
                        chip.label,
                        filterDrafts(list.rows, chip).size,
                        state.draftChip == chip,
                    ) { onEvent(ListEvent.SetDraftChip(chip)) }
                }
            }
        }
        ViewBar(
            caption = "${filtered.size} ${if (filtered.size == 1) "draft" else "drafts"}",
            view = state.draftsView,
        ) {
            onEvent(ListEvent.SetDraftsView(it))
        }
        list.error?.let { Box(Modifier.padding(16.dp)) { ReportErrorLine(it) { onEvent(ListEvent.Retry) } } }
        val visible = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        when {
            !list.loaded && list.loading -> ReportEmptyState("Loading drafts…", bordered = false)
            filtered.isEmpty() -> ReportEmptyState("No draft production reports.", bordered = false)
            state.draftsView == ListView.Table -> DraftsTable(state, visible, page * PAGE_SIZE, onEvent)
            else -> Box(Modifier.fillMaxWidth().background(colors.elevated).padding(16.dp)) {
                CardGrid(visible) { row, modifier -> DraftCard(state, row, nowMillis, onEvent, modifier) }
            }
        }
        if (filtered.size > PAGE_SIZE) Pager(page, filtered.size, "draft") { page = it }
    }
}

private const val PAGE_SIZE = 10

@Composable
private fun DraftsTable(state: ReportUiState, rows: List<ReportSummary>, offset: Int, onEvent: (ReportEvent) -> Unit) {
    ReportTable(
        columns = listOf(
            TableColumn("#", width = 60.dp, alignment = Alignment.CenterHorizontally),
            TableColumn("Name", weight = 1.3f),
            TableColumn("Day", width = 90.dp, alignment = Alignment.CenterHorizontally),
            TableColumn("Created By", weight = 1f),
            TableColumn("Created At", width = 180.dp),
            TableColumn("Updated At", width = 180.dp),
            TableColumn("Status", width = 175.dp),
            TableColumn("Actions", width = 150.dp, alignment = Alignment.End),
        ),
        rows = rows,
        roomy = true,
        minWidth = 1100.dp,
    ) { row, column, index ->
        when (column) {
            0 -> Text("${offset + index + 1}", style = reportText(12.sp), color = ReportTheme.colors.textTertiary)
            1 -> Text(
                row.name.ifBlank { "-" },
                style = reportText(13.sp, FontWeight.Medium),
                color = ReportTheme.colors.textPrimary,
                maxLines = 2,
            )
            2 -> MetaCell(shootDayLabel(row.shared))
            3 -> CreatorCell(state, row)
            4 -> MetaCell(formatDateTime(row.createdOn))
            5 -> MetaCell(if (row.updatedOn != null) formatDateTime(row.updatedOn) else "—")
            6 -> StatusBadge(row.status, row.statusLabel)
            else -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowViewButton { onEvent(ListEvent.View(row)) }
                ActionMenu(draftMenu(state, row, onEvent, includeView = false), badge = unreadFor(state, row))
            }
        }
    }
}

/** A creator's name and designation, resolved by id first. */
@Composable
internal fun CreatorCell(state: ReportUiState, row: ReportSummary) {
    val member = state.member(row.createdById) ?: state.members.firstOrNull { it.fullName == row.createdBy.trim() }
    PersonCell(row.createdBy.ifBlank { member?.fullName.orEmpty() }, member?.designation.orEmpty())
}

@Composable
private fun DraftCard(
    state: ReportUiState,
    row: ReportSummary,
    nowMillis: Long,
    onEvent: (ReportEvent) -> Unit,
    modifier: Modifier,
) {
    val member = state.member(row.createdById)
    val entries = draftMenu(state, row, onEvent, includeView = true)
    ReportGridCard(
        row = row,
        creatorName = row.createdBy.ifBlank { member?.fullName.orEmpty() },
        creatorDesignation = member?.designation.orEmpty(),
        nowMillis = nowMillis,
        approvals = null,
        links = cardLinks(entries, primaryKeys = setOf("signature", "delete")),
        pills = cardPills(entries, primaryKeys = setOf("signature", "delete")),
        modifier = modifier,
    )
}

/** The Drafts chips — `.rpt-chip`. */
@Composable
private fun Chip(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) colors.accent.copy(alpha = 0.10f) else colors.surface)
            .border(1.dp, if (on) colors.accent else if (hovered) colors.textTertiary else colors.border, CircleShape)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = if (on) colors.chipOnText else colors.textSecondary
        Text(label, style = reportText(12.5.sp, FontWeight.SemiBold), color = text)
        Text("$count", style = reportText(11.sp, FontWeight.ExtraBold), color = text.copy(alpha = 0.75f))
    }
}

/** The caption and Table | Cards switch above a list. */
@Composable
internal fun ViewBar(caption: String, view: ListView, onChange: (ListView) -> Unit) {
    val colors = ReportTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            caption.uppercase(),
            style = reportText(12.sp, FontWeight.SemiBold).copy(letterSpacing = 0.6.sp),
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ViewToggle(view, onChange)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
internal fun ViewToggle(view: ListView, onChange: (ListView) -> Unit) {
    Segmented(
        options = listOf(ListView.Table to "Table", ListView.Cards to "Cards"),
        selected = view,
        onSelect = onChange,
        icons = mapOf(ListView.Table to ReportIcons.Table, ListView.Cards to ZillitIcons.Grid),
    )
}

/** The row "View" button — `.rpt-row-btn`. */
@Composable
internal fun RowViewButton(onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) colors.elevated else colors.surface)
            .border(1.dp, if (hovered) colors.textTertiary else colors.border, RoundedCornerShape(10.dp))
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ZillitIcons.Eye, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(13.dp))
        Text("View", style = reportText(13.sp, FontWeight.SemiBold), color = colors.textPrimary)
    }
}

@Composable
internal fun Pager(page: Int, total: Int, noun: String, onPage: (Int) -> Unit) {
    val colors = ReportTheme.colors
    val pages = (total + PAGE_SIZE - 1) / PAGE_SIZE
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$total ${if (total == 1) noun else "${noun}s"}", style = reportText(12.sp), color = colors.textTertiary)
        Spacer(Modifier.size(8.dp))
        ReportIconButton(ZillitIcons.ChevronLeft, "Previous page", { onPage(page - 1) }, enabled = page > 0)
        repeat(pages) { index ->
            val on = index == page
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (on) colors.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .plainClick { onPage(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}",
                    style = reportText(12.sp, FontWeight.Medium),
                    color = if (on) colors.accent else colors.textSecondary,
                )
            }
        }
        ReportIconButton(ZillitIcons.ChevronRight, "Next page", { onPage(page + 1) }, enabled = page < pages - 1)
    }
}

internal fun unreadFor(
    state: ReportUiState,
    row: ReportSummary,
): Int = state.badges.commentsFor(state.commentScope, row.id)
