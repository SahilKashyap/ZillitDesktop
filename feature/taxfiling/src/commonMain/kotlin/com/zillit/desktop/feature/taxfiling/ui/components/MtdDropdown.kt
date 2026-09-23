package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The web's fields are 42px tall, and so is the app's date field these sit
 * beside — one height, so a row of mixed fields lines up along its foot.
 */
internal val FieldHeight = 42.dp

/**
 * The web's `Dropdown`: a field-shaped trigger over a list of rich options.
 *
 * The list is composed in full inside a bounded, scrolling column rather than a
 * lazy list in a menu — a lazy list measured by a menu's intrinsics crashes on
 * open — and it drops upward when the window has no room below, as the web's
 * does.
 */
@Composable
internal fun <T> MtdDropdown(
    value: T?,
    options: List<MtdOption<T>>,
    onChange: (T?) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearable: Boolean = false,
    enabled: Boolean = true,
    mono: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val selected = options.firstOrNull { it.value == value }

    Box(modifier = modifier.onSizeChanged { width = it.width }) {
        DropdownTrigger(
            label = selected?.label,
            placeholder = placeholder,
            open = open,
            enabled = enabled,
            mono = mono,
            onToggle = { open = !open },
            onClear = if (clearable && selected != null && enabled) ({ onChange(null) }) else null,
        )
        if (open) {
            DropdownPopup(widthPx = width, onDismiss = { open = false }) {
                DropdownOptions(options = options, value = value, mono = mono) { picked ->
                    open = false
                    onChange(picked)
                }
            }
        }
    }
}

/** The field-shaped face of a dropdown: what is chosen, and a chevron — or a clear cross once it can be cleared. */
@Composable
private fun DropdownTrigger(
    label: String?,
    placeholder: String,
    open: Boolean,
    enabled: Boolean,
    mono: Boolean,
    onToggle: () -> Unit,
    onClear: (() -> Unit)?,
) {
    val palette = mtdPalette()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val edge by animateColorAsState(
        when {
            open -> palette.accent
            hovered && enabled -> palette.border2
            else -> palette.border
        },
        label = "dropdownEdge",
    )
    val chosen = label != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FieldHeight)
            .clip(FieldShape)
            .background(if (enabled) palette.surface else palette.surface3)
            .border(1.dp, edge, FieldShape)
            .hoverable(hover)
            .clickable(enabled = enabled, role = Role.DropdownList, onClick = onToggle)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(
            text = label ?: placeholder,
            style = if (chosen) {
                mtdText(if (mono) 13.5.sp else 14.sp, FontWeight.Medium, mono = mono)
            } else {
                mtdText(14.sp)
            },
            color = if (chosen) palette.ink else palette.muted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (onClear != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.txt_clear),
                onClick = onClear,
                size = 22.dp,
            )
        } else {
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = palette.muted, size = 15.dp)
        }
    }
}

@Composable
private fun <T> DropdownOptions(options: List<MtdOption<T>>, value: T?, mono: Boolean, onPick: (T) -> Unit) {
    options.forEach { option ->
        OptionRow(option = option, active = option.value == value, mono = mono) { onPick(option.value) }
    }
    if (options.isEmpty()) {
        ZillitText(
            text = str(S.desktop_nothing_to_choose_from),
            style = mtdText(13.sp),
            color = mtdPalette().muted,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
        )
    }
}

/** The floating list under a field, the width of the field. */
@Composable
internal fun DropdownPopup(
    widthPx: Int,
    onDismiss: () -> Unit,
    focusable: Boolean = true,
    maxHeight: Dp = 280.dp,
    content: @Composable () -> Unit,
) {
    val palette = mtdPalette()
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val position = remember(gap) { BelowOrAbove(gap) }
    val shape = RoundedCornerShape(12.dp)
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) {
        Column(
            modifier = Modifier
                .width(with(density) { widthPx.toDp() }.coerceAtLeast(MIN_POPUP_WIDTH))
                .shadow(16.dp, shape, clip = false)
                .clip(shape)
                .background(ZillitTheme.colors.surfaceRaised)
                .border(1.dp, palette.border, shape)
                .heightIn(max = maxHeight)
                .zillitVerticalScroll(rememberScrollState())
                .padding(5.dp),
        ) { content() }
    }
}

private val MIN_POPUP_WIDTH = 220.dp

@Composable
private fun <T> OptionRow(option: MtdOption<T>, active: Boolean, mono: Boolean, onPick: () -> Unit) {
    val palette = mtdPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> palette.accentWash
                    hovered -> palette.surface3
                    else -> ZillitTheme.colors.surfaceRaised
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = option.label,
                style = mtdText(if (mono) 13.sp else 13.5.sp, FontWeight.SemiBold, mono = mono, tracking = (-0.01).em),
                color = if (active) palette.accentText else palette.ink,
                maxLines = 1,
            )
            option.sub?.let { ZillitText(text = it, style = mtdText(11.5.sp), color = palette.muted, maxLines = 1) }
        }
        option.pill?.let { (text, tone) -> MtdPill(text = text, tone = tone) }
    }
}

/**
 * Below the anchor when it fits, above it when it does not — and never off the
 * window's side, whatever the anchor's position.
 */
private class BelowOrAbove(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = anchorBounds.bottom + gap
        val fitsBelow = below + popupContentSize.height <= windowSize.height
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (fitsBelow || above < 0) below else above
        val x = anchorBounds.left.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x.coerceAtLeast(0), y)
    }
}
