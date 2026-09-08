package com.zillit.desktop.feature.productionreport.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** An internal (comments-round) reviewer, as `submit-for-internal-approval` wants them. */
data class InternalApprover(
    val assigneeId: String,
    val assigneeName: String,
    val role: String,
)

/**
 * The production report service (`productionreportsapi`), routes under `/v2`
 * — no `/api` segment, same exception as the call-sheet service.
 */
interface ReportRepository {
    /** The user ids holding posting rights on [toolIdentifier] — the only people who may be picked as approvers. */
    suspend fun postingRightsUserIds(toolIdentifier: String): ZillitResult<Set<String>>


    /**
     * A pulse per report workflow event from another client — the web's
     * `handleSocketReportUpdate` (`ProductionReportApp.jsx:958-1048`)
     * answers each with targeted list reloads. The ViewModel re-runs its
     * load for the open destination. Empty by default: tests, and hosts
     * without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

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
        statuses: List<ReportStatus> = emptyList(),
        createdById: String? = null,
        approverId: String? = null,
    ): ZillitResult<List<ReportSummary>>

    suspend fun sheet(id: String): ZillitResult<ReportDetail>

    suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary>

    /** Every subsequent save — editing is never a PUT on this service. */
    suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    /** Signature round — unlike the call sheet, approvers ride in the body. */
    suspend fun submitForApproval(
        id: String,
        approvers: List<InternalApprover>,
        createdBy: String,
    ): ZillitResult<Unit>

    /** Comments round, with the picked reviewers. */
    suspend fun submitForInternalApproval(
        id: String,
        approvers: List<InternalApprover>,
        createdBy: String,
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
 * The server-rendered PDF of a saved report, and where it goes on publish.
 * Split from the repository because fetching bytes and fanning out to the
 * unit chat both live outside this module's HTTP envelope.
 */
interface ReportDelivery {
    /** `GET /v2/production-reports/{id}/pdf` — needs a saved id. */
    suspend fun pdf(sheetId: String): ZillitResult<ByteArray>

    /** Renders PDF bytes to page images for the in-app viewer. */
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>>

    /**
     * Posts the published PDF into the production-report unit's chat — the
     * web's publish fan-out. [replacePrevious] mirrors `continuation_type`.
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

/**
 * The last published call sheet, for seeding a new report's crew IN times
 * and key personnel. A seam because the sheets live on a different service;
 * the host wires it to the call-sheet repository. Both failures and absence
 * are null — the web populates silently or not at all.
 */
fun interface PublishedCallSheetLookup {
    suspend fun lastPublishedPayload(projectId: String): SheetPayload?
}
