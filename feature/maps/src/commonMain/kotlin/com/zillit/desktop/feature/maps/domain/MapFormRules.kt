package com.zillit.desktop.feature.maps.domain

/**
 * The location form's rules — `hooks/useLocationForm.js`, kept pure so the
 * messages and their order are testable without a screen.
 */
object LocationRules {

    /** Existing photos plus new ones, together (`MAX_MEDIA`). */
    const val MAX_MEDIA = 10

    /** "Other" asks for a free-text type, which is what gets saved. */
    fun isOtherType(type: String): Boolean = type.equals("other", ignoreCase = true)

    /** A shooting type offers a scene number — "shooting", or anything with "shoot". */
    fun isShootingType(type: String): Boolean =
        type.lowercase().let { it == "shooting" || "shoot" in it }

    /**
     * The first reason the form cannot save, in the web's order, or null.
     *
     * The coordinate check is the desktop's own: the web would post `NaN`
     * (which JSON writes as null) and the pin would never appear on any map.
     * Here the address search is the one route to coordinates, so saying so
     * beats saving a location nobody can find.
     */
    fun saveError(name: String, address: String, type: String, customType: String, point: LatLng?): String? =
        when {
            name.isBlank() -> "Please fill in required fields"
            address.isBlank() -> "Please enter an address or search for a location"
            isOtherType(type) && customType.isBlank() -> "Please specify the custom location type"
            point == null -> "Please search and select an address to set the coordinates"
            else -> null
        }

    /** What is saved as the type: the free text for "Other", the name otherwise. */
    fun savedType(type: String, customType: String): String =
        if (isOtherType(type) && customType.isNotBlank()) customType.trim() else type
}

/** The studio-zone form's rules — `hooks/useStudioZoneForm.js`. */
object ZoneRules {

    /** `STUDIO_ZONE_RADIUS_OPTIONS`. */
    val PRESETS: List<Int> = listOf(30, 40, 50, 60)

    /**
     * Why the custom radius is unusable, or null.
     *
     * The web once read an empty Custom box as 30 miles and saved a zone
     * nobody asked for; submission checks the raw input, and only the
     * preview falls back.
     */
    fun customRadiusError(useCustom: Boolean, text: String): String? {
        if (!useCustom) return null
        if (text.isBlank()) return "Zone radius is required when Custom is selected."
        val parsed = text.trim().toDoubleOrNull()
        return if (parsed == null || parsed <= 0 || parsed.isNaN()) "Enter a radius greater than 0 miles." else null
    }

    /** The radius the preview draws — a valid custom value, else 30; the preset otherwise. */
    fun effectiveRadius(useCustom: Boolean, preset: Int, text: String): Double =
        if (useCustom) text.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_ZONE_MILES else preset.toDouble()

    /**
     * The city must sit inside its own zone. Null when it does, or when either
     * point is unknown; otherwise the web's sentence, distance to one decimal.
     */
    fun cityOutsideZone(cityName: String, city: LatLng?, centre: LatLng?, radiusMiles: Double): String? {
        if (city == null || centre == null) return null
        val miles = Geo.distanceMiles(centre, city)
        if (miles <= radiusMiles) return null
        return "$cityName is ${toFixed(miles, 1)} miles from the center point, but zone radius is " +
            "${jsNumber(radiusMiles)} miles. Please adjust the center or increase the radius."
    }

    /** The zone name an intersection suggests: "MG Road & Ring Road Zone". */
    fun intersectionZoneName(street1: String, street2: String): String =
        if (street2.isBlank()) "${street1.trim()} Zone" else "${street1.trim()} & ${street2.trim()} Zone"

    /** Why "Select Intersection" cannot run yet, or null. */
    fun intersectionSearchError(street1: String, street1Picked: Boolean, street2: String, street2Picked: Boolean): String? =
        when {
            street1.isBlank() -> "Please enter street 1"
            // A real place from the suggestions, not typed text: the geocoder
            // happily resolves "." to the city and reports a false hit.
            !street1Picked -> "Please select Street 1 from the dropdown suggestions"
            street2.isNotBlank() && !street2Picked ->
                "Please select Street 2 from the dropdown suggestions, or clear it"
            else -> null
        }

    /** The geocoder query — the streets, then the city to bias generic names. */
    fun intersectionQuery(street1: String, street2: String, cityName: String): String {
        val streets = if (street2.isBlank()) street1.trim() else "${street1.trim()} & ${street2.trim()}"
        return if (cityName.isBlank()) streets else "$streets, $cityName"
    }

    /** ~50 miles either way of the city — the web's `delta` bias box for zone searches. */
    const val CITY_BIAS_DEGREES = 0.75
}

/** A search biased to a circle — the location form's ~20 km around its pin or city. */
data class SearchBias(val centre: LatLng, val radiusMeters: Double) {
    companion object {
        /** `useLocationForm.js` address bounds. */
        const val ADDRESS_RADIUS_METERS = 20_000.0

        /** `AddCityModal.jsx` city bounds, used for its (hidden) centre pickers. */
        const val CITY_RADIUS_METERS = 50_000.0
    }
}

/** A box of ±[ZoneRules.CITY_BIAS_DEGREES] around [centre] — the zone form's search bounds. */
fun zoneSearchBounds(centre: LatLng): GeoBounds = GeoBounds(
    north = centre.lat + ZoneRules.CITY_BIAS_DEGREES,
    south = centre.lat - ZoneRules.CITY_BIAS_DEGREES,
    east = centre.lng + ZoneRules.CITY_BIAS_DEGREES,
    west = centre.lng - ZoneRules.CITY_BIAS_DEGREES,
)
