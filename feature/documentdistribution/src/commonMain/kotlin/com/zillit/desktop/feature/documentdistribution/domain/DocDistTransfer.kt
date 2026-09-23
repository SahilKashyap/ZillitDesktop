package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The byte-level I/O the repository cannot do through the envelope client.
 *
 * Injected from the host because each piece is the platform's: storage
 * PUTs are signed with the workspace's AWS keys, the signed fetch is the
 * app's, and the doc-dist routes that answer a file rather than an envelope
 * (`/watermarked`, `/watermark-zip`, `/presets/:id/export`, `/raw`) need the
 * encrypted headers on a raw HTTP call. The default refuses everything with
 * a reason, which is what a test host and a workspace with no storage get.
 */
interface DocDistTransfer {

    /** PUTs bytes into the production's storage under [key]; answers where they landed. */
    suspend fun putObject(key: String, contentType: String, bytes: ByteArray): ZillitResult<DocumentStorage> =
        unsupported(str(S.desktop_docdist_uploads_unavailable_no_storage))

    /** Reads an object the listing named, through the app's signed fetch. */
    suspend fun fetchObject(storage: DocumentStorage): ZillitResult<ByteArray> =
        unsupported(str(S.desktop_docdist_no_file_storage_open))

    /** A signed GET on the doc-dist service whose answer is a file. */
    suspend fun getBytes(url: String): ZillitResult<ByteArray> =
        unsupported(str(S.desktop_docdist_action_unavailable_desktop))

    /** A signed POST whose answer is a file. */
    suspend fun postBytes(url: String,
        body: JsonObject): ZillitResult<ByteArray> = unsupported(str(S.desktop_docdist_action_unavailable_desktop))

    /** A signed multipart POST — the LOCAL storage upload path. */
    suspend fun postMultipart(url: String, fields: Map<String, String>, file: LocalFile): ZillitResult<JsonElement> =
        unsupported(str(S.desktop_docdist_uploads_server_library_unavailable))

    companion object {
        /** No I/O at all — tests, and hosts that have not wired storage. */
        val None: DocDistTransfer = object : DocDistTransfer {}

        private fun <T> unsupported(reason: String): ZillitResult<T> =
            ZillitResult.Failure(ZillitError.Storage(technical = "DocDistTransfer not wired", userMessage = reason))
    }
}

/**
 * What the view model asks the machine for: a file dialog, a PDF rasteriser,
 * the Downloads folder, the clipboard, and the mail service's signatures.
 * Every member has a harmless default so tests need none of it.
 */
interface DocDistHost {

    /** Empty when the user cancelled. */
    suspend fun pickFiles(): List<LocalFile> = emptyList()

    /** Each page as PNG bytes, [targetWidthPx] wide. */
    fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> =
        ZillitResult.Failure(ZillitError.Storage("no PDF renderer", str(S.desktop_docdist_pdf_preview_unavailable)))

    /** Writes into the user's Downloads folder; answers the path written. */
    suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> =
        ZillitResult.Failure(ZillitError.Storage("no download folder", str(S.desktop_docdist_downloads_unavailable)))

    /** Hands a saved file to the OS. */
    fun openFile(path: String) {}

    fun copyToClipboard(text: String) {}

    /** The person's saved sign-offs; empty when the mail service has none. */
    suspend fun signatures(): List<DocDistSignature> = emptyList()

    companion object {
        val None: DocDistHost = object : DocDistHost {}
    }
}
