package com.zillit.desktop.core.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer

/**
 * One visit to the preview dialog: the items still selected, which one is
 * on screen, the caption, and each picture's edits.
 *
 * Android's `GalleryItemView` keeps the same set — a pager of items, a
 * caption field, and the edited copy written back per item
 * (`GalleryViewer.kt:143-147`). Held outside the composables so switching
 * item, tool and mode keeps every picture's edits.
 */
class MediaPreviewSession(items: List<PreviewItem>, caption: String = "") {
    var items by mutableStateOf(items)
        private set
    var index by mutableStateOf(0)
        private set
    var caption by mutableStateOf(caption)

    /** Whether the editor is open over the current picture, and with which tool. */
    var tool by mutableStateOf<EditTool?>(null)

    private val edits = mutableStateMapOf<PreviewItem, ImageEditState>()

    /** Pictures whose bytes would not decode — previewed as files, never edited. */
    var undecodable by mutableStateOf<Set<PreviewItem>>(emptySet())
        private set

    val current: PreviewItem? get() = items.getOrNull(index)

    /** Whether the item previews as a picture with the editor behind it. */
    fun isEditable(item: PreviewItem): Boolean = item.kind == PreviewKind.Image && item !in undecodable

    /** The current picture's edits — created on first ask; only images have any. */
    val currentEdit: ImageEditState?
        get() = current?.takeIf(::isEditable)?.let(::editFor)

    fun editFor(item: PreviewItem): ImageEditState = edits.getOrPut(item) { ImageEditState() }

    fun markUndecodable(item: PreviewItem) {
        undecodable = undecodable + item
    }

    fun select(at: Int) {
        if (at in items.indices) {
            index = at
            tool = null
        }
    }

    /**
     * Drops the current item — Android's delete, offered only while more
     * than one is selected (`GalleryViewer.kt:779`); the last item is
     * removed by cancelling the dialog instead.
     */
    fun removeCurrent() {
        val item = current ?: return
        if (items.size <= 1) return
        edits.remove(item)
        items = items - item
        index = index.coerceAtMost(items.lastIndex)
        tool = null
    }

    /**
     * Every item as it will send: edited pictures re-encoded, the rest as
     * they came. Real work for a large photo — callers run it off the UI
     * thread. Null when a picture that was edited could not be encoded.
     */
    fun results(measurer: TextMeasurer, jpegQuality: Int = JPEG_QUALITY): List<PreviewResult>? =
        items.map { item -> resultFor(item, measurer, jpegQuality) ?: return null }

    private fun resultFor(item: PreviewItem, measurer: TextMeasurer, jpegQuality: Int): PreviewResult? {
        val edit = edits[item]?.takeIf { it.isEdited } ?: return item.asResult()
        val rendered = edit.render(measurer) ?: return item.asResult()
        val encoding =
            if (item.contentType.equals(PNG_TYPE, ignoreCase = true)) ImageEncoding.Png else ImageEncoding.Jpeg
        val bytes = encodeImage(rendered, encoding, jpegQuality) ?: return null
        return when (encoding) {
            ImageEncoding.Png -> PreviewResult(item.name, PNG_TYPE, bytes)
            ImageEncoding.Jpeg -> PreviewResult(item.name.withExtension("jpg"), JPEG_TYPE, bytes)
        }
    }

    private companion object {
        const val JPEG_QUALITY = 90
        const val PNG_TYPE = "image/png"
        const val JPEG_TYPE = "image/jpeg"
    }
}

/** `door.heic` edited becomes `door.jpg` — the bytes changed format, so the name says so. */
private fun String.withExtension(extension: String): String {
    val stem = substringBeforeLast('.', missingDelimiterValue = this).ifBlank { this }
    return "$stem.$extension"
}
