package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A reviewer as `submit-for-internal-approval` wants them: `{assignee_id, assignee_name, role}`. */
data class ReviewAssignee(
    val assigneeId: String,
    val assigneeName: String,
    val role: String,
)

/** One `GET /call-sheets` query. Null fields are not sent. */
data class SheetQuery(
    val projectId: String? = null,
    val statuses: List<CallSheetStatus> = emptyList(),
    val createdById: String? = null,
    val approverId: String? = null,
)

/**
 * A partial metadata write — only the fields that are set go on the wire.
 * `saveDraft` sends [totalDays] as the typed string; "Save Approvers" sends
 * the full shape with numbers ([totalDaysNumber]), exactly as the web does.
 */
data class MetadataUpdate(
    val totalDays: String? = null,
    val totalDaysNumber: Int? = null,
    val currentShootDay: Int? = null,
    val dayTypes: List<String>? = null,
    val finalApproverIds: List<String>? = null,
    val internalReceiverIds: List<String>? = null,
    /** Sent only when the author answered the removal prompt with "and viewing access". */
    val revokeAccessOnRemoval: Boolean = false,
    val dayTypeAdd: String? = null,
) {
    val isEmpty: Boolean
        get() = totalDays == null && totalDaysNumber == null && currentShootDay == null && dayTypes == null &&
            finalApproverIds == null && internalReceiverIds == null && dayTypeAdd == null
}

/** The body of an approval. */
sealed interface ApprovalDecision {
    /** An INTERNAL-stage approval — an empty body. */
    data object Plain : ApprovalDecision

    /** `{without_signature: true}`. */
    data object WithoutSignature : ApprovalDecision

    /** `{signature_image: {media, thumbnail, …}}` — the signature, already in storage. */
    data class Signature(val media: String, val thumbnail: String, val bucket: String, val region: String) :
        ApprovalDecision
}

/**
 * `POST /send-reminder`. [sentBy] is the sender's MEMBER ID, not their name
 * (the reader looks them up and shows who they are today; reminders written
 * before Sep 2026 hold a name there and render it verbatim), and
 * [sentByRole] the designation KEY, read only when the id resolves to nobody.
 */
data class ReminderRequest(
    val sentBy: String,
    val sentById: String,
    val sentByRole: String,
    val assigneeIds: List<String>,
    val message: String,
)

/** What a live update names, for the targeted reloads and the open comment thread. */
data class SheetSyncEvent(
    val name: String,
    val sheetId: String? = null,
    val status: CallSheetStatus? = null,
    /** `callsheet:comment:created|updated`: the comment as saved. */
    val comment: SheetComment? = null,
    /** `callsheet:comment:deleted`: the comment that went. */
    val commentId: String? = null,
) {
    val isComment: Boolean get() = name.startsWith("callsheet:comment:")
}

/**
 * The call-sheet service (`callsheetapi`, routes under `/api/v2` — the web
 * spells `/v2`, its env base carries the `/api` segment).
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation.
interface CallSheetRepository {

    /** Live workflow and comment events from other clients; empty for tests and hosts without a socket. */
    val events: Flow<SheetSyncEvent> get() = emptyFlow()

    suspend fun metadata(projectId: String): ZillitResult<SheetMetadata>

    /** A partial write; answers the stored metadata when the server echoes it. */
    suspend fun saveMetadata(projectId: String, update: MetadataUpdate): ZillitResult<SheetMetadata?>

    /** `GET /default-template` — the stock layouts, prepared for the picker. */
    suspend fun stockTemplates(): ZillitResult<List<StockTemplate>>

    /** `GET /call-sheets/templates` — the project's saved layouts, without payloads. */
    suspend fun savedTemplates(): ZillitResult<List<SavedTemplate>>

    suspend fun savedTemplate(id: String): ZillitResult<SavedTemplate>

    /** Names are server-generated; the answer carries the one it chose. */
    suspend fun createTemplate(payload: SheetPayload): ZillitResult<SavedTemplate?>

    suspend fun updateTemplate(id: String, payload: SheetPayload): ZillitResult<Unit>

    suspend fun deleteTemplate(id: String): ZillitResult<Unit>

    suspend fun sheets(query: SheetQuery): ZillitResult<List<CallSheetSummary>>

    suspend fun sheet(id: String): ZillitResult<CallSheetDetail>

    suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<CallSheetSummary>

    /** Every later save — editing is `POST /revisions`, never a PUT. */
    suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    /** Signature round. Empty body — the service takes the sheet's approvers, else the project's. */
    suspend fun submitForApproval(id: String): ZillitResult<Unit>

    /** Comments round, with the picked recipients. */
    suspend fun submitForInternalApproval(id: String, approvers: List<ReviewAssignee>): ZillitResult<Unit>

    /** Acts on the approval REQUEST id, never the sheet id. */
    suspend fun approve(requestId: String, decision: ApprovalDecision): ZillitResult<Unit>

    suspend fun reject(requestId: String, reason: String): ZillitResult<Unit>

    suspend fun sendReminder(id: String, reminder: ReminderRequest): ZillitResult<Unit>

    /** `continuation_type` CONTINUATION or NEW, with the author's notes. */
    suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
        notes: String,
    ): ZillitResult<Unit>

    suspend fun comments(id: String): ZillitResult<List<SheetComment>>

    suspend fun addComment(
        id: String,
        author: SheetMember?,
        fallbackName: String,
        text: String,
    ): ZillitResult<SheetComment?>

    suspend fun editComment(id: String, commentId: String, text: String): ZillitResult<SheetComment?>

    suspend fun deleteComment(id: String, commentId: String): ZillitResult<Unit>

    /**
     * The Permission tab: the crew axis of the permission grid (settings
     * service), `callsheet_tool` cells only, [page] 0-based; the signed-in
     * person's own row dropped.
     */
    suspend fun accessPage(page: Int, limit: Int, currentUserId: String?): ZillitResult<AccessPage>

    /** `POST /callsheet-tool-access {user_id, enabled}` — view, posting and download together. */
    suspend fun setToolAccess(userId: String, enabled: Boolean): ZillitResult<ToolAccessGrant>
}
