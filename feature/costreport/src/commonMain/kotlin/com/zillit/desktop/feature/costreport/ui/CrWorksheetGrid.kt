// One composable per row kind; the cell rules branch on the column.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "LongParameterList")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.CrCalc
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrColumnGroup
import com.zillit.desktop.feature.costreport.domain.CrFigures
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.CrLineFilter
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrRow
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CrTable
import com.zillit.desktop.feature.costreport.domain.SortDirection
import kotlin.math.roundToLong

internal val ACCOUNT_WIDTH: Dp = 280.dp
private val MIN_VALUE_WIDTH: Dp = 86.dp
private val ROW_MIN: Dp = 36.dp
private val CELL_PAD: Dp = 4.dp
private val ACCOUNT_PAD: Dp = 10.dp
private val CHEVRON_SLOT: Dp = 12.dp

/** What the grid asks of whichever pane hosts it. */
internal data class CrGridActions(
    val onSection: (String) -> Unit,
    val onHeader: (String) -> Unit,
    val onNominal: (String) -> Unit,
    /** Null where there is no ledger to open. */
    val onLedger: ((CrNominal, CrColumn?) -> Unit)? = null,
    /** Null where the columns do not sort — the Live CR. */
    val onSort: ((CrColumn) -> Unit)? = null,
    /** Null leaves ETC, EFC and VTP read-only. */
    val onCommit: ((CrNominal, CrColumn, Double) -> Unit)? = null,
    val onClearFlat: () -> Unit = {},
)

/**
 * The worksheet table — the web's `WorksheetTable`.
 *
 * The eleven value columns share whatever width the pane has beyond the
 * account column, as the web's fixed table layout does; only when that would
 * squeeze a column below a legible width does the table scroll sideways as
 * one piece. The two-row grouped header stays above the rows and the grand
 * total below them.
 */
@Composable
internal fun CrWorksheetGrid(
    table: CrTable,
    symbol: String,
    decimals: Int,
    projectName: String,
    sort: CrSort,
    locked: Boolean,
    actions: CrGridActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = ZillitTheme.colors
    var editing by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface)) {
        val columns = CrColumn.entries.size
        val valueWidth = max(MIN_VALUE_WIDTH, (maxWidth - ACCOUNT_WIDTH) / columns)
        val tableWidth = ACCOUNT_WIDTH + valueWidth * columns
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().then(if (tableWidth > maxWidth) Modifier.horizontalScroll(scroll) else Modifier)) {
            Column(Modifier.width(tableWidth).fillMaxHeight()) {
                GridHeader(valueWidth, sort, readOnly = actions.onCommit == null, onSort = actions.onSort)
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(table.rows, key = { it.key }) { row ->
                        when (row) {
                            is CrRow.Section -> SectionRow(row, table.search.needle, actions)
                            is CrRow.Header -> HeaderRow(
                                row,
                                table.search.needle,
                                valueWidth,
                                symbol,
                                decimals,
                                actions,
                            )
                            is CrRow.HeaderTotal -> HeaderTotalRow(row, valueWidth, symbol, decimals)
                            is CrRow.Nominal -> NominalRow(
                                row = row,
                                needle = table.search.needle,
                                valueWidth = valueWidth,
                                symbol = symbol,
                                decimals = decimals,
                                locked = locked,
                                actions = actions,
                                editing = editing,
                                onEditing = { editing = it },
                            )
                            is CrRow.Set -> SetRow(row, table.search.needle, valueWidth, symbol, decimals)
                            is CrRow.ModeStrip -> ModeStripRow(row, actions.onClearFlat)
                            is CrRow.NoMatches -> NoMatchesRow(row)
                        }
                    }
                }
                GrandTotalRow(
                    figures = table.grandTotal,
                    label = (if (table.flat) "Grand Total — All Lines" else "Grand Total — $projectName").uppercase(),
                    valueWidth = valueWidth,
                    symbol = symbol,
                    decimals = decimals,
                )
            }
        }
    }
}

// -- header ---------------------------------------------------------------------------

@Composable
private fun GridHeader(valueWidth: Dp, sort: CrSort, readOnly: Boolean, onSort: ((CrColumn) -> Unit)?) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surfaceSunken)) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier.width(ACCOUNT_WIDTH).height(GROUP_ROW_HEIGHT).padding(horizontal = 14.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                ZillitText(
                    text = "ACCOUNT",
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                    ),
                    color = colors.textMuted,
                )
            }
            CrColumnGroup.entries.forEach { group ->
                val span = CrColumn.entries.count { it.group == group }
                val accent = CrPalette.group(group)
                Box(
                    modifier = Modifier
                        .width(valueWidth * span)
                        .height(GROUP_ROW_HEIGHT)
                        .drawBehind {
                            val stroke = 2.dp.toPx()
                            drawRect(
                                accent ?: colors.divider,
                                Offset(0f, size.height - stroke),
                                Size(size.width, stroke),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (group.label.isNotEmpty()) {
                        ZillitText(
                            text = group.label.uppercase(),
                            style = ZillitTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp,
                                fontSize = 9.5.sp,
                            ),
                            color = accent ?: colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ACCOUNT_WIDTH))
            CrColumn.entries.forEach { column ->
                val active = sort.column == column
                val editable = column.isEditable && !readOnly
                val labelColor = when {
                    active -> CrPalette.cta
                    editable -> CrPalette.EDITABLE_ACCENT
                    else -> colors.textMuted
                }
                Row(
                    modifier = Modifier
                        .width(valueWidth)
                        .heightIn(min = 44.dp)
                        .background(if (editable) CrPalette.EDITABLE_TINT else Color.Transparent)
                        .then(if (onSort != null) Modifier.clickable { onSort(column) } else Modifier)
                        .padding(horizontal = CELL_PAD, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        val style = ZillitTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                            fontSize = 9.5.sp,
                            lineHeight = 12.sp,
                        )
                        ZillitText(
                            column.line1.uppercase(),
                            style = style,
                            color = labelColor,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        if (column.line2.isNotBlank()) {
                            ZillitText(
                                column.line2.uppercase(),
                                style = style,
                                color = labelColor,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                    if (editable) {
                        ZillitIcon(
                            ZillitIcons.Edit,
                            tint = CrPalette.EDITABLE_ACCENT,
                            size = 9.dp,
                            modifier = Modifier.padding(start = 3.dp),
                        )
                    }
                    if (onSort != null) {
                        ZillitText(
                            text = when {
                                !active -> "↕"
                                sort.direction == SortDirection.Descending -> "▼"
                                else -> "▲"
                            },
                            style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (active) CrPalette.cta else colors.textMuted,
                            modifier = Modifier.padding(start = 3.dp),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

private val GROUP_ROW_HEIGHT = 28.dp

// -- rows -----------------------------------------------------------------------------------

@Composable
private fun SectionRow(row: CrRow.Section, needle: String, actions: CrGridActions) {
    val colors = ZillitTheme.colors
    val accent = CrPalette.section(row.section.id)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(CrPalette.CTA.copy(alpha = 0.045f))
            .leftBar(accent, 3.dp)
            .clickable { actions.onSection(row.section.id) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(
            icon = if (row.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
            tint = accent,
            size = 11.dp,
        )
        Text(
            text = highlighted(row.section.sec.uppercase(), needle),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp,
            ),
            color = accent,
            maxLines = 1,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

@Composable
private fun HeaderRow(
    row: CrRow.Header,
    needle: String,
    valueWidth: Dp,
    symbol: String,
    decimals: Int,
    actions: CrGridActions,
) {
    val colors = ZillitTheme.colors
    val accent = if (row.figures.tv < 0) CrPalette.over else CrPalette.section(row.section.id)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(hover)
            .clickable { actions.onHeader(row.header.code) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountCell(
            open = row.open,
            hasChildren = row.header.nominals.isNotEmpty(),
            code = row.header.code,
            name = row.header.name,
            needle = needle,
            codeColor = colors.textPrimary,
            nameWeight = FontWeight.Bold,
            uppercase = true,
            leftAccent = accent,
            badge = row.badge?.let { it to CrPalette.section(row.section.id) },
        )
        CrColumn.entries.forEach { column ->
            ValueCell(
                text = if (row.showValues) CrFormat.grid(row.figures.value(column), symbol, column, decimals) else "",
                color = cellColor(row.figures.value(column), column),
                bold = true,
                width = valueWidth,
                groupStart = column.startsGroup,
                background = CrPalette.EDITABLE_TINT.copy(alpha = HEADER_TINT_ALPHA)
                    .takeIf { column.isEditable && actions.onCommit != null },
            )
        }
    }
    Divider()
}

@Composable
private fun HeaderTotalRow(row: CrRow.HeaderTotal, valueWidth: Dp, symbol: String, decimals: Int) {
    val colors = ZillitTheme.colors
    val accent = if (row.figures.tv < 0) CrPalette.over else CrPalette.section(row.section.id)
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderStrong))
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN).background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(ACCOUNT_WIDTH)
                .heightIn(min = ROW_MIN)
                .leftBar(accent, 3.dp)
                .padding(horizontal = ACCOUNT_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(CHEVRON_SLOT + 6.dp))
            ZillitText(
                text = "TOTAL",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
                color = colors.textSecondary,
            )
        }
        CrColumn.entries.forEach { column ->
            ValueCell(
                text = CrFormat.grid(row.figures.value(column), symbol, column, decimals),
                color = cellColor(row.figures.value(column), column),
                bold = true,
                width = valueWidth,
                groupStart = column.startsGroup,
            )
        }
    }
    Divider()
}

@Composable
private fun NominalRow(
    row: CrRow.Nominal,
    needle: String,
    valueWidth: Dp,
    symbol: String,
    decimals: Int,
    locked: Boolean,
    actions: CrGridActions,
    editing: String?,
    onEditing: (String?) -> Unit,
) {
    val colors = ZillitTheme.colors
    val nominal = row.nominal
    val ledger = actions.onLedger?.takeIf { !nominal.isContractual }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(hover)
            .then(if (nominal.hasSets) Modifier.clickable { actions.onNominal(nominal.identity) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountCell(
            open = row.open,
            hasChildren = nominal.hasSets,
            code = nominal.code,
            name = nominal.name,
            needle = needle,
            codeColor = colors.textSecondary,
            nameWeight = FontWeight.Medium,
            onCode = ledger?.let { { it(nominal, null) } },
        )
        CrColumn.entries.forEach { column ->
            val value = row.figures.value(column)
            val commit = actions.onCommit
            if (commit != null && column.isEditable) {
                val key = "${row.key}|${column.key}"
                EditableCell(
                    value = value,
                    column = column,
                    over = column == CrColumn.Etc && value < 0,
                    locked = locked,
                    editing = editing == key,
                    symbol = symbol,
                    decimals = decimals,
                    width = valueWidth,
                    groupStart = column.startsGroup,
                    onStart = { onEditing(key) },
                    onDone = { typed ->
                        onEditing(null)
                        typed?.let { commit(nominal, column, it) }
                    },
                )
            } else {
                val drill = ledger != null && (column.isActuals || column.isCommits)
                ValueCell(
                    text = CrFormat.grid(value, symbol, column, decimals),
                    color = cellColor(value, column),
                    bold = false,
                    width = valueWidth,
                    groupStart = column.startsGroup,
                    onClick = if (drill) ({ ledger?.invoke(nominal, column) }) else null,
                )
            }
        }
    }
    Divider()
}

@Composable
private fun SetRow(row: CrRow.Set, needle: String, valueWidth: Dp, symbol: String, decimals: Int) {
    val colors = ZillitTheme.colors
    val line = row.set.line
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN).background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountCell(
            open = false,
            hasChildren = false,
            code = row.set.code,
            name = row.set.name,
            needle = needle,
            codeColor = colors.textMuted,
            nameWeight = FontWeight.Normal,
        )
        CrColumn.entries.forEach { column ->
            val value = when (column) {
                CrColumn.Atp -> line.atp
                CrColumn.Atd -> line.atd
                CrColumn.Po -> line.po
                CrColumn.Card -> line.card
                CrColumn.Cash -> line.cash
                CrColumn.Pr -> line.pr
                else -> null
            }
            ValueCell(
                text = if (value == null) CrFormat.DASH else CrFormat.grid(value, symbol, column, decimals),
                color = if (value == null || value == 0.0) colors.textMuted else null,
                bold = false,
                width = valueWidth,
                groupStart = column.startsGroup,
            )
        }
    }
    Divider()
}

@Composable
private fun ModeStripRow(row: CrRow.ModeStrip, onClear: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CrPalette.CTA.copy(alpha = 0.04f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val parts = buildList {
            if (row.filter != CrLineFilter.All) add("Filter: ${row.filter.stripLabel}")
            row.sort.column?.let { column ->
                val direction = if (row.sort.direction == SortDirection.Descending) "(high→low)" else "(low→high)"
                add("Sort: ${column.label} $direction")
            }
        }
        ZillitText(
            text = "${row.lines} line${if (row.lines == 1) "" else "s"}",
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = CrPalette.cta,
        )
        ZillitText(
            text = "  ·  " + parts.joinToString(" · "),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitText(
            text = "Clear ✕",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 4.dp),
        )
    }
    Divider()
}

@Composable
private fun NoMatchesRow(row: CrRow.NoMatches) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 14.dp), contentAlignment = Alignment.Center) {
        ZillitText(
            text = "No codes or descriptions match “${row.query}”.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun GrandTotalRow(figures: CrFigures, label: String, valueWidth: Dp, symbol: String, decimals: Int) {
    val ink = CrPalette.grandTotalInk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CrPalette.grandTotalBackground)
            .drawBehind { drawRect(ink.copy(alpha = 0.3f), Offset.Zero, Size(size.width, 2.dp.toPx())) }
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(ACCOUNT_WIDTH).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(6.dp).background(ink, CircleShape))
            ZillitText(
                text = label,
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp),
                color = ink,
                maxLines = 2,
            )
        }
        CrColumn.entries.forEach { column ->
            val value = figures.value(column)
            val color = when {
                !column.isVariance || value == 0.0 -> ink
                value < 0 -> CrPalette.over
                else -> CrPalette.under
            }
            ValueCell(
                text = CrFormat.grid(value, symbol, column, decimals),
                color = color,
                bold = true,
                width = valueWidth,
                groupStart = column.startsGroup,
                weight = FontWeight.ExtraBold,
            )
        }
    }
}

// -- cells -----------------------------------------------------------------------------------

/**
 * The account column: a chevron slot on every row (so codes line up in a true
 * column whether or not a row has children), an optional section badge, the
 * code — a link into the ledger where there is one — and the name.
 */
@Composable
private fun AccountCell(
    open: Boolean,
    hasChildren: Boolean,
    code: String,
    name: String,
    needle: String,
    codeColor: Color,
    nameWeight: FontWeight,
    uppercase: Boolean = false,
    leftAccent: Color? = null,
    badge: Pair<String, Color>? = null,
    onCode: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .width(ACCOUNT_WIDTH)
            .heightIn(min = ROW_MIN)
            .then(if (leftAccent != null) Modifier.leftBar(leftAccent, 3.dp) else Modifier)
            .drawBehind {
                drawRect(colors.borderStrong, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
            }
            .padding(horizontal = ACCOUNT_PAD, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(CHEVRON_SLOT)) {
            if (hasChildren) {
                ZillitIcon(
                    icon = if (open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    tint = colors.textSecondary,
                    size = 10.dp,
                )
            }
        }
        badge?.let { (text, tint) ->
            ZillitText(
                text = text,
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = tint,
                modifier = Modifier
                    .background(tint.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (code.isNotEmpty()) {
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Text(
                text = highlighted(code, needle),
                style = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                color = if (onCode != null) CrPalette.ACTUALS else codeColor,
                maxLines = 1,
                modifier = Modifier
                    .then(
                        if (onCode != null) {
                            Modifier
                                .hoverable(hover)
                                .background(
                                    if (hovered) Color(0x1A14A394) else Color.Transparent,
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable(onClick = onCode)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        Text(
            text = highlighted(if (uppercase) name.uppercase() else name, needle),
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = nameWeight,
                letterSpacing = if (uppercase) 0.4.sp else 0.sp,
                lineHeight = 17.sp,
            ),
            color = colors.textPrimary,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ValueCell(
    text: String,
    color: Color?,
    bold: Boolean,
    width: Dp,
    groupStart: Color?,
    background: Color? = null,
    weight: FontWeight? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = ROW_MIN)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .then(if (groupStart != null) Modifier.leftBar(groupStart, 2.dp) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = CELL_PAD, vertical = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.numeric.copy(
                fontWeight = weight ?: if (bold) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = (-0.2).sp,
            ),
            color = if (text == CrFormat.DASH) colors.textMuted else color ?: colors.textPrimary,
            maxLines = 2,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * An ETC, EFC or VTP cell: a teal wash marks it editable, one click opens it,
 * Enter or clicking away commits, Escape abandons. It takes arithmetic
 * (`1200*3`) as the web's calculator field does. Opening a cell and leaving
 * it untouched commits nothing — otherwise a stray click would pin a derived
 * figure as an override, and the cell would stop following the actuals.
 */
@Composable
private fun EditableCell(
    value: Double,
    column: CrColumn,
    over: Boolean,
    locked: Boolean,
    editing: Boolean,
    symbol: String,
    decimals: Int,
    width: Dp,
    groupStart: Color?,
    onStart: () -> Unit,
    onDone: (Double?) -> Unit,
) {
    val colors = ZillitTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ring = if (over) CrPalette.over else CrPalette.EDITABLE_ACCENT
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = ROW_MIN)
            .background(if (over) CrPalette.OVER_TINT else CrPalette.EDITABLE_TINT)
            .then(if (groupStart != null) Modifier.leftBar(groupStart, 2.dp) else Modifier)
            .hoverable(hover)
            .drawBehind {
                if (hovered && !locked && !editing) {
                    val stroke = 1.5.dp.toPx()
                    val inset = 3.dp.toPx()
                    drawRect(
                        color = ring.copy(alpha = 0.5f),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(stroke),
                    )
                }
            }
            .alpha(if (locked) LOCKED_ALPHA else 1f)
            .then(if (!locked && !editing) Modifier.clickable(onClick = onStart) else Modifier)
            .padding(horizontal = CELL_PAD, vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (editing) {
            CellEditor(initial = plainNumber(value), over = over, onDone = onDone)
        } else {
            ZillitText(
                text = CrFormat.grid(value, symbol, column, decimals),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
                color = when {
                    value == 0.0 -> colors.textMuted
                    else -> cellColor(value, column) ?: colors.textPrimary
                },
                maxLines = 2,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun CellEditor(initial: String, over: Boolean, onDone: (Double?) -> Unit) {
    val colors = ZillitTheme.colors
    var text by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        if (!commit || text.trim() == initial) {
            onDone(null)
            return
        }
        val typed = if (CrCalc.isExpression(text)) {
            CrCalc.evaluate(text)
        } else {
            text.replace(",", "").trim().toDoubleOrNull() ?: 0.0
        }
        onDone(typed?.let { it.roundToLong().toDouble() })
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { next -> if (CrCalc.allows(next)) text = next },
        singleLine = true,
        textStyle = ZillitTheme.typography.numeric.copy(color = colors.textPrimary, textAlign = TextAlign.End),
        cursorBrush = SolidColor(colors.textPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(4.dp))
            .drawBehind {
                drawRect(
                    color = if (over) CrPalette.LOCK_RED else CrPalette.ACTUALS,
                    style = Stroke(1.dp.toPx()),
                )
            }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) focused = true else if (focused) finish(commit = true)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { finish(commit = true); true }
                    Key.Escape -> { finish(commit = false); true }
                    else -> false
                }
            },
    )
}

/** The value as the field starts: blank for zero, pennies trimmed. */
private fun plainNumber(value: Double): String {
    if (value == 0.0) return ""
    val pennies = (value * PENNIES).roundToLong()
    if (pennies % PENNIES.toLong() == 0L) return (pennies / PENNIES.toLong()).toString()
    return Money.group(value, 2).replace(",", "")
}

private const val PENNIES = 100.0
private const val HEADER_TINT_ALPHA = 0.08f
private const val LOCKED_ALPHA = 0.55f

// -- helpers -----------------------------------------------------------------------------------

/** Variance columns read red when over and green when under; the rest take the row's ink. */
@Composable
private fun cellColor(value: Double, column: CrColumn): Color? = when {
    !column.isVariance || value == 0.0 -> null
    value < 0 -> CrPalette.over
    else -> CrPalette.under
}

/** The group's accent where a column opens a group — the 2dp band down the table. */
private val CrColumn.startsGroup: Color?
    get() = if (CrColumn.entries.first { it.group == group } == this) CrPalette.group(group) else null

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.divider))
}

private fun Modifier.leftBar(color: Color, width: Dp): Modifier = drawBehind {
    drawRect(color, Offset.Zero, Size(width.toPx(), size.height))
}

/** [text] with every occurrence of [needle] marked, as the web's `<Highlight>`. */
internal fun highlighted(text: String, needle: String): AnnotatedString {
    if (needle.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    return buildAnnotatedString {
        var at = 0
        var hit = lower.indexOf(needle, at)
        while (hit >= 0) {
            append(text.substring(at, hit))
            withStyle(SpanStyle(background = HIGHLIGHT)) { append(text.substring(hit, hit + needle.length)) }
            at = hit + needle.length
            hit = lower.indexOf(needle, at)
        }
        append(text.substring(at))
    }
}

private val HIGHLIGHT = Color(0x66FBBF24)
