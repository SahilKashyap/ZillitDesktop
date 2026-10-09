package com.zillit.desktop.feature.email.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.media.decodeImageBitmap
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
 * Slow for a long PDF, so callers run it off the UI thread.
 */
fun renderAttachmentPreview(fileName: String, contentType: String?, bytes: ByteArray): PreviewBody {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val type = contentType.orEmpty().lowercase()
    return when {
        bytes.isEmpty() -> PreviewBody.Unsupported
        // The magic number first: a PDF sent as `application/octet-stream`
        // with no extension is still a PDF.
        bytes.startsWith(PDF_MAGIC) || extension == "pdf" || type == "application/pdf" ->
            renderPdfPages(bytes).takeIf { it.isNotEmpty() }?.let(PreviewBody::Pages) ?: PreviewBody.Unsupported
        extension in IMAGE_EXTENSIONS || type.startsWith("image/") ->
            decodeImageBitmap(bytes)?.let(PreviewBody::Picture) ?: PreviewBody.Unsupported
        extension in TEXT_EXTENSIONS || type.startsWith("text/") ->
            PreviewBody.Text(bytes.decodeToString().take(MAX_TEXT_CHARS))
        else -> PreviewBody.Unsupported
    }
}

/**
 * A PDF's pages at reading resolution; empty when it will not open
 * (encrypted, truncated). Capped, so a 300-page appendix does not hold the
 * dialog while it rasterises the lot.
 */
internal expect fun renderPdfPages(bytes: ByteArray): List<ImageBitmap>

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val PDF_MAGIC = "%PDF".encodeToByteArray()
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val TEXT_EXTENSIONS = setOf("txt", "csv", "tsv", "log", "md", "json", "xml", "ics", "vcf")

/** Enough to read; a multi-megabyte log is not laid out in one go. */
private const val MAX_TEXT_CHARS = 200_000
