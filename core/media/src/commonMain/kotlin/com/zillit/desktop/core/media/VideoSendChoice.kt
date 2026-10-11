package com.zillit.desktop.core.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** 100 MB: a video above it asks how it should go; smaller ones go as they are. */
const val LARGE_VIDEO_BYTES: Long = 100L * 1024 * 1024

/** How a large video leaves: re-encoded smaller first, or byte for byte. */
enum class VideoSend { Compressed, Original }

/** Whether the sender must choose how this file goes — a video over [LARGE_VIDEO_BYTES]. */
val PreviewItem.asksVideoChoice: Boolean
    get() = kind == PreviewKind.Video && bytes.size > LARGE_VIDEO_BYTES

/**
 * Over the caption while a large video — or one most players cannot draw —
 * is on screen: why it asks, and the two ways it can go. Neither is
 * preselected — the sender picks each time, and Send waits until every such
 * video has an answer.
 */
@Composable
internal fun VideoChoiceBar(
    item: PreviewItem,
    choice: VideoSend?,
    /** "AV1" when the video will not play for most people as it is — said instead of its size. */
    unplayableFormat: String?,
    onChoose: (VideoSend) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
    ) {
        ZillitText(
            text = unplayableFormat?.let { str(S.desktop_video_choice_unplayable, it) }
                ?: str(S.desktop_video_choice_prompt, formatBytes(item.bytes.size.toLong())),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.widthIn(max = PROMPT_MAX_WIDTH),
        )
        ChoiceButton(str(S.desktop_video_compress), selected = choice == VideoSend.Compressed) {
            onChoose(VideoSend.Compressed)
        }
        ChoiceButton(str(S.desktop_video_send_original), selected = choice == VideoSend.Original) {
            onChoose(VideoSend.Original)
        }
    }
}

/** One half of the choice: filled when chosen, outlined when not. */
@Composable
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = if (selected) ButtonVariant.Primary else ButtonVariant.Secondary,
        size = ButtonSize.Small,
    )
}

private val PROMPT_MAX_WIDTH = 360.dp
