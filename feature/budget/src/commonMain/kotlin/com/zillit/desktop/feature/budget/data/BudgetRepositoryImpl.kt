package com.zillit.desktop.feature.budget.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMembers
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

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
        get("$base/budget/${type.wire}/$departmentId")
            .mapData { documentsOf(it, json) }

    /** `POST /v2/budget` (`budgetApi/api.js:5-13`). */
    override suspend fun post(
        type: BudgetType,
        departmentId: String,
        file: BudgetFile,
    ): ZillitResult<BudgetDocument> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/budget",
        module = RequestModule.ProjectUser,
        body = postBody(type, departmentId, file),
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
                main = dto?.main.orEmpty().mapNotNull { it.toMember() },
                department = dto?.department.orEmpty().mapNotNull { it.toMember() },
            )
        }

    /** `PUT /v2/budget/update-last-visited/{id}` (`budgetApi/api.js:147-154`). */
    override suspend fun markVisited(documentId: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Put,
        url = "$base/budget/update-last-visited/$documentId",
        module = RequestModule.ProjectUser,
    ).mapData { }

    /** `GET /v2/budget/view-download/count/{id}?action=` (`budgetApi/api.js:133-140`). */
    override suspend fun activityCount(
        documentId: String,
        action: BudgetActivity,
    ): ZillitResult<Int> = get(
        url = "$base/budget/view-download/count/$documentId",
        query = mapOf("action" to action.wire),
    ).mapData { data ->
        val dto = data?.let {
            runCatching { json.decodeFromJsonElement(BudgetCountDto.serializer(), it) }.getOrNull()
        }
        dto?.count
            ?: data?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
            ?: 0
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
        // The list is worth keeping for a train tunnel; the per-document
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
