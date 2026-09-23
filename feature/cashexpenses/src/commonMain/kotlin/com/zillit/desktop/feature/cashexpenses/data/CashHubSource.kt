package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.QueryThread
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * A signed POST whose answer is a file rather than an envelope.
 *
 * The register exports stream bytes and `ApiClient` only speaks envelopes, so
 * the host supplies this — the same seam as Bank Reconciliation's. Absent,
 * every export says it cannot download rather than failing quietly.
 */
fun interface CashBinaryPost {
    suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray>
}

/**
 * The routes this module needs that are not the cash service's own.
 *
 * The cost-report lock that bounds a ledger date, Production Setup's
 * companies, the hub's auto-assignment rules and query threads — and the two
 * register exports, which are the cash service's but answer with a file.
 */
internal class CashHubSource(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val binaryPost: CashBinaryPost?,
    private val cashBase: String,
) {
    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"
    private val costReportBase = "${config.apiV2(ZillitService.CostReport).trimEnd('/')}/cost-reports"

    /**
     * The close boundary, read as the web's `useCrLock` reads it.
     *
     * Two readings of one row — the lock route and `last_cr_locked_date` on the
     * combined project settings — and the later wins, because the lock only
     * moves forward. Neither answering is no lock, not an error: a post the
     * server then refuses says why, and a picker frozen by a failed read would
     * stop every post on the production.
     */
    suspend fun lockedThrough(): ZillitResult<String?> = coroutineScope {
        val route = async { json("$costReportBase/lock-period") }
        val settings = async { json("$hubBase/project-settings") }
        val fromRoute = (route.await() as? ZillitResult.Success)?.data?.unwrap()?.let { body ->
            lockDate(body["lockedDate"]) ?: lockDate(body["last_cr_locked_date"])
        }
        val fromSettings = ((settings.await() as? ZillitResult.Success)?.data?.unwrap()?.get("settings") as? JsonObject)
            ?.let { lockDate(it["last_cr_locked_date"]) }
        ZillitResult.Success(listOfNotNull(fromRoute, fromSettings).maxOrNull())
    }

    suspend fun companies(): ZillitResult<List<CashCompany>> =
        json("$hubBase/project-settings/companies").map { data ->
            data.readList(CompanyDto.serializer()).mapNotNull { it.toDomain() }.ifEmpty {
                (data as? JsonObject)?.get("value").readList(CompanyDto.serializer()).mapNotNull { it.toDomain() }
            }
        }

    suspend fun saveAssignmentRule(rule: CashAssignmentRule): ZillitResult<CashAssignmentRule> {
        val body = rule.toJson()
        return apiClient.request(
            verb = if (rule.persisted) HttpVerb.Patch else HttpVerb.Post,
            url = if (rule.persisted) "$hubBase/assignment-rules/${rule.id}" else "$hubBase/assignment-rules",
            serializer = CashRuleDto.serializer(),
            module = RequestModule.ProjectUser,
            // The module rides in the body on create; on update the id says which.
            body = if (rule.persisted) body else JsonObject(body + (MODULE to JsonPrimitive(CASH_MODULE))),
        ).map { it.toDomain() ?: rule.copy(persisted = true) }
    }

    suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/assignment-rules/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    /** The batch's thread; an entity with none yet answers an empty one. */
    suspend fun queryThread(batchId: String): ZillitResult<QueryThread> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = "$hubBase/queries/entity/$QUERY_ENTITY/$batchId",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toThread() }

    suspend fun sendQuery(batchId: String, threadId: String?, text: String): ZillitResult<QueryThread> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = if (threadId == null) "$hubBase/queries" else "$hubBase/queries/$threadId/add",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                if (threadId == null) {
                    put("entity_type", JsonPrimitive(QUERY_ENTITY))
                    put("entity_id", JsonPrimitive(batchId))
                }
                put("query", JsonPrimitive(text))
            },
        ).map { it.toThread() }

    suspend fun exportFloats(format: ExportFormat): ZillitResult<ByteArray> =
        bytes("$cashBase/float-requests/export", buildJsonObject { put("format", JsonPrimitive(format.wire)) })

    /**
     * `{format, expenseType, scope}` — camelCase, as the web's `exportReceipts`
     * sends it. History is every posted batch, so its type is null.
     */
    suspend fun exportReceipts(
        format: ExportFormat,
        expenseType: ExpenseType?,
        historyOnly: Boolean,
    ): ZillitResult<ByteArray> = bytes(
        "$cashBase/claims/export",
        buildJsonObject {
            put("format", JsonPrimitive(format.wire))
            put("expenseType", expenseType?.let { JsonPrimitive(it.wire) } ?: JsonNull)
            put("scope", JsonPrimitive(if (historyOnly) "history" else "all"))
        },
    )

    private suspend fun bytes(url: String, body: JsonObject): ZillitResult<ByteArray> =
        binaryPost?.post(url, body)
            ?: ZillitResult.Failure(ZillitError.Validation(str(S.desktop_cannot_download_exports)))

    private suspend fun json(url: String): ZillitResult<JsonElement> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    )

    private fun JsonElement.unwrap(): JsonObject? {
        val body = this as? JsonObject ?: return null
        return body["value"] as? JsonObject ?: body
    }

    /** A null `data` — no thread yet — is an empty one, not a failure. */
    private fun JsonElement?.toThread(): QueryThread {
        val body = (this as? JsonObject)?.let { it["value"] as? JsonObject ?: it } ?: return QueryThread(null)
        return runCatching { cashLenient.decodeFromJsonElement(QueryThreadDto.serializer(), body).toDomain() }
            .getOrElse { QueryThread(null) }
    }

    private companion object {
        const val MODULE = "module"
        const val CASH_MODULE = "cash_expenses"
        const val QUERY_ENTITY = "cash_claim"
    }
}

/**
 * `YYYY-MM-DD` from whichever shape the boundary arrives in — a date string,
 * an ISO datetime, or epoch millis (sliced in UTC, as the reference server
 * writes it). The web's `normalizeLockedYmd`.
 */
internal fun lockDate(value: JsonElement?): String? {
    val text = (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        ?: return null
    if (YMD.matches(text.take(YMD_LENGTH)) && (text.length == YMD_LENGTH || text[YMD_LENGTH] == 'T')) {
        return text.take(YMD_LENGTH)
    }
    val millis = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return null
    return com.zillit.desktop.feature.cashexpenses.domain.CashDates.utcYmd(millis)
}

private val YMD = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private const val YMD_LENGTH = 10

internal val cashLenient = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
}
