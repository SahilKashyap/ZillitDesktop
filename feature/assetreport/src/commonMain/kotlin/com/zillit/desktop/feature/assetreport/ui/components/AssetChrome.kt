package com.zillit.desktop.feature.assetreport.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetFormat
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import com.zillit.desktop.feature.assetreport.ui.CategoryFilter

/**
 * The Account Hub's top bar, as both register screens wear it: a square back
 * button, the title (or a breadcrumb), anything the screen puts on the right.
 */
@Composable
internal fun AssetTopBar(
    backLabel: String,
    onBack: () -> Unit,
    title: @Composable RowScope.() -> Unit,
    right: @Composable RowScope.() -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackSquare(label = backLabel, onClick = onBack)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = title,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = right,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun BackSquare(label: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(SQUARE_SHAPE)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, colors.border, SQUARE_SHAPE)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = label,
            tint = colors.textSecondary,
            size = 15.dp,
        )
    }
}

/** A filter's eyebrow — the web's 10.5px uppercase `field-lbl`. */
@Composable
internal fun FieldLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.12.em,
        ),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = Modifier.padding(start = 2.dp),
    )
}

/** A detail section's heading — the web's `det-slbl`. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.09.em,
        ),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The Keep / Sell pill: tinted fill, uppercase ink, no border. */
@Composable
internal fun CategoryPill(category: AssetCategory) {
    val tint = assetPalette().category(category) ?: return
    TintChip(text = category.wire, tint = tint, horizontal = 9.dp, tracking = 0.07)
}

/** The expense type's tag, and a rental's date range under it. */
@Composable
internal fun ExpenseCell(line: AssetLine) {
    val tint = assetPalette().expense(line.expenditureType) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TintChip(text = line.expenditureType.label, tint = tint, horizontal = 10.dp, tracking = 0.06)
        val range = if (line.expenditureType == ExpenditureType.Rent) {
            AssetFormat.range(line.rentalStartMillis, line.rentalEndMillis)
        } else {
            ""
        }
        if (range.isNotEmpty()) {
            // Proportional, not the mono the web uses: this app's mono is wider than
            // DM Mono, and two full dates must fit the column without an ellipsis.
            ZillitText(
                text = range,
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                color = ZillitTheme.colors.textMuted,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TintChip(text: String, tint: AssetTint, horizontal: androidx.compose.ui.unit.Dp, tracking: Double) {
    Box(
        modifier = Modifier
            .clip(CHIP_SHAPE)
            .background(tint.soft)
            .padding(horizontal = horizontal, vertical = 4.dp),
    ) {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = tracking.em,
            ),
            color = tint.ink,
            maxLines = 1,
        )
    }
}

/**
 * All / Keep / Sell on a sunken track — the web's `.seg`. The picked segment
 * lifts onto the surface in its own colour: accent, keep green, sell amber.
 */
@Composable
internal fun CategorySegments(selected: CategoryFilter, onSelect: (CategoryFilter) -> Unit) {
    val palette = assetPalette()
    Row(
        modifier = Modifier
            .height(FIELD_HEIGHT)
            .clip(TRACK_SHAPE)
            .background(palette.track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryFilter.entries.forEach { filter ->
            val ink = when (filter) {
                CategoryFilter.All -> ZillitTheme.colors.accent
                CategoryFilter.Keep -> palette.keep.ink
                CategoryFilter.Sell -> palette.sell.ink
            }
            Segment(label = filter.label, picked = selected == filter, ink = ink) { onSelect(filter) }
        }
    }
}

@Composable
private fun Segment(label: String, picked: Boolean, ink: Color, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (picked) colors.surface else Color.Transparent, label = "segment")
    Box(
        modifier = Modifier
            .then(if (picked) Modifier.shadow(2.dp, SEGMENT_SHAPE) else Modifier)
            .clip(SEGMENT_SHAPE)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
            color = when {
                picked -> ink
                hovered -> colors.textPrimary
                else -> colors.textSecondary
            },
            maxLines = 1,
        )
    }
}

/** The web's search box: 270 × 44, a card shadow at rest, an accent ring in focus. */
@Composable
internal fun AssetSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .width(270.dp)
            .height(FIELD_HEIGHT)
            .shadow(if (focused) 0.dp else 1.dp, FIELD_SHAPE)
            .clip(FIELD_SHAPE)
            .background(colors.surface)
            .border(1.dp, if (focused) colors.accent else colors.border, FIELD_SHAPE)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Search, tint = colors.textMuted, size = 16.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                ZillitText(
                    text = "Search assets, vendors, refs…",
                    style = ZillitTheme.typography.bodyLarge,
                    color = colors.textDisabled,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = ZillitTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The accent Save — the header's compact one and the note's large one. At rest
 * with nothing to save it goes quiet rather than looking broken; just after a
 * save lands it turns green and says so.
 */
@Composable
internal fun SaveButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
    confirmed: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val look = saveLook(enabled = enabled, compact = compact, confirmed = confirmed, hovered = hovered)
    val fill by animateColorAsState(look.fill, label = "assetSave")
    val shape = if (compact) COMPACT_SAVE_SHAPE else LARGE_SAVE_SHAPE
    Box(
        modifier = Modifier
            .then(if (look.glow) Modifier.shadow(6.dp, shape, spotColor = look.fill) else Modifier)
            .clip(shape)
            .background(fill)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 26.dp, vertical = if (compact) 7.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontSize = if (compact) 12.5.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = look.ink,
            maxLines = 1,
        )
    }
}

private class SaveLook(val fill: Color, val ink: Color, val glow: Boolean)

/**
 * The web's `.save` / `.hdr-save` states. Dark mode's accent is a light orange,
 * so a label on it takes the dark ink rather than white.
 */
@Composable
private fun saveLook(enabled: Boolean, compact: Boolean, confirmed: Boolean, hovered: Boolean): SaveLook {
    val colors = ZillitTheme.colors
    val onBright = if (colors.isDark) colors.canvas else Color.White
    return when {
        confirmed -> SaveLook(assetPalette().keep.ink, onBright, glow = false)
        !enabled && compact -> SaveLook(Color.Transparent, colors.textDisabled, glow = false)
        !enabled -> SaveLook(
            fill = colors.accent.copy(alpha = DISABLED_FILL),
            ink = if (colors.isDark) colors.textMuted else Color.White,
            glow = false,
        )
        else -> SaveLook(if (hovered) colors.accentHover else colors.accent, colors.textOnAccent, glow = !compact)
    }
}

/**
 * Takes the presses that reach it, after its children have had theirs.
 *
 * For a surface laid over another: being hit at all stops the siblings beneath
 * from being hit, and consuming the press stops a backdrop's tap behind it.
 * Only presses — consuming the moves too would cancel every button inside,
 * whose tap gives up on a consumed move. `clickable { }` would swallow as well,
 * but it merges everything inside into one accessibility node: a whole page
 * read out as a single run-on label.
 */
internal fun Modifier.swallowPresses(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
        }
    }
}

/** A tap on a backdrop — without `clickable`'s semantics merge, for the same reason. */
internal fun Modifier.onBackdropTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    detectTapGestures { onTap() }
}

private val SQUARE_SHAPE = RoundedCornerShape(10.dp)
private val COMPACT_SAVE_SHAPE = RoundedCornerShape(9.dp)
private val LARGE_SAVE_SHAPE = RoundedCornerShape(11.dp)
private val CHIP_SHAPE = RoundedCornerShape(7.dp)
private val TRACK_SHAPE = RoundedCornerShape(13.dp)
private val SEGMENT_SHAPE = RoundedCornerShape(10.dp)
private const val DISABLED_FILL = 0.32f
