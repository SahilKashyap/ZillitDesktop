package com.zillit.desktop.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The whole frame, covered, while this build is below the Remote Config floor
 * (or `desktop_force_update` is on and a newer build exists).
 *
 * The mandatory strip left the app usable, so "must update" was only advice.
 * This is the Android client's `IMMEDIATE` update: nothing behind it takes a
 * click, and the only ways forward are the update itself — downloaded,
 * installed and restarted on its own, with the same steps and buttons as the
 * strip (see [UpdateActions]) — or quitting.
 *
 * The restart still waits for a call to end: the call overlay is its own
 * window, and a forced update that drops someone mid-call is worse than one
 * that lands a few minutes later.
 */
@Composable
// One per callback the strip exposes; and a card whose every line is one
// visible element reads better whole than split into pieces.
@Suppress("LongParameterList", "LongMethod")
internal fun ForceUpdateScreen(
    notice: UpdateNotice,
    onDownload: (String) -> Unit,
    onInstall: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onOpenDownloaded: () -> Unit,
    onQuit: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Keys too: focus leaves whatever field was being typed in, and Tab may
    // not walk back out into the frame.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.scrim)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { it.key == Key.Tab }
            // Being the topmost pointer target is what keeps presses from the
            // frame beneath. Main pass, not Initial: Initial reaches this box
            // before its own buttons, and consuming there deafened them.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .testTag(FORCE_UPDATE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CARD_MAX_WIDTH)
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIcon(ZillitIcons.Download, tint = ZillitTheme.colors.danger, size = ICON_SIZE)
            ZillitText(text = str(S.desktop_update_required_title), style = ZillitTheme.typography.titleLarge)
            ZillitText(
                text = str(S.desktop_update_required_detail),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            ZillitText(
                text = bannerText(notice),
                style = ZillitTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                UpdateActions(
                    notice = notice,
                    accent = ZillitTheme.colors.danger,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    onRestart = onRestart,
                    onCancel = onCancel,
                    onOpenDownloaded = onOpenDownloaded,
                )
                onQuit?.let {
                    ZillitButton(
                        text = str(S.desktop_quit_zillit),
                        onClick = it,
                        size = ButtonSize.Small,
                        variant = ButtonVariant.Tertiary,
                        modifier = Modifier.testTag(FORCE_UPDATE_QUIT_TAG),
                    )
                }
            }
        }
    }
}

private val CARD_MAX_WIDTH = 480.dp
private val ICON_SIZE = 40.dp

/** The blocking screen. */
internal const val FORCE_UPDATE_TAG = "update-force"

/** Its Quit button. */
internal const val FORCE_UPDATE_QUIT_TAG = "update-force-quit"
