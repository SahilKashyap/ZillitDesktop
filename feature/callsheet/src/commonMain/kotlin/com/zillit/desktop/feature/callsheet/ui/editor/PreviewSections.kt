// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.callsheet.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.WeatherValue
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

// Branch 1: a row of key/value boxes ---------------------------------------------------------

/** `TopSectionsRow`: several section boxes on a black frame with 1 px rules between them. */
@Composable
internal fun TopSectionsRow(cells: List<PageCell>, contexts: List<SectionContext>, modifier: Modifier = Modifier) {
    val doc = docColors()
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(if (contexts.firstOrNull()?.interactive == false) doc.pickerRule else doc.rule)
            .padding(1.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.forEachIndexed { index, cell ->
            val ctx = contexts[index]
            SelectFrame(ctx.selected, ctx.onSelect, Modifier.weight(1f).fillMaxHeight().background(doc.page)) {
                Column(Modifier.fillMaxWidth()) {
                    if (cell.showsTitle) {
                        SectionBar(
                            cell.title,
                            ctx.selected,
                            ctx.onSelect,
                            Modifier.rules(doc.rule, bottom = true),
                            fontSize = 10.sp,
                            horizontal = 6.dp,
                            align = BarAlign.Center,
                            interactive = ctx.interactive,
                        )
                    }
                    KeyValueBody(cell, ctx, fontSize = 10.5f, padH = 5, padV = 3, highlight = false)
                }
            }
        }
    }
}

/** The key/value lines of a section: **Label:** value | value. */
@Composable
private fun KeyValueBody(
    cell: PageCell,
    ctx: SectionContext,
    fontSize: Float,
    padH: Int,
    padV: Int,
    highlight: Boolean,
) {
    val doc = docColors()
    if (cell.rows.isEmpty()) {
        Text(
            "No fields",
            style = sheetText(10.sp),
            color = doc.meta,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
        )
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = padH.dp, vertical = padV.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        cell.rows.forEachIndexed { index, line ->
            val active = highlight && ctx.selected && ctx.focusedLine == index
            val label = line.values.firstOrNull()
            val labelText = if (label == null || label.value.isBlank()) {
                null
            } else {
                cellValueText(cell.columns.firstOrNull(), label.value, ctx.members)
            }
            val values = cell.columns.drop(1).mapIndexed { offset, column ->
                val atom = line.values.getOrNull(offset + 1)
                cellValueText(column, atom?.value.orEmpty(), ctx.members)
            }
            val text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    labelText?.let { append(it) }
                    append(":")
                }
                append(" ")
                if (cell.columns.size <= 1) {
                    withStyle(SpanStyle(color = doc.dash)) { append("—") }
                } else {
                    values.forEachIndexed { i, value ->
                        if (i > 0) withStyle(SpanStyle(color = doc.faint)) { append(" | ") }
                        append(value)
                    }
                }
            }
            Text(
                text,
                style = sheetText(fontSize.sp, lineHeight = (fontSize * LINE_HEIGHT).sp),
                color = doc.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (active) Modifier.background(doc.rowTint)
                            .drawBehind { accentBar() }.padding(start = 6.dp) else Modifier,
                    ),
            )
        }
    }
}

private const val LINE_HEIGHT = 1.3f

/** The focused line's orange edge, 60 % of the accent. */
private val ACCENT_BAR = Color(0x99FC9404)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.accentBar() {
    drawRect(ACCENT_BAR, Offset.Zero, Size(3.dp.toPx(), size.height))
}

// Branch 3 renderers --------------------------------------------------------------------------

/** `SingleSection` — a bordered key/value box, or a table when its header is vertical. */
@Composable
internal fun SingleSection(cell: PageCell, ctx: SectionContext, modifier: Modifier = Modifier) {
    val doc = docColors()
    if (cell.isVerticalHeader) {
        SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp)) {
            Column(Modifier.fillMaxWidth()) {
                if (cell.showsTitle) {
                    SectionBar(
                        cell.title,
                        ctx.selected,
                        ctx.onSelect,
                        Modifier.border(1.dp, doc.rule),
                        detail = ctx.detail(cell),
                        interactive = ctx.interactive,
                    )
                }
                GridTable(cell, ctx, headerSize = 10, vertical = true, cellHighlight = true)
            }
        }
        return
    }
    SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp).border(1.dp, doc.rule)) {
        Column(Modifier.fillMaxWidth()) {
            if (cell.showsTitle) {
                SectionBar(
                    cell.title,
                    ctx.selected,
                    ctx.onSelect,
                    Modifier.rules(doc.rule, bottom = true),
                    interactive = ctx.interactive,
                )
            }
            if (cell.rows.isNotEmpty() || cell.columns.isNotEmpty()) {
                KeyValueBody(cell, ctx, fontSize = 10.5f, padH = 6, padV = 4, highlight = true)
            }
            if (!cell.showsTitle && ctx.interactive) FooterBadge(ctx.selected, ctx.detail(cell), ctx.onSelect)
        }
    }
}

/** `GenericTable` — a titled table with proportional columns. */
@Composable
internal fun GenericTable(
    cell: PageCell,
    ctx: SectionContext,
    modifier: Modifier = Modifier,
    withColumnFocus: Boolean = true,
) {
    val doc = docColors()
    SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp).border(1.dp, doc.rule)) {
        Column(Modifier.fillMaxWidth()) {
            if (!cell.effectiveHideTitle) {
                SectionBar(
                    cell.title.ifBlank { "Table" },
                    ctx.selected,
                    ctx.onSelect,
                    Modifier.border(1.dp, doc.rule),
                    fontSize = 11.sp,
                    weight = FontWeight.Bold,
                    vertical = 5.dp,
                    detail = if (withColumnFocus) ctx.detail(cell) else null,
                    interactive = ctx.interactive,
                )
            }
            GridTable(cell, ctx, headerSize = 10, vertical = cell.isVerticalHeader, cellHighlight = withColumnFocus)
            if (cell.effectiveHideTitle && ctx.interactive) {
                FooterBadge(ctx.selected, if (withColumnFocus) ctx.detail(cell) else null, ctx.onSelect)
            }
        }
    }
}

/** A navy header row and bordered body lines, columns weighted by their stored widths. */
@Composable
private fun GridTable(cell: PageCell, ctx: SectionContext, headerSize: Int, vertical: Boolean, cellHighlight: Boolean) {
    val doc = docColors()
    val accent = SheetTheme.colors.accent
    val columns = cell.columns
    val weights = if (columns.isEmpty()) emptyList() else cell.columnWeights().map { it.toFloat() }
    Column(Modifier.fillMaxWidth().rules(doc.rule, top = true, start = true)) {
        if (columns.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (vertical) Modifier.height(80.dp) else Modifier.height(IntrinsicSize.Min)),
            ) {
                columns.forEachIndexed { index, column ->
                    Box(
                        Modifier
                            .weight(weights[index])
                            .fillMaxHeight()
                            .background(doc.headerCell)
                            .rules(doc.rule, end = true, bottom = true)
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        contentAlignment = if (vertical) Alignment.BottomCenter else Alignment.Center,
                    ) {
                        Text(
                            column.label,
                            style = sheetText(headerSize.sp, FontWeight.SemiBold, (headerSize * 1.25f).sp),
                            color = doc.headerText,
                            textAlign = TextAlign.Center,
                            maxLines = if (vertical) 1 else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (vertical) Modifier.readsUpward() else Modifier,
                        )
                    }
                }
            }
        }
        if (cell.rows.isEmpty()) {
            Text(
                "Empty",
                style = sheetText(10.sp),
                color = doc.faint,
                modifier = Modifier
                    .fillMaxWidth()
                    .rules(doc.rule, end = true, bottom = true)
                    .padding(horizontal = 3.dp, vertical = 4.dp),
            )
            return@Column
        }
        cell.rows.forEachIndexed { lineIndex, line ->
            val rowActive = ctx.selected && ctx.focusedLine == lineIndex
            val count = if (columns.isEmpty()) line.values.size.coerceAtLeast(1) else columns.size
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .then(line.height?.let { Modifier.heightIn(min = it.dp) } ?: Modifier)
                    .background(if (rowActive) doc.rowTint else Color.Transparent),
            ) {
                repeat(count) { columnIndex ->
                    val atom = line.values.getOrNull(columnIndex)
                    val cellActive = cellHighlight && rowActive && ctx.focusedColumn == columnIndex
                    Box(
                        Modifier
                            .weight(weights.getOrNull(columnIndex) ?: 1f)
                            .fillMaxHeight()
                            .background(if (cellActive) doc.cellTint else Color.Transparent)
                            .rules(if (cellActive) accent else doc.rule, end = true, bottom = true)
                            .then(if (rowActive && columnIndex == 0) Modifier.drawBehind { accentBar() } else Modifier)
                            .then(if (cellActive) Modifier.border(2.dp, accent) else Modifier)
                            .padding(horizontal = 3.dp, vertical = 4.dp),
                    ) {
                        Text(
                            cellValueText(columns.getOrNull(columnIndex), atom?.value.orEmpty(), ctx.members),
                            style = sheetText(10.sp, lineHeight = 13.sp).merge(cssStyle(atom?.cssValue.orEmpty())),
                            color = doc.ink,
                        )
                    }
                }
            }
        }
    }
}

/** The handful of `css_value` declarations a desktop can honour. */
private fun cssStyle(css: String): androidx.compose.ui.text.TextStyle {
    if (css.isBlank()) return androidx.compose.ui.text.TextStyle.Default
    var style = androidx.compose.ui.text.TextStyle.Default
    css.split(";").mapNotNull { declaration ->
        val at = declaration.indexOf(':')
        if (at <= 0) null else declaration.substring(
            0,
            at,
        ).trim().lowercase() to declaration.substring(at + 1).trim().lowercase()
    }.forEach { (property, value) ->
        style = when (property) {
            "font-weight" -> {
                val bold = value == "bold" || (value.toIntOrNull() ?: 0) >= BOLD_WEIGHT
                style.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            }
            "font-style" -> style.copy(fontStyle = if (value == "italic") FontStyle.Italic else FontStyle.Normal)
            "text-align" -> style.copy(
                textAlign = when (value) {
                    "center" -> TextAlign.Center
                    "right" -> TextAlign.End
                    else -> TextAlign.Start
                },
            )
            else -> style
        }
    }
    return style
}

/** `EmployeeCards` — one department's crew table: no header row, a member count, and a collapse toggle. */
@Composable
internal fun CrewTable(cell: PageCell, ctx: SectionContext, modifier: Modifier = Modifier) {
    val doc = docColors()
    var collapsed by rememberSaveable(ctx.key) { mutableStateOf(false) }
    val visible = cell.columns.indices.filterNot { HIDDEN_CREW_COLUMN.matches(cell.columns[it].label) }
    val members = cell.rows.size
    SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (!cell.hideTitle) {
                SectionBar(
                    cell.title.ifBlank { "Department" },
                    ctx.selected,
                    ctx.onSelect,
                    Modifier.border(1.dp, doc.rule),
                    fontSize = 11.sp,
                    weight = FontWeight.Bold,
                    vertical = 5.dp,
                    trailing = {
                        Text(
                            if (members == 1) "1 member" else "$members members",
                            style = sheetText(9.sp, lineHeight = 12.sp),
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    },
                    after = { BarToggle(expanded = !collapsed) { collapsed = !collapsed } },
                    interactive = ctx.interactive,
                )
            }
            if (!collapsed) {
                val weights = visible.map {
                    (cell.columns[it].width ?: 1.0).coerceAtLeast(PageCell.MIN_WIDTH).toFloat()
                }
                Column(Modifier.fillMaxWidth().rules(doc.rule, top = cell.hideTitle, start = true)) {
                    if (cell.rows.isEmpty()) {
                        Text(
                            "No members",
                            style = sheetText(10.sp),
                            color = doc.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().rules(doc.rule, end = true, bottom = true).padding(4.dp),
                        )
                    }
                    cell.rows.forEach { line ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            visible.forEachIndexed { position, columnIndex ->
                                val column = cell.columns[columnIndex]
                                val raw = line.values.getOrNull(columnIndex)?.value.orEmpty()
                                Box(
                                    Modifier
                                        .weight(weights[position])
                                        .fillMaxHeight()
                                        .rules(doc.rule, end = true, bottom = true)
                                        .padding(horizontal = 3.dp, vertical = 4.dp),
                                ) {
                                    if (column.isInColumn()) {
                                        Text(
                                            SheetTime.inDisplay(raw).ifEmpty { "--" },
                                            style = sheetText(10.sp, lineHeight = 13.sp),
                                            color = doc.ink,
                                        )
                                    } else {
                                        Text(
                                            cellValueText(column, raw, ctx.members),
                                            style = sheetText(10.sp, lineHeight = 13.sp),
                                            color = doc.ink,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** `RadioChannels` — numbered channels, eight to a line. */
@Composable
internal fun RadioChannels(cell: PageCell, ctx: SectionContext, modifier: Modifier = Modifier) {
    val doc = docColors()
    val numberColumn = cell.columns.indexOfFirst { it.label.contains("number", ignoreCase = true) }
    val items = cell.rows.mapIndexed { index, line ->
        val numbered = line.values.getOrNull(numberColumn)?.value?.ifBlank { null }
        val number = numbered.takeIf { numberColumn >= 0 } ?: "${index + 1}"
        val rest = line.values.mapIndexedNotNull { i, atom ->
            if (i == numberColumn) null else atom.value.trim().ifEmpty { null }
        }
        "$number. ${rest.joinToString(" | ").ifEmpty { "-" }}"
    }.ifEmpty { listOf("1. -") }
    SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp)) {
        Column(Modifier.fillMaxWidth().border(1.dp, doc.rule)) {
            if (!cell.effectiveHideTitle) {
                SectionBar(
                    cell.title.ifBlank { "RADIO CHANNELS" },
                    ctx.selected,
                    ctx.onSelect,
                    Modifier.rules(doc.rule, bottom = true),
                    fontSize = 11.sp,
                    weight = FontWeight.Bold,
                    horizontal = 6.dp,
                    align = BarAlign.Center,
                    letterSpacing = 0.sp,
                    detail = ctx.detail(cell),
                    interactive = ctx.interactive,
                )
            }
            val chunks = items.chunked(RADIO_PER_LINE)
            chunks.forEachIndexed { chunkIndex, chunk ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .then(if (chunkIndex < chunks.lastIndex) Modifier.rules(doc.rule, bottom = true) else Modifier),
                ) {
                    chunk.forEachIndexed { index, item ->
                        Text(
                            item,
                            style = sheetText(10.sp, FontWeight.SemiBold, 13.sp),
                            color = doc.ink,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .heightIn(min = 24.dp)
                                .then(if (index < chunk.lastIndex) Modifier.rules(doc.rule, end = true) else Modifier)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val RADIO_PER_LINE = 8
private const val BOLD_WEIGHT = 600

/** `NotesBox` — a numbered list, or one line joined with pipes when laid out horizontally. */
@Composable
internal fun NotesBox(cell: PageCell, ctx: SectionContext, modifier: Modifier = Modifier) {
    val doc = docColors()
    val items = cell.rows.mapNotNull { it.values.firstOrNull()?.value?.trim()?.ifEmpty { null } }
    SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp).border(1.dp, doc.rule)) {
        Column(Modifier.fillMaxWidth()) {
            if (!cell.effectiveHideTitle) {
                SectionBar(
                    cell.title.ifBlank { "Notes" },
                    ctx.selected,
                    ctx.onSelect,
                    Modifier.rules(doc.rule, bottom = true),
                    horizontal = 6.dp,
                    vertical = 5.dp,
                    align = BarAlign.Center,
                    interactive = ctx.interactive,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when {
                    items.isEmpty() -> Text(
                        "Click to add notes",
                        style = sheetText(10.sp).copy(fontStyle = FontStyle.Italic),
                        color = doc.faint,
                    )
                    cell.viewType.equals("horizontal", ignoreCase = true) ->
                        Text(items.joinToString(" | "), style = sheetText(10.sp, lineHeight = 14.sp), color = doc.ink)
                    else -> items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                "${index + 1}.",
                                style = sheetText(10.sp, lineHeight = 14.sp),
                                color = doc.ink,
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                item,
                                style = sheetText(10.sp, lineHeight = 14.sp),
                                color = doc.ink,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (cell.effectiveHideTitle && ctx.interactive) FooterBadge(ctx.selected, ctx.detail(cell), ctx.onSelect)
        }
    }
}

/** `WeatherBox` — the fetched forecast as a compact card, or the typed text. */
@Composable
internal fun WeatherBox(cell: PageCell, ctx: SectionContext, modifier: Modifier = Modifier) {
    val doc = docColors()
    val raw = cell.rows.firstOrNull()?.values?.firstOrNull()?.value.orEmpty()
    val weather = WeatherValue.parse(raw)
    SelectFrame(ctx.selected, ctx.onSelect, modifier.padding(bottom = 6.dp).border(1.dp, doc.rule)) {
        Column(Modifier.fillMaxWidth()) {
            if (cell.showsTitle) {
                SectionBar(
                    cell.title,
                    ctx.selected,
                    ctx.onSelect,
                    Modifier.rules(doc.rule, bottom = true),
                    fontSize = 10.sp,
                    horizontal = 6.dp,
                    align = BarAlign.Center,
                    detail = if (weather != null) ctx.detail(cell) else null,
                    interactive = ctx.interactive,
                )
            }
            if (weather != null) WeatherCard(weather) else WeatherText(cell, raw)
            if (cell.effectiveHideTitle && ctx.interactive) {
                FooterBadge(ctx.selected, null, ctx.onSelect, small = weather != null)
            }
        }
    }
}

@Composable
private fun WeatherCard(weather: WeatherValue) {
    val doc = docColors()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(doc.weatherFrom, doc.weatherTo)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (weather.icon.isNotBlank()) Text(
                weatherGlyph(weather.icon),
                style = sheetText(18.sp, lineHeight = 22.sp),
            )
            Text("${weather.tempC ?: ""}°C", style = sheetText(14.sp, FontWeight.Bold, 16.sp), color = doc.weatherInk)
            Text("/ ${weather.tempF ?: ""}°F", style = sheetText(9.sp, lineHeight = 12.sp), color = doc.weatherSub)
            Text(
                weather.description.ifBlank { weather.condition }.replaceFirstChar { it.uppercase() },
                style = sheetText(9.sp, lineHeight = 12.sp),
                color = doc.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val separator = SpanStyle(color = Color(0xFFD0D5DD))
        Text(
            buildAnnotatedString {
                append("Feels ${weather.feelsLikeC ?: ""}°C")
                withStyle(separator) { append("  |  ") }
                append("Humidity ${weather.humidity ?: ""}%")
                withStyle(separator) { append("  |  ") }
                append("Wind ${weather.windKmh ?: ""} km/h")
                withStyle(separator) { append("  |  ") }
                append("UV ${weather.uvi?.let { formatUv(it) } ?: "--"}")
                withStyle(separator) { append("  |  ") }
                append("SR: ${weather.clock(weather.sunrise)}  SS: ${weather.clock(weather.sunset)}")
            },
            style = sheetText(8.sp, lineHeight = 11.sp),
            color = doc.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatUv(uv: Double): String =
    if (uv % 1.0 == 0.0) uv.toInt().toString() else ((uv * HUNDREDTHS).toInt() / HUNDREDTHS).toString()

private const val HUNDREDTHS = 100.0

@Composable
private fun WeatherText(cell: PageCell, raw: String) {
    val doc = docColors()
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        if (raw.isBlank()) {
            Text(
                "Click to add weather information",
                style = sheetText(10.sp).copy(fontStyle = FontStyle.Italic),
                color = doc.dash,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(
                            "${cell.columns.firstOrNull()?.label?.ifBlank { null } ?: "Weather"}: ",
                        )
                    }
                    append(raw.trim())
                },
                style = sheetText(10.5.sp, lineHeight = 14.sp),
                color = doc.ink,
            )
        }
    }
}

/** OpenWeather's icon codes as glyphs — the desktop draws no remote images in a document. */
internal fun weatherGlyph(icon: String): String = when (icon.take(2)) {
    "01" -> if (icon.endsWith("n")) "🌙" else "☀️"
    "02" -> "⛅"
    "03", "04" -> "☁️"
    "09" -> "🌧️"
    "10" -> "🌦️"
    "11" -> "⛈️"
    "13" -> "❄️"
    "50" -> "🌫️"
    else -> "🌡️"
}

/** The approvers block — names and roles in three columns. */
@Composable
internal fun ApproversBlock(
    approverIds: List<String>,
    members: List<SheetMember>,
    selected: Boolean,
    onSelect: () -> Unit,
    interactive: Boolean = true,
) {
    val doc = docColors()
    val approvers = approverIds.mapNotNull { id -> members.firstOrNull { it.userId == id } }
    SelectFrame(selected, onSelect, Modifier.fillMaxWidth().padding(bottom = 6.dp).border(1.dp, doc.rule)) {
        Column(Modifier.fillMaxWidth()) {
            SectionBar(
                "Approvers",
                selected,
                onSelect,
                Modifier.rules(doc.rule, bottom = true),
                fontSize = 12.sp,
                weight = FontWeight.Bold,
                horizontal = 6.dp,
                letterSpacing = 0.2.sp,
                interactive = interactive,
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (approvers.isEmpty()) {
                    Text("No approvers selected", style = sheetText(10.5.sp), color = doc.meta)
                }
                approvers.chunked(APPROVER_COLUMNS).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(APPROVER_COLUMNS) { index ->
                            val member = line.getOrNull(index)
                            Box(Modifier.weight(1f)) {
                                if (member != null) {
                                    ApproverName(member, doc)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val APPROVER_COLUMNS = 3

/** **Name** — designation. */
@Composable
private fun ApproverName(member: SheetMember, doc: DocColors) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(member.fullName) }
            if (member.designation.isNotBlank()) {
                withStyle(SpanStyle(color = doc.secondary)) { append(" — ${member.designation.localised()}") }
            }
        },
        style = sheetText(10.5.sp, lineHeight = 13.5.sp),
        color = doc.ink,
    )
}

/** A small circular toggle for the crew table bar. */
@Composable
internal fun BarToggle(expanded: Boolean, onToggle: () -> Unit) {
    Box(Modifier.size(16.dp).plainClick(onClick = onToggle), contentAlignment = Alignment.Center) {
        Icon(
            if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = Color.White,
            modifier = Modifier.size(10.dp),
        )
    }
}
