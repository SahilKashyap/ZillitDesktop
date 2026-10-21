package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * How a file is drawn inside the app, once its bytes are in hand.
 *
 * Everything here renders in the app's own window: a picture is decoded, a
 * PDF is rasterised, text is read as text. Nothing is handed to the operating
 * system — a production's call sheet should open where the conversation about
 * it is, not in whatever the machine keeps for `.pdf`.
 */
sealed interface DocumentBody {
    data class Picture(val image: ImageBitmap) : DocumentBody

    /** A PDF, every page rendered (up to a cap) and stacked to scroll. */
    data class Pages(val pages: List<ImageBitmap>) : DocumentBody

    data class Text(val text: String) : DocumentBody

    /** Nothing the app can draw — a spreadsheet, an archive; the download is the way in. */
    data object Unsupported : DocumentBody
}

/** What a viewer has of a file: nothing yet, a refusal, or something to draw. */
sealed interface DocumentState {
    data object Loading : DocumentState

    /** The bytes never arrived — no credentials, no object, no network. */
    data object Failed : DocumentState

    data class Ready(val body: DocumentBody) : DocumentState
}

/**
 * Decides how [bytes] are shown, and renders them.
 *
 * The same seam every surface that shows a received file used to carry
 * privately (mail's `renderAttachmentPreview`); it lives here so the mailbox,
 * the notice board and the chat thread share one — and so a format added here
 * opens everywhere at once.
 *
 * Slow for a long PDF, so callers run it off the UI thread — see
 * [rememberDocument], which does.
 */
fun renderDocumentBody(fileName: String, contentType: String?, bytes: ByteArray): DocumentBody {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val type = contentType.orEmpty().lowercase()
    return when {
        bytes.isEmpty() -> DocumentBody.Unsupported
        // The magic number first: a PDF sent as `application/octet-stream`
        // with no extension is still a PDF.
        bytes.startsWith(PDF_MAGIC) || extension == "pdf" || type == "application/pdf" ->
            renderPdfPages(bytes).takeIf { it.isNotEmpty() }?.let(DocumentBody::Pages)
                ?: DocumentBody.Unsupported
        extension in IMAGE_EXTENSIONS || type.startsWith("image/") ->
            decodeImageBitmap(bytes)?.let(DocumentBody::Picture) ?: DocumentBody.Unsupported
        extension in TEXT_EXTENSIONS || type.startsWith("text/") ->
            DocumentBody.Text(bytes.decodeToString().take(MAX_TEXT_CHARS))
        else -> DocumentBody.Unsupported
    }
}

/**
 * A PDF's pages at reading resolution; empty when it will not open
 * (encrypted, truncated). Capped, so a 300-page appendix does not hold the
 * viewer while it rasterises the lot.
 */
internal expect fun renderPdfPages(bytes: ByteArray): List<ImageBitmap>

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val PDF_MAGIC = "%PDF".encodeToByteArray()
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val TEXT_EXTENSIONS = setOf("txt", "csv", "tsv", "log", "md", "json", "xml", "ics", "vcf")

/** Enough to read; a multi-megabyte log is not laid out in one go. */
private const val MAX_TEXT_CHARS = 200_000
