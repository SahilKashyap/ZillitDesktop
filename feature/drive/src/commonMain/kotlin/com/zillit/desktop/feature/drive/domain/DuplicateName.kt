package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.common.ZillitError

/**
 * Whether the server refused because a sibling already has this name.
 *
 * It answers `status:0` with a message **key** — `duplicate_file_name` for a
 * file, `duplicate_folder_name` for a folder, `duplicate_name` where it does
 * not say which (Android's `isDuplicateNameError`). Matched on the key rather
 * than on translated text, and loosely: a network layer that put the whole
 * response body in the message still contains it.
 */
fun ZillitError.isDuplicateName(): Boolean {
    val key = ((this as? ZillitError.Http)?.serverMessage ?: return false).lowercase()
    return DUPLICATE_KEYS.any { it in key }
}

private val DUPLICATE_KEYS = listOf("duplicate_file_name", "duplicate_folder_name", "duplicate_name")

/**
 * A name close to [name] that is not in [taken]: `report.pdf` → `report (1).pdf`.
 *
 * Offered as the starting point in the rename box, never applied silently —
 * the user might want a different name entirely. The extension stays last so
 * the format cannot change, and comparison ignores case because the server's
 * duplicate test does.
 */
fun suggestFreeName(name: String, taken: Set<String>): String {
    val used = taken.map { it.lowercase() }.toSet()
    val dot = name.lastIndexOf('.').takeIf { it > 0 }
    val stem = dot?.let { name.substring(0, it) } ?: name
    val extension = dot?.let { name.substring(it) }.orEmpty()
    var counter = 1
    while (true) {
        val candidate = "$stem ($counter)$extension"
        if (candidate.lowercase() !in used) return candidate
        counter++
    }
}

/**
 * The name to send for what the user typed into a file's rename box: trimmed,
 * and keeping the original extension (ZL-18310 — a rename cannot change the
 * format). A name that already ends in it is left alone.
 */
fun keepExtension(typed: String, original: String): String {
    val name = typed.trim()
    val extension = original.substringAfterLast('.', "")
    if (extension.isBlank() || name.isBlank()) return name
    return if (name.lowercase().endsWith(".${extension.lowercase()}")) name else "$name.$extension"
}
