package com.zillit.desktop.core.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Every file leaving this machine, from any production, in one floating card
 * — Drive's uploads panel, for chat and the boards.
 *
 * App-wide on purpose: a large video keeps compressing and uploading after a
 * project switch, and this is where its progress stays visible. Each row
 * shows the file's own picture, where it is going, and its phase; a running
 * row can be cancelled, a failed one retried, and a finished one leaves on
 * its own after a moment.
 *
 * Shown while [open]; [onHide] closes it (the status bar's count reopens it).
 */
@Composable
fun UploadsTray(tracker: MediaSendTracker, open: Boolean, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val sends by tracker.sends.collectAsState()
    if (!open || sends.isEmpty()) return
    Column(
        modifier = modifier
            .width(TRAY_WIDTH)
            .shadow(TRAY_ELEVATION, ZillitTheme.shapes.medium)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceRaised)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.drive_uploads_header_count, sends.size),
                style = ZillitTheme.typography.label,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (sends.any { it.status == SendStatus.Done }) {
                ZillitButton(
                    text = str(S.desktop_drive_clear_completed),
                    onClick = tracker::clearFinished,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            ZillitIconButton(icon = ZillitIcons.ChevronDown, contentDescription = str(S.hide), onClick = onHide)
        }
        LazyColumn(Modifier.heightIn(max = TRAY_MAX_LIST)) {
            items(sends, key = { it.id }) { send -> SendRow(send, tracker) }
        }
    }
}

/** One file: its picture, its name and destination, and where it stands. */
@Composable
private fun SendRow(send: TrackedSend, tracker: MediaSendTracker) {
    // A finished row has said what it needed to; it steps aside on its own.
    if (send.status == SendStatus.Done) {
        LaunchedEffect(send.id) {
            delay(DONE_LINGER_MILLIS)
            tracker.dismiss(send.id)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Thumb(send)
        SendText(send, Modifier.weight(1f))
        SendActions(send, tracker)
    }
}

/** The file's name, where it is going, and how far it has got. */
@Composable
private fun SendText(send: TrackedSend, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(
            text = send.fileName,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        ZillitText(
            text = str(S.docusign_bulk_meta, send.projectName, send.destination),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        when (send.status) {
            SendStatus.Running -> {
                if (UploadProgress.phase(send.progress) != UploadProgress.Phase.Preparing) {
                    ZillitProgressBar(
                        fraction = UploadProgress.percent(send.progress) / FULL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitText(
                    text = UploadProgress.label(send.progress),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            SendStatus.Failed -> ZillitText(
                text = str(S.dd_status_failed),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
            SendStatus.Done -> ZillitText(
                text = str(S.txt_sent),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.success,
            )
        }
    }
}

/** Cancel while it runs, Retry when it failed, a tick when it is done. */
@Composable
private fun SendActions(send: TrackedSend, tracker: MediaSendTracker) {
    when (send.status) {
        SendStatus.Running -> ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.cancel),
            onClick = { tracker.cancel(send.id) },
        )
        SendStatus.Failed -> Row {
            if (tracker.canRetry(send.id)) {
                ZillitButton(
                    text = str(S.retry),
                    onClick = { tracker.retry(send.id) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.hide),
                onClick = { tracker.dismiss(send.id) },
            )
        }
        SendStatus.Done -> ZillitIcon(
            icon = ZillitIcons.Check,
            contentDescription = str(S.txt_sent),
            tint = ZillitTheme.colors.success,
        )
    }
}

/** The file's own picture, or a file mark where there is none to show. */
@Composable
private fun Thumb(send: TrackedSend) {
    val bitmap by produceState<ImageBitmap?>(null, send.preview) {
        value = send.preview?.let { withContext(Dispatchers.Default) { decodeImageBitmap(it, THUMB_DECODE_PX) } }
    }
    Box(
        modifier = Modifier
            .size(THUMB)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            ZillitIcon(icon = ZillitIcons.Upload, contentDescription = null, tint = ZillitTheme.colors.textMuted)
        }
    }
}

private val TRAY_WIDTH = 340.dp
private val TRAY_MAX_LIST = 320.dp
private val TRAY_ELEVATION = 8.dp
private val THUMB = 44.dp
private const val THUMB_DECODE_PX = 132
private const val DONE_LINGER_MILLIS = 4_000L
private const val FULL = 100f
