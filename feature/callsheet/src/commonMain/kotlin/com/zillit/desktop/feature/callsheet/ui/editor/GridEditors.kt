// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.callsheet.ui.editor

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.rememberHorizontalResizeCursor
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.callsheet.domain.CELL_TYPES
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.ui.DocumentEvent
import com.zillit.desktop.feature.callsheet.ui.EditorEvent
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetInput
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlin.math.roundToInt

/** Where the edited cell lives in the document. */
internal data class CellAddress(val row: Int, val cell: Int)

/** Which grid a value input sits in — the crew table's free text never walks on Tab. */
internal enum class GridVariant { Table, Section, Crew }

// Tab walking ------------------------------------------------------------------------------------

/**
 * Tab and Shift+Tab walk the grid's free-text cells (typed inputs are
 * skipped, as on the web); Tab past the last one adds a line and lands in it.
 */
internal class GridWalk {
    private val requesters = mutableMapOf<Long, FocusRequester>()
    var textColumns: List<Int> = emptyList()
    var lines: Int = 0
    var addLine: () -> Unit = {}
    var pending by mutableStateOf<Pair<Int, Int>?>(null)

    fun requester(line: Int, column: Int): FocusRequester =
        requesters.getOrPut(line * STRIDE + column) { FocusRequester() }

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
private fun rememberGridWalk(address: CellAddress, cell: PageCell, onEvent: (SheetEvent) -> Unit): GridWalk {
    val walk = remember(address) { GridWalk() }
    walk.textColumns = cell.columns.indices.filter { cell.columns[it].isFreeText() }
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

/** The column types that edit as a growing text box — the web's textarea, and the Tab chain. */
private fun ColumnSpec.isFreeText(): Boolean = type !in TYPED_INPUTS

private val TYPED_INPUTS = setOf("users", "date", "time", "number", "phone", "email", "url")

// Typed value ------------------------------------------------------------------------------------

/** One grid cell's input, chosen by the column type (`renderTypedCellInput`). */
@Composable
private fun GridValue(
    variant: GridVariant,
    column: ColumnSpec?,
    atom: CellValue?,
    line: Int,
    columnIndex: Int,
    address: CellAddress,
    members: List<SheetMember>,
    sheetDateMs: Long?,
    walk: GridWalk,
    onEvent: (SheetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = atom?.value.orEmpty()
    val onFocus = { onEvent(EditorEvent.Focus(line, columnIndex)) }
    val set: (String) -> Unit = { onEvent(DocumentEvent.SetValue(address.row, address.cell, line, columnIndex, it)) }
    val placeholder = placeholderFor(column)
    val field = modifier.fillMaxWidth()
    when (column?.type) {
        "users" -> UsersInput(value, members, set, field, placeholder = placeholder, onFocus = onFocus)
        "date" -> DateInput(
            value,
            { picked -> set(picked?.toString().orEmpty()) },
            field,
            placeholder = placeholder,
            disablePast = true,
            onFocus = onFocus,
        )
        "time" -> TimeInput(value, set, sheetDateMs, field, placeholder = placeholder, onFocus = onFocus)
        "number" -> InlineText(
            value,
            set,
            field,
            placeholder = placeholder,
            singleLine = true,
            keyboardType = KeyboardType.Decimal,
            accept = { NUMBER_TEXT.matches(it) },
            onFocus = onFocus,
        )
        "phone" -> InlineText(
            value,
            set,
            field,
            placeholder = placeholder,
            singleLine = true,
            keyboardType = KeyboardType.Phone,
            accept = { it.length <= PHONE_MAX && PHONE_TEXT.matches(it) },
            onFocus = onFocus,
        )
        "email" -> InlineText(
            value,
            set,
            field,
            placeholder = placeholder,
            singleLine = true,
            keyboardType = KeyboardType.Email,
            onFocus = onFocus,
        )
        "url" -> InlineText(
            value,
            set,
            field,
            placeholder = placeholder,
            singleLine = true,
            keyboardType = KeyboardType.Uri,
            onFocus = onFocus,
        )
        else -> InlineText(
            value,
            set,
            field,
            placeholder = placeholder,
            focusRequester = if (variant == GridVariant.Crew) null else walk.requester(line, columnIndex),
            onFocus = onFocus,
            onTab = if (variant == GridVariant.Crew) null else { backwards -> walk.tab(line, columnIndex, backwards) },
        )
    }
}

/**
 * The column type select: every type but `attachment`, and no `users` in a
 * Call Times section. A stored type outside that list is still shown as it is.
 */
private fun typeOptions(cell: PageCell, current: String): List<Pair<String, String>> {
    val callTimes = CALL_TIMES.containsMatchIn(cell.title)
    val offered = CELL_TYPES.filter { it != "attachment" && !(callTimes && it == "users") }
    val types = if (current.isNotBlank() && current !in offered) offered + current else offered
    return types.map { it to if (it == "url") "URL" else it.replaceFirstChar { c -> c.uppercase() } }
}

private val CALL_TIMES = Regex("""call\s*times""", RegexOption.IGNORE_CASE)

// Shared chrome -----------------------------------------------------------------------------------

/** The grid's body rules — black on the web, a quiet white in the dark theme. */
@Composable
private fun bodyLine(): Color = if (SheetTheme.colors.isDark) Color(0x26FFFFFF) else Color.Black

/** The light rules of the section grid's header rows. */
@Composable
private fun headerLine(): Color = if (SheetTheme.colors.isDark) Color(0x14FFFFFF) else Color(0xFFE5E7EB)

/** The `#dddddd` column-name band of tables and the crew table. */
@Composable
private fun nameBand(): Color = if (SheetTheme.colors.isDark) Color(0x14FFFFFF) else Color(0xFFDDDDDD)

@Composable
private fun RemoveMark(description: String, onClick: () -> Unit, size: Int = 10) {
    val colors = SheetTheme.colors
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

/** "Add Row" and "Add Column" under every grid. */
@Composable
private fun AddButtons(address: CellAddress, onEvent: (SheetEvent) -> Unit) {
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetButton(
            "Add Row",
            { onEvent(DocumentEvent.AddLine(address.row, address.cell)) },
            kind = ButtonKind.Accent,
            icon = ZillitIcons.Add,
            height = 32.dp,
            fontSize = 13.sp,
            radius = 12.dp,
            horizontalPadding = 12.dp,
        )
        SheetButton(
            "Add Column",
            { onEvent(DocumentEvent.AddColumn(address.row, address.cell)) },
            kind = ButtonKind.Warning,
            height = 32.dp,
            fontSize = 13.sp,
            radius = 12.dp,
            horizontalPadding = 12.dp,
        )
    }
}

/**
 * A column name: one line across, or turned to read bottom-to-top in a
 * 120 px band when the headers are vertical.
 */
@Composable
private fun ColumnName(
    label: String,
    placeholder: String,
    vertical: Boolean,
    onRename: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = SheetTheme.colors
    if (vertical) {
        Box(modifier.height(VERTICAL_BAND), contentAlignment = Alignment.Center) {
            Box(Modifier.height(VERTICAL_FIELD)) {
                InlineText(
                    label,
                    onRename,
                    Modifier.readsUpward().widthIn(min = 24.dp),
                    placeholder = placeholder,
                    singleLine = true,
                    bold = true,
                    align = TextAlign.Center,
                    textColor = colors.textPrimary,
                )
            }
        }
    } else {
        InlineText(
            label,
            onRename,
            modifier,
            placeholder = placeholder,
            singleLine = true,
            bold = true,
            align = TextAlign.Center,
            textColor = colors.textPrimary,
        )
    }
}

private val VERTICAL_BAND = 120.dp
private val VERTICAL_FIELD = 100.dp

// Section grid ------------------------------------------------------------------------------------

/**
 * A section: a type row, a name row, and equal-width value columns (the
 * stored widths are kept but not used here), with a remove mark per line once
 * there is more than one.
 */
@Composable
internal fun SectionGridEditor(
    cell: PageCell,
    address: CellAddress,
    members: List<SheetMember>,
    sheetDateMs: Long?,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val header = headerLine()
    val body = bodyLine()
    val walk = rememberGridWalk(address, cell, onEvent)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, header, RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.elevated)) {
            cell.columns.forEachIndexed { index, column ->
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .rules(header, end = true, bottom = true)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaneSelect(
                        column.type,
                        typeOptions(cell, column.type),
                        { onEvent(DocumentEvent.SetColumnType(address.row, address.cell, index, it)) },
                        Modifier.weight(1f),
                        borderless = true,
                        fontSize = 11,
                        centered = true,
                    )
                    if (cell.columns.size > 1) {
                        RemoveMark(
                            "Remove column",
                            { onEvent(DocumentEvent.RemoveColumn(address.row, address.cell, index)) },
                        )
                    }
                }
            }
            Box(Modifier.width(GUTTER).fillMaxHeight().rules(header, bottom = true))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.sunken)) {
            cell.columns.forEachIndexed { index, column ->
                ColumnName(
                    column.label,
                    "Col ${index + 1}",
                    cell.isVerticalHeader,
                    { onEvent(DocumentEvent.RenameColumn(address.row, address.cell, index, it)) },
                    Modifier.weight(1f).fillMaxHeight().rules(header, end = true, bottom = true),
                )
            }
            Box(Modifier.width(GUTTER).fillMaxHeight().background(nameBand()).rules(body, bottom = true))
        }
        cell.rows.forEachIndexed { lineIndex, row ->
            val last = lineIndex == cell.rows.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                cell.columns.forEachIndexed { index, column ->
                    Box(Modifier.weight(1f).fillMaxHeight().rules(body, end = true, bottom = !last)) {
                        GridValue(
                            GridVariant.Section,
                            column,
                            row.values.getOrNull(index),
                            lineIndex,
                            index,
                            address,
                            members,
                            sheetDateMs,
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
                        .rules(body, bottom = !last),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cell.rows.size > 1) {
                        RemoveMark(
                            "Remove row",
                            { onEvent(DocumentEvent.RemoveLine(address.row, address.cell, lineIndex)) },
                            size = 11,
                        )
                    }
                }
            }
        }
    }
    AddButtons(address, onEvent)
}

// Generic table -----------------------------------------------------------------------------------

/**
 * `TableEditor`: numbered lines, a type row with a resize grip on every
 * column, a name row, a line-height grip, and a horizontal scroll once the
 * columns outgrow the pane (each keeps about 100 px).
 */
@Composable
internal fun TableEditor(
    cell: PageCell,
    address: CellAddress,
    members: List<SheetMember>,
    sheetDateMs: Long?,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val body = bodyLine()
    val walk = rememberGridWalk(address, cell, onEvent)
    val weights = cell.columnWeights().map { it.toFloat() }
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimum = (cell.columns.size * MIN_COLUMN + GUTTERS).dp
        val tableWidth = if (maxWidth > minimum) maxWidth else minimum
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column(Modifier.width(tableWidth).border(1.dp, body).background(colors.surface)) {
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.elevated)) {
                        Text(
                            "#",
                            style = sheetText(12.sp),
                            color = colors.textMeta,
                            modifier = Modifier
                                .width(GUTTER)
                                .fillMaxHeight()
                                .rules(body, end = true, bottom = true)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                        cell.columns.forEachIndexed { index, column ->
                            Row(
                                Modifier
                                    .weight(weights[index])
                                    .fillMaxHeight()
                                    .rules(body, end = true, bottom = true)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PaneSelect(
                                    column.type,
                                    typeOptions(cell, column.type),
                                    { onEvent(DocumentEvent.SetColumnType(address.row, address.cell, index, it)) },
                                    Modifier.weight(1f),
                                    borderless = true,
                                    fontSize = 12,
                                    centered = true,
                                )
                                if (cell.columns.size > 1) {
                                    RemoveMark(
                                        "Remove column",
                                        { onEvent(DocumentEvent.RemoveColumn(address.row, address.cell, index)) },
                                        size = 11,
                                    )
                                }
                            }
                        }
                        Box(Modifier.width(GUTTER).fillMaxHeight().rules(body, bottom = true))
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
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(nameBand())) {
                    Box(Modifier.width(GUTTER).fillMaxHeight().rules(body, end = true, bottom = true))
                    cell.columns.forEachIndexed { index, column ->
                        ColumnName(
                            column.label,
                            "Col ${index + 1}",
                            cell.isVerticalHeader,
                            { onEvent(DocumentEvent.RenameColumn(address.row, address.cell, index, it)) },
                            Modifier.weight(weights[index]).fillMaxHeight().rules(body, end = true, bottom = true),
                        )
                    }
                    Box(Modifier.width(GUTTER).fillMaxHeight().rules(body, bottom = true))
                }
                val heights = remember(address) { mutableStateMapOf<Int, Int>() }
                val density = LocalDensity.current
                cell.rows.forEachIndexed { lineIndex, row ->
                    val last = lineIndex == cell.rows.lastIndex
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
                                .rules(body, end = true, bottom = !last),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                "${lineIndex + 1}",
                                style = sheetText(12.sp),
                                color = colors.textMeta,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            var startHeight by remember { mutableFloatStateOf(0f) }
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
                                Modifier.weight(weights[index]).fillMaxHeight().rules(body, end = true, bottom = !last),
                            ) {
                                GridValue(
                                    GridVariant.Table,
                                    column,
                                    row.values.getOrNull(index),
                                    lineIndex,
                                    index,
                                    address,
                                    members,
                                    sheetDateMs,
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
                                .rules(body, bottom = !last),
                            contentAlignment = Alignment.Center,
                        ) {
                            RemoveMark(
                                "Remove row",
                                { onEvent(DocumentEvent.RemoveLine(address.row, address.cell, lineIndex)) },
                                size = 11,
                            )
                        }
                    }
                }
            }
        }
    }
    AddButtons(address, onEvent)
}

private const val MIN_COLUMN = 100
private const val GUTTERS = 56
private const val MIN_LINE = 28
private val GUTTER = 28.dp

/** A column widened by [deltaUnits]; its right neighbour gives the room back (the last column simply grows). */
private fun resized(baseline: List<Double>, index: Int, deltaUnits: Double): List<Double> {
    val next = baseline.toMutableList()
    next[index] = round2((baseline[index] + deltaUnits).coerceAtLeast(PageCell.MIN_WIDTH))
    if (index + 1 < baseline.size) {
        next[index + 1] = round2((baseline[index + 1] - deltaUnits).coerceAtLeast(PageCell.MIN_WIDTH))
    }
    return next
}

private fun round2(value: Double): Double = (value * HUNDREDTHS).roundToInt() / HUNDREDTHS

private const val HUNDREDTHS = 100.0

/** Grips on the column borders of a header row, placed over it by the weights. */
@Composable
private fun ColumnGrips(
    weights: List<Float>,
    leading: Dp,
    trailing: Dp,
    resizable: List<Int>,
    modifier: Modifier,
    onResize: (index: Int, deltaUnits: Double, baseline: List<Double>) -> Unit,
) {
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
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
 * One column grip: an 8 px target over the border, a pill that lights up on
 * hover, and the drag reported against the widths as they were when it began.
 */
@Composable
private fun ColumnGrip(baseline: () -> List<Double>, onDrag: (travelledPx: Double, baseline: List<Double>) -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    var dragging by remember { mutableStateOf(false) }
    ZillitTooltip("Drag to resize column") {
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
            GripPill(horizontal = false, lit = hovered || dragging, idle = colors.borderStrong, accent = colors.accent)
        }
    }
}

/** The line-height grip on a row number's lower edge; reports the distance dragged since it began. */
@Composable
private fun RowGrip(modifier: Modifier, onStart: () -> Unit, onDrag: (travelledPx: Float) -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    var dragging by remember { mutableStateOf(false) }
    ZillitTooltip("Drag to resize row") {
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
                            dragging = true
                            onStart()
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        travelled += delta
                        onDrag(travelled)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            GripPill(horizontal = true, lit = hovered || dragging, idle = colors.borderStrong, accent = colors.accent)
        }
    }
}

/** The grip's pill with its three dots. */
@Composable
private fun GripPill(horizontal: Boolean, lit: Boolean, idle: Color, accent: Color) {
    val size = if (horizontal) Modifier.width(18.dp).height(3.dp) else Modifier.width(3.dp).height(18.dp)
    Box(
        size.clip(RoundedCornerShape(2.dp)).background(if (lit) accent else idle),
        contentAlignment = Alignment.Center,
    ) {
        val dots: @Composable () -> Unit = {
            repeat(3) { Box(Modifier.size(1.5.dp).background(Color.White.copy(alpha = 0.8f))) }
        }
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { dots() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { dots() }
        }
    }
}

// Crew table ----------------------------------------------------------------------------------------

/**
 * `EmployeeTableEditor`: one department's crew. Name and In are the
 * department's own columns (never renamed or removed); In gets the mode
 * selector and "Apply All". Legacy "As Per" / "To Include" / "Additional"
 * columns stay in the data but out of sight.
 */
@Composable
internal fun CrewEditor(
    cell: PageCell,
    address: CellAddress,
    members: List<SheetMember>,
    sheetDateMs: Long?,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val body = bodyLine()
    val visible = cell.columns.indices.filterNot { HIDDEN_CREW_COLUMN.matches(cell.columns[it].label.trim()) }
    val weights = visible.map { (cell.columns[it].width ?: 1.0).coerceAtLeast(PageCell.MIN_WIDTH).toFloat() }
    val walk = rememberGridWalk(address, cell, onEvent)
    Column(Modifier.fillMaxWidth().border(1.dp, body).background(colors.surface)) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(nameBand())) {
                visible.forEachIndexed { position, index ->
                    val column = cell.columns[index]
                    Box(
                        Modifier
                            .weight(weights[position])
                            .fillMaxHeight()
                            .rules(body, end = true, bottom = true)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        when {
                            column.isInColumn() -> Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            ) {
                                Text(
                                    column.label,
                                    style = sheetText(14.sp, FontWeight.Bold),
                                    color = colors.textPrimary,
                                )
                                ApplyAll { value ->
                                    onEvent(DocumentEvent.ApplyToColumn(address.row, address.cell, index, value))
                                }
                            }
                            index == 0 -> Text(
                                column.label.ifBlank { "Col 1" },
                                style = sheetText(14.sp, FontWeight.Bold),
                                color = colors.textPrimary,
                            )
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                InlineText(
                                    column.label,
                                    { onEvent(DocumentEvent.RenameColumn(address.row, address.cell, index, it)) },
                                    Modifier.weight(1f),
                                    placeholder = "Col ${index + 1}",
                                    singleLine = true,
                                    bold = true,
                                    textColor = colors.textPrimary,
                                )
                                if (cell.columns.size > 1) {
                                    RemoveMark(
                                        "Remove column",
                                        { onEvent(DocumentEvent.RemoveColumn(address.row, address.cell, index)) },
                                    )
                                }
                            }
                        }
                    }
                }
                Box(Modifier.width(GUTTER).fillMaxHeight().rules(body, bottom = true))
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
                "No members in this department",
                style = sheetText(14.sp),
                color = colors.textMeta,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
        cell.rows.forEachIndexed { lineIndex, row ->
            val last = lineIndex == cell.rows.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                visible.forEachIndexed { position, index ->
                    val column = cell.columns[index]
                    val atom = row.values.getOrNull(index)
                    val set: (String) -> Unit = {
                        onEvent(DocumentEvent.SetValue(address.row, address.cell, lineIndex, index, it))
                    }
                    Box(Modifier.weight(weights[position]).fillMaxHeight().rules(body, end = true, bottom = !last)) {
                        when {
                            index == 0 -> InlineText(
                                atom?.value.orEmpty(),
                                set,
                                Modifier.fillMaxSize(),
                                placeholder = "Name",
                                weight = FontWeight.Medium,
                                ground = colors.elevated,
                                onFocus = { onEvent(EditorEvent.Focus(lineIndex, index)) },
                            )
                            column.isInColumn() -> InSelector(
                                atom?.value.orEmpty(),
                                set,
                                sheetDateMs,
                                onFocus = { onEvent(EditorEvent.Focus(lineIndex, index)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> GridValue(
                                GridVariant.Crew,
                                column,
                                atom,
                                lineIndex,
                                index,
                                address,
                                members,
                                sheetDateMs,
                                walk,
                                onEvent,
                            )
                        }
                    }
                }
                Box(
                    Modifier.width(GUTTER).fillMaxHeight().rules(body, bottom = !last),
                    contentAlignment = Alignment.Center,
                ) {
                    RemoveMark(
                        "Remove row",
                        { onEvent(DocumentEvent.RemoveLine(address.row, address.cell, lineIndex)) },
                        size = 11,
                    )
                }
            }
        }
    }
    AddButtons(address, onEvent)
}

/** "Apply All" on the In column: Per HOD, O/C, or clear every member. */
@Composable
private fun ApplyAll(onApply: (String) -> Unit) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    Box {
        ZillitTooltip("Apply to all members in this department") {
            Text(
                "Apply All",
                style = sheetText(9.sp, FontWeight.Medium, 12.sp),
                color = if (hovered) Color.White else colors.accent,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (hovered) colors.accent else Color.Transparent)
                    .border(1.dp, colors.accent, RoundedCornerShape(6.dp))
                    .hoverable(source)
                    .plainClick(source = source) { open = true }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
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
                MenuOption("Per HOD") {
                    open = false
                    onApply(SheetTime.PER_HOD)
                }
                MenuOption("O/C") {
                    open = false
                    onApply(SheetTime.ON_CALL)
                }
                MenuOption("Clear All", danger = true) {
                    open = false
                    onApply("")
                }
            }
        }
    }
}

// Notes ---------------------------------------------------------------------------------------------

/** One input per note with its Remove, rules between them, and Add Note. */
@Composable
internal fun NotesEditor(cell: PageCell, address: CellAddress, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Column(Modifier.fillMaxWidth()) {
        cell.rows.forEachIndexed { index, row ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(colors.border))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetInput(
                    row.values.firstOrNull()?.value.orEmpty(),
                    { onEvent(DocumentEvent.SetValue(address.row, address.cell, index, 0, it)) },
                    Modifier.weight(1f),
                    placeholder = "Enter note",
                    onFocusChange = { if (it) onEvent(EditorEvent.Focus(index, null)) },
                )
                val (source, hovered) = rememberHover()
                Text(
                    "Remove",
                    style = sheetText(12.sp).copy(textDecoration = if (hovered) TextDecoration.Underline else null),
                    color = colors.red,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .hoverable(source)
                        .plainClick(source = source) {
                            onEvent(DocumentEvent.RemoveLine(address.row, address.cell, index))
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        if (cell.rows.isEmpty()) {
            Text(
                "No notes yet.",
                style = sheetText(12.sp),
                color = colors.textMuted,
            )
        }
        Box(Modifier.padding(top = 12.dp)) {
            SheetButton(
                "Add Note",
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
