package com.zillit.desktop.feature.callsheet.data

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient JSON readers for a service whose responses arrive in two spellings:
 * stored snake_case, or camelCased by the platform's normaliser.
 */

internal fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

internal fun JsonObject.long(vararg names: String): Long? {
    val primitive = firstOf(*names) as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: primitive.content.trim().toLongOrNull()
}

internal fun JsonObject.bool(vararg names: String): Boolean? {
    val primitive = firstOf(*names) as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: when (primitive.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

internal fun JsonObject.strings(vararg names: String): List<String> =
    (firstOf(*names) as? JsonArray).elements()
        .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { id -> id.isNotBlank() } }

/**
 * A timestamp in any of the three spellings the service has used: epoch
 * milliseconds, epoch seconds, or ISO-8601.
 */
internal fun JsonObject.millis(vararg names: String): Long? {
    val primitive = firstOf(*names) as? JsonPrimitive ?: return null
    val number = primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    if (number != null) {
        return if (number in 1..EPOCH_SECONDS_MAX) number * SECOND_MILLIS else number.takeIf { it > 0 }
    }
    val content = primitive.content.trim()
    content.toLongOrNull()?.let { number ->
        return if (number in 1..EPOCH_SECONDS_MAX) number * SECOND_MILLIS else number.takeIf { ms -> ms > 0 }
    }
    return runCatching { Instant.parse(content).toEpochMilliseconds() }.getOrNull()
}

/** Below this a number is epoch seconds (year 5138) rather than milliseconds. */
private const val EPOCH_SECONDS_MAX = 99_999_999_999L
private const val SECOND_MILLIS = 1000L

internal fun List<String>.toJson(): JsonArray = buildJsonArray {
    forEach { add(JsonPrimitive(it)) }
}

internal fun JsonArray?.elements(): List<JsonElement> = this?.toList() ?: emptyList()
