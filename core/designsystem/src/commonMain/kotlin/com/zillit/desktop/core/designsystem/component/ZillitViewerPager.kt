package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Steps a full-screen viewer through a run of pictures and clips — a chevron
 * either side of [content], and the ← → keys — so a conversation's or a
 * board's media can be looked through in one go instead of open, close, open.
 *
 * The chevrons sit in gutters *beside* the media rather than floating over
 * it: a playing clip is a heavyweight surface and paints over anything
 * composed on top of it. A null [onPrevious]/[onNext] is the end of the run —
 * its gutter stays, empty, so the media does not shift sideways at the ends;
 * with neither there is nothing to step through and no gutters at all.
 */
@Composable
fun ZillitViewerPager(
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (onPrevious == null && onNext == null) {
        Box(modifier, contentAlignment = Alignment.Center, content = content)
        return
    }
    val focus = remember { FocusRequester() }
    // Focus on opening, so the arrow keys work without a click first.
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    Row(
        modifier = modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = when (event.key) {
                    Key.DirectionLeft -> onPrevious
                    Key.DirectionRight -> onNext
                    else -> return@onPreviewKeyEvent false
                }
                step?.invoke()
                true
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerStep(ZillitIcons.ChevronLeft, str(S.docusign_tour_prev), onPrevious)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center, content = content)
        PagerStep(ZillitIcons.ChevronRight, str(S.next), onNext)
    }
}

/** One gutter: a round chevron on the scrim, or nothing at the run's end. */
@Composable
private fun PagerStep(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    Box(Modifier.width(STEP_GUTTER).fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (onClick != null) {
            Box(
                Modifier
                    .background(Color.Black.copy(alpha = STEP_SCRIM), CircleShape)
                    .padding(STEP_INSET),
            ) {
                ZillitIconButton(
                    icon = icon,
                    contentDescription = label,
                    onClick = onClick,
                    tint = Color.White,
                    size = STEP_BUTTON,
                )
            }
        }
    }
}

private val STEP_GUTTER = 64.dp
private val STEP_BUTTON = 40.dp
private val STEP_INSET = 2.dp
private const val STEP_SCRIM = 0.45f
