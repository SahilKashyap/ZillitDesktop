package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

/**
 * The deal page's own tokens (`DMDealPreviewPage.jsx` §8.2, dark from §8.7):
 * the memo card is a document — gray-scale Tailwind inks on white — while the
 * chrome around it keeps the module's warm hairlines.
 */
@Immutable
data class PreviewPalette(
    val page: Color,
    val card: Color,
    val cardBorder: Color,
    val divider: Color,
    val ink: Color,
    val body: Color,
    val muted: Color,
    val faint: Color,
    val label: Color,
    val sectionTitle: Color,
    val mastheadRule: Color,
    val tableHead: Color,
    val chipBorder: Color,
    val barBorder: Color,
    val innerDivider: Color,
    val bar: Color,
    val amberBg: Color,
    val amberBorder: Color,
    val amberInk: Color,
    val amberIcon: Color,
    val shareBg: Color,
    val shareInk: Color,
    val doneBorder: Color,
    val doneBg: Color,
    val doneInk: Color,
    val doneHover: Color,
    val neutralHoverBg: Color,
    val menuText: Color,
    val hoverWash: Color,
    val blueBg: Color,
    val blueBorder: Color,
    val blueInk: Color,
    val teal: Color,
    val redTagBg: Color,
    val redTagInk: Color,
    val redTagBorder: Color,
    val navy: Color,
    val rowBg: Color,
) {
    companion object {
        val Light = PreviewPalette(
            page = Color(0xFFF8F9FB),
            card = Color.White,
            cardBorder = Color(0xFFE5E7EB),
            divider = Color(0xFFF3F4F6),
            ink = Color(0xFF111827),
            body = Color(0xFF374151),
            muted = Color(0xFF6B7280),
            faint = Color(0xFF9CA3AF),
            label = Color(0xFF6B7280),
            sectionTitle = Color(0xFFE08600),
            mastheadRule = Color(0xFFE08600),
            tableHead = Color(0xFFF9FAFB),
            chipBorder = Color(0xFFECECEA),
            barBorder = Color(0xFFE2E4E9),
            innerDivider = Color(0xFFEEF0F4),
            bar = Color.White,
            amberBg = Color(0xFFFDF2E2),
            amberBorder = Color(0xFFF6D8A8),
            amberInk = Color(0xFF8A5A00),
            amberIcon = Color(0xFFE8861A),
            shareBg = Color(0xFFFDF8EF),
            shareInk = Color(0xFF8A6A36),
            doneBorder = Color(0xFFBFE3C9),
            doneBg = Color(0xFFF2FAF5),
            doneInk = Color(0xFF1F7A3D),
            doneHover = Color(0xFFEAF7EE),
            neutralHoverBg = Color(0xFFFAFAF6),
            menuText = Color(0xFF1A1E2C),
            hoverWash = Color(0xFFF8F9FB),
            blueBg = Color(0xFFEFF6FF),
            blueBorder = Color(0xFFBFDBFE),
            blueInk = Color(0xFF1D4ED8),
            teal = Color(0xFF0D9488),
            redTagBg = Color(0xFFFEF2F2),
            redTagInk = Color(0xFFDC2626),
            redTagBorder = Color(0xFFFECACA),
            navy = Color(0xFF1D4186),
            rowBg = Color(0xFFF9FAFB),
        )

        val Dark = PreviewPalette(
            page = Color(0xFF07090C),
            card = Color(0xFF141720),
            cardBorder = Color(0xFF1E2535),
            divider = Color(0xFF1F2436),
            ink = Color(0xFFE8EAF0),
            body = Color(0xFFC9CFDB),
            muted = Color(0xFF8B95A8),
            faint = Color(0xFF5A6478),
            label = Color(0xFF93A0B5),
            sectionTitle = Color(0xFFE8B84B),
            mastheadRule = Color(0xFFA07E2E),
            tableHead = Color(0xFF1C2030),
            chipBorder = Color.White.copy(alpha = 0.08f),
            barBorder = Color.White.copy(alpha = 0.08f),
            innerDivider = Color.White.copy(alpha = 0.06f),
            bar = Color(0xFF1A1D24),
            amberBg = Color(0xFFF59E0B).copy(alpha = 0.12f),
            amberBorder = Color(0xFFF59E0B).copy(alpha = 0.30f),
            amberInk = Color(0xFFFDE68A),
            amberIcon = Color(0xFFFBBF24),
            shareBg = Color(0xFFF59E0B).copy(alpha = 0.08f),
            shareInk = Color(0xFFE8C98A),
            doneBorder = Color(0xFF10B981).copy(alpha = 0.30f),
            doneBg = Color(0xFF10B981).copy(alpha = 0.08f),
            doneInk = Color(0xFF6EE7B7),
            doneHover = Color(0xFF10B981).copy(alpha = 0.14f),
            neutralHoverBg = Color.White.copy(alpha = 0.04f),
            menuText = Color(0xFFE8EAF0),
            hoverWash = Color.White.copy(alpha = 0.05f),
            blueBg = Color(0xFF60A5FA).copy(alpha = 0.10f),
            blueBorder = Color(0xFF60A5FA).copy(alpha = 0.15f),
            blueInk = Color(0xFF60A5FA),
            teal = Color(0xFF2DD4BF),
            redTagBg = Color(0xFFF87171).copy(alpha = 0.10f),
            redTagInk = Color(0xFFF87171),
            redTagBorder = Color(0xFFF87171).copy(alpha = 0.15f),
            navy = Color(0xFF1D4186),
            rowBg = Color(0xFF161A25),
        )
    }
}

val pv: PreviewPalette
    @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) PreviewPalette.Dark else PreviewPalette.Light

/** The page's two ambers: the primary action and the brand. */
internal object PreviewInk {
    val Action = Color(0xFFE8861A)
    val ActionHover = Color(0xFFD6770F)
    val Brand = Color(0xFFFC9404)
    val BrandHover = Color(0xFFFDB034)
    val Todo = Color(0xFFE08600)
    val TodoHover = Color(0xFFC97A00)
    val Green = Color(0xFF22C55E)
    val GreenInk = Color(0xFF15803D)
    val GreenRole = Color(0xFF16A34A)
    val GreenLine = Color(0xFF4ADE80)
    val DoneDot = Color(0xFF1AA463)
    val ObserverDot = Color(0xFFC8CCD4)
    val RejectInk = Color(0xFFC0392B)
    val RejectBorder = Color(0xFFF0CDC9)
    val RejectHover = Color(0xFFFDF6F5)
    val Red = Color(0xFFEF4444)
    val RedHover = Color(0xFFDC2626)
    val Download = Color(0xFFE08600)
    val DownloadHover = Color(0xFFB8872A)
}

/** `MS`: a titled section of the memo — a top hairline, the Syne amber title, then its body. */
@Composable
internal fun MemoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pv.divider))
        Spacer(Modifier.height(14.dp))
        ZillitText(
            text = title.uppercase(),
            style = DmType.display(10.sp, FontWeight.Bold, 0.05.em),
            color = pv.sectionTitle,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** `MF`'s label: Syne 10, bold, upper-case, gray. */
@Composable
internal fun MemoLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.display(10.sp, FontWeight.Bold, 0.05.em),
        color = pv.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A two-column grid of cells; a wide cell takes its own row. */
@Composable
internal fun <T> TwoColumnGrid(
    items: List<T>,
    isWide: (T) -> Boolean,
    columnGap: Dp = 24.dp,
    rowGap: Dp = 12.dp,
    cell: @Composable (T) -> Unit,
) {
    val rows = mutableListOf<List<T>>()
    var pending: T? = null
    items.forEach { item ->
        when {
            isWide(item) -> {
                pending?.let { rows += listOf(it) }
                pending = null
                rows += listOf(item)
            }
            pending == null -> pending = item
            else -> {
                rows += listOf(pending as T, item)
                pending = null
            }
        }
    }
    pending?.let { rows += listOf(it) }
    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        rows.forEach { row ->
            if (row.size == 1 && isWide(row[0])) {
                Box(Modifier.fillMaxWidth()) { cell(row[0]) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                    row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The amber banner: an info mark, a bold title, a body, an optional action. */
@Composable
internal fun AmberBanner(
    title: String?,
    body: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.amberBg)
            .border(1.dp, pv.amberBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 10.dp),
        verticalAlignment = if (trailing != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        ZillitIcon(
            ZillitIcons.Info,
            size = if (large) 16.dp else 14.dp,
            tint = pv.amberIcon,
            modifier = Modifier.padding(top = if (trailing != null) 0.dp else 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            title?.let {
                ZillitText(
                    text = it,
                    style = DmType.sans(if (large) 15.sp else 13.5.sp, FontWeight.Bold),
                    color = pv.amberInk,
                )
                Spacer(Modifier.height(2.dp))
            }
            ZillitText(
                text = body,
                style = DmType.sans(if (large) 13.5.sp else 12.5.sp).copy(lineHeight = if (large) 20.sp else 19.sp),
                color = pv.amberInk,
            )
            below?.invoke(this)
        }
        trailing?.invoke(this)
    }
}

/** A solid page button: amber by default, 34 tall, 8 px corners. */
@Composable
internal fun SolidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = PreviewInk.Action,
    hover: Color = PreviewInk.ActionHover,
    ink: Color = Color.White,
    height: Dp = 34.dp,
    horizontal: Dp = 14.dp,
    radius: Dp = 8.dp,
    textSize: Float = 13f,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val (source, hovered) = rememberHover()
    val active = enabled && !loading
    val shape = RoundedCornerShape(radius)
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED)
            .height(height)
            .clip(shape)
            .background(if (hovered && active) hover else color)
            .hoverable(source)
            .then(
                if (active) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (active) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        when {
            loading -> ZillitSpinner(size = 12.dp, color = ink)
            icon != null -> ZillitIcon(icon, size = 12.dp, tint = ink)
        }
        ZillitText(text = text, style = DmType.sans(textSize.sp, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/** A bordered page button: white with a hairline. */
@Composable
internal fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    ink: Color = Color.Unspecified,
    border: Color = Color.Unspecified,
    hoverBg: Color = Color.Unspecified,
    height: Dp = 34.dp,
    horizontal: Dp = 14.dp,
    radius: Dp = 8.dp,
    textSize: Float = 13f,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(radius)
    val inkColor = if (ink == Color.Unspecified) pv.menuText else ink
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED)
            .height(height)
            .clip(shape)
            .background(
                if (hovered && enabled) (if (hoverBg == Color.Unspecified) pv.divider else hoverBg) else pv.card,
            )
            .border(1.dp, if (border == Color.Unspecified) pv.barBorder else border, shape)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { ZillitIcon(it, size = 12.dp, tint = inkColor) }
        ZillitText(text = text, style = DmType.sans(textSize.sp, FontWeight.Bold), color = inkColor, maxLines = 1)
        trailingIcon?.let { ZillitIcon(it, size = 11.dp, tint = pv.faint) }
    }
}

/** A menu row: an icon tile, a bold label, a quiet line under it. */
@Composable
internal fun MenuRow(icon: ImageVector, label: String, sub: String, onClick: () -> Unit, tileSize: Dp = 30.dp) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) pv.hoverWash else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(tileSize).clip(RoundedCornerShape(8.dp)).background(pv.divider),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon, size = 14.dp, tint = pv.muted)
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = DmType.sans(13.5.sp, FontWeight.Bold), color = pv.menuText, maxLines = 1)
            ZillitText(text = sub, style = DmType.sans(11.5.sp), color = pv.faint, maxLines = 1)
        }
    }
}

/** A wrapper for a tooltip that can be absent. */
@Composable
internal fun MaybeTooltip(text: String?, content: @Composable () -> Unit) {
    if (text.isNullOrEmpty()) content() else ZillitTooltip(text = text) { content() }
}

/** A pulsing ring behind the chips that still wait for this signer. */
internal fun Modifier.signGlow(enabled: Boolean, color: Color, radius: Dp, spread: Float): Modifier =
    if (!enabled) this else drawBehind {
        val ring = spread * density
        drawRoundRect(
            color = color,
            topLeft = Offset(-ring, -ring),
            size = Size(size.width + ring * 2, size.height + ring * 2),
            cornerRadius = CornerRadius((radius.toPx() + ring)),
        )
    }

/** The ring's breathing, 0 → 9 px and back, over 1.9 s. */
@Composable
internal fun rememberGlowSpread(): Float {
    val transition = rememberInfiniteTransition(label = "sign-glow")
    val spread by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(GLOW_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sign-glow-spread",
    )
    return spread
}

/** A small round dot. */
@Composable
internal fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A vertical hairline. */
@Composable
internal fun VerticalRule(color: Color, height: Dp) {
    Box(Modifier.width(1.dp).height(height).background(color))
}

/** Right-aligned above its anchor, [gap] apart — the crew bar's More menu. */
internal class AboveEndPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceAtLeast(EDGE),
        (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(EDGE),
    )
}

/**
 * Right-aligned below its anchor, flipping above when the room below is short
 * and above is longer — the header's Edit menu.
 */
internal class BelowEndPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(EDGE, (windowSize.width - popupContentSize.width - EDGE).coerceAtLeast(EDGE))
        val below = windowSize.height - anchorBounds.bottom
        val above = anchorBounds.top
        val flip = below < popupContentSize.height + gap && above > below
        val y = if (flip) anchorBounds.top - popupContentSize.height - gap else anchorBounds.bottom + gap
        return IntOffset(x, y.coerceAtLeast(EDGE))
    }
}

private const val DISABLED = 0.45f
private const val GLOW_MS = 1900
private const val EDGE = 8
