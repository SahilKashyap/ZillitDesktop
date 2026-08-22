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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.chat.domain.ChatAttachment

/**
 * The in-app lightbox a clicked picture opens — the Home board's
 * `MediaLightbox` (feature/home NoticeMediaContent.kt:508) in chat's frame:
 * the image over a scrim, the filename, and a Download that is a *right*,
 * not a given. Saving to disk is what the download permission governs, so
 * looking is free but the button asks [ChatSeams.canDownload] first and
 * refuses with Android's own sentence (`msg_download_right`,
 * `res/values/strings.xml:7884`) when the answer is no.
 */
@Composable
internal fun ChatMediaViewer(
    file: ChatAttachment,
    loadImage: suspend (ChatAttachment) -> ImageBitmap?,
    canDownload: () -> Boolean,
    onDownload: (ChatAttachment) -> Unit,
    onClose: () -> Unit,
) {
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
                        text = "Loading…",
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
            text = "Download",
            onClick = onDownload,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Download,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close viewer",
            onClick = onClose,
            tint = Color.White,
        )
    }
}

/** Android `msg_download_right` — `res/values/strings.xml:7884`, verbatim. */
internal const val DOWNLOAD_REFUSED = "You do not have Downloading Rights"

private const val VIEWER_SCRIM = 0.86f
private val VIEWER_PADDING = 32.dp
