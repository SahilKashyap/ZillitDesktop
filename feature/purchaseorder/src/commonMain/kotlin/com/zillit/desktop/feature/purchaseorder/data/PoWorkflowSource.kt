package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.purchaseorder.domain.PoApprovalTiers
import com.zillit.desktop.feature.purchaseorder.domain.PoCurrencyRates
import com.zillit.desktop.feature.purchaseorder.domain.PoPeriodLock
import com.zillit.desktop.feature.purchaseorder.domain.PoQueryMessage
import com.zillit.desktop.feature.purchaseorder.domain.PoQueryThread
import com.zillit.desktop.feature.purchaseorder.domain.PoTier
import com.zillit.desktop.feature.purchaseorder.domain.PoTierApprover
import com.zillit.desktop.feature.purchaseorder.domain.PoTierConfig
import com.zillit.desktop.feature.purchaseorder.domain.PoTierRule
import com.zillit.desktop.feature.purchaseorder.domain.utcIsoDay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The account hub's routes the order workflow leans on — none of them this
 * service's:
 *
 * - `GET /account-hub/approval-tiers?module=purchase_orders` — who approves which
 *   tier (the web's `approvalTiersApi.list`);
 * - `GET /cost-reports/lock-period` with `/account-hub/project-settings` — the
 *   cost-report lock, read the way the web's `useCrLock` does;
 * - `/account-hub/queries` — the per-order query thread (`queriesApi`);
 * - `GET /account-hub/project-settings/project-currencies` — the rates a
 *   mixed-currency total converts through.
 *
 * Its own class so the repository stays the orders'. Every host is resolved
 * lazily: a configuration without the cost-report service must still be able to
 * list orders.
 */
internal class PoWorkflowSource(private val apiClient: ApiClient, private val config: AppConfig) {

    private val hubBase: String? by lazy {
        runCatching { "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub" }.getOrNull()
    }
    private val costReportBase: String? by lazy {
        runCatching { "${config.baseUrl(ZillitService.CostReport)}/api/v2/cost-reports" }.getOrNull()
    }

    suspend fun approvalTiers(): ZillitResult<PoApprovalTiers> {
        val hub = hubBase ?: return unconfigured()
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "$hub/approval-tiers",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("module" to PO_MODULE),
        ).map { it.toApprovalTiers() }
    }

    /**
     * The lock boundary: the later of the lock route and the project-settings
     * document, because the lock only moves forward and the live route has
     * failed outright on a date stored as a string — the web's `useCrLock`
     * reads both for that reason. Neither answering is "no lock".
     */
    suspend fun periodLock(): ZillitResult<PoPeriodLock> = coroutineScope {
        val route = async {
            costReportBase?.let { base ->
                apiClient.request(
                    verb = HttpVerb.Get,
                    url = "$base/lock-period",
                    serializer = JsonElement.serializer(),
                    module = RequestModule.ProjectUser,
                )
            }
        }
        val settings = async {
            hubBase?.let { hub ->
                apiClient.request(
                    verb = HttpVerb.Get,
                    url = "$hub/project-settings",
                    serializer = JsonElement.serializer(),
                    module = RequestModule.ProjectUser,
                )
            }
        }
        val fromRoute = (route.await() as? ZillitResult.Success)?.data?.routeLockedDate().orEmpty()
        val fromSettings = (settings.await() as? ZillitResult.Success)?.data?.settingsLockedDate().orEmpty()
        ZillitResult.Success(PoPeriodLock(lockedThrough = maxOf(fromRoute, fromSettings)))
    }

    /** `GET /queries/entity/purchase_order/{id}` — null when nobody has asked yet. */
    suspend fun queryThread(orderId: String): ZillitResult<PoQueryThread?> {
        val hub = hubBase ?: return unconfigured()
        // An order nobody has queried answers `data: null` — a state, not an error.
        return apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$hub/queries/entity/$QUERY_ENTITY/$orderId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it?.toQueryThread() }
    }

    /**
     * Sends one message: the first opens the thread (`POST /queries` with the
     * entity), every later one is added to it (`POST /queries/{id}/add`) —
     * exactly the web's `QueryPanel.handleSend`.
     */
    suspend fun sendQuery(orderId: String, threadId: String?, text: String): ZillitResult<PoQueryThread?> {
        val hub = hubBase ?: return unconfigured()
        val (url, body) = if (threadId.isNullOrBlank()) {
            "$hub/queries" to buildJsonObject {
                put("entity_type", JsonPrimitive(QUERY_ENTITY))
                put("entity_id", JsonPrimitive(orderId))
                put("query", JsonPrimitive(text))
            }
        } else {
            "$hub/queries/$threadId/add" to buildJsonObject { put("query", JsonPrimitive(text)) }
        }
        return apiClient.requestOrNull(
            verb = HttpVerb.Post,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = body,
        ).map { it?.toQueryThread() }
    }

    /** The project currencies' rates against the default — `exr`, base 1. */
    suspend fun currencyRates(): ZillitResult<PoCurrencyRates> {
        val hub = hubBase ?: return unconfigured()
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "$hub/project-settings/project-currencies",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it.toCurrencyRates() }
    }

    private fun <T> unconfigured(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Unknown("the account hub service is not configured"))

    companion object {
        const val PO_MODULE = "purchase_orders"

        /** The web's `entityType` for an order's query thread. */
        const val QUERY_ENTITY = "purchase_order"
    }
}

private val workflowJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** `data` bare, or out of the `{ value: … }` wrapper the hub's own routes use. */
private fun JsonElement.unwrapValue(): JsonElement =
    (this as? JsonObject)?.get("value")?.takeIf { it !is JsonNull } ?: this

private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.number(): Double? = text()?.toDoubleOrNull()

// -- approval tiers ---------------------------------------------------------------

/**
 * The tier configuration, in either of its two shapes: an array of config rows
 * (`{ scope, department_id, tiers }`, where `tiers` may itself be a JSON
 * string), or the legacy object keyed by tier number.
 */
internal fun JsonElement.toApprovalTiers(): PoApprovalTiers {
    val body = unwrapValue()
    val rows = (body as? JsonArray) ?: ((body as? JsonObject)?.get("configs") as? JsonArray)
    if (rows != null) return PoApprovalTiers(configs = rows.mapNotNull { (it as? JsonObject)?.toTierConfig() })
    val legacy = (body as? JsonObject)
        ?.filterKeys { key -> key.toIntOrNull() != null }
        ?.mapNotNull { (key, value) ->
            val approvers = (value as? JsonArray)?.mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                row["user_id"].text()?.let { PoTierApprover(it, row["department_id"].text()) }
            }
            approvers?.let { key.toInt() to it }
        }
        ?.toMap()
        .orEmpty()
    return PoApprovalTiers(legacy = legacy)
}

private fun JsonObject.toTierConfig(): PoTierConfig? {
    val scope = this["scope"].text() ?: return null
    val rawTiers = this["tiers"]
    // An array on most rows, a JSON *string* holding one on some — the web's
    // `parseTiers` takes both.
    val tiers = when (rawTiers) {
        is JsonArray -> rawTiers
        is JsonPrimitive -> rawTiers.contentOrNull
            ?.let { text -> runCatching { workflowJson.parseToJsonElement(text) }.getOrNull() } as? JsonArray
        else -> null
    } ?: return null
    return PoTierConfig(
        scope = scope,
        departmentId = this["department_id"].text(),
        tiers = tiers.mapIndexedNotNull { index, element ->
            val tier = element as? JsonObject ?: return@mapIndexedNotNull null
            PoTier(
                order = tier["order"].number()?.toInt() ?: (index + 1),
                rules = (tier["rules"] as? JsonArray).orEmpty().mapNotNull { rule ->
                    val row = rule as? JsonObject ?: return@mapNotNull null
                    PoTierRule(
                        type = row["type"].text() ?: PoApprovalTiers.RULE_DEFAULT,
                        amountThreshold = row["amount_threshold"].number(),
                        userIds = (row["user_ids"] as? JsonArray).orEmpty().mapNotNull { it.text() },
                    )
                },
            )
        }.sortedBy { it.order },
    )
}

// -- the period lock --------------------------------------------------------------

private fun JsonElement.routeLockedDate(): String {
    val body = unwrapValue() as? JsonObject ?: return ""
    return normaliseLockedDate(body["lockedDate"]).ifBlank { normaliseLockedDate(body["last_cr_locked_date"]) }
}

private fun JsonElement.settingsLockedDate(): String {
    val settings = (unwrapValue() as? JsonObject)?.get("settings") as? JsonObject ?: return ""
    return normaliseLockedDate(settings["last_cr_locked_date"])
}

/**
 * `YYYY-MM-DD` out of every shape the lock has taken: the string the live
 * backend stores, a full ISO date-time, or UTC-midnight epoch millis.
 */
internal fun normaliseLockedDate(raw: JsonElement?): String {
    val text = raw.text() ?: return ""
    ISO_DAY.find(text)?.let { return it.groupValues[1] }
    val millis = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return ""
    return millis.utcIsoDay()
}

private val ISO_DAY = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:$|T)")

// -- queries ----------------------------------------------------------------------

/** The thread record — `{ id, queries: [{ query, queried_by, queried_at }] }` — or null. */
internal fun JsonElement.toQueryThread(): PoQueryThread? {
    val record = unwrapValue() as? JsonObject ?: return null
    val id = record["id"].text() ?: record["_id"].text() ?: return null
    return PoQueryThread(
        id = id,
        messages = (record["queries"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val text = row["query"].text() ?: return@mapNotNull null
            PoQueryMessage(text = text, by = row["queried_by"].text(), at = row["queried_at"].toStampOrNull())
        },
    )
}

// -- currencies -------------------------------------------------------------------

internal fun JsonElement.toCurrencyRates(): PoCurrencyRates {
    val body = unwrapValue() as? JsonObject ?: return PoCurrencyRates()
    val rows = (body["currencies"] as? JsonArray).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val code = row["code"].text()?.uppercase() ?: return@mapNotNull null
        row["exr"].number()?.takeIf { it > 0 }?.let { code to it }
    }
    return PoCurrencyRates(defaultCode = body["default"].text()?.uppercase(), rates = rows.toMap())
}
