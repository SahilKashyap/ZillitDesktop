package com.zillit.desktop.core.media

/**
 * How a picked file previews and which tools it gets — the same split
 * Android's gallery viewer makes on the file's MIME type: the `image` family
 * gets the editor and the HD toggle, everything else a plain preview
 * (`GalleryViewer.kt:761-767`).
 */
enum class PreviewKind {
    Image,
    Video,
    Audio,
    Document,
    ;

    companion object {
        /** Classified the way every composer already classifies uploads — by MIME prefix. */
        fun of(contentType: String): PreviewKind = when {
            contentType.startsWith("image/", ignoreCase = true) -> Image
            contentType.startsWith("video/", ignoreCase = true) -> Video
            contentType.startsWith("audio/", ignoreCase = true) -> Audio
            else -> Document
        }
    }
}

/**
 * A file the user picked, as the preview dialog wants it.
 *
 * Deliberately free of any feature's own media type: Home's `PickedMedia`
 * and chat's pending upload both map onto this in a line, and the dialog
 * stays reusable by every composer. [thumbnailBytes] is a poster frame for
 * videos and PDFs, when the picker made one — shown in place of a file glyph.
 */
data class PreviewItem(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
    val thumbnailBytes: ByteArray? = null,
    val kind: PreviewKind = PreviewKind.of(contentType),
) {
    override fun toString(): String = "PreviewItem(name=$name, type=$contentType, size=${bytes.size})"

    /** Identity, not content — a 200 MB video must not be compared byte-wise. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = name.hashCode()
}

/**
 * What the dialog hands back per item on Send: the original bytes for
 * anything left alone, or the edited picture re-encoded — with the name and
 * type it now has (an edited PNG stays PNG; everything else edited becomes
 * JPEG, as both phones' editors write JPEG).
 */
data class PreviewResult(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
    val thumbnailBytes: ByteArray? = null,
) {
    override fun toString(): String = "PreviewResult(name=$name, type=$contentType, size=${bytes.size})"
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = name.hashCode()
}

/** The untouched item, passed through as it came. */
fun PreviewItem.asResult(): PreviewResult = PreviewResult(name, contentType, bytes, thumbnailBytes)
