package com.zillit.desktop.feature.settings.approvals

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two approval queues, against the endpoints the other clients use.
 *
 * | Queue | List | Decide |
 * |---|---|---|
 * | New crew | `GET user/join-project` | `PUT user/join-project` |
 * | Profile changes | `GET user/profile/change-requests` | `PATCH user/profile/change-requests` |
 *
 * The verbs differ between the two on the server's insistence, not this
 * client's: Android's `approveDeclineApi` is a PUT and its
 * `approveDeclineProfileChangeApi` is a PATCH, and the web's mutations agree.
 * Sending the wrong one is a 404 on a path that exists.
 *
 * `approved` is the string `"yes"` or `"no"`, not a boolean. Both other clients
 * send it that way and the field is named as though it were a boolean, which is
 * exactly why this comment exists.
 */
class ApprovalsRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /**
     * Whether approving should also create a mailbox for the new crew member.
     *
     * Off for personal productions, which have no mail. Read at decision time
     * rather than at construction: the graph outlives a production switch.
     */
    private val createsMailboxes: () -> Boolean = { true },
) : ApprovalsRepository {

    private val core get() = config.apiV2(ZillitService.Core)

    private fun url(queue: ApprovalQueue) = when (queue) {
        ApprovalQueue.NewCrew -> "${core}user/join-project"
        ApprovalQueue.ProfileChanges -> "${core}user/profile/change-requests"
    }

    override suspend fun pending(queue: ApprovalQueue): ZillitResult<List<PendingApproval>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = url(queue),
            serializer = ListSerializer(ApprovalRowDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.filter { it.isPending }.mapNotNull { it.toDomain(queue) } }

    override suspend fun decide(
        queue: ApprovalQueue,
        request: PendingApproval,
        approved: Boolean,
    ): ZillitResult<Unit> =
        apiClient.envelope(
            verb = if (queue == ApprovalQueue.NewCrew) HttpVerb.Put else HttpVerb.Patch,
            url = url(queue),
            module = RequestModule.ProjectUser,
            body = approvalBody(queue, request, approved, createsMailboxes()),
        ).map { }
}

/**
 * What a decision puts on the wire.
 *
 * A decline says who and no more. Sending a declined request's department and
 * role back would describe a placement that is not happening — and on the join
 * queue those fields are exactly what the server reads to seat someone.
 *
 * Separate from the client so the shape can be asserted on: "does declining
 * really send `no`" is not a question to answer by reading an HTTP call.
 */
internal fun approvalBody(
    queue: ApprovalQueue,
    request: PendingApproval,
    approved: Boolean,
    createsMailbox: Boolean,
): JsonObject = when (queue) {
    ApprovalQueue.NewCrew -> newCrewBody(request, approved, createsMailbox)
    ApprovalQueue.ProfileChanges -> profileChangeBody(request, approved)
}

/** Keyed on the person and their device: this is a join, not a record. */
private fun newCrewBody(
    request: PendingApproval,
    approved: Boolean,
    createsMailbox: Boolean,
): JsonObject = buildJsonObject {
    put("approved", if (approved) YES else NO)
    put("user_id", request.userId)
    request.deviceId?.let { put("device_id", it) }
    request.projectId?.let { put("project_id", it) }
    if (!approved) return@buildJsonObject

    request.departmentId?.let { put("department_id", it) }
    request.designationId?.let { put("designation_id", it) }
    request.unitId?.let { put("join_unit_id", it) }
    put("keep_name_private", request.keepNamePrivate)
    put("create_email_box", createsMailbox)
}

/** Keyed on the request: one person can have several outstanding. */
private fun profileChangeBody(request: PendingApproval, approved: Boolean): JsonObject =
    buildJsonObject {
        put("approved", if (approved) YES else NO)
        put("request_id", request.id)
        if (!approved) return@buildJsonObject

        request.departmentId?.let { put("department_id", it) }
        request.designationId?.let { put("designation_id", it) }
        request.firstName?.let { put("first_name", it) }
        request.lastName?.let { put("last_name", it) }
        put("full_name", request.displayName)
        put("keep_name_private", request.keepNamePrivate)
    }

private const val YES = "yes"
private const val NO = "no"

/**
 * One row from either queue.
 *
 * The two endpoints answer with the same user shape — Android decodes both into
 * its single `UserData` — so one tolerant DTO covers them. Every field is
 * nullable because most of them are absent on one queue or the other, and a
 * strict reader here would fail the whole list over a missing role.
 */
@Serializable
internal data class ApprovalRowDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("join_unit_id") val unitId: String? = null,
    @SerialName("join_unit_name") val unitName: String? = null,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean? = null,
    @SerialName("requested_on") val requestedOn: Long? = null,
    /**
     * Set once someone has decided.
     *
     * The change-requests endpoint answers with settled rows as well as waiting
     * ones — the web filters on this exact field — so an unfiltered list shows
     * an admin work they already did.
     */
    @SerialName("approved") val approved: JsonPrimitive? = null,
) {
    val isPending: Boolean
        get() = approved == null || approved.content.isBlank() || approved.content == "null"

    fun toDomain(queue: ApprovalQueue): PendingApproval? {
        // A row that names nobody cannot be decided: the join queue is keyed on
        // the user and the change queue on the request, and neither decision
        // can be addressed without its key.
        val resolvedUser = userId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() }
        val resolvedUserId = resolvedUser ?: return null
        val key = when (queue) {
            ApprovalQueue.NewCrew -> resolvedUserId
            ApprovalQueue.ProfileChanges -> id?.takeIf { it.isNotBlank() } ?: return null
        }

        return PendingApproval(
            id = key,
            userId = resolvedUserId,
            fullName = fullName?.trim().orEmpty(),
            firstName = firstName?.takeIf { it.isNotBlank() },
            lastName = lastName?.takeIf { it.isNotBlank() },
            email = email?.takeIf { it.isNotBlank() },
            deviceId = deviceId?.takeIf { it.isNotBlank() },
            projectId = projectId?.takeIf { it.isNotBlank() },
            departmentId = departmentId?.takeIf { it.isNotBlank() },
            departmentName = departmentName?.takeIf { it.isNotBlank() },
            designationId = designationId?.takeIf { it.isNotBlank() },
            designationName = designationName?.takeIf { it.isNotBlank() },
            unitId = unitId?.takeIf { it.isNotBlank() },
            unitName = unitName?.takeIf { it.isNotBlank() },
            keepNamePrivate = keepNamePrivate == true,
            requestedAtMillis = requestedOn?.takeIf { it > 0 },
        )
    }
}
