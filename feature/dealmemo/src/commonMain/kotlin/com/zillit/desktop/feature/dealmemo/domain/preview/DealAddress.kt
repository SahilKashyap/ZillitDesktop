package com.zillit.desktop.feature.dealmemo.domain.preview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A crew address — exactly `{line1, line2, city, state, postal_code, country}`
 * (`addressShape.js`). Older deals stored one free-text string; it reads as
 * `line1` and nothing is parsed out of it.
 */
data class DealAddress(
    val line1: String? = null,
    val line2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
) {
    val isBlank: Boolean get() = listOf(line1, line2, city, state, postalCode, country).all { it.isNullOrBlank() }

    /** A marked address is satisfied only by line 1, city and postal code. */
    val isIncomplete: Boolean get() = line1.isNullOrBlank() || city.isNullOrBlank() || postalCode.isNullOrBlank()

    /**
     * `formatAddress`: one comma-joined line — `line1, line2, "<city>, <state>
     * <postal>", country` — blanks dropped, legacy newlines folded to commas;
     * empty when there is nothing (callers print the dash).
     */
    fun format(): String {
        val locality = listOfNotNull(
            city?.trim()?.takeIf { it.isNotEmpty() },
            listOfNotNull(state?.trim(), postalCode?.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                .takeIf { it.isNotEmpty() },
        ).joinToString(", ")
        return listOf(line1, line2, locality, country)
            .mapNotNull { part -> part?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(", ")
            .replace(Regex("\\s*\\n+\\s*"), ", ")
    }

    /** The wire object: six keys, blanks as null. */
    fun toJson(): JsonObject = buildJsonObject {
        put("line1", line1.nullIfBlank())
        put("line2", line2.nullIfBlank())
        put("city", city.nullIfBlank())
        put("state", state.nullIfBlank())
        put("postal_code", postalCode.nullIfBlank())
        put("country", country.nullIfBlank())
    }

    companion object {
        /** `normalizeAddress`: null → empty; a string → its trimmed `line1`; an object → its six keys. */
        fun of(element: JsonElement?): DealAddress = when (element) {
            is JsonObject -> DealAddress(
                line1 = element.textOrNull("line1"),
                line2 = element.textOrNull("line2"),
                city = element.textOrNull("city"),
                state = element.textOrNull("state"),
                postalCode = element.textOrNull("postal_code"),
                country = element.textOrNull("country"),
            )
            is JsonPrimitive -> if (element is JsonNull || !element.isString) {
                DealAddress()
            } else {
                DealAddress(line1 = element.content.trim().ifEmpty { null })
            }
            else -> DealAddress()
        }

        private fun JsonObject.textOrNull(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.ifEmpty { null }

        private fun String?.nullIfBlank(): String? = this?.trim()?.ifEmpty { null }
    }
}

/**
 * `isValueBlank`: null; an empty list; an object whose every value is blank;
 * a string that trims to nothing. Zero is a value.
 */
fun isValueBlank(element: JsonElement?): Boolean = when (element) {
    null, JsonNull -> true
    is JsonArray -> element.isEmpty()
    is JsonObject -> element.values.all(::isValueBlank)
    is JsonPrimitive -> element.isString && element.content.isBlank()
}
