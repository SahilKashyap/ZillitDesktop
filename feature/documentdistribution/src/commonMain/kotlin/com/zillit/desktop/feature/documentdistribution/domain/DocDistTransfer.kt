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
 * the Downloads folder, the clipboard, the mail service's signatures, and the
 * production's crew and departments.
 * Every member has a harmless default so tests need none of it.
 */
interface DocDistHost {

    /** Empty when the user cancelled. */
    suspend fun pickFiles(): List<LocalFile> = emptyList()

    /**
     * A folder chosen in the OS dialog, walked but not read; null when the user
     * cancelled. Each file's bytes are read when its upload comes round.
     */
    suspend fun pickFolder(): LocalFolderTree? = null

    /** Page 1 of a PDF as a JPEG for a card cover; null when it cannot be rendered. */
    suspend fun pdfThumbnail(pdf: ByteArray): ByteArray? = null

    /** How many pages a PDF has; 0 when it cannot be read, which sends the preview down the all-at-once path. */
    suspend fun pdfPageCount(pdf: ByteArray): Int = 0

    /** One page (1-based) as PNG bytes, [widthPx] wide; null when it cannot be drawn. */
    suspend fun renderPdfPage(pdf: ByteArray, page: Int, widthPx: Int): ByteArray? = null

    /** Each page as PNG bytes, [targetWidthPx] wide. */
    fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> =
        ZillitResult.Failure(ZillitError.Storage("no PDF renderer", str(S.desktop_docdist_pdf_preview_unavailable)))

    /** Writes into the user's Downloads folder; answers the path written. */
    suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> =
        ZillitResult.Failure(ZillitError.Storage("no download folder", str(S.desktop_docdist_downloads_unavailable)))

    /**
     * Joins finished PDFs end to end, in order. Only used when a merge asks
     * for a clean copy of its own as well as stamped crew copies, which the
     * server cannot build in one call.
     */
    suspend fun joinPdfs(parts: List<ByteArray>): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Storage("no PDF joiner", str(S.desktop_docdist_merge_failed)))

    /**
     * Opens a PDF in the OS viewer from a throwaway location, for printing.
     * Not the Downloads folder: a print run is not a file the user asked to keep.
     */
    suspend fun openForPrinting(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Storage("no viewer", str(S.desktop_docdist_downloads_unavailable)))

    /** Hands a saved file to the OS. */
    fun openFile(path: String) {}

    fun copyToClipboard(text: String) {}

    /** The person's saved sign-offs; empty when the mail service has none. */
    suspend fun signatures(): List<DocDistSignature> = emptyList()

    /**
     * The production's crew, from the project context the app already holds
     * — the composer suggests them beside saved contacts, and the Watermark
     * settings dialog names who last saved. Never a request of its own.
     */
    fun crew(): List<DocDistCrewMember> = emptyList()

    /**
     * The production's department names, translated and in display order —
     * suggestions for the address book's Department field, which stays free
     * text. Empty when they cannot be read.
     */
    suspend fun departments(): List<String> = emptyList()

    companion object {
        val None: DocDistHost = object : DocDistHost {}
    }
}
