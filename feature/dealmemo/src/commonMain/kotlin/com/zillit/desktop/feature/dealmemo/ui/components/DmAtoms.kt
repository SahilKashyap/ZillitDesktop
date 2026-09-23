package com.zillit.desktop.feature.dealmemo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.rememberAvatar
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

// -- pills and badges -----------------------------------------------------------

/** The `StatusBadge` palette keys (`ui/StatusBadge.jsx`). */
enum class DmTone { Gray, Teal, Amber, Green, Red, Blue, Purple, Pink }

/** Background and ink for a tone: the ink at 15 % behind itself, gray solid. */
@Composable
fun DmTone.colors(): Pair<Color, Color> {
    val dark = ZillitTheme.colors.isDark
    fun tint(ink: Int, text: Int, darkText: Int = text): Pair<Color, Color> =
        Color(ink).copy(alpha = if (dark) 0.18f else 0.15f) to Color(if (dark) darkText else text)
    return when (this) {
        DmTone.Gray -> if (dark) {
            Color.White.copy(alpha = 0.08f) to Color(0xFF9CA3AF)
        } else {
            Color(0xFFF3F4F6) to Color(0xFF6B7280)
        }
        DmTone.Teal -> tint(0xFF14B8A6.toInt(), 0xFF0D9488.toInt(), 0xFF2DD4BF.toInt())
        DmTone.Amber -> tint(0xFFFC9404.toInt(), 0xFFE08600.toInt(), 0xFFFBBF24.toInt())
        DmTone.Green -> tint(0xFF22C55E.toInt(), 0xFF16A34A.toInt(), 0xFF4ADE80.toInt())
        DmTone.Red -> tint(0xFFEF4444.toInt(), 0xFFDC2626.toInt(), 0xFFF87171.toInt())
        DmTone.Blue -> tint(0xFF3B82F6.toInt(), 0xFF2563EB.toInt(), 0xFF60A5FA.toInt())
        DmTone.Purple -> tint(0xFFA855F7.toInt(), 0xFF9333EA.toInt(), 0xFFC084FC.toInt())
        DmTone.Pink -> tint(0xFFE84B7A.toInt(), 0xFFE84B7A.toInt())
    }
}

/** The web's status colours (`dealStatus.js:18-36`). */
val DealStatus.tone: DmTone
    get() = when (this) {
        DealStatus.Draft -> DmTone.Gray
        DealStatus.Issued -> DmTone.Teal
        DealStatus.AwaitingApproval -> DmTone.Amber
        DealStatus.Approved, DealStatus.Active -> DmTone.Green
        DealStatus.Completed -> DmTone.Blue
        DealStatus.Rejected, DealStatus.Cancelled, DealStatus.Deactivated -> DmTone.Red
    }

/** `StatusBadge`: DM Mono 12 bold on the tone's tint, 6 px corners. */
@Composable
fun DmBadge(text: String, tone: DmTone, modifier: Modifier = Modifier) {
    val (background, ink) = tone.colors()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ZillitText(text = text, style = DmType.mono(12.sp, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

@Composable
fun DmStatusBadge(status: DealStatus, modifier: Modifier = Modifier) = DmBadge(status.label, status.tone, modifier)

/** The red unread count before a reference — `99+` past ninety-nine. */
@Composable
fun DmUnreadPill(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    ZillitTooltip(
        text = if (count == 1) str(S.desktop_dm_one_unread_notification) else str(
            S.desktop_dm_n_unread_notifications,
            count,
        ),
    ) {
        Box(
            modifier = modifier
                .defaultMinSize(minWidth = 16.dp)
                .height(16.dp)
                .clip(CircleShape)
                .background(dm.unread)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = if (count > MAX_BADGE) "99+" else count.toString(),
                style = DmType.sans(10.sp, FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** The mono uppercase label: table headings, eyebrows, `QUICK FILTERS:`. */
@Composable
fun DmEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = dm.ink3,
    size: Float = 10.5f,
    tracking: Float = 0.1f,
) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.mono(size.sp, FontWeight.Bold, tracking.em),
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A 3 px separator dot for meta rows. */
@Composable
fun DmDot(modifier: Modifier = Modifier, color: Color = dm.separatorDot, size: Dp = 3.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

// -- surfaces -------------------------------------------------------------------

/** The web card: white, a warm hairline, 16 px corners, a whisper of shadow. */
@Composable
fun DmCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .shadow(elevation = 0.5.dp, shape = shape, clip = false, ambientColor = SHADOW, spotColor = SHADOW)
            .clip(shape)
            .background(dm.card)
            .border(1.dp, dm.cardBorder, shape)
            .padding(padding),
        content = content,
    )
}

/** Whether the pointer is over this element — for the web's hover borders and fills. */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

// -- buttons --------------------------------------------------------------------

/** The web module's button faces, each a size and a colour pair. */
enum class DmButtonStyle {
    /** Create Deal Memo, Deal Memo Setup — amber, 44 tall. */
    Cta,

    /** Export — white with a hairline, 44 tall. */
    Ghost,

    /** Global Production Rates, Notice Template — white, 36 tall. */
    GhostSmall,

    /** Approve & Sign. */
    Approve,

    /** Send (notices). */
    Notice,

    /** Deactivate. */
    Danger,

    /** Send all — the group header's small orange. */
    SendAll,

    /** The shared `Button sm` in primary amber — Chase. */
    SmallPrimary,

    /** The shared `Button sm` in green — Activate. */
    SmallGreen,

    /** The shared `Button sm` secondary — Retry, Refresh data. */
    SmallSecondary,

    /** A modal footer's neutral button. */
    ModalNeutral,

    /** A modal footer's red-text button. */
    ModalDangerText,

    /** A modal footer's amber confirm. */
    ModalPrimary,

    /** A modal footer's red confirm. */
    ModalDanger,
}

private data class ButtonFace(
    val height: Dp,
    val horizontal: Dp,
    val radius: Dp,
    val textSize: Float,
    val weight: FontWeight,
    val iconSize: Dp,
)

@Composable
private fun DmButtonStyle.face(): ButtonFace = when (this) {
    DmButtonStyle.Cta, DmButtonStyle.Ghost -> ButtonFace(44.dp, 16.dp, 10.dp, 13f, FontWeight.Bold, 12.dp)
    DmButtonStyle.GhostSmall -> ButtonFace(36.dp, 14.dp, 10.dp, 13f, FontWeight.Bold, 12.dp)
    DmButtonStyle.Approve -> ButtonFace(40.dp, 18.dp, 10.dp, 13.5f, FontWeight.Bold, 13.dp)
    DmButtonStyle.Notice, DmButtonStyle.Danger -> ButtonFace(32.dp, 14.dp, 8.dp, 12f, FontWeight.Bold, 12.dp)
    DmButtonStyle.SendAll -> ButtonFace(28.dp, 12.dp, 8.dp, 11.5f, FontWeight.Bold, 11.dp)
    DmButtonStyle.SmallPrimary, DmButtonStyle.SmallGreen, DmButtonStyle.SmallSecondary ->
        ButtonFace(30.dp, 12.dp, 8.dp, 12f, FontWeight.SemiBold, 11.dp)
    DmButtonStyle.ModalNeutral, DmButtonStyle.ModalDangerText, DmButtonStyle.ModalPrimary, DmButtonStyle.ModalDanger ->
        ButtonFace(34.dp, 16.dp, 8.dp, 12f, FontWeight.SemiBold, 12.dp)
}

/** Background, border and ink for a style, hovered or not. */
@Suppress("CyclomaticComplexMethod")
@Composable
private fun DmButtonStyle.colors(hovered: Boolean): Triple<Color, Color?, Color> {
    val p = dm
    return when (this) {
        DmButtonStyle.Cta -> Triple(if (hovered) p.accentHover else Color(0xFFE8861A), null, Color.White)
        DmButtonStyle.Ghost, DmButtonStyle.GhostSmall ->
            Triple(
                if (hovered) p.controlHoverBg else p.control,
                if (hovered) p.controlHoverBorder else p.controlBorder,
                p.ink2,
            )
        DmButtonStyle.Approve -> Triple(if (hovered) p.greenHover else p.green, null, Color.White)
        DmButtonStyle.Notice, DmButtonStyle.SendAll ->
            Triple(if (hovered) p.noticeOrangeHover else p.noticeOrange, null, Color.White)
        DmButtonStyle.Danger -> Triple(if (hovered) p.redHover else p.red, null, Color.White)
        DmButtonStyle.SmallPrimary -> Triple(if (hovered) Color(0xFFFDB034) else p.brand, null, Color.White)
        DmButtonStyle.SmallGreen -> Triple(if (hovered) Color(0xFF4ADE80) else Color(0xFF22C55E), null, Color.White)
        DmButtonStyle.SmallSecondary, DmButtonStyle.ModalNeutral ->
            Triple(if (hovered) p.controlHoverBg else p.control, p.hairline, p.ink2)
        DmButtonStyle.ModalDangerText -> Triple(if (hovered) p.redSoft else p.control, p.hairline, Color(0xFFDC2626))
        DmButtonStyle.ModalPrimary -> Triple(if (hovered) Color(0xFFFDB034) else p.brand, null, Color(0xFF0E0F12))
        DmButtonStyle.ModalDanger -> Triple(if (hovered) Color(0xFFDC2626) else Color(0xFFEF4444), null, Color.White)
    }
}

/**
 * A web deal-memo button. Disabled buttons fade rather than grey out, as the
 * web's `disabled:opacity-40` does; a loading button shows its spinner in
 * place of the icon.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
fun DmButton(
    text: String,
    onClick: () -> Unit,
    style: DmButtonStyle,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    tooltip: String? = null,
) {
    val face = style.face()
    val (source, hovered) = rememberHover()
    val active = enabled && !loading
    val (background, border, ink) = style.colors(hovered && active)
    val shape = RoundedCornerShape(face.radius)
    val shadow = when (style) {
        DmButtonStyle.Cta -> Color(0xFFE8861A)
        DmButtonStyle.Approve -> dm.green
        else -> null
    }
    val button: @Composable () -> Unit = {
        Row(
            modifier = modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .then(
                    if (shadow != null && enabled) {
                        Modifier.shadow(
                            4.dp,
                            shape,
                            clip = false,
                            ambientColor = shadow.copy(alpha = 0.3f),
                            spotColor = shadow.copy(alpha = 0.3f),
                        )
                    } else {
                        Modifier
                    },
                )
                .height(face.height)
                .clip(shape)
                .background(background)
                .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
                .hoverable(source)
                .then(
                    if (active) {
                        Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .pointerHoverIcon(if (active) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = face.horizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            when {
                loading -> ZillitSpinner(size = face.iconSize, color = ink)
                icon != null -> ZillitIcon(icon, size = face.iconSize, tint = ink)
            }
            ZillitText(text = text, style = DmType.sans(face.textSize.sp, face.weight), color = ink, maxLines = 1)
        }
    }
    if (tooltip != null) ZillitTooltip(text = tooltip) { button() } else button()
}

/** The table's 28 px round icon button — History in amber, Delete in red on hover. */
@Composable
fun DmRoundIcon(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    size: Dp = 28.dp,
    radius: Dp = size / 2,
    iconSize: Dp = 13.dp,
) {
    val (source, hovered) = rememberHover()
    val p = dm
    val background = when {
        !hovered -> Color.Transparent
        danger -> p.redSoft
        else -> p.amberSoft
    }
    val tint = when {
        !hovered -> p.ink3
        danger -> p.redInk
        else -> p.accent
    }
    ZillitTooltip(text = tooltip) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(radius))
                .background(background)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon, size = iconSize, tint = tint)
        }
    }
}

/** A quick-filter pill: amber when on, a white hairline pill when off. */
@Composable
fun DmFilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (source, hovered) = rememberHover()
    val p = dm
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color(0xFFE8861A) else p.control)
            .border(
                1.dp,
                if (selected) Color(0xFFE8861A) else if (hovered) p.controlHoverBorder else p.controlBorder,
                shape,
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        ZillitText(
            text = label,
            style = DmType.sans(12.5.sp, FontWeight.Bold),
            color = if (selected) Color.White else p.ink2,
            maxLines = 1,
        )
    }
}

/**
 * The web's search pill: 44 tall, magnifier inset, an ✕ once there is text,
 * and the amber border while focused.
 */
@Composable
fun DmSearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    /** The sidebar's tighter pill: 13 px text, less inset. */
    compact: Boolean = false,
) {
    val p = dm
    val shape = RoundedCornerShape(10.dp)
    val textSize = if (compact) 13.sp else 13.5.sp
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(p.control)
            .border(1.dp, if (focused) p.accent else p.controlBorder, shape)
            .padding(horizontal = if (compact) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        ZillitIcon(ZillitIcons.Search, size = if (compact) 13.dp else 16.dp, tint = p.ink3)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                ZillitText(text = placeholder, style = DmType.sans(textSize), color = p.ink3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = DmType.sans(textSize).copy(color = p.ink),
                cursorBrush = SolidColor(p.accent),
                interactionSource = focus,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            DmRoundIcon(
                ZillitIcons.Close,
                tooltip = str(S.ah_cd_clear_search),
                onClick = { onValueChange("") },
                size = 20.dp,
                iconSize = 12.dp,
            )
        }
    }
}

// -- people ---------------------------------------------------------------------

/** Loads a person's profile photo by user id — the host's avatar seam. */
val LocalDealFaces: ProvidableCompositionLocal<suspend (String) -> ImageBitmap?> =
    staticCompositionLocalOf { { _: String -> null } }

@Composable
fun ProvideDealFaces(load: suspend (String) -> ImageBitmap?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDealFaces provides load, content = content)
}

private val AVATAR_TONES = listOf(
    Triple(Color(0xFFF1EBFF), Color(0xFF7A4CD6), Color(0xFFDBCDF6)),
    Triple(Color(0xFFD9F4F0), Color(0xFF14A394), Color(0xFFA8E3D9)),
    Triple(Color(0xFFFDF2E2), Color(0xFFE8861A), Color(0xFFF6D8A8)),
    Triple(Color(0xFFE9EFFF), Color(0xFF2862E0), Color(0xFFCBD6F3)),
    Triple(Color(0xFFFDE7E7), Color(0xFFB22A2A), Color(0xFFF4CCCC)),
    Triple(Color(0xFFE6F7EE), Color(0xFF0C6A3F), Color(0xFFC2E6D3)),
)

/**
 * `PersonAvatar`: the photo when one loads, else bold initials on a tone
 * picked by hashing the name — so one person wears one colour everywhere.
 */
@Composable
fun DmPersonAvatar(
    name: String,
    userId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    initialsSize: Float? = null,
) {
    val loader = LocalDealFaces.current
    val photo by produceState<ImageBitmap?>(null, userId) {
        value = userId?.takeIf { it.isNotBlank() }?.let { runCatching { loader(it) }.getOrNull() }
    }
    // The app-wide loader stands in when the tool's own is not in scope.
    val shared = rememberAvatar(userId)
    val tone = AVATAR_TONES[(avatarHash(name).absoluteValue) % AVATAR_TONES.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.first)
            .border(1.dp, tone.third, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val image = photo ?: shared
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            val text = initialsSize ?: max(MIN_INITIALS, (size.value * INITIALS_RATIO).roundToInt().toFloat())
            ZillitText(
                text = initials(name),
                style = DmType.sans(text.sp, FontWeight.Bold),
                color = tone.second,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** First letters of the first two words, ignoring anything in brackets; a dash for nothing. */
fun initials(name: String): String {
    val words = name.replace(Regex("\\(.*?\\)"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "—" }
}

/** `h = (h * 31 + charCode) | 0` — the web's 32-bit string hash. */
private fun avatarHash(name: String): Int = name.fold(0) { h, c -> h * HASH_MULTIPLIER + c.code }

// -- layout helpers ---------------------------------------------------------------

/** A clickable surface that reveals its hover fill. */
@Composable
fun DmHoverRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hoverColor: Color = dm.rowHover,
    content: @Composable RowScope.() -> Unit,
) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = modifier
            .background(if (hovered && onClick != null) hoverColor else Color.Transparent)
            .hoverable(source)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A soft square tile holding an icon — the stat cards' and panels' chips. */
@Composable
fun DmIconTile(
    icon: ImageVector,
    background: Color,
    ring: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    radius: Dp = 8.dp,
    iconSize: Dp = 14.dp,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier.size(size).clip(shape).background(background).border(1.dp, ring, shape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, size = iconSize, tint = tint)
    }
}

/** A diagonal-gradient badge square — the export and create menus' file-type marks. */
@Composable
fun DmGradientBadge(text: String, from: Color, to: Color, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    Box(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(10.dp))
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(from, to))),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(text = text, style = DmType.mono(9.5.sp, FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

/** Centres [content] in a box — for spinners and small empty states. */
@Composable
fun DmCentered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center, content = content)
}

/** A 1 px hairline border around a shape, reused by chips. */
fun borderOf(color: Color): BorderStroke = BorderStroke(1.dp, color)

private val SHADOW = Color(0x0A0F1115)
private const val DISABLED_ALPHA = 0.45f
private const val HASH_MULTIPLIER = 31
private const val MAX_BADGE = 99
private const val MIN_INITIALS = 9f
private const val INITIALS_RATIO = 0.4f
