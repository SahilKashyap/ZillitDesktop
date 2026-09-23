package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.delay

/**
 * A transient error popup — the web's `message.error`, floated over the
 * content rather than pushed into it.
 *
 * Auto-dismisses, because an error about a failed send is stale the moment
 * the user retries — but carries a close button too, for the reader faster
 * than the timer. Renders nothing when [message] is null, so the call site
 * can pass state straight in.
 */
@Composable
fun ZillitErrorToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long = AUTO_DISMISS_MILLIS,
) = ZillitToast(message, onDismiss, ZillitToastTone.Danger, modifier, autoDismissMillis)

/**
 * The same floating popup in any [ZillitToastTone] — the web's `message.error`
 * and `message.success` are one component there too.
 */
@Composable
fun ZillitToast(
    message: String?,
    onDismiss: () -> Unit,
    tone: ZillitToastTone,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long = AUTO_DISMISS_MILLIS,
) {
    if (message == null) return

    // Keyed on the text: a new error restarts the clock rather than being
    // cut short by the previous one's timer.
    LaunchedEffect(message) {
        delay(autoDismissMillis)
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize().padding(TOAST_MARGIN), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .widthIn(max = TOAST_MAX_WIDTH)
                .shadow(TOAST_ELEVATION, ZillitTheme.shapes.medium)
                .clip(ZillitTheme.shapes.medium)
                .background(
                    when (tone) {
                        ZillitToastTone.Danger -> ZillitTheme.colors.danger
                        ZillitToastTone.Success -> ZillitTheme.colors.success
                    },
                )
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textOnAccent,
                modifier = Modifier.weight(1f, fill = false),
            )
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.sync_action_dismiss),
                onClick = onDismiss,
                tint = ZillitTheme.colors.textOnAccent,
                size = TOAST_CLOSE,
            )
        }
    }
}

private val TOAST_MARGIN = 16.dp
private val TOAST_MAX_WIDTH = 480.dp
private val TOAST_ELEVATION = 6.dp
private val TOAST_CLOSE = 24.dp
private const val AUTO_DISMISS_MILLIS = 5_000L
