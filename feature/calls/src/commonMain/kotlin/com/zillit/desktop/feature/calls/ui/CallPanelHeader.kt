package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The title row every call panel opens with, and its ✕.
 *
 * Each panel used to close only from the control that opened it — press
 * People again, press ⋮ again — which nobody finds: the panel is a thing
 * on the screen, so the way out belongs on it, as the web's `cupClose`
 * does (`CallOverlays.tsx:363`). One composable so the ✕ sits in the same
 * corner, at the same size, on every panel.
 *
 * [tint] is the panel's text colour: the stage's own panels are drawn on
 * the call's dark palette, the rest on the workspace theme.
 */
@Composable
internal fun CallPanelHeader(
    title: String,
    onClose: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (icon != null) ZillitIcon(icon = icon, contentDescription = null, tint = tint, size = HEADER_ICON)
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailing()
        PanelCloseButton(onClose = onClose, tint = tint)
    }
}

/** A round ✕ that lights on hover, so it reads as a button and not a glyph. */
@Composable
internal fun PanelCloseButton(onClose: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(CLOSE_BUTTON)
            .clip(CircleShape)
            .background(if (hovered) tint.copy(alpha = HOVER_ALPHA) else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.Close, contentDescription = str(S.close), tint = tint, size = CLOSE_ICON)
    }
}

private val HEADER_ICON = 18.dp
private val CLOSE_BUTTON = 28.dp
private val CLOSE_ICON = 16.dp
private const val HOVER_ALPHA = 0.12f
