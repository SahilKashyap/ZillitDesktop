package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.common.ZillitResult

/** An internal (comments-round) reviewer, as `submit-for-internal-approval` wants them. */
data class InternalApprover(
    val assigneeId: String,
    val assigneeName: String,
    val role: String,
)

/**
 * The call-sheet service (`callsheetapi`), routes under `/v2` — no `/api`
 * segment; this service is the exception to the shared prefix.
 */
interface CallSheetRepository {

    suspend fun metadata(projectId: String): ZillitResult<SheetMetadata>

    /**
     * Writes the counters back. Best-effort on save — the web ignores a
     * failure here and proceeds to the sheet write.
     */
    suspend fun saveMetadata(
        projectId: String,
        totalDays: String?,
        currentShootDay: Int?,
        finalApproverIds: List<String>?,
        internalReceiverIds: List<String>? = null,
    ): ZillitResult<Unit>

    /** The server's base template, or null when the service has none. */
    suspend fun defaultTemplate(): ZillitResult<SheetPayload?>

    suspend fun sheets(
        projectId: String?,
        statuses: List<CallSheetStatus> = emptyList(),
        createdById: String? = null,
        approverId: String? = null,
    ): ZillitResult<List<CallSheetSummary>>

    suspend fun sheet(id: String): ZillitResult<CallSheetDetail>

    suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<CallSheetSummary>

    /** Every subsequent save — editing is never a PUT on this service. */
    suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    /** Signature round. Empty body — assignees come from project metadata. */
    suspend fun submitForApproval(id: String): ZillitResult<Unit>

    /** Comments round, with the picked reviewers. */
    suspend fun submitForInternalApproval(
        id: String,
        approvers: List<InternalApprover>,
    ): ZillitResult<Unit>

    /** Acts on the approval REQUEST id, never the sheet id. */
    suspend fun approve(requestId: String, withoutSignature: Boolean): ZillitResult<Unit>

    suspend fun reject(requestId: String, reason: String): ZillitResult<Unit>

    suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
        notes: String,
    ): ZillitResult<Unit>
}

/**
 * The server-rendered PDF of a saved sheet, and where it goes on publish.
 * Split from the repository because fetching bytes and fanning out to the
 * home-unit chat both live outside this module's HTTP envelope.
 */
interface CallSheetDelivery {
    /** `GET /v2/call-sheets/{id}/pdf` — needs a saved id, drafts included. */
    suspend fun pdf(sheetId: String): ZillitResult<ByteArray>

    /** Renders PDF bytes to page images for the in-app viewer. */
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>>

    /**
     * Posts the published PDF into the call-sheet home unit's chat — the
     * web's publish fan-out. [replacePrevious] mirrors `continuation_type`:
     * a NEW sheet replaces the pinned PDF, a continuation appends.
     */
    suspend fun distribute(
        sheetId: String,
        serialNo: String,
        pdf: ByteArray,
        replacePrevious: Boolean,
    ): ZillitResult<Unit>
}

data class SheetPdfPage(
    val page: Int,
    val imageBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean = other is SheetPdfPage && other.page == page
    override fun hashCode(): Int = page
}
