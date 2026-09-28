package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

@Composable
internal fun StatusTone.background(): Color = when (this) {
    StatusTone.Neutral -> ZillitTheme.colors.surfaceHover
    StatusTone.Pending -> ZillitTheme.colors.warningSoft
    StatusTone.Progress -> ZillitTheme.colors.infoSoft
    StatusTone.Ready -> ZillitTheme.colors.successSoft
    StatusTone.Done -> ZillitTheme.colors.tealSoft
    StatusTone.Rejected -> ZillitTheme.colors.dangerSoft
    StatusTone.Escalated -> ZillitTheme.colors.violetSoft
    StatusTone.InTransit -> ZillitTheme.colors.goldSoft
}

@Composable
internal fun StatusTone.content(): Color = when (this) {
    StatusTone.Neutral -> ZillitTheme.colors.textSecondary
    StatusTone.Pending -> ZillitTheme.colors.warning
    StatusTone.Progress -> ZillitTheme.colors.info
    StatusTone.Ready -> ZillitTheme.colors.success
    StatusTone.Done -> ZillitTheme.colors.teal
    StatusTone.Rejected -> ZillitTheme.colors.danger
    StatusTone.Escalated -> ZillitTheme.colors.violet
    StatusTone.InTransit -> ZillitTheme.colors.gold
}

/**
 * A workflow status, as a pill.
 *
 * Colour is never the only signal — the label always reads — because a queue
 * scanned by someone with a colour vision deficiency has to sort the same way
 * as one scanned by anyone else. The [dot] is the web's own affordance for the
 * same reason: two pills of similar lightness stay distinguishable by their
 * marker.
 */
@Composable
fun ZillitStatusPill(
    label: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    dot: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.pill)
            .background(tone.background())
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = PILL_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .clip(ZillitTheme.shapes.pill)
                    .background(tone.content()),
            )
        }
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = tone.content(),
            maxLines = 1,
        )
    }
}

private val PILL_VERTICAL_PADDING = 3.dp
private val DOT_SIZE = 5.dp
