package com.zillit.desktop.feature.email.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.media.DocumentBody
import com.zillit.desktop.core.media.renderDocumentBody
import com.zillit.desktop.feature.email.domain.EmailAttachment

/**
 * An attachment open in the preview, from the click until it is closed.
 *
 * The bytes themselves stay with [AttachmentDownloader] — a data class holding
 * a `ByteArray` compares by identity, and nothing on screen needs them.
 */
data class AttachmentPreview(
    val attachment: EmailAttachment,
    val messageId: String,
    val folderName: String,
    /** Set when the fetch or the decode refused; the dialog says so in place of the file. */
    val failed: String? = null,
    /** Null while the bytes are on their way. */
    val body: PreviewBody? = null,
) {
    val isLoading: Boolean get() = failed == null && body == null
}

/** What the preview draws once the bytes are in. */
sealed interface PreviewBody {
    data class Picture(val image: ImageBitmap) : PreviewBody

    /** A PDF, every page rendered (up to a cap) and stacked. */
    data class Pages(val pages: List<ImageBitmap>) : PreviewBody

    data class Text(val text: String) : PreviewBody

    /** Nothing the app can draw — a spreadsheet, an archive; Download is the way in. */
    data object Unsupported : PreviewBody
}

/**
 * Decides how an attachment is shown, and draws it.
 *
 * Everything here renders inside the app: a picture is decoded, a PDF is
 * rasterised, text is read as text. Nothing is handed to the operating system
 * to open — see `DownloadsAttachmentStore` for why mail never does that.
 *
 * The deciding and the drawing are [renderDocumentBody]'s, shared with the
 * notice board's and the chat thread's document viewers; this maps its answer
 * onto the mailbox's own [PreviewBody], which the dialog is written against.
 *
 * Slow for a long PDF, so callers run it off the UI thread.
 */
fun renderAttachmentPreview(fileName: String, contentType: String?, bytes: ByteArray): PreviewBody =
    when (val body = renderDocumentBody(fileName, contentType, bytes)) {
        is DocumentBody.Picture -> PreviewBody.Picture(body.image)
        is DocumentBody.Pages -> PreviewBody.Pages(body.pages)
        is DocumentBody.Text -> PreviewBody.Text(body.text)
        DocumentBody.Unsupported -> PreviewBody.Unsupported
    }
