package com.zillit.desktop.feature.chat.ui

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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import com.zillit.desktop.feature.chat.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bubble menu's "Image Reply": someone's picture, drawn on, and sent
 * back into the thread as a new picture with a caption — the web's
 * `ImageReplyHandler` (`cnc_latest/components/ImageReplyModal.jsx`), which
 * uploads the edited file and sends it as an ordinary image message, not a
 * threaded reply.
 *
 * The Home board's dialog with the chat's types in place of the notice's:
 * the same `core:media` pen, so the desktop has one pen wherever a picture
 * gets marked up. Kept composed with [target] null so the shell's exit can
 * play; the last target is remembered so the fading card still has content.
 */
@Composable
internal fun ChatImageReplyDialog(
    target: ChatMessage?,
    loadImage: suspend (ChatAttachment) -> ImageBitmap?,
    onPost: (name: String, contentType: String, bytes: ByteArray, caption: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    if (target != null) shown = target
    val message = shown ?: return
    val editor = remember(message.id) { ImageEditState() }
    var caption by remember(message.id) { mutableStateOf("") }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    // The full-size picture, not the thumbnail: the strokes are composited
    // onto what gets sent, and a marked-up poster would send a postage stamp.
    LaunchedEffect(message.id) {
        val file = message.attachment ?: return@LaunchedEffect
        loadImage(file)?.let(editor::load)
    }
    var posting by remember(message.id) { mutableStateOf(false) }
    val captionOverLimit = ChatComposerRules.bodyTooLong(caption.trim())

    ZillitDialogShell(
        title = str(S.image_reply),
        subtitle = str(S.desktop_chat_image_reply_subtitle),
        icon = ZillitIcons.Photo,
        visible = target != null,
        onDismiss = onDismiss,
        width = EDITOR_WIDTH,
        maxHeight = EDITOR_MAX_HEIGHT,
        // The canvas takes the dialog's height and the caption sits under
        // it — a fixed canvas in a scrolling body pushed the caption below
        // the fold on a laptop screen (the Home board's lesson).
        scrollable = false,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), onClick = onDismiss, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (posting) str(S.dm_nda_sending) else str(S.send),
                enabled = editor.working != null && !posting && !captionOverLimit,
                onClick = {
                    val composited = editor.render(measurer) ?: return@ZillitButton
                    posting = true
                    scope.launch {
                        // The encode is the real work; off the UI thread.
                        val bytes = withContext(Dispatchers.Default) { encodeImageJpeg(composited, JPEG_QUALITY) }
                        posting = false
                        if (bytes != null) onPost(IMAGE_REPLY_NAME, "image/jpeg", bytes, caption.trim())
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
            placeholder = str(S.desktop_media_add_caption),
            singleLine = false,
            maxLength = ChatComposerRules.MAX_BODY_CHARS,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val JPEG_QUALITY = 90
private const val IMAGE_REPLY_NAME = "image-reply.jpg"

private val EDITOR_WIDTH = 760.dp
private val EDITOR_MAX_HEIGHT = 760.dp
