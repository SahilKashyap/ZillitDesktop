package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cardexpenses.domain.CardCoaAccount
import com.zillit.desktop.feature.cardexpenses.domain.CardPeriodLock
import com.zillit.desktop.feature.cardexpenses.domain.CardTaxType
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs
import com.zillit.desktop.feature.cardexpenses.domain.TrackingNode
import com.zillit.desktop.feature.cardexpenses.domain.TrackingSet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * What the accountant's process pages read besides the card routes.
 *
 * None of it belongs to the card service, which is why the desktop had none
 * of it: the close boundary is the cost report's (`useCrLock` →
 * `/cost-reports/lock-period` on the cost-report host, with the hub's
 * project-settings copy as the fallback — the later of the two wins); tax
 * types and account tags are Production Setup's (`/account-hub/project-settings`);
 * the chart and the Layers sets are the hub's. Each part is optional: a read
 * that fails leaves that part empty rather than failing the page.
 */
internal class CardProcessCalls(private val apiClient: ApiClient, config: AppConfig) {

    private val hub = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"
    private val lockUrl = "${config.baseUrl(ZillitService.CostReport)}/api/v2/cost-reports/lock-period"

    suspend fun references(): ZillitResult<ProcessRefs> = coroutineScope {
        val lock = async { tree(lockUrl) }
        val settings = async { tree("$hub/project-settings") }
        val chart = async { tree("$hub/chart-of-accounts", mapOf("active_only" to "true")) }
        val sets = async { tree("$hub/tracking-sets", mapOf("active_only" to "true", "include_nodes" to "true")) }

        val settingsDoc = (settings.await() as? ZillitResult.Success)?.data?.settingsDocument()
        val routeLock = (lock.await() as? ZillitResult.Success)?.data?.let(::parseLockRoute)
        val settingsLock = settingsDoc?.let(::parseSettingsLock)
        ZillitResult.Success(
            ProcessRefs(
                loaded = true,
                lock = laterLock(routeLock, settingsLock) ?: CardPeriodLock(),
                taxTypes = settingsDoc?.let(::parseTaxTypes).orEmpty(),
                taxTypesKnown = settingsDoc != null,
                accounts = (chart.await() as? ZillitResult.Success)?.data?.let(::parseChart).orEmpty(),
                trackingSets = (sets.await() as? ZillitResult.Success)?.data?.let(::parseTrackingSets).orEmpty(),
                assetTags = settingsDoc?.let(::parseAssetTags).orEmpty(),
            ),
        )
    }

    private suspend fun tree(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<JsonElement> =
        apiClient.requestOrNull(
            HttpVerb.Get,
            url,
            JsonElement.serializer(),
            RequestModule.ProjectUser,
            queryParameters = query,
        ).map { it ?: JsonNull }
}

/**
 * `POST /approvals/:id/approve` — the step being signed and who signed it
 * (`ApprovalQueuePage.jsx:180-183`): `tier_number` is one past the sign-offs
 * already collected.
 */
internal fun approveBody(tierNumber: Int, userId: String): JsonObject = buildJsonObject {
    put("tier_number", JsonPrimitive(tierNumber))
    put("user_id", JsonPrimitive(userId))
}

/** `POST /approvals/:id/reject` — why, and who (`ApprovalQueuePage.jsx:197-200`). */
internal fun rejectBody(reason: String, userId: String): JsonObject = buildJsonObject {
    put("reason", JsonPrimitive(reason))
    put("user_id", JsonPrimitive(userId))
}

// -- parsing ------------------------------------------------------------------------

/** The project-settings document: `data.settings`, bare or inside the hub's `{ value }` wrapper. */
internal fun JsonElement.settingsDocument(): JsonObject? {
    val root = (this as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it } ?: return null
    return root["settings"] as? JsonObject ?: root
}

/** `useProjectTaxTypes`: `{identifier, label, value (percent), is_recoverable, country}`. */
internal fun parseTaxTypes(settings: JsonObject): List<CardTaxType> =
    settings.rows("tax_types").mapNotNull { row ->
        val identifier = row.text("identifier") ?: return@mapNotNull null
        CardTaxType(
            identifier = identifier,
            label = row.text("label") ?: row.text("name") ?: identifier,
            rate = row.text("value")?.removeSuffix("%")?.trim().toAmountOrNull(),
            recoverable = (row["is_recoverable"] as? JsonPrimitive)?.booleanOrNull == true,
            country = row.text("country") ?: row.text("country_code").orEmpty(),
        )
    }

/** `useProjectAssetTags`: the Account Tags, non-blank strings only. */
internal fun parseAssetTags(settings: JsonObject): List<String> =
    (settings["asset_tags"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    }

private fun parseSettingsLock(settings: JsonObject): CardPeriodLock? =
    normaliseDay(settings["last_cr_locked_date"]).takeIf { it.isNotEmpty() }
        ?.let { CardPeriodLock(lockedThrough = it, timeZone = settings.text("timezone").orEmpty()) }

/** `GET /cost-reports/lock-period`: `lockedDate` on the read route, `last_cr_locked_date` on the row. */
internal fun parseLockRoute(data: JsonElement): CardPeriodLock? {
    val obj = (data as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it } ?: return null
    val day = normaliseDay(obj["lockedDate"]).ifEmpty { normaliseDay(obj["last_cr_locked_date"]) }
    return CardPeriodLock(lockedThrough = day, timeZone = obj.text("tz").orEmpty())
}

/** The lock only moves forward, so of two readings of one boundary the later wins. */
internal fun laterLock(first: CardPeriodLock?, second: CardPeriodLock?): CardPeriodLock? {
    val readings = listOfNotNull(first, second)
    val set = readings.filter { it.isSet }
    val winner = set.maxByOrNull { it.lockedThrough } ?: return readings.firstOrNull()
    val zone = winner.timeZone.ifBlank { readings.map { it.timeZone }.firstOrNull { it.isNotBlank() }.orEmpty() }
    return winner.copy(timeZone = zone)
}

/** `normalizeLockedYmd`: `YYYY-MM-DD`, an ISO instant, or epoch millis (sliced in UTC) → `YYYY-MM-DD`. */
private fun normaliseDay(raw: JsonElement?): String {
    val text = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
    if (text.isEmpty()) return ""
    ISO_DAY.find(text)?.let { return it.groupValues[1] }
    val millis = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return ""
    return Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()
}

/**
 * The postable rows of the chart (`CoaCodeInput`): categories and codes —
 * never headers or sections — still active, and not switched off for posting;
 * ascending by code, numbers first.
 */
internal fun parseChart(data: JsonElement): List<CardCoaAccount> =
    rowsOf(data)
        .filter { row ->
            val type = row.text("line_type")
            (type == null || type == "category" || type == "sub_category") &&
                (row["is_active"] as? JsonPrimitive)?.booleanOrNull != false &&
                (row["posting_box"] as? JsonPrimitive)?.booleanOrNull != false
        }
        .mapNotNull { row -> row.text("code")?.let { CardCoaAccount(it, row.text("name").orEmpty()) } }
        .distinctBy { it.code }
        .sortedWith(compareBy({ it.code.toDoubleOrNull() == null }, { it.code.toDoubleOrNull() ?: 0.0 }, { it.code }))

/** Active Layers sets with their active, non-header codes (`TrackingCodesPicker.jsx:409-410`). */
internal fun parseTrackingSets(data: JsonElement): List<TrackingSet> =
    rowsOf(data)
        .filter { (it["active"] as? JsonPrimitive)?.booleanOrNull != false }
        .mapNotNull { set ->
            val id = set.text("id") ?: set.text("_id") ?: return@mapNotNull null
            TrackingSet(
                id = id,
                name = set.text("name").orEmpty(),
                nodes = (set["nodes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { node ->
                        (node["active"] as? JsonPrimitive)?.booleanOrNull != false &&
                            (node["is_header"] as? JsonPrimitive)?.booleanOrNull != true
                    }
                    .mapNotNull { node ->
                        node.text("code")?.let { TrackingNode(it, node.text("label") ?: node.text("name").orEmpty()) }
                    },
            )
        }

/** A list, bare or under `rows` / `data` / `items`. */
private fun rowsOf(data: JsonElement): List<JsonObject> {
    val list = when (data) {
        is JsonArray -> data
        is JsonObject -> listOf("rows", "data", "items", "value").firstNotNullOfOrNull { data[it] as? JsonArray }
        else -> null
    }
    return list.orEmpty().mapNotNull { it as? JsonObject }
}

private fun JsonObject.rows(key: String): List<JsonObject> {
    val element = this[key]
    val list = when {
        element is JsonArray -> element
        element is JsonPrimitive && element.isString ->
            runCatching { Json.parseToJsonElement(element.content) as? JsonArray }.getOrNull()

        else -> null
    }
    return list.orEmpty().mapNotNull { it as? JsonObject }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

private val ISO_DAY = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:$|T)")
