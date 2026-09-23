package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.ui.TaxToast
import com.zillit.desktop.feature.taxfiling.ui.TaxToastTone
import kotlinx.coroutines.delay

/**
 * The confirmation pill at the foot of the surface — the web's dark toast.
 *
 * A done thing reads for [SUCCESS_MILLIS], as on the web; something worth
 * knowing and something that went wrong stay long enough to be read, and
 * carry a close button for the reader faster than the timer. It slides up
 * as it arrives and away as it goes.
 */
@Composable
internal fun MtdToast(toast: TaxToast?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = mtdPalette()
    // The last message stays composed through the exit, so it does not vanish mid-slide.
    val last = remember { mutableStateOf<TaxToast?>(null) }
    SideEffect { if (toast != null) last.value = toast }
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = toast != null

    LaunchedEffect(toast) {
        val current = toast ?: return@LaunchedEffect
        delay(
            when (current.tone) {
                TaxToastTone.Success -> SUCCESS_MILLIS
                TaxToastTone.Info -> INFO_MILLIS
                TaxToastTone.Error -> ERROR_MILLIS
            },
        )
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize().padding(bottom = 26.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(MOTION_MS)) + slideInVertically(tween(MOTION_MS)) { it / 2 },
            exit = fadeOut(tween(MOTION_MS)) + slideOutVertically(tween(MOTION_MS)) { it / 2 },
        ) {
            val message = toast ?: last.value ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(11.dp)
            Row(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .shadow(18.dp, shape, clip = false)
                    .clip(shape)
                    .background(palette.toast)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                val (icon, tint) = when (message.tone) {
                    TaxToastTone.Success -> ZillitIcons.Check to palette.green
                    TaxToastTone.Info -> ZillitIcons.Info to palette.accent
                    TaxToastTone.Error -> ZillitIcons.Warning to palette.red
                }
                ZillitIcon(icon = icon, tint = if (palette.isDark) tint else lighten(tint), size = 16.dp)
                ZillitText(
                    text = message.message,
                    style = mtdText(13.5.sp, FontWeight.Medium),
                    color = palette.onToast,
                    modifier = Modifier.weight(1f, fill = false).padding(vertical = 3.dp),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.sync_action_dismiss),
                    onClick = onDismiss,
                    tint = palette.onToast,
                    size = 26.dp,
                )
            }
        }
    }
}

/** A status hue lifted for the dark pill it sits on in the light scheme. */
private fun lighten(color: Color): Color = lerp(color, Color.White, LIFT)

private const val LIFT = 0.35f
private const val MOTION_MS = 200
private const val SUCCESS_MILLIS = 2_400L
private const val INFO_MILLIS = 6_000L
private const val ERROR_MILLIS = 6_000L
