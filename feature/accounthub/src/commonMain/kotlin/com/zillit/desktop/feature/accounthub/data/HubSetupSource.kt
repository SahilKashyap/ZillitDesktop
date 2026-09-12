package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The three setup registers that are their own resources rather than slices
 * of the project-settings document: payroll groups (on the payroll host),
 * auto-assignment rules and the chart's layers (on the hub).
 *
 * Split out of [AccountHubRepositoryImpl] the way [HubReportSource] was — the
 * repository mirrors an interface with one method per slice, and these three
 * carried enough CRUD of their own to make it the largest class in the module.
 * The repository still owns the interface; this is where the calls live.
 */
@Suppress("TooManyFunctions") // Three registers, four verbs each.
internal class HubSetupSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) {
    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    /** Payroll groups live on the payroll service, not the hub's. */
    private val payrollBase = "${config.apiV2(ZillitService.Payroll).trimEnd('/')}/payroll"

    suspend fun payrollGroups(): ZillitResult<List<PayrollGroup>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$payrollBase/payroll-groups",
        serializer = ListSerializer(PayrollGroupDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun createPayrollGroup(group: PayrollGroup): ZillitResult<PayrollGroup> =
        writeGroup(HttpVerb.Post, "$payrollBase/payroll-groups", group)

    suspend fun updatePayrollGroup(group: PayrollGroup): ZillitResult<PayrollGroup> =
        writeGroup(HttpVerb.Patch, "$payrollBase/payroll-groups/${group.id}", group)

    suspend fun deletePayrollGroup(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$payrollBase/payroll-groups/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    private suspend fun writeGroup(verb: HttpVerb, url: String, group: PayrollGroup): ZillitResult<PayrollGroup> =
        apiClient.request(
            verb = verb,
            url = url,
            serializer = PayrollGroupDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("assignee_id", group.assigneeId.trim().orNull())
                put("user_ids", group.userIds.toJsonArray())
                put("department_ids", group.departmentIds.toJsonArray())
                put("designation_ids", group.designationIds.toJsonArray())
            },
        ).map { it.toDomain() ?: group }


    // -- auto-assignment rules ----------------------------------------------

    suspend fun assignmentRules(module: String): ZillitResult<List<AssignmentRule>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/assignment-rules",
        serializer = ListSerializer(AssignmentRuleDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("module" to module),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun createAssignmentRule(rule: AssignmentRule): ZillitResult<AssignmentRule> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$hubBase/assignment-rules",
            serializer = AssignmentRuleDto.serializer(),
            module = RequestModule.ProjectUser,
            // The module rides in the body on create; on update the id already says which.
            body = JsonObject(rule.toJson() + ("module" to JsonPrimitive(rule.module))),
        ).map { it.toDomain() ?: rule.copy(persisted = true) }

    suspend fun updateAssignmentRule(rule: AssignmentRule): ZillitResult<AssignmentRule> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$hubBase/assignment-rules/${rule.id}",
            serializer = AssignmentRuleDto.serializer(),
            module = RequestModule.ProjectUser,
            body = JsonObject(rule.toJson()),
        ).map { it.toDomain() ?: rule }

    suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/assignment-rules/$id",
        module = RequestModule.ProjectUser,
    ).map { }


    // -- layers (tracking sets) ---------------------------------------------

    suspend fun trackingSets(): ZillitResult<List<TrackingSet>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/tracking-sets",
        serializer = ListSerializer(TrackingSetDto.serializer()),
        module = RequestModule.ProjectUser,
        // One round trip rather than one per set: the screen always draws the
        // codes under their set, so fetching sets alone is never enough.
        queryParameters = mapOf("include_nodes" to "true"),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun createTrackingSet(set: TrackingSet): ZillitResult<TrackingSet> =
        writeTrackingSet(HttpVerb.Post, "$hubBase/tracking-sets", set)

    suspend fun updateTrackingSet(set: TrackingSet): ZillitResult<TrackingSet> =
        writeTrackingSet(HttpVerb.Patch, "$hubBase/tracking-sets/${set.id}", set)

    suspend fun deleteTrackingSet(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/tracking-sets/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    suspend fun createTrackingNode(node: TrackingNode): ZillitResult<TrackingNode> =
        writeTrackingNode(HttpVerb.Post, "$hubBase/tracking-sets/${node.setId}/nodes", node)

    suspend fun updateTrackingNode(node: TrackingNode): ZillitResult<TrackingNode> =
        writeTrackingNode(HttpVerb.Patch, "$hubBase/tracking-sets/${node.setId}/nodes/${node.id}", node)

    suspend fun deleteTrackingNode(setId: String, id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/tracking-sets/$setId/nodes/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    /** `{ name, color, prefix?, active }` — a blank prefix is omitted so the server derives one. */
    private suspend fun writeTrackingSet(verb: HttpVerb, url: String, set: TrackingSet): ZillitResult<TrackingSet> =
        apiClient.request(
            verb = verb,
            url = url,
            serializer = TrackingSetDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("name", JsonPrimitive(set.name.trim()))
                put("color", JsonPrimitive(set.color))
                set.prefix.trim().uppercase().takeIf { it.isNotEmpty() }?.let { put("prefix", JsonPrimitive(it)) }
                put("active", JsonPrimitive(set.isActive))
            },
        ).map { it.toDomain() ?: set }

    /** `parent_id` and `is_header` always null/false in v1 — never edited, as on the web. */
    private suspend fun writeTrackingNode(verb: HttpVerb, url: String, node: TrackingNode): ZillitResult<TrackingNode> =
        apiClient.request(
            verb = verb,
            url = url,
            serializer = TrackingNodeDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("parent_id", JsonNull)
                put("code", JsonPrimitive(node.code.trim()))
                put("label", JsonPrimitive(node.name.trim()))
                put("description", node.description.trim().orNull())
                put("is_header", JsonPrimitive(false))
                put("active", JsonPrimitive(node.isActive))
            },
        ).map { it.toDomain(node.setId) ?: node }
}
