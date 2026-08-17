package com.zillit.desktop.feature.callsheet.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull

/**
 * Lenient JSON readers for a service whose responses arrive in two spellings:
 * stored snake_case, or camelCased by the platform's normaliser.
 */

internal fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

internal fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.longOrNull

internal fun JsonObject.strings(vararg names: String): List<String> =
    (firstOf(*names) as? JsonArray).elements().mapNotNull { (it as? JsonPrimitive)?.content }

internal fun List<String>.toJson(): JsonArray = buildJsonArray {
    forEach { add(JsonPrimitive(it)) }
}

internal fun JsonArray?.elements(): List<JsonElement> = this?.toList() ?: emptyList()
