package com.zillit.desktop.core.locationpicker

/**
 * A pinned place: where it is, the address the picker read back, and the
 * venue's own name when the search had one (blank for a pasted link).
 */
data class PinnedPlace(
    val lat: Double,
    val long: Double,
    val address: String = "",
    val name: String = "",
) {

    /** The canonical Google Maps link — never a URL somebody typed. */
    val mapsUrl: String get() = mapsLink(lat, long)
}

/** `https://www.google.com/maps?q=<lat>,<long>` — the one link shape saved anywhere. */
fun mapsLink(lat: Double, long: Double): String = "https://www.google.com/maps?q=$lat,$long"
