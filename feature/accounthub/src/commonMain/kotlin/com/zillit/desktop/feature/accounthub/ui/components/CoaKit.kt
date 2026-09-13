package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType

/*
 * The Chart of Accounts' own atoms — the web's `coaTheme.jsx`: the level and
 * class chips, the row actions, the tabs and the glyphs the module draws with.
 *
 * The chip colours are the web's, in both themes, rather than the app's status
 * tones: five classes and four levels need nine distinguishable families, and
 * the status palette has five.
 */

/** A chip's three colours. */
internal data class CoaTone(val background: Color, val content: Color, val border: Color = Color.Transparent)

/** A level's chip — header warmest, section teal, the two leaves quiet (`typeChipStyle`). */
@Composable
internal fun coaTypeTone(lineType: CoaLineType): CoaTone {
    val colors = ZillitTheme.colors
    val dark = colors.isDark
    return when (lineType) {
        CoaLineType.Header -> if (dark) {
            CoaTone(Color(0x1FFBBF24), Color(0xFFFBBF24), Color(0x4DFBBF24))
        } else {
            CoaTone(Color(0x1AEA7A0E), Color(0xFFB95B00), Color(0x38EA7A0E))
        }
        CoaLineType.Section -> if (dark) {
            CoaTone(Color(0x1A4ADE80), Color(0xFF5EEAD4), Color(0x4D4ADE80))
        } else {
            CoaTone(Color(0x1A14A394), Color(0xFF0C7A6E), Color(0x3814A394))
        }
        CoaLineType.SubCategory ->
            CoaTone(if (dark) Color(0x0DFFFFFF) else Color(0x0A0A0C10), colors.textMuted, colors.divider)
        CoaLineType.Category ->
            CoaTone(if (dark) Color(0x0FFFFFFF) else Color(0x0D0A0C10), colors.textSecondary, colors.divider)
    }
}

/** A class's chip (`costChipStyle`). */
@Composable
internal fun coaCostTone(costType: CoaCostType): CoaTone {
    val dark = ZillitTheme.colors.isDark
    return when (costType) {
        CoaCostType.Asset -> if (dark) CoaTone(Color(0x240EA5E9), Color(0xFF7DD3FC)) else
            CoaTone(Color(0x1A0EA5E9), Color(0xFF0369A1))
        CoaCostType.Liability -> if (dark) CoaTone(Color(0x24DC2626), Color(0xFFFCA5A5)) else
            CoaTone(Color(0x1ADC2626), Color(0xFFB91C1C))
        CoaCostType.Capital -> if (dark) CoaTone(Color(0x247C3AED), Color(0xFFC4B5FD)) else
            CoaTone(Color(0x1A7C3AED), Color(0xFF5B21B6))
        CoaCostType.Income -> if (dark) CoaTone(Color(0x2414A394), Color(0xFF5EEAD4)) else
            CoaTone(Color(0x1A14A394), Color(0xFF0C7A6E))
        CoaCostType.Expense -> if (dark) CoaTone(Color(0x24D97706), Color(0xFFFCD34D)) else
            CoaTone(Color(0x1AD97706), Color(0xFF92400E))
    }
}

/** The bulk grid's rail and row-number colours by class (`COST_TONE`). */
@Composable
internal fun coaRailTone(costType: CoaCostType): CoaTone {
    val rail = when (costType) {
        CoaCostType.Asset -> Color(0xFF14A394)
        CoaCostType.Liability -> Color(0xFFDC2626)
        CoaCostType.Capital -> Color(0xFF7A4CD6)
        CoaCostType.Income -> Color(0xFF1AA463)
        CoaCostType.Expense -> Color(0xFFEA7A0E)
    }
    // The web's pale washes glare on a dark surface; a tint of the rail reads the same.
    val wash = if (ZillitTheme.colors.isDark) rail.copy(alpha = 0.18f) else rail.copy(alpha = 0.12f)
    return CoaTone(wash, rail)
}

/** The mono face at a size and weight — codes, counts, chips. */
@Composable
internal fun coaMono(size: TextUnit, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 0.sp): TextStyle =
    ZillitTheme.typography.numeric.copy(
        fontSize = size,
        fontWeight = weight,
        letterSpacing = spacing,
        lineHeight = size * MONO_LINE_HEIGHT,
    )

/** The uppercase column/label face. */
@Composable
internal fun coaEyebrow(size: TextUnit = 10.5.sp): TextStyle = ZillitTheme.typography.labelSmall.copy(
    fontSize = size,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.9.sp,
)

/** The rounded white pane the tree, the table and the empty state sit in. */
@Composable
internal fun CoaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(colors.surface)
            .border(1.dp, colors.border, CARD_SHAPE),
        content = content,
    )
}

/**
 * The only states worth a chip — "Inactive" and "Non-posting" (`StatusChips`).
 *
 * Active and postable is the norm and gets nothing. These are never dimmed with
 * the retired row they sit on: they are the explanation for the dimming.
 */
@Composable
internal fun CoaStatusChips(account: CoaAccount) {
    if (!account.isActive) CoaStatusChip("Inactive")
    if (!account.isPosting) CoaStatusChip("Non-posting")
}

@Composable
private fun CoaStatusChip(label: String) {
    val colors = ZillitTheme.colors
    val background = if (colors.isDark) Color(0x0FFFFFFF) else Color(0x0B0A0C10)
    val content = if (colors.isDark) Color(0xD9FFFFFF) else Color(0xC70A0C10)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .border(1.dp, colors.divider, CircleShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(content))
        ZillitText(label.uppercase(), style = coaMono(11.sp, FontWeight.Bold, 0.3.sp), color = content, maxLines = 1)
    }
}

/** The table's level chip — every row gets one, the top level reading "Group". */
@Composable
internal fun CoaTypeChip(lineType: CoaLineType) {
    val tone = coaTypeTone(lineType)
    Box(
        modifier = Modifier
            .clip(SMALL_SHAPE)
            .background(tone.background)
            .border(1.dp, tone.border, SMALL_SHAPE)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        ZillitText(
            lineType.tagLabel.uppercase(),
            style = coaMono(10.sp, FontWeight.Bold, 0.6.sp),
            color = tone.content,
            maxLines = 1,
        )
    }
}

/** A read-only class: a dot and the word, in the class's colours. */
@Composable
internal fun CoaCostChip(costType: CoaCostType, tooltip: String = "") {
    val tone = coaCostTone(costType)
    ZillitTooltip(tooltip) {
        Row(
            modifier = Modifier
                .clip(SMALL_SHAPE)
                .background(tone.background)
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(tone.content))
            ZillitText(
                costType.label,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = tone.content,
                maxLines = 1,
            )
        }
    }
}

/** The table's inline class select, dressed in the class's own colours as the web's is. */
@Composable
internal fun CoaCostSelect(value: CoaCostType, onSelect: (CoaCostType) -> Unit) {
    val colors = ZillitTheme.colors
    val tone = coaCostTone(value)
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            modifier = Modifier
                .clip(SMALL_SHAPE)
                .background(tone.background)
                .border(1.dp, if (hovered || open) tone.content.copy(alpha = 0.35f) else Color.Transparent, SMALL_SHAPE)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .padding(start = 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(tone.content))
            ZillitText(
                value.label,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = tone.content,
                maxLines = 1,
            )
            ZillitIcon(CoaIcons.ChevronDown, tint = tone.content, size = 11.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(8.dp)),
        ) {
            CoaCostType.entries.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.background(
                        if (option == value) colors.surfaceSelected else colors.surfaceRaised,
                    ),
                    text = { CostOption(option) },
                )
            }
        }
    }
}

/** A class in the select's menu: its dot, then its word. */
@Composable
private fun CostOption(option: CoaCostType) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(coaCostTone(option).content))
        ZillitText(option.label, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textPrimary)
    }
}

/**
 * A row's small square action — Add child, Edit, Deactivate (`RowAction`).
 *
 * Always composed: a control that only exists while its row is hovered loses
 * the press on desktop, because the press re-evaluates hover and removes the
 * button from under the pointer. Callers fade it instead.
 */
@Composable
internal fun CoaRowAction(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    size: Dp = 24.dp,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            hovered && danger -> colors.danger.copy(alpha = 0.10f)
            hovered -> colors.surfaceHover
            else -> colors.surface
        },
        label = "rowActionBackground",
    )
    val border = when {
        hovered && danger -> colors.danger.copy(alpha = 0.3f)
        hovered -> colors.borderStrong
        else -> colors.border
    }
    ZillitTooltip(tooltip) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon,
                contentDescription = tooltip,
                tint = if (hovered && danger) colors.danger else colors.textSecondary,
                size = 12.dp,
            )
        }
    }
}

/** A code saved without a display name, flagged so accountants can find it (`Unnamed`). */
@Composable
internal fun CoaUnnamed(style: TextStyle) {
    ZillitText(
        "Unnamed",
        style = style.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal),
        color = ZillitTheme.colors.textPrimary,
        modifier = Modifier.alpha(0.45f),
        maxLines = 1,
    )
}

/** One of the module's tabs. */
internal data class CoaTabSpec(val id: String, val label: String, val icon: ImageVector)

/** The module's underline tabs, each with its glyph (`COATabs`). */
@Composable
internal fun CoaUnderlineTabs(tabs: List<CoaTabSpec>, activeId: String, onSelect: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { tab ->
                val active = tab.id == activeId
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val content by animateColorAsState(
                    when {
                        active -> colors.accent
                        hovered -> colors.textPrimary
                        else -> colors.textSecondary
                    },
                    label = "coaTab",
                )
                val underline by animateFloatAsState(if (active) 1f else 0f, label = "coaTabUnderline")
                val accent = colors.accent
                Row(
                    modifier = Modifier
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(tab.id) }
                        // Drawn rather than laid out, so the underline is exactly the tab's width.
                        .drawBehind {
                            if (underline > 0f) {
                                val inset = 12.dp.toPx()
                                val thickness = 2.dp.toPx()
                                drawRoundRect(
                                    color = accent.copy(alpha = underline),
                                    topLeft = Offset(inset, size.height - thickness),
                                    size = Size(size.width - inset * 2, thickness),
                                    cornerRadius = CornerRadius(thickness),
                                )
                            }
                        }
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitIcon(tab.icon, tint = if (active) colors.accent else colors.textMuted, size = 14.dp)
                    ZillitText(
                        tab.label,
                        style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** A bordered toolbar button with a glyph — Expand all, Import Budget (`ToolbarBtn`). */
@Composable
internal fun CoaToolbarButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> colors.accentSoft
            hovered -> colors.surfaceHover
            else -> colors.surface
        },
        label = "coaToolbarButton",
    )
    val content = if (selected) colors.accentText else colors.textSecondary
    Row(
        modifier = Modifier
            .heightIn(min = TOOLBAR_HEIGHT)
            .clip(TOOLBAR_SHAPE)
            .background(background)
            .border(1.dp, if (selected) colors.accent.copy(alpha = 0.45f) else colors.border, TOOLBAR_SHAPE)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon, tint = content, size = 13.dp)
        ZillitText(
            label,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
        )
        trailing?.invoke()
    }
}

/** The Tree | Table switch: the active half filled with the accent (`ViewSwitch`). */
@Composable
internal fun <T> CoaViewSwitch(options: List<Triple<T, String, ImageVector>>, value: T, onChange: (T) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = TOOLBAR_HEIGHT)
            .clip(TOOLBAR_SHAPE)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, TOOLBAR_SHAPE)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (option, label, icon) ->
            val active = option == value
            val background by animateColorAsState(if (active) colors.accent else Color.Transparent, label = "coaSwitch")
            val content = if (active) colors.textOnAccent else colors.textSecondary
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .clickable { onChange(option) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(icon, tint = content, size = 13.dp)
                ZillitText(
                    label,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The ON / OFF pill inside the Show inactive button. */
@Composable
internal fun CoaOnOffPill(on: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) colors.accent.copy(alpha = 0.18f) else colors.surfaceSunken)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        ZillitText(
            if (on) "ON" else "OFF",
            style = coaMono(10.5.sp, FontWeight.Bold),
            color = if (on) colors.accentText else colors.textMuted,
        )
    }
}

/** A rotating chevron: right when closed, down when open. */
@Composable
internal fun CoaChevron(open: Boolean, tint: Color, size: Dp = 11.dp) {
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "coaChevron")
    ZillitIcon(CoaIcons.ChevronRight, tint = tint, size = size, modifier = Modifier.rotate(angle))
}

/** A square wash with a glyph in it — the empty states' and the intro strip's badge. */
@Composable
internal fun CoaIconWash(icon: ImageVector, box: Dp, glyph: Dp) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.size(box).clip(RoundedCornerShape(box / 4)).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, tint = colors.accentText, size = glyph)
    }
}

/** A thin ellipsised line of text. */
@Composable
internal fun CoaLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    ZillitText(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The module's glyphs, drawn from the web's own 14-point paths so both render the same shapes. */
internal object CoaIcons {

    /** Four squares — Cost Accounts, the tree view. */
    val Tree: ImageVector = stroked("CoaTree") {
        rect(1.5f, 1.5f, 4f, 4f)
        rect(8.5f, 1.5f, 4f, 4f)
        rect(1.5f, 8.5f, 4f, 4f)
        rect(8.5f, 8.5f, 4f, 4f)
    }

    /** A ruled grid — Balance Sheet Codes, the table view. */
    val Table: ImageVector = stroked("CoaTable") {
        rect(1.5f, 1.5f, 11f, 11f)
        moveTo(1.5f, 5f); lineTo(12.5f, 5f)
        moveTo(1.5f, 9f); lineTo(12.5f, 9f)
        moveTo(5f, 1.5f); lineTo(5f, 12.5f)
    }

    /** Two sliders — Layers. */
    val Sliders: ImageVector = stroked("CoaSliders") {
        moveTo(2.5f, 4f); lineTo(11.5f, 4f)
        moveTo(2.5f, 10f); lineTo(11.5f, 10f)
        // A ring this small under a 1.5 stroke closes into the web's solid knob.
        circle(5f, 4f, 0.75f)
        circle(9f, 10f, 0.75f)
    }

    val Expand: ImageVector = stroked("CoaExpand") {
        moveTo(9f, 1.5f); lineTo(12.5f, 1.5f); lineTo(12.5f, 5f)
        moveTo(5f, 12.5f); lineTo(1.5f, 12.5f); lineTo(1.5f, 9f)
        moveTo(12.5f, 1.5f); lineTo(8.5f, 5.5f)
        moveTo(1.5f, 12.5f); lineTo(5.5f, 8.5f)
    }

    val Eye: ImageVector = stroked("CoaEye") {
        moveTo(1f, 7f)
        curveTo(1f, 7f, 3.2f, 2.8f, 7f, 2.8f)
        curveTo(10.8f, 2.8f, 13f, 7f, 13f, 7f)
        curveTo(13f, 7f, 10.8f, 11.2f, 7f, 11.2f)
        curveTo(3.2f, 11.2f, 1f, 7f, 1f, 7f)
        close()
        circle(7f, 7f, 1.7f)
    }

    val Upload: ImageVector = stroked("CoaUpload") {
        moveTo(2f, 9.5f); lineTo(2f, 11f)
        curveTo(2f, 11.55f, 2.45f, 12f, 3f, 12f)
        lineTo(11f, 12f)
        curveTo(11.55f, 12f, 12f, 11.55f, 12f, 11f)
        lineTo(12f, 9.5f)
        moveTo(7f, 9f); lineTo(7f, 2f)
        moveTo(4f, 5f); lineTo(7f, 2f); lineTo(10f, 5f)
    }

    val Refresh: ImageVector = stroked("CoaRefresh") {
        moveTo(12f, 3f); lineTo(12f, 6f); lineTo(9f, 6f)
        moveTo(2f, 11f); lineTo(2f, 8f); lineTo(5f, 8f)
        moveTo(11.5f, 5.5f)
        curveTo(10.6f, 3.2f, 8.1f, 1.9f, 5.7f, 2.4f)
        curveTo(4.5f, 2.7f, 3.6f, 3.4f, 3f, 5f)
        moveTo(2.5f, 8.5f)
        curveTo(3.4f, 10.8f, 5.9f, 12.1f, 8.3f, 11.6f)
        curveTo(9.5f, 11.3f, 10.4f, 10.6f, 11f, 9f)
    }

    val ChevronRight: ImageVector = stroked("CoaChevronRight", viewport = 12f, stroke = 1.8f) {
        moveTo(4.5f, 2.5f); lineTo(8f, 6f); lineTo(4.5f, 9.5f)
    }

    val ChevronDown: ImageVector = stroked("CoaChevronDown", viewport = 12f, stroke = 1.8f) {
        moveTo(3f, 4.5f); lineTo(6f, 8f); lineTo(9f, 4.5f)
    }

    val ChevronLeft: ImageVector = stroked("CoaChevronLeft", viewport = 12f, stroke = 1.8f) {
        moveTo(7.5f, 2.5f); lineTo(4f, 6f); lineTo(7.5f, 9.5f)
    }

    val Plus: ImageVector = stroked("CoaPlus", viewport = 12f, stroke = 1.8f) {
        moveTo(6f, 2.5f); lineTo(6f, 10f)
        moveTo(2.5f, 6f); lineTo(9.5f, 6f)
    }

    val Edit: ImageVector = stroked("CoaEdit") {
        moveTo(2f, 12f); lineTo(3f, 9f); lineTo(9.5f, 2.5f)
        curveTo(10.05f, 1.95f, 10.95f, 1.95f, 11.5f, 2.5f)
        curveTo(12.05f, 3.05f, 12.05f, 3.95f, 11.5f, 4.5f)
        lineTo(5f, 11f); lineTo(2f, 12f)
        close()
    }

    val Trash: ImageVector = stroked("CoaTrash") {
        moveTo(2.5f, 4f); lineTo(11.5f, 4f)
        moveTo(5.5f, 4f); lineTo(5.5f, 2.5f); lineTo(8.5f, 2.5f); lineTo(8.5f, 4f)
        moveTo(3.5f, 4f); lineTo(4.1f, 11f)
        curveTo(4.15f, 11.5f, 4.6f, 11.9f, 5.1f, 11.9f)
        lineTo(8.9f, 11.9f)
        curveTo(9.4f, 11.9f, 9.85f, 11.5f, 9.9f, 11f)
        lineTo(10.5f, 4f)
        moveTo(6f, 6.5f); lineTo(6f, 10f)
        moveTo(8f, 6.5f); lineTo(8f, 10f)
    }

    val Close: ImageVector = stroked("CoaClose", viewport = 12f, stroke = 1.7f) {
        moveTo(3f, 3f); lineTo(9f, 9f)
        moveTo(9f, 3f); lineTo(3f, 9f)
    }

    val Check: ImageVector = stroked("CoaCheck", viewport = 12f, stroke = 2.1f) {
        moveTo(2f, 6.3f); lineTo(4.8f, 9f); lineTo(10f, 3.5f)
    }

    /** The empty chart's larger emblem: four boxes joined by a tree. */
    val TreeLarge: ImageVector = stroked("CoaTreeLarge", viewport = 28f, stroke = 1.4f) {
        rect(3f, 3f, 8f, 6f)
        rect(17f, 3f, 8f, 6f)
        rect(3f, 19f, 8f, 6f)
        rect(17f, 19f, 8f, 6f)
        moveTo(7f, 9f); lineTo(7f, 13f); lineTo(21f, 13f); lineTo(21f, 9f)
        moveTo(7f, 13f); lineTo(7f, 19f)
        moveTo(21f, 13f); lineTo(21f, 19f)
    }

    private fun PathBuilder.rect(x: Float, y: Float, width: Float, height: Float) {
        moveTo(x, y); lineTo(x + width, y); lineTo(x + width, y + height); lineTo(x, y + height); close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
        moveTo(cx - radius, cy)
        arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + radius, y1 = cy)
        arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - radius, y1 = cy)
        close()
    }

    private fun stroked(
        name: String,
        viewport: Float = VIEWPORT,
        stroke: Float = STROKE,
        pathBuilder: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = viewport.dp,
        defaultHeight = viewport.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        )
    }.build()

    private const val VIEWPORT = 14f
    private const val STROKE = 1.5f
}

internal val CARD_SHAPE = RoundedCornerShape(14.dp)
internal val SMALL_SHAPE = RoundedCornerShape(6.dp)
internal val TOOLBAR_SHAPE = RoundedCornerShape(10.dp)
internal val TOOLBAR_HEIGHT = 36.dp
private const val MONO_LINE_HEIGHT = 1.4f
