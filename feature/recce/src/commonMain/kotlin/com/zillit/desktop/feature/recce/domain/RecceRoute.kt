package com.zillit.desktop.feature.recce.domain

/**
 * Where each stop sits on the route picture — the web's `RecceRouteMap`
 * resolution, in the same priority order:
 *
 *  1. the exact pin the map picker captured (or a pasted maps link parsed to);
 *  2. the geocoder, trying `place, address` → `address` → `place`, first hit
 *     wins;
 *  3. a second pass that re-geocodes any failure or wrong-country outlier
 *     biased to the dominant country of the other stops — so a station code
 *     like "NDLS" resolves in India, not Ireland.
 *
 * Lunch breaks are included (the web draws them as a grey "L" pin); only a
 * stop with neither a pin nor any text drops out. The line between pins skips
 * the lunch detours.
 */
object RecceRoute {

    /** A stop worth plotting, with the geocode queries to try when it has no pin. */
    data class Candidate(
        val number: Int,
        val isLunch: Boolean,
        val exact: LatLng?,
        val queries: List<String>,
    )

    fun candidates(stops: List<RecceStop>): List<Candidate> =
        stops.mapIndexedNotNull { index, stop ->
            val place = stop.place.trim()
            val address = stop.address.trim()
            val queries = listOf(
                listOf(place, address).filter { it.isNotEmpty() }.joinToString(", "),
                address,
                place,
            ).filter { it.isNotEmpty() }.distinct()
            if (stop.pin == null && queries.isEmpty()) {
                null
            } else {
                Candidate(
                    number = index + 1,
                    isLunch = stop.kind == StopKind.Lunch,
                    exact = stop.pin,
                    queries = queries,
                )
            }
        }

    /** Resolves every candidate that can be, in itinerary order. */
    suspend fun resolve(
        stops: List<RecceStop>,
        geocode: suspend (query: String, country: String?) -> GeocodeHit?,
    ): List<RoutePin> {
        val candidates = candidates(stops)
        if (candidates.isEmpty()) return emptyList()

        suspend fun resolveOne(candidate: Candidate, country: String?): GeocodeHit? {
            candidate.exact?.let { return GeocodeHit(it, country = "") }
            for (query in candidate.queries) {
                geocode(query, country)?.let { return it }
            }
            return null
        }

        val firstPass = candidates.map { resolveOne(it, country = null) }
        val dominant = firstPass
            .mapNotNull { it?.country?.takeIf { c -> c.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val finalPass = candidates.mapIndexed { index, candidate ->
            val hit = firstPass[index]
            when {
                dominant == null -> hit
                hit != null && (hit.country.isBlank() || hit.country == dominant) -> hit
                else -> resolveOne(candidate, country = dominant) ?: hit
            }
        }
        return candidates.zip(finalPass).mapNotNull { (candidate, hit) ->
            hit?.let { RoutePin(pin = it.pin, number = candidate.number, isLunch = candidate.isLunch) }
        }
    }

    /**
     * Whether two itineraries plot the same picture — the web re-resolves only
     * when a stop's kind, address, place or pin changes.
     */
    fun signature(stops: List<RecceStop>): String =
        stops.joinToString("|") { "${it.kind}/${it.place}/${it.address}/${it.lat}/${it.long}" }
}
