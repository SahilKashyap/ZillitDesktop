package com.zillit.desktop.feature.productionreport.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportRepository
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.InternalApprover
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The production report service (`productionreportsapi`).
 *
 * Transcription notes, verified against the web client:
 *
 *  - the full prefix is `/api/v2`, same as the call-sheet service: the
 *    web's env base carries the `/api` segment the client never spells;
 *  - both submit calls carry `{approvers, created_by}` — unlike the call
 *    sheet, whose signature round posts an empty body;
 *  - editing is `POST .../revisions`, never a PUT;
 *  - approve/reject act on approval-request ids, not report ids;
 *  - the embedded revision key is camelCase `currentRevision` amid an
 *    otherwise snake_case document — both spellings are read.
 */
class ReportRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : ReportRepository {

    private val base = config.apiV2(ZillitService.ProductionReport).trimEnd('/')

    override suspend fun metadata(projectId: String): ZillitResult<SheetMetadata> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/projects/$projectId/production-report-metadata",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element -> parseMetadata(element) }

    override suspend fun saveMetadata(
        projectId: String,
        totalDays: String?,
        currentShootDay: Int?,
        finalApproverIds: List<String>?,
        internalReceiverIds: List<String>?,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Put,
        url = "$base/projects/$projectId/production-report-metadata",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            totalDays?.takeIf { it.isNotBlank() }?.let { put("total_days", it) }
            currentShootDay?.let { put("current_shoot_day", it) }
            finalApproverIds?.let { put("final_approver_ids", it.toJson()) }
            internalReceiverIds?.let { put("internal_distribution_receivers", it.toJson()) }
        },
    ).map { }

    override suspend fun defaultTemplate(): ZillitResult<SheetPayload?> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/default-template",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element ->
        // The web spec says `template`; the dev deployment answers a
        // `templates` ARRAY — both are read, first entry wins.
        val obj = element as? JsonObject
        val template = obj?.firstOf("template")
            ?: (obj?.firstOf("templates") as? JsonArray)?.firstOrNull()
        template?.let { PayloadWire.parse(it) }
    }

    override suspend fun sheets(
        projectId: String?,
        statuses: List<ReportStatus>,
        createdById: String?,
        approverId: String?,
    ): ZillitResult<List<ReportSummary>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/production-reports",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap {
            projectId?.takeIf { it.isNotBlank() }?.let { put("project_id", it) }
            if (statuses.isNotEmpty()) put("status", statuses.joinToString(",") { it.wire })
            createdById?.takeIf { it.isNotBlank() }?.let { put("created_by_id", it) }
            approverId?.takeIf { it.isNotBlank() }?.let { put("approver_id", it) }
        },
    ).map { element ->
        val list = (element as? JsonObject)?.firstOf("production_reports", "productionReports") as? JsonArray
        list.elements().mapNotNull { row -> parseSummary(row as? JsonObject) }
    }

    override suspend fun sheet(id: String): ZillitResult<ReportDetail> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/production-reports/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element ->
        val sheet = (element as? JsonObject)?.firstOf("production_report", "productionReport") as? JsonObject
        parseDetail(sheet)
    }

    override suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/production-reports",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("payload", PayloadWire.emit(payload))
            put("created_by", createdBy)
            put("created_by_id", createdById)
            put("project_id", projectId)
            put("name", name)
        },
    ).map { element ->
        val sheet = (element as? JsonObject)?.firstOf("production_report", "productionReport") as? JsonObject
        parseSummary(sheet) ?: ReportSummary(
            id = "", serialNo = "", name = name, status = ReportStatus.Draft,
            createdBy = createdBy, createdById = createdById,
            createdAt = "", updatedAt = "", publishedAt = "",
        )
    }

    override suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/production-reports/$id/revisions",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("payload", PayloadWire.emit(payload))
            put("created_by", createdBy)
            put("created_by_id", createdById)
            put("name", name)
        },
    ).map { }

    override suspend fun delete(id: String): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Delete,
        url = "$base/production-reports/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun submitForApproval(
        id: String,
        approvers: List<InternalApprover>,
        createdBy: String,
    ): ZillitResult<Unit> = submitReview(id, "submit-for-approval", approvers, createdBy)

    override suspend fun submitForInternalApproval(
        id: String,
        approvers: List<InternalApprover>,
        createdBy: String,
    ): ZillitResult<Unit> = submitReview(id, "submit-for-internal-approval", approvers, createdBy)

    private suspend fun submitReview(
        id: String,
        action: String,
        approvers: List<InternalApprover>,
        createdBy: String,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/production-reports/$id/$action",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
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
            put("created_by", createdBy)
        },
    ).map { }

    override suspend fun approve(requestId: String, withoutSignature: Boolean): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/approval-requests/$requestId/approve",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("without_signature", withoutSignature) },
        ).map { }

    override suspend fun reject(requestId: String, reason: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/approval-requests/$requestId/reject",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("reason", reason) },
        ).map { }

    override suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
        notes: String,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/production-reports/$id/publish",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("published_by", publishedBy)
            put("published_by_id", publishedById)
            put("continuation_type", if (continuation) "CONTINUATION" else "NEW")
            put("publish_notes", notes)
        },
    ).map { }

    // Parsers --------------------------------------------------------------

    private fun parseMetadata(element: JsonElement): SheetMetadata {
        val obj = element as? JsonObject ?: return SheetMetadata()
        val custom = obj.strings("day_types", "dayTypes")
        return SheetMetadata(
            currentShootDay = obj.long("current_shoot_day", "currentShootDay")?.toInt() ?: 0,
            totalDays = obj.text("total_days", "totalDays"),
            dayTypes = (SheetMetadata.STANDARD_DAY_TYPES + custom).distinct(),
            finalApproverIds = obj.strings("final_approver_ids", "finalApproverIds"),
            internalReceiverIds = obj.strings(
                "internal_distribution_receivers",
                "internalDistributionReceivers",
            ),
        )
    }

    private fun parseSummary(obj: JsonObject?): ReportSummary? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return ReportSummary(
            id = id,
            serialNo = obj.text("serial_no", "serialNo"),
            name = obj.text("name"),
            status = ReportStatus.fromWire(obj.text("status")),
            createdBy = obj.text("created_by", "createdBy"),
            createdById = obj.text("created_by_id", "createdById"),
            createdAt = obj.text("created_on", "createdAt"),
            updatedAt = obj.text("updated_on", "updatedAt"),
            publishedAt = obj.text("published_on", "publishedAt"),
            reportType = reportTypeOf(obj),
        )
    }

    /** List rows embed the current revision, so the kind is known without opening the sheet. */
    private fun reportTypeOf(obj: JsonObject): String {
        val revision = obj.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = (revision?.firstOf("payload") ?: obj.firstOf("payload")) as? JsonObject
        val shared = payload?.firstOf("shared") as? JsonObject
        return shared?.text("reportType", "report_type").orEmpty().trim().lowercase()
    }

    private fun parseDetail(obj: JsonObject?): ReportDetail {
        val summary = parseSummary(obj) ?: ReportSummary(
            id = "", serialNo = "", name = "", status = ReportStatus.Unknown,
            createdBy = "", createdById = "", createdAt = "", updatedAt = "", publishedAt = "",
        )
        val revision = obj?.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = revision?.firstOf("payload") ?: obj?.firstOf("payload")
        val approvals = (obj?.firstOf("approval_requests", "approvalRequests") as? JsonArray)
            .elements()
            .mapNotNull { parseApproval(it as? JsonObject) }
        return ReportDetail(
            summary = summary,
            payload = PayloadWire.parse(payload),
            approvals = approvals,
        )
    }

    private fun parseApproval(obj: JsonObject?): ApprovalRequest? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return ApprovalRequest(
            id = id,
            assigneeId = obj.text("assignee_id", "assigneeId"),
            assigneeName = obj.text("assignee_name", "assigneeName"),
            role = obj.text("role"),
            stage = obj.text("stage").ifBlank { "FINAL" },
            status = obj.text("status").ifBlank { "PENDING" },
            reason = obj.text("reason"),
        )
    }
}
