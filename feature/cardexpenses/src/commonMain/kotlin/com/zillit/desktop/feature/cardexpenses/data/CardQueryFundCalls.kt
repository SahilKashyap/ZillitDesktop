package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cardexpenses.domain.FundRequest
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.QueryMessage
import com.zillit.desktop.feature.cardexpenses.domain.QueryThread
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The two surfaces that live beside the card routes rather than among them.
 *
 * Queries are the Account Hub's shared thread service (`/account-hub/queries`,
 * on the hub's own host), keyed by entity; fund requests are card routes, but
 * answer the hub's way — an empty list arrives as `{}` (`cardExpenses.js:30-35`),
 * so both are read off the raw tree rather than a typed list.
 */
internal class CardQueryFundCalls(private val apiClient: ApiClient, config: AppConfig) {

    private val queries = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/queries"
    private val cards = "${config.baseUrl(ZillitService.CardExpenses)}/api/v2/card-expenses"

    suspend fun thread(entityType: String, entityId: String): ZillitResult<QueryThread> =
        tree(HttpVerb.Get, "$queries/entity/$entityType/$entityId").map { it.toThread() }

    /**
     * The first message creates the thread (`{entity_type, entity_id, query}`);
     * every later one is added to it by id (`{query}`) — the web's `handleSend`.
     */
    suspend fun send(
        thread: QueryThread,
        entityType: String,
        entityId: String,
        text: String,
    ): ZillitResult<QueryThread> {
        val id = thread.id
        val sent = if (id == null) {
            tree(
                HttpVerb.Post,
                queries,
                buildJsonObject {
                    put("entity_type", JsonPrimitive(entityType))
                    put("entity_id", JsonPrimitive(entityId))
                    put("query", JsonPrimitive(text.trim()))
                },
            )
        } else {
            tree(HttpVerb.Post, "$queries/$id/add", buildJsonObject { put("query", JsonPrimitive(text.trim())) })
        }
        // An answer without the record still means the message went: show it
        // on the thread rather than pretending nothing was said.
        return sent.map { it.toThread().takeIf { echoed -> echoed.id != null } ?: thread }
    }

    suspend fun fundRequests(): ZillitResult<List<FundRequest>> =
        tree(HttpVerb.Get, "$cards/fund-requests").map { data ->
            (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toFundRequest() }
        }

    suspend fun createFundRequest(draft: FundRequestDraft): ZillitResult<Unit> =
        tree(
            HttpVerb.Post,
            "$cards/fund-requests",
            buildJsonObject {
                put("bank_id", JsonPrimitive(draft.bankId))
                put("fund_account", JsonPrimitive(draft.fundAccount.trim()))
                put("amount", JsonPrimitive(draft.amountValue))
            },
        ).map { }

    suspend fun receive(id: String): ZillitResult<Unit> =
        tree(HttpVerb.Patch, "$cards/fund-requests/$id/receive", JsonObject(emptyMap())).map { }

    suspend fun cancel(id: String): ZillitResult<Unit> =
        tree(HttpVerb.Patch, "$cards/fund-requests/$id/cancel", JsonObject(emptyMap())).map { }

    private suspend fun tree(verb: HttpVerb, url: String, body: JsonObject? = null): ZillitResult<JsonElement> =
        apiClient.requestOrNull(verb, url, JsonElement.serializer(), RequestModule.ProjectUser, body)
            .map { it ?: JsonNull }

    private fun JsonElement.toThread(): QueryThread {
        val record = this as? JsonObject ?: return QueryThread()
        val messages = (record["queries"].unwrap() as? JsonArray).orEmpty().mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val text = row.text("query") ?: return@mapNotNull null
            QueryMessage(
                userId = row.text("queried_by").orEmpty(),
                text = text,
                at = row.text("queried_at").toEpochMillisOrNull(),
            )
        }
        return QueryThread(id = record.text("id") ?: record.text("_id"), messages = messages)
    }

    private fun JsonObject.toFundRequest(): FundRequest? {
        val id = text("id") ?: return null
        return FundRequest(
            id = id,
            bankId = text("bank_id").orEmpty(),
            fundAccount = text("fund_account").orEmpty(),
            amount = text("amount").toAmountOrNull() ?: 0.0,
            receivedAmount = text("received_amount").toAmountOrNull(),
            currency = text("currency"),
            status = text("status")?.lowercase() ?: FundRequest.REQUESTED,
            requestedBy = text("requested_by"),
            requestedAt = (text("requested_at") ?: text("created_at")).toEpochMillisOrNull(),
            receivedBy = text("received_by"),
            receivedAt = text("received_at").toEpochMillisOrNull(),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    /** A list, or a JSON string holding one. */
    private fun JsonElement?.unwrap(): JsonElement? = when {
        this is JsonPrimitive && isString -> runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(content)
        }.getOrNull()

        else -> this
    }
}
