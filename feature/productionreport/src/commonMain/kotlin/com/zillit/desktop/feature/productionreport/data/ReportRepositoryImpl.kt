package com.zillit.desktop.feature.productionreport.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.ReminderRequest
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportQuery
import com.zillit.desktop.feature.productionreport.domain.ReportRepository
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import com.zillit.desktop.feature.productionreport.domain.ReviewAssignee
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.StockTemplate
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
 * The production report service (`productionreportsapi`).
 *
 * Transcription notes, verified against the web client:
 *
 *  - the full prefix is `/api/v2`: the web's env base carries the `/api`
 *    segment its client never spells;
 *  - both submit calls carry `{approvers, created_by}`;
 *  - editing is `POST .../revisions`, never a PUT;
 *  - approve/reject act on approval-request ids, not report ids;
 *  - a refusal arrives as HTTP 200 with `status: 0`, so every call reads the
 *    envelope and treats anything but `status: 1` as a failure — the web
 *    resolves those as successes and shows empty lists.
 */
@Suppress("TooManyFunctions") // One function per endpoint the tool speaks.
class ReportRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : ReportRepository {

    private val base = config.apiV2(ZillitService.ProductionReport).trimEnd('/')

    override val events: Flow<ReportSyncEvent> =
        bus?.onAny(PRODUCTION_REPORT_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { message -> syncEventOf(message.event.value, message.payload) }
            ?: emptyFlow()

    override suspend fun metadata(projectId: String): ZillitResult<SheetMetadata> =
        call(HttpVerb.Get, "$base/projects/$projectId/production-report-metadata").mapData { ReportWire.metadata(it) }

    override suspend fun saveMetadata(projectId: String, update: MetadataUpdate): ZillitResult<SheetMetadata?> =
        call(
            HttpVerb.Put,
            "$base/projects/$projectId/production-report-metadata",
            body = buildJsonObject {
                update.totalDays?.let { put("total_days", it) }
                update.currentShootDay?.let { put("current_shoot_day", it) }
                update.finalApproverIds?.let { put("final_approver_ids", it.toJson()) }
                update.internalReceiverIds?.let { put("internal_distribution_receivers", it.toJson()) }
                if (update.revokeAccessOnRemoval) put("revoke_access_on_removal", true)
                update.dayTypeAdd?.let { put("day_type_add", it) }
            },
        ).mapData { data -> (data as? JsonObject)?.let { ReportWire.metadata(it) } }

    override suspend fun stockTemplates(): ZillitResult<List<StockTemplate>> =
        call(HttpVerb.Get, "$base/default-template").mapData { ReportWire.stockTemplates(it) }

    override suspend fun savedTemplates(): ZillitResult<List<SavedTemplate>> =
        call(HttpVerb.Get, "$base/production-reports/templates").mapData { ReportWire.savedTemplates(it) }

    override suspend fun savedTemplate(id: String): ZillitResult<SavedTemplate> =
        call(HttpVerb.Get, "$base/production-reports/templates/$id").flatMapData { data ->
            ReportWire.savedTemplateOf(data)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Http(status = NOT_FOUND, serverMessage = "Template has no content"))
        }

    override suspend fun createTemplate(payload: SheetPayload): ZillitResult<SavedTemplate?> =
        call(HttpVerb.Post, "$base/production-reports/templates", body = templateBody(payload))
            .mapData { ReportWire.savedTemplateOf(it) }

    override suspend fun updateTemplate(id: String, payload: SheetPayload): ZillitResult<Unit> =
        call(HttpVerb.Put, "$base/production-reports/templates/$id", body = templateBody(payload)).mapData { }

    override suspend fun deleteTemplate(id: String): ZillitResult<Unit> =
        call(HttpVerb.Delete, "$base/production-reports/templates/$id").mapData { }

    private fun templateBody(payload: SheetPayload) = buildJsonObject { put("payload", PayloadWire.emit(payload)) }

    override suspend fun reports(query: ReportQuery): ZillitResult<List<ReportSummary>> =
        call(
            HttpVerb.Get,
            "$base/production-reports",
            query = buildMap {
                query.projectId?.let { put("project_id", it) }
                if (query.statuses.isNotEmpty()) put("status", query.statuses.joinToString(",") { it.wire })
                query.createdById?.let { put("created_by_id", it) }
                query.approverId?.let { put("approver_id", it) }
            },
        ).mapData { ReportWire.summaries(it) }

    override suspend fun report(id: String): ZillitResult<ReportDetail> =
        call(HttpVerb.Get, "$base/production-reports/$id").flatMapData { data ->
            ReportWire.detail(data)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("production report missing from the answer"))
        }

    override suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary> =
        call(
            HttpVerb.Post,
            "$base/production-reports",
            body = buildJsonObject {
                put("payload", PayloadWire.emit(payload))
                put("created_by", createdBy)
                put("created_by_id", idOrNull(createdById))
                put("project_id", projectId)
                put("name", name)
            },
        ).flatMapData { data ->
            ReportWire.summary(ReportWire.reportOf(data))?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("the created report came back without an id"))
        }

    override suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary?> =
        call(
            HttpVerb.Post,
            "$base/production-reports/$id/revisions",
            body = buildJsonObject {
                put("payload", PayloadWire.emit(payload))
                put("created_by", createdBy)
                put("created_by_id", idOrNull(createdById))
                put("name", name)
            },
        ).mapData { ReportWire.summary(ReportWire.reportOf(it)) }

    override suspend fun delete(id: String): ZillitResult<Unit> =
        call(HttpVerb.Delete, "$base/production-reports/$id").mapData { }

    override suspend fun submitForApproval(
        id: String,
        approvers: List<ReviewAssignee>,
        createdBy: String,
    ): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/production-reports/$id/submit-for-approval",
            body = reviewBody(approvers, createdBy),
        ).mapData { }

    override suspend fun submitForInternalApproval(
        id: String,
        approvers: List<ReviewAssignee>,
        createdBy: String,
    ): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/production-reports/$id/submit-for-internal-approval",
            body = reviewBody(approvers, createdBy),
        )
            .mapData { }

    private fun reviewBody(approvers: List<ReviewAssignee>, createdBy: String) = buildJsonObject {
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
        put("created_by", createdBy)
    }

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
        )
            .mapData { }

    override suspend fun sendReminder(id: String, reminder: ReminderRequest): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/production-reports/$id/send-reminder",
            body = buildJsonObject {
                put("sent_by", reminder.sentBy)
                put("sent_by_id", idOrNull(reminder.sentById))
                put("assignee_ids", reminder.assigneeIds.toJson())
                put("sent_by_role", reminder.sentByRole)
                put("message", reminder.message)
            },
        ).mapData { }

    override suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
    ): ZillitResult<Unit> =
        call(
            HttpVerb.Post,
            "$base/production-reports/$id/publish",
            body = buildJsonObject {
                put("published_by", publishedBy)
                put("published_by_id", idOrNull(publishedById))
                put("continuation_type", if (continuation) "CONTINUATION" else "NEW")
                put("publish_notes", "")
            },
        ).mapData { }

    override suspend fun comments(id: String): ZillitResult<List<ReportComment>> =
        call(HttpVerb.Get, "$base/production-reports/$id/comments").mapData { ReportWire.comments(it) }

    override suspend fun addComment(id: String, author: SheetMember?, text: String): ZillitResult<ReportComment?> =
        call(
            HttpVerb.Post,
            "$base/production-reports/$id/comments",
            body = buildJsonObject {
                put("author_id", idOrNull(author?.userId.orEmpty()))
                put("author_name", author?.fullName?.ifBlank { null } ?: "Unknown")
                put("author_role", author?.designation.orEmpty())
                put("text", text)
            },
        ).mapData { ReportWire.commentOf(it) }

    override suspend fun editComment(id: String, commentId: String, text: String): ZillitResult<ReportComment?> =
        call(
            HttpVerb.Put,
            "$base/production-reports/$id/comments/$commentId",
            body = buildJsonObject { put("text", text) },
        )
            .mapData { ReportWire.commentOf(it) }

    override suspend fun deleteComment(id: String, commentId: String): ZillitResult<Unit> =
        call(HttpVerb.Delete, "$base/production-reports/$id/comments/$commentId").mapData { }

    /**
     * Every call reads the envelope: `status: 1` is success whatever `data`
     * holds (writes answer none), anything else is the server's refusal.
     */
    private suspend fun call(
        verb: HttpVerb,
        url: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<JsonElement?> =
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
                ZillitResult.Success(envelope.data.data)
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
