package com.zillit.desktop.feature.maps.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Which city the map opens on — `utils/citySelection.js`, client reqs G + H
 * (spec §4.5). The ladder, in order:
 *
 *  - no cities → nothing (the empty state);
 *  - exactly one → it, whatever was remembered;
 *  - otherwise the last-visited city if it still exists, else the first.
 *
 * A remembered city that is gone (deleted, or access lost) falls through to
 * the first rather than to the empty state, which would read as "you have no
 * cities".
 */
fun pickAutoCity(cities: List<MapCity>, lastVisitedId: String?): MapCity? = when {
    cities.isEmpty() -> null
    cities.size == 1 -> cities.first()
    else -> lastVisitedId?.takeIf { it.isNotBlank() }?.let { id -> cities.firstOrNull { it.id == id } }
        ?: cities.first()
}

/** A share, ready to send: a heading, the message, and the link inside it. */
data class SharePayload(val title: String, val text: String, val url: String)

/**
 * Google's universal "search" link — opens the Maps app where installed and
 * the web viewer otherwise, and needs no API key, so a shared link keeps
 * working whatever happens to the map keys (`utils/shareLocation.js`).
 */
fun locationMapsUrl(point: LatLng): String =
    "https://www.google.com/maps/search/?api=1&query=${jsNumber(point.lat)},${jsNumber(point.lng)}"

fun directionsMapsUrl(origin: LatLng, destination: LatLng): String =
    "https://www.google.com/maps/dir/?api=1&origin=${jsNumber(origin.lat)},${jsNumber(origin.lng)}" +
        "&destination=${jsNumber(destination.lat)},${jsNumber(destination.lng)}"

/**
 * The location share text, fixed by spec §3.N so the three platforms send the
 * same message and a recipient cannot tell which one it came from.
 *
 * Blank lines are omitted rather than left empty — a share with a hole in the
 * middle looks broken in SMS, where every line shows. Null without usable
 * coordinates: sharing a pin with no position is worse than not offering it.
 */
fun buildLocationShare(location: MapLocation): SharePayload? {
    val point = location.point ?: return null
    val name = location.name.ifBlank { "Location" }
    val url = locationMapsUrl(point)
    val lines = listOf("📍 $name", location.address, "${jsNumber(point.lat)}, ${jsNumber(point.lng)}", "View: $url")
        .filter { it.isNotBlank() }
    return SharePayload(title = name, text = lines.joinToString("\n"), url = url)
}

/** The route share — 🚗 for a route, 📍 for a place, so a recipient tells them apart. */
fun buildDirectionsShare(
    originName: String?,
    origin: LatLng?,
    destinationName: String?,
    destination: LatLng?,
    distance: String?,
    eta: String?,
): SharePayload? {
    if (origin == null || destination == null) return null
    val heading = "Directions to ${destinationName?.takeIf { it.isNotBlank() } ?: "destination"}"
    val url = directionsMapsUrl(origin, destination)
    val lines = listOf(
        "🚗 $heading",
        originName?.takeIf { it.isNotBlank() }?.let { "From: $it" }.orEmpty(),
        distance?.takeIf { it.isNotBlank() }?.let { "Distance: $it" }.orEmpty(),
        eta?.takeIf { it.isNotBlank() }?.let { "ETA: $it" }.orEmpty(),
        "View: $url",
    ).filter { it.isNotBlank() }
    return SharePayload(title = heading, text = lines.joinToString("\n"), url = url)
}

/**
 * A number the way JavaScript prints one — `19`, not `19.0`; `0.00001`, not
 * `1.0E-5` — so a share built here is character-for-character the web's.
 */
fun jsNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    if (value == floor(value) && abs(value) < WHOLE_LIMIT) return value.toLong().toString()
    val text = value.toString()
    val exponentAt = text.indexOfFirst { it == 'E' || it == 'e' }
    if (exponentAt < 0) return text
    return expandExponent(text.substring(0, exponentAt), text.substring(exponentAt + 1).toInt())
}

private fun expandExponent(mantissa: String, exponent: Int): String {
    val negative = mantissa.startsWith("-")
    val unsigned = mantissa.removePrefix("-")
    val point = unsigned.indexOf('.').let { if (it < 0) unsigned.length else it }
    val digits = unsigned.replace(".", "")
    val shifted = point + exponent
    val body = when {
        shifted <= 0 -> "0." + "0".repeat(-shifted) + digits
        shifted >= digits.length -> digits + "0".repeat(shifted - digits.length)
        else -> digits.substring(0, shifted) + "." + digits.substring(shifted)
    }
    val trimmed = if ('.' in body) body.trimEnd('0').trimEnd('.') else body
    return if (negative) "-$trimmed" else trimmed
}

/** Beyond this JavaScript prints whole numbers in exponent form too. */
private const val WHOLE_LIMIT = 1e15

/**
 * `Number.prototype.toFixed` for the coordinates and distances the tool shows,
 * decided on the number's exact binary value as the spec requires: 12.35 is
 * really 12.3499… and prints "12.3"; a true half (12.25) rounds up; a small
 * negative keeps its sign ("-0.00").
 */
fun toFixed(value: Double, digits: Int): String {
    if (value.isNaN() || value.isInfinite() || abs(value) >= FIXED_LIMIT) return jsNumber(value)
    val magnitude = abs(value)
    val factor = 10.0.pow(digits)
    val product = magnitude * factor
    val whole = floor(product)
    // The product was rounded to a double. Only when it reads as exactly
    // half-way does that rounding matter, and then its exact error decides.
    val fraction = product - whole - HALF
    val roundUp = if (fraction == 0.0) productError(magnitude, factor, product) >= 0.0 else fraction > 0.0
    val scaled = whole.toLong() + if (roundUp) 1 else 0
    val sign = if (value < 0) "-" else ""
    if (digits == 0) return "$sign$scaled"
    val unit = factor.toLong()
    return "$sign${scaled / unit}.${(scaled % unit).toString().padStart(digits, '0')}"
}

/** `a * b - product`, exactly — Dekker's two-product, with no fused multiply in common code. */
private fun productError(a: Double, b: Double, product: Double): Double {
    val aHigh = splitHigh(a)
    val aLow = a - aHigh
    val bHigh = splitHigh(b)
    val bLow = b - bHigh
    return ((aHigh * bHigh - product) + aHigh * bLow + aLow * bHigh) + aLow * bLow
}

private fun splitHigh(value: Double): Double {
    val scaled = SPLITTER * value
    return scaled - (scaled - value)
}

private const val HALF = 0.5

/** 2^27 + 1: splits a double into halves whose products are exact. */
private const val SPLITTER = 134_217_729.0

/** From here JavaScript's `toFixed` prints the plain number. */
private const val FIXED_LIMIT = 1e21

/** One component of a reverse-geocoded address. */
data class GeoComponent(val longName: String, val types: List<String>)

/** A reverse-geocoded point: Google's formatted address and its parts. */
data class GeocodedPlace(val address: String, val components: List<GeoComponent> = emptyList()) {

    /**
     * The name the pin-mode preview card shows — `reverseGeocodePin`: a point
     * of interest, then the sublocality, then the road, then the address's
     * first line.
     */
    val pinName: String
        get() = first("point_of_interest", "establishment")
            ?: first("sublocality_level_1", "sublocality")
            ?: first("route")
            ?: firstLine

    /**
     * The name "Create Another City" suggests — `reverseGeocodePlace`'s
     * short-name walk (spec §4.3), most specific first.
     */
    val suggestedCityName: String
        get() = first("sublocality_level_1", "sublocality")
            ?: first("neighborhood")
            ?: first("locality")
            ?: first("route")
            ?: firstLine

    private val firstLine: String get() = address.substringBefore(',')

    /** The first component carrying any of [types], in Google's order. */
    private fun first(vararg types: String): String? =
        components.firstOrNull { component -> types.any { it in component.types } }
            ?.longName?.takeIf { it.isNotBlank() }
}
