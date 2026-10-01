package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/*
 * Small pieces the Scene Breakdown / Characters / Actors screens share, drawn to the web's
 * SyncOnset.css numbers (px to dp 1:1): the ink underline sub-tabs, antd's `Input.Search`
 * (a field with a trailing magnifier button), the 38dp square avatar tile and its list row,
 * and the dark-pill filter chip.
 */

/** The web's `.csync-subtabs`: 14sp labels, 8/16 padding, the active one ink-coloured with a 2dp ink underline. */
@Composable
internal fun InkTabs(
    tabs: List<Pair<String, String>>,
    activeId: String,
    modifier: Modifier = Modifier,
    /** A pill after a tab's label (`csync-badge--warn`: the characters tab's "2 new"), by tab id. */
    badges: Map<String, String> = emptyMap(),
    onSelect: (String) -> Unit,
) {
    val colors = ZillitTheme.colors
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border).align(Alignment.BottomStart))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { (id, label) ->
                val on = id == activeId
                Column(Modifier.width(IntrinsicSize.Max).clickable { onSelect(id) }) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ZillitText(
                            label,
                            style = ZillitTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (on) colors.textPrimary else colors.textMuted,
                            maxLines = 1,
                        )
                        badges[id]?.let { badge ->
                            ZillitText(
                                badge,
                                Modifier.clip(RoundedCornerShape(999.dp)).background(colors.accentSoft).padding(
                                    horizontal = 8.dp,
                                    vertical = 2.dp,
                                ),
                                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.accentText,
                                maxLines = 1,
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(if (on) colors.textPrimary else androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
    }
}

/** antd's `Input.Search`: a 32dp field whose right edge is a joined button carrying the magnifier. */
@Composable
internal fun SearchWithButton(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.height(32.dp).clip(shape).background(colors.surface).border(
            1.dp,
            if (focused) colors.accent else colors.border,
            shape,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 11.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) ZillitText(
                placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
                maxLines = 1,
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                interactionSource = source,
                textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
        Box(
            Modifier.width(38.dp).fillMaxHeight().background(colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.Search, tint = colors.textSecondary, size = 16.dp)
        }
    }
}

/** The web's `.csync-avatar`: a 38dp rounded (10dp) square, never a circle. */
@Composable
internal fun SquareAvatar(text: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.size(38.dp).clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text.ifBlank { "–" },
            style = ZillitTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

/** The web's `.csync-charlist__row`: 10/14 padding, 12 gap, hairline between rows; [end] is pushed right. */
@Composable
internal fun CharListRow(
    onClick: (() -> Unit)?,
    leading: @Composable () -> Unit,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    extra: String = "",
    end: String = "",
    last: Boolean = false,
) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                ZillitText(
                    title,
                    style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (sub.isNotBlank()) ZillitText(
                    sub,
                    style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.textMuted,
                    maxLines = 1,
                )
                if (extra.isNotBlank()) ZillitText(
                    extra,
                    style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            if (end.isNotEmpty()) ZillitText(
                end,
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** The web's `button.csync-chip`: 12sp, 4/12 padding, pill; the active one is solid ink with surface-coloured text. */
@Composable
internal fun InkChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(999.dp)
    ZillitText(
        label,
        Modifier
            .clip(shape)
            .background(if (active) colors.textPrimary else colors.surface)
            .border(1.dp, if (active) colors.textPrimary else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = if (active) colors.surface else colors.textPrimary,
        maxLines = 1,
    )
}

/** The web's `.csync-backbtn`: a 38dp bordered rounded square with the arrow. */
@Composable
internal fun BackSquare(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(38.dp).clip(shape).background(colors.surface).border(1.dp, colors.border, shape).clickable(
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(ZillitIcons.ArrowLeft, tint = colors.textPrimary, size = 18.dp) }
}

/**
 * The Scene Breakdown header (`.csync-pagehead--scenes`): the back square and an 18sp title block
 * (max 340 wide, its sub-line wrapping) with the action buttons right-aligned beside it, wrapping onto a
 * row of their own when they do not fit.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ScenesHead(title: String, sub: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    androidx.compose.ui.layout.Layout(
        {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                BackSquare(onBack)
                Column(Modifier.weight(1f, fill = false).widthIn(min = 160.dp)) {
                    ZillitText(
                        title,
                        style = ZillitTheme.typography.titleLarge.copy(
                            fontSize = 18.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    if (sub.isNotBlank()) ZillitText(
                        sub,
                        Modifier.padding(top = 2.dp),
                        style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.textMuted,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
                content = actions,
            )
        },
        Modifier.fillMaxWidth().padding(top = 12.dp),
    ) { (head, act), constraints ->
        val width = constraints.maxWidth
        val gap = 14.dp.roundToPx()
        val headPlaced = head.measure(androidx.compose.ui.unit.Constraints(maxWidth = minOf(width, 392.dp.roundToPx())))
        val natural = act.maxIntrinsicWidth(0)
        val beside = headPlaced.width + gap + natural <= width
        val actPlaced = act.measure(
            androidx.compose.ui.unit.Constraints(maxWidth = if (beside) width - headPlaced.width - gap else width),
        )
        val height = if (beside) {
            maxOf(headPlaced.height, actPlaced.height)
        } else {
            headPlaced.height + 12.dp.roundToPx() + actPlaced.height
        }
        layout(width, height) {
            headPlaced.placeRelative(0, 0)
            actPlaced.placeRelative(width - actPlaced.width, if (beside) 0 else headPlaced.height + 12.dp.roundToPx())
        }
    }
}

/**
 * A button with notes stacked under it (`.csync-scenehead__stack`): the notes wrap within the
 * BUTTON's width and never widen the stack.
 */
@Composable
internal fun ButtonStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val head = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val rest = measurables.drop(1).map { it.measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = head.width),
        ) }
        val gap = 2.dp.roundToPx()
        val height = head.height + rest.sumOf { it.height + gap }
        layout(head.width, height) {
            head.placeRelative(0, 0)
            var y = head.height + gap
            rest.forEach {
                it.placeRelative((head.width - it.width) / 2, y)
                y += it.height + gap
            }
        }
    }
}

/** The reference's blue "+ Add" (`btn-blue`): a 32dp button in the info blue. */
@Composable
internal fun BlueButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.info.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(ZillitIcons.Add, tint = androidx.compose.ui.graphics.Color.White, size = 16.dp)
        ZillitText(
            text,
            style = ZillitTheme.typography.button,
            color = androidx.compose.ui.graphics.Color.White,
            maxLines = 1,
        )
    }
}

/**
 * The web's `.csync-dropzone` (its later rule wins): a full-width, centred panel with a 1.5dp dashed border, 18/12
 * padding, 4dp gap and the page fill.
 */
@Composable
internal fun DropZone(title: String, formats: String, busy: Boolean, busyText: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val border = colors.border
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .drawDashed(border)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (busy) {
            ZillitText(busyText, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        } else {
            ZillitText(title, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            ZillitText(
                formats,
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.textMuted,
            )
        }
    }
}

private fun Modifier.drawDashed(color: androidx.compose.ui.graphics.Color): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        ),
    )
}
