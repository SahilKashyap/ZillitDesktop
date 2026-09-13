package com.zillit.desktop.feature.dealmemo.domain.rates

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/**
 * JavaScript's reading of JSON values, for the formatters ported from the web.
 *
 * The union catalogue is authored by hand and carries its numbers sometimes as
 * numbers and sometimes as strings; the web prints both through template
 * strings and tests both for truthiness. Those rules decide what a rate cell
 * says, so they are reproduced rather than approximated.
 */
internal object Js {

    /** `v == null` — absent, or JSON null. */
    fun isNullish(value: JsonElement?): Boolean = value == null || value is JsonNull

    /** `v == null || v === ''` — absent, JSON null, or the empty string. */
    fun isNullishOrEmpty(value: JsonElement?): Boolean =
        isNullish(value) || (value is JsonPrimitive && value.isString && value.content.isEmpty())

    /** JavaScript truthiness: `0`, `""`, `false` and null are falsy; objects and arrays are not. */
    fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull == true
            else -> value.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
        }
    }

    /** `${value}` — how a template string prints a value the caller already knows is present. */
    fun text(value: JsonElement?): String = when (value) {
        null -> "undefined"
        JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) value.content else value.doubleOrNull?.let(::number) ?: value.content
        is JsonObject -> "[object Object]"
        is JsonArray -> value.joinToString(",") { if (isNullish(it)) "" else text(it) }
    }

    /** `Number(value)`, or null where JavaScript would produce `NaN`. */
    fun toNumber(value: JsonElement?): Double? = when (value) {
        null -> null
        JsonNull -> 0.0
        is JsonPrimitive -> primitiveNumber(value)
        else -> null
    }?.takeIf { it.isFinite() }

    private fun primitiveNumber(value: JsonPrimitive): Double? {
        val flag = value.booleanOrNull
        return when {
            value.isString -> value.content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }
            flag != null -> if (flag) 1.0 else 0.0
            else -> value.doubleOrNull
        }
    }

    /**
     * `String(n)` for a finite number: integers print bare (`10`, not `10.0`),
     * fractions in their shortest form, and nothing in the exponent notation
     * the JVM switches to past ten million.
     */
    fun number(value: Double): String {
        if (!value.isFinite()) return if (value.isNaN()) "NaN" else if (value > 0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"
        val sign = if (value < 0) "-" else ""
        val plain = plainDecimal(abs(value))
        val trimmed = if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
        return sign + trimmed
    }

    /** `1.2345678905E7` → `12345678.905`: the JVM's shortest digits, laid out without an exponent. */
    fun plainDecimal(value: Double): String {
        val text = value.toString()
        val exponentAt = text.indexOfFirst { it == 'E' || it == 'e' }
        if (exponentAt < 0) return text
        val mantissa = text.substring(0, exponentAt)
        val digits = mantissa.replace(".", "")
        val point = (mantissa.indexOf('.').takeIf { it >= 0 } ?: mantissa.length) +
            text.substring(exponentAt + 1).toInt()
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
    }

    /**
     * `n.toFixed(digits)` — rounded on the double's exact binary value, which
     * is why `(1.005).toFixed(2)` is `"1.00"` on the web and must be here.
     */
    fun toFixed(value: Double, digits: Int): String = exactToFixed(value, digits)

    /**
     * `parseFloat(value)`, finite or null: the longest number at the start of
     * `String(value)` after leading whitespace — `"12.5kg"` is 12.5, `"£8"` and
     * `true` are nothing, a JSON number is itself.
     */
    fun parseFloat(value: JsonElement?): Double? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) {
            parseFloat(value.content)
        } else {
            value.doubleOrNull?.takeIf { it.isFinite() }
        }
        is JsonArray -> parseFloat(text(value))
        is JsonObject -> null
    }

    fun parseFloat(text: String): Double? =
        FLOAT_PREFIX.find(text.trimStart())?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private val FLOAT_PREFIX = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
}

/** Platform half of [Js.toFixed]: exact binary rounding needs a big decimal. */
internal expect fun exactToFixed(value: Double, digits: Int): String
