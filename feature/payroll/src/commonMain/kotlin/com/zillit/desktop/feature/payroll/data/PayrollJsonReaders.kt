package com.zillit.desktop.feature.payroll.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Tolerant readers for the payroll service's documents.
 *
 * The timecard is read as JSON rather than through a DTO because three
 * endpoints answer three projections of it and the numbers arrive as numbers
 * on some columns and as strings on others. A strict DTO fails the whole list
 * on one odd row — the currency-object trap that emptied the bank accounts —
 * so every field here is optional and every figure is read either way.
 */

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

internal fun JsonObject.objects(key: String): List<JsonObject> = array(key).mapNotNull { it as? JsonObject }

internal fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.toDoubleOrNull()
}

internal fun JsonObject.amount(vararg keys: String): Double = number(*keys) ?: 0.0

internal fun JsonObject.millis(vararg keys: String): Long? = number(*keys)?.toLong()?.takeIf { it > 0 }

internal fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } == true

/** `_id` on the Mongo documents, `id` on the projections. */
internal fun JsonObject.identifier(): String? = text("_id", "id")

/** A list from `data`: a bare array, or one nested under [key] or a second `data`. */
internal fun JsonElement?.rows(key: String = "data"): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> ((this[key] as? JsonArray) ?: (this["data"] as? JsonArray)).orEmpty()
        .mapNotNull { it as? JsonObject }
    else -> emptyList()
}
