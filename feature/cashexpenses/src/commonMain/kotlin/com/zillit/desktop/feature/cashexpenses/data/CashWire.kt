package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTierConfig
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.domain.QueryMessage
import com.zillit.desktop.feature.cashexpenses.domain.QueryThread
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.domain.TierApproval
import com.zillit.desktop.feature.cashexpenses.domain.TierRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// The newer halves of the cash wire: approval chains, the claims a post sends
// back, the reconciliation count, and the account-hub routes this module leans
// on. Kept apart from CashDtos.kt, which is the service's original surface.

// -- approvals ---------------------------------------------------------------

@Serializable
internal data class ApprovalDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("tier_number") val tierNumber: JsonPrimitive? = null,
)

/** `[{user_id, tier_number, approved_at}]`, as an array or a string holding one. */
internal fun JsonElement?.readApprovals(): List<TierApproval> =
    readList(ApprovalDto.serializer()).mapNotNull { dto ->
        val tier = dto.tierNumber?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
        TierApproval(userId = dto.userId.orEmpty(), tierNumber = tier)
    }

@Serializable
internal data class TierRuleDto(
    @SerialName("type") val type: String? = null,
    @SerialName("amount_threshold") val amountThreshold: JsonPrimitive? = null,
    @SerialName("user_ids") val userIds: List<String>? = null,
)

@Serializable
internal data class TierDto(@SerialName("rules") val rules: List<TierRuleDto>? = null)

@Serializable
internal data class TierConfigDto(
    @SerialName("scope") val scope: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    /** An array of levels, or a string holding one. */
    @SerialName("tiers") val tiers: JsonElement? = null,
)

/** `/metadata`'s `approval_tier_configs` — the chains the approve routes need a level from. */
internal fun JsonElement?.readTierConfigs(): List<ApprovalTierConfig> =
    readList(TierConfigDto.serializer()).map { config ->
        ApprovalTierConfig(
            scope = config.scope.orEmpty(),
            departmentId = config.departmentId,
            tiers = config.tiers.readList(TierDto.serializer()).map { tier ->
                ApprovalTier(
                    rules = tier.rules.orEmpty().map { rule ->
                        TierRule(
                            type = rule.type.orEmpty(),
                            amountThreshold = rule.amountThreshold?.contentOrNull?.toDoubleOrNull(),
                            userIds = rule.userIds.orEmpty(),
                        )
                    },
                )
            },
        )
    }

// -- claims -------------------------------------------------------------------

/** `true` in either spelling the wire uses; anything else is false. */
internal fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.contentOrNull == "true"

/** `["review"]` or `[{flag: "review"}]`, in an array or a string holding one. */
internal fun JsonElement?.readFlags(): List<String> = readArray().mapNotNull { entry ->
    when (entry) {
        is JsonPrimitive -> entry.contentOrNull
        is JsonObject -> (entry["flag"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }
}

/**
 * The stored lines, as the server sent them, less the engine's own rows.
 *
 * The engine re-derives exactly one deduction per matching rule on every
 * save, so sending its rows back duplicates them — the web strips them the
 * same way (`stripAutoDeductionLines`).
 */
internal fun JsonElement?.readRawLines(): List<JsonObject> =
    readArray().filterIsInstance<JsonObject>().filterNot { it.isEngineRow() }

private fun JsonObject.isEngineRow(): Boolean {
    val tagged = (this["meta"].readObject()?.get("auto") as? JsonPrimitive)?.contentOrNull == "true"
    return tagged || (this["account"] as? JsonPrimitive)?.contentOrNull == DEDUCT
}

/** The keys a claim's line carries on a save — the web's `editorLinesToWire` plus the tax line's own. */
private val LINE_KEYS = listOf(
    "id", "description", "quantity", "unit_price", "total", "account", "tax_type", "tax_rate",
    "tax_amount", "expenditure_type", "rental_start", "rental_end", "split_parent_id", "sort_order",
    "tracking_codes", "tags", "is_tax",
)

/**
 * The batch's claims, as a post, a verify or a forward sends them back.
 *
 * Built from what the server holds, as the web's `buildClaimsPayload` builds
 * from its editor: the receipt's facts, and its stored lines with the keys a
 * save takes — so a tax line keeps `is_tax`, and a coded line its layers and
 * tags. A receipt with no lines sends `null`, as the web does.
 */
internal fun List<Claim>.toClaimsPayload(verified: Map<String, Boolean> = emptyMap()): JsonArray = buildJsonArray {
    forEach { claim ->
        add(
            buildJsonObject {
                put("id", JsonPrimitive(claim.id))
                put("description", claim.description.ifBlank { null }.json())
                put("gross_amount", JsonPrimitive(claim.grossAmount))
                put("receipt_date", claim.receiptDate?.let(::JsonPrimitive) ?: JsonNull)
                put("category", claim.category?.ifBlank { null }.json())
                put("cost_code", claim.costCode?.ifBlank { null }.json())
                put("episode", claim.episode?.ifBlank { null }.json())
                put("coded_description", (claim.codedDescription?.ifBlank { null } ?: claim.description).json())
                put("tax_type", claim.taxType?.ifBlank { null }.json())
                put("tax_rate", JsonPrimitive(claim.taxRate ?: 0.0))
                put("vat_amount", JsonPrimitive(claim.vatAmount))
                put("net_amount", JsonPrimitive(claim.netAmount))
                put(
                    "line_items",
                    if (claim.rawLines.isEmpty()) {
                        JsonNull
                    } else {
                        JsonArray(claim.rawLines.map { line -> JsonObject(line.filterKeys { it in LINE_KEYS }) })
                    },
                )
                verified[claim.id]?.let { put("is_verified", JsonPrimitive(it)) }
            },
        )
    }
}

private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

// -- reconciliation -------------------------------------------------------------

/** The whole count, as every reconciliation write sends it (`buildPayload`). */
internal fun ReconDraft.toPayload(periodStart: Long, periodEnd: Long): JsonObject = buildJsonObject {
    put("opening_safe_balance", JsonPrimitive(opening))
    put("period_start", JsonPrimitive(periodStart))
    put("period_end", JsonPrimitive(periodEnd))
    put("book_balance", JsonPrimitive(bookBalance))
    put("physical_cash", JsonPrimitive(physicalTotal))
    put("variance", JsonPrimitive(variance))
    put("denominations", denominations.toJson())
    put(
        "reconciling_items",
        buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("desc", JsonPrimitive(item.description))
                        put("ref", JsonPrimitive(item.reference))
                        put("type", JsonPrimitive(item.type))
                        put("amount", JsonPrimitive(item.amount))
                    },
                )
            }
        },
    )
    put("notes", notes.trim().ifBlank { null }.json())
}

internal fun List<com.zillit.desktop.feature.cashexpenses.domain.Denomination>.toJson(): JsonArray = buildJsonArray {
    forEach { row ->
        add(
            buildJsonObject {
                put("id", JsonPrimitive(row.id))
                put("type", JsonPrimitive(row.type))
                put("value", JsonPrimitive(row.value))
                put("count", JsonPrimitive(row.count))
            },
        )
    }
}

// -- settings sections ---------------------------------------------------------

internal fun JsonObject?.toRequestCap(): RequestCap {
    val body = this ?: return RequestCap()
    fun number(key: String, fallback: Double): Double =
        (body[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: fallback
    return RequestCap(
        enabled = (body["enabled"] as? JsonPrimitive)?.contentOrNull == "true",
        basis = if ((body["basis"] as? JsonPrimitive)?.contentOrNull == RequestCap.WEEKLY_SALARY) {
            RequestCap.WEEKLY_SALARY
        } else {
            RequestCap.MAX_AMOUNT
        },
        maxAmount = number("max_amount", 0.0),
        salaryMultiplier = number("salary_multiplier", 1.0),
    )
}

/** All four keys, numbers where numbers are due — the web's `requestCapPayload`. */
internal fun RequestCap.toJson(): JsonObject = buildJsonObject {
    put("enabled", JsonPrimitive(enabled))
    put("basis", JsonPrimitive(basis))
    put("max_amount", JsonPrimitive(maxAmount.coerceAtLeast(0.0)))
    put("salary_multiplier", JsonPrimitive(salaryMultiplier.coerceAtLeast(0.0)))
}

@Serializable
internal data class CashRuleDto(
    @SerialName("id") val id: String? = null,
    @SerialName("departments") val departments: JsonElement? = null,
    @SerialName("nominal_codes") val nominalCodes: JsonElement? = null,
    @SerialName("amount_min") val amountMin: JsonPrimitive? = null,
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("priority") val priority: Int? = null,
) {
    fun toDomain(): CashAssignmentRule? = id?.takeIf { it.isNotBlank() }?.let {
        CashAssignmentRule(
            id = it,
            departments = departments.readStrings(),
            nominalCodes = nominalCodes.readStrings(),
            amountMin = amountMin?.contentOrNull?.takeUnless { value -> value == "null" }.orEmpty(),
            assignTo = targetUserId.orEmpty(),
            isActive = isActive != false,
            priority = priority ?: 0,
            persisted = true,
        )
    }
}

/** The hub route's body — the web's `saveAssignmentRules` payload. */
internal fun CashAssignmentRule.toJson(): JsonObject = buildJsonObject {
    put("departments", buildJsonArray { departments.forEach { add(JsonPrimitive(it)) } })
    put("nominal_codes", buildJsonArray { nominalCodes.forEach { add(JsonPrimitive(it)) } })
    put("amount_min", amountMinValue?.let(::JsonPrimitive) ?: JsonNull)
    put("target_user_id", JsonPrimitive(assignTo))
    put("is_active", JsonPrimitive(isActive))
    put("priority", JsonPrimitive(priority))
}

// -- fund requests, queries, companies -------------------------------------------

@Serializable
internal data class FundRequestDto(
    @SerialName("id") val id: String? = null,
    @SerialName("fund_account") val fundAccount: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("received_amount") val receivedAmount: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("received_by") val receivedBy: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
) {
    fun toDomain(): FundRequest? = id?.takeIf { it.isNotBlank() }?.let {
        FundRequest(
            id = it,
            fundAccount = fundAccount.orEmpty(),
            currency = currency,
            amount = amount.toAmount(),
            receivedAmount = receivedAmount.toAmountOrNull(),
            status = status?.lowercase().orEmpty().ifBlank { FundRequest.REQUESTED },
            requestedBy = requestedBy,
            requestedAt = (requestedAt ?: createdAt).toEpochMillisOrNull(),
            receivedBy = receivedBy,
            receivedAt = receivedAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class QueryMessageDto(
    @SerialName("queried_by") val queriedBy: String? = null,
    @SerialName("query") val query: String? = null,
    @SerialName("queried_at") val queriedAt: String? = null,
)

@Serializable
internal data class QueryThreadDto(
    @SerialName("id") val id: String? = null,
    @SerialName("queries") val queries: JsonElement? = null,
) {
    fun toDomain() = QueryThread(
        id = id?.takeIf { it.isNotBlank() },
        messages = queries.readList(QueryMessageDto.serializer()).mapNotNull { message ->
            message.query?.takeIf { it.isNotBlank() }?.let { text ->
                QueryMessage(message.queriedBy, text, message.queriedAt.toEpochMillisOrNull())
            }
        },
    )
}

@Serializable
internal data class CompanyDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("country") val country: String? = null,
) {
    fun toDomain(): CashCompany? = (id ?: altId)?.takeIf { it.isNotBlank() }?.let {
        CashCompany(id = it, name = name.orEmpty().ifBlank { it }, country = country.orEmpty())
    }
}

// -- tolerant readers ---------------------------------------------------------------

/** An array, a string holding one, or `{value: [...]}` — anything else is empty. */
internal fun JsonElement?.readArray(): List<JsonElement> = when (this) {
    null, JsonNull -> emptyList()
    is JsonArray -> this
    is JsonObject -> (this["value"] as? JsonArray).orEmpty()
    is JsonPrimitive -> if (isString) {
        runCatching { cashWireJson.parseToJsonElement(content) }.getOrNull().readArray()
    } else {
        emptyList()
    }
}

internal fun JsonElement?.readStrings(): List<String> =
    readArray().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

internal fun JsonElement?.intOrZero(): Int = (this as? JsonPrimitive)?.intOrNull ?: 0

private val cashWireJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val DEDUCT = "DEDUCT"
