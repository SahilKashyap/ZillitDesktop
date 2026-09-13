package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Production Setup's non-union pay breakdown (`NonUnionPayBreakdownSection.jsx`
 * `normalize`) — the rule book a non-union deal's rules are imported from.
 */
object NonUnionPayBreakdown {

    private const val KEY = "non_union_paybreakdown"

    /** A Production Setup section lives under `settings.production.<key>` or at the top level. */
    fun section(settings: JsonObject?, key: String = KEY): JsonElement? =
        DocRead.obj(settings, "production")?.get(key)?.takeUnless { it is JsonNull } ?: settings?.get(key)

    /**
     * `normalizeNonUnionValue`: a legacy bare list reads as percentage
     * premiums; an object's three lists get explicit caps and a `triggers`
     * array; anything else is empty.
     */
    fun lists(value: JsonElement?, newId: () -> String): RuleLists = when (value) {
        is JsonArray -> RuleLists(
            mapOf(
                RuleList.Overtimes to emptyList(),
                RuleList.Premiums to DocRead.objects(value).map { legacyPremium(it, newId) },
                RuleList.Penalties to emptyList(),
            ),
        )
        is JsonObject -> RuleLists(
            listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Penalties).associateWith { list ->
                DocRead.objects(value[list.wire]).map(::normalizeRow)
            },
        )
        else -> RuleLists(emptyMap())
    }

    private fun legacyPremium(row: JsonObject, newId: () -> String): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(DocRead.text(row, "id") ?: newId()),
            "label" to JsonPrimitive(DocRead.text(row, "name").orEmpty()),
            "rate_type" to JsonPrimitive("percentage"),
            "rate_amount" to JsonPrimitive(Js.toNumber(row["percentage"] ?: JsonNull)?.takeIf { it.isFinite() } ?: 0.0),
            "basis" to JsonPrimitive("day"),
            "triggers" to JsonArray(emptyList()),
            "is_enhancement" to JsonPrimitive(true),
            "nominal_code" to JsonPrimitive(""),
            "note" to JsonPrimitive(DocRead.text(row, "notes").orEmpty()),
        ),
    )

    private fun normalizeRow(row: JsonObject): JsonObject {
        val capAmount = row["cap_amount"]?.takeUnless { it is JsonNull }?.let { Js.toNumber(it) }
        val cap = mapOf(
            "cap_type" to JsonPrimitive(if (DocRead.text(row, "cap_type") == "capped") "capped" else "uncapped"),
            "cap_amount" to (capAmount?.let(::JsonPrimitive) ?: JsonNull),
        )
        return when {
            row["triggers"] is JsonArray -> JsonObject(row + cap)
            row["trigger"] is JsonObject -> JsonObject(
                (row - "trigger") + cap + ("triggers" to buildJsonArray { add(row.getValue("trigger")) }),
            )
            else -> JsonObject(row + cap + ("triggers" to JsonArray(emptyList())))
        }
    }
}
