package com.zillit.desktop.feature.budget.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetActivityRow
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetMembers
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The budget service.
 *
 * **On the C&C host, not the budget one.** `BUDGET_BASE_URL` points at
 * `budgetapi`, and none of these paths live there: the web maps its
 * `budgetBase` to `VITE_CNC_BASE_URL` (`src/config.js:112`), so `/v2/budget…`
 * is served by `cncapi` alongside the chat that discusses it. Sending these to
 * the host whose name matches gets a 404 and a long afternoon.
 *
 * The web is the only reference for this tool — the phones never shipped it —
 * so every route below cites the web file that calls it.
 */
class BudgetRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** The chat socket — the conversation list is a socket ask, not a route. Null: no list. */
    private val socket: SocketEventBus? = null,
    private val projectId: () -> String? = { null },
    private val userId: () -> String? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BudgetRepository {

    private val base = config.apiV2(ZillitService.Chat).trimEnd('/')

    /** `GET /v2/budget/list` (`budgetApi/api.js:14-22`). */
    override suspend fun documents(): ZillitResult<List<BudgetDocument>> =
        get("$base/budget/list", cacheAs = "$base/budget/list")
            .mapData { documentsOf(it, json) }

    /** `GET /v2/budget/{type}/{departmentId}` (`budgetApi/api.js:24-33`). */
    override suspend fun documentsOf(
        type: BudgetType,
        departmentId: String,
    ): ZillitResult<List<BudgetDocument>> =
        get("$base/budget/${type.wire}/$departmentId", cacheAs = "$base/budget/${type.wire}/$departmentId")
            .mapData { documentsOf(it, json) }

    /** `POST /v2/budget` (`budgetApi/api.js:5-13`). */
    override suspend fun post(upload: BudgetUpload): ZillitResult<BudgetDocument> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/budget",
        module = RequestModule.ProjectUser,
        body = postBody(upload),
    ).mapData { documentsOf(it, json).firstOrNull() }
        .flatMapNotNull("the budget was saved but came back empty")

    /** `DELETE /v2/budget` with `{budget_ids}` (`budgetApi/api.js:35-43`). */
    override suspend fun delete(documentIds: List<String>): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$base/budget",
        module = RequestModule.ProjectUser,
        body = deleteBody(documentIds),
    ).mapData { }

    /** `GET /v2/budget-users/{departmentId}` (`budgetApi/api.js:44-51`). */
    override suspend fun members(departmentId: String): ZillitResult<BudgetMembers> =
        get("$base/budget-users/$departmentId").mapData { data ->
            val dto = data?.let {
                runCatching { json.decodeFromJsonElement(BudgetMembersDto.serializer(), it) }.getOrNull()
            }
            BudgetMembers(
                main = dto?.main.toMembers(json),
                department = dto?.department.toMembers(json),
            )
        }

    /** `PUT /v2/budget/update-last-visited/{id}` (`budgetApi/api.js:147-154`). */
    override suspend fun markVisited(documentId: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Put,
        url = "$base/budget/update-last-visited/$documentId",
        module = RequestModule.ProjectUser,
    ).mapData { }

    /** `GET /v2/budget/view-download/{action}/{id}` (`budgetApi/api.js:116-123`). */
    override suspend fun record(documentId: String, activity: BudgetActivity): ZillitResult<BudgetDocument?> =
        get("$base/budget/view-download/${activity.wire}/$documentId")
            .mapData { documentsOf(it, json).firstOrNull() }

    /** `GET /v2/budget/view-download/count/{id}?action=` (`budgetApi/api.js:125-132`). */
    override suspend fun activity(
        documentId: String,
        activity: BudgetActivity,
    ): ZillitResult<List<BudgetActivityRow>> = get(
        url = "$base/budget/view-download/count/$documentId",
        query = mapOf("action" to activity.wire),
    ).mapData { activityRowsOf(it) }

    /** The socket's `budget:recent:list` (`cncEmit.js:482-511`). */
    override suspend fun chats(
        mode: BudgetMode,
        departmentId: String,
        documentId: String,
    ): ZillitResult<List<BudgetChatEntry>> {
        val bus = socket ?: return ZillitResult.Success(emptyList())
        val project = projectId() ?: return ZillitResult.Failure(ZillitError.Unauthorized("no open project"))
        val me = userId() ?: return ZillitResult.Failure(ZillitError.Unauthorized("no signed-in user"))
        return bus.emitForAck(
            ZillitSocketEvents.Budget.RecentList,
            chatListBody(mode, project, me, departmentId, documentId),
            JsonElement.serializer(),
        ).map { ack -> chatEntriesOf(ack) }
    }

    /** `POST /v2/chat-room` (`budgetApi/api.js:91-98`, body from `CommonBudget.jsx:createGroup`). */
    override suspend fun createRoom(
        mode: BudgetMode,
        departmentId: String,
        documentId: String,
        name: String,
        memberIds: List<String>,
    ): ZillitResult<BudgetChatEntry.Group> {
        val me = userId() ?: return ZillitResult.Failure(ZillitError.Unauthorized("no signed-in user"))
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/chat-room",
            module = RequestModule.ProjectUser,
            body = createRoomBody(mode, departmentId, documentId, me, name, memberIds),
        ).mapData { createdGroupOf(it) }
            .flatMapNotNull("the room was created but came back empty")
    }

    // -- plumbing ------------------------------------------------------------

    private suspend fun get(
        url: String,
        query: Map<String, Any?> = emptyMap(),
        cacheAs: String? = null,
    ) = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
        queryParameters = query,
        // The lists are worth keeping for a train tunnel; the per-document
        // reads are not, and a stale count is worse than no count.
        options = cacheAs?.let { CallOptions(cacheAs = it) } ?: CallOptions(),
    )

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(
        transform: (JsonElement?) -> T,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> if (data.status == 1) {
            ZillitResult.Success(transform(data.data))
        } else {
            ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = data.message))
        }
    }

    /** A success whose payload is missing is a failure with a sentence, not a null. */
    private fun <T : Any> ZillitResult<T?>.flatMapNotNull(complaint: String): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> data
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = complaint))
    }
}
