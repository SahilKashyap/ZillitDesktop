package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.EditTool
import com.zillit.desktop.core.media.EditableImageCanvas
import com.zillit.desktop.core.media.ImageEditState
import com.zillit.desktop.core.media.PenToolbar
import com.zillit.desktop.core.media.encodeImageJpeg
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeDraft
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.domain.PickedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phones' "Image Reply": someone's picture, drawn on, and posted back as
 * a new picture with a caption.
 *
 * On Android the menu item opens the gallery editor over a downloaded copy
 * and hands the result to the ordinary media send (`handleImageReply` →
 * `uploadFilesInDb`); iOS does the same through its photo editor. The
 * desktop's editor is the useful subset — a pen in a few colours and
 * widths, undo, clear — because a circled prop or an arrow to a door is
 * what people actually draw on a call-sheet photo; the phones' stickers
 * and crops are not the point of the feature. The pen itself is
 * `core:media`'s, the same one the picked-media preview draws with, so the
 * desktop has one pen wherever a picture gets marked up.
 *
 * Kept composed with [target] null so the shell's exit can play; the last
 * target is remembered so the fading card still has content.
 */
@Composable
internal fun ImageReplyDialog(
    target: Notice?,
    media: NoticeMediaSource?,
    onPost: (PickedMedia, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    if (target != null) shown = target
    val notice = shown ?: return
    val editor = remember(notice.id) { ImageEditState() }
    var caption by remember(notice.id) { mutableStateOf("") }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    // The full-size picture — not the thumbnail: the strokes are composited
    // onto what gets posted, and posting a marked-up thumbnail would send
    // the crew a postage stamp.
    LaunchedEffect(notice.id) {
        val attachment = notice.attachment ?: return@LaunchedEffect
        val fetched = media?.fetch(attachment, preview = false)
        (fetched as? ZillitResult.Success)?.data?.let(::decodeImageBitmap)?.let(editor::load)
    }
    var posting by remember(notice.id) { mutableStateOf(false) }
    val captionOverLimit = caption.length > NoticeDraft.MAX_LENGTH

    ZillitDialogShell(
        title = "Image Reply",
        subtitle = "Draw on the picture and post it back to the board.",
        icon = ZillitIcons.Photo,
        visible = target != null,
        onDismiss = onDismiss,
        width = EDITOR_WIDTH,
        maxHeight = EDITOR_MAX_HEIGHT,
        // The canvas takes whatever height the dialog gets and the caption
        // sits under it; a fixed-height canvas in a scrolling body pushed the
        // caption below the fold on a laptop screen (seen live).
        scrollable = false,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (posting) "Posting…" else "Post",
                enabled = editor.working != null && !posting && !captionOverLimit,
                onClick = {
                    // Composited on the UI thread — the picture is already
                    // decoded and the strokes are few; the encode, which is
                    // the real work, goes off it so the dialog does not freeze.
                    val composited = editor.render(measurer) ?: return@ZillitButton
                    posting = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.Default) { encodeImageJpeg(composited, JPEG_QUALITY) }
                        posting = false
                        if (bytes != null) {
                            onPost(PickedMedia(IMAGE_REPLY_NAME, "image/jpeg", bytes), caption.trim())
                        }
                    }
                },
            )
        },
    ) {
        PenToolbar(editor.pen)
        EditableImageCanvas(editor, EditTool.Draw, Modifier.weight(1f), measurer)
        ZillitTextField(
            value = caption,
            onValueChange = { caption = it },
            placeholder = "Add a caption…",
            singleLine = false,
            maxLength = NoticeDraft.MAX_LENGTH,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val JPEG_QUALITY = 90
private const val IMAGE_REPLY_NAME = "image-reply.jpg"

private val EDITOR_WIDTH = 760.dp
private val EDITOR_MAX_HEIGHT = 760.dp
