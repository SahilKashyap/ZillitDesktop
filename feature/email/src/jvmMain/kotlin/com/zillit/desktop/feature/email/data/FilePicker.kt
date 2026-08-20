package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.email.domain.PickedFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system file chooser.
 *
 * AWT's `FileDialog` rather than Swing's `JFileChooser`: it is the *native*
 * dialog on macOS and Windows, so it looks like every other Open dialog on the
 * machine and understands the sidebar, recents and tags the user already has.
 * `JFileChooser` draws its own, and on macOS it looks a decade out of date.
 */
class FilePicker(private val maxBytes: Long = MAX_ATTACHMENT_BYTES) {

    /** Null when the user cancelled, or the file could not be read. */
    suspend fun pick(): List<PickedFile> = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true

        dialog.files.orEmpty().mapNotNull(::read)
    }

    private fun read(file: File): PickedFile? = try {
        when {
            !file.isFile -> null

            // Refused before reading, not after: loading a 2 GB file into
            // memory to then reject it would hang the app first.
            file.length() > maxBytes -> {
                ZillitLog.w(TAG) { "attachment refused: ${file.length()} bytes" }
                null
            }

            else -> PickedFile(
                name = file.name,
                // The JRE's table first, then our own: on Windows the JRE's
                // content-types table misses common types, and a photo typed
                // `application/octet-stream` classifies as a document — no
                // preview, no edit tools, a file chip on the board.
                contentType = URLConnection.guessContentTypeFromName(file.name)
                    ?: KNOWN_TYPES[file.extension.lowercase()]
                    ?: "application/octet-stream",
                bytes = file.readBytes(),
            )
        }
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // The path is not logged: it is the user's business.
        ZillitLog.e(TAG, throwable) { "could not read the chosen file" }
        null
    }

    private companion object {
        const val TAG = "Email"

        /**
         * 25 MB, which is where most mail servers stop accepting.
         *
         * Enforced here so the refusal happens at the picker, rather than after
         * an upload the server will not deliver.
         */
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

        /** The types the boards care about, by extension — see the read note. */
        val KNOWN_TYPES = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "heic" to "image/heic",
            "bmp" to "image/bmp",
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "avi" to "video/x-msvideo",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "pdf" to "application/pdf",
        )
    }
}
