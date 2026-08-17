package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A thin determinate bar: the filled share of a job that will finish.
 *
 * Uploads are its home — the chat bubble and the board card both wear it while
 * a file climbs to storage. One component so progress looks the same wherever
 * it appears; the audio track in [ZillitAudioProgress] stays its own thing
 * because it is a *control* (seekable), not a readout.
 */
@Composable
fun ZillitProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = ZillitTheme.colors.border,
    fillColor: Color = ZillitTheme.colors.accent,
) {
    Box(
        modifier = modifier
            .height(PROGRESS_TRACK)
            .clip(ZillitTheme.shapes.pill)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(PROGRESS_TRACK)
                .background(fillColor),
        )
    }
}

private val PROGRESS_TRACK = 4.dp
