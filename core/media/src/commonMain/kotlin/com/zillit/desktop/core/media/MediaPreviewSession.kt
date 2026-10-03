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
class MediaPreviewSession(
    items: List<PreviewItem>,
    caption: String = "",
    /**
     * One caption per item rather than one for all — chat sends each file as
     * its own message, and WhatsApp captions each; a board post has one text.
     */
    val captionPerItem: Boolean = false,
) {
    var items by mutableStateOf(items)
        private set
    var index by mutableStateOf(0)
        private set
    private var sharedCaption by mutableStateOf(caption)
    private val itemCaptions = mutableStateMapOf<PreviewItem, String>()

    /** The caption the field shows: the current item's, or the one shared by all. */
    var caption: String
        get() = if (captionPerItem) current?.let { itemCaptions[it] }.orEmpty() else sharedCaption
        set(value) {
            if (!captionPerItem) {
                sharedCaption = value
            } else {
                current?.let { itemCaptions[it] = value }
            }
        }

    /** The longest caption in play — what Send weighs against the limit. */
    val longestCaption: Int
        get() = if (captionPerItem) itemCaptions.values.maxOfOrNull { it.length } ?: 0 else sharedCaption.length

    /** Posters for the files that are not pictures, as the app's maker answers them. */
    val posters = mutableStateMapOf<PreviewItem, PreviewPoster?>()

    /** Items the user took out here — a host still listing them must not bring them back. */
    private val removed = mutableSetOf<PreviewItem>()

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
        itemCaptions.remove(item)
        posters.remove(item)
        removed += item
        items = items - item
        index = index.coerceAtMost(items.lastIndex)
        tool = null
    }

    /**
     * Takes in what the host lists now that this session has not seen — the
     * strip's "+" adding files while the preview is open — and shows the
     * first of them. Items removed here stay removed; nothing else moves.
     */
    fun sync(listed: List<PreviewItem>) {
        val fresh = listed.filter { it !in items && it !in removed }
        if (fresh.isEmpty()) return
        items = items + fresh
        index = items.indexOf(fresh.first())
        tool = null
    }

    /**
     * Every item as it will send: edited pictures re-encoded, the rest as
     * they came. Real work for a large photo — callers run it off the UI
     * thread. Null when a picture that was edited could not be encoded.
     */
    fun results(measurer: TextMeasurer, jpegQuality: Int = JPEG_QUALITY): List<PreviewResult>? =
        items.map { item ->
            val result = resultFor(item, measurer, jpegQuality) ?: return null
            if (captionPerItem) result.copy(caption = itemCaptions[item].orEmpty().trim()) else result
        }

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
