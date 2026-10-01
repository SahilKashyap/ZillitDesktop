package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One record from the Costumes & Set Sync service, read straight off the wire.
 *
 * The service answers snake_case JSON whose shape differs per resource (and
 * which the web reads untyped, key by key), so the desktop reads it the same
 * way rather than carrying forty DTOs that would all drift from the backend.
 * Every accessor is total: a missing key, a `null`, or the wrong JSON type
 * reads as the empty value, never as a crash — the service uses `0` and `""`
 * for "not yet" and omits keys freely.
 */
@JvmInline
value class Rec(val json: JsonObject) {

    /** The record's id: the service writes `_id`; a few embedded rows write `id`. */
    val id: String get() = str("_id").ifBlank { str("id") }

    fun has(key: String): Boolean = json[key].let { it != null && it !is JsonNull }

    /** A string; numbers and booleans read as their text. Blank when absent. */
    fun str(key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    fun long(key: String): Long = (json[key] as? JsonPrimitive)?.let {
        it.longOrNull ?: it.doubleOrNull?.toLong()
    } ?: 0L

    fun int(key: String): Int = long(key).toInt()

    fun double(key: String): Double = (json[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0

    /** Null when the key is absent, so "unset" and "zero" stay distinguishable. */
    fun doubleOrNull(key: String): Double? = (json[key] as? JsonPrimitive)?.doubleOrNull

    fun bool(key: String): Boolean = (json[key] as? JsonPrimitive)?.booleanOrNull ?: (str(key) == "true")

    @Suppress("MemberNameEqualsClassName") // The accessor every screen reads nested objects with.
    fun rec(key: String): Rec? = (json[key] as? JsonObject)?.let(::Rec)

    /** An array of objects; empty when absent or not an array. */
    fun recs(key: String): List<Rec> = (json[key] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonObject)?.let(::Rec)
    }

    /** An array of strings (numbers read as text). */
    fun strings(key: String): List<String> = (json[key] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull
    }

    /** The first non-blank of [keys] — the service names the same field two ways in places. */
    fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { str(it).ifBlank { null } }.orEmpty()

    /** The record's own keys, for a map of counts (`by_status`) rather than a record. */
    val keys: Set<String> get() = json.keys

    companion object {
        val Empty = Rec(JsonObject(emptyMap()))
    }
}

/** `data` as one record, or null. */
fun JsonElement?.asRec(): Rec? = (this as? JsonObject)?.let(::Rec)

/**
 * `data` as a list of records. The service answers a bare array for some
 * resources and `{ items: [...] }` (with `total`, `pipeline`...) for others;
 * both read here.
 */
fun JsonElement?.asRows(): List<Rec> {
    val array = when (this) {
        is JsonArray -> this
        is JsonObject -> (this["items"] ?: this["data"]) as? JsonArray
        else -> null
    }
    return array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::Rec) }
}
