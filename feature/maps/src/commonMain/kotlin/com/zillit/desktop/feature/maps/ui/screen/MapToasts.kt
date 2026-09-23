@file:Suppress("MatchingDeclarationName") // The file is the toast surface; MapToast is one entry in it.

package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.maps.ui.NoticeTone
import kotlinx.coroutines.delay

/** One toast on screen. [id] keeps two identical messages apart. */
data class MapToast(val id: Long, val message: String, val tone: NoticeTone)

/**
 * react-toastify's corner: the newest message at the top right, leaving on its
 * own. Four voices — success, info, warning, error — because the web speaks in
 * all four and a warning dressed as an error reads as a failure.
 *
 * One at a time, inside the toolbar's band. Anywhere lower it would sit over
 * the map, which is a browser surface that paints above every Compose pixel —
 * a toast there would never be seen.
 */
@Composable
internal fun MapToastHost(toasts: List<MapToast>, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(top = 8.dp, end = 12.dp), contentAlignment = Alignment.TopEnd) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            toasts.lastOrNull()?.let { toast ->
                key(toast.id) { ToastCard(toast, onDismiss) }
            }
        }
    }
}

@Composable
private fun ToastCard(toast: MapToast, onDismiss: (Long) -> Unit) {
    val colors = ZillitTheme.colors
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(toast.id) {
        delay(TOAST_MILLIS)
        visible.targetState = false
        delay(EXIT_MILLIS)
        onDismiss(toast.id)
    }
    val (accent, icon) = when (toast.tone) {
        NoticeTone.Success -> MapColors.Success to MapIcons.CheckCircle
        NoticeTone.Info -> MapColors.Info to ZillitIcons.Info
        NoticeTone.Warning -> MapColors.Warning to MapIcons.AlertTriangle
        NoticeTone.Error -> MapColors.DangerDeep to MapIcons.AlertTriangle
    }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn() + slideInHorizontally { it / 3 },
        exit = fadeOut() + slideOutHorizontally { it / 3 },
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 520.dp)
                .shadow(10.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(42.dp).background(accent))
            Row(
                modifier = Modifier.height(42.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitIcon(icon = icon, tint = accent, size = 18.dp)
                ZillitText(
                    text = toast.message,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textPrimary,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
                CardAction(onClick = { visible.targetState = false; onDismiss(toast.id) }, icon = ZillitIcons.Close)
            }
        }
    }
}

private const val TOAST_MILLIS = 3_500L
private const val EXIT_MILLIS = 250L
