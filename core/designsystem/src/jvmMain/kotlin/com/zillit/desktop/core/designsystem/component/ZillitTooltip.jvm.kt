package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun ZillitTooltip(text: String, content: @Composable () -> Unit) {
    if (text.isBlank()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            ZillitText(
                text = text,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier
                    .shadow(TOOLTIP_ELEVATION, ZillitTheme.shapes.small)
                    .clip(ZillitTheme.shapes.small)
                    .background(ZillitTheme.colors.surface)
                    .border(TOOLTIP_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.small)
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            )
        },
        delayMillis = TOOLTIP_DELAY_MILLIS,
        content = content,
    )
}

private val TOOLTIP_ELEVATION = 4.dp
private val TOOLTIP_HAIRLINE = 1.dp
private const val TOOLTIP_DELAY_MILLIS = 400
