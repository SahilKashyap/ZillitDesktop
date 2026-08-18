package com.zillit.desktop.core.common

/**
 * Coordinates out of a pasted Google Maps link — the web's `parseLatLngFromUrl`,
 * regex for regex: `@lat,lng`, `!3dlat!4dlng`, and `?q=`/`ll=`/`query=`/…
 * pairs. Short `goo.gl` links carry nothing and give null.
 */
object MapsLink {
    private val patterns = listOf(
        Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)"""),
        Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"""),
        Regex("""[?&](?:q|ll|query|destination|center|daddr)=(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)"""),
    )

    fun parseLatLng(url: String): Pair<Double, Double>? {
        if (url.isBlank()) return null
        return patterns.firstNotNullOfOrNull { re ->
            val m = re.find(url) ?: return@firstNotNullOfOrNull null
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) lat to lng else null
        }
    }
}
