package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * What a screen shows when there is nothing to show.
 *
 * The [message] is not optional decoration: "No receipts" and "No receipts
 * match the current filter" are different facts, and a queue that shows the
 * first when the second is true has quietly hidden work from an accountant.
 */
@Composable
fun ZillitEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ZillitTheme.spacing.xxl, horizontal = ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(EMPTY_ICON_WELL)
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon ?: ZillitIcons.File, tint = ZillitTheme.colors.textMuted)
        }
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        message?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}

/**
 * A failed load, with the way out.
 *
 * Always offers [onRetry]: the finance endpoints fail transiently on a shoot's
 * connection more often than they fail permanently, and a dead end for a
 * recoverable error sends people to the phone app instead.
 */
@Composable
fun ZillitErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = str(S.desktop_could_not_load_this),
) {
    ZillitEmptyState(
        title = title,
        message = message,
        icon = ZillitIcons.Warning,
        modifier = modifier,
        action = {
            ZillitButton(
                text = str(S.docusign_token_gateway_retry),
                onClick = onRetry,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
            )
        },
    )
}

/**
 * A pulsing grey bar standing in for text that has not arrived.
 *
 * The pulse is what distinguishes "loading" from "empty field" — a static grey
 * bar reads as a rendering fault.
 */
@Composable
fun ZillitSkeletonBar(
    modifier: Modifier = Modifier,
    height: Dp = SKELETON_BAR_HEIGHT,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = SKELETON_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(SKELETON_PULSE_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(alpha)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.surfaceHover),
    )
}

/**
 * An inline notice — the amber and blue banners the cash pages use to explain
 * a state before the user hits it ("this float is awaiting collection", "coding
 * is required on this production").
 */
@Composable
fun ZillitNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Pending,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(tone.background())
            .border(CARD_HAIRLINE, tone.content().copy(alpha = NOTICE_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon ?: ZillitIcons.Info, tint = tone.content(), size = ZillitTheme.spacing.lg)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = tone.content(),
            modifier = Modifier.weight(1f),
            maxLines = NOTICE_MAX_LINES,
        )
        action?.invoke()
    }
}

private val EMPTY_ICON_WELL = 44.dp
private val SKELETON_BAR_HEIGHT = 11.dp
private const val SKELETON_PULSE_MILLIS = 750
private const val SKELETON_MIN_ALPHA = 0.35f
private const val SKELETON_MAX_ALPHA = 0.9f
private const val NOTICE_BORDER_ALPHA = 0.25f
private const val NOTICE_MAX_LINES = 3
