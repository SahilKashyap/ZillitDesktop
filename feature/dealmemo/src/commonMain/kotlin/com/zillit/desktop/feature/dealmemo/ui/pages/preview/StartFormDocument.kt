package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.domain.preview.StartField
import com.zillit.desktop.feature.dealmemo.domain.preview.StartFormView
import com.zillit.desktop.feature.dealmemo.domain.preview.StartSection
import com.zillit.desktop.feature.dealmemo.domain.preview.StartTable
import com.zillit.desktop.feature.dealmemo.ui.components.DmType

/** The Crew Start Form (`CrewStartForm.jsx`): a navy-headed card of grids and rule tables. */
@Composable
internal fun StartFormDocument(view: StartFormView) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.card)
            .border(1.dp, pv.cardBorder, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(pv.navy).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = "CREW START FORM",
                    style = DmType.display(18.sp, FontWeight.Bold, 0.04.em),
                    color = Color.White,
                    maxLines = 1,
                )
                ZillitText(
                    text = view.subtitle,
                    style = DmType.sans(12.sp),
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ZillitText(
                text = view.status.uppercase(),
                style = DmType.sans(11.sp, tracking = 0.06.em),
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 6.dp)) {
            ZillitText(
                text = view.crewName,
                style = DmType.sans(22.sp, FontWeight.Bold, (-0.01).em),
                color = START_INK,
            )
            Spacer(Modifier.height(2.dp))
            ZillitText(text = view.roleLine, style = DmType.sans(13.sp), color = START_MUTED)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 24.dp)) {
            view.sections.forEach { section -> StartSectionView(section) }
            view.tables.forEach { table -> StartTableView(table) }
        }
    }
}

@Composable
private fun StartSectionView(section: StartSection) {
    if (section.title == null) {
        Box(Modifier.padding(top = 8.dp)) { StartGrid(section) }
        return
    }
    StartSectionFrame(section.title) { StartGrid(section) }
}

@Composable
private fun StartSectionFrame(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pv.cardBorder))
        Spacer(Modifier.height(10.dp))
        ZillitText(
            text = title.uppercase(),
            style = DmType.sans(11.sp, FontWeight.Bold, 0.06.em),
            color = pv.sectionTitle,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Two or three across; a wide field takes its own row. */
@Composable
private fun StartGrid(section: StartSection) {
    if (section.columns == 2) {
        TwoColumnGrid(section.fields, isWide = { it.wide }, columnGap = 12.dp, rowGap = 8.dp) { StartFieldView(it) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        section.fields.chunked(section.columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { StartFieldView(it) } }
                repeat(section.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StartFieldView(field: StartField) {
    Column {
        ZillitText(
            text = field.label.uppercase(),
            style = DmType.sans(10.sp, FontWeight.SemiBold, 0.05.em),
            color = START_MUTED,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        val style = if (field.mono) DmType.mono(13.sp, FontWeight.Medium) else DmType.sans(13.sp, FontWeight.Medium)
        ZillitText(
            text = if (field.empty) MemoFormat.DASH else field.value.orEmpty(),
            style = style,
            color = if (field.empty) Color(0xFF9CA3AF) else START_INK,
        )
    }
}

@Composable
private fun StartTableView(table: StartTable) {
    StartSectionFrame(table.title) {
        val shape = RoundedCornerShape(4.dp)
        Column(modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, pv.cardBorder, shape)) {
            Row(modifier = Modifier.fillMaxWidth().background(pv.navy)) {
                table.columns.forEachIndexed { index, column ->
                    StartCell(table.widths.getOrNull(index)) {
                        ZillitText(
                            text = column.title.uppercase(),
                            style = DmType.sans(10.sp, FontWeight.SemiBold, 0.05.em),
                            color = Color.White,
                            textAlign = if (column.alignEnd) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (table.rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().background(pv.rowBg).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    ZillitText(
                        text = table.emptyText,
                        style = DmType.sans(11.sp).copy(fontStyle = FontStyle.Italic),
                        color = START_MUTED,
                    )
                }
            }
            table.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEF0F3)))
                Row(modifier = Modifier.fillMaxWidth().background(pv.rowBg), verticalAlignment = Alignment.Top) {
                    row.forEachIndexed { index, cell ->
                        val column = table.columns.getOrNull(index)
                        StartCell(table.widths.getOrNull(index)) {
                            ZillitText(
                                text = cell.ifEmpty { MemoFormat.DASH },
                                style = (if (column?.mono == true) DmType.mono(12.sp) else DmType.sans(12.sp))
                                    .copy(lineHeight = 18.sp),
                                color = pv.body,
                                textAlign = if (column?.alignEnd == true) TextAlign.End else TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StartCell(width: Int?, content: @Composable () -> Unit) {
    val base = if (width != null) Modifier.width(width.dp) else Modifier.weight(1f)
    Box(modifier = base.padding(horizontal = 8.dp, vertical = 6.dp)) { content() }
}

private val START_INK = Color(0xFF1F2937)
private val START_MUTED = Color(0xFF6B7280)
