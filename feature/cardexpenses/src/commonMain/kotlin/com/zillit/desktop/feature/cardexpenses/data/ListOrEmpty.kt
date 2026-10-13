package com.zillit.desktop.feature.cardexpenses.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * A list the card service may answer as `{}`.
 *
 * Its backends serialise an EMPTY list as an empty object, and a plain list serialiser throws on
 * it, blanking the page behind an error. The web normalises at the boundary — `api.getList`
 * coerces any non-array `data` to `[]` (`lib/cardExpenses.js:22-35`, ZL-20593, ZL-20579).
 */
internal class ListOrEmpty<T>(element: KSerializer<T>) :
    JsonTransformingSerializer<List<T>>(ListSerializer(element)) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonArray(emptyList()) else element
}
