package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitVideoView
import com.zillit.desktop.core.designsystem.component.ZillitViewerClose
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.ChatAttachment

/**
 * The in-app lightbox a clicked picture or clip opens — the Home board's
 * `MediaLightbox` in chat's frame: the media over a scrim, the filename, and a
 * Download that is a *right*, not a given. Saving to disk is what the download
 * permission governs, so looking is free but the button asks
 * [ChatSeams.canDownload] first and refuses with Android's own sentence
 * (`msg_download_right`, `res/values/strings.xml:7884`) when the answer is no.
 *
 * A clip plays here rather than being handed to the OS, and it *streams*:
 * [videoUrl] presigns the object and the player ranges over it, so nothing
 * waits for a whole file. Watching is looking, so it is ungated too — only
 * the Download beside it asks.
 */
@Composable
internal fun ChatMediaViewer(
    file: ChatAttachment,
    loadImage: suspend (ChatAttachment) -> ImageBitmap?,
    videoUrl: (suspend (ChatAttachment) -> String?)?,
    canDownload: () -> Boolean,
    onDownload: (ChatAttachment) -> Unit,
    onOpenOutside: (ChatAttachment) -> Unit,
    onClose: () -> Unit,
) {
    if (file.kind == "video") {
        ChatVideoViewer(file, videoUrl, canDownload, onDownload, onOpenOutside, onClose)
        return
    }
    val image by produceState<ImageBitmap?>(initialValue = null, file.media) {
        value = loadImage(file)
    }
    var refused by remember(file.media) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = VIEWER_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            ViewerHeader(file.name, refused, onClose) {
                if (canDownload()) onDownload(file) else refused = true
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val loaded = image
                if (loaded == null) {
                    ZillitText(
                        text = str(S.ah_loading),
                        style = ZillitTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                } else {
                    Image(
                        bitmap = loaded,
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(VIEWER_PADDING),
                    )
                }
            }
        }
    }
}

/** Name on the left, the gated Download and the close on the right. */
@Composable
private fun ViewerHeader(
    name: String,
    refused: Boolean,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = name,
            style = ZillitTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (refused) {
            // Android's exact refusal, where the button's promise was.
            ZillitText(
                text = DOWNLOAD_REFUSED,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
        ZillitButton(
            text = str(S.download),
            onClick = onDownload,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Download,
        )
        ZillitViewerClose(onClose = onClose)
    }
}

/**
 * The same frame with a player in it instead of a picture.
 *
 * Its own composable rather than a branch inside the image one because the
 * player is a *heavyweight* surface: it paints over anything composed on top
 * of it, so the header has to sit beside it in a column, and the
 * click-anywhere-to-close scrim the picture carries would be a click the
 * player's own controls never receive. The cross is the way out here.
 */
@Composable
private fun ChatVideoViewer(
    file: ChatAttachment,
    videoUrl: (suspend (ChatAttachment) -> String?)?,
    canDownload: () -> Boolean,
    onDownload: (ChatAttachment) -> Unit,
    onOpenOutside: (ChatAttachment) -> Unit,
    onClose: () -> Unit,
) {
    var refused by remember(file.media) { mutableStateOf(false) }
    val link by produceState<StreamLink?>(initialValue = null, file.media, videoUrl) {
        value = StreamLink(videoUrl?.invoke(file))
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = VIEWER_SCRIM))) {
        Column(Modifier.fillMaxSize()) {
            ViewerHeader(file.name, refused, onClose) {
                if (canDownload()) onDownload(file) else refused = true
            }
            ZillitVideoView(
                url = link?.url,
                // Resolved, and there was nothing to resolve to.
                failed = link != null && link?.url == null,
                onOpenOutside = {
                    // The viewer first: a dead player left standing behind the
                    // OS's window is the next thing the user comes back to.
                    onClose()
                    onOpenOutside(file)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** Distinguishes "not resolved yet" (null) from "resolved to nothing" (url null). */
private data class StreamLink(val url: String?)

/** Android `msg_download_right` — `res/values/strings.xml:7884`, verbatim. */
internal val DOWNLOAD_REFUSED: String get() = str(S.msg_download_right)

private const val VIEWER_SCRIM = 0.86f
private val VIEWER_PADDING = 32.dp
