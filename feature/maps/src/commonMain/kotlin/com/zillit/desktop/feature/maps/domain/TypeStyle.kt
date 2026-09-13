package com.zillit.desktop.feature.maps.domain

/**
 * How a location type is drawn: the glyph inside its pin and its colour.
 *
 * Hex strings rather than Compose colours so the canvas page and the Compose
 * screens paint a type identically from one table, and so this stays testable
 * without the UI toolkit.
 */
data class TypeStyle(val icon: String, val colorHex: String)

/** `config/constants.js` `LOCATION_TYPE_ICONS` — the web's built-in type styles. */
val DEFAULT_TYPE_STYLES: Map<String, TypeStyle> = mapOf(
    "Hotel" to TypeStyle("H", "#8E44AD"),
    "Parking" to TypeStyle("P", "#2980B9"),
    "Shooting" to TypeStyle("S", "#E74C3C"),
    "Catering" to TypeStyle("C", "#F39C12"),
    "Hospital" to TypeStyle("+", "#27AE60"),
    "Base Camp" to TypeStyle("B", "#1ABC9C"),
    "Crew Parking" to TypeStyle("C", "#3498DB"),
    "Equipment" to TypeStyle("E", "#E67E22"),
    "Extras Holding" to TypeStyle("X", "#9B59B6"),
    "Dressing Room" to TypeStyle("D", "#E91E63"),
    "Production Office" to TypeStyle("O", "#607D8B"),
    "Other" to TypeStyle("O", "#795548"),
)

/** The `default` entry — a navy Z. */
val FALLBACK_TYPE_STYLE = TypeStyle("Z", "#1B4F72")

/**
 * `utils/getTypeInfo.js`: a type's own icon when the production gave it one,
 * on the built-in colour for its name.
 */
fun typeStyle(typeName: String, types: List<LocationType>): TypeStyle {
    val base = DEFAULT_TYPE_STYLES[typeName] ?: FALLBACK_TYPE_STYLE
    val custom = types.firstOrNull { it.name == typeName }?.icon?.takeIf { it.isNotBlank() }
    return if (custom != null) base.copy(icon = custom) else base
}

/** The colours the web module paints its own surfaces with. */
object MapPalette {
    /** The map module's brand orange — toolbar, heroes, primary actions. */
    const val BRAND = "#F99300"

    /** Studio zones everywhere outside the map: heroes, chips, previews. */
    const val ZONE_ACCENT = "#3B82F6"

    /** The active zone's circle on the map. */
    const val ZONE_CIRCLE = "#1B4F72"

    /** The pin being placed. */
    const val PREVIEW_PIN = "#E74C3C"

    /** A Places search result. */
    const val PLACE_PIN = "#8B5CF6"
}

/** `headers/HeaderForm.jsx` `ICON_OPTIONS` — the emoji a type can wear. */
val TYPE_ICON_OPTIONS: List<String> = listOf(
    "📍", "🏨", "🅿️", "🎬", "🍽️", "🏥", "⛺", "🚗", "🎥", "👥",
    "👗", "🏢", "📌", "🏠", "🏫", "🏪", "🏭", "🏗️", "🎭", "🎪",
    "🏟️", "⛪", "🕌", "🏛️", "🗼", "🌳", "🏖️", "⛰️", "🚉", "✈️",
    "🚢", "⛽", "🏦", "📦", "🔧", "💡", "🎵", "🎨", "📸", "🛒",
    "☕", "🍕", "🏋️", "🏊", "🎯", "🔑", "🚿", "🛏️", "💼", "📡",
)

/** The glyph a type falls back to when saved with no icon — `icon || 'Z'`. */
const val DEFAULT_TYPE_ICON = "Z"
