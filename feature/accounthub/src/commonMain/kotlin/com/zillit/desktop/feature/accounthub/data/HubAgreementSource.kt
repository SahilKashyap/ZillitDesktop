package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.AgreementRuleImport
import com.zillit.desktop.feature.accounthub.domain.AgreementRuleRow
import com.zillit.desktop.feature.accounthub.domain.ImportedRules
import com.zillit.desktop.feature.accounthub.domain.PayRateBasis
import com.zillit.desktop.feature.accounthub.domain.PayRateType
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.UnionAgreementSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.random.Random

/**
 * The union agreements the non-union breakdown can import from — three reads
 * of the deal-memo service, the way the web's `useWizardData` and
 * `ImportAgreementRulesModal` make them. The hub reaches four services
 * already; this is the fifth, and deliberate: the rules live there.
 */
internal class HubAgreementSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) {
    private val base = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"

    /** The territories with at least one published agreement — lower-cased ids. */
    suspend fun coveredTerritories(): ZillitResult<Set<String>> =
        read("$base/branches/covered-territories").map { data ->
            (data as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf(String::isNotBlank) }
                .toSet()
        }

    /** The agreements published for one territory; the listing answers a bare array or `{ union: [...] }`. */
    suspend fun agreements(territory: String): ZillitResult<List<UnionAgreementSummary>> =
        read("$base/agreements", mapOf("territory" to territory)).map { data ->
            val rows = when (data) {
                is JsonArray -> data
                is JsonObject -> data["union"] as? JsonArray
                else -> null
            }
            rows.orEmpty().mapNotNull { row ->
                val json = row as? JsonObject ?: return@mapNotNull null
                val id = json.text("_identifier") ?: return@mapNotNull null
                UnionAgreementSummary(
                    identifier = id,
                    name = json.text("name") ?: json.text("label") ?: id,
                    territory = json.text("territory"),
                )
            }
        }

    /** One agreement's rule tables, projected into the breakdown's three lists. */
    suspend fun agreementRules(identifier: String): ZillitResult<ImportedRules> =
        read("$base/agreements/$identifier").flatMap { data ->
            val doc = data as? JsonObject ?: return@flatMap ZillitResult.Failure(
                ZillitError.Serialization("agreement $identifier came back empty"),
            )
            ZillitResult.Success(
                AgreementRuleImport.project(
                    overtimes = doc.rows("overtimes"),
                    premiums = doc.rows("premiums"),
                    turnarounds = doc.rows("turnaround"),
                    salt = Random.nextInt(0, SALT_SPACE).toString(SALT_RADIX),
                ),
            )
        }

    private suspend fun read(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<JsonElement?> =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = query,
        ).map { it.data }

    private companion object {
        const val SALT_SPACE = 1 shl 30
        const val SALT_RADIX = 36
    }
}

/** A block's rows; the backends serialise an empty list as `{}` rather than `[]`, so both are guarded. */
private fun JsonObject.rows(block: String): List<AgreementRuleRow> =
    ((this[block] as? JsonObject)?.get("rows") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toRuleRow() }

/**
 * One published row. The rate is read through the engine's own normalisation
 * — `rate_type`/`rate_amount` first, then the legacy `multiplier` / `flat` /
 * `percentage` / `amount` spellings in that order.
 */
private fun JsonObject.toRuleRow(): AgreementRuleRow = AgreementRuleRow(
    id = text("id").orEmpty(),
    label = text("label") ?: text("raw_label").orEmpty(),
    rateType = PayRateType.from(rateTypeWire()),
    rateAmount = LEGACY_AMOUNT_KEYS.firstNotNullOfOrNull { number(it) },
    basis = text("basis")?.let { wire -> PayRateBasis.entries.firstOrNull { it.wire == wire } },
    trigger = firstTrigger(),
    capped = text("cap_type") == "capped",
    capAmount = number("cap_amount"),
    nominalCode = text("nominal_code").orEmpty(),
    note = text("note").orEmpty(),
)

/** `rate_type` when stated; otherwise whichever legacy spelling carries a number, in the engine's order. */
private fun JsonObject.rateTypeWire(): String? =
    text("rate_type") ?: LEGACY_TYPE_KEYS.firstOrNull { number(it) != null }

/** The first trigger entry, read through the settings DTO so the padding is stripped the same way. */
private fun JsonObject.firstTrigger(): PayTrigger =
    ((this["triggers"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let { entry ->
        runCatching { accountHubJson.decodeFromJsonElement(PayTriggerDto.serializer(), entry).toDomain() }.getOrNull()
    } ?: PayTrigger()

private val LEGACY_TYPE_KEYS = listOf("multiplier", "flat", "percentage")
private val LEGACY_AMOUNT_KEYS = listOf("rate_amount", "amount", "multiplier", "flat", "percentage")

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** A finite number, or null — a numeric string counts, as the engine's own reader allows. */
private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { primitive ->
        if (primitive.isString) primitive.content.toDoubleOrNull() else primitive.doubleOrNull
    }?.takeIf { it.isFinite() }
