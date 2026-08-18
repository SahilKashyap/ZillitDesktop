package com.zillit.desktop.feature.pagedistribution.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The schedule-distribution / script-distribution services, both `/api/v2`,
 * addressed through a [DistributionTab] so one implementation serves three
 * tools.
 */
interface DistributionRepository {

    /** A single-list tab's documents; history includes replaced ones. */
    suspend fun documents(tab: DistributionTab, mode: ListMode): ZillitResult<List<DistDocument>>

    /** A folder tab's folder headers; history includes deleted ones. */
    suspend fun folders(tab: DistributionTab, mode: ListMode): ZillitResult<List<DistFolder>>

    /**
     * One page of a folder's documents. [beforeMs] with `previous` for the
     * first page (now), the last row's `created` with `next` to append.
     */
    suspend fun folderDocuments(
        tab: DistributionTab,
        folderKey: String,
        beforeMs: Long,
        next: Boolean,
        mode: ListMode,
    ): ZillitResult<List<DistDocument>>

    /** Folder search — scene number and episode, OR a colour; the two exclude each other. */
    suspend fun search(
        tab: DistributionTab,
        sceneNumber: String?,
        episode: String?,
        colour: PageColour?,
        mode: ListMode,
    ): ZillitResult<List<DistDocument>>

    /** One document; [action] is what the server tallies. */
    suspend fun document(
        tab: DistributionTab,
        id: String,
        action: ReadAction,
        mode: ListMode,
    ): ZillitResult<DistDocument>

    /** Creates (or replaces) a document from an already-stored PDF. */
    suspend fun upload(
        tab: DistributionTab,
        draft: UploadDraft,
        stored: StoredPdf,
        nowMs: Long,
    ): ZillitResult<DistDocument?>

    suspend fun delete(tab: DistributionTab, id: String): ZillitResult<Unit>

    /** D.O.D only: refile a document under another folder NAME. */
    suspend fun move(tab: DistributionTab, id: String, folderName: String): ZillitResult<Unit>

    suspend fun counts(tab: DistributionTab, id: String): ZillitResult<List<CountRow>>

    /**
     * Publishes a document into Document Distribution's library —
     * `POST documents/from-tool` on that service, filed under the tool's
     * root and sub-folder, with the episode (or scene) as the leaf.
     */
    suspend fun publish(
        tool: DistributionTool,
        tab: DistributionTab,
        document: DistDocument,
        todayYmd: String,
    ): ZillitResult<Unit>
}

/** Host seams: PDF up to storage, PDF bytes down, pages rendered, file saved. */
interface DistributionTransfer {
    suspend fun upload(storagePath: String, fileName: String, bytes: ByteArray): ZillitResult<StoredPdf>

    /** The stored PDF's bytes, through the signed-storage path. */
    suspend fun fetch(stored: StoredPdf): ZillitResult<ByteArray>

    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<PdfPageImage>>

    /** Saves to Downloads and hands the file to the OS. */
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}

data class PdfPageImage(
    val page: Int,
    val imageBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean = other is PdfPageImage && other.page == page
    override fun hashCode(): Int = page
}
