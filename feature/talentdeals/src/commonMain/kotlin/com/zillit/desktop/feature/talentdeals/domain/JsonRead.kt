package com.zillit.desktop.feature.talentdeals.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tolerant readers for the talent-contract wire.
 *
 * The backend is Mongo-shaped: a number may arrive as a string, a boolean as
 * `"true"`, a currency as a bare code or as `{code, name, symbol}`, and any
 * key may be missing or null. A typed DTO would fail the whole list on one odd
 * record, so rows are read field by field and a bad field is simply absent.
 */
internal object JsonRead {

    fun primitive(obj: JsonObject?, key: String): JsonPrimitive? =
        (obj?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }

    fun text(obj: JsonObject?, key: String): String? = primitive(obj, key)?.contentOrNull

    fun number(obj: JsonObject?, key: String): Double? = number(primitive(obj, key))

    fun number(element: JsonPrimitive?): Double? {
        val raw = element?.contentOrNull?.replace(",", "")?.trim() ?: return null
        val parsed = raw.toDoubleOrNull() ?: return null
        return parsed.takeUnless { it.isNaN() || it.isInfinite() }
    }

    fun long(obj: JsonObject?, key: String): Long? = number(obj, key)?.toLong()

    fun bool(obj: JsonObject?, key: String): Boolean = when (primitive(obj, key)?.contentOrNull?.trim()?.lowercase()) {
        "true", "1" -> true
        else -> false
    }

    fun obj(obj: JsonObject?, key: String): JsonObject? = obj?.get(key) as? JsonObject

    fun array(element: JsonElement?): List<JsonElement> = (element as? JsonArray)?.toList().orEmpty()

    fun objects(element: JsonElement?): List<JsonObject> = array(element).mapNotNull { it as? JsonObject }

    /** A currency field that is a bare code or the whole currency object. */
    fun currencyCode(obj: JsonObject?, key: String): String? = when (val value = obj?.get(key)) {
        is JsonObject -> text(value, "code")
        is JsonPrimitive -> value.takeUnless { it is JsonNull }?.contentOrNull
        else -> null
    }
}
