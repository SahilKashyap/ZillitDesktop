package com.zillit.desktop.feature.dealmemo.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Tolerant readers for the deal-memo service's JSON.
 *
 * Its documents are hand-authored and its lists are sometimes serialised as
 * `{}` when empty (`accountHub/api/client.js:70-73`); numbers arrive as
 * numbers or strings. Decoding through fixed DTOs dropped whole rows on this
 * port before, and a DTO also forgets every key it does not name — which a
 * deal cannot afford, because the server stores what it is sent. So deals and
 * reference data are kept as their JSON and read field by field.
 */
internal object DocRead {

    /** An array, with the backend's empty-object stand-in read as empty. */
    fun array(element: JsonElement?): List<JsonElement> = (element as? JsonArray).orEmpty()

    fun objects(element: JsonElement?): List<JsonObject> = array(element).mapNotNull { it as? JsonObject }

    fun obj(json: JsonObject?, key: String): JsonObject? = json?.get(key) as? JsonObject

    /** A present, non-empty string — numbers read as their text. */
    fun text(json: JsonObject?, key: String): String? {
        val value = json?.get(key) as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.takeIf { it.isNotEmpty() }
    }

    /** A number, whether it was sent as one or as a numeric string. */
    fun number(json: JsonObject?, key: String): Double? {
        val value = json?.get(key) as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return (value.doubleOrNull ?: value.content.trim().toDoubleOrNull())?.takeIf { it.isFinite() }
    }

    /** Epoch milliseconds — a number, `"1754300000000"`, or `"1754300000000.0"`. */
    fun epoch(json: JsonObject?, key: String): Long? = number(json, key)?.toLong()?.takeIf { it > 0 }

    /** A JSON boolean only — `null` when absent or of another type, so "unknown" stays unknown. */
    fun bool(json: JsonObject?, key: String): Boolean? {
        val value = json?.get(key) as? JsonPrimitive ?: return null
        if (value is JsonNull || value.isString) return null
        return value.booleanOrNull
    }

    /** `!!value` for a flag the web tests for truthiness. */
    fun flag(json: JsonObject?, key: String): Boolean {
        val value = json?.get(key) as? JsonPrimitive ?: return false
        if (value is JsonNull) return false
        return value.booleanOrNull ?: when {
            value.isString -> value.content.isNotEmpty()
            else -> value.doubleOrNull?.let { it != 0.0 } ?: false
        }
    }

    /** Whether the key is present with a non-null value — JavaScript's `v != null`. */
    fun present(json: JsonObject?, key: String): Boolean = json?.get(key).let { it != null && it !is JsonNull }
}
