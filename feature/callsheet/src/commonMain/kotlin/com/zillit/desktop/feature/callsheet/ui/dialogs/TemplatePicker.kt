// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.dialogs

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.StockTemplate
import com.zillit.desktop.feature.callsheet.domain.templateSectionTitles
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetModal
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlin.time.TimeSource

/**
 * "Choose a Call Sheet Template" — `TemplatePickerModal.jsx`: the
 * stock layouts as radio options (double-click opens one), the
 * create-your-own seed lifted out as a button, and a faithful thumbnail of
 * the highlighted layout.
 */
@Composable
internal fun TemplatePickerDialog(dialog: SheetDialog.TemplatePicker, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val templates = dialog.templates
    val createOwn = templates.indexOfFirst { it.isCreateYourOwn }.takeIf { it >= 0 }
    val pickable = templates.indices.filter { it != createOwn }
    val chosen = templates.getOrNull(dialog.selected)
    SheetModal(
        title = "Choose a Call Sheet Template",
        onClose = { onEvent(DialogEvent.Dismiss) },
        modifier = Modifier.fillMaxHeight(PICKER_HEIGHT),
        width = 1080.dp,
        maxHeight = 4000.dp,
        scrollable = false,
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick a starting layout. Every section stays editable after it opens.",
                    style = sheetText(12.sp, lineHeight = 17.sp),
                    color = colors.textTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                var lastTap by remember { mutableStateOf<Pair<Int, TimeSource.Monotonic.ValueTimeMark>?>(null) }
                pickable.forEach { index ->
                    val template = templates[index]
                    TemplateOption(
                        label = labelFor(templates, index),
                        sections = templateSectionTitles(template.payload).size,
                        selected = index == dialog.selected,
                    ) {
                        val previous = lastTap
                        val now = TimeSource.Monotonic.markNow()
                        val quick = previous != null && (now - previous.second).inWholeMilliseconds < DOUBLE_TAP_MS
                        if (quick && previous?.first == index) {
                            lastTap = null
                            onEvent(DialogEvent.UseTemplate(index))
                        } else {
                            lastTap = index to now
                            onEvent(DialogEvent.PickTemplate(index))
                        }
                    }
                }
                createOwn?.let { index ->
                    SheetButton(
                        labelFor(templates, index),
                        { onEvent(DialogEvent.UseTemplate(index)) },
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        kind = ButtonKind.Accent,
                        height = 38.dp,
                        fontSize = 12.sp,
                    )
                }
            }
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.previewBg)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
            ) {
                Box(
                    Modifier.fillMaxSize().zillitVerticalScroll(scroll).padding(24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (chosen != null) {
                        TemplateMiniPreview(
                            chosen.payload,
                            pageTitle = "CALL SHEET",
                            Modifier.widthIn(max = 820.dp),
                        )
                    } else {
                        Text("No template to preview.", style = sheetText(13.sp), color = colors.textMuted)
                    }
                }
                ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            SheetButton(
                "Cancel",
                { onEvent(DialogEvent.Dismiss) },
                kind = ButtonKind.Outline,
                fontSize = 12.sp,
                horizontalPadding = 16.dp,
            )
            SheetButton(
                "Use ${labelFor(templates, dialog.selected)}",
                { onEvent(DialogEvent.UseTemplate(dialog.selected)) },
                kind = ButtonKind.Accent,
                enabled = chosen != null,
                fontSize = 12.sp,
                horizontalPadding = 16.dp,
            )
        }
    }
}

private const val PICKER_HEIGHT = 0.92f
private const val DOUBLE_TAP_MS = 400

private fun labelFor(templates: List<StockTemplate>, index: Int): String =
    templates.getOrNull(index)?.displayName?.ifBlank { null } ?: "Template ${index + 1}"

@Composable
private fun TemplateOption(label: String, sections: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val border by animateColorAsState(
        when {
            selected -> colors.accent
            hovered -> colors.borderStrong
            else -> colors.border
        },
        tween(120),
    )
    val bg by animateColorAsState(
        when {
            selected -> colors.accentLight
            hovered -> colors.hover
            else -> colors.surface
        },
        tween(120),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(1.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = sheetText(13.sp, FontWeight.SemiBold, 16.sp),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (sections == 1) "1 section" else "$sections sections",
                style = sheetText(10.sp, lineHeight = 13.sp),
                color = colors.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * `TemplateMiniPreview` — every section, column and row of a layout on a
 * white page (ZL-21253), with the dark title band when [pageTitle] is set.
 */
@Composable
internal fun TemplateMiniPreview(payload: SheetPayload, pageTitle: String, modifier: Modifier = Modifier) {
    val rows = payload.rows
    Column(
        modifier
            .fillMaxWidth()
            .shadow(3.dp)
            .background(Color.White)
            .border(1.dp, Color(0xFFD0D5DD))
            .padding(horizontal = 36.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (rows.isEmpty()) {
            Text("This template has no sections yet.", style = sheetText(12.sp), color = Color(0xFF98A2B3))
            return@Column
        }
        if (pageTitle.isNotBlank()) {
            val today = SheetTime.todayMidnight(kotlin.time.Clock.System.now().toEpochMilliseconds())
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1E2A3A)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pageTitle.uppercase(),
                    style = sheetText(18.sp, FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    SheetTime.headerDate(payload.shared.dateMs?.takeIf { it > 0 } ?: today),
                    style = sheetText(14.sp, FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }
        rows.forEach { row ->
            when {
                row.isPageBreak -> PageBreakLine()
                row.cells.isEmpty() -> Unit
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.cells.forEach { cell -> MiniCell(cell, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PageBreakLine() {
    val dash = Color(0xFF98A2B3)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 18.dp)
            .drawBehind {
                drawLine(
                    dash,
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                )
            }
            .padding(top = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text("PAGE BREAK", style = sheetText(10.sp, FontWeight.Medium).copy(letterSpacing = 0.5.sp), color = dash)
    }
}

@Composable
private fun MiniCell(cell: PageCell, modifier: Modifier) {
    val headers = cell.columns.map { it.label.trim() }
    val widest = cell.rows.maxOfOrNull { it.values.size } ?: 0
    val columnCount = maxOf(headers.size, widest, 1)
    val anyHeader = headers.any { it.isNotEmpty() }
    val asTable = when (cell.kind) {
        CellKind.Table -> true
        CellKind.Section -> false
        else -> anyHeader || columnCount > 2
    }
    Column(modifier.border(1.dp, Color(0xFFEAECF0))) {
        if (cell.title.isNotBlank() && !cell.effectiveHideTitle) {
            Text(
                cell.title.trim().uppercase(),
                style = sheetText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF475467))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (asTable) MiniTable(cell, headers, anyHeader, columnCount) else MiniKeyValues(cell)
    }
}

@Composable
private fun MiniTable(cell: PageCell, headers: List<String>, anyHeader: Boolean, columnCount: Int) {
    val grid = Color(0xFFD0D5DD)
    if (anyHeader) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1E2A3A))) {
            repeat(columnCount) { index ->
                Text(
                    headers.getOrNull(index).orEmpty().uppercase(),
                    style = sheetText(11.sp, FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
    val lines = cell.rows.map { line -> List(columnCount) { line.values.getOrNull(it)?.value?.trim().orEmpty() } }
        .ifEmpty { listOf(List(columnCount) { "" }) }
    lines.forEach { values ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            values.forEach { value ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(0.5.dp, grid)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                ) {
                    Text(
                        value.ifEmpty { "—" },
                        style = sheetText(10.5.sp),
                        color = Color(0xFF344054),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniKeyValues(cell: PageCell) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (cell.rows.isEmpty()) {
            Text("—", style = sheetText(10.5.sp), color = Color(0xFF344054))
            return@Column
        }
        cell.rows.forEach { line ->
            val label = line.values.firstOrNull()?.value?.trim().orEmpty()
            val value = line.values.drop(1)
                .map { it.value.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
                .ifEmpty { "—" }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label.isNotEmpty()) {
                    Text(
                        "$label:",
                        style = sheetText(10.5.sp, FontWeight.Bold),
                        color = Color(0xFF475467),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
                Text(
                    value,
                    style = sheetText(10.5.sp),
                    color = Color(0xFF344054),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
