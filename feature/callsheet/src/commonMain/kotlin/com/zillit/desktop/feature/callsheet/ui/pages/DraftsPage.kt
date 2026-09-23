// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.DraftChip
import com.zillit.desktop.feature.callsheet.domain.SavedTemplate
import com.zillit.desktop.feature.callsheet.domain.filterDrafts
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.domain.shootDayLabel
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.ListView
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.ActionMenu
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.CardGrid
import com.zillit.desktop.feature.callsheet.ui.components.CscIconButton
import com.zillit.desktop.feature.callsheet.ui.components.DayChip
import com.zillit.desktop.feature.callsheet.ui.components.FilterChip
import com.zillit.desktop.feature.callsheet.ui.components.InlineCount
import com.zillit.desktop.feature.callsheet.ui.components.MetaCell
import com.zillit.desktop.feature.callsheet.ui.components.NameCell
import com.zillit.desktop.feature.callsheet.ui.components.PersonCell
import com.zillit.desktop.feature.callsheet.ui.components.Segmented
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetErrorLine
import com.zillit.desktop.feature.callsheet.ui.components.SheetGridCard
import com.zillit.desktop.feature.callsheet.ui.components.SheetTable
import com.zillit.desktop.feature.callsheet.ui.components.StatusBadge
import com.zillit.desktop.feature.callsheet.ui.components.StripLabel
import com.zillit.desktop.feature.callsheet.ui.components.TableColumn
import com.zillit.desktop.feature.callsheet.ui.components.TableStyle
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * Drafts — the whole pre-signature phase and the project's shared templates
 * (`DraftTab.jsx`). Posting users see everything and every action; a
 * view-only user reaches the sheets they are asked to comment on.
 */
@Composable
internal fun DraftsPage(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long) {
    Column {
        if (state.isPoster && state.savedTemplates.isNotEmpty()) {
            TemplatePanel(state, onEvent)
        }
        DraftsList(state, onEvent, nowMillis)
    }
}

/** "Drafts Template" — `.csc-tpl-panel`: the project's saved layouts, a row opens one. */
@Composable
private fun TemplatePanel(state: SheetUiState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Column(
        Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.dsCard)
            .border(1.dp, colors.dsBorder, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StripLabel("Drafts Template", Modifier.weight(1f))
            Text(
                "Shared with everyone who can create call sheets",
                style = sheetText(12.sp),
                color = colors.dsTextPlaceholder,
            )
        }
        SheetTable(
            columns = listOf(
                TableColumn("#", width = 44.dp),
                TableColumn("Template Name", weight = 1f),
                TableColumn("Created By", width = 190.dp),
                TableColumn("Updated", width = 170.dp),
                TableColumn("Actions", width = 110.dp, alignment = Alignment.End),
            ),
            rows = state.savedTemplates,
            style = TableStyle.Csc,
            minWidth = 640.dp,
            onRowClick = { onEvent(DialogEvent.OpenSavedTemplate(it)) },
        ) { template, column, index ->
            TemplateCell(state, template, column, index, onEvent)
        }
    }
}

@Composable
private fun TemplateCell(
    state: SheetUiState,
    template: SavedTemplate,
    column: Int,
    index: Int,
    onEvent: (SheetEvent) -> Unit,
) {
    when (column) {
        0 -> Text("${index + 1}", style = sheetText(12.sp), color = SheetTheme.colors.dsTextMuted)
        1 -> NameCell(template.name, template.createdOn?.let { formatDateTime(it) })
        2 -> {
            // `created_by` is a NAME; the id, when sent, wins.
            val byId = state.member(template.createdById)
            val byName = byId ?: state.members.firstOrNull {
                it.fullName.trim().equals(template.createdBy.trim(), ignoreCase = true)
            }
            PersonCell(
                byId?.fullName ?: template.createdBy.ifBlank { "—" },
                byName?.designation.orEmpty(),
                csc = true,
            )
        }
        3 -> MetaCell(template.updatedOn?.let { formatDateTime(it) } ?: "—", csc = true)
        else -> CscIconButton(
            icon = ZillitIcons.Trash,
            description = "Delete template ${template.name}",
            onClick = { onEvent(DialogEvent.DeleteSavedTemplate(template)) },
            danger = true,
        )
    }
}

@Composable
private fun DraftsList(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long) {
    val list = state.lists.drafts
    val chip = if (state.isPoster) state.draftChip else DraftChip.All
    val filtered = filterDrafts(list.rows, chip)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isPoster) {
            DraftChip.entries.forEach { entry ->
                FilterChip(entry.label, filterDrafts(list.rows, entry).size, chip == entry) {
                    onEvent(ListEvent.SetDraftChip(entry))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ViewToggle(state.draftsView) { onEvent(ListEvent.SetDraftsView(it)) }
    }
    list.error?.let { error ->
        Box(Modifier.padding(bottom = 12.dp)) { SheetErrorLine(error) { onEvent(ListEvent.Retry) } }
    }
    when {
        !list.loaded -> LoadingBlock("Loading drafts…")
        filtered.isEmpty() -> CscEmpty(
            title = "No draft call sheets.",
            sub = if (chip != DraftChip.All) {
                "Nothing matches this filter."
            } else if (state.isPoster) {
                "Create one from a template to get started."
            } else {
                "Call sheets shared with you for comments will appear here."
            },
        )
        state.draftsView == ListView.Table -> DraftsTable(state, filtered, onEvent)
        else -> CardGrid(filtered) { row, modifier -> DraftCard(state, row, nowMillis, onEvent, modifier) }
    }
}

@Composable
private fun DraftsTable(state: SheetUiState, rows: List<CallSheetSummary>, onEvent: (SheetEvent) -> Unit) {
    SheetTable(
        columns = listOf(
            TableColumn("#", width = 44.dp),
            TableColumn("Document Name", weight = 1f),
            TableColumn("Day", width = 110.dp),
            TableColumn("Created By", width = 190.dp),
            TableColumn("Updated", width = 170.dp),
            TableColumn("Status", width = 170.dp, alignment = Alignment.CenterHorizontally),
            TableColumn("Actions", width = 150.dp, alignment = Alignment.End),
        ),
        rows = rows,
        style = TableStyle.Csc,
        minWidth = 1060.dp,
    ) { row, column, index ->
        when (column) {
            0 -> Text("${index + 1}", style = sheetText(12.sp), color = SheetTheme.colors.dsTextMuted)
            // The unread REPORT number beside the name; the comment count sits on the kebab.
            1 -> Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NameCell(row.name, formatDateTime(row.createdOn))
                InlineCount(state.unreadReports(row.id))
            }
            2 -> DayChip(shootDayLabel(row.shared))
            3 -> CreatorCell(state, row, csc = true)
            4 -> MetaCell(row.updatedOn?.let { formatDateTime(it) } ?: "—", csc = true)
            5 -> StatusBadge(row.status, row.statusLabel)
            else -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetButton(
                    "View",
                    { onEvent(ListEvent.View(row)) },
                    kind = ButtonKind.Csc,
                    icon = ZillitIcons.Eye,
                    radius = 10.dp,
                    height = 32.dp,
                    horizontalPadding = 13.dp,
                )
                ActionMenu(draftMenu(state, row, onEvent, includeView = false), badge = state.unreadComments(row.id))
            }
        }
    }
}

@Composable
private fun DraftCard(
    state: SheetUiState,
    row: CallSheetSummary,
    nowMillis: Long,
    onEvent: (SheetEvent) -> Unit,
    modifier: Modifier,
) {
    val entries = draftMenu(state, row, onEvent, includeView = true)
    val member = state.member(row.createdById)
    SheetGridCard(
        row = row,
        creatorName = member?.fullName ?: row.createdBy,
        creatorDesignation = member?.designation.orEmpty(),
        nowMillis = nowMillis,
        approvals = null,
        links = cardLinks(entries, listOf("view", "edit", "comment", "comments", "docdist", "sendChat")),
        pills = cardPills(entries, listOf("signature", "delete")),
        reportBadge = state.unreadReports(row.id),
        modifier = modifier,
    )
}

/** A creator's name and designation, resolved by id first, then by name. */
@Composable
internal fun CreatorCell(state: SheetUiState, row: CallSheetSummary, csc: Boolean = false) {
    val member = state.member(row.createdById)
        ?: state.members.firstOrNull { it.fullName.trim().equals(row.createdBy.trim(), ignoreCase = true) }
    PersonCell(member?.fullName ?: row.createdBy, member?.designation.orEmpty(), csc = csc)
}

/** The Table | Cards switch — antd Segmented with its icons. */
@Composable
internal fun ViewToggle(view: ListView, onChange: (ListView) -> Unit) {
    Segmented(
        options = listOf(ListView.Table to "Table", ListView.Cards to "Cards"),
        selected = view,
        onSelect = onChange,
        icons = mapOf(ListView.Table to SheetIcons.Table, ListView.Cards to ZillitIcons.Grid),
    )
}

/** `.csc-empty`: a dashed card with a bold line and a hint. */
@Composable
internal fun CscEmpty(title: String, sub: String?) {
    val colors = SheetTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.dsCard)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = colors.dsBorderStrong,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP))),
                )
            }
            .padding(horizontal = 20.dp, vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(SheetIcons.Tray, contentDescription = null, tint = colors.dsBorderStrong, modifier = Modifier.size(40.dp))
        Text(
            title,
            style = sheetText(15.sp, FontWeight.SemiBold),
            color = colors.dsText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
        if (!sub.isNullOrBlank()) {
            Text(sub, style = sheetText(13.5.sp), color = colors.dsTextMuted, textAlign = TextAlign.Center)
        }
    }
}

private const val DASH = 10f
private const val GAP = 7f

/** The first fetch — a quiet line instead of flashing the empty state (the web's §4.7 recommendation). */
@Composable
internal fun LoadingBlock(text: String) {
    val colors = SheetTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(Modifier.size(16.dp))
        Text(text, style = sheetText(13.sp), color = colors.textTertiary)
    }
}
