@file:Suppress("MatchingDeclarationName") // The table kit; DmColumn is only its column spec.

package com.zillit.desktop.feature.dealmemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/** One table column: a fixed width, or a share of what is left. */
data class DmColumn(val title: String, val width: Dp? = null, val weight: Float = 1f, val alignEnd: Boolean = false)

/** Lays a cell out at its column's width. */
@Composable
fun RowScope.DmCell(column: DmColumn, content: @Composable BoxScope.() -> Unit) {
    val base = if (column.width != null) Modifier.width(column.width) else Modifier.weight(column.weight)
    Box(
        modifier = base.padding(horizontal = 10.dp),
        contentAlignment = if (column.alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
        content = content,
    )
}

/** The table's sticky heading row: DM Mono 10, tracked, upper-case, on the warm tint. */
@Composable
fun DmTableHeader(columns: List<DmColumn>) {
    Row(
        modifier = Modifier.fillMaxWidth().background(dm.tableHeader).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            DmCell(column) { DmEyebrow(column.title, size = 10f, tracking = 0.11f) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
}

/**
 * The web's native-select pill: a value in a white hairline pill with a
 * chevron, dropping a list of options.
 */
@Composable
fun <T> DmSelectPill(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    menuWidth: Dp = 220.dp,
) {
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    val drop = with(LocalDensity.current) { 36.dp.roundToPx() }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(dm.control)
                .border(1.dp, if (open) dm.accent else if (hovered) dm.controlHoverBorder else dm.controlBorder, shape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitText(
                text = label(value),
                style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                color = dm.ink,
                maxLines = 1,
            )
            ZillitIcon(ZillitIcons.ChevronDown, size = 11.dp, tint = dm.ink3)
        }
        DmDropPanel(open = open, onDismiss = { open = false }, offsetY = drop, width = menuWidth, alignEnd = false) {
            options.forEach { option ->
                val selected = option == value
                DmHoverRow(
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(
                        horizontal = 10.dp,
                        vertical = 8.dp,
                    ),
                    hoverColor = dm.controlHoverBg,
                ) {
                    ZillitText(
                        text = label(option),
                        style = DmType.sans(13.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (selected) dm.accent else dm.ink,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) ZillitIcon(ZillitIcons.Check, size = 12.dp, tint = dm.accent)
                }
            }
        }
    }
}

/** A labelled control on the filter row — `DEPT:` beside its select. */
@Composable
fun DmLabelled(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DmEyebrow(label)
        content()
    }
}

/** A stack that lays out as a column; for table cells with two lines. */
@Composable
fun DmTwoLines(
    first: String,
    second: String?,
    firstStyle: TextStyle,
    secondStyle: TextStyle,
) {
    Column {
        ZillitText(text = first, style = firstStyle, color = dm.ink2, maxLines = 1)
        if (!second.isNullOrEmpty()) {
            ZillitText(
                text = second,
                style = secondStyle,
                color = dm.ink3,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
