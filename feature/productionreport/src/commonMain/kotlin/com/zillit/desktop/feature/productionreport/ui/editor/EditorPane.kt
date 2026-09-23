// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.SectionBlock
import com.zillit.desktop.feature.productionreport.domain.approverCandidates
import com.zillit.desktop.feature.productionreport.domain.sectionBlocks
import com.zillit.desktop.feature.productionreport.ui.DocumentEvent
import com.zillit.desktop.feature.productionreport.ui.EditorEvent
import com.zillit.desktop.feature.productionreport.ui.EditorState
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.UndoRecord
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.Face
import com.zillit.desktop.feature.productionreport.ui.components.HoverCard
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportInput
import com.zillit.desktop.feature.productionreport.ui.components.ReportSwitch
import com.zillit.desktop.feature.productionreport.ui.components.Segmented
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlin.math.abs
import kotlin.time.Clock

/**
 * The right-hand pane — `PageRowsEditor.jsx`: the Sections list, or the
 * panel for what is selected (header info, approvers, a section), or the
 * empty state that keeps an undo alive after a removal.
 */
@Composable
internal fun EditorPane(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val selection = editor.selection
    val paneKey = when {
        selection == null && editor.sidebarVisible -> "sections"
        selection == EditorSelection.Shared -> "shared"
        selection == EditorSelection.Approvers -> "approvers"
        selection is EditorSelection.Cell && editor.selectedCell() != null -> "cell-${selection.row}-${selection.cell}"
        else -> "empty"
    }
    AnimatedContent(
        targetState = paneKey,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 40 }) togetherWith fadeOut(tween(120))
        },
        modifier = Modifier.fillMaxSize().background(colors.editorBg),
    ) { key ->
        when {
            key == "sections" -> SectionsList(editor, onEvent)
            key == "shared" -> PanelScroll { SharedPanel(editor, onEvent) }
            key == "approvers" -> PanelScroll { ApproversPanel(state, editor, onEvent) }
            key.startsWith("cell-") -> {
                val cellSelection = editor.selection as? EditorSelection.Cell
                val cell = editor.selectedCell()
                if (cellSelection != null && cell != null) {
                    val address = CellAddress(cellSelection.row, cellSelection.cell)
                    PanelScroll { CellPanel(state, editor, address, cell, onEvent) }
                } else {
                    EmptyPanel(editor, onEvent)
                }
            }
            else -> EmptyPanel(editor, onEvent)
        }
    }
}

@Composable
private fun PanelScroll(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().zillitVerticalScroll(scroll).padding(16.dp), content = content)
        ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

/** The panel's top: an eyebrow, a title, and the close cross. */
@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val colors = ReportTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PaneEyebrow(str(S.desktop_editing))
                Text(
                    title,
                    style = reportText(14.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            trailing?.invoke()
            CloseSquare(onClose, str(S.desktop_close_editor))
        }
        Divider()
    }
}

// Undo ---------------------------------------------------------------------------------------------

/** "Removed …  Undo" over a draining bar; hovering pauses the countdown. */
@Composable
internal fun UndoBar(undo: UndoRecord, onEvent: (ReportEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReportTheme.colors
    val progress = remember(undo.serial) { Animatable(0f) }
    val (source, hovered) = rememberHover()
    LaunchedEffect(undo.serial, hovered) {
        if (hovered) return@LaunchedEffect
        val remaining = ((1f - progress.value) * UNDO_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        onEvent(EditorEvent.ExpireUndo(undo.serial))
    }
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(colors.undoBg)
            .hoverable(source)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append(str(S.removed) + " ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(undo.label) }
                },
                style = reportText(12.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val (undoSource, undoHovered) = rememberHover()
            Text(
                str(S.dd_rt_undo),
                style = reportText(12.sp, FontWeight.SemiBold),
                color = if (undoHovered) Color(0xFFFDB022) else colors.accent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .hoverable(undoSource)
                    .plainClick(source = undoSource) { onEvent(EditorEvent.Undo) },
            )
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFF344054))) {
            Box(Modifier.fillMaxWidth(1f - progress.value).fillMaxHeight().clip(CircleShape).background(colors.accent))
        }
    }
}

private const val UNDO_MS = 3000

// Sections list ----------------------------------------------------------------------------------------

/**
 * "Sections": the header and approvers blocks among the rows, each card
 * dragged by its handle; page breaks stay put. Search filters what shows,
 * never what can move.
 */
@Composable
private fun SectionsList(editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val document = editor.document
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    str(S.desktop_sections_upper),
                    style = reportText(12.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                CloseSquare({ onEvent(EditorEvent.HideSections) }, str(S.desktop_hide_sidebar))
            }
            Box(Modifier.padding(bottom = 12.dp)) {
                val undo = editor.undo
                if (undo != null) {
                    UndoBar(undo, onEvent)
                } else {
                    ReportInput(
                        editor.sectionSearch,
                        { onEvent(EditorEvent.SetSectionSearch(it)) },
                        Modifier.fillMaxWidth(),
                        placeholder = str(S.desktop_search_sections),
                        leadingIcon = ZillitIcons.Search,
                        textStyle = reportText(12.sp),
                    )
                }
            }
        }
        Divider()
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().zillitVerticalScroll(scroll).padding(12.dp)) {
                SortableBlocks(editor, onEvent)
                if (editor.removedDefaults.isNotEmpty()) RestoreFields(editor, onEvent)
                if (document.rows.isEmpty()) {
                    Text(
                        str(S.desktop_no_sections_yet),
                        style = reportText(12.sp),
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private fun SectionBlock.matches(query: String, editor: EditorState): Boolean {
    if (query.isEmpty()) return true
    return when (this) {
        SectionBlock.Header -> "header".contains(query)
        SectionBlock.Approvers -> "approvers".contains(query)
        is SectionBlock.Row ->
            editor.document.rows.getOrNull(index)?.cells?.any { it.title.lowercase().contains(query) } == true
    }
}

@Composable
private fun SortableBlocks(editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val document = editor.document
    val query = editor.sectionSearch.trim().lowercase()
    val blocks = document.sectionBlocks().filter { it.matches(query, editor) }
    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val movable by rememberUpdatedState(
        blocks
            .filterNot { it is SectionBlock.Row && document.rows.getOrNull(it.index)?.isPageBreak == true }
            .map { it.key },
    )

    fun target(): String? {
        val key = dragging ?: return null
        val (top, bottom) = bounds[key] ?: return null
        val centre = (top + bottom) / 2 + offset
        return movable.minByOrNull { candidate ->
            val (t, b) = bounds[candidate] ?: return@minByOrNull Float.MAX_VALUE
            if (centre in t..b) 0f else minOf(abs(centre - t), abs(centre - b))
        }
    }

    val dropTarget = target()
    val order = blocks.map { it.key }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            val key = block.key
            val isDragged = dragging == key
            val indicator = when {
                dragging == null || dropTarget == null || dropTarget == dragging || dropTarget != key -> null
                order.indexOf(key) < order.indexOf(dragging) -> DropEdge.Top
                else -> DropEdge.Bottom
            }
            val row = (block as? SectionBlock.Row)?.let { document.rows.getOrNull(it.index) }
            val cardModifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val y = coords.positionInParent().y
                    bounds[key] = y to y + coords.size.height
                }
                .zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer {
                    translationY = if (isDragged) offset else 0f
                    shadowElevation = if (isDragged) 12f else 0f
                    alpha = if (isDragged) 0.92f else 1f
                }
                .dropEdge(indicator, ReportTheme.colors.accent)
            val handle = Modifier.pointerInput(key) {
                detectDragGestures(
                    onDragStart = {
                        dragging = key
                        offset = 0f
                    },
                    onDragEnd = {
                        val to = target()
                        if (to != null && to != key) onEvent(DocumentEvent.MoveBlock(key, to))
                        dragging = null
                        offset = 0f
                    },
                    onDragCancel = {
                        dragging = null
                        offset = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    offset += amount.y
                }
            }
            when {
                block == SectionBlock.Header -> BlockCard(
                    label = str(S.desktop_header),
                    active = editor.selection == EditorSelection.Shared,
                    handle = handle,
                    modifier = cardModifier,
                ) {
                    VirtualLine(
                        str(S.desktop_header),
                        str(S.desktop_pr_title_bar),
                        editor.selection == EditorSelection.Shared,
                    ) { onEvent(EditorEvent.Select(EditorSelection.Shared)) }
                }
                block == SectionBlock.Approvers -> BlockCard(
                    label = str(S.desktop_approvers),
                    active = editor.selection == EditorSelection.Approvers,
                    handle = handle,
                    modifier = cardModifier,
                ) {
                    VirtualLine(
                        str(S.desktop_approvers),
                        "${document.shared.approverIds.size} selected",
                        editor.selection == EditorSelection.Approvers,
                    ) {
                        onEvent(EditorEvent.Select(EditorSelection.Approvers))
                    }
                }
                row != null && row.isPageBreak -> BreakLine(Modifier.onGloballyPositioned { coords ->
                    val y = coords.positionInParent().y
                    bounds[key] = y to y + coords.size.height
                }) { onEvent(DocumentEvent.RemoveRow((block as SectionBlock.Row).index)) }
                row != null -> {
                    val index = (block as SectionBlock.Row).index
                    val selected = editor.selection as? EditorSelection.Cell
                    BlockCard(
                        label = "Row ${index + 1}",
                        active = selected?.row == index,
                        handle = handle,
                        modifier = cardModifier,
                        onRemove = { onEvent(DocumentEvent.RemoveRow(index)) },
                    ) {
                        row.cells.forEachIndexed { cellIndex, cell ->
                            CellLine(
                                cell,
                                selected = selected?.row == index && selected.cell == cellIndex,
                                first = cellIndex == 0,
                                onSelect = { onEvent(EditorEvent.Select(EditorSelection.Cell(index, cellIndex))) },
                                onRemove = { onEvent(DocumentEvent.RemoveCell(index, cellIndex)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class DropEdge { Top, Bottom }

private fun Modifier.dropEdge(edge: DropEdge?, color: Color): Modifier = if (edge == null) {
    this
} else {
    drawBehind {
        val stroke = 3.dp.toPx()
        val gap = 6.dp.toPx()
        val y = if (edge == DropEdge.Top) -gap else size.height + gap - stroke
        drawRect(color, Offset(0f, y), Size(size.width, stroke))
    }
}

/** A draggable card: handle, label, remove on hover, and its lines. */
@Composable
private fun BlockCard(
    label: String,
    active: Boolean,
    handle: Modifier,
    modifier: Modifier,
    onRemove: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    val lift by animateFloatAsState(if (hovered && !active) -1f else 0f, tween(200))
    Column(
        modifier
            .graphicsLayer { translationY += lift * density }
            .shadow(if (hovered) 4.dp else 0.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> colors.accentLight
                    hovered -> colors.surface
                    else -> colors.elevated
                },
            )
            .border(
                1.dp,
                if (active) colors.accent else if (hovered) colors.borderStrong else colors.border,
                RoundedCornerShape(8.dp),
            )
            .hoverable(source)
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val (handleSource, handleHovered) = rememberHover()
            ZillitTooltip(str(S.dd_cd_drag_handle)) {
                Icon(
                    ReportIcons.DragHandle,
                    contentDescription = str(S.dd_cd_drag_handle),
                    tint = if (handleHovered) colors.accent else colors.textMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .hoverable(handleSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(handle),
                )
            }
            Text(label, style = reportText(12.sp), color = colors.textMeta, modifier = Modifier.weight(1f))
            if (onRemove != null) {
                ZillitTooltip(str(S.desktop_remove_row)) {
                    Icon(
                        ZillitIcons.Close,
                        contentDescription = str(S.desktop_remove_row),
                        tint = colors.red,
                        modifier = Modifier.size(12.dp).alpha(if (hovered) 1f else 0f).plainClick(onClick = onRemove),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, colors.border, RoundedCornerShape(4.dp)),
            content = content,
        )
    }
}

@Composable
private fun VirtualLine(title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentLight else if (hovered) colors.hover else colors.surface)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            title,
            style = reportText(12.sp, if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = if (active) colors.chipOnText else colors.textPrimary,
        )
        Text(
            subtitle,
            style = reportText(12.sp),
            color = if (active) colors.chipOnText.copy(alpha = 0.7f) else colors.textMeta,
        )
    }
}

@Composable
private fun CellLine(cell: PageCell, selected: Boolean, first: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.rules(colors.border, top = true))
            .background(
                when {
                    selected -> if (colors.isDark) colors.accent.copy(alpha = 0.16f) else Color(0xFFFEF3CD)
                    hovered -> colors.hover
                    else -> colors.surface
                },
            )
            .hoverable(source),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).plainClick(onClick = onSelect).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                cell.title.ifBlank { str(S.untitled) },
                style = reportText(12.sp, if (selected) FontWeight.SemiBold else FontWeight.Medium),
                color = if (selected) colors.chipOnText else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${cell.rawKind.ifBlank { cell.kind.wire }} • ${cell.columns.size} cols • ${cell.rows.size} rows",
                style = reportText(12.sp),
                color = if (selected) colors.chipOnText.copy(alpha = 0.7f) else colors.textMeta,
                maxLines = 1,
            )
        }
        ZillitTooltip(str(S.desktop_remove_cell)) {
            Box(
                Modifier.padding(horizontal = 6.dp).alpha(if (hovered) 1f else 0f).plainClick(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ZillitIcons.Close,
                    contentDescription = str(S.desktop_remove_cell),
                    tint = colors.red,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun BreakLine(modifier: Modifier, onRemove: () -> Unit) {
    val accent = ReportTheme.colors.accent
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashedRule(Modifier.weight(1f), accent)
        Text(str(S.desktop_page_break_upper), style = reportText(10.sp, FontWeight.SemiBold), color = accent)
        DashedRule(Modifier.weight(1f), accent)
        ZillitTooltip(str(S.desktop_remove_page_break)) {
            Icon(
                ZillitIcons.Close,
                contentDescription = str(S.desktop_remove_page_break),
                tint = ReportTheme.colors.red,
                modifier = Modifier.size(11.dp).plainClick(onClick = onRemove),
            )
        }
    }
}

@Composable
private fun DashedRule(modifier: Modifier, color: Color) {
    Box(
        modifier.height(2.dp).drawBehind {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
            )
        },
    )
}

@Composable
private fun RestoreFields(editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Divider()
        Text(
            str(S.desktop_restore_fields_upper),
            style = reportText(12.sp, FontWeight.SemiBold),
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        editor.removedDefaults.forEachIndexed { index, removed ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.elevated)
                    .drawBehind {
                        drawRoundRect(
                            colors.borderStrong,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                            ),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        removed.cell.title.ifBlank { str(S.untitled) },
                        style = reportText(12.sp, FontWeight.Medium),
                        color = colors.textMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        removed.cell.rawKind.ifBlank { removed.cell.kind.wire },
                        style = reportText(12.sp),
                        color = colors.textMuted,
                    )
                }
                Text(
                    str(S.drive_restore),
                    style = reportText(12.sp, FontWeight.Medium),
                    color = colors.accent,
                    modifier = Modifier.padding(start = 8.dp).plainClick { onEvent(EditorEvent.RestoreDefault(index)) },
                )
            }
        }
    }
}

// Empty -------------------------------------------------------------------------------------------------

@Composable
private fun EmptyPanel(editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        editor.undo?.let { UndoBar(it, onEvent, Modifier.align(Alignment.TopCenter)) }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(colors.sunken), contentAlignment = Alignment.Center) {
                Icon(
                    ReportIcons.Pencil,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                str(S.desktop_no_section_selected),
                style = reportText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                str(S.desktop_click_section_to_edit),
                style = reportText(12.sp, lineHeight = 18.sp),
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 220.dp).padding(bottom = 20.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportButton(
                    str(S.desktop_sections),
                    { onEvent(EditorEvent.ShowSections) },
                    kind = ButtonKind.Outline,
                    icon = ReportIcons.Table,
                    height = 32.dp,
                    fontSize = 12.sp,
                )
                ReportButton(
                    str(S.desktop_close_editor_title),
                    { onEvent(EditorEvent.ClosePane) },
                    kind = ButtonKind.Outline,
                    height = 32.dp,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// Header info --------------------------------------------------------------------------------------------

/** "Production Report Info": shoot day, total days, date and day type. */
@Composable
private fun SharedPanel(editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val shared = editor.document.shared
    PanelHeader(str(S.desktop_pr_info_title), onClose = { onEvent(EditorEvent.Select(null)) })
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                PaneLabel(str(S.pr_shoot_day))
                ReportInput(shared.shootDayNumber, { onEvent(DocumentEvent.SetShootDay(it)) }, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f)) {
                PaneLabel(str(S.desktop_total_days))
                ReportInput(shared.totalDays, { onEvent(DocumentEvent.SetTotalDays(it)) }, Modifier.fillMaxWidth())
            }
        }
        Column {
            PaneLabel(str(S.date))
            DateInput(
                shared.dateYmd,
                { if (it.isNotEmpty()) onEvent(DocumentEvent.SetDate(it)) },
                Modifier.fillMaxWidth(),
                placeholder = "DD/MM/YYYY",
                boxed = true,
                clearable = false,
            )
            Text(
                str(S.desktop_pr_date_clears_weather),
                style = reportText(11.sp, lineHeight = 15.sp),
                color = colors.textMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column {
            PaneLabel(str(S.desktop_day_type))
            var newType by remember { mutableStateOf("") }
            val types = (editor.dayTypes + shared.dayType).filter { it.isNotBlank() }.distinct()
            val add = {
                if (newType.isNotBlank()) {
                    onEvent(DocumentEvent.AddDayType(newType.trim()))
                    newType = ""
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaneSelect(
                    shared.dayType,
                    listOf("" to str(S.select)) + types.map { it to it },
                    { onEvent(DocumentEvent.SetDayType(it)) },
                    Modifier.weight(1f),
                )
                ReportInput(
                    newType,
                    { newType = it },
                    Modifier.width(96.dp),
                    placeholder = str(S.continue_new),
                    onEnter = add,
                )
                ReportButton(
                    str(S.add),
                    add,
                    kind = ButtonKind.Ghost,
                    icon = ZillitIcons.Add,
                    enabled = newType.isNotBlank(),
                    height = 36.dp,
                    horizontalPadding = 8.dp,
                )
            }
        }
    }
}

// Approvers -----------------------------------------------------------------------------------------------

/**
 * `ApproverEditor`: chips for the chosen (three, then "+n more"), an add
 * list of every accepted member but you, and Save Approvers — which makes the
 * list the project's default for every report.
 */
@Composable
private fun ApproversPanel(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val ids = editor.document.shared.approverIds
    val chosen = ids.mapNotNull { id -> state.member(id) }
    var adding by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    PanelHeader(
        str(S.desktop_approvers),
        onClose = { onEvent(EditorEvent.Select(null)) },
        trailing = {
            if (editor.approversDirty && ids.isNotEmpty()) {
                ReportButton(
                    if (editor.savingApprovers) str(S.ah_saving) else str(S.desktop_save_approvers),
                    { onEvent(DocumentEvent.SaveApprovers) },
                    Modifier.padding(end = 8.dp),
                    kind = ButtonKind.Accent,
                    enabled = !editor.savingApprovers,
                    height = 30.dp,
                    fontSize = 12.sp,
                    radius = 12.dp,
                )
            }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (chosen.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chosen.take(VISIBLE_CHIPS).forEach { member ->
                    PersonChip(member) { onEvent(DocumentEvent.ToggleApprover(member.userId)) }
                }
                if (chosen.size > VISIBLE_CHIPS) {
                    HoverCard(
                        title = str(S.desktop_approvers),
                        trigger = {
                            Text(
                                "+${chosen.size - VISIBLE_CHIPS} more",
                                style = reportText(12.sp, FontWeight.SemiBold),
                                color = colors.accent,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(colors.accentLight)
                                    .border(1.dp, colors.accent, CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        },
                    ) {
                        Column(Modifier.width(260.dp)) {
                            chosen.drop(VISIBLE_CHIPS).forEach { member ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Face(member.userId, member.fullName, 28.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            member.fullName,
                                            style = reportText(12.sp, FontWeight.Medium),
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (member.designation.isNotBlank()) Text(
                                            member.designation,
                                            style = reportText(10.sp),
                                            color = colors.textTertiary,
                                            maxLines = 1,
                                        )
                                    }
                                    ZillitTooltip(str(S.desktop_remove_approver)) {
                                        Icon(
                                            ZillitIcons.Close,
                                            contentDescription = str(S.desktop_remove_approver),
                                            tint = colors.red,
                                            modifier = Modifier
                                                .size(11.dp)
                                                .plainClick { onEvent(DocumentEvent.ToggleApprover(member.userId)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        when {
            adding -> AddApproverList(state, ids, search, { search = it }, onClose = {
                adding = false
                search = ""
            }) { onEvent(DocumentEvent.ToggleApprover(it)) }
            chosen.isEmpty() -> NoApproversCard { adding = true }
            else -> ReportButton(
                str(S.desktop_add_approver),
                { adding = true },
                kind = ButtonKind.Warning,
                icon = ZillitIcons.Add,
                height = 30.dp,
                fontSize = 12.sp,
            )
        }
    }
}

private const val VISIBLE_CHIPS = 3

@Composable
private fun NoApproversCard(onAdd: () -> Unit) {
    val colors = ReportTheme.colors
    val dash = colors.border
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .drawBehind {
                drawRoundRect(
                    dash,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                    ),
                )
            }
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accentLight)
                .border(1.dp, Color(0xFFFED7AA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZillitIcons.UserPlus, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Text(
            str(S.desktop_no_approvers_yet_plain),
            style = reportText(14.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            str(S.desktop_pr_add_approvers_hint),
            style = reportText(12.sp, lineHeight = 18.sp),
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp).padding(bottom = 16.dp),
        )
        ReportButton(
            str(S.desktop_add_approver),
            onAdd,
            kind = ButtonKind.Accent,
            icon = ZillitIcons.Add,
            height = 30.dp,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AddApproverList(
    state: ReportUiState,
    ids: List<String>,
    search: String,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val colors = ReportTheme.colors
    val query = search.trim().lowercase()
    val candidates = approverCandidates(state.members, ids, state.me)
        .filter { query.isEmpty() || listOf(
            it.fullName,
            it.designation,
            it.department,
        ).any { field -> field.lowercase().contains(query) } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                str(S.desktop_select_members_to_add),
                style = reportText(14.sp, FontWeight.Medium),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                str(S.close),
                style = reportText(12.sp, FontWeight.Medium),
                color = colors.textMeta,
                modifier = Modifier.plainClick(onClick = onClose),
            )
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ReportInput(
                search,
                onSearch,
                Modifier.fillMaxWidth(),
                placeholder = str(S.desktop_search_by_name_role_department),
                autoFocus = true,
                leadingIcon = ZillitIcons.Search,
            )
        }
        Divider()
        if (candidates.isEmpty()) {
            Text(
                if (query.isNotEmpty()) {
                    str(S.desktop_no_members_match_search)
                } else {
                    str(S.desktop_all_members_already_approvers)
                },
                style = reportText(14.sp),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }
        candidates.forEachIndexed { index, member ->
            val (source, hovered) = rememberHover()
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (index > 0) Modifier.rules(colors.borderFaint, top = true) else Modifier)
                    .background(if (hovered) colors.accentLight else Color.Transparent)
                    .hoverable(source)
                    .plainClick(source = source) { onAdd(member.userId) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Face(member.userId, member.fullName, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        member.fullName,
                        style = reportText(14.sp, FontWeight.Medium),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildAnnotatedString {
                            append(member.designation)
                            if (member.department.isNotBlank()) {
                                withStyle(SpanStyle(color = colors.textMuted)) {
                                    append(
                                        if (member.designation.isNotBlank()) {
                                            " · ${member.department}"
                                        } else {
                                            member.department
                                        },
                                    )
                                }
                            }
                        },
                        style = reportText(12.sp),
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("+ Add", style = reportText(14.sp, FontWeight.Medium), color = colors.accent)
            }
        }
    }
}

// Section panel -----------------------------------------------------------------------------------------------

/**
 * The selected section: visibility, remove, title (required on system
 * sections), type and header orientation, then its grid. Crew and weather
 * sections keep their type — the web let a switch strand them.
 */
@Composable
private fun CellPanel(
    state: ReportUiState,
    editor: EditorState,
    address: CellAddress,
    cell: PageCell,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneEyebrow(str(S.desktop_editing))
            if (cell.renderAs != RenderKind.Employee && !cell.systemDefault) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (cell.hideTitle) str(S.desktop_hidden) else str(S.desktop_visible),
                        style = reportText(11.sp),
                        color = colors.textTertiary,
                    )
                    ReportSwitch(
                        !cell.hideTitle,
                        { visible -> onEvent(DocumentEvent.SetHideTitle(address.row, address.cell, !visible)) },
                    )
                }
            }
            Box(Modifier.weight(1f))
            ReportButton(
                str(S.remove),
                { onEvent(DocumentEvent.RemoveCell(address.row, address.cell)) },
                kind = ButtonKind.DangerOutline,
                icon = ZillitIcons.Trash,
                height = 26.dp,
                fontSize = 11.sp,
                horizontalPadding = 10.dp,
            )
            CloseSquare({ onEvent(EditorEvent.Select(null)) }, str(S.desktop_close_editor))
        }
        val required = cell.systemDefault
        val missing = required && cell.title.isBlank()
        ReportInput(
            cell.title,
            { onEvent(DocumentEvent.SetTitle(address.row, address.cell, it)) },
            Modifier.fillMaxWidth(),
            placeholder = if (required) str(S.desktop_section_title_required) else str(S.desktop_section_title),
            autoFocus = true,
            error = missing,
            textStyle = reportText(14.sp, FontWeight.SemiBold),
        )
        if (missing) Text(
            str(S.desktop_section_name_required_for_defaults),
            style = reportText(11.sp),
            color = colors.red,
            modifier = Modifier.padding(top = 4.dp),
        )
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { Divider() }
    }
    editor.undo?.let { UndoBar(it, onEvent, Modifier.padding(bottom = 16.dp)) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PaneEyebrow(str(S.type), strong = true)
            val fixed = cell.renderAs != RenderKind.Generic
            ZillitTooltip(
                if (fixed) str(S.desktop_pr_fixed_section_type_hint) else str(S.desktop_change_section_layout_hint),
            ) {
                PaneSelect(
                    cell.kind.wire.ifBlank { "section" },
                    listOf(
                        "section" to str(S.desktop_section),
                        "table" to str(S.desktop_table),
                        "notes" to str(S.notes),
                    ),
                    { onEvent(DocumentEvent.SetKind(address.row, address.cell, CellKind.fromWire(it))) },
                    Modifier.width(170.dp),
                    enabled = !fixed,
                )
            }
        }
        // Crew and weather sections draw no header row, so orientation would change nothing there.
        if (cell.kind != CellKind.Notes && cell.renderAs == RenderKind.Generic) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PaneEyebrow(str(S.desktop_header_orientation), strong = true)
                Segmented(
                    options = listOf(false to str(S.desktop_horizontal), true to str(S.desktop_vertical)),
                    selected = cell.isVerticalHeader,
                    onSelect = { onEvent(DocumentEvent.SetVertical(address.row, address.cell, it)) },
                    solidSelection = false,
                )
            }
        }
    }
    when {
        cell.renderAs == RenderKind.Weather -> WeatherEditor(
            raw = cell.rows.firstOrNull()?.values?.firstOrNull()?.value.orEmpty(),
            address = address,
            shootYmd = editor.document.shared.dateYmd,
            panel = editor.weather,
            nowMillis = Clock.System.now().toEpochMilliseconds(),
            onEvent = onEvent,
        )
        cell.kind == CellKind.Section -> SectionGridEditor(cell, address, state.members, onEvent)
        cell.kind == CellKind.Notes -> NotesEditor(cell, address, onEvent)
        cell.renderAs == RenderKind.Employee -> CrewEditor(cell, address, state.members, onEvent)
        else -> TableEditor(cell, address, state.members, onEvent)
    }
}
