package com.zillit.desktop.feature.purchaseorder.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * [PoLine.customFields]'s wire shape — `[{name, value}, ...]`
 * (`POForm.jsx`'s `liCustomFields`, not the header's `{section, fields}`
 * [CustomFieldGroup] — a line's extra fields are flat, keyed on the
 * template's own field label) — as the plain map the line's extra columns
 * edit.
 */
internal fun JsonArray?.customFieldPicks(): Map<String, String> {
    if (this == null) return emptyMap()
    return mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val value = (obj["value"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        name to value
    }.toMap()
}

/**
 * The edited map back to the wire shape — blank answers dropped, as the
 * web's own `if (val !== undefined && val !== "")` does; a field somebody
 * skipped and a field somebody cleared should not be told apart on save.
 */
internal fun Map<String, String>.customFieldsJson(): JsonArray = buildJsonArray {
    forEach { (name, value) ->
        if (value.isNotBlank()) {
            add(buildJsonObject { put("name", JsonPrimitive(name)); put("value", JsonPrimitive(value)) })
        }
    }
}
