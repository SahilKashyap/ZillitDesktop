package com.zillit.desktop.feature.purchaseorder.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A tracking set — one analytical dimension ("Locations", "Episodes") a line
 * can be tagged with, from `GET /account-hub/tracking-sets` (the web's
 * `trackingSetsApi.listSets({ includeNodes: true, activeOnly: true })`).
 *
 * A line stores its picks in [PoLine.trackingCodes] as `{ [set id]: code }`.
 */
data class TrackingSet(
    val id: String,
    val name: String = "",
    val prefix: String = "",
    /** `#RRGGBB`, the chip colour; blank uses the accent. */
    val color: String = "",
    val active: Boolean = true,
    val nodes: List<TrackingNode> = emptyList(),
) {
    /** The codes a line may pick — headers group, they are not picked. */
    val pickable: List<TrackingNode> get() = nodes.filter { !it.isHeader && it.active }

    /** The node a stored value names — by code, or by id for rows written before codes were stored. */
    fun resolve(value: String?): TrackingNode? =
        value?.takeIf { it.isNotBlank() }?.let { v -> nodes.firstOrNull { it.code == v || it.id == v } }
}

data class TrackingNode(
    val id: String,
    val code: String = "",
    val label: String = "",
    val isHeader: Boolean = false,
    val active: Boolean = true,
) {
    val optionLabel: String get() = if (label.isBlank()) code else "$code · $label"
}

/**
 * [PoLine.trackingCodes]'s wire shape (`{ [set id]: code }`) as the plain map
 * the picker edits — the JsonObject stays the line's own stored shape so the
 * rest of the line (custom fields, an unrecognised set) keeps round-tripping
 * exactly as it always has.
 */
internal fun JsonObject?.trackingPicks(): Map<String, String> {
    if (this == null) return emptyMap()
    return mapNotNull { (setId, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { setId to it }
    }.toMap()
}

/** The picker's edits back to the wire shape a line carries. */
internal fun Map<String, String>.trackingJson(): JsonObject = buildJsonObject {
    forEach { (setId, code) -> put(setId, JsonPrimitive(code)) }
}
