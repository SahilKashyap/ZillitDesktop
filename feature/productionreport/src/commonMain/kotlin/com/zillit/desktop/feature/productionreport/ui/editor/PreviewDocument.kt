package com.zillit.desktop.feature.productionreport.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.InsertKind
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.ui.DocumentEvent
import com.zillit.desktop.feature.productionreport.ui.EditorEvent
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlinx.serialization.json.JsonPrimitive

/** The live document on its A4 sheet. */
@Composable
internal fun PreviewDocument(
    document: SheetPayload,
    members: List<SheetMember>,
    selection: EditorSelection?,
    focusedLine: Int?,
    focusedColumn: Int?,
    onEvent: (ReportEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = docColors()
    val blocks = remember(document) { previewBlocks(document) }
    Column(modifier.background(doc.page).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp)) {
        blocks.forEach { block ->
            when (block) {
                is PreviewBlock.Insert -> InsertStrip { kind ->
                    onEvent(DocumentEvent.InsertRow(kind, block.afterIndex, block.lockApprovers, block.aboveHeader))
                }
                PreviewBlock.Title -> TitleBar(
                    shared = document.shared,
                    document = document,
                    selected = selection == EditorSelection.Shared,
                    onSelect = { onEvent(EditorEvent.Select(EditorSelection.Shared)) },
                )
                PreviewBlock.Approvers -> ApproversBlock(
                    document.shared.approverIds,
                    members,
                    selected = selection == EditorSelection.Approvers,
                    onSelect = { onEvent(EditorEvent.Select(EditorSelection.Approvers)) },
                )
                is PreviewBlock.Row -> PreviewRow(
                    document.rows[block.index],
                    block.index,
                    members,
                    selection,
                    focusedLine,
                    focusedColumn,
                    onEvent,
                )
            }
        }
        Disclaimer()
        if (document.rows.isEmpty()) {
            Text(
                "No sections to preview",
                style = reportText(14.sp, lineHeight = 20.sp),
                color = doc.meta,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        }
    }
}

@Composable
private fun PreviewRow(
    row: PageRow,
    index: Int,
    members: List<SheetMember>,
    selection: EditorSelection?,
    focusedLine: Int?,
    focusedColumn: Int?,
    onEvent: (ReportEvent) -> Unit,
) {
    val doc = docColors()
    fun contextFor(cellIndex: Int): SectionContext {
        val selected = (selection as? EditorSelection.Cell)?.let { it.row == index && it.cell == cellIndex } == true
        return SectionContext(
            selected = selected,
            onSelect = { onEvent(EditorEvent.Select(EditorSelection.Cell(index, cellIndex))) },
            members = members,
            focusedLine = focusedLine,
            focusedColumn = focusedColumn,
            key = "cell-$index-$cellIndex",
        )
    }
    Column(Modifier.fillMaxWidth()) {
        if (row.isBreak && index > 0) PageBreakDivider { onEvent(DocumentEvent.RemoveRow(index)) }
        when {
            row.cells.isEmpty() -> Unit
            row.isTopSections() -> Box(Modifier.fillMaxWidth()) {
                TopSectionsRow(row.cells, row.cells.indices.map { contextFor(it) })
                EdgeInserts(row.cells.size, Modifier.matchParentSize()) { after, kind ->
                    onEvent(DocumentEvent.InsertCell(index, after, kind))
                }
            }
            row.isMultiCrew() -> Row(Modifier.fillMaxWidth().border(1.dp, doc.rule).height(IntrinsicSize.Min)) {
                row.cells.forEachIndexed { cellIndex, cell ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (cellIndex > 0) Modifier.dottedStart(doc.rule) else Modifier),
                    ) { SectionCell(cell, contextFor(cellIndex)) }
                }
            }
            else -> Box(Modifier.fillMaxWidth()) {
                // Each section keeps its own 6 px foot; stretched to the tallest sibling so borders run the full row.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.cells.forEachIndexed { cellIndex, cell ->
                        Box(Modifier.weight(1f).fillMaxHeight().background(doc.page)) {
                            SectionCell(cell, contextFor(cellIndex), Modifier.fillMaxHeight())
                        }
                    }
                }
                EdgeInserts(row.cells.size, Modifier.matchParentSize()) { after, kind ->
                    onEvent(DocumentEvent.InsertCell(index, after, kind))
                }
            }
        }
    }
}

/** `renderCell` — the web's dispatch, first match wins. */
@Composable
private fun SectionCell(cell: PageCell, ctx: SectionContext, modifier: Modifier = Modifier) {
    val base = modifier.fillMaxWidth()
    when {
        cell.rows.isEmpty() && cell.kind == CellKind.Section && cell.renderAs != RenderKind.Weather -> Unit
        cell.kind == CellKind.Notes -> NotesBox(cell, ctx, base)
        cell.renderAs == RenderKind.Weather -> WeatherBox(cell, ctx, base)
        cell.renderAs == RenderKind.Employee -> CrewTable(cell, ctx, base)
        RADIO.containsMatchIn(cell.title) -> RadioChannels(cell, ctx, base)
        cell.kind == CellKind.Table -> GenericTable(cell, ctx, base)
        cell.kind == CellKind.Section -> SingleSection(cell, ctx, base)
        else -> GenericTable(cell, ctx, base, withColumnFocus = false)
    }
}

private val RADIO = Regex("""radio\s*channel""", RegexOption.IGNORE_CASE)

private fun Modifier.dottedStart(color: Color): Modifier = drawBehind {
    drawLine(
        color,
        Offset(0f, 0f),
        Offset(0f, size.height),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(DOTTED),
    )
}

// Title bar ------------------------------------------------------------------------------------

/** `TitleBar`: "PRODUCTION REPORT 12 of 40 — SWD", the long date, and the script / schedule strip above. */
@Composable
private fun TitleBar(shared: SharedHeader, document: SheetPayload, selected: Boolean, onSelect: () -> Unit) {
    val doc = docColors()
    val label = when (shared.reportType.lowercase()) {
        "wrap" -> "WRAP REPORT"
        "ad" -> "AD REPORT"
        else -> "PRODUCTION REPORT"
    }
    val dayType = shared.dayType.trim()
    val left = if (shared.shootDayNumber.isNotBlank() || shared.totalDays.isNotBlank()) {
        val days = "${shared.shootDayNumber.ifBlank { "0" }} of ${shared.totalDays.ifBlank { "0" }}"
        "$label $days" + if (dayType.isNotEmpty()) " — $dayType" else ""
    } else {
        label
    }
    val script = sharedText(shared, "currentScript").ifBlank { fieldFromRows(document, "Current Script") }
    val schedule = sharedText(shared, "currentSchedule").ifBlank { fieldFromRows(document, "Current Schedule") }
    val strip = listOfNotNull(
        script.takeIf { it.isNotBlank() }?.let { "Current Script: $it" },
        schedule.takeIf { it.isNotBlank() }?.let { "Current Schedule: $it" },
    ).joinToString(" / ")
    Column(Modifier.fillMaxWidth()) {
        if (strip.isNotEmpty()) {
            Text(
                strip,
                style = reportText(11.sp, FontWeight.Bold),
                color = doc.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        SelectFrame(selected, onSelect, Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(doc.titleBar)
                    .rules(doc.rule, top = true, start = true, end = true)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    left,
                    style = reportText(18.sp, FontWeight.Bold, 24.sp),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                val date = ReportTime.headerDate(shared.dateYmd)
                if (date.isNotEmpty()) Text(
                    date,
                    style = reportText(18.sp, FontWeight.Bold, 24.sp),
                    color = Color.White,
                    textAlign = TextAlign.End,
                )
                BarBadge(selected, null, onSelect)
            }
        }
    }
}

private fun sharedText(shared: SharedHeader, key: String): String =
    (shared.extras[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

/** `extractFieldFromPageRows`: a system section's "Field | Value" line. */
private fun fieldFromRows(document: SheetPayload, field: String): String {
    document.rows.forEach { row ->
        row.cells.filter { it.systemDefault && it.kind == CellKind.Section }.forEach { cell ->
            cell.rows.forEach { line ->
                line.values.forEachIndexed { index, atom ->
                    if (index < line.values.lastIndex && atom.value.trim().equals(field, ignoreCase = true)) {
                        line.values[index + 1].value.trim().takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
            }
        }
    }
    return ""
}

@Composable
private fun Disclaimer() {
    val doc = docColors()
    Text(
        "This Document is highly confidential. Personal information must not be disclosed to any " +
            "unauthorized person(s) and must be kept securely. Therefore please ensure it is not left in " +
            "a place where it could be taken by a third party. All production reports are to be " +
            "shredded on disposal.",
        style = reportText(9.sp, lineHeight = 12.sp),
        color = doc.disclaimer,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .rules(doc.rule, top = true)
            .padding(start = 2.dp, end = 2.dp, top = 6.dp),
    )
}

@Composable
private fun PageBreakDivider(onRemove: () -> Unit) {
    val accent = ReportTheme.colors.accent
    val doc = docColors()
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(16.dp)) {
        Box(
            Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).drawBehind {
                drawLine(
                    accent,
                    Offset(0f, size.height / 2),
                    Offset(size.width, size.height / 2),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            },
        )
        Text(
            "PAGE BREAK",
            style = reportText(10.sp, FontWeight.SemiBold, 14.sp),
            color = accent,
            modifier = Modifier.align(Alignment.Center).background(doc.page).padding(horizontal = 8.dp),
        )
        ZillitTooltip("Remove page break") {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(doc.page)
                    .plainClick(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ZillitIcons.Close,
                    contentDescription = "Remove page break",
                    tint = accent,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

// Insert tooling --------------------------------------------------------------------------------

/** The 22 px strip between blocks: a hairline and a "+" that appear on hover. */
@Composable
private fun InsertStrip(onInsert: (InsertKind) -> Unit) {
    val accent = ReportTheme.colors.accent
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val reveal by animateFloatAsState(if (hovered || open) 1f else 0f, tween(REVEAL_MS))
    Row(
        Modifier.fillMaxWidth().height(22.dp).hoverable(source).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).alpha(reveal).background(accent.copy(alpha = 0.4f)))
        Box {
            PlusButton(Modifier.alpha(reveal), onClick = { open = true })
            InsertMenu(open, horizontal = false, onDismiss = { open = false }) {
                open = false
                onInsert(it)
            }
        }
        Box(Modifier.weight(1f).height(1.dp).alpha(reveal).background(accent.copy(alpha = 0.4f)))
    }
}

/**
 * The "+" targets on a row's cell borders — start, between each pair, end —
 * each revealed only while the pointer is on it.
 */
@Composable
private fun EdgeInserts(cells: Int, modifier: Modifier, onInsert: (afterCell: Int, kind: InsertKind) -> Unit) {
    Layout(
        modifier = modifier,
        content = {
            for (edge in 0..cells) {
                EdgeZone { kind -> onInsert(edge - 1, kind) }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { edge, placeable ->
                val x = (constraints.maxWidth * edge / cells.coerceAtLeast(1)) - placeable.width / 2
                val y = constraints.maxHeight / 2 - placeable.height / 2
                placeable.place(x, y)
            }
        }
    }
}

@Composable
private fun EdgeZone(onInsert: (InsertKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val reveal by animateFloatAsState(if (hovered || open) 1f else 0f, tween(REVEAL_MS))
    Box(Modifier.size(20.dp).hoverable(source), contentAlignment = Alignment.Center) {
        PlusButton(Modifier.alpha(reveal), onClick = { open = true })
        InsertMenu(open, horizontal = true, onDismiss = { open = false }) {
            open = false
            onInsert(it)
        }
    }
}

private const val REVEAL_MS = 150

@Composable
private fun PlusButton(modifier: Modifier, onClick: () -> Unit) {
    ZillitTooltip("Insert section") {
        Box(
            modifier
                .size(18.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(ReportTheme.colors.accent)
                .plainClick(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.Add,
                contentDescription = "Insert section",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Notes · Section · Table [· Page Break] — a side-by-side zone cannot take a page break. */
@Composable
private fun InsertMenu(open: Boolean, horizontal: Boolean, onDismiss: () -> Unit, onPick: (InsertKind) -> Unit) {
    val colors = ReportTheme.colors
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        offset = DpOffset((-66).dp, 2.dp),
        shape = RoundedCornerShape(8.dp),
        containerColor = colors.surface,
        modifier = Modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.widthIn(min = 150.dp)) {
            InsertKind.entries.filter { !horizontal || it != InsertKind.PageBreak }.forEach { kind ->
                val (source, hovered) = rememberHover()
                Text(
                    kind.label,
                    style = reportText(12.sp, lineHeight = 16.sp),
                    color = if (hovered) colors.accent else colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (hovered) colors.accentLight else Color.Transparent)
                        .hoverable(source)
                        .plainClick(source = source) { onPick(kind) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The multi-department row's dotted separators. */
private val DOTTED = floatArrayOf(1.5f, 2f)
