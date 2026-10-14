// The hub's visual kit: one composable per web component it stands in for.
// The numbers are the web's own colours and sizes, kept verbatim so they diff
// against hubTheme.css and the components' inline styles.
@file:Suppress("TooManyFunctions", "LongParameterList", "MagicNumber")

package com.zillit.desktop.feature.transporthub.ui.kit

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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubLocation

/**
 * The hub's glyphs, in one place.
 *
 * The web draws the hub with Phosphor icons. Most have a match in the app's
 * own sets; the vehicle-specific few (car profile, steering wheel, path, film
 * slate, wrench, folded map) do not, and borrow the nearest glyph here so the
 * real ones can be dropped in without touching a page.
 */
internal object HubGlyphs {
    val Home: ImageVector = AhIcons.Home
    val PaperPlane: ImageVector = AhIcons.Send
    val Path: ImageVector = AhIcons.Dashboard
    val Chat: ImageVector = AhIcons.MessageCircle
    val Truck: ImageVector = ZillitIcons.Transport
    val Car: ImageVector = ZillitToolIcons.Transportation
    val CarProfile: ImageVector = ZillitToolIcons.Transportation
    val FilmSlate: ImageVector = ZillitToolIcons.Script
    val Users: ImageVector = AhIcons.Users
    val User: ImageVector = AhIcons.User
    val Person: ImageVector = AhIcons.Person
    val MapPin: ImageVector = AhIcons.MapPin
    val MapTrifold: ImageVector = AhIcons.Globe
    val Wrench: ImageVector = ZillitIcons.Tools
    val SteeringWheel: ImageVector = ZillitToolIcons.Transportation
    val Clock: ImageVector = AhIcons.Clock
    val Calendar: ImageVector = AhIcons.Calendar
    val Check: ImageVector = AhIcons.CheckCircle
    val XCircle: ImageVector = AhIcons.XCircle
    val Warning: ImageVector = AhIcons.AlertTriangle
    val Hourglass: ImageVector = AhIcons.Clock
    val Spinner: ImageVector = AhIcons.Refresh
    val SignIn: ImageVector = AhIcons.Login
    val UserCheck: ImageVector = AhIcons.UserPlus
    val Plus: ImageVector = AhIcons.Plus
    val Funnel: ImageVector = AhIcons.Filter
    val Close: ImageVector = AhIcons.X
    val ArrowLeft: ImageVector = AhIcons.ArrowLeft
    val ArrowRight: ImageVector = AhIcons.ArrowRight
    val ArrowUp: ImageVector = AhIcons.ChevronUp
    val ArrowDown: ImageVector = AhIcons.ChevronDown
    val CaretLeft: ImageVector = AhIcons.ChevronLeft
    val CaretRight: ImageVector = AhIcons.ChevronRight
    val Pencil: ImageVector = AhIcons.Edit
    val Trash: ImageVector = AhIcons.Trash
    val Phone: ImageVector = AhIcons.Phone
    val Moon: ImageVector = AhIcons.Moon
    val Sun: ImageVector = AhIcons.Sun
    val Lightning: ImageVector = AhIcons.AlertCircle
}

/* ── Type ──────────────────────────────────────────────────────────────── */

/** A text style at the web's own size and weight. */
internal fun hubType(size: TextUnit, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 0.sp) =
    TextStyle(fontSize = size, fontWeight = weight, letterSpacing = spacing)

@Composable
internal fun HubText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    spacing: TextUnit = 0.sp,
    maxLines: Int = 1,
) {
    ZillitText(
        text = text,
        style = hubType(size, weight, spacing),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/* ── Page scaffolding ──────────────────────────────────────────────────── */

/**
 * A hub page: the banner across the top and the page body on the hub's grey.
 *
 * The web's `SectionBanner` bleeds to the content's edges (`margin: -24px`)
 * and the body sits in padding below it, so the banner reads as the page's
 * own header bar rather than a heading inside a card.
 */
@Composable
internal fun HubPage(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val p = hub
    Column(modifier.fillMaxWidth().fillMaxHeight().background(p.bg)) {
        HubBanner(title = title, icon = icon, subtitle = subtitle, actions = actions)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = body,
        )
    }
}

/** The web's `SectionBanner`: a white bar with an amber stripe on its left edge. */
@Composable
internal fun HubBanner(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = hub
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(p.surface)
            .drawBehind {
                // The 3px accent stripe and the 1px rule underneath.
                drawRect(p.accent, size = size.copy(width = 3.dp.toPx()))
                drawRect(
                    p.border,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(start = 23.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HubIconTile(icon = icon, background = p.accentBg, tint = p.accentDark, size = 40.dp)
        Column(Modifier.weight(1f)) {
            HubText(title, 20.sp, p.textPrimary, weight = FontWeight.SemiBold)
            subtitle?.let { HubText(it, 12.sp, p.textMuted) }
        }
        actions?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = it) }
        LocalHubThemeToggle.current?.let { toggle ->
            val dark = p.isDark
            ZillitTooltip(
                text = if (dark) str(S.desktop_hub_switch_to_light) else str(S.desktop_hub_switch_to_dark),
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { toggle(!dark) },
                    Alignment.Center,
                ) { ZillitIcon(if (dark) HubGlyphs.Sun else HubGlyphs.Moon, tint = p.accentDark, size = 18.dp) }
            }
        }
    }
}

/**
 * The hub's own theme switch, when the shell offers one — the banner's
 * moon/sun. Null in a test or any host that keeps the app's theme.
 */
internal val LocalHubThemeToggle = androidx.compose.runtime.staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

/**
 * A padded page body for a page that lays itself out as one column — the
 * coordinator's pages, which open with a [HubPageHeader].
 */
@Composable
internal fun HubPaddedPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

/**
 * The banner as the first child of a padded page.
 *
 * The web's `SectionBanner` sits inside the page's padding and pulls itself
 * out with `margin: -24px -24px 16px`, so it spans the content area edge to
 * edge; [bleed] does the same here.
 */
@Composable
internal fun HubPageHeader(
    title: String,
    icon: ImageVector,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    HubBanner(
        title = title,
        icon = icon,
        subtitle = description,
        modifier = Modifier.bleed(horizontal = 24.dp, top = 16.dp),
        actions = actions,
    )
}

/**
 * Widens a child past its parent's padding — CSS's negative margin.
 *
 * Measured [horizontal] wider on each side and placed [top] higher, while
 * reporting the parent's own width so the rest of the column is untouched.
 * A parent with no width bound gets the child as it is.
 */
internal fun Modifier.bleed(horizontal: Dp, top: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val plain = measurable.measure(constraints)
        return@layout layout(plain.width, plain.height) { plain.place(0, 0) }
    }
    val h = horizontal.roundToPx()
    val t = top.roundToPx()
    val wide = constraints.maxWidth + 2 * h
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, (placeable.height - t).coerceAtLeast(0)) {
        placeable.place(-h, -t)
    }
}

/** A rounded square holding one glyph — every header and stat card has one. */
@Composable
internal fun HubIconTile(
    icon: ImageVector,
    background: Color,
    tint: Color,
    size: Dp = 40.dp,
    radius: Dp = 10.dp,
    iconSize: Dp = size / 2,
    border: Color? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(background)
            .then(
                if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(radius)) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(icon, tint = tint, size = iconSize) }
}

/** The web's white card: 1px border, a whisper of shadow, generous radius. */
@Composable
internal fun HubCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    radius: Dp = 16.dp,
    accentTop: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = hub
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .shadow(1.dp, shape, clip = false, ambientColor = SHADOW, spotColor = SHADOW)
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.border, shape),
    ) {
        accentTop?.let { Box(Modifier.fillMaxWidth().height(3.dp).background(it)) }
        Column(Modifier.padding(padding), content = content)
    }
}

/** The dashed "nothing here" card every hub list falls back to. */
@Composable
internal fun HubEmpty(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = HubGlyphs.CarProfile,
    emphasis: String? = null,
) {
    val p = hub
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.surface)
            .dashedBorder(p.border, 12.dp)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitIcon(icon, tint = p.textMuted, size = 40.dp)
        if (emphasis == null) {
            HubText(message, 13.sp, p.textMuted, maxLines = 3)
        } else {
            // "No rides yet. Tap **Call a Driver** to request one." — the
            // action is named in bold, exactly as the web sets it.
            val (before, after) = message.split(emphasis, limit = 2).let {
                it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubText(before, 13.sp, p.textMuted)
                HubText(emphasis, 13.sp, p.textPrimary, weight = FontWeight.Bold)
                HubText(after, 13.sp, p.textMuted)
            }
        }
    }
}

/** A centred spinner on the hub's grey — the web's `HubLoader`. */
@Composable
internal fun HubLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 240.dp), Alignment.Center) { ZillitSpinner() }
}

/** An error strip above a list — the web's red banner. */
@Composable
internal fun HubErrorStrip(message: String) {
    HubText(
        message,
        13.sp,
        Color(0xFF991B1B),
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFEE2E2))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/* ── Buttons ───────────────────────────────────────────────────────────── */

/** The four button looks the hub uses, each the web's own. */
internal enum class HubButtonStyle {
    /** antd primary: amber fill, white text. */
    Primary,

    /** The vivid `#fc9404` call to action on cards (Track driver, View). */
    Vivid,

    /** The banner's darker amber call to action (Call a Driver). */
    Cta,

    /** White with a hairline border (View Details, Edit). */
    Outline,

    /** Pale red with a red border (Cancel). */
    Danger,

    /** Text only, amber (See all →, View Detail →). */
    Link,
}

@Composable
internal fun HubButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HubButtonStyle = HubButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    pill: Boolean = false,
    height: Dp = 40.dp,
    fontSize: TextUnit = 14.sp,
) {
    val p = hub
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = if (pill) CircleShape else RoundedCornerShape(if (height < 34.dp) 6.dp else 10.dp)
    val (bg, fg, border) = style.colors(p, hovered)
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .hoverable(interaction)
            .clickable(enabled = enabled && !loading, interactionSource = interaction, indication = null) {
                onClick()
            }
            .padding(horizontal = if (style == HubButtonStyle.Link) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) {
            ZillitSpinner(size = 14.dp)
        } else {
            icon?.let { ZillitIcon(it, tint = fg.copy(alpha = if (enabled) 1f else 0.5f), size = 16.dp) }
        }
        HubText(
            text,
            fontSize,
            fg.copy(alpha = if (enabled) 1f else 0.5f),
            weight = if (style == HubButtonStyle.Outline) FontWeight.SemiBold else FontWeight.Bold,
        )
    }
}

/** A button's fill, text and border for [this] style — the web's per-variant CSS. */
private fun HubButtonStyle.colors(p: HubPalette, hovered: Boolean): Triple<Color, Color, Color> = when (this) {
    HubButtonStyle.Primary ->
        Triple(if (hovered) p.accentDark else p.accent, Color.White, Color.Transparent)

    HubButtonStyle.Vivid -> Triple(VIVID.copy(alpha = if (hovered) 0.9f else 1f), Color.White, VIVID)
    HubButtonStyle.Cta -> Triple(if (hovered) Color(0xFFB45309) else p.accentDark, Color.White, Color.Transparent)
    HubButtonStyle.Outline ->
        Triple(if (hovered) p.surfaceHover else p.surface, p.textPrimary, p.border)

    HubButtonStyle.Danger -> Triple(
        Color(0xFFDC2626).copy(alpha = if (hovered) 0.14f else 0.08f),
        Color(0xFFB91C1C),
        Color(0xFFDC2626).copy(alpha = 0.35f),
    )

    HubButtonStyle.Link -> Triple(Color.Transparent, p.accentDark, Color.Transparent)
}

/* ── Pills ─────────────────────────────────────────────────────────────── */

/** One pill's look: what it says, its glyph, and its three colours. */
internal data class PillMeta(
    val label: String,
    val icon: ImageVector?,
    val fg: Color,
    val bg: Color,
    val border: Color,
    val bold: Boolean = false,
)

/**
 * A ride's status as the web's `RideStatusPill` draws it: an icon, the word
 * in capitals, and solid fill with a matching border. Every status in the
 * ride and trip vocabularies has its own colours; one the build has not met
 * falls back to grey with its own words.
 */
@Composable
internal fun HubStatusPill(status: String) {
    HubPill(statusMeta(status))
}

/** Priority, the same way. `normal` and `low` are drawn too, as the web does on cards. */
@Composable
internal fun HubPriorityPill(priority: String) {
    priorityMeta(priority)?.let { HubPill(it) }
}

@Composable
internal fun HubPill(meta: PillMeta) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(meta.bg)
            .border(1.dp, meta.border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        meta.icon?.let { ZillitIcon(it, tint = meta.fg, size = 13.dp) }
        HubText(
            meta.label.uppercase(),
            11.5.sp,
            meta.fg,
            weight = if (meta.bold) FontWeight.ExtraBold else FontWeight.Bold,
            spacing = 0.5.sp,
        )
    }
}

internal fun statusMeta(status: String): PillMeta {
    val key = status.trim().lowercase().replace('-', '_')
    return when (key) {
        "pending" -> PillMeta(str(S.pending), HubGlyphs.Hourglass, rgb(0x92400E), rgb(0xFEF3C7), rgb(0xFCD34D))
        "approved" -> PillMeta(str(S.approved), HubGlyphs.UserCheck, rgb(0x075985), rgb(0xE0F2FE), rgb(0x7DD3FC))
        "assigned" -> PillMeta(str(S.assigned), HubGlyphs.SignIn, rgb(0x3730A3), rgb(0xE0E7FF), rgb(0xA5B4FC))
        "in_transit" -> PillMeta(
            str(S.desktop_card_in_transit),
            HubGlyphs.Spinner,
            rgb(0x1E40AF),
            rgb(0xDBEAFE),
            rgb(0x93C5FD),
        )

        "in_progress" -> PillMeta(str(S.in_progress), HubGlyphs.Spinner, rgb(0x1E40AF), rgb(0xDBEAFE), rgb(0x93C5FD))
        "en_route_pickup" -> PillMeta(
            str(S.desktop_hub_en_route),
            HubGlyphs.ArrowRight,
            rgb(0xB45309),
            rgb(0xFEF3C7),
            rgb(0xFCD34D),
        )

        "at_pickup" -> PillMeta(
            str(S.desktop_hub_at_pickup),
            HubGlyphs.Clock,
            rgb(0x854D0E),
            rgb(0xFDE68A),
            rgb(0xFACC15),
        )
        "at_dropoff" -> PillMeta(
            str(S.desktop_hub_at_dropoff),
            HubGlyphs.Clock,
            rgb(0x5B21B6),
            rgb(0xEDE9FE),
            rgb(0xC4B5FD),
        )

        "completed" -> PillMeta(str(S.completed), HubGlyphs.Check, rgb(0x047857), rgb(0xD1FAE5), rgb(0x6EE7B7))
        "cancelled" -> PillMeta(str(S.cancelled), HubGlyphs.XCircle, rgb(0x525252), rgb(0xF5F5F5), rgb(0xD4D4D4))
        "rejected" -> PillMeta(str(S.rejected), HubGlyphs.Warning, rgb(0xB91C1C), rgb(0xFEE2E2), rgb(0xFCA5A5))
        else -> PillMeta(
            key.replace('_', ' ').ifBlank { str(S.desktop_unknown) },
            null,
            rgb(0x4B5563),
            rgb(0xF3F4F6),
            rgb(0xE5E7EB),
        )
    }
}

internal fun priorityMeta(priority: String): PillMeta? = when (priority.trim().lowercase()) {
    "urgent" -> PillMeta(
        str(S.ah_topup_filter_urgent),
        HubGlyphs.Warning,
        rgb(0xB91C1C),
        rgb(0xFEE2E2),
        rgb(0xFCA5A5),
        bold = true,
    )

    "high" -> PillMeta(
        str(S.desktop_weather_uv_high),
        HubGlyphs.Warning,
        rgb(0xB45309),
        rgb(0xFEF3C7),
        rgb(0xFCD34D),
        bold = true,
    )

    "normal" -> PillMeta(str(S.nda_style_normal), HubGlyphs.SignIn, rgb(0x047857), rgb(0xD1FAE5), rgb(0x6EE7B7))
    "low" -> PillMeta(str(S.desktop_weather_uv_low), HubGlyphs.SignIn, rgb(0x525252), rgb(0xF5F5F5), rgb(0xD4D4D4))
    else -> null
}

/** A small flat tag — antd's borderless `<Tag>` the driver cards use. */
@Composable
internal fun HubTag(text: String, fg: Color, bg: Color, bold: Boolean = false) {
    HubText(
        text,
        11.sp,
        fg,
        weight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/* ── Segmented control ─────────────────────────────────────────────────── */

/** One option of a [HubSegmented]. */
internal data class HubSegment(val id: String, val label: String, val count: Int? = null)

/**
 * antd's `Segmented`, as the hub skins it: a pale track and one chosen item —
 * solid amber in light, a raised chip in dark.
 */
@Composable
internal fun HubSegmented(
    options: List<HubSegment>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = hub
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (p.isDark) p.bgAlt else p.surfaceAlt)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option.id == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            active -> p.segmentActiveBg
                            hovered -> p.surfaceHover
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(option.id) }
                    .padding(horizontal = 11.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HubText(
                    option.label,
                    14.sp,
                    if (active) p.segmentActiveText else p.textSecondary,
                    weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                option.count?.takeIf { it > 0 }?.let { count ->
                    HubText(
                        count.toString(),
                        10.sp,
                        if (active) VIVID else p.textSecondary,
                        weight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (active) Color.White else Color(0xFFE5E7EB))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** An outline pill filter — the Dashboard's Recent Trips chips. */
@Composable
internal fun HubChip(label: String, active: Boolean, onClick: () -> Unit) {
    val p = hub
    HubText(
        label,
        12.5.sp,
        if (active) p.accentDark else p.textSecondary,
        weight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) p.accentBg else Color.Transparent)
            .border(1.dp, if (active) p.accentBorder else p.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/* ── Route block ───────────────────────────────────────────────────────── */

/**
 * Pickup over drop-off, joined by a dashed track — the block every ride and
 * trip card leads with on the web. [large] is the passenger card's version
 * (36dp pin tiles, capital labels in the pin's own colour); the compact one
 * is the driver card's (20dp round pins, muted labels).
 */
@Composable
internal fun HubRouteBlock(
    pickup: HubLocation,
    dropoff: HubLocation,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    emptyText: String = str(S.dm_gpr_not_set),
) {
    val p = hub
    if (!large) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.surfaceAlt)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactRouteEntry(str(S.desktop_hub_pickup_upper), pickup, PIN_GREEN, emptyText)
            CompactRouteEntry(str(S.desktop_hub_dropoff_upper), dropoff, PIN_RED, emptyText)
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.surfaceAlt)
            .border(1.dp, p.border, RoundedCornerShape(12.dp))
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The track: a green dot, a dashed line, a red dot.
        Column(
            modifier = Modifier.width(10.dp).padding(vertical = 6.dp).heightIn(min = 70.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(TRACK_GREEN))
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = 24.dp)
                    .width(2.dp)
                    .padding(vertical = 4.dp)
                    .drawBehind {
                        drawLine(
                            p.border,
                            Offset(size.width / 2, 0f),
                            Offset(size.width / 2, size.height),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                        )
                    },
            )
            Box(Modifier.size(10.dp).clip(CircleShape).background(TRACK_RED))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LargeRouteEntry(str(S.desktop_hub_pickup_upper), pickup, PIN_GREEN, PIN_GREEN_BG, emptyText)
            LargeRouteEntry(str(S.desktop_hub_dropoff_upper), dropoff, PIN_RED, PIN_RED_BG, emptyText)
        }
    }
}

@Composable
private fun LargeRouteEntry(label: String, place: HubLocation, fg: Color, bg: Color, emptyText: String) {
    val p = hub
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HubIconTile(HubGlyphs.MapPin, background = bg, tint = fg, size = 36.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            HubText(label, 11.sp, fg, weight = FontWeight.Bold, spacing = 0.6.sp)
            if (place.address.isBlank()) {
                HubText(emptyText, 14.5.sp, p.textMuted)
            } else {
                HubText(place.address, 14.5.sp, p.textPrimary, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CompactRouteEntry(label: String, place: HubLocation, fg: Color, emptyText: String) {
    val p = hub
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape).background(fg.copy(alpha = 0.18f)),
            Alignment.Center,
        ) { ZillitIcon(HubGlyphs.MapPin, tint = fg, size = 12.dp) }
        Column(Modifier.weight(1f)) {
            HubText(label.uppercase(), 9.sp, p.textMuted, weight = FontWeight.Bold, spacing = 0.5.sp)
            if (place.address.isBlank()) {
                HubText(emptyText, 13.sp, p.textMuted)
            } else {
                HubText(place.address, 13.sp, p.textPrimary, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** "Fri, 19 Jun 2026, 15:51" in the orange outlined chip a card's header wears. */
@Composable
internal fun HubWhenChip(text: String, icon: ImageVector = HubGlyphs.Clock) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(rgb(0xFFF7ED))
            .border(1.dp, rgb(0xFED7AA), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon, tint = rgb(0xC2410C), size = 14.dp)
        HubText(text, 13.sp, rgb(0xC2410C), weight = FontWeight.SemiBold)
    }
}

/** The pale-green strip naming the driver and the car. */
@Composable
internal fun HubDriverStrip(driver: String, vehicle: String) {
    val p = hub
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(TRACK_GREEN.copy(alpha = 0.08f))
            .border(1.dp, TRACK_GREEN.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(HubGlyphs.User, tint = TRACK_GREEN, size = 14.dp)
        HubText(driver, 12.sp, p.textPrimary, weight = FontWeight.SemiBold)
        if (vehicle.isNotBlank()) {
            ZillitIcon(HubGlyphs.Car, tint = p.textMuted, size = 12.dp)
            HubText(vehicle, 11.sp, p.textMuted)
        }
    }
}

/** An icon and a few words, muted — a card's meta row item. */
@Composable
internal fun HubMeta(icon: ImageVector?, text: String, size: TextUnit = 13.sp) {
    val p = hub
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { ZillitIcon(it, tint = p.textMuted, size = 14.dp) }
        HubText(text, size, p.textMuted)
    }
}

/** The rule between a card's body and its footer. */
@Composable
internal fun HubDivider(dashed: Boolean = false) {
    val p = hub
    Box(
        Modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawLine(
                if (dashed) p.borderLight else p.border,
                Offset(0f, 0f),
                Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null,
            )
        },
    )
}

/* ── Filter bar ────────────────────────────────────────────────────────── */

/**
 * The web's `RideFilterBar`: a search with Advanced beside it, the status
 * pills always in view, and — behind Advanced — priority, a date range and
 * the sort. The summary line appears only when a filter is hiding rows.
 */
@Composable
internal fun HubFilterBar(
    filters: RideFilters,
    onChange: (RideFilters) -> Unit,
    statusOptions: List<HubSegment>,
    total: Int,
    shown: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val p = hub
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitSearchField(
                value = filters.query,
                onValueChange = { onChange(filters.copy(query = it)) },
                placeholder = str(S.desktop_hub_ride_search_long),
                modifier = Modifier.weight(1f),
            )
            AdvancedToggle(filters.activeCount, expanded, onToggleExpanded)
            if (filters.activeCount > 0) {
                HubButton(
                    text = str(S.txt_clear),
                    onClick = { onChange(RideFilters()) },
                    style = HubButtonStyle.Link,
                    icon = HubGlyphs.Close,
                    height = 36.dp,
                )
            }
        }
        if (statusOptions.isNotEmpty()) {
            HubSegmented(
                options = statusOptions,
                selected = filters.status,
                onSelect = { onChange(filters.copy(status = it)) },
            )
        }
        if (expanded) AdvancedFilters(filters, onChange)
        if (shown < total) {
            HubText(str(S.desktop_hub_showing_of, shown.toString(), total.toString()), 11.sp, p.textMuted)
        }
    }
}

/** Advanced, with how many filters are on badged at its corner. */
@Composable
private fun AdvancedToggle(active: Int, expanded: Boolean, onToggle: () -> Unit) {
    Box {
        HubButton(
            text = str(S.desktop_hub_advanced),
            onClick = onToggle,
            style = if (expanded) HubButtonStyle.Primary else HubButtonStyle.Outline,
            icon = HubGlyphs.Funnel,
            height = 36.dp,
        )
        if (active > 0) {
            HubText(
                active.toString(),
                10.sp,
                Color.White,
                weight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(VIVID)
                    .padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
private fun AdvancedFilters(filters: RideFilters, onChange: (RideFilters) -> Unit) {
    val p = hub
    HubDivider(dashed = true)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.priority))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RideFilters.PRIORITIES.forEach { (id, label) ->
                    val active = filters.priority == id
                    val tint = when {
                        !active -> null
                        id == "urgent" -> Triple(rgb(0xFEE2E2), rgb(0xB91C1C), rgb(0xFCA5A5))
                        id == "high" -> Triple(rgb(0xFEF3C7), rgb(0xB45309), rgb(0xFCD34D))
                        else -> Triple(p.accent, Color.White, p.accent)
                    }
                    HubText(
                        label(),
                        11.sp,
                        tint?.second ?: p.textSecondary,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tint?.first ?: Color.Transparent)
                            .border(1.dp, tint?.third ?: p.border, CircleShape)
                            .clickable { onChange(filters.copy(priority = id)) }
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.drive_sort_by))
            HubSegmented(
                options = RideFilters.SORTS.map { (id, label) -> HubSegment(id, label()) },
                selected = filters.sort,
                onSelect = { onChange(filters.copy(sort = it)) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.drive_search_date_range))
            // Which date the range is read against, then the range itself —
            // the web's select-plus-picker pair.
            HubSegmented(
                options = RideFilters.DATE_FIELDS.map { (id, label) -> HubSegment(id, label()) },
                selected = filters.dateField,
                onSelect = { onChange(filters.copy(dateField = it)) },
            )
            HubSegmented(
                options = RideFilters.RANGES.map { (id, label) -> HubSegment(id, label()) },
                selected = filters.range,
                onSelect = { onChange(filters.copy(range = it)) },
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    HubText(text.uppercase(), 10.sp, hub.textMuted, weight = FontWeight.Bold, spacing = 0.5.sp)
}

/* ── Misc ──────────────────────────────────────────────────────────────── */

/** A border drawn in dashes — the web's `border: 1px dashed`. */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
    )
}

/** The accent gradient the Film Tools button wears. */
internal val hubAccentGradient: Brush
    @Composable get() = Brush.linearGradient(listOf(hub.accent, hub.accentGradientEnd))

@Composable
internal fun HubSpacer(width: Dp = 0.dp, height: Dp = 0.dp) {
    Spacer(Modifier.width(width).height(height))
}

internal fun rgb(hex: Int): Color = Color(0xFF000000 or hex.toLong())

/** The web's vivid card amber, `#fc9404` — brighter than the theme's accent. */
internal val VIVID = Color(0xFFFC9404)

internal val PIN_GREEN = Color(0xFF059669)
internal val PIN_GREEN_BG = Color(0xFFD1FAE5)
internal val PIN_RED = Color(0xFFDC2626)
internal val PIN_RED_BG = Color(0xFFFEE2E2)
internal val TRACK_GREEN = Color(0xFF10B981)
internal val TRACK_RED = Color(0xFFEF4444)
private val SHADOW = Color(0x0A000000)
