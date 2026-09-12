package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.purchaseorder.domain.AssetFilters
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoDescriptionFormat
import com.zillit.desktop.feature.purchaseorder.domain.PoNominal
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsBundle
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The Settings tab's routes: `/api/v2/purchase-orders/settings` on this
 * service, and the hub's `/assignment-rules`, `/chart-of-accounts` and
 * `/project-settings/asset-tags` — the web's `po-settings.js` and
 * `assignment-rules.js`. Its own class so the repository stays the orders'.
 */
internal class PoSettingsSource(private val apiClient: ApiClient, config: AppConfig) {

    private val settingsUrl = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders/settings"
    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    /** The document and the module's rules together, as one GET answers both. */
    suspend fun settings(): ZillitResult<PoSettingsBundle> = apiClient.request(
        verb = HttpVerb.Get,
        url = settingsUrl,
        serializer = PoSettingsDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toBundle() }

    suspend fun saveDescriptionFormat(format: PoDescriptionFormat): ZillitResult<PoSettings> =
        patch(buildJsonObject { put("description_format", JsonPrimitive(format.wire)) })

    suspend fun saveRentalSplit(autoSplit: Boolean, splitType: PoSplitType): ZillitResult<PoSettings> = patch(
        buildJsonObject {
            put("auto_split_rentals", JsonPrimitive(autoSplit))
            put("default_split_type", JsonPrimitive(splitType.wire))
            // Always on — the web sends both as true with every rental save.
            put("require_effective_date", JsonPrimitive(true))
            put("enforce_period_close", JsonPrimitive(true))
        },
    )

    /** One PATCH for both keys — they are one card with one Save on the web. */
    suspend fun saveNumbering(prefix: String, allowAmendAfterApproval: Boolean): ZillitResult<PoSettings> = patch(
        buildJsonObject {
            put("po_number_prefix", JsonPrimitive(PoSettings.normalisePrefix(prefix)))
            put("allow_amend_after_approval", JsonPrimitive(allowAmendAfterApproval))
        },
    )

    suspend fun saveTermsDocument(document: PoAttachment): ZillitResult<PoSettings> =
        patch(buildJsonObject { put("terms_attachment", document.toJson()) })

    suspend fun saveAssetFilters(filters: AssetFilters): ZillitResult<PoSettings> =
        patch(buildJsonObject { put("asset_filters", filters.toJson()) })

    private suspend fun patch(body: JsonObject): ZillitResult<PoSettings> = apiClient.request(
        verb = HttpVerb.Patch,
        url = settingsUrl,
        serializer = PoSettingsDto.serializer(),
        module = RequestModule.ProjectUser,
        body = body,
    ).map { it.toDomain() }

    // -- auto-assignment rules ----------------------------------------------

    suspend fun createRule(rule: PoAssignmentRule): ZillitResult<PoAssignmentRule> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$hubBase/assignment-rules",
        serializer = AssignmentRuleDto.serializer(),
        module = RequestModule.ProjectUser,
        // The module rides in the body on create; on update the id already says which.
        body = JsonObject(rule.toJson() + ("module" to JsonPrimitive(PO_FORM_MODULE))),
    ).map { it.toDomain() ?: rule.copy(persisted = true) }

    suspend fun updateRule(rule: PoAssignmentRule): ZillitResult<PoAssignmentRule> = apiClient.request(
        verb = HttpVerb.Patch,
        url = "$hubBase/assignment-rules/${rule.id}",
        serializer = AssignmentRuleDto.serializer(),
        module = RequestModule.ProjectUser,
        body = JsonObject(rule.toJson()),
    ).map { it.toDomain() ?: rule }

    suspend fun deleteRule(id: String): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Delete, "$hubBase/assignment-rules/$id", RequestModule.ProjectUser).map { }

    // -- what the pickers offer ---------------------------------------------

    /** The chart's postable lines — the web's `useCoaLeafRows`. */
    suspend fun nominalCodes(): ZillitResult<List<PoNominal>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/chart-of-accounts",
        serializer = ListSerializer(CoaRowDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("active_only" to "true"),
    ).map { rows -> rows.leaves() }

    suspend fun assetTags(): ZillitResult<List<String>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/project-settings/asset-tags",
        serializer = ValueListDto.serializer(String.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.orEmpty().filter(String::isNotBlank) }
}

/** The settings document as the service sends it; the rules ride along on a GET. */
@Serializable
internal data class PoSettingsDto(
    @SerialName("description_format") val descriptionFormat: String? = null,
    @SerialName("auto_split_rentals") val autoSplitRentals: Boolean? = null,
    @SerialName("default_split_type") val splitType: String? = null,
    @SerialName("po_number_prefix") val numberPrefix: String? = null,
    @SerialName("allow_amend_after_approval") val allowAmend: Boolean? = null,
    /** An attachment, null, or `{}` where the web sends null. */
    @SerialName("terms_attachment") val terms: PoAttachmentDto? = null,
    @SerialName("asset_filters") val assetFilters: AssetFiltersDto? = null,
    @SerialName("assignment_rules") val rules: List<AssignmentRuleDto>? = null,
) {
    fun toDomain(): PoSettings = PoSettings(
        descriptionFormat = PoDescriptionFormat.from(descriptionFormat),
        // Absent is on, which is how the web reads it too.
        autoSplitRentals = autoSplitRentals != false,
        splitType = PoSplitType.from(splitType),
        numberPrefix = PoSettings.normalisePrefix(numberPrefix.orEmpty()),
        // A terms row with no key is not a document — `{}` is the server's null.
        termsDocument = terms?.toDomain(),
        allowAmendAfterApproval = allowAmend == true,
        assetFilters = assetFilters?.toDomain() ?: AssetFilters(),
    )

    fun toBundle(): PoSettingsBundle = PoSettingsBundle(toDomain(), rules.orEmpty().mapNotNull { it.toDomain() })
}

/**
 * `asset_filters` as stored: each sub-rule independently nullable, and the
 * server normalises on read, so "no rule" always arrives in one shape.
 */
@Serializable
internal data class AssetFiltersDto(
    @SerialName("price") val price: PriceRangeDto? = null,
    @SerialName("exp_type") val expType: JsonElement? = null,
    @SerialName("tags") val tags: JsonElement? = null,
) {
    /** The web's `mapAssetFiltersFromDb`: `["*"]` is the server's every-type sentinel, the same as choosing none. */
    fun toDomain(): AssetFilters {
        val types = expType.asStringList()
        return AssetFilters(
            priceLow = price?.low?.contentOrNull.orEmpty(),
            priceHigh = price?.high?.contentOrNull.orEmpty(),
            expTypes = if ("*" in types) emptyList() else types,
            tags = tags.asStringList(),
        )
    }
}

@Serializable
internal data class PriceRangeDto(
    @SerialName("low") val low: JsonPrimitive? = null,
    @SerialName("high") val high: JsonPrimitive? = null,
)

/**
 * One rule as the hub stores it.
 *
 * The three lists occasionally arrive as JSON encoded into a string — an
 * unparsed jsonb column — so each is read through [asStringList].
 */
@Serializable
internal data class AssignmentRuleDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("departments") val departments: JsonElement? = null,
    @SerialName("vendors") val vendors: JsonElement? = null,
    @SerialName("nominal_codes") val nominalCodes: JsonElement? = null,
    @SerialName("amount_min") val amountMin: JsonPrimitive? = null,
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("priority") val priority: Int? = null,
) {
    fun toDomain(): PoAssignmentRule? = (id ?: altId)?.takeIf { it.isNotBlank() }?.let {
        PoAssignmentRule(
            id = it,
            departments = departments.asStringList(),
            vendors = vendors.asStringList(),
            nominalCodes = nominalCodes.asStringList(),
            amountMin = amountMin?.contentOrNull.orEmpty().asAmountText(),
            assignTo = targetUserId.orEmpty(),
            isActive = isActive != false,
            priority = priority ?: 0,
            persisted = true,
        )
    }
}

/** A row of the chart — only what the leaf derivation and the label read. */
@Serializable
internal data class CoaRowDto(
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("line_type") val lineType: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
internal data class ValueListDto<T>(@SerialName("value") val value: List<T>? = null)

/** The web's `computeLeafRows`: with typed rows, the categories and sub-categories are the postable leaves. */
internal fun List<CoaRowDto>.leaves(): List<PoNominal> {
    val active = filter { it.isActive != false && !it.code.isNullOrBlank() }
    val typed = active.any { !it.lineType.isNullOrBlank() }
    val leaves = if (typed) active.filter { it.lineType == "category" || it.lineType == "sub_category" } else active
    return leaves.map { PoNominal(code = it.code.orEmpty(), name = it.name.orEmpty()) }
}

/** The web's `mapRuleToApi`. */
internal fun PoAssignmentRule.toJson(): Map<String, JsonElement> = mapOf(
    "departments" to departments.asJsonArray(),
    "vendors" to vendors.asJsonArray(),
    "nominal_codes" to nominalCodes.asJsonArray(),
    "amount_min" to (amountMinValue?.let(::JsonPrimitive) ?: JsonNull),
    "target_user_id" to JsonPrimitive(assignTo),
    "is_active" to JsonPrimitive(isActive),
    "priority" to JsonPrimitive(priority),
)

/**
 * The web's `mapAssetFiltersToDb`: a bound pair with neither bound, an empty
 * type list and an empty tag list each go as null, and all three unset send
 * null for the whole rule — which is what the server would normalise it to.
 */
internal fun AssetFilters.toJson(): JsonElement {
    if (isEmpty) return JsonNull
    val lowBound = low
    val highBound = high
    return buildJsonObject {
        put(
            "price",
            if (lowBound == null && highBound == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("low", lowBound?.let(::JsonPrimitive) ?: JsonNull)
                    put("high", highBound?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )
        put("exp_type", if (expTypes.isEmpty()) JsonNull else expTypes.asJsonArray())
        put("tags", if (tags.isEmpty()) JsonNull else tags.asJsonArray())
    }
}

/** The attachment model the web stores under `terms_attachment` after an upload. */
private fun PoAttachment.toJson(): JsonElement = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("title", JsonPrimitive(name))
    put("caption", JsonPrimitive(""))
    put("media", JsonPrimitive(media))
    put("bucket", JsonPrimitive(bucket))
    put("region", JsonPrimitive(region))
    put("content_type", JsonPrimitive(contentType))
    put("content_subtype", JsonPrimitive(name.substringAfterLast('.', "").lowercase()))
}

private fun List<String>.asJsonArray(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

/** A list, or a list encoded into a string; anything else is empty. */
internal fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
    is JsonPrimitive -> contentOrNull
        ?.let { text -> runCatching { settingsJson.parseToJsonElement(text) }.getOrNull() }
        ?.takeIf { it is JsonArray }
        ?.asStringList()
        .orEmpty()
    else -> emptyList()
}

/** "500" for 500 or "500.0", "500.5" as it is — what the amount field shows for a stored minimum. */
private fun String.asAmountText(): String {
    val value = toDoubleOrNull() ?: return this
    return if (value == value.toLong().toDouble()) value.toLong().toString() else this
}

private val settingsJson = Json { ignoreUnknownKeys = true; isLenient = true }
