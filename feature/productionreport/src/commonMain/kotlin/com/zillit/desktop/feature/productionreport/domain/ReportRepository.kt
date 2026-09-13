package com.zillit.desktop.feature.productionreport.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A reviewer as both submit calls want them: `{assignee_id, assignee_name, role}`. */
data class ReviewAssignee(
    val assigneeId: String,
    val assigneeName: String,
    val role: String,
)

/** One `GET /production-reports` query. Null fields are not sent. */
data class ReportQuery(
    val projectId: String? = null,
    val statuses: List<ReportStatus> = emptyList(),
    val createdById: String? = null,
    val approverId: String? = null,
)

/** A partial metadata write — only the fields that are set go on the wire. */
data class MetadataUpdate(
    val totalDays: String? = null,
    val currentShootDay: Int? = null,
    val finalApproverIds: List<String>? = null,
    val internalReceiverIds: List<String>? = null,
    /** Sent only when the author answered the removal prompt with "and viewing access". */
    val revokeAccessOnRemoval: Boolean = false,
    val dayTypeAdd: String? = null,
) {
    val isEmpty: Boolean
        get() = totalDays == null && currentShootDay == null && finalApproverIds == null &&
            internalReceiverIds == null && dayTypeAdd == null
}

/** The body of an approval. */
sealed interface ApprovalDecision {
    /** An INTERNAL-stage approval — an empty body. */
    data object Plain : ApprovalDecision

    /** `{without_signature: true}`. */
    data object WithoutSignature : ApprovalDecision

    /** `{signature_image: {media, thumbnail, …}}` — the drawn signature, already in storage. */
    data class Signature(val media: String, val thumbnail: String, val bucket: String, val region: String) :
        ApprovalDecision
}

/** `POST /send-reminder`. */
data class ReminderRequest(
    val sentBy: String,
    val sentById: String,
    val assigneeIds: List<String>,
    /** A designation KEY (ZL-20648) — the receiver translates it. */
    val sentByRole: String,
    val message: String,
)

/** What a live update names, for the targeted reloads the web runs. */
data class ReportSyncEvent(
    val name: String,
    val reportId: String? = null,
    val status: ReportStatus? = null,
) {
    val isComment: Boolean get() = name.startsWith("productionreport:comment:")
}

/**
 * The production report service (`productionreportsapi`, routes under `/api/v2`).
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation.
interface ReportRepository {

    /** Live workflow and comment events from other clients; empty for tests and hosts without a socket. */
    val events: Flow<ReportSyncEvent> get() = emptyFlow()

    suspend fun metadata(projectId: String): ZillitResult<SheetMetadata>

    /** A partial write; answers the stored metadata when the server echoes it. */
    suspend fun saveMetadata(projectId: String, update: MetadataUpdate): ZillitResult<SheetMetadata?>

    /** `GET /default-template` — the stock layouts, decorated for the picker. */
    suspend fun stockTemplates(): ZillitResult<List<StockTemplate>>

    /** `GET /production-reports/templates` — the project's saved layouts, without payloads. */
    suspend fun savedTemplates(): ZillitResult<List<SavedTemplate>>

    suspend fun savedTemplate(id: String): ZillitResult<SavedTemplate>

    /** Names are server-generated; the answer carries the one it chose. */
    suspend fun createTemplate(payload: SheetPayload): ZillitResult<SavedTemplate?>

    suspend fun updateTemplate(id: String, payload: SheetPayload): ZillitResult<Unit>

    suspend fun deleteTemplate(id: String): ZillitResult<Unit>

    suspend fun reports(query: ReportQuery): ZillitResult<List<ReportSummary>>

    suspend fun report(id: String): ZillitResult<ReportDetail>

    suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary>

    /** Every later save — editing is `POST /revisions`, never a PUT. */
    suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary?>

    suspend fun delete(id: String): ZillitResult<Unit>

    suspend fun submitForApproval(id: String, approvers: List<ReviewAssignee>, createdBy: String): ZillitResult<Unit>

    suspend fun submitForInternalApproval(
        id: String,
        approvers: List<ReviewAssignee>,
        createdBy: String,
    ): ZillitResult<Unit>

    /** Acts on the approval REQUEST id, never the report id. */
    suspend fun approve(requestId: String, decision: ApprovalDecision): ZillitResult<Unit>

    suspend fun reject(requestId: String, reason: String): ZillitResult<Unit>

    suspend fun sendReminder(id: String, reminder: ReminderRequest): ZillitResult<Unit>

    /** `continuation_type` CONTINUATION or NEW; the web always sends empty notes. */
    suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
    ): ZillitResult<Unit>

    suspend fun comments(id: String): ZillitResult<List<ReportComment>>

    suspend fun addComment(id: String, author: SheetMember?, text: String): ZillitResult<ReportComment?>

    suspend fun editComment(id: String, commentId: String, text: String): ZillitResult<ReportComment?>

    suspend fun deleteComment(id: String, commentId: String): ZillitResult<Unit>
}

/**
 * The last published call sheet, for seeding a new report's crew IN times
 * and key personnel. Failures and absence are both null — the web populates
 * silently or not at all.
 */
fun interface PublishedCallSheetLookup {
    suspend fun lastPublishedPayload(projectId: String): SheetPayload?
}
