package com.zillit.desktop.feature.crewdood.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.crewdood.domain.DayCode
import com.zillit.desktop.feature.crewdood.domain.DoodBand
import com.zillit.desktop.feature.crewdood.domain.DoodCalc
import com.zillit.desktop.feature.crewdood.domain.DoodColumn
import com.zillit.desktop.feature.crewdood.domain.DoodCrew
import com.zillit.desktop.feature.crewdood.domain.DoodPhaseKind

/**
 * The Crew DOOD grid — the design's table (`UIs/Crew DOOD (standalone).html`):
 * frozen Designation / Name / Days columns, a five-row header (phase band,
 * month, day label, date, weekday), department groups that collapse, and the
 * "Number of Crew on each day" footer on the bottom edge.
 *
 * **The day columns are drawn, not composed.** A chart can be 1,100 days wide
 * and `horizontalScroll` composes every child it holds: at a few hundred rows
 * that is hundreds of thousands of nodes, which no amount of tuning makes
 * smooth. Each row is one [Canvas] instead, painting only the columns inside
 * the viewport — `across.value` and `across.viewportSize` are read in the draw
 * lambda, so scrolling repaints without recomposing anything — and the hover
 * card and column highlight come from the pointer's x over that canvas. The
 * three frozen columns stay ordinary composables: they are what a reader
 * actually selects, and there are only three of them.
 */
@Suppress("LongMethod") // The chart's parts in order: header, rows, footer, rails, card.
@Composable
internal fun CrewDoodGrid(
    state: CrewDoodUiState,
    onEvent: (CrewDoodEvent) -> Unit,
    controller: DoodGridController,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val palette = doodPalette()
    val weeks = state.weeks
    val columns = remember(state.model, weeks) {
        if (weeks) {
            DoodCalc.weekColumns(state.model.dates, state.model.phases, state.model.calendar)
        } else {
            DoodCalc.dayColumns(state.model.dates, state.model.phases, state.model.calendar)
        }
    }
    val cellWidth = if (weeks) WEEK_CELL else DAY_CELL
    val across = rememberScrollState()
    val down = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPx = with(density) { cellWidth.toPx() }
    val header = with(density) {
        DoodHeaderRows(
            band = BAND_ROW_HEIGHT.toPx(),
            month = MONTH_ROW_HEIGHT.toPx(),
            label = LABEL_ROW_HEIGHT.toPx(),
            dayNumber = DAYNUM_ROW_HEIGHT.toPx(),
            weekday = WEEKDAY_ROW_HEIGHT.toPx(),
        )
    }
    val chart = remember(columns, cellPx, weeks, palette, colors, measurer, header) {
        DoodChart(
            columns = columns,
            cellPx = cellPx,
            palette = palette,
            colors = colors,
            weeks = weeks,
            text = DoodText(measurer),
            header = header,
            scale = density.density,
        )
    }

    /** The footer's figure per column: crew on the day, or the week's peak. */
    val columnCounts = remember(columns, state.counts) {
        columns.map { column -> column.days.maxOfOrNull { state.counts.getOrElse(it) { 0 } } ?: 0 }
    }
    val peak = (columnCounts.maxOrNull() ?: 0).coerceAtLeast(1)

    // The hover card and the column highlight live outside composition: a
    // move must repaint the columns, never recompose a row (see the class).
    val pointer = remember(columns, columnCounts, weeks, cellPx) {
        DoodPointer(
            columns = columns,
            counts = columnCounts,
            weeks = weeks,
            cellPx = cellPx,
            blocked = { across.isScrollInProgress || down.isScrollInProgress },
        )
    }

    // Scrolling hides the card and the highlight: the grid slides under a
    // still pointer, so both would describe a cell that has moved away.
    val scrolling = across.isScrollInProgress || down.isScrollInProgress
    LaunchedEffect(scrolling) { if (scrolling) pointer.clear() }

    LaunchedEffect(controller.pendingBand, columns) {
        val band = controller.pendingBand ?: return@LaunchedEffect
        val index = columns.indexOfFirst { it.band == band }
        if (index >= 0) across.animateScrollTo((index * cellPx).toInt())
        controller.consumeJump()
    }
    LaunchedEffect(controller.resetTick) { across.scrollTo(0) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            GridHeader(chart, across, pointer.lit, cellWidth) { index, at -> pointer.report(index, null, at) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GridRows(state, onEvent, chart, across, down, pointer, cellWidth)
                ZillitScrollRail(down, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            FooterRow(chart, columnCounts, peak, across, pointer.lit, cellWidth) { index, at ->
                pointer.report(index, null, at)
            }
            ZillitHorizontalScrollRail(across, Modifier.fillMaxWidth())
        }
        pointer.hover?.let { DoodHoverCard(it) }
    }
}

@Composable
private fun GridRows(
    state: CrewDoodUiState,
    onEvent: (CrewDoodEvent) -> Unit,
    chart: DoodChart,
    across: ScrollState,
    down: LazyListState,
    pointer: DoodPointer,
    cellWidth: Dp,
) {
    ZillitLazyColumn(state = down, modifier = Modifier.fillMaxSize()) {
        state.groups.forEach { group ->
            item(key = "dept:${group.key}") {
                DepartmentRow(
                    group = group,
                    collapsed = group.key in state.collapsed,
                    chart = chart,
                    across = across,
                    litColumn = pointer.lit,
                    cellWidth = cellWidth,
                    onToggle = { onEvent(CrewDoodEvent.ToggleDepartment(group.key)) },
                )
            }
            if (group.key in state.collapsed) return@forEach
            items(
                count = group.crew.size,
                key = { index ->
                    val person = group.crew[index]
                    "crew:${group.key}:${person.key.ifBlank { index.toString() }}"
                },
            ) { index ->
                val person = group.crew[index]
                CrewRow(
                    person = person,
                    chart = chart,
                    across = across,
                    litColumn = pointer.lit,
                    cellWidth = cellWidth,
                    onHover = { column, at -> pointer.report(column, person, at) },
                )
            }
        }
        // Takes the slack between the last row and the footer, so the footer
        // sits on the grid's bottom edge even with a handful of rows — the
        // web's `tr.cd-filler`.
        item(key = "filler") { Box(Modifier.fillMaxWidth().height(FILLER_HEIGHT)) }
    }
}

/**
 * Jump to and the view switch reach the grid's scroll through this.
 *
 * Public because the screen and its tool provider are: the provider remembers
 * one and turns the view model's effects into scrolls on it.
 */
class DoodGridController {
    var pendingBand by mutableStateOf<DoodBand?>(null)
        private set
    var resetTick by mutableStateOf(0)
        private set

    fun jumpTo(band: DoodBand) {
        pendingBand = band
    }

    fun consumeJump() {
        pendingBand = null
    }

    fun resetScroll() {
        resetTick += 1
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun GridHeader(
    chart: DoodChart,
    across: ScrollState,
    litColumn: MutableState<Int>,
    cellWidth: Dp,
    onHover: (Int?, Offset?) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column {
        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).background(colors.surfaceSunken)) {
            FrozenHeaderCell(str(S.designation), DESIGNATION_WIDTH)
            FrozenHeaderCell(str(S.name), NAME_WIDTH)
            FrozenHeaderCell(str(S.dm_ds_unit_days), DAYS_WIDTH, center = true)
            DayStrip(chart, across, litColumn, cellWidth, HEADER_HEIGHT, onHover) { drawHeader(it) }
        }
        Divider()
    }
}

@Composable
private fun FrozenHeaderCell(text: String, width: Dp, center: Boolean = false) {
    Box(
        Modifier.width(width).fillMaxHeight().padding(horizontal = CELL_PADDING),
        contentAlignment = if (center) Alignment.Center else Alignment.BottomStart,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(bottom = CELL_PADDING),
        )
    }
}

// ── Rows ────────────────────────────────────────────────────────────────────

@Composable
private fun DepartmentRow(
    group: DoodGroup,
    collapsed: Boolean,
    chart: DoodChart,
    across: ScrollState,
    litColumn: MutableState<Int>,
    cellWidth: Dp,
    onToggle: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DEPARTMENT_HEIGHT)
                .background(colors.surfaceSunken)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .width(DESIGNATION_WIDTH + NAME_WIDTH + DAYS_WIDTH)
                    .fillMaxHeight()
                    .padding(horizontal = CELL_PADDING),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CELL_PADDING),
            ) {
                ZillitIcon(
                    icon = if (collapsed) ZillitIcons.ChevronRight else ZillitIcons.ChevronDown,
                    tint = colors.textSecondary,
                    size = CHEVRON_SIZE,
                )
                ZillitText(
                    text = group.name,
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ZillitText(
                    text = str(S.desktop_cdood_booked_count, group.booked, group.total),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            DayStrip(chart, across, litColumn, cellWidth, DEPARTMENT_HEIGHT, { _, _ -> }) { range ->
                drawColumnBackgrounds(range, size.height, dim = true)
            }
        }
        Divider()
    }
}

@Composable
private fun CrewRow(
    person: DoodCrew,
    chart: DoodChart,
    across: ScrollState,
    litColumn: MutableState<Int>,
    cellWidth: Dp,
    onHover: (Int?, Offset?) -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .background(if (hovered) colors.surfaceHover else colors.surface)
                .hoverable(interaction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FrozenCell(person.role.ifBlank { "—" }, DESIGNATION_WIDTH, colors.textSecondary)
            FrozenCell(person.name.ifBlank { "—" }, NAME_WIDTH, colors.textPrimary)
            Box(
                Modifier.width(DAYS_WIDTH).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = if (person.days > 0) person.days.toString() else "–",
                    style = ZillitTheme.typography.bodySmall,
                    color = if (person.days > 0) colors.textPrimary else colors.textMuted,
                    maxLines = 1,
                )
            }
            DayStrip(chart, across, litColumn, cellWidth, ROW_HEIGHT, onHover) { range ->
                drawColumnBackgrounds(range, size.height, dim = false)
                drawBars(range, person)
            }
        }
        Divider()
    }
}

@Composable
private fun FrozenCell(text: String, width: Dp, color: Color) {
    Box(
        Modifier.width(width).fillMaxHeight().padding(horizontal = CELL_PADDING),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = color, maxLines = 1)
    }
}

// ── Footer ──────────────────────────────────────────────────────────────────

@Composable
private fun FooterRow(
    chart: DoodChart,
    counts: List<Int>,
    peak: Int,
    across: ScrollState,
    litColumn: MutableState<Int>,
    cellWidth: Dp,
    onHover: (Int?, Offset?) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.borderStrong))
        Row(
            Modifier.fillMaxWidth().height(FOOTER_HEIGHT).background(colors.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(DESIGNATION_WIDTH + NAME_WIDTH + DAYS_WIDTH)
                    .fillMaxHeight()
                    .padding(horizontal = CELL_PADDING),
                contentAlignment = Alignment.CenterStart,
            ) {
                ZillitText(
                    text = if (chart.weeks) {
                        str(S.desktop_cdood_peak_crew_in_week)
                    } else {
                        str(S.desktop_cdood_crew_on_day)
                    },
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
            DayStrip(chart, across, litColumn, cellWidth, FOOTER_HEIGHT, onHover) { range ->
                drawColumnBackgrounds(range, size.height, dim = false)
                drawCounts(range, counts, peak)
            }
        }
    }
}

// ── The scrolling day strip ─────────────────────────────────────────────────

/**
 * One row's worth of day columns: a canvas as wide as the whole chart inside
 * the shared horizontal scroll, painting only what the viewport shows.
 *
 * [paint] runs in the canvas's draw scope with the visible column range, so a
 * caller draws nothing it cannot see.
 */
@Composable
private fun RowScope.DayStrip(
    chart: DoodChart,
    across: ScrollState,
    litColumn: MutableState<Int>,
    cellWidth: Dp,
    height: Dp,
    onHover: (Int?, Offset?) -> Unit,
    paint: DoodPainter.(IntRange) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val width = cellWidth * chart.columns.size
    Row(Modifier.weight(1f, fill = true).fillMaxHeight().horizontalScroll(across)) {
        Canvas(
            Modifier
                .width(width)
                .height(height)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(chart, cellWidth) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Move, PointerEventType.Enter -> {
                                    val local = event.changes.last().position
                                    onHover((local.x / chart.cellPx).toInt(), origin + local)
                                }
                                PointerEventType.Exit -> onHover(null, null)
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            val range = chart.visible(across.value, across.viewportSize)
            if (!range.isEmpty()) {
                DoodPainter(this, chart, litColumn.value).paint(range)
            }
        }
    }
}

/**
 * The chart's brush: every column is painted through this, so the shading,
 * the highlight and the bars agree about where a column starts.
 */
private class DoodPainter(
    private val scope: DrawScope,
    private val chart: DoodChart,
    private val litColumn: Int,
) : DrawScope by scope {

    /** The day-off grey, the holiday hatch, the hovered column's wash and the hairlines. */
    fun drawColumnBackgrounds(range: IntRange, height: Float, dim: Boolean) {
        val palette = chart.palette
        for (index in range) {
            val column = chart.columns[index]
            val x = chart.x(index)
            if (column.off || column.holiday != null) {
                drawRect(palette.weekend, Offset(x, 0f), Size(chart.cellPx, height))
            } else if (dim) {
                drawRect(chart.colors.surfaceSunken, Offset(x, 0f), Size(chart.cellPx, height))
            }
            // A holiday: the day-off grey, hatched — the "/" a printed DOOD
            // puts on one. Drawn after the grey so a holiday that is also a
            // weekly day off still reads as a holiday.
            if (column.holiday != null) hatch(x, chart.cellPx, height, palette.holiday)
            if (index == litColumn) {
                drawRect(palette.columnHighlight, Offset(x, 0f), Size(chart.cellPx, height))
            }
            drawRect(chart.colors.border, Offset(x, 0f), Size(chart.hairline, height))
        }
    }

    private fun hatch(x: Float, width: Float, height: Float, stripe: Color) {
        // Held in locals: inside `clipRect`'s own DrawScope lambda the
        // painter's fields are out of implicit reach.
        val stroke = chart.hatchStroke
        val gap = chart.hatchGap
        clipRect(left = x, top = 0f, right = x + width, bottom = height) {
            var offset = -height
            while (offset < width) {
                drawLine(
                    color = stripe,
                    start = Offset(x + offset, height),
                    end = Offset(x + offset + height, 0f),
                    strokeWidth = stroke,
                )
                offset += gap
            }
        }
    }

    /** One person's bars across the visible columns. */
    fun drawBars(range: IntRange, person: DoodCrew) {
        val palette = chart.palette
        for (index in range) {
            val column = chart.columns[index]
            val cell = DoodCalc.cellOf(person.cells, column)
            if (cell.code == DayCode.Blank) continue
            val x = chart.x(index)
            val fill = palette.fill(cell.code)
            if (fill != null) {
                drawRect(fill, Offset(x + chart.barInset, chart.barInset),
                    Size(chart.cellPx - chart.barInset, size.height - chart.barInset * 2),
                )
                // SWF is start and finish in one cell: the design paints it as
                // a 50/50 split of the two.
                if (cell.code == DayCode.Single) {
                    val half = (chart.cellPx - chart.barInset) / 2f
                    drawRect(
                        palette.finish,
                        Offset(x + chart.barInset + half, chart.barInset),
                        Size(half, size.height - chart.barInset * 2),
                    )
                }
            }
            val label = DoodWords.bar(cell.code, cell.booked, chart.weeks)
            centred(label, chart.styles.bar(palette.ink(cell.code, chart.colors.textPrimary)), x, chart.cellPx)
        }
    }

    /** The footer's heat chips: crew on the day, washed by how close it is to the peak. */
    fun drawCounts(range: IntRange, counts: List<Int>, peak: Int) {
        for (index in range) {
            val value = counts.getOrElse(index) { 0 }
            val x = chart.x(index)
            if (value == 0) {
                centred("·", chart.styles.bar(chart.palette.dim), x, chart.cellPx)
                continue
            }
            val share = value.toFloat() / peak
            val alpha = (HEAT_FLOOR + share * HEAT_RANGE).coerceIn(0f, 1f)
            drawRect(
                chart.palette.accent.copy(alpha = alpha),
                Offset(x + chart.heatInset, chart.heatInset),
                Size(chart.cellPx - chart.heatInset * 2, size.height - chart.heatInset * 2),
            )
            val ink = if (share > HEAT_INK_SWITCH) Color.White else chart.colors.textPrimary
            centred(value.toString(), chart.styles.bar(ink), x, chart.cellPx)
        }
    }

    /** The five header rows, banded where a run of columns shares a value. */
    fun drawHeader(range: IntRange) {
        drawColumnBackgrounds(range, size.height, dim = false)
        drawBandRow(range)
        drawMonthRow(range)
        val rows = chart.header
        var top = rows.band + rows.month
        drawPerColumn(range, top, rows.label) { column ->
            DoodWords.label(column.label) to chart.styles.label(labelInk(column))
        }
        top += rows.label
        drawPerColumn(range, top, rows.dayNumber) { column ->
            val text = if (chart.weeks) DoodWords.dayMonth(column.start) else column.start.day.toString()
            text to chart.styles.dayNumber(chart.colors.textPrimary)
        }
        top += rows.dayNumber
        drawPerColumn(range, top, rows.weekday) { column ->
            val text = if (chart.weeks) {
                "– ${DoodWords.dayMonth(column.end)}"
            } else {
                DoodWords.weekday(column.start).take(WEEKDAY_LETTERS)
            }
            text to chart.styles.label(chart.colors.textMuted)
        }
    }

    private fun labelInk(column: DoodColumn): Color = when (column.label?.kind) {
        DoodPhaseKind.Shoot -> chart.palette.shootInk
        DoodPhaseKind.Prep -> chart.colors.textSecondary
        DoodPhaseKind.Post -> chart.palette.muted
        else -> chart.colors.textMuted
    }

    private fun drawBandRow(range: IntRange) {
        chart.bandRuns.forEach { run ->
            if (run.from > range.last || run.from + run.count - 1 < range.first) return@forEach
            val x = chart.x(run.from)
            val width = chart.cellPx * run.count
            val band = run.value
            drawRect(
                chart.palette.bandFill(band, chart.colors.surfaceSunken, chart.colors.surface),
                Offset(x, 0f),
                Size(width, chart.header.band),
            )
            if (band == null) return@forEach
            val count = if (chart.weeks) {
                str(S.desktop_cdood_phase_weeks, run.count)
            } else {
                val span = (run.from until run.from + run.count)
                val days = if (band == DoodBand.Shoot) {
                    span.count { chart.columns[it].label?.kind == DoodPhaseKind.Shoot }
                } else {
                    run.count
                }
                str(S.desktop_payroll_days_count, days)
            }
            val ink = chart.palette.bandInk(band, chart.colors.textPrimary)
            centred(
                "${DoodWords.band(band)}  $count",
                chart.styles.band(ink),
                x,
                width,
                top = 0f,
                rowHeight = chart.header.band,
            )
        }
    }

    private fun drawMonthRow(range: IntRange) {
        chart.monthRuns.forEach { run ->
            if (run.from > range.last || run.from + run.count - 1 < range.first) return@forEach
            val x = chart.x(run.from)
            val width = chart.cellPx * run.count
            centred(
                DoodWords.monthYear(chart.columns[run.from].start),
                chart.styles.label(chart.colors.textSecondary),
                x,
                width,
                top = chart.header.band,
                rowHeight = chart.header.month,
            )
        }
    }

    private fun drawPerColumn(
        range: IntRange,
        top: Float,
        rowHeight: Float,
        text: (DoodColumn) -> Pair<String, TextStyle>,
    ) {
        for (index in range) {
            val (label, style) = text(chart.columns[index])
            if (label.isEmpty()) continue
            centred(label, style, chart.x(index), chart.cellPx, top, rowHeight)
        }
    }

    /** Text in the middle of a cell, clipped to it so a long label cannot bleed. */
    private fun centred(
        text: String,
        style: TextStyle,
        x: Float,
        width: Float,
        top: Float = 0f,
        rowHeight: Float = size.height,
    ) {
        val layout = chart.text.layout(text, style)
        clipRect(left = x, top = top, right = x + width, bottom = top + rowHeight) {
            drawText(
                layout,
                topLeft = Offset(
                    x + (width - layout.size.width) / 2f,
                    top + (rowHeight - layout.size.height) / 2f,
                ),
            )
        }
    }
}

// ── The hover card ──────────────────────────────────────────────────────────

@Composable
private fun DoodHoverCard(hover: DoodHover) {
    val colors = ZillitTheme.colors
    androidx.compose.ui.window.Popup(
        popupPositionProvider = BesideCursor(hover.at),
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = true),
    ) {
        Column(
            Modifier
                .background(colors.surfaceRaised, ZillitTheme.shapes.medium)
                .padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            hover.person?.let { person ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    com.zillit.desktop.core.designsystem.component.ZillitAvatar(
                        name = person.name,
                        userId = person.userId.takeIf { it.isNotBlank() },
                        size = AVATAR_SIZE,
                    )
                    Column {
                        ZillitText(
                            text = person.name.ifBlank { "—" },
                            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                        )
                        if (person.role.isNotBlank()) {
                            ZillitText(
                                text = person.role,
                                style = ZillitTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            ZillitText(
                text = hover.title,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            ZillitText(
                text = hover.detail,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 2,
            )
        }
    }
}

/**
 * Beside the pointer, kept inside the window: flipped above it near the bottom
 * edge, pulled left near the right edge — the web card's own placement.
 */
private class BesideCursor(private val at: Offset) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = (at.x.toInt() + TIP_GAP)
            .coerceAtMost(windowSize.width - popupContentSize.width - TIP_MARGIN)
            .coerceAtLeast(TIP_MARGIN)
        var y = at.y.toInt() + TIP_GAP
        if (y + popupContentSize.height > windowSize.height - TIP_MARGIN) {
            y = (at.y.toInt() - popupContentSize.height - TIP_GAP).coerceAtLeast(TIP_MARGIN)
        }
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

// ── Plumbing ────────────────────────────────────────────────────────────────

/** What the hover card is showing, and where the pointer was. */
private data class DoodHover(
    val person: DoodCrew?,
    val title: String,
    val detail: String,
    val at: Offset,
)

/**
 * What the pointer is over, outside composition.
 *
 * [lit] is read in the draw lambdas only, so moving across the grid repaints
 * the columns without recomposing a single row; [hover] is snapshot state the
 * one card reads. Both go quiet while the grid scrolls ([blocked]).
 */
private class DoodPointer(
    private val columns: List<DoodColumn>,
    private val counts: List<Int>,
    private val weeks: Boolean,
    private val cellPx: Float,
    private val blocked: () -> Boolean,
) {
    var hover by mutableStateOf<DoodHover?>(null)
        private set

    val lit = mutableStateOf(-1)

    fun report(columnIndex: Int?, person: DoodCrew?, at: Offset?) {
        if (blocked()) return
        val index = columnIndex?.takeIf { it in columns.indices }
        if (index == null || at == null) {
            clear()
            return
        }
        lit.value = index
        val column = columns[index]
        hover = DoodHover(
            person = person,
            title = DoodWords.whenLine(column, weeks),
            detail = person?.let { DoodWords.stateLine(column, DoodCalc.cellOf(it.cells, column), weeks) }
                ?: crewLine(index),
            at = at,
        )
    }

    fun clear() {
        hover = null
        lit.value = -1
    }

    /** A header or footer cell: the crew on that day, or the week's peak. */
    private fun crewLine(index: Int): String {
        val value = counts.getOrElse(index) { 0 }
        return if (weeks) {
            str(S.desktop_cdood_tip_peak_crew, value)
        } else {
            str(S.desktop_cdood_tip_crew_on_day, value)
        }
    }
}

/**
 * Everything the day columns are drawn from, held together so each row's draw
 * lambda takes one object and the whole chart stays consistent.
 */
@Suppress("LongParameterList") // One object for the whole chart, by design.
private class DoodChart(
    val columns: List<DoodColumn>,
    val cellPx: Float,
    val palette: DoodPalette,
    val colors: ZillitColors,
    val weeks: Boolean,
    val text: DoodText,
    val header: DoodHeaderRows,
    /** Device pixels per dp — the hairlines and insets below are drawn in dp. */
    val scale: Float,
) {
    val hairline: Float get() = scale
    val barInset: Float get() = scale
    val hatchGap: Float get() = HATCH_GAP_DP * scale
    val hatchStroke: Float get() = scale
    val heatInset: Float get() = HEAT_INSET_DP * scale

    val styles = DoodStyles()

    /** Runs of columns sharing a band, and sharing a month — the two banded header rows. */
    val bandRuns = DoodCalc.runs(columns.map { it.band })
    val monthRuns = DoodCalc.runs(columns.map { it.start.year * MONTHS_IN_YEAR + it.start.monthNumber })

    val totalPx: Float get() = cellPx * columns.size

    /** The columns inside the viewport, plus one either side so a half-column still paints. */
    fun visible(scroll: Int, viewport: Int): IntRange {
        if (columns.isEmpty() || cellPx <= 0f) return IntRange.EMPTY
        val width = if (viewport > 0) viewport else totalPx.toInt()
        val first = ((scroll / cellPx).toInt() - 1).coerceAtLeast(0)
        val last = (((scroll + width) / cellPx).toInt() + 1).coerceAtMost(columns.size - 1)
        return first..last
    }

    fun x(index: Int): Float = index * cellPx
}

/**
 * Text laid out once and reused.
 *
 * A chart's labels are a handful of distinct strings repeated across hundreds
 * of columns — SW, W, WF, OFF, a day number — so measuring per column per
 * frame is wasted work. Dropped whole whenever the theme or the view changes,
 * because the cache is keyed on the style's colour as well as the text.
 */
private class DoodText(private val measurer: TextMeasurer) {
    private val cache = HashMap<String, TextLayoutResult>()

    fun layout(text: String, style: TextStyle): TextLayoutResult =
        cache.getOrPut("${style.color.value}|${style.fontSize.value}|${style.fontWeight?.weight}|$text") {
            measurer.measure(AnnotatedString(text), style)
        }
}

/** The header's five row heights, in device pixels. */
private class DoodHeaderRows(
    val band: Float,
    val month: Float,
    val label: Float,
    val dayNumber: Float,
    val weekday: Float,
)

/** The styles the canvas draws with — one place, so the rows cannot drift apart. */
private class DoodStyles {
    fun bar(color: Color) = TextStyle(color = color, fontSize = BAR_FONT, fontWeight = FontWeight.SemiBold)
    fun band(color: Color) = TextStyle(color = color, fontSize = BAND_FONT, fontWeight = FontWeight.SemiBold)
    fun label(color: Color) = TextStyle(color = color, fontSize = LABEL_FONT)
    fun dayNumber(color: Color) = TextStyle(color = color, fontSize = DAY_FONT, fontWeight = FontWeight.Medium)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
}

private val DESIGNATION_WIDTH = 196.dp
private val NAME_WIDTH = 168.dp
private val DAYS_WIDTH = 56.dp
private val DAY_CELL = 48.dp
private val WEEK_CELL = 96.dp
private val ROW_HEIGHT = 36.dp
private val DEPARTMENT_HEIGHT = 34.dp
private val FOOTER_HEIGHT = 40.dp
private val FILLER_HEIGHT = 1.dp
private val HAIRLINE = 1.dp
private val CELL_PADDING = 8.dp
private val CHEVRON_SIZE = 14.dp
private val AVATAR_SIZE = 28.dp

/** The five header rows. The canvas draws them itself, from [DoodChart.header]. */
private val BAND_ROW_HEIGHT = 30.dp
private val MONTH_ROW_HEIGHT = 22.dp
private val LABEL_ROW_HEIGHT = 26.dp
private val DAYNUM_ROW_HEIGHT = 24.dp
private val WEEKDAY_ROW_HEIGHT = 20.dp
private val HEADER_HEIGHT =
    BAND_ROW_HEIGHT + MONTH_ROW_HEIGHT + LABEL_ROW_HEIGHT + DAYNUM_ROW_HEIGHT + WEEKDAY_ROW_HEIGHT

private val BAR_FONT = 11.sp
private val BAND_FONT = 11.sp
private val LABEL_FONT = 10.sp
private val DAY_FONT = 12.sp

private const val HATCH_GAP_DP = 7f
private const val HEAT_INSET_DP = 4f
private const val HEAT_FLOOR = 0.10f
private const val HEAT_RANGE = 0.80f
private const val HEAT_INK_SWITCH = 0.5f
private const val WEEKDAY_LETTERS = 2
private const val MONTHS_IN_YEAR = 12
private const val TIP_GAP = 14
private const val TIP_MARGIN = 8
