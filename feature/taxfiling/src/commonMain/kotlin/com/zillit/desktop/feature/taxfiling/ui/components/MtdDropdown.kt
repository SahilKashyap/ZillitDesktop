package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
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
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSelectTrigger
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll

/**
 * The web's fields are 42px tall, and so is the app's date field these sit
 * beside — one height, so a row of mixed fields lines up along its foot.
 */
internal val FieldHeight = 42.dp

/**
 * The web's `Dropdown`: a field over a list of rich options — the app's shared
 * select field ([ZillitSelectTrigger]) at this tool's 42dp height, opening the
 * shared list ([ZillitOptionPopup]), which flips upward when the window has no
 * room below. An option's status pill, or [mono] labels, need the row drawn
 * here; otherwise the shared row draws the label and its quiet line.
 */
@Suppress("LongMethod")
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
    val palette = mtdPalette()
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val selected = options.firstOrNull { it.value == value }

    Box(modifier = modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = open,
            enabled = enabled,
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            minHeight = FieldHeight,
            onClear = if (clearable && selected != null) ({ onChange(null) }) else null,
        ) {
            ZillitText(
                text = selected?.label ?: placeholder,
                style = if (selected != null) {
                    mtdText(if (mono) 13.5.sp else 14.sp, FontWeight.Medium, mono = mono)
                } else {
                    mtdText(14.sp)
                },
                color = if (selected != null) palette.ink else palette.muted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            val rich = mono || options.any { it.pill != null }
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = anchor.popupWidth(),
                options = options,
                isSelected = { it.value == value },
                onPick = { option ->
                    open = false
                    onChange(option.value)
                },
                label = MtdOption<T>::label,
                searchable = false,
                subtitle = MtdOption<T>::sub,
                renderOption = if (rich) ({ option, active -> RichOption(option, active, mono) }) else null,
            )
        }
    }
}

/** A row with what the shared one cannot draw: a monospace label, a status pill. */
@Composable
private fun <T> RichOption(option: MtdOption<T>, active: Boolean, mono: Boolean) {
    val palette = mtdPalette()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
