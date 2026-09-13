package com.zillit.desktop.feature.callsheet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/** `EmptyState`: a tray and one grey sentence. */
@Composable
internal fun SheetEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    bordered: Boolean = true,
    minHeight: Dp = 220.dp,
    sub: String? = null,
) {
    val colors = SheetTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(
                if (bordered) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 24.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                SheetIcons.Tray,
                contentDescription = null,
                tint = colors.borderStrong,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text,
                style = sheetText(14.sp, if (sub != null) FontWeight.Medium else FontWeight.Normal, 20.sp),
                color = if (sub != null) colors.textSecondary else colors.textTertiary,
                textAlign = TextAlign.Center,
            )
            if (sub != null) {
                Text(sub, style = sheetText(12.sp), color = colors.textTertiary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A list's error line with a retry — the web was silent; the desktop says why. */
@Composable
internal fun SheetErrorLine(message: String, onRetry: () -> Unit) {
    val colors = SheetTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.redBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = sheetText(13.sp), color = colors.red, modifier = Modifier.weight(1f))
        SheetButton("Retry", onRetry, kind = ButtonKind.DangerOutline, height = 30.dp, fontSize = 12.sp)
    }
}

/**
 * A hover card anchored under its trigger — the web's antd Popover
 * ("Approval Status"). Opens on hover, stays while the pointer is on it.
 */
@Composable
internal fun HoverCard(
    title: String,
    trigger: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val triggerSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val cardSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val overTrigger by triggerSource.collectIsHoveredAsState()
    val overCard by cardSource.collectIsHoveredAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(overTrigger, overCard) {
        if (overTrigger || overCard) {
            visible = true
        } else {
            // A moment's grace so the pointer can cross from trigger to card.
            kotlinx.coroutines.delay(POPOVER_GRACE_MS)
            visible = false
        }
    }
    Box(Modifier.hoverable(triggerSource)) {
        trigger()
        if (visible) {
            Popup(offset = IntOffset(0, POPOVER_OFFSET), properties = PopupProperties(focusable = false)) {
                Column(
                    modifier = Modifier
                        .hoverable(cardSource)
                        .widthIn(max = 360.dp)
                        .shadow(12.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(SheetTheme.colors.surface)
                        .border(1.dp, SheetTheme.colors.border, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = sheetText(14.sp, FontWeight.SemiBold), color = SheetTheme.colors.textPrimary)
                    content()
                }
            }
        }
    }
}

private const val POPOVER_GRACE_MS = 180L
private const val POPOVER_OFFSET = 36
