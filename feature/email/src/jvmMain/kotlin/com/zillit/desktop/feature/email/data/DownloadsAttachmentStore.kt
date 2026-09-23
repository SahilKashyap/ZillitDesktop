package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.AttachmentStore
import com.zillit.desktop.feature.email.domain.safeFileName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saves attachments into the user's Downloads folder.
 *
 * Downloads rather than a location the app picked: it is where every other
 * download on the machine lands, it is already in the user's muscle memory, and
 * it is somewhere they can find things without the app's help.
 *
 * ## What this deliberately does not do
 *
 * It does not open what it writes. Mail attachments are the single most common
 * malware vector there is, and a client that opens them on the user's behalf
 * has made the decision to run untrusted code for them. The path is reported;
 * opening it stays a human action.
 */
class DownloadsAttachmentStore(
    private val directory: File = File(System.getProperty("user.home"), "Downloads"),
) : AttachmentStore {

    override suspend fun save(fileName: String, bytes: ByteArray): ZillitResult<String> =
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()

                // Sanitised before it is joined to anything. See `safeFileName`:
                // the name comes from a mail header, so it comes from whoever
                // sent the mail.
                val target = directory.uniqueFile(safeFileName(fileName))

                // Re-checked after the join rather than trusted from the
                // sanitiser alone. Belt and braces on the one bug in this file
                // that would be catastrophic rather than annoying.
                if (target.parentFile?.canonicalFile != directory.canonicalFile) {
                    ZillitLog.w(TAG) { "refusing to write outside the downloads folder" }
                    return@withContext ZillitResult.Failure(
                        ZillitError.Storage(
                            technical = "attachment name is not safe to save",
                            userMessage = str(S.desktop_email_unsafe_file_name),
                        ),
                    )
                }

                target.writeBytes(bytes)
                ZillitLog.i(TAG) { "saved attachment (${bytes.size} bytes)" }
                ZillitResult.Success(target.absolutePath)
            } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
                // Path is not logged: it contains the filename, and a filename
                // can carry as much of the sender's business as the subject.
                ZillitLog.e(TAG, throwable) { "could not save attachment" }
                ZillitResult.Failure(
                    ZillitError.Storage(
                        technical = "could not save: ${throwable::class.simpleName}",
                        userMessage = str(S.desktop_email_downloads_save_failed),
                    ),
                )
            }
        }

    /**
     * `report.pdf`, then `report (2).pdf`, and so on.
     *
     * Never overwrites. Downloading the same attachment twice is something
     * people do by accident, and silently replacing a file the user had already
     * opened and edited is not recoverable.
     */
    private fun File.uniqueFile(name: String): File {
        val first = File(this, name)
        if (!first.exists()) return first

        val stem = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }

        // Bounded: a directory holding thousands of copies of one name is a bug
        // somewhere else, and an unbounded loop here would hang the download.
        for (index in 2..MAX_ATTEMPTS) {
            val candidate = File(this, "$stem ($index)$suffix")
            if (!candidate.exists()) return candidate
        }
        error("too many files named $stem")
    }

    private companion object {
        const val TAG = "Email"
        const val MAX_ATTEMPTS = 1000
    }
}
