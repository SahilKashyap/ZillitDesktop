@file:Suppress("TooManyFunctions") // The web's `RecceShared.jsx`: one small atom per composable.

package com.zillit.desktop.feature.recce.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.StopKind

/** The web's `recce.css` colours that the theme has no token for. */
@Suppress("MagicNumber") // The stylesheet's palette, colour for colour.
internal object RecceColors {
    /** The brand amber, constant across themes. */
    val Brand = Color(0xFFF99300)
    val BrandStrong = Color(0xFFE07F00)

    /** What3Words red — the `///`. */
    @Composable
    fun w3wRed(): Color = if (ZillitTheme.colors.isDark) Color(0xFFFF5A5F) else Color(0xFFE11F26)

    @Composable
    fun link(): Color = if (ZillitTheme.colors.isDark) Color(0xFF60A5FA) else Color(0xFF1353D1)

    /** The web's unit colour key, by unit NAME; anything else is grey. */
    fun unit(name: String): Color = when (name) {
        "Stunt" -> Color(0xFFE8543E)
        "Main Unit" -> Brand
        "2nd Unit" -> Color(0xFF1353D1)
        "Splinter" -> Color(0xFF7C3AED)
        "Aerial" -> Color(0xFF00A06E)
        else -> Color(0xFF6B7280)
    }

    /** The kind label's colour on the timeline — the web's `KIND` table. */
    fun kind(kind: StopKind): Color = when (kind) {
        StopKind.Rendezvous, StopKind.Start -> Brand
        StopKind.Continue -> Color(0xFF6B7280)
        StopKind.Lunch -> Color(0xFF9CA3AF)
        StopKind.End -> Color(0xFF00A06E)
    }
}

/** The web's `az-tag` kinds. */
internal enum class TagKind { Success, Pending, Info, Brand, Neutral, Danger }

/** A small bordered chip with an optional status dot — the web's `Tag`. */
@Composable
internal fun RecceTag(text: String, kind: TagKind, modifier: Modifier = Modifier, dot: Boolean = false) {
    val dark = ZillitTheme.colors.isDark
    val (background, content, border) = tagColours(kind, dark)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).background(content, CircleShape))
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1,
        )
    }
}

private data class TagColours(val background: Color, val content: Color, val border: Color)

@Suppress("MagicNumber") // The web's stylesheet, colour for colour.
private fun tagColours(kind: TagKind, dark: Boolean): TagColours = when (kind) {
    TagKind.Success ->
        if (dark) TagColours(Color(0x2634D399), Color(0xFF34D399), Color(0x4D34D399))
        else TagColours(Color(0xFFE6F5EF), Color(0xFF0A7A52), Color(0xFFB6E4D3))
    TagKind.Pending ->
        if (dark) TagColours(Color(0x26F59E0B), Color(0xFFFBBF24), Color(0x4DF59E0B))
        else TagColours(Color(0xFFFFF7EB), Color(0xFFB45309), Color(0xFFFCE3B6))
    TagKind.Info ->
        if (dark) TagColours(Color(0x2660A5FA), Color(0xFF60A5FA), Color(0x4D60A5FA))
        else TagColours(Color(0xFFE8F0FE), Color(0xFF0D44B0), Color(0xFFC2D6F9))
    TagKind.Brand ->
        if (dark) TagColours(Color(0x29F99300), Color(0xFFFBBF24), Color(0x52F99300))
        else TagColours(Color(0xFFFFF7EB), Color(0xFFC46E00), Color(0xFFFCE3B6))
    TagKind.Neutral ->
        if (dark) TagColours(Color(0x0FFFFFFF), Color(0xBFFFFFFF), Color(0x1FFFFFFF))
        else TagColours(Color(0xFFF0F2F5), Color(0xFF4B5563), Color(0xFFE5E7EB))
    TagKind.Danger ->
        if (dark) TagColours(Color(0x26F87171), Color(0xFFF87171), Color(0x4DF87171))
        else TagColours(Color(0xFFFDECEA), Color(0xFFB53324), Color(0xFFF6C9C1))
}

/** "Published · v2" in green, or "Draft" in amber — the web's `StatusTag`. */
@Composable
internal fun RecceStatusTag(recce: Recce, modifier: Modifier = Modifier) {
    RecceTag(
        text = recce.statusLabel,
        kind = if (recce.isPublished) TagKind.Success else TagKind.Pending,
        dot = true,
        modifier = modifier,
    )
}

/** A coloured square and the unit's name — the web's `UnitTag`. Nothing for a blank unit. */
@Composable
internal fun UnitTag(name: String, modifier: Modifier = Modifier) {
    if (name.isBlank()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(RecceColors.unit(name), RoundedCornerShape(2.dp)))
        ZillitText(
            text = name,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The What3Words chip — red `///`, the words in mono, a navigation arrow;
 * opens what3words, which hands off to satnav. The web's hero feature.
 */
@Composable
internal fun W3WChip(words: String, onOpen: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    if (words.isBlank()) return
    val colors = ZillitTheme.colors
    val red = RecceColors.w3wRed()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) red else colors.border, label = "w3wBorder")
    val arrow by animateColorAsState(if (hovered) red else colors.textMuted, label = "w3wArrow")
    val size = if (small) 12.sp else 13.sp
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = if (small) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(
            text = "///",
            style = ZillitTheme.typography.label.copy(
                fontSize = size,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (-0.5).sp,
            ),
            color = red,
        )
        ZillitText(
            text = words,
            style = ZillitTheme.typography.label.copy(
                fontSize = size,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            color = colors.textPrimary,
            maxLines = 1,
        )
        ZillitIcon(icon = RecceIcons.Navigation, tint = arrow, size = if (small) 12.dp else 14.dp)
    }
}

/** A blue text link with a leading icon — the web's `az-link` (Map, Open in Google Maps). */
@Composable
internal fun RecceLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = RecceIcons.MapPin,
    iconSize: Dp = 13.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.5.sp,
) {
    val link = RecceColors.link()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { ZillitIcon(icon = it, tint = link, size = iconSize) }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.label.copy(
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                textDecoration = if (hovered) TextDecoration.Underline else TextDecoration.None,
            ),
            color = link,
            maxLines = 1,
        )
    }
}

/** Up to five overlapping initials, ringed in the surface colour — the web's `AvatarStack`. */
@Composable
internal fun AvatarStack(names: List<String>, modifier: Modifier = Modifier, max: Int = 5) {
    val surface = ZillitTheme.colors.surface
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        names.take(max).forEachIndexed { index, name ->
            Box(
                modifier = Modifier
                    .offset(x = if (index == 0) 0.dp else (-9 * index).dp)
                    .size(30.dp)
                    .background(surface, CircleShape)
                    .padding(2.dp),
            ) {
                ZillitAvatar(name = name.ifBlank { "?" }, size = 26.dp)
            }
        }
    }
}

/** The web's `az-card`: a bordered, softly shadowed panel. */
@Composable
internal fun RecceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(CARD_SHAPE)
            .background(colors.surface)
            .border(1.dp, colors.border, CARD_SHAPE),
        content = content,
    )
}

/** The card's title row with a hairline under it — the web's `az-card-head`. */
@Composable
internal fun RecceCardHead(title: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke(this)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** An uppercase, letter-spaced label with a brand-coloured icon — the web's `az-desc-label`. */
@Composable
internal fun DescLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        ZillitIcon(icon = icon, tint = RecceColors.Brand, size = 16.dp)
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** The round back button the tool header wears — the Sides / PO `BackButton` geometry. */
@Composable
internal fun RoundBackButton(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) RecceColors.Brand else colors.border, label = "backBorder")
    val tint by animateColorAsState(if (hovered) RecceColors.Brand else colors.textSecondary, label = "backTint")
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, border, CircleShape)
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.ArrowLeft, tint = tint, size = 18.dp)
    }
}

/**
 * The full-width banner every recce screen wears — the web's `RecceHeader`:
 * a round back button and the title on the left, the page's actions on the
 * right. The theme toggle is the app's, not the tool's.
 */
@Composable
internal fun RecceToolHeader(
    title: String,
    onBack: (() -> Unit)?,
    titleExtra: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .height(HEADER_HEIGHT)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                onBack?.let { RoundBackButton(it) }
                ZillitText(
                    text = title,
                    style = ZillitTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleExtra?.invoke(this)
            }
            actions?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = it,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/**
 * The scrolling page under the header: the web's `.recce-body`, a centred
 * container at 1440 (or 1200 for the form) with the warm brand glow the
 * stylesheet paints in the top-right corner.
 */
@Composable
internal fun RecceBody(
    modifier: Modifier = Modifier,
    narrow: Boolean = false,
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
        state = scroll,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (narrow) BODY_NARROW else BODY_WIDE)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 48.dp),
            content = content,
        )
    }
}

/** Overlays the same ground the body scrolls over, for a centred spinner. */
@Composable
internal fun RecceCanvas(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The 26dp numbered disc a form section starts with. */
@Composable
internal fun SectionNumber(n: Int) {
    Box(
        modifier = Modifier.size(26.dp).background(ZillitTheme.colors.accentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = n.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
            color = ZillitTheme.colors.accentText,
        )
    }
}

/** Body text in the card's secondary colour, for descriptions and helper lines. */
@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 13.sp) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium.copy(fontSize = size),
        color = ZillitTheme.colors.textSecondary,
        modifier = modifier,
    )
}

/** Sets a body style for a subtree — the web's inherited 14px Lato. */
@Composable
internal fun RecceTextDefaults(content: @Composable () -> Unit) {
    ProvideTextStyle(ZillitTheme.typography.bodyLarge, content)
}

internal val CARD_SHAPE = RoundedCornerShape(8.dp)
private val HEADER_HEIGHT = 60.dp
private val BODY_WIDE = 1440.dp
private val BODY_NARROW = 1200.dp
