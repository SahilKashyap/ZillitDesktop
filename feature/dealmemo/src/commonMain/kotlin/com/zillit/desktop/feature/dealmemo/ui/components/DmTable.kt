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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSelectTrigger
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
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
 * chevron. The pill stays the filter row's own; its list is the app's one
 * select list, at least [menuWidth] and never narrower than the pill.
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
    val anchor = rememberZillitSelectAnchor()
    Box(modifier = modifier.zillitSelectAnchor(anchor)) {
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
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                options = options,
                isSelected = { it == value },
                onPick = { option ->
                    open = false
                    onSelect(option)
                },
                label = label,
                width = maxOf(anchor.popupWidth(), menuWidth),
            )
        }
    }
}

/**
 * A deal-memo form select's closed field, in the app's one select style
 * ([ZillitSelectTrigger]) but at the form's own [height] and type size, so it
 * still lines up with the inputs beside it. [shown] null reads [placeholder],
 * muted; [onClear] draws the ✕ only while something is shown, and [leading]
 * sits before the shown text. While open, [list] — a [ZillitOptionPopup] — is
 * composed in the field's box with the width to open at (the field's own,
 * never under [minListWidth]) and a closer.
 */
@Suppress("LongParameterList")
@Composable
internal fun DmSelectField(
    shown: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    height: Dp = 40.dp,
    textSize: Float = 14f,
    minListWidth: Dp = 240.dp,
    onClear: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    list: @Composable (width: Dp, close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val colors = ZillitTheme.colors
    Box(modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = open,
            enabled = enabled,
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            isError = error,
            minHeight = height,
            contentPadding = 10.dp,
            onClear = onClear?.takeIf { shown != null },
        ) {
            if (shown != null) leading?.invoke()
            ZillitText(
                text = shown ?: placeholder,
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = textSize.sp),
                color = when {
                    !enabled -> colors.textDisabled
                    shown == null -> colors.textMuted
                    else -> colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) list(maxOf(anchor.popupWidth(), minListWidth)) { open = false }
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
