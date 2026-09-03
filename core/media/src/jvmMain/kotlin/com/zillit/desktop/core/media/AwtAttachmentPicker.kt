package com.zillit.desktop.core.media

import com.zillit.desktop.core.common.ZillitLog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system file chooser, by kind.
 *
 * AWT's `FileDialog` rather than Swing's `JFileChooser`: it is the *native*
 * dialog on macOS and Windows, so it looks like every other Open dialog on the
 * machine and understands the sidebar, recents and tags the user already has.
 * `JFileChooser` draws its own, and on macOS it looks a decade out of date.
 *
 * The kind does two jobs. It filters the dialog, so "Video" shows videos —
 * advisory on macOS, which greys rather than hides. And it is checked again
 * after the choice, by extension *and* by the type the name resolves to,
 * because a filter the OS may ignore is not a rule.
 */
class AwtAttachmentPicker : AttachmentPicker {

    override suspend fun pick(
        kind: PreviewKind,
        multiple: Boolean,
        maxBytes: Long,
        onRefused: (PickRefusal) -> Unit,
    ): List<PickedFile> = withContext(Dispatchers.IO) {
        chosen(kind, multiple).mapNotNull { file -> read(file, kind, maxBytes, onRefused) }
    }

    override suspend fun pickPaths(
        kind: PreviewKind,
        multiple: Boolean,
        onRefused: (PickRefusal) -> Unit,
    ): List<PickedPath> = withContext(Dispatchers.IO) {
        chosen(kind, multiple).mapNotNull { file -> describe(file, kind, onRefused) }
    }

    private fun chosen(kind: PreviewKind, multiple: Boolean): List<File> {
        val dialog = FileDialog(null as Frame?, kind.pickerTitle, FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        kind.extensions?.let { allowed ->
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in allowed }
        }
        dialog.isVisible = true
        return dialog.files.orEmpty().filter { it.isFile }
    }

    private fun read(
        file: File,
        kind: PreviewKind,
        maxBytes: Long,
        onRefused: (PickRefusal) -> Unit,
    ): PickedFile? {
        val contentType = contentTypeFor(file.name, URLConnection.guessContentTypeFromName(file.name))
        return when {
            // Refused before reading, not after: loading a 2 GB file into
            // memory to then reject it would hang the app first.
            file.length() > maxBytes -> {
                ZillitLog.w(TAG) { "attachment refused: ${file.length()} bytes" }
                onRefused(PickRefusal.TooLarge(file.name, file.length()))
                null
            }

            !kind.admits(file.name, contentType) -> {
                ZillitLog.w(TAG) { "attachment refused: not ${kind.label.lowercase()}" }
                onRefused(PickRefusal.WrongKind(file.name, kind))
                null
            }

            else -> runCatching { PickedFile(file.name, contentType, file.readBytes()) }
                .onFailure {
                    // The path is not logged: it is the user's business.
                    ZillitLog.e(TAG, it) { "could not read the chosen file" }
                }
                .getOrNull()
        }
    }

    private fun describe(file: File, kind: PreviewKind, onRefused: (PickRefusal) -> Unit): PickedPath? {
        val contentType = contentTypeFor(file.name, URLConnection.guessContentTypeFromName(file.name))
        if (!kind.admits(file.name, contentType)) {
            onRefused(PickRefusal.WrongKind(file.name, kind))
            return null
        }
        return PickedPath(
            path = file.absolutePath,
            name = file.name,
            sizeBytes = file.length(),
            contentType = contentType,
        )
    }

    private companion object {
        const val TAG = "Attach"
    }
}
