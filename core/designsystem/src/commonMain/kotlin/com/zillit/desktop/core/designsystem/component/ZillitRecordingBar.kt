package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The composer while the microphone is open: a clock, and the two ways out.
 *
 * It replaces the input entirely — typing while recording is not a state
 * anyone needs, and the swap is what makes "you are being recorded"
 * impossible to miss. One component because the board and the chat must not
 * drift: a crew member who learns what recording looks like has learned it
 * everywhere.
 */
@Composable
fun ZillitRecordingBar(
    seconds: Int,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BAR_RADIUS))
            .background(ZillitTheme.colors.canvas)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // The live dot: recording indicators are red everywhere or nowhere.
        Box(
            modifier = Modifier
                .size(RECORD_DOT)
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.danger),
        )
        ZillitText(
            text = str(S.desktop_recording_clock, recordingClock(seconds)),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.cancel),
            variant = ButtonVariant.Tertiary,
            onClick = onCancel,
        )
        ZillitButton(
            text = str(S.stop),
            variant = ButtonVariant.Primary,
            onClick = onStop,
        )
    }
}

/** 0:07, 1:23 — a clock, not a number. */
private fun recordingClock(seconds: Int): String {
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return minutes.toString() + ":" + rest.toString().padStart(2, '0')
}

private val BAR_RADIUS = 22.dp
private val RECORD_DOT = 10.dp
private const val SECONDS_PER_MINUTE = 60
