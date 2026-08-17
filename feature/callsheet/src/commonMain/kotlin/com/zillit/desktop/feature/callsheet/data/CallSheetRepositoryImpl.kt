package com.zillit.desktop.feature.callsheet.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.InternalApprover
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The call-sheet service (`callsheetapi`).
 *
 * Transcription notes, verified against the web client:
 *
 *  - the web appends `/v2` to an env base that already carries `/api`, so
 *    the full prefix here is `/api/v2` — bare `/v2` answers "Route Not
 *    Found" convincingly enough to look like a wrong resource name
 *    (verified live against callsheetapi-dev);
 *  - the body is snake_case at the top level (`created_by`, `project_id`)
 *    while `payload` goes out verbatim — see [PayloadWire];
 *  - editing an existing sheet is `POST .../revisions`, never a PUT;
 *  - `submit-for-approval` takes an EMPTY body — assignees come from the
 *    project metadata written on save;
 *  - approve/reject act on approval-request ids, not sheet ids;
 *  - list responses arrive as `{call_sheets:[...]}` or camelCased
 *    `{callSheets:[...]}` depending on the deployment's normaliser.
 */
class CallSheetRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : CallSheetRepository {

    private val base = config.apiV2(ZillitService.CallSheet).trimEnd('/')

    override suspend fun metadata(projectId: String): ZillitResult<SheetMetadata> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/projects/$projectId/call-sheet-metadata",
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
        url = "$base/projects/$projectId/call-sheet-metadata",
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
        statuses: List<CallSheetStatus>,
        createdById: String?,
        approverId: String?,
    ): ZillitResult<List<CallSheetSummary>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/call-sheets",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap {
            projectId?.takeIf { it.isNotBlank() }?.let { put("project_id", it) }
            if (statuses.isNotEmpty()) put("status", statuses.joinToString(",") { it.wire })
            createdById?.takeIf { it.isNotBlank() }?.let { put("created_by_id", it) }
            approverId?.takeIf { it.isNotBlank() }?.let { put("approver_id", it) }
        },
    ).map { element ->
        val list = (element as? JsonObject)?.firstOf("call_sheets", "callSheets") as? JsonArray
        list.elements().mapNotNull { row -> parseSummary(row as? JsonObject) }
    }

    override suspend fun sheet(id: String): ZillitResult<CallSheetDetail> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/call-sheets/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element ->
        val sheet = (element as? JsonObject)?.firstOf("call_sheet", "callSheet") as? JsonObject
        parseDetail(sheet)
    }

    override suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<CallSheetSummary> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/call-sheets",
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
        val sheet = (element as? JsonObject)?.firstOf("call_sheet", "callSheet") as? JsonObject
        parseSummary(sheet) ?: CallSheetSummary(
            id = "", serialNo = "", name = name, status = CallSheetStatus.Draft,
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
        url = "$base/call-sheets/$id/revisions",
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
        url = "$base/call-sheets/$id",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun submitForApproval(id: String): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/call-sheets/$id/submit-for-approval",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { },
    ).map { }

    override suspend fun submitForInternalApproval(
        id: String,
        approvers: List<InternalApprover>,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/call-sheets/$id/submit-for-internal-approval",
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
        url = "$base/call-sheets/$id/publish",
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

    private fun parseSummary(obj: JsonObject?): CallSheetSummary? {
        if (obj == null) return null
        val id = obj.text("_id", "id")
        if (id.isBlank()) return null
        return CallSheetSummary(
            id = id,
            serialNo = obj.text("serial_no", "serialNo"),
            name = obj.text("name"),
            status = CallSheetStatus.fromWire(obj.text("status")),
            createdBy = obj.text("created_by", "createdBy"),
            createdById = obj.text("created_by_id", "createdById"),
            createdAt = obj.text("created_on", "createdAt"),
            updatedAt = obj.text("updated_on", "updatedAt"),
            publishedAt = obj.text("published_on", "publishedAt"),
        )
    }

    private fun parseDetail(obj: JsonObject?): CallSheetDetail {
        val summary = parseSummary(obj) ?: CallSheetSummary(
            id = "", serialNo = "", name = "", status = CallSheetStatus.Unknown,
            createdBy = "", createdById = "", createdAt = "", updatedAt = "", publishedAt = "",
        )
        val revision = obj?.firstOf("currentRevision", "current_revision") as? JsonObject
        val payload = revision?.firstOf("payload") ?: obj?.firstOf("payload")
        val approvals = (obj?.firstOf("approval_requests", "approvalRequests") as? JsonArray)
            .elements()
            .mapNotNull { parseApproval(it as? JsonObject) }
        return CallSheetDetail(
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
