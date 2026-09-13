package com.zillit.desktop.feature.assetreport.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.assetreport.domain.AssetCurrency
import com.zillit.desktop.feature.assetreport.domain.AssetDepartment
import com.zillit.desktop.feature.assetreport.domain.AssetFormat
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.ui.AssetEvent
import com.zillit.desktop.feature.assetreport.ui.AssetUiState
import com.zillit.desktop.feature.assetreport.ui.components.AssetExportMenu
import com.zillit.desktop.feature.assetreport.ui.components.AssetSearchField
import com.zillit.desktop.feature.assetreport.ui.components.AssetSelect
import com.zillit.desktop.feature.assetreport.ui.components.AssetTopBar
import com.zillit.desktop.feature.assetreport.ui.components.CategoryPill
import com.zillit.desktop.feature.assetreport.ui.components.CategorySegments
import com.zillit.desktop.feature.assetreport.ui.components.ExpenseCell
import com.zillit.desktop.feature.assetreport.ui.components.FieldLabel

/**
 * The register: the filters, the lines as one grid, the pinned total — the
 * web's `AssetReportModule` landing, column for column.
 */
@Composable
internal fun AssetRegisterPage(state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        AssetTopBar(
            backLabel = "Back to Film Tools",
            onBack = { onEvent(AssetEvent.Leave) },
            title = {
                ZillitText(
                    text = "Asset Register",
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            },
            right = {
                if (!state.isLoading) {
                    val count = state.visible.size
                    ZillitText(
                        text = "$count asset${if (count == 1) "" else "s"} · " +
                            AssetFormat.money(state.total, state.currencySymbol),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
                AssetExportMenu(
                    exporting = state.exporting,
                    enabled = !state.isLoading,
                    onExport = { onEvent(AssetEvent.Export(it)) },
                )
            },
        )
        RegisterBody(state, onEvent, Modifier.weight(1f))
        if (!state.isLoading) TotalBar(state)
    }
}

@Composable
private fun RegisterBody(state: AssetUiState, onEvent: (AssetEvent) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The grid keeps its columns' minimums and scrolls across rather than crushing them.
        val tableWidth = if (maxWidth > TABLE_MIN_WIDTH) maxWidth else TABLE_MIN_WIDTH
        val across = rememberScrollState()
        val down = rememberLazyListState()
        Box(Modifier.fillMaxSize().horizontalScroll(across, enabled = tableWidth > maxWidth)) {
            ZillitLazyColumn(
                state = down,
                modifier = Modifier.width(tableWidth).fillMaxHeight(),
                contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            ) {
                item(key = "filters") { FilterBar(state, onEvent) }
                item(key = "head") { TableHead() }
                when {
                    state.isLoading -> items(SKELETON_ROWS.toList(), key = { "skeleton-$it" }) { SkeletonRow(it) }
                    state.visible.isEmpty() -> item(key = "empty") { NoResult() }
                    else -> items(state.visible, key = { "line-${it.lineItemId}" }) { line ->
                        LineRow(
                            line = line,
                            state = state,
                            active = state.detail?.line?.lineItemId == line.lineItemId,
                            onOpen = { onEvent(AssetEvent.Open(line.lineItemId)) },
                        )
                    }
                }
                item(key = "tail") { Rule(ZillitTheme.colors.border) }
            }
        }
        if (tableWidth > maxWidth) ZillitHorizontalScrollRail(across, Modifier.align(Alignment.BottomCenter))
    }
}

// -- filters ----------------------------------------------------------------------------

@Composable
private fun FilterBar(state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // Privileged only: everyone else is scoped to their own department server-side.
        if (state.viewer.privileged) {
            FilterField("Department", Modifier.width(DEPARTMENT_WIDTH)) {
                AssetSelect(
                    options = state.departments,
                    selectedKeys = state.departmentFilter,
                    key = AssetDepartment::id,
                    label = { state.departmentName(it.id) },
                    onPick = { picked ->
                        val ids = state.departmentFilter
                        val next = if (picked.id in ids) ids - picked.id else ids + picked.id
                        onEvent(AssetEvent.FilterDepartments(next))
                    },
                    onClear = { onEvent(AssetEvent.FilterDepartments(emptyList())) },
                    placeholder = if (state.departmentsLoading) "Loading…" else "All departments",
                    multiple = true,
                )
            }
        }
        FilterField("Currency", Modifier.width(CURRENCY_WIDTH)) {
            val choices = state.currencies.choices
            AssetSelect(
                options = choices,
                selectedKeys = listOfNotNull(state.activeCurrency.takeIf { it.isNotBlank() }),
                key = AssetCurrency::code,
                label = AssetCurrency::code,
                searchText = { "${it.code} ${it.name}" },
                onPick = { onEvent(AssetEvent.PickCurrency(it.code)) },
                // Clearing returns to the production's default, so it is offered only away from it.
                onClear = { onEvent(AssetEvent.PickCurrency(null)) }.takeIf {
                    state.currencyCode.isNotBlank() && !state.currencyCode.equals(state.currencies.defaultCode, true)
                },
                placeholder = "Currency",
                rowHeight = CURRENCY_ROW_HEIGHT,
                row = { CurrencyRow(it, state.currencies.symbolFor(it.code)) },
            )
        }
        FilterField("Category") {
            CategorySegments(selected = state.categoryFilter, onSelect = { onEvent(AssetEvent.FilterCategory(it)) })
        }
        Spacer(Modifier.weight(1f))
        AssetSearchField(value = state.query, onValueChange = { onEvent(AssetEvent.Search(it)) })
    }
}

@Composable
private fun FilterField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        content()
    }
}

/** The web's `CurrencyOption`: the symbol on a monogram, the code over its name. */
@Composable
private fun CurrencyRow(currency: AssetCurrency, symbol: String) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = symbol.trim().ifBlank { currency.code.take(1) },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        Column {
            ZillitText(
                text = currency.code,
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            if (currency.name.isNotBlank()) {
                ZillitText(
                    text = currency.name,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

// -- the grid ------------------------------------------------------------------------------

/** The web's grid: fixed columns hold their width, the three named ones share the rest. */
private enum class Col(val title: String, val fixed: Dp?, val share: Float = 0f, val end: Boolean = false) {
    Code("Code", 88.dp),
    Asset("Asset", null, share = 1.5f),
    Vendor("Vendor", null, share = 0.9f),
    Department("Department", null, share = 0.9f),
    Ref("Ref", 118.dp),
    Exp("Exp. Type", 172.dp),
    Qty("Qty", 62.dp, end = true),
    Unit("Unit Cost", 134.dp, end = true),
    Total("Total", 150.dp, end = true),
}

@Composable
private fun TableHead() {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surfaceSunken)) {
        Rule(colors.border)
        GridRow {
            Col.entries.forEach { col ->
                Cell(col) {
                    ZillitText(
                        text = col.title.uppercase(),
                        style = ZillitTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.1.em,
                        ),
                        color = colors.textMuted,
                        maxLines = 1,
                        textAlign = if (col.end) TextAlign.End else TextAlign.Start,
                    )
                }
            }
        }
        Rule(colors.border)
    }
}

@Composable
private fun LineRow(line: AssetLine, state: AssetUiState, active: Boolean, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            active -> colors.accentSoft
            hovered -> colors.surfaceSunken
            else -> colors.surface
        },
        label = "assetRow",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        GridRow {
            Cell(Col.Code) { Mono(line.account, weight = FontWeight.SemiBold, size = 13f, color = colors.textMuted) }
            Cell(Col.Asset) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZillitText(
                        text = line.description,
                        style = ZillitTheme.typography.bodyLarge.copy(
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.005).em,
                        ),
                        color = colors.textPrimary,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    CategoryPill(line.category)
                }
            }
            Cell(Col.Vendor) { Plain(state.vendorName(line.vendorId)) }
            Cell(Col.Department) { Plain(state.departmentName(line.departmentId)) }
            Cell(Col.Ref) { Mono(line.poNumber, size = 13f, color = colors.textSecondary) }
            Cell(Col.Exp) { ExpenseCell(line) }
            Cell(Col.Qty) { Mono(AssetFormat.quantity(line.quantity), weight = FontWeight.Medium) }
            Cell(Col.Unit) { Mono(state.money(line.unitPrice, line.currency), weight = FontWeight.Medium) }
            Cell(Col.Total) {
                Mono(state.money(line.total, line.currency), weight = FontWeight.Bold, color = colors.textPrimary)
            }
        }
        Rule(colors.divider)
    }
}

/** Seven rows of pulsing bars laid on the real grid, so nothing jumps when the lines land. */
@Composable
private fun SkeletonRow(index: Int) {
    Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
        GridRow {
            Col.entries.forEachIndexed { column, col ->
                Cell(col) {
                    // The asset column's bars vary a little row to row, so the block reads as text.
                    val width = SKELETON_WIDTHS[column] - if (col == Col.Asset) (index % 3) * 26 else 0
                    val side = if (col.end) Alignment.CenterEnd else Alignment.CenterStart
                    Box(Modifier.fillMaxWidth(), contentAlignment = side) {
                        ZillitSkeletonBar(Modifier.width(width.dp), height = 11.dp)
                    }
                }
            }
        }
        Rule(ZillitTheme.colors.divider)
    }
}

@Composable
private fun NoResult() {
    Box(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.surface).padding(vertical = 54.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = "No assets match your filters.",
            style = ZillitTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The grand total, pinned under the scroller so it stays in view — tax lines excluded. */
@Composable
private fun TotalBar(state: AssetUiState) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surfaceSunken)) {
        Rule(colors.border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "TOTAL",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.1.em),
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = AssetFormat.money(state.total, state.currencySymbol),
                style = ZillitTheme.typography.numeric.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.01).em,
                ),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

// -- grid pieces ------------------------------------------------------------------------------

@Composable
private fun GridRow(content: @Composable RowScope.() -> Unit) {
    // Intrinsic height so every cell — and its column rule — runs the row's full height.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), content = content)
}

@Composable
private fun RowScope.Cell(col: Col, content: @Composable () -> Unit) {
    val rule = ZillitTheme.colors.divider
    val last = col == Col.entries.last()
    val sized = col.fixed?.let { Modifier.width(it) } ?: Modifier.weight(col.share)
    Box(
        modifier = sized
            .fillMaxHeight()
            .drawBehind {
                if (!last) drawLine(rule, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
            }
            .padding(
                start = if (col == Col.entries.first()) EDGE_PADDING else CELL_PADDING,
                end = if (last) EDGE_PADDING else CELL_PADDING,
                top = 14.dp,
                bottom = 14.dp,
            ),
        contentAlignment = if (col.end) Alignment.CenterEnd else Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun Plain(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyLarge,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 2,
    )
}

@Composable
private fun Mono(
    text: String,
    weight: FontWeight = FontWeight.Normal,
    size: Float = 13.5f,
    color: androidx.compose.ui.graphics.Color = ZillitTheme.colors.textSecondary,
) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.numeric.copy(fontSize = size.sp, fontWeight = weight),
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun Rule(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 88 + 118 + 172 + 62 + 134 + 150 fixed, plus the named columns' minimums (230 + 140 + 140). */
private val TABLE_MIN_WIDTH = 1234.dp
private val DEPARTMENT_WIDTH = 250.dp
private val CURRENCY_WIDTH = 150.dp
private val CURRENCY_ROW_HEIGHT = 56.dp
private val EDGE_PADDING = 26.dp
private val CELL_PADDING = 16.dp
private val SKELETON_ROWS = 0 until 7
private val SKELETON_WIDTHS = listOf(56, 210, 96, 92, 74, 96, 24, 78, 90)
