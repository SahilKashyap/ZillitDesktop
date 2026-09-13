package com.zillit.desktop.feature.dealmemo.ui.pages.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleList
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed

/** The rules grid's tokens (`BulkRulesEditor.jsx` `BRE_CSS`), light and dark. */
@Immutable
internal data class RulesPalette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceHover: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val cta: Color,
    val ctaHover: Color,
    val red: Color,
    val chipBg: Color,
    val chipBorder: Color,
) {
    companion object {
        val Light = RulesPalette(
            bg = Color(0xFFF0EEE9),
            surface = Color.White,
            surfaceAlt = Color(0xFFF7F7F6),
            surfaceHover = Color(0xFFF5F5F4),
            ink = Color(0xFF0A0C10),
            ink2 = Color(0xFF0A0C10).copy(alpha = 0.62f),
            ink3 = Color(0xFF0A0C10).copy(alpha = 0.42f),
            ink4 = Color(0xFF0A0C10).copy(alpha = 0.30f),
            border = Color(0xFF0A0C10).copy(alpha = 0.10f),
            borderStrong = Color(0xFF0A0C10).copy(alpha = 0.22f),
            divider = Color(0xFF0A0C10).copy(alpha = 0.07f),
            cta = Color(0xFFEA7A0E),
            ctaHover = Color(0xFFD96B00),
            red = Color(0xFFDC2626),
            chipBg = Color(0xFF0A0C10).copy(alpha = 0.045f),
            chipBorder = Color(0xFF0A0C10).copy(alpha = 0.08f),
        )
        val Dark = RulesPalette(
            bg = Color(0xFF0B0D11),
            surface = Color(0xFF14171C),
            surfaceAlt = Color(0xFF1A1D24),
            surfaceHover = Color(0xFF1F232C),
            ink = Color.White,
            ink2 = Color.White.copy(alpha = 0.70f),
            ink3 = Color.White.copy(alpha = 0.42f),
            ink4 = Color.White.copy(alpha = 0.28f),
            border = Color.White.copy(alpha = 0.10f),
            borderStrong = Color.White.copy(alpha = 0.24f),
            divider = Color.White.copy(alpha = 0.07f),
            cta = Color(0xFFFBBF24),
            ctaHover = Color(0xFFFCD34D),
            red = Color(0xFFDC2626),
            chipBg = Color.White.copy(alpha = 0.06f),
            chipBorder = Color.White.copy(alpha = 0.10f),
        )
    }
}

internal val rp: RulesPalette
    @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) RulesPalette.Dark else RulesPalette.Light

/** A category's colours: overtime amber, premium blue, penalty red; grey before a type is picked. */
internal data class CategoryTone(val fg: Color, val bg: Color, val rail: Color)

@Suppress("MagicNumber") // The web's category swatches, verbatim.
internal fun toneOf(list: RuleList?): CategoryTone = when (list) {
    RuleList.Overtimes -> CategoryTone(Color(0xFFEA7A0E), Color(0xFFFFF4EA), Color(0xFFEA7A0E))
    RuleList.Premiums -> CategoryTone(Color(0xFF2563EB), Color(0xFFEAF1FE), Color(0xFF2563EB))
    RuleList.Penalties, RuleList.Turnarounds -> CategoryTone(Color(0xFFDC2626), Color(0xFFFEF0EF), Color(0xFFDC2626))
    null ->
        CategoryTone(Color(0xFF8A8D95), Color(0xFF0A0C10).copy(alpha = 0.05f), Color(0xFF0A0C10).copy(alpha = 0.16f))
}

/** One option of a grid select; [group] starts an optgroup. */
internal data class GridOption(
    val value: String,
    val label: String,
    val group: String? = null,
    val disabled: Boolean = false,
)

/** A seamless spreadsheet select: the value and a chevron, a list dropping under it. */
@Composable
internal fun GridSelect(
    value: String,
    options: List<GridOption>,
    onPick: (String) -> Unit,
    placeholder: String? = null,
    mono: Boolean = false,
    required: Boolean = false,
    menuWidth: Dp = 240.dp,
) {
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val label = options.firstOrNull { it.value == value && !it.disabled }?.label
    val shape = RoundedCornerShape(5.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(shape)
                .background(if (hovered || open) rp.surfaceHover else Color.Transparent)
                .then(if (required) Modifier.border(2.dp, rp.red, shape) else Modifier)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 10.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = label ?: placeholder.orEmpty()
            val weight = if (label == null) FontWeight.Medium else FontWeight.SemiBold
            ZillitText(
                text = shown,
                style = if (mono) DmType.mono(12.5.sp, weight) else DmType.sans(12.5.sp, weight),
                color = if (label == null) rp.ink3 else rp.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(ZillitIcons.ChevronDown, size = 11.dp, tint = rp.ink3)
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 2) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                OptionList(options, value, menuWidth) { picked ->
                    open = false
                    onPick(picked)
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun OptionList(options: List<GridOption>, selected: String, width: Dp, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .widthIn(min = width)
            .heightIn(max = 320.dp)
            .shadowed(shape)
            .clip(shape)
            .background(rp.surface)
            .border(1.dp, rp.border, shape)
            .verticalScroll(rememberScrollState())
            .padding(5.dp),
    ) {
        var lastGroup: String? = null
        options.forEach { option ->
            if (option.group != null && option.group != lastGroup) {
                lastGroup = option.group
                ZillitText(
                    text = option.group,
                    style = DmType.sans(10.5.sp, FontWeight.Bold),
                    color = rp.ink3,
                    modifier = Modifier.padding(start = 9.dp, top = 8.dp, bottom = 3.dp),
                )
            }
            val (source, hovered) = rememberHover()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (hovered && !option.disabled) rp.surfaceAlt else Color.Transparent)
                    .hoverable(source)
                    .then(
                        if (option.disabled) Modifier else Modifier.clickable(
                            interactionSource = source,
                            indication = null,
                        ) {
                            onPick(option.value)
                        },
                    )
                    .pointerHoverIcon(if (option.disabled) PointerIcon.Default else PointerIcon.Hand)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = option.label,
                    style = DmType.sans(
                        12.5.sp,
                        if (option.value == selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = when {
                        option.disabled -> rp.ink3
                        option.value == selected -> rp.cta
                        else -> rp.ink
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (option.value == selected && !option.disabled) {
                    ZillitIcon(ZillitIcons.Check, size = 11.dp, tint = rp.cta)
                }
            }
        }
    }
}

/** A seamless text input: transparent until hovered, an amber ring while focused, red when required and empty. */
@Composable
internal fun GridInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    required: Boolean = false,
    prefix: String? = null,
    suffix: String? = null,
    filter: (String) -> Boolean = { true },
) {
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(5.dp)
    val style = if (mono) DmType.mono(12.5.sp, FontWeight.Medium) else DmType.sans(12.5.sp, FontWeight.Medium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .background(
                when {
                    focused -> rp.surface
                    hovered -> rp.surfaceHover
                    else -> Color.Transparent
                },
            )
            .then(
                when {
                    focused -> Modifier.border(2.dp, rp.cta, shape)
                    required -> Modifier.border(2.dp, rp.red, shape)
                    else -> Modifier
                },
            )
            .hoverable(source)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        prefix?.let { ZillitText(text = it, style = DmType.mono(11.sp), color = rp.ink3) }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) ZillitText(text = placeholder, style = style, color = rp.ink3, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { if (filter(it)) onValueChange(it) },
                singleLine = true,
                interactionSource = focus,
                textStyle = style.copy(color = rp.ink),
                cursorBrush = SolidColor(rp.cta),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        suffix?.let { ZillitText(text = it, style = DmType.mono(11.sp), color = rp.ink3) }
    }
}

/** The grid's 28 px icon button — grey, red on hover when it deletes. */
@Composable
internal fun GridIcon(icon: ImageVector, onClick: () -> Unit, danger: Boolean = false, size: Dp = 28.dp) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    !hovered -> Color.Transparent
                    danger -> Color(0xFFDC2626).copy(alpha = 0.10f)
                    else -> rp.chipBg
                },
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, size = 12.dp, tint = if (hovered && danger) rp.red else if (hovered) rp.ink else rp.ink3)
    }
}

/** Numbers only: an optional leading minus, digits and one decimal point. */
internal val numberFilter: (String) -> Boolean = { it.isEmpty() || Regex("^-?\\d*\\.?\\d*$").matches(it) }

/** A text style for the grid's read-only cells. */
@Composable
internal fun cellText(mono: Boolean = false): TextStyle =
    if (mono) DmType.mono(12.5.sp) else DmType.sans(12.5.sp)

/** Left-aligned under its anchor — the grid's dropdowns. */
internal class BelowStartPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceAtMost((windowSize.width - popupContentSize.width - EDGE).coerceAtLeast(EDGE))
        val below = anchorBounds.bottom + gap
        val roomAbove = anchorBounds.top > windowSize.height - anchorBounds.bottom
        val flip = below + popupContentSize.height > windowSize.height && roomAbove
        val y = if (flip) anchorBounds.top - popupContentSize.height - gap else below
        return IntOffset(x, y.coerceAtLeast(EDGE))
    }
}

private const val EDGE = 8
