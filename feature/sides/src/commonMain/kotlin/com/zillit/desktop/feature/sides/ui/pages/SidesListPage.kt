@file:Suppress("LongMethod") // Card, table and history panels read best whole.

package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.SidesLayout
import com.zillit.desktop.feature.sides.ui.SidesListState
import com.zillit.desktop.feature.sides.ui.components.CountChip
import com.zillit.desktop.feature.sides.ui.components.CreatorAvatar
import com.zillit.desktop.feature.sides.ui.components.GeneratingPill
import com.zillit.desktop.feature.sides.ui.components.LayoutToggle
import com.zillit.desktop.feature.sides.ui.components.MetaCell
import com.zillit.desktop.feature.sides.ui.components.MetaDot
import com.zillit.desktop.feature.sides.ui.components.SceneChip
import com.zillit.desktop.feature.sides.ui.components.SidesLoader
import com.zillit.desktop.feature.sides.ui.components.SidesTile
import com.zillit.desktop.feature.sides.ui.components.hand
import com.zillit.desktop.feature.sides.ui.components.hexColor

/** The generated-sides list — the web's `SidesPage` below its header. */
@Composable
internal fun SidesListPage(list: SidesListState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZillitText(str(S.desktop_sides_list_title), style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = str(S.desktop_sides_list_subtitle),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (list.sides.isNotEmpty()) LayoutToggle(list.layout, onChange = { onEvent(SidesEvent.SetLayout(it)) })
        }

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(end = RAIL_GUTTER),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            when {
                list.loading && list.sides.isEmpty() -> SidesLoader(str(S.desktop_sides_loading))
                list.sides.isEmpty() -> ZillitEmptyState(
                    title = str(S.sides_empty_title),
                    message = str(S.sides_empty_subtitle),
                    icon = ZillitIcons.File,
                    action = {
                        ZillitButton(
                            text = str(S.sides_generate),
                            onClick = { onEvent(SidesEvent.OpenGenerate) },
                            leadingIcon = ZillitIcons.Add,
                        )
                    },
                )
                list.layout == SidesLayout.Table -> SidesTable(list.sides, history = false, onEvent)
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    list.sides.forEach { record -> SideCard(record, history = false, onEvent) }
                }
            }
            HistoryPanel(list, onEvent)
        }
    }
}

// ── Cards ──────────────────────────────────────────────────────────────────

/**
 * The premium card for a generated record: source script + version, the
 * file it was built from, scene and page chips, who generated it,
 * downloads, timestamp and size — the web's `SideCard`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SideCard(record: SidesRecord, history: Boolean, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, if (hovered) colors.borderStrong else colors.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SidesTile()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        text = record.title.ifBlank { str(S.txt_sides) },
                        style = ZillitTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusPill(record, history)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (record.scriptLabel.isNotBlank()) MetaCell(ZillitIcons.Folder, record.scriptLabel)
                    if (record.scriptLabel.isNotBlank() && record.fileLabel.isNotBlank()) MetaDot()
                    if (record.fileLabel.isNotBlank()) MetaCell(ZillitIcons.File, record.fileLabel)
                }
            }
            RowActions(record, history, small = false, onEvent)
        }

        CardChips(record)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaCell(ZillitIcons.Grid, "${record.sceneCount} scenes")
            if (record.generatedByName.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CreatorAvatar(record.generatedById, record.generatedByName)
                    ZillitText(
                        record.generatedByName,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            MetaCell(ZillitIcons.Download, "${record.downloadCount} downloads")
            MetaCell(ZillitIcons.Clock, SidesRules.formatDateTime(record.createdAt))
            SidesRules.formatBytes(record.attachmentSize)?.let { MetaCell(ZillitIcons.Paperclip, it) }
        }
    }
}

/** Scene chips, then colour-coded page chips, each capped with a tooltip overflow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardChips(record: SidesRecord) {
    if (record.sceneNumbers.isNotEmpty()) {
        ChipRow(label = str(S.av_scenes)) {
            record.sceneNumbers.take(SidesRules.SCENE_CHIP_CAP).forEach { SceneChip(it) }
            val overflow = record.sceneNumbers.size - SidesRules.SCENE_CHIP_CAP
            if (overflow > 0) {
                SceneChip(
                    "+$overflow",
                    more = true,
                    tip = record.sceneNumbers.drop(SidesRules.SCENE_CHIP_CAP).joinToString(", "),
                )
            }
        }
    }
    val pages = record.pageRefs
    if (pages.isNotEmpty()) {
        ChipRow(label = str(S.pages)) {
            pages.take(SidesRules.PAGE_CHIP_CAP).forEach { page ->
                SceneChip(
                    text = page.sceneNumber,
                    tint = hexColor(page.color),
                    tip = page.sceneNumbers.joinToString(", ").let {
                        if (it.isEmpty()) "" else str(S.desktop_sides_scenes_list, it)
                    },
                )
            }
            val overflow = pages.size - SidesRules.PAGE_CHIP_CAP
            if (overflow > 0) {
                SceneChip(
                    text = "+$overflow",
                    more = true,
                    tip = pages.drop(SidesRules.PAGE_CHIP_CAP).joinToString(", ") { it.sceneNumber },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(top = 5.dp, end = 4.dp),
        )
        chips()
    }
}

@Composable
private fun StatusPill(record: SidesRecord, history: Boolean) {
    when {
        history -> ZillitStatusPill(str(S.desktop_archived), tone = StatusTone.Done)
        record.status == SidesStatus.Ready -> Unit
        record.status == SidesStatus.Unknown && record.rawStatus.isNotBlank() ->
            ZillitStatusPill(record.rawStatus, tone = StatusTone.Neutral)
        else -> ZillitStatusPill(record.status.label, tone = record.status.tone, dot = true)
    }
}

/**
 * View, Download and Delete for a record. Every button stays on screen —
 * the handler asks an admin when the right is missing (ZL-19714).
 */
@Composable
private fun RowActions(record: SidesRecord, history: Boolean, small: Boolean, onEvent: (SidesEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (record.status == SidesStatus.Generating) GeneratingPill()
        if (record.status.viewable) {
            if (small) {
                ZillitTooltip(str(S.view)) {
                    ZillitIconButton(ZillitIcons.Eye, str(S.view), onClick = { onEvent(SidesEvent.ViewSides(record)) })
                }
                ZillitTooltip(str(S.download)) {
                    ZillitIconButton(
                        ZillitIcons.Download,
                        str(S.download),
                        onClick = { onEvent(SidesEvent.DownloadSides(record)) },
                    )
                }
            } else {
                ZillitButton(
                    text = str(S.view),
                    onClick = { onEvent(SidesEvent.ViewSides(record)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Eye,
                )
                ZillitButton(
                    text = str(S.download),
                    onClick = { onEvent(SidesEvent.DownloadSides(record)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Download,
                )
            }
        }
        if (!history) {
            ZillitTooltip(str(S.delete)) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.desktop_sides_delete),
                    onClick = { onEvent(SidesEvent.AskDeleteSides(record)) },
                    tint = ZillitTheme.colors.danger,
                )
            }
        }
    }
}

// ── Table ─────────────────────────────────────────────────

/** The compact table view — the web's `SidesTable`. */
@Composable
private fun SidesTable(rows: List<SidesRecord>, history: Boolean, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val columns = listOf(
        TableColumn<SidesRecord>(str(S.name), ColumnWidth.Weight(2.2f)) { record ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SidesTile(size = 30.dp)
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ZillitText(
                            text = record.title.ifBlank { str(S.txt_sides) },
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!history && !record.status.viewable) StatusPill(record, history = false)
                    }
                    val sub = record.scriptTitle.takeIf { it.isNotBlank() }
                        ?.let { title -> if (record.versionText.isBlank()) title else "$title · ${record.versionText}" }
                        ?: record.callSheetTitle
                    if (sub.isNotBlank()) {
                        ZillitText(
                            sub,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        TableColumn(str(S.av_scenes), ColumnWidth.Fixed(80.dp), numeric = true) { record ->
            ZillitText(record.sceneCount.toString(), style = ZillitTheme.typography.bodySmall)
        },
        TableColumn(str(S.pages), ColumnWidth.Fixed(80.dp), numeric = true) { record ->
            val pages = record.pageRefs
            ZillitTooltip(pages.joinToString(", ") { it.sceneNumber }) {
                ZillitText(
                    if (pages.isEmpty()) "—" else pages.size.toString(),
                    style = ZillitTheme.typography.bodySmall,
                )
            }
        },
        TableColumn(str(S.desktop_generated_by), ColumnWidth.Weight(1.2f)) { record ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CreatorAvatar(record.generatedById, record.generatedByName, size = 26.dp)
                ZillitText(
                    text = record.generatedByName.ifBlank { "—" },
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        TableColumn(str(S.desktop_downloads), ColumnWidth.Fixed(90.dp), numeric = true) { record ->
            ZillitText(record.downloadCount.toString(), style = ZillitTheme.typography.bodySmall)
        },
        TableColumn(str(S.date), ColumnWidth.Fixed(170.dp)) { record ->
            ZillitText(SidesRules.formatDateTime(record.createdAt), style = ZillitTheme.typography.bodySmall)
        },
        TableColumn(str(S.drive_sort_size), ColumnWidth.Fixed(80.dp), numeric = true) { record ->
            ZillitText(SidesRules.formatBytes(record.attachmentSize) ?: "—", style = ZillitTheme.typography.bodySmall)
        },
        TableColumn(str(S.dd_actions), ColumnWidth.Fixed(if (history) 100.dp else 140.dp)) { record ->
            RowActions(record, history, small = true, onEvent)
        },
    )
    ZillitDataTable(
        rows = rows.sortedByDescending { it.createdAt },
        columns = columns,
        key = { it.id },
        // Inside a scrolling page: a virtualised table would measure to nothing.
        virtualised = false,
        emptyTitle = str(S.desktop_sides_none),
    )
}

// ── History ────────────────────────────────────────────────────────────────

/** The collapsible archived panel with its name/creator search. */
@Composable
private fun HistoryPanel(list: SidesListState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val caret by animateFloatAsState(if (list.historyOpen) 180f else 0f, label = "historyCaret")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(SidesEvent.ToggleHistory) }
                .hand()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SidesTile(icon = ZillitIcons.Clock, size = 30.dp)
            ZillitText(str(S.history), style = ZillitTheme.typography.titleMedium)
            if (list.historyOpen && list.history.isNotEmpty()) CountChip(list.history.size)
            Spacer(Modifier.weight(1f))
            ZillitIcon(
                icon = ZillitIcons.ChevronDown,
                tint = colors.textMuted,
                size = 16.dp,
                modifier = Modifier.rotate(caret),
            )
        }
        if (list.historyOpen) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    list.historyLoading && list.history.isEmpty() -> SidesLoader(str(S.desktop_loading_history))
                    list.history.isEmpty() -> ZillitText(
                        text = str(S.desktop_sides_no_archived),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    else -> {
                        ZillitSearchField(
                            value = list.historySearch,
                            onValueChange = { onEvent(SidesEvent.HistorySearch(it)) },
                            placeholder = str(S.desktop_sides_search_archived),
                            modifier = Modifier.width(360.dp),
                        )
                        val rows = list.filteredHistory
                        when {
                            rows.isEmpty() -> ZillitText(
                                text = str(S.desktop_sides_no_archived_match, list.historySearch),
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                            list.layout == SidesLayout.Table -> SidesTable(rows, history = true, onEvent)
                            else -> rows.forEach { SideCard(it, history = true, onEvent) }
                        }
                    }
                }
            }
        }
    }
}

internal val SidesStatus.tone: StatusTone
    get() = when (this) {
        SidesStatus.Ready -> StatusTone.Ready
        SidesStatus.Generating -> StatusTone.Progress
        SidesStatus.Error, SidesStatus.Failed -> StatusTone.Rejected
        SidesStatus.Archived -> StatusTone.Done
        SidesStatus.Unknown -> StatusTone.Neutral
    }

/** Keeps the cards clear of the scroll rail. */
private val RAIL_GUTTER = 14.dp
