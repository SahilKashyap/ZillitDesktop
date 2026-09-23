// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.productionreport.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.rememberHorizontalResizeCursor
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.AUTHORABLE_COLUMN_TYPES
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.ui.DocumentEvent
import com.zillit.desktop.feature.productionreport.ui.EditorEvent
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportInput
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlin.math.roundToInt

/** Where the edited cell lives in the document. */
internal data class CellAddress(val row: Int, val cell: Int)

/** Which grid a value input sits in — the web gives each a slightly different set of controls. */
internal enum class GridVariant { Table, Section, Crew }

// Tab walking ------------------------------------------------------------------------------------

/**
 * Tab and Shift+Tab walk the grid's free-text cells (typed pickers are
 * skipped, as on the web); Tab past the last one adds a line and lands in it.
 */
internal class GridWalk {
    private val requesters = mutableMapOf<Long, FocusRequester>()
    var textColumns: List<Int> = emptyList()
    var lines: Int = 0
    var addLine: () -> Unit = {}
    var pending by mutableStateOf<Pair<Int, Int>?>(null)

    fun requester(
        line: Int,
        column: Int,
    ): FocusRequester = requesters.getOrPut(line * STRIDE + column) { FocusRequester() }

    fun tab(line: Int, column: Int, backwards: Boolean) {
        if (textColumns.isEmpty()) return
        val at = textColumns.indexOf(column)
        if (!backwards) {
            when {
                at in 0 until textColumns.lastIndex -> focus(line, textColumns[at + 1])
                line < lines - 1 -> focus(line + 1, textColumns.first())
                else -> {
                    pending = (line + 1) to textColumns.first()
                    addLine()
                }
            }
        } else {
            when {
                at > 0 -> focus(line, textColumns[at - 1])
                line > 0 -> focus(line - 1, textColumns.last())
            }
        }
    }

    private fun focus(line: Int, column: Int) {
        runCatching { requester(line, column).requestFocus() }
    }

    private companion object {
        const val STRIDE = 10_000L
    }
}

@Composable
private fun rememberGridWalk(
    address: CellAddress,
    cell: PageCell,
    textColumns: List<Int>,
    onEvent: (ReportEvent) -> Unit,
): GridWalk {
    val walk = remember(address) { GridWalk() }
    walk.textColumns = textColumns
    walk.lines = cell.rows.size
    walk.addLine = { onEvent(DocumentEvent.AddLine(address.row, address.cell)) }
    LaunchedEffect(walk.pending, cell.rows.size) {
        val target = walk.pending ?: return@LaunchedEffect
        if (target.first < cell.rows.size) {
            runCatching { walk.requester(target.first, target.second).requestFocus() }
            walk.pending = null
        }
    }
    return walk
}

/** The column types that edit as free text and join the Tab walk. */
private fun ColumnSpec.isFreeText(variant: GridVariant): Boolean = when (type) {
    "users", "date", "time", "email", "phone", "number" -> false
    "location" -> variant != GridVariant.Table
    "url" -> variant != GridVariant.Crew
    else -> true
}

// Typed value ------------------------------------------------------------------------------------

/** One grid cell's input, chosen by the column type (`PageRowsEditor.jsx` dispatch matrix). */
@Composable
private fun GridValue(
    variant: GridVariant,
    column: ColumnSpec?,
    atom: CellValue?,
    line: Int,
    columnIndex: Int,
    address: CellAddress,
    members: List<SheetMember>,
    walk: GridWalk,
    onEvent: (ReportEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = atom?.value.orEmpty()
    val onFocus = { onEvent(EditorEvent.Focus(line, columnIndex)) }
    val set: (String) -> Unit = { onEvent(DocumentEvent.SetValue(address.row, address.cell, line, columnIndex, it)) }
    val placeholder = placeholderFor(column)
    when (column?.type) {
        "users" -> UsersInput(
            value,
            members,
            set,
            modifier.fillMaxWidth(),
            placeholder = placeholder,
            onFocus = onFocus,
        )
        "location" -> if (variant == GridVariant.Table) {
            LocationInput(
                value,
                atom?.attachment.orEmpty(),
                { text, lat, lng ->
                    onEvent(DocumentEvent.SetLocation(address.row, address.cell, line, columnIndex, text, lat, lng))
                },
                modifier.fillMaxWidth(),
                placeholder = placeholder,
                onFocus = onFocus,
            )
        } else {
            FreeText(value, set, placeholder, line, columnIndex, walk, onFocus, modifier)
        }
        "date" -> DateInput(value, set, modifier.fillMaxWidth(), placeholder = placeholder, onFocus = onFocus)
        "time" -> TimeInput(
            value,
            set,
            modifier.fillMaxWidth(),
            placeholder = placeholder,
            compact = variant != GridVariant.Crew,
            onFocus = onFocus,
        )
        "email" -> EmailText(
            value,
            set,
            placeholder,
            validate = variant != GridVariant.Crew,
            onFocus = onFocus,
            modifier = modifier,
        )
        "phone" -> InlineText(
            value,
            set,
            modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = true,
            keyboardType = KeyboardType.Phone,
            accept = { it.length <= PHONE_MAX && PHONE_TEXT.matches(it) },
            onFocus = onFocus,
        )
        "number" -> InlineText(
            value,
            set,
            modifier.fillMaxWidth(),
            placeholder = if (variant == GridVariant.Crew) column.label else placeholder,
            singleLine = true,
            keyboardType = KeyboardType.Decimal,
            accept = { NUMBER_TEXT.matches(it) },
            onFocus = onFocus,
        )
        "url" -> if (variant == GridVariant.Crew) {
            InlineText(
                value,
                set,
                modifier.fillMaxWidth(),
                placeholder = column.label.ifBlank { "https://..." },
                singleLine = true,
                keyboardType = KeyboardType.Uri,
                onFocus = onFocus,
            )
        } else {
            FreeText(value, set, placeholder, line, columnIndex, walk, onFocus, modifier)
        }
        else -> FreeText(value, set, placeholder, line, columnIndex, walk, onFocus, modifier)
    }
}

@Composable
private fun FreeText(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    line: Int,
    column: Int,
    walk: GridWalk,
    onFocus: () -> Unit,
    modifier: Modifier,
) {
    InlineText(
        value,
        onChange,
        modifier.fillMaxWidth(),
        placeholder = placeholder,
        focusRequester = walk.requester(line, column),
        onFocus = onFocus,
        onTab = { backwards -> walk.tab(line, column, backwards) },
    )
}

@Composable
private fun EmailText(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    validate: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier,
) {
    var invalid by remember { mutableStateOf(false) }
    InlineText(
        value,
        onChange,
        modifier.fillMaxWidth(),
        placeholder = placeholder,
        singleLine = true,
        keyboardType = KeyboardType.Email,
        onFocus = {
            invalid = false
            onFocus()
        },
        onBlur = { invalid = validate && value.isNotBlank() && !EMAIL_TEXT.matches(value.trim()) },
        ring = if (invalid) Color(0x66F04438) else Color.Transparent,
    )
}

private fun typeOptions(current: String): List<Pair<String, String>> {
    val types = if (current == "attachment") AUTHORABLE_COLUMN_TYPES + "attachment" else AUTHORABLE_COLUMN_TYPES
    return types.map { it to if (it == "url") "URL" else it.replaceFirstChar { c -> c.uppercase() } }
}

// Shared chrome -----------------------------------------------------------------------------------

@Composable
private fun gridLine(): Color = if (ReportTheme.colors.isDark) Color(0x1FFFFFFF) else Color(0xFFE4E7EC)

@Composable
private fun RemoveMark(description: String, onClick: () -> Unit, size: Int = 10) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(description) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered) colors.redBg else Color.Transparent)
                .hoverable(source)
                .plainClick(source = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.Close,
                contentDescription = description,
                tint = if (hovered) colors.red else colors.textMuted,
                modifier = Modifier.size(size.dp),
            )
        }
    }
}

/** "Add Row" (the primary look) and "Add Column". */
@Composable
private fun AddButtons(onAddRow: () -> Unit, onAddColumn: (() -> Unit)?) {
    val colors = ReportTheme.colors
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportButton(
            str(S.pr_add_row),
            onAddRow,
            kind = if (colors.isDark) ButtonKind.Accent else ButtonKind.Navy,
            icon = ZillitIcons.Add,
            height = 32.dp,
            fontSize = 12.sp,
            radius = 6.dp,
            horizontalPadding = 16.dp,
        )
        onAddColumn?.let {
            ReportButton(
                str(S.desktop_add_column),
                it,
                kind = ButtonKind.Secondary,
                icon = ZillitIcons.Add,
                height = 32.dp,
                fontSize = 12.sp,
                radius = 6.dp,
                horizontalPadding = 16.dp,
            )
        }
    }
}

// Section grid ------------------------------------------------------------------------------------

/** A section's grid: a type row, a name row, and equal-width value columns. */
@Composable
internal fun SectionGridEditor(
    cell: PageCell,
    address: CellAddress,
    members: List<SheetMember>,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    val line = gridLine()
    val walk = rememberGridWalk(
        address,
        cell,
        cell.columns.indices.filter { cell.columns[it].isFreeText(GridVariant.Section) },
        onEvent,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.elevated)) {
            cell.columns.forEachIndexed { index, column ->
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .rules(line, end = true, bottom = true)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaneSelect(
                        column.type,
                        typeOptions(column.type),
                        { onEvent(DocumentEvent.SetColumnType(address.row, address.cell, index, it)) },
                        Modifier.weight(1f),
                        borderless = true,
                        fontSize = 12,
                    )
                    if (cell.columns.size > 1) RemoveMark(
                        str(S.desktop_remove_column),
                        { onEvent(DocumentEvent.RemoveColumn(address.row, address.cell, index)) },
                    )
                }
            }
            Box(Modifier.width(28.dp).fillMaxHeight().rules(line, bottom = true))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.sunken)) {
            cell.columns.forEachIndexed { index, column ->
                InlineText(
                    column.label,
                    { onEvent(DocumentEvent.RenameColumn(address.row, address.cell, index, it)) },
                    Modifier.weight(1f).fillMaxHeight().rules(line, end = true, bottom = true),
                    placeholder = str(S.desktop_col_n, index + 1),
                    singleLine = true,
                    bold = true,
                    align = TextAlign.Center,
                    textColor = colors.textSecondary,
                )
            }
            Box(Modifier.width(28.dp).fillMaxHeight().rules(line, bottom = true))
        }
        cell.rows.forEachIndexed { lineIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                cell.columns.forEachIndexed { index, column ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .rules(line, end = true, bottom = lineIndex < cell.rows.lastIndex),
                    ) {
                        GridValue(
                            GridVariant.Section,
                            column,
                            row.values.getOrNull(index),
                            lineIndex,
                            index,
                            address,
                            members,
                            walk,
                            onEvent,
                        )
                    }
                }
                Box(
                    Modifier
                        .width(28.dp)
                        .fillMaxHeight()
                        .background(colors.editorBg)
                        .rules(line, bottom = lineIndex < cell.rows.lastIndex),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cell.rows.size > 1) RemoveMark(
                        str(S.desktop_remove_row),
                        { onEvent(DocumentEvent.RemoveLine(address.row, address.cell, lineIndex)) },
                    )
                }
            }
        }
    }
    AddButtons(
        onAddRow = { onEvent(DocumentEvent.AddLine(address.row, address.cell)) },
        onAddColumn = { onEvent(DocumentEvent.AddColumn(address.row, address.cell)) },
    )
}

// Generic table -----------------------------------------------------------------------------------

/**
 * `TableEditor`: numbered lines, a type row with resize grips, a name row,
 * a line-height grip, and a horizontal scroll once columns outgrow the pane.
 */
@Composable
internal fun TableEditor(
    cell: PageCell,
    address: CellAddress,
    members: List<SheetMember>,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    val line = gridLine()
    val walk = rememberGridWalk(
        address,
        cell,
        cell.columns.indices.filter { cell.columns[it].isFreeText(GridVariant.Table) },
        onEvent,
    )
    val weights = cell.columnWeights().map { it.toFloat() }
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimum = (cell.columns.size * MIN_COLUMN + GUTTERS).dp
        val tableWidth = if (maxWidth > minimum) maxWidth else minimum
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column(Modifier.width(tableWidth).border(1.dp, colors.borderStrong).background(colors.surface)) {
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.elevated)) {
                        Text(
                            "#",
                            style = reportText(12.sp),
                            color = colors.textMeta,
                            modifier = Modifier
                                .width(GUTTER)
                                .fillMaxHeight()
                                .rules(line, end = true, bottom = true)
                                .padding(6.dp),
                        )
                        cell.columns.forEachIndexed { index, column ->
                            Row(
                                Modifier
                                    .weight(weights[index])
                                    .fillMaxHeight()
                                    .rules(line, end = true, bottom = true)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PaneSelect(
                                    column.type,
                                    typeOptions(column.type),
                                    { onEvent(DocumentEvent.SetColumnType(address.row, address.cell, index, it)) },
                                    Modifier.weight(1f),
                                    borderless = true,
                                    fontSize = 12,
                                )
                                if (cell.columns.size > 1) RemoveMark(
                                    str(S.desktop_remove_column),
                                    { onEvent(DocumentEvent.RemoveColumn(address.row, address.cell, index)) },
                                    size = 11,
                                )
                            }
                        }
                        Box(Modifier.width(GUTTER).fillMaxHeight().rules(line, bottom = true))
                    }
                    ColumnGrips(
                        weights = weights,
                        leading = GUTTER,
                        trailing = GUTTER,
                        resizable = cell.columns.indices.toList(),
                        modifier = Modifier.matchParentSize(),
                    ) { index, deltaUnits, baseline ->
                        onEvent(
                            DocumentEvent.SetColumnWidths(
                                address.row,
                                address.cell,
                                resized(baseline, index, deltaUnits),
                            ),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.sunken)) {
                    Box(Modifier.width(GUTTER).fillMaxHeight().rules(line, end = true, bottom = true))
                    cell.columns.forEachIndexed { index, column ->
                        InlineText(
                            column.label,
                            { onEvent(DocumentEvent.RenameColumn(address.row, address.cell, index, it)) },
                            Modifier.weight(weights[index]).fillMaxHeight().rules(line, end = true, bottom = true),
                            placeholder = str(S.desktop_col_n, index + 1),
                            singleLine = !cell.isVerticalHeader,
                            bold = true,
                            align = TextAlign.Center,
                            textColor = colors.textSecondary,
                        )
                    }
                    Box(Modifier.width(GUTTER).fillMaxHeight().rules(line, bottom = true))
                }
                val heights = remember(address) { mutableStateMapOf<Int, Int>() }
                val density = LocalDensity.current
                cell.rows.forEachIndexed { lineIndex, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .then(row.height?.let { Modifier.heightIn(min = it.dp) } ?: Modifier)
                            .onSizeChanged { heights[lineIndex] = it.height },
                    ) {
                        Box(
                            Modifier
                                .width(GUTTER)
                                .fillMaxHeight()
                                .background(colors.editorBg)
                                .rules(line, end = true, bottom = true),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                "${lineIndex + 1}",
                                style = reportText(12.sp),
                                color = colors.textMeta,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            var startHeight by remember { mutableStateOf(0f) }
                            RowGrip(
                                Modifier.align(Alignment.BottomCenter),
                                onStart = { startHeight = with(density) { (heights[lineIndex] ?: 0).toDp().value } },
                            ) { travelled ->
                                val next = (startHeight + with(density) { travelled.toDp().value })
                                    .roundToInt()
                                    .coerceAtLeast(MIN_LINE)
                                onEvent(DocumentEvent.SetLineHeight(address.row, address.cell, lineIndex, next))
                            }
                        }
                        cell.columns.forEachIndexed { index, column ->
                            Box(
                                Modifier.weight(weights[index]).fillMaxHeight().rules(line, end = true, bottom = true),
                            ) {
                                GridValue(
                                    GridVariant.Table,
                                    column,
                                    row.values.getOrNull(index),
                                    lineIndex,
                                    index,
                                    address,
                                    members,
                                    walk,
                                    onEvent,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .width(GUTTER)
                                .fillMaxHeight()
                                .background(colors.editorBg)
                                .rules(line, bottom = true),
                            contentAlignment = Alignment.Center,
                        ) {
                            RemoveMark(
                                str(S.desktop_remove_row),
                                { onEvent(DocumentEvent.RemoveLine(address.row, address.cell, lineIndex)) },
                                size = 11,
                            )
                        }
                    }
                }
            }
        }
    }
    AddButtons(
        onAddRow = { onEvent(DocumentEvent.AddLine(address.row, address.cell)) },
        onAddColumn = { onEvent(DocumentEvent.AddColumn(address.row, address.cell)) },
    )
}

private const val MIN_COLUMN = 100
private const val GUTTERS = 56
private const val MIN_LINE = 28
private val GUTTER = 28.dp

/** A column widened by [deltaUnits]; its right neighbour gives the room back (the last column simply grows). */
private fun resized(baseline: List<Double>, index: Int, deltaUnits: Double): List<Double> {
    val next = baseline.toMutableList()
    next[index] = round2((baseline[index] + deltaUnits).coerceAtLeast(PageCell.MIN_WIDTH))
    if (index + 1 < baseline.size) next[index + 1] = round2(
        (baseline[index + 1] - deltaUnits).coerceAtLeast(PageCell.MIN_WIDTH),
    )
    return next
}

private fun round2(value: Double): Double = (value * HUNDREDTHS).roundToInt() / HUNDREDTHS

private const val HUNDREDTHS = 100.0

/** Grips on the column borders of a header row, placed over it by the weights. */
@Composable
private fun ColumnGrips(
    weights: List<Float>,
    leading: androidx.compose.ui.unit.Dp,
    trailing: androidx.compose.ui.unit.Dp,
    resizable: List<Int>,
    modifier: Modifier,
    onResize: (index: Int, deltaUnits: Double, baseline: List<Double>) -> Unit,
) {
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0) }
    val currentWeights by rememberUpdatedState(weights)
    val resize by rememberUpdatedState(onResize)
    Layout(
        modifier = modifier.onSizeChanged { width = it.width },
        content = {
            resizable.forEach { index ->
                ColumnGrip(baseline = { currentWeights.map { it.toDouble() } }) { travelled, baseline ->
                    val data = width - with(density) { (leading + trailing).toPx() }
                    val total = baseline.sum()
                    if (data > 0 && total > 0) resize(index, travelled / (data / total), baseline)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val lead = leading.roundToPx()
        val data = constraints.maxWidth - lead - trailing.roundToPx()
        val total = currentWeights.sum().takeIf { it > 0f } ?: 1f
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { position, placeable ->
                val index = resizable[position]
                val edge = lead + (data * currentWeights.take(index + 1).sum() / total).roundToInt()
                placeable.place(edge - placeable.width / 2, 0)
            }
        }
    }
}

/**
 * One column grip: an 8 px target over the border, a small bar that lights
 * up on hover, and the drag reported against the widths as they were when it
 * began.
 */
@Composable
private fun ColumnGrip(baseline: () -> List<Double>, onDrag: (travelledPx: Double, baseline: List<Double>) -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(8.dp)
            .fillMaxHeight()
            .hoverable(source)
            .then(rememberHorizontalResizeCursor())
            .pointerInput(Unit) {
                var start = emptyList<Double>()
                var travelled = 0.0
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = baseline()
                        travelled = 0.0
                        dragging = true
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, delta ->
                    change.consume()
                    travelled += delta
                    if (start.isNotEmpty()) onDrag(travelled, start)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (hovered || dragging) colors.accent else colors.borderStrong),
        )
    }
}

/** The line-height grip on a row number's lower edge; reports the distance dragged since it began. */
@Composable
private fun RowGrip(modifier: Modifier, onStart: () -> Unit, onDrag: (travelledPx: Float) -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .hoverable(source)
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        travelled = 0f
                        onStart()
                    },
                ) { change, delta ->
                    change.consume()
                    travelled += delta
                    onDrag(travelled)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(14.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (hovered) colors.accent else colors.borderStrong),
        )
    }
}

// Crew table ----------------------------------------------------------------------------------------

/**
 * `EmployeeTableEditor`: one department's crew. Role, Name, IN and OUT are
 * protected (the web left Name removable, which broke the call-sheet merge);
 * IN / OUT get the mode selector and Apply All.
 */
@Composable
internal fun CrewEditor(
    cell: PageCell,
    address: CellAddress,
    members: List<SheetMember>,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    val line = gridLine()
    val visible = cell.columns.indices.filterNot { HIDDEN_CREW_COLUMN.matches(cell.columns[it].label) }
    val weights = visible.map { (cell.columns[it].width ?: 1.0).coerceAtLeast(PageCell.MIN_WIDTH).toFloat() }
    val walk = rememberGridWalk(address, cell, emptyList(), onEvent)
    Column(Modifier.fillMaxWidth().border(1.dp, colors.borderStrong).background(colors.surface)) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.sunken)) {
                visible.forEachIndexed { position, index ->
                    val column = cell.columns[index]
                    Box(
                        Modifier
                            .weight(weights[position])
                            .fillMaxHeight()
                            .rules(line, end = true, bottom = true)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        when {
                            column.isInOut() -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    column.label,
                                    style = reportText(14.sp, FontWeight.Bold),
                                    color = colors.textSecondary,
                                )
                                ApplyAll { value ->
                                    onEvent(DocumentEvent.ApplyToColumn(address.row, address.cell, index, value))
                                }
                            }
                            index == 0 || column.label.trim().equals("name", ignoreCase = true) ->
                                Text(
                                    column.label.ifBlank { str(S.desktop_col_n, index + 1) },
                                    style = reportText(14.sp, FontWeight.Bold),
                                    color = colors.textSecondary,
                                )
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                InlineText(
                                    column.label,
                                    { onEvent(DocumentEvent.RenameColumn(address.row, address.cell, index, it)) },
                                    Modifier.weight(1f),
                                    placeholder = str(S.desktop_col_n, index + 1),
                                    singleLine = true,
                                    bold = true,
                                    textColor = colors.textSecondary,
                                )
                                if (cell.columns.size > 1) RemoveMark(
                                    str(S.desktop_remove_column),
                                    { onEvent(DocumentEvent.RemoveColumn(address.row, address.cell, index)) },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.width(GUTTER).fillMaxHeight().rules(line, bottom = true))
            }
            ColumnGrips(
                weights = weights,
                leading = 0.dp,
                trailing = GUTTER,
                resizable = visible.indices.toList().dropLast(1),
                modifier = Modifier.matchParentSize(),
            ) { position, deltaUnits, baseline ->
                val next = resized(baseline, position, deltaUnits)
                val widths = cell.columns.mapIndexed { index, column ->
                    val at = visible.indexOf(index)
                    if (at >= 0) next[at] else column.width ?: 1.0
                }
                onEvent(DocumentEvent.SetColumnWidths(address.row, address.cell, widths))
            }
        }
        if (cell.rows.isEmpty()) {
            Text(
                str(S.desktop_no_members_in_department),
                style = reportText(14.sp),
                color = colors.textMeta,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        cell.rows.forEachIndexed { lineIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                visible.forEachIndexed { position, index ->
                    val column = cell.columns[index]
                    val atom = row.values.getOrNull(index)
                    Box(
                        Modifier
                            .weight(weights[position])
                            .fillMaxHeight()
                            .rules(line, end = true, bottom = lineIndex < cell.rows.lastIndex),
                    ) {
                        when {
                            index == 0 -> InlineText(
                                atom?.value.orEmpty(),
                                { onEvent(DocumentEvent.SetValue(address.row, address.cell, lineIndex, index, it)) },
                                Modifier.fillMaxSize(),
                                placeholder = column.label.ifBlank { str(S.name) },
                                ground = colors.elevated,
                                onFocus = { onEvent(EditorEvent.Focus(lineIndex, index)) },
                            )
                            column.isInOut() -> InOutSelector(
                                atom?.value.orEmpty(),
                                { onEvent(DocumentEvent.SetValue(address.row, address.cell, lineIndex, index, it)) },
                                onFocus = { onEvent(EditorEvent.Focus(lineIndex, index)) },
                            )
                            else -> GridValue(
                                GridVariant.Crew,
                                column,
                                atom,
                                lineIndex,
                                index,
                                address,
                                members,
                                walk,
                                onEvent,
                            )
                        }
                    }
                }
                Box(
                    Modifier.width(GUTTER).fillMaxHeight().rules(line, bottom = lineIndex < cell.rows.lastIndex),
                    contentAlignment = Alignment.Center,
                ) {
                    RemoveMark(
                        str(S.desktop_remove_row),
                        { onEvent(DocumentEvent.RemoveLine(address.row, address.cell, lineIndex)) },
                    )
                }
            }
        }
    }
    AddButtons(
        onAddRow = { onEvent(DocumentEvent.AddLine(address.row, address.cell)) },
        onAddColumn = { onEvent(DocumentEvent.AddColumn(address.row, address.cell)) },
    )
}

/** "Apply All" on an IN / OUT column: Per HOD, O/C, or clear every member. */
@Composable
private fun ApplyAll(onApply: (String) -> Unit) {
    val colors = ReportTheme.colors
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    Box {
        ZillitTooltip(str(S.desktop_apply_to_all_in_department)) {
            Text(
                str(S.desktop_apply_all),
                style = reportText(9.sp, FontWeight.Medium, 12.sp),
                color = if (hovered) Color.White else colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (hovered) colors.accent else Color.Transparent)
                    .border(1.dp, colors.accent, RoundedCornerShape(6.dp))
                    .hoverable(source)
                    .plainClick(source = source) { open = true }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(10.dp),
            containerColor = colors.surface,
            modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(10.dp)),
        ) {
            Column(Modifier.width(150.dp).padding(horizontal = 4.dp)) {
                MenuOption(str(S.desktop_per_hod)) { open = false; onApply("Per HOD") }
                MenuOption("O/C") { open = false; onApply("O/C") }
                MenuOption(str(S.txt_clear_all), danger = true) { open = false; onApply("") }
            }
        }
    }
}

// Notes ---------------------------------------------------------------------------------------------

/** One input per note, a Vertical / Horizontal layout toggle, and Add Note. */
@Composable
internal fun NotesEditor(cell: PageCell, address: CellAddress, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val horizontal = cell.viewType.equals("horizontal", ignoreCase = true)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(str(S.bs_pdf_layout_label), style = reportText(12.sp), color = colors.textMeta)
            LayoutChip(
                str(S.desktop_vertical),
                !horizontal,
            ) { onEvent(DocumentEvent.SetNotesHorizontal(address.row, address.cell, false)) }
            LayoutChip(
                str(S.desktop_horizontal),
                horizontal,
            ) { onEvent(DocumentEvent.SetNotesHorizontal(address.row, address.cell, true)) }
        }
        cell.rows.forEachIndexed { index, row ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(colors.border))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportInput(
                    row.values.firstOrNull()?.value.orEmpty(),
                    { onEvent(DocumentEvent.SetValue(address.row, address.cell, index, 0, it)) },
                    Modifier.weight(1f),
                    placeholder = str(S.enter_notes),
                    onFocusChange = { if (it) onEvent(EditorEvent.Focus(index, null)) },
                )
                Text(
                    str(S.remove),
                    style = reportText(12.sp),
                    color = colors.red,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .plainClick {
                            onEvent(
                                DocumentEvent.RemoveLine(address.row, address.cell, index),
                            )
                        }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.padding(top = 12.dp)) {
            ReportButton(
                str(S.add_note),
                { onEvent(DocumentEvent.AddLine(address.row, address.cell)) },
                kind = ButtonKind.Accent,
                icon = ZillitIcons.Add,
                height = 30.dp,
                fontSize = 12.sp,
                radius = 12.dp,
            )
        }
    }
}

@Composable
private fun LayoutChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Text(
        label,
        style = reportText(12.sp, if (active) FontWeight.SemiBold else FontWeight.Normal),
        color = if (active) Color.White else if (hovered) colors.accent else colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.accent else Color.Transparent)
            .then(if (active) Modifier else Modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)))
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
