package com.zillit.desktop.feature.callsheet.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.domain.AccessPage
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.ReminderRequest
import com.zillit.desktop.feature.callsheet.domain.ReviewAssignee
import com.zillit.desktop.feature.callsheet.domain.SavedTemplate
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import com.zillit.desktop.feature.callsheet.domain.StockTemplate
import com.zillit.desktop.feature.callsheet.domain.ToolAccessGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The call-sheet service (`callsheetapi`).
 *
 * Transcription notes, verified against the web client and the live service:
 *
 *  - the full prefix is `/api/v2`: the web appends `/v2` to an env base that
 *    already carries `/api`; bare `/v2` answers "Route Not Found";
 *  - a refusal arrives as HTTP 200 with `status: 0` (a create without posting
 *    rights answers `callsheet_tool_posting_rights_required`), so every call
 *    reads the envelope and treats anything but `status: 1` as a failure;
 *  - the body is snake_case at the top level while `payload` goes out
 *    verbatim — see [PayloadWire];
 *  - editing an existing sheet is `POST .../revisions`, never a PUT;
 *  - `submit-for-approval` takes an EMPTY body — the service takes the
 *    sheet's approvers, else the project's;
 *  - approve/reject act on approval-request ids, not sheet ids;
 *  - lists answer `{call_sheets:[…]}` or camelCased `{callSheets:[…]}`.
 */
@Suppress("TooManyFunctions") // One function per endpoint the tool speaks.
class CallSheetRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : CallSheetRepository {

    private val base = config.apiV2(ZillitService.CallSheet).trimEnd('/')

    /** The permission grid lives on the core (settings) service. */
    private val gridBase = config.apiV2().trimEnd('/')

    override val events: Flow<SheetSyncEvent> =
        bus?.onAny(CALL_SHEET_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { message -> syncEventOf(message.event.value, message.payload) }
            ?: emptyFlow()

    override suspend fun metadata(projectId: String): ZillitResult<SheetMetadata> =
        call(HttpVerb.Get, "$base/projects/$projectId/call-sheet-metadata").mapData { SheetWire.metadata(it) }

    override suspend fun saveMetadata(projectId: String, update: MetadataUpdate): ZillitResult<SheetMetadata?> =
        call(
            HttpVerb.Put,
            "$base/projects/$projectId/call-sheet-metadata",
            body = buildJsonObject {
                update.totalDays?.let { put("total_days", it) }
                update.totalDaysNumber?.let { put("total_days", it) }
                update.currentShootDay?.let { put("current_shoot_day", it) }
                update.dayTypes?.let { put("day_types", it.toJson()) }
                update.finalApproverIds?.let { put("final_approver_ids", it.toJson()) }
                update.internalReceiverIds?.let { put("internal_distribution_receivers", it.toJson()) }
                if (update.revokeAccessOnRemoval) put("revoke_access_on_removal", true)
                update.dayTypeAdd?.let { put("day_type_add", it) }
            },
        ).mapData { data -> (data as? JsonObject)?.let { SheetWire.metadata(it) } }

    override suspend fun stockTemplates(): ZillitResult<List<StockTemplate>> =
        call(HttpVerb.Get, "$base/default-template").mapData { SheetWire.stockTemplates(it) }

    override suspend fun savedTemplates(): ZillitResult<List<SavedTemplate>> =
        call(HttpVerb.Get, "$base/call-sheets/templates").mapData { SheetWire.savedTemplates(it) }

    override suspend fun savedTemplate(id: String): ZillitResult<SavedTemplate> =
        call(HttpVerb.Get, "$base/call-sheets/templates/$id").flatMapData { data ->
            SheetWire.savedTemplateOf(data)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Http(status = NOT_FOUND, serverMessage = "Template has no content"))
        }

    override suspend fun createTemplate(payload: SheetPayload): ZillitResult<SavedTemplate?> =
        call(HttpVerb.Post, "$base/call-sheets/templates", body = templateBody(payload))
            .mapData { SheetWire.savedTemplateOf(it) }

    override suspend fun updateTemplate(id: String, payload: SheetPayload): ZillitResult<Unit> =
        call(HttpVerb.Put, "$base/call-sheets/templates/$id", body = templateBody(payload)).mapData { }

    override suspend fun deleteTemplate(id: String): ZillitResult<Unit> =
        call(HttpVerb.Delete, "$base/call-sheets/templates/$id").mapData { }

    private fun templateBody(payload: SheetPayload) = buildJsonObject { put("payload", PayloadWire.emit(payload)) }

    override suspend fun sheets(query: SheetQuery): ZillitResult<List<CallSheetSummary>> =
        call(
            HttpVerb.Get,
            "$base/call-sheets",
            query = buildMap {
                query.projectId?.takeIf { it.isNotBlank() }?.let { put("project_id", it) }
                if (query.statuses.isNotEmpty()) put("status", query.statuses.joinToString(",") { it.wire })
                query.createdById?.takeIf { it.isNotBlank() }?.let { put("created_by_id", it) }
                query.approverId?.takeIf { it.isNotBlank() }?.let { put("approver_id", it) }
            },
        ).mapData { SheetWire.summaries(it) }

    override suspend fun sheet(id: String): ZillitResult<CallSheetDetail> =
        call(HttpVerb.Get, "$base/call-sheets/$id").flatMapData { data ->
            SheetWire.detail(data)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("call sheet missing from the answer"))
        }

    override suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<CallSheetSummary> =
        call(
            HttpVerb.Post,
            "$base/call-sheets",
            body = buildJsonObject {
                put("payload", PayloadWire.emit(payload))
                put("created_by", createdBy)
                put("created_by_id", createdById)
                put("project_id", projectId)
                put("name", name)
            },
        ).flatMapData { data ->
            // A create without an id is a failure: the next save would post
            // revisions to `/call-sheets//revisions`.
            SheetWire.summary(SheetWire.sheetOf(data))?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("the created call sheet came back without an id"))
        }

    override suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/call-sheets/$id/revisions",
            body = buildJsonObject {
                put("payload", PayloadWire.emit(payload))
                put("created_by", createdBy)
                put("created_by_id", createdById)
                put("name", name)
            },
        ).mapData { }

    override suspend fun delete(id: String): ZillitResult<Unit> =
        call(HttpVerb.Delete, "$base/call-sheets/$id").mapData { }

    override suspend fun submitForApproval(id: String): ZillitResult<Unit> =
        call(HttpVerb.Post, "$base/call-sheets/$id/submit-for-approval", body = buildJsonObject { }).mapData { }

    override suspend fun submitForInternalApproval(id: String, approvers: List<ReviewAssignee>): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/call-sheets/$id/submit-for-internal-approval",
            body = buildJsonObject {
                put(
                    "approvers",
                    buildJsonArray {
                        approvers.forEach { approver ->
                            add(
                                buildJsonObject {
                                    put("assignee_id", approver.assigneeId)
                                    put("assignee_name", approver.assigneeName)
                                    put("role", approver.role)
                                },
                            )
                        }
                    },
                )
            },
        ).mapData { }

    override suspend fun approve(requestId: String, decision: ApprovalDecision): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/approval-requests/$requestId/approve",
            body = buildJsonObject {
                when (decision) {
                    ApprovalDecision.Plain -> Unit
                    ApprovalDecision.WithoutSignature -> put("without_signature", true)
                    is ApprovalDecision.Signature -> put(
                        "signature_image",
                        buildJsonObject {
                            put("media", decision.media)
                            put("thumbnail", decision.thumbnail.ifBlank { decision.media })
                            put("content_type", "image")
                            put("content_subtype", "png")
                            put("caption", "")
                            put("duration", 1)
                            put("height", 1)
                            put("width", 1)
                            put("bucket", decision.bucket)
                            put("region", decision.region)
                            put("name", "signature.png")
                        },
                    )
                }
            },
        ).mapData { }

    override suspend fun reject(requestId: String, reason: String): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/approval-requests/$requestId/reject",
            body = buildJsonObject { put("reason", reason) },
        ).mapData { }

    override suspend fun sendReminder(id: String, reminder: ReminderRequest): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/call-sheets/$id/send-reminder",
            body = buildJsonObject {
                put("sent_by", reminder.sentBy)
                put("sent_by_id", idOrNull(reminder.sentById))
                put("sent_by_role", reminder.sentByRole)
                put("assignee_ids", reminder.assigneeIds.toJson())
                put("message", reminder.message)
            },
        ).mapData { }

    override suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
        notes: String,
    ): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/call-sheets/$id/publish",
            body = buildJsonObject {
                put("published_by", publishedBy)
                put("published_by_id", idOrNull(publishedById))
                put("continuation_type", if (continuation) "CONTINUATION" else "NEW")
                put("publish_notes", notes)
            },
        ).mapData { }

    override suspend fun comments(id: String): ZillitResult<List<SheetComment>> =
        call(HttpVerb.Get, "$base/call-sheets/$id/comments").mapData { SheetWire.comments(it) }

    override suspend fun addComment(
        id: String,
        author: SheetMember?,
        fallbackName: String,
        text: String,
    ): ZillitResult<SheetComment?> =
        call(
            HttpVerb.Post,
            "$base/call-sheets/$id/comments",
            body = buildJsonObject {
                put("author_id", idOrNull(author?.userId.orEmpty()))
                put(
                    "author_name",
                    author?.fullName?.ifBlank { null } ?: fallbackName.ifBlank { str(S.desktop_unknown) },
                )
                put("author_role", author?.designation.orEmpty())
                put("text", text)
            },
        ).mapData { SheetWire.commentOf(it) }

    override suspend fun editComment(id: String, commentId: String, text: String): ZillitResult<SheetComment?> =
        call(
            HttpVerb.Put,
            "$base/call-sheets/$id/comments/$commentId",
            body = buildJsonObject { put("text", text) },
        ).mapData { SheetWire.commentOf(it) }

    override suspend fun deleteComment(id: String, commentId: String): ZillitResult<Unit> =
        call(HttpVerb.Delete, "$base/call-sheets/$id/comments/$commentId").mapData { }

    override suspend fun accessPage(page: Int, limit: Int, currentUserId: String?): ZillitResult<AccessPage> =
        call(
            HttpVerb.Get,
            "$gridBase/permissions/crewlist/tools/access",
            query = mapOf("page" to page, "limit" to limit),
        ).mapData { SheetWire.accessPage(it, currentUserId) }

    override suspend fun setToolAccess(userId: String, enabled: Boolean): ZillitResult<ToolAccessGrant> =
        when (
            val envelope = envelope(
                HttpVerb.Post,
                "$base/callsheet-tool-access",
                body = buildJsonObject {
                    put("user_id", userId)
                    put("enabled", enabled)
                },
            )
        ) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success -> ZillitResult.Success(
                SheetWire.toolAccess(envelope.data.data, enabled, envelope.data.message.orEmpty()),
            )
        }

    /**
     * Every call reads the envelope: `status: 1` is success whatever `data`
     * holds (writes answer none), anything else is the server's refusal.
     */
    private suspend fun call(
        verb: HttpVerb,
        url: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<JsonElement?> = when (val envelope = envelope(verb, url, body, query)) {
        is ZillitResult.Failure -> envelope
        is ZillitResult.Success -> ZillitResult.Success(envelope.data.data)
    }

    private suspend fun envelope(
        verb: HttpVerb,
        url: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<ApiEnvelope> =
        when (
            val envelope = apiClient.envelope(
                verb = verb,
                url = url,
                module = RequestModule.ProjectUser,
                body = body,
                queryParameters = query,
            )
        ) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success -> if (envelope.data.status == STATUS_OK) {
                envelope
            } else {
                ZillitResult.Failure(
                    ZillitError.Http(
                        status = HTTP_OK,
                        serverMessage = envelope.data.message,
                        messageElements = envelope.data.messageElements.orEmpty(),
                    ),
                )
            }
        }

    private inline fun <T> ZillitResult<JsonElement?>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Success -> ZillitResult.Success(transform(data))
            is ZillitResult.Failure -> this
        }

    private inline fun <T> ZillitResult<JsonElement?>.flatMapData(
        transform: (JsonElement?) -> ZillitResult<T>,
    ): ZillitResult<T> =
        when (this) {
            is ZillitResult.Success -> transform(data)
            is ZillitResult.Failure -> this
        }

    /** A user id, or JSON null when there is none — the web sends `null`, never an empty string. */
    private fun idOrNull(value: String): JsonElement = if (value.isBlank()) JsonNull else JsonPrimitive(value)

    private companion object {
        const val STATUS_OK = 1
        const val HTTP_OK = 200
        const val NOT_FOUND = 404
    }
}
