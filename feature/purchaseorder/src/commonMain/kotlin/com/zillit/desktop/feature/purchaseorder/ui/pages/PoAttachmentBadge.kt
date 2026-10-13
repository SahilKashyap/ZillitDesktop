package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The paperclip a list row carries when its order has files — the web's
 * `PoAttachmentBadge`, on every list so the signal is the same everywhere.
 * Draws nothing for none; the count shows only past one, and the pointer
 * resting on it says how many.
 */
@Composable
internal fun PoAttachmentBadge(count: Int) {
    if (count <= 0) return
    ZillitTooltip(str(if (count == 1) S.desktop_docdist_one_attachment else S.dd_attachments_line, count)) {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .background(ZillitTheme.colors.surfaceSunken)
                .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.small)
                .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(ZillitIcons.Paperclip, tint = ZillitTheme.colors.textMuted, size = ICON)
            if (count > 1) {
                ZillitText(
                    text = count.toString(),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

private val HAIRLINE = 1.dp
private val ICON = 12.dp
