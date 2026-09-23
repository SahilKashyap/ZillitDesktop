// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")

package com.zillit.desktop.feature.callsheet.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.style.TextDecoration
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
import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SectionBlock
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.approverCandidates
import com.zillit.desktop.feature.callsheet.domain.sectionBlocks
import com.zillit.desktop.feature.callsheet.ui.DocumentEvent
import com.zillit.desktop.feature.callsheet.ui.EditorEvent
import com.zillit.desktop.feature.callsheet.ui.EditorState
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.UndoRecord
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.HoverCard
import com.zillit.desktop.feature.callsheet.ui.components.Segmented
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetInput
import com.zillit.desktop.feature.callsheet.ui.components.SheetSwitch
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.delay

/**
 * The editing pane — `PageRowsEditor.jsx`: the Sections list, or the panel
 * for what is selected (Call Sheet Info, approvers, one section), or the
 * empty state. Focus leaving the pane clears the preview's line highlight.
 */
@Composable
internal fun EditorPane(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val selection = editor.selection
    val paneKey = when {
        selection == null && editor.sidebarVisible -> "sections"
        selection == EditorSelection.Shared -> "shared"
        selection == EditorSelection.Approvers -> "approvers"
        selection is EditorSelection.Cell && editor.selectedCell() != null -> "cell-${selection.row}-${selection.cell}"
        else -> "empty"
    }
    val focusedLine by rememberUpdatedState(editor.focusedLine)
    AnimatedContent(
        targetState = paneKey,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 40 }) togetherWith fadeOut(tween(120))
        },
        modifier = Modifier
            .fillMaxSize()
            .background(colors.editorBg)
            .onFocusChanged { if (!it.hasFocus && focusedLine != null) onEvent(EditorEvent.Focus(null, null)) },
    ) { key ->
        when {
            key == "sections" -> SectionsList(editor, onEvent)
            key == "shared" -> PanelScroll { SharedPanel(editor, onEvent) }
            key == "approvers" -> PanelScroll { ApproversPanel(state, editor, onEvent) }
            key.startsWith("cell-") -> {
                val cellSelection = editor.selection as? EditorSelection.Cell
                val cell = editor.selectedCell()
                if (cellSelection != null && cell != null) {
                    PanelScroll { CellPanel(
                        state,
                        editor,
                        CellAddress(cellSelection.row, cellSelection.cell),
                        cell,
                        onEvent,
                    ) }
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

/** The panel's top: the "Editing" eyebrow over a title, anything [trailing], and the close square. */
@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val colors = SheetTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PaneEyebrow(str(S.desktop_editing))
                Text(
                    title,
                    style = sheetText(14.sp, FontWeight.SemiBold),
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
internal fun UndoBar(undo: UndoRecord, onEvent: (SheetEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = SheetTheme.colors
    val progress = remember(undo.serial) { Animatable(0f) }
    val (source, hovered) = rememberHover()
    LaunchedEffect(undo.serial, hovered) {
        if (hovered) return@LaunchedEffect
        val remaining = ((1f - progress.value) * EditorState.UNDO_MILLIS).toInt().coerceAtLeast(1)
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
                    append(str(S.removed))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(undo.label) }
                },
                style = sheetText(12.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val (undoSource, undoHovered) = rememberHover()
            Text(
                str(S.dd_rt_undo),
                style = sheetText(12.sp, FontWeight.SemiBold),
                color = if (undoHovered) UNDO_HOVER else colors.accent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .hoverable(undoSource)
                    .plainClick(source = undoSource) { onEvent(EditorEvent.Undo) },
            )
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFF344054))) {
            Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().clip(CircleShape).background(colors.accent))
        }
    }
}

private val UNDO_HOVER = Color(0xFFFDB022)

/**
 * The cell editor's "Row removed" / "Column removed" toast, bottom right,
 * with UNDO for three seconds after the latest removal.
 */
@Composable
internal fun QuickUndoToast(undo: UndoRecord, onEvent: (SheetEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = SheetTheme.colors
    LaunchedEffect(undo.serial) {
        delay(EditorState.UNDO_MILLIS)
        onEvent(EditorEvent.ExpireQuickUndo(undo.serial))
    }
    Row(
        modifier
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1D2939))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                ZillitIcons.Check,
                contentDescription = null,
                tint = Color(0xFF12B76A),
                modifier = Modifier.size(12.dp),
            )
            Text(undo.label, style = sheetText(12.sp, FontWeight.Medium), color = Color.White)
        }
        val (source, hovered) = rememberHover()
        Text(
            str(S.desktop_undo_upper),
            style = sheetText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.3.sp),
            color = if (hovered) UNDO_HOVER else colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .hoverable(source)
                .plainClick(source = source) { onEvent(EditorEvent.QuickUndo) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// Sections list ----------------------------------------------------------------------------------------

/**
 * "Sections": the header and approvers blocks among the rows, each card
 * dragged by its handle; page breaks stay put. Search filters what shows,
 * never what can move.
 */
@Composable
private fun SectionsList(editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    str(S.desktop_sections_upper),
                    style = sheetText(12.sp, FontWeight.SemiBold).copy(letterSpacing = 0.6.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                CloseMark(str(S.desktop_hide_sidebar)) { onEvent(EditorEvent.HideSections) }
            }
            Box(Modifier.padding(bottom = 12.dp)) {
                val undo = editor.undo
                if (undo != null) {
                    UndoBar(undo, onEvent)
                } else {
                    Box {
                        SheetInput(
                            editor.sectionSearch,
                            { onEvent(EditorEvent.SetSectionSearch(it)) },
                            Modifier.fillMaxWidth(),
                            placeholder = str(S.desktop_search_sections),
                            leadingIcon = ZillitIcons.Search,
                            textStyle = sheetText(12.sp),
                        )
                        if (editor.sectionSearch.isNotEmpty()) {
                            Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) {
                                CloseMark(
                                    str(S.ah_cd_clear_search),
                                    size = 9,
                                ) { onEvent(EditorEvent.SetSectionSearch("")) }
                            }
                        }
                    }
                }
            }
        }
        Divider()
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().zillitVerticalScroll(scroll).padding(12.dp)) {
                SortableBlocks(editor, onEvent)
                if (editor.removedDefaults.isNotEmpty()) RestoreFields(editor, onEvent)
            }
            ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** A small ×: muted, darkening on hover. */
@Composable
private fun CloseMark(description: String, size: Int = 12, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(description) {
        Box(
            Modifier
                .size((size + 12).dp)
                .clip(RoundedCornerShape(4.dp))
                .hoverable(source)
                .plainClick(source = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.Close,
                contentDescription = description,
                tint = if (hovered) colors.textPrimary else colors.textMuted,
                modifier = Modifier.size(size.dp),
            )
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
private fun SortableBlocks(editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
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

    if (blocks.isEmpty()) {
        Text(
            if (query.isEmpty()) str(S.desktop_no_sections_yet) else str(S.desktop_no_sections_match_search),
            style = sheetText(12.sp),
            color = colors.textMuted,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val dropTarget = target()
    val order = blocks.map { it.key }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            val key = block.key
            val isDragged = dragging == key
            val indicator = when {
                dragging == null || dropTarget == null || dropTarget == dragging || dropTarget != key -> null
                order.indexOf(key) < order.indexOf(dragging) -> DropEdge.Top
                else -> DropEdge.Bottom
            }
            val row = (block as? SectionBlock.Row)?.let { document.rows.getOrNull(it.index) }
            val measured = Modifier.onGloballyPositioned { coords ->
                val y = coords.positionInParent().y
                bounds[key] = y to y + coords.size.height
            }
            val cardModifier = Modifier
                .fillMaxWidth()
                .then(measured)
                .zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer {
                    translationY = if (isDragged) offset else 0f
                    shadowElevation = if (isDragged) 12f else 0f
                    alpha = if (isDragged) 0.85f else 1f
                }
                .dropEdge(indicator, colors.accent)
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
                        str(S.desktop_cs_title_bar),
                        editor.selection == EditorSelection.Shared,
                    ) {
                        onEvent(EditorEvent.Select(EditorSelection.Shared))
                    }
                }
                block == SectionBlock.Approvers -> BlockCard(
                    label = str(S.desktop_approvers),
                    active = editor.selection == EditorSelection.Approvers,
                    handle = handle,
                    modifier = cardModifier,
                ) {
                    VirtualLine(
                        str(S.desktop_approvers),
                        str(S.dd_n_selected, document.shared.approverIds.size),
                        editor.selection == EditorSelection.Approvers,
                    ) { onEvent(EditorEvent.Select(EditorSelection.Approvers)) }
                }
                row != null && row.isPageBreak -> BreakLine(measured) {
                    onEvent(DocumentEvent.RemoveRow((block as SectionBlock.Row).index))
                }
                row != null -> {
                    val index = (block as SectionBlock.Row).index
                    val selected = editor.selection as? EditorSelection.Cell
                    BlockCard(
                        label = str(S.desktop_row_n, index + 1),
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
        val gap = 7.dp.toPx()
        val y = if (edge == DropEdge.Top) -gap else size.height + gap - stroke
        drawRect(color, Offset(0f, y), Size(size.width, stroke))
    }
}

/** A draggable card: the red handle, its label, remove on hover, and its lines. */
@Composable
private fun BlockCard(
    label: String,
    active: Boolean,
    handle: Modifier,
    modifier: Modifier,
    onRemove: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.accentLight else colors.elevated)
            .border(1.dp, if (active) colors.accent else colors.border, RoundedCornerShape(8.dp))
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
                    SheetIcons.DragHandle,
                    contentDescription = str(S.dd_cd_drag_handle),
                    tint = if (handleHovered) Color(0xFF991B1B) else colors.red,
                    modifier = Modifier
                        .size(14.dp)
                        .hoverable(handleSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(handle),
                )
            }
            Text(label, style = sheetText(12.sp), color = colors.textMeta, modifier = Modifier.weight(1f))
            if (onRemove != null) {
                ZillitTooltip(str(S.desktop_remove_row)) {
                    Icon(
                        ZillitIcons.Close,
                        contentDescription = str(S.desktop_remove_row),
                        tint = colors.red,
                        modifier = Modifier.size(10.dp).alpha(if (hovered) 1f else 0f).plainClick(onClick = onRemove),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, if (active) colors.accent else colors.border, RoundedCornerShape(4.dp)),
            content = content,
        )
    }
}

@Composable
private fun VirtualLine(title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
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
            style = sheetText(12.sp, if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = if (active) colors.chipOnText else colors.textPrimary,
        )
        Text(
            subtitle,
            style = sheetText(12.sp),
            color = if (active) colors.chipOnText.copy(alpha = 0.7f) else colors.textMeta,
        )
    }
}

@Composable
private fun CellLine(cell: PageCell, selected: Boolean, first: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    val colors = SheetTheme.colors
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
                style = sheetText(12.sp, if (selected) FontWeight.SemiBold else FontWeight.Medium),
                color = if (selected) colors.chipOnText else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                str(
                    S.desktop_cs_cell_shape_summary,
                    cell.rawKind.ifBlank { cell.kind.wire.ifBlank { "section" } },
                    cell.columns.size,
                    cell.rows.size,
                ),
                style = sheetText(12.sp),
                color = if (selected) colors.chipOnText.copy(alpha = 0.7f) else colors.textMeta,
                maxLines = 1,
            )
        }
        ZillitTooltip(str(S.desktop_remove_cell)) {
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .alpha(if (hovered) 1f else 0f)
                    .plainClick(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ZillitIcons.Close,
                    contentDescription = str(S.desktop_remove_cell),
                    tint = colors.red,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun BreakLine(modifier: Modifier, onRemove: () -> Unit) {
    val accent = SheetTheme.colors.accent
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashedRule(Modifier.weight(1f), accent)
        Text(
            str(S.desktop_page_break_upper),
            style = sheetText(10.sp, FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
        )
        DashedRule(Modifier.weight(1f), accent)
        ZillitTooltip(str(S.desktop_remove_page_break)) {
            Icon(
                ZillitIcons.Close,
                contentDescription = str(S.desktop_remove_page_break),
                tint = SheetTheme.colors.red,
                modifier = Modifier.size(10.dp).plainClick(onClick = onRemove),
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

/** "Restore Fields": the system sections removed in this editing session. */
@Composable
private fun RestoreFields(editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Divider()
        Text(
            str(S.desktop_restore_fields_upper),
            style = sheetText(12.sp, FontWeight.SemiBold).copy(letterSpacing = 0.4.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        editor.removedDefaults.forEachIndexed { index, removed ->
            val dash = colors.borderStrong
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.elevated)
                    .drawBehind {
                        drawRoundRect(
                            dash,
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        removed.cell.title.ifBlank { str(S.untitled) },
                        style = sheetText(12.sp, FontWeight.Medium),
                        color = colors.textMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        removed.cell.rawKind.ifBlank { removed.cell.kind.wire.ifBlank { "section" } },
                        style = sheetText(12.sp),
                        color = colors.textMuted,
                    )
                }
                val (source, hovered) = rememberHover()
                Text(
                    str(S.drive_restore),
                    style = sheetText(12.sp, FontWeight.Medium)
                        .copy(textDecoration = if (hovered) TextDecoration.Underline else null),
                    color = colors.accent,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .hoverable(source)
                        .plainClick(source = source) { onEvent(EditorEvent.RestoreDefault(index)) },
                )
            }
        }
    }
}

// Empty -------------------------------------------------------------------------------------------------

@Composable
private fun EmptyPanel(editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        editor.undo?.let { UndoBar(it, onEvent, Modifier.align(Alignment.TopCenter)) }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(colors.sunken), contentAlignment = Alignment.Center) {
                Icon(
                    SheetIcons.Pencil,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                str(S.desktop_no_section_selected),
                style = sheetText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                str(S.desktop_click_section_to_edit),
                style = sheetText(12.sp, lineHeight = 19.sp),
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 220.dp).padding(bottom = 20.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton(
                    str(S.desktop_sections),
                    { onEvent(EditorEvent.ShowSections) },
                    kind = ButtonKind.Outline,
                    icon = SheetIcons.Table,
                    height = 34.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 16.dp,
                )
                SheetButton(
                    str(S.desktop_close_editor_title),
                    { onEvent(EditorEvent.ClosePane) },
                    kind = ButtonKind.Outline,
                    height = 34.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 16.dp,
                )
            }
        }
    }
}

// Call Sheet Info --------------------------------------------------------------------------------------------

/** "Call Sheet Info": shoot day, total days, the date and the day type. */
@Composable
private fun SharedPanel(editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val shared = editor.document.shared
    PanelHeader(str(S.cs_title_shared_info), onClose = { onEvent(EditorEvent.Select(null)) })
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                PaneLabel(str(S.cs_shoot_day))
                SheetInput(shared.shootDayNumber, { onEvent(DocumentEvent.SetShootDay(it)) }, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f)) {
                PaneLabel(str(S.desktop_total_days))
                SheetInput(shared.totalDays, { onEvent(DocumentEvent.SetTotalDays(it)) }, Modifier.fillMaxWidth())
            }
        }
        Column {
            PaneLabel(str(S.date))
            DateInput(
                shared.dateMs?.toString().orEmpty(),
                { picked -> picked?.let { onEvent(DocumentEvent.SetDate(it)) } },
                Modifier.fillMaxWidth(),
                placeholder = "DD/MM/YYYY",
                boxed = true,
                clearable = false,
            )
            if (editor.document.rows.any { row -> row.cells.any { it.renderAs == RenderKind.Weather } }) {
                Text(
                    str(S.desktop_cs_date_clears_weather),
                    style = sheetText(11.sp, lineHeight = 15.sp),
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Column {
            PaneLabel(str(S.desktop_day_type))
            var newType by remember { mutableStateOf("") }
            val types = (editor.dayTypes + shared.dayType).filter { it.isNotBlank() }.distinct()
            val add = {
                val name = newType.trim()
                if (name.isNotEmpty()) {
                    onEvent(DocumentEvent.AddDayType(name))
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
                SheetInput(
                    newType,
                    { newType = it },
                    Modifier.width(88.dp),
                    placeholder = str(S.continue_new),
                    radius = 4.dp,
                    onEnter = add,
                )
                SheetButton(
                    str(S.add),
                    add,
                    kind = ButtonKind.Ghost,
                    icon = ZillitIcons.Add,
                    enabled = newType.isNotBlank(),
                    height = 36.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 8.dp,
                )
            }
        }
    }
}

// Approvers -----------------------------------------------------------------------------------------------

/**
 * `ApproverEditor`: pills for the chosen (three, then "+n more"), an add
 * list of every accepted member but you, and Save Approvers — which makes the
 * list the project's default for every call sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApproversPanel(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val ids = editor.document.shared.approverIds
    val chosen = ids.mapNotNull { id -> state.member(id) }
    var adding by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    PanelHeader(
        str(S.desktop_approvers),
        onClose = { onEvent(EditorEvent.Select(null)) },
        trailing = {
            if (editor.approversDirty && ids.isNotEmpty()) {
                SheetButton(
                    if (editor.savingApprovers) str(S.ah_saving) else str(S.desktop_save_approvers),
                    { onEvent(DocumentEvent.SaveApprovers) },
                    Modifier.padding(end = 8.dp),
                    kind = ButtonKind.Accent,
                    enabled = !editor.savingApprovers,
                    height = 30.dp,
                    fontSize = 12.sp,
                    radius = 12.dp,
                    horizontalPadding = 12.dp,
                )
            }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (chosen.isNotEmpty()) {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chosen.take(VISIBLE_CHIPS).forEach { member ->
                    PersonChip(member) { onEvent(DocumentEvent.ToggleApprover(member.userId)) }
                }
                if (chosen.size > VISIBLE_CHIPS) {
                    MoreApprovers(chosen.drop(VISIBLE_CHIPS)) { onEvent(DocumentEvent.ToggleApprover(it)) }
                }
            }
        }
        when {
            adding -> AddApproverList(
                state = state,
                ids = ids,
                search = search,
                onSearch = { search = it },
                onClose = {
                    adding = false
                    search = ""
                },
            ) { onEvent(DocumentEvent.ToggleApprover(it)) }
            chosen.isEmpty() -> NoApproversCard { adding = true }
            else -> SheetButton(
                str(S.desktop_add_approver),
                { adding = true },
                kind = ButtonKind.Warning,
                icon = ZillitIcons.Add,
                height = 30.dp,
                fontSize = 12.sp,
                horizontalPadding = 10.dp,
            )
        }
    }
}

private const val VISIBLE_CHIPS = 3

/** "+n more": hovering lists the rest, each removable. */
@Composable
private fun MoreApprovers(
    rest: List<SheetMember>,
    onRemove: (String) -> Unit,
) {
    val colors = SheetTheme.colors
    HoverCard(
        title = str(S.desktop_approvers),
        trigger = {
            Text(
                str(S.desktop_n_more, rest.size),
                style = sheetText(12.sp, FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.accentLight)
                    .border(1.dp, colors.accent, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    ) {
        Column(Modifier.width(260.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            rest.forEach { member ->
                val (source, hovered) = rememberHover()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (hovered) colors.hover else Color.Transparent)
                        .hoverable(source)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Face(member.userId, member.fullName, 28.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            member.fullName,
                            style = sheetText(12.sp, FontWeight.Medium),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (member.designation.isNotBlank()) {
                            Text(
                                member.designation,
                                style = sheetText(10.sp),
                                color = colors.textTertiary,
                                maxLines = 1,
                            )
                        }
                    }
                    ZillitTooltip(str(S.desktop_remove_approver)) {
                        Icon(
                            ZillitIcons.Close,
                            contentDescription = str(S.desktop_remove_approver),
                            tint = colors.red,
                            modifier = Modifier.size(11.dp).plainClick { onRemove(member.userId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoApproversCard(onAdd: () -> Unit) {
    val colors = SheetTheme.colors
    val dash = colors.border
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .drawBehind {
                drawRoundRect(
                    dash,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
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
                .border(1.dp, if (colors.isDark) colors.accent.copy(alpha = 0.35f) else Color(0xFFFED7AA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SheetIcons.UsersAdd, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Text(
            str(S.desktop_no_approvers_yet),
            style = sheetText(14.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            str(S.desktop_cs_add_approvers_hint),
            style = sheetText(12.sp, lineHeight = 18.sp),
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp).padding(bottom = 16.dp),
        )
        SheetButton(
            str(S.desktop_add_approver),
            onAdd,
            kind = ButtonKind.Accent,
            icon = ZillitIcons.Add,
            height = 30.dp,
            fontSize = 12.sp,
            horizontalPadding = 12.dp,
        )
    }
}

/** "Select members to add": every accepted member not chosen and not you; the list stays open while adding. */
@Composable
private fun AddApproverList(
    state: SheetUiState,
    ids: List<String>,
    search: String,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val colors = SheetTheme.colors
    val query = search.trim().lowercase()
    val candidates = approverCandidates(state.members, ids, state.me).filter { member ->
        query.isEmpty() ||
            listOf(member.fullName, member.designation, member.department).any { it.lowercase().contains(query) }
    }
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
                style = sheetText(14.sp, FontWeight.Medium),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            val (source, hovered) = rememberHover()
            Text(
                str(S.close),
                style = sheetText(12.sp, FontWeight.Medium),
                color = if (hovered) colors.textPrimary else colors.textMeta,
                modifier = Modifier.hoverable(source).plainClick(source = source, onClick = onClose),
            )
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SheetInput(
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
                style = sheetText(14.sp),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
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
                            style = sheetText(14.sp, FontWeight.Medium),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        MemberRole(member, 12)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            ZillitIcons.Add,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(str(S.add), style = sheetText(14.sp, FontWeight.Medium), color = colors.accent)
                    }
                }
            }
        }
    }
}

// Section panel -----------------------------------------------------------------------------------------------

/**
 * The selected section: title visibility, Remove, the title (required on
 * system sections, read-only on a department), the Type and header
 * orientation, then the editor for its layout.
 */
@Composable
private fun CellPanel(
    state: SheetUiState,
    editor: EditorState,
    address: CellAddress,
    cell: PageCell,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val employee = cell.renderAs == RenderKind.Employee
    val weather = cell.renderAs == RenderKind.Weather
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneEyebrow(str(S.desktop_editing))
            if (!employee && !cell.systemDefault) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (cell.hideTitle) str(S.desktop_hidden) else str(S.desktop_visible),
                        style = sheetText(11.sp),
                        color = colors.textTertiary,
                        modifier = Modifier.plainClick {
                            onEvent(DocumentEvent.SetHideTitle(address.row, address.cell, !cell.hideTitle))
                        },
                    )
                    SheetSwitch(
                        !cell.hideTitle,
                        { visible -> onEvent(DocumentEvent.SetHideTitle(address.row, address.cell, !visible)) },
                    )
                }
            }
            Box(Modifier.weight(1f))
            SheetButton(
                str(S.remove),
                { onEvent(DocumentEvent.RemoveCell(address.row, address.cell, fromPane = true)) },
                kind = ButtonKind.DangerOutline,
                height = 26.dp,
                fontSize = 11.sp,
                horizontalPadding = 10.dp,
            )
            CloseSquare({ onEvent(EditorEvent.Select(null)) }, str(S.desktop_close_editor))
        }
        if (employee) {
            ReadOnlyTitle(cell.title.ifBlank { str(S.department) })
        } else {
            val missing = cell.systemDefault && cell.title.isBlank()
            SheetInput(
                cell.title,
                { onEvent(DocumentEvent.SetTitle(address.row, address.cell, it)) },
                Modifier.fillMaxWidth(),
                placeholder = if (cell.systemDefault) {
                    str(S.desktop_section_title_required)
                } else {
                    str(S.desktop_section_title)
                },
                autoFocus = true,
                error = missing,
                textStyle = sheetText(14.sp, FontWeight.SemiBold),
            )
            if (missing) {
                Text(
                    str(S.desktop_section_name_required_for_defaults),
                    style = sheetText(11.sp),
                    color = colors.red,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { Divider() }
    }
    editor.undo?.let { UndoBar(it, onEvent, Modifier.padding(bottom = 16.dp)) }
    val orientation = !weather && (cell.kind == CellKind.Section || (cell.kind == CellKind.Table && !employee))
    if (!weather || orientation) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!weather) {
                Column {
                    PaneLabel(str(S.type))
                    PaneSelect(
                        cell.kind.wire.ifBlank { "section" },
                        listOf(
                            "section" to str(S.desktop_section),
                            "table" to str(S.desktop_table),
                            "notes" to str(S.notes),
                        ),
                        { onEvent(DocumentEvent.SetKind(address.row, address.cell, CellKind.fromWire(it))) },
                        Modifier.width(170.dp),
                    )
                }
            }
            if (orientation) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PaneEyebrow(str(S.desktop_header_orientation), strong = true)
                    Segmented(
                        options = listOf(false to str(S.desktop_horizontal), true to str(S.desktop_vertical)),
                        selected = cell.isVerticalHeader,
                        onSelect = { onEvent(DocumentEvent.SetVertical(address.row, address.cell, it)) },
                        modifier = Modifier.padding(bottom = 2.dp),
                        solidSelection = false,
                    )
                }
            }
        }
    }
    val sheetDate = editor.document.shared.dateMs
    when {
        weather -> WeatherEditor(
            raw = cell.rows.firstOrNull()?.values?.firstOrNull()?.value.orEmpty(),
            address = address,
            shootDateMs = sheetDate,
            panel = editor.weather,
            nowMillis = Clock.System.now().toEpochMilliseconds(),
            onEvent = onEvent,
        )
        cell.kind == CellKind.Section -> SectionGridEditor(cell, address, state.members, sheetDate, onEvent)
        cell.kind == CellKind.Notes -> NotesEditor(cell, address, onEvent)
        cell.kind == CellKind.Table && employee -> CrewEditor(cell, address, state.members, sheetDate, onEvent)
        cell.kind == CellKind.Table -> TableEditor(cell, address, state.members, sheetDate, onEvent)
        else -> Text(
            str(S.desktop_layout_not_editable, cell.rawKind.ifBlank { str(S.desktop_unknown_lower) }),
            style = sheetText(12.sp, lineHeight = 18.sp),
            color = colors.textTertiary,
        )
    }
}

/** A department's name — managed by the project, so shown, not edited. */
@Composable
private fun ReadOnlyTitle(name: String) {
    val colors = SheetTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.elevated)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            style = sheetText(14.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ZillitTooltip(str(S.desktop_department_name_managed)) {
            Text(
                str(S.desktop_read_only_upper),
                style = sheetText(10.sp, FontWeight.Medium, 13.sp).copy(letterSpacing = 0.5.sp),
                color = colors.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
