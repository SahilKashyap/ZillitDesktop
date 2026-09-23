@file:Suppress("TooManyFunctions") // A kit of small shared pieces; each is one composable.

package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.MapPalette
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.TypeStyle

/** A `#RRGGBB` string as a colour — the domain speaks hex so the page and Compose agree. */
internal fun hexColor(hex: String, fallback: Color = Color.Gray): Color {
    val clean = hex.removePrefix("#")
    val value = clean.toLongOrNull(HEX_RADIX) ?: return fallback
    return when (clean.length) {
        RGB_DIGITS -> Color(OPAQUE or value)
        else -> fallback
    }
}

/** The map module's own hues — the web's Tailwind shades, named by what they do. */
internal object MapColors {
    val Brand: Color = hexColor(MapPalette.BRAND)
    val BrandText: Color = hexColor("#EA580C")
    val BrandActive: Color = hexColor("#C2410C")
    val BrandSoft: Color = hexColor("#FFF7ED")
    val BrandSoftStrong: Color = hexColor("#FFEDD5")
    val Zone: Color = hexColor(MapPalette.ZONE_ACCENT)
    val ZoneDeep: Color = hexColor("#1D4ED8")
    val ZoneSoft: Color = hexColor("#EFF6FF")
    val ZoneCircle: Color = hexColor(MapPalette.ZONE_CIRCLE)
    val Danger: Color = hexColor("#EF4444")
    val DangerDeep: Color = hexColor("#DC2626")
    val DangerSoft: Color = hexColor("#FEF2F2")
    val Success: Color = hexColor("#16A34A")
    val SuccessSoft: Color = hexColor("#F0FDF4")
    val Info: Color = hexColor("#2563EB")
    val InfoSoft: Color = hexColor("#EFF6FF")
    val Warning: Color = hexColor("#D97706")
    val WarningSoft: Color = hexColor("#FFFBEB")
    val PickupDot: Color = hexColor("#22C55E")
    val DropDot: Color = hexColor("#EF4444")
    val GuideCard: Color = hexColor("#111827")
}

/** The accent's pale well: its soft tint in light mode, a raised surface in dark. */
@Composable
internal fun softOf(accent: Color): Color =
    if (ZillitTheme.colors.isDark) accent.copy(alpha = DARK_SOFT_ALPHA) else accent.copy(alpha = LIGHT_SOFT_ALPHA)

/** Whether the pointer is over this element, and the hand cursor while it is. */
@Composable
internal fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

/**
 * The coloured header every web drawer wears (`mm-hero`): a gradient of the
 * panel's accent, a round close button, chips and actions top-right, then the
 * title and one line saying what the panel is for.
 */
@Composable
internal fun HeroHeader(
    accent: Color,
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeIcon: ImageVector = ZillitIcons.Close,
    closeLabel: String = str(S.close),
    trailing: @Composable RowScope.() -> Unit = {},
    eyebrow: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = HERO_FADE)))),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroIconButton(icon = closeIcon, description = closeLabel, onClick = onClose)
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (eyebrow != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                    content = eyebrow,
                )
            }
            ZillitText(
                text = title,
                style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
            )
            if (!subtitle.isNullOrBlank()) {
                ZillitText(
                    text = subtitle,
                    style = ZillitTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = HERO_SUBTITLE_ALPHA),
                    maxLines = 3,
                )
            }
        }
        below?.invoke(this)
    }
}

@Composable
internal fun HeroIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (hovered) HERO_HOVER_ALPHA else HERO_IDLE_ALPHA))
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, contentDescription = description, tint = Color.White, size = 18.dp)
    }
}

/** A translucent count chip in a hero: "3 Cities". */
@Composable
internal fun HeroChip(text: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = HERO_CHIP_ALPHA))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) ZillitIcon(icon = icon, tint = Color.White, size = 12.dp)
        ZillitText(text = text, style = labelBold(12.sp), color = Color.White, maxLines = 1)
    }
}

/** A hero's action: translucent, or [solid] white with the accent's text. */
@Composable
internal fun HeroPillButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    solid: Boolean = false,
    accent: Color = MapColors.BrandText,
) {
    val (source, hovered) = rememberHover()
    val background = when {
        solid && hovered -> MapColors.BrandSoft
        solid -> Color.White
        hovered -> Color.White.copy(alpha = HERO_HOVER_ALPHA)
        else -> Color.White.copy(alpha = HERO_IDLE_ALPHA)
    }
    val content = if (solid) accent else Color.White
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) ZillitIcon(icon = icon, tint = content, size = 14.dp)
        ZillitText(text = text, style = labelBold(13.sp), color = content, maxLines = 1)
    }
}

/**
 * A white card with an icon chip and a title over a hairline — the web's
 * `Section` inside every form and detail drawer.
 */
@Composable
internal fun SectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconChip(icon = icon, tint = accent, background = softOf(accent), size = 28.dp, iconSize = 14.dp)
            ZillitText(
                text = title,
                style = ZillitTheme.typography.titleSmall,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke(this)
        }
        Box(Modifier.padding(top = 10.dp, bottom = 14.dp).fillMaxWidth().height(1.dp).background(colors.divider))
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = if (enabled) Modifier else Modifier.background(Color.Transparent),
            content = content,
        )
    }
}

/** The small uppercase label with an icon chip over a field. */
@Composable
internal fun FieldLabel(text: String, icon: ImageVector?, accent: Color, required: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) {
            IconChip(icon = icon, tint = accent, background = softOf(accent), size = 20.dp, iconSize = 11.dp)
        }
        ZillitText(
            text = text.uppercase(),
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = ZillitTheme.colors.textSecondary,
        )
        if (required) ZillitText(text = "*", style = labelBold(11.sp), color = accent)
    }
}

@Composable
internal fun IconChip(
    icon: ImageVector,
    tint: Color,
    background: Color,
    size: Dp = 32.dp,
    iconSize: Dp = 16.dp,
    corner: Dp = 8.dp,
) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(corner)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = tint, size = iconSize)
    }
}

/** A type's glyph on its colour — the web's coloured icon squares. */
@Composable
internal fun TypeGlyph(style: TypeStyle, size: Dp = 32.dp, corner: Dp = 8.dp, fontSize: Int = 14) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(corner)).background(hexColor(style.colorHex)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = style.icon,
            style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

enum class BadgeTone { Primary, Secondary, Danger, Zone, OnAccent }

/** `common/Badge.jsx` — a count pill that grows only when the number needs it. */
@Composable
internal fun CountBadge(count: Int, tone: BadgeTone = BadgeTone.Primary, large: Boolean = false) {
    val colors = ZillitTheme.colors
    val (background, content) = when (tone) {
        BadgeTone.Primary -> if (colors.isDark) {
            MapColors.Brand.copy(alpha = DARK_BADGE_ALPHA) to MapColors.Brand
        } else {
            MapColors.BrandSoftStrong to MapColors.BrandActive
        }
        BadgeTone.Secondary -> colors.surfaceSunken to colors.textSecondary
        BadgeTone.Danger -> MapColors.Danger to Color.White
        BadgeTone.Zone -> softOf(MapColors.Zone) to MapColors.Zone
        BadgeTone.OnAccent -> Color.White.copy(alpha = 0.3f) to Color.White
    }
    val height = if (large) 22.dp else 18.dp
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = height, minHeight = height)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = if (large) 6.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > MAX_BADGE) "99+" else count.toString(),
            style = labelBold(if (large) 12.sp else 10.sp),
            color = content,
            maxLines = 1,
        )
    }
}

enum class ActionTone { Neutral, Accent, Zone, Danger, ZoneActive }

/** The bordered small buttons on cards: View, Edit, Details, Map, Hide, the trash can. */
@Composable
@Suppress("CyclomaticComplexMethod") // One control; its states are read in place.
internal fun CardAction(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String? = null,
    tone: ActionTone = ActionTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val (source, hovered) = rememberHover()
    val accent = when (tone) {
        ActionTone.Neutral -> colors.textPrimary
        ActionTone.Accent -> MapColors.BrandText
        ActionTone.Zone, ActionTone.ZoneActive -> MapColors.Zone
        ActionTone.Danger -> MapColors.DangerDeep
    }
    val active = tone == ActionTone.ZoneActive
    val background = when {
        active -> if (hovered) MapColors.ZoneDeep else MapColors.Zone
        hovered -> softOf(accent)
        else -> colors.surface
    }
    val content = when {
        active -> Color.White
        hovered -> accent
        tone == ActionTone.Danger -> MapColors.Danger
        else -> colors.textSecondary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, if (active) MapColors.Zone else colors.border, RoundedCornerShape(6.dp))
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = if (text == null) 8.dp else 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon = icon, tint = content, size = 13.dp, contentDescription = text)
        if (text != null) ZillitText(text = text, style = labelBold(12.sp, FontWeight.Medium), color = content)
    }
}

/** A labelled line in a detail card: icon chip, small caps label, value. */
@Composable
internal fun InfoRow(
    icon: ImageVector,
    label: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        IconChip(icon = icon, tint = accent, background = softOf(accent), size = 32.dp, iconSize = 14.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = label.uppercase(),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
                color = ZillitTheme.colors.textMuted,
            )
            content()
        }
    }
}

/** An empty list's message: a soft icon tile, a line, a hint, an action. */
@Composable
internal fun EmptyBlock(
    icon: ImageVector,
    accent: Color,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconChip(
            icon = icon,
            tint = accent,
            background = softOf(accent),
            size = 56.dp,
            iconSize = 24.dp,
            corner = 16.dp,
        )
        Spacer(Modifier.height(8.dp))
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            color = ZillitTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(300.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}

/**
 * Google's suggestions under a search field, in the page flow rather than a
 * popup — a popup that strayed over the map would be drawn beneath it.
 */
@Composable
internal fun SuggestionList(
    predictions: List<PlacePrediction>,
    onPick: (PlacePrediction) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = MapIcons.MapPin,
) {
    if (predictions.isEmpty()) return
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .heightIn(max = 260.dp)
            .padding(vertical = 4.dp),
    ) {
        predictions.take(MAX_SUGGESTIONS).forEach { prediction ->
            val (source, hovered) = rememberHover()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (hovered) colors.surfaceHover else Color.Transparent)
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) { onPick(prediction) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitIcon(icon = icon, tint = colors.textMuted, size = 14.dp)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = prediction.mainText,
                        style = labelBold(13.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (prediction.secondaryText.isNotBlank()) {
                        ZillitText(
                            text = prediction.secondaryText,
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A read-only value box — the auto-filled latitude and longitude. */
@Composable
internal fun ReadOnlyBox(value: String, placeholder: String, mono: Boolean = true, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(
            text = value.ifBlank { placeholder },
            style = if (mono) {
                ZillitTheme.typography.bodyMedium.copy(fontFamily = ZillitTheme.fonts.mono)
            } else {
                ZillitTheme.typography.bodyMedium
            },
            color = if (value.isBlank()) colors.textMuted else colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** A choice button in a row of presets — the radius presets, the centre modes. */
@Composable
internal fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else if (hovered) softOf(accent) else colors.surface)
            .border(1.dp, if (selected) accent else colors.borderStrong, RoundedCornerShape(8.dp))
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val content = if (selected) Color.White else if (hovered) accent else colors.textPrimary
        if (icon != null) ZillitIcon(icon = icon, tint = content, size = 14.dp)
        ZillitText(text = text, style = labelBold(13.sp, FontWeight.Medium), color = content, maxLines = 1)
    }
}

/** A busy line, for the lists that load under a header. */
@Composable
internal fun LoadingBlock(text: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitSpinner(size = 24.dp)
        if (text != null) {
            ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
        }
    }
}

/** A soft info strip: the city a form works in, a found intersection. */
@Composable
internal fun SoftBanner(
    accent: Color,
    icon: ImageVector,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(softOf(accent))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = icon, tint = accent, size = 15.dp, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(text = title, style = labelBold(13.sp), color = accent)
            if (!body.isNullOrBlank()) {
                ZillitText(
                    text = body,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

/** Helper text under a field — the web's grey italic hints. */
@Composable
internal fun Hint(text: String, color: Color? = null) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        color = color ?: ZillitTheme.colors.textMuted,
    )
}

/** The scroll body of a side panel: padded, card-spaced. */
internal val PanelBodyPadding = PaddingValues(20.dp)

/**
 * A panel beside the map — the web's drawers, set beside the map rather than
 * over it: the map is a browser surface that would paint over a drawer, and
 * beside it the map stays live while a form is filled in.
 */
@Composable
internal fun SidePanelFrame(
    width: Dp,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    header: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(if (colors.isDark) colors.canvas else colors.surfaceSunken),
    ) {
        header()
        Column(Modifier.weight(1f).fillMaxWidth(), content = body)
        if (footer != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Box(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
                footer()
            }
        }
    }
}

/** A filter box at the top of a panel list. */
@Composable
internal fun PanelFilter(value: String, placeholder: String, onChange: (String) -> Unit) {
    com.zillit.desktop.core.designsystem.component.ZillitTextField(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        leadingIcon = ZillitIcons.Search,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        trailingContent = if (value.isNotEmpty()) {
            { CardAction(onClick = { onChange("") }, icon = ZillitIcons.Close) }
        } else {
            null
        },
    )
}

/**
 * A multi-line field — the web's `<textarea rows={3}>`. The shared text field
 * sizes its border to one line whatever height it is given, which left the
 * description a one-line box floating in empty space; this one's border is the
 * whole writing area, growing with the text.
 */
@Composable
internal fun MapTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = TEXT_AREA_MIN_HEIGHT,
) {
    val colors = ZillitTheme.colors
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by animateColorAsState(
        when {
            focused -> colors.accent
            hovered -> colors.borderStrong
            else -> colors.border
        },
        label = "textAreaBorder",
    )
    val shape = ZillitTheme.shapes.medium
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, borderColor, shape)
            .hoverable(interaction)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            ZillitText(placeholder, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

private val TEXT_AREA_MIN_HEIGHT = 88.dp

internal fun labelBold(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontSize = size, fontWeight = weight)

private const val DARK_BADGE_ALPHA = 0.22f
private const val HEX_RADIX = 16
private const val RGB_DIGITS = 6
private const val OPAQUE = 0xFF000000L
private const val HERO_FADE = 0.87f
private const val HERO_SUBTITLE_ALPHA = 0.85f
private const val HERO_IDLE_ALPHA = 0.15f
private const val HERO_HOVER_ALPHA = 0.25f
private const val HERO_CHIP_ALPHA = 0.2f
private const val LIGHT_SOFT_ALPHA = 0.1f
private const val DARK_SOFT_ALPHA = 0.18f
private const val MAX_BADGE = 99
private const val MAX_SUGGESTIONS = 6
