package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A panel sliding in from the right over a dimmed sheet — the antd `Drawer`
 * the web opens for a crew member's profile and for the company details.
 * A tap on the scrim closes it; presses on the panel never reach the sheet.
 */
@Composable
internal fun CrewDrawer(
    visible: Boolean,
    width: Dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(ENTER_MS)), exit = fadeOut(tween(EXIT_MS))) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM))
                .onBackdropTap(onDismiss),
        ) {
            val panelWidth = if (maxWidth < width + GUTTER) maxWidth else width
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .animateEnterExit(
                            enter = slideInHorizontally(tween(ENTER_MS)) { it },
                            exit = slideOutHorizontally(tween(EXIT_MS)) { it },
                        )
                        .shadow(24.dp)
                        .background(ZillitTheme.colors.surface)
                        .swallowPresses(),
                    content = content,
                )
            }
        }
    }
}

private val GUTTER = 48.dp
private const val SCRIM = 0.4f
private const val ENTER_MS = 240
private const val EXIT_MS = 180
