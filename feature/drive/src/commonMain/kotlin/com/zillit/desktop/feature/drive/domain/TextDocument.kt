package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.common.ZillitError

/**
 * The files Drive reads and writes as text itself — `markdownUtils.js`.
 *
 * Markdown and plain text never reach Zillit Sheets / Docs / Slides: the
 * config call refuses them with `file_type_not_editable`, because those
 * editors serve office formats. They open in Drive's own viewer instead, over
 * `GET`/`PUT /files/{id}/text`, which is also the only way the desktop can
 * *edit* one — an editor it cannot save through is a broken feature, not a
 * missing one.
 */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")

/** Plain text opens in the same viewer, shown as written — no Markdown. */
private val PLAIN_TEXT_EXTENSIONS = setOf("txt")

/** `.md` / `.markdown` — rendered in the viewer, with the source beside it. */
val DriveItem.isMarkdownDocument: Boolean
    get() = !isFolder && extensionKey in MARKDOWN_EXTENSIONS

/** `.txt` — the same viewer, shown exactly as written (REL-BUG-10). */
val DriveItem.isPlainTextDocument: Boolean
    get() = !isFolder && extensionKey in PLAIN_TEXT_EXTENSIONS

/** Whether this opens in Drive's own text viewer rather than an editor. */
val DriveItem.isTextDocument: Boolean
    get() = isMarkdownDocument || isPlainTextDocument

private val DriveItem.extensionKey: String
    get() = extension.lowercase().trimStart('.').ifBlank { name.substringAfterLast('.', "").lowercase() }

/**
 * The largest text the server will store on this route. Checked before the
 * save so an over-long file costs no round trip.
 */
const val TEXT_DOCUMENT_MAX_BYTES: Int = 2 * 1024 * 1024

/** The size the server measures — UTF-8 bytes, as the web's `Blob` does. */
fun textByteLength(text: String): Int = text.encodeToByteArray().size

/**
 * Whether the server refused a save because the file moved on since it was
 * opened (`file_changed_since_opened`, answered as a 409).
 *
 * Matched on the key rather than on translated text, and loosely: a network
 * layer that put the whole body in `message` still contains it.
 */
fun ZillitError.isChangedSinceOpened(): Boolean {
    val message = (this as? ZillitError.Http)?.serverMessage
        ?: (this as? ZillitError.Forbidden)?.technical
        ?: return false
    return CHANGED_KEY in message.lowercase()
}

private const val CHANGED_KEY = "file_changed_since_opened"
