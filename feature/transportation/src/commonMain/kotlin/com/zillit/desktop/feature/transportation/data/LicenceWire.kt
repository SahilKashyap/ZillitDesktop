package com.zillit.desktop.feature.transportation.data

import com.zillit.desktop.feature.transportation.domain.LicenceRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A licence-change request (`DocumentRequests.jsx`, `DocumentDetailsModal.jsx`).
 *
 * `is_licence_verified` is absent while nobody has answered, which is what
 * makes the request pending — so it is read as a nullable rather than through
 * [bool], whose false would claim the manager had rejected it.
 */
internal fun parseLicenceRequest(obj: JsonObject?): LicenceRequest? {
    if (obj == null) return null
    val id = obj.string("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return LicenceRequest(
        id = id,
        userId = obj.string("user_id"),
        licencePicture = obj.string("license_picture"),
        createdAtMs = obj.number("created_at") ?: 0,
        verified = (obj["is_licence_verified"] as? JsonPrimitive)?.booleanOrNull,
    )
}

/** The same tolerant reads the rest of this wire makes, kept local to the file. */
private fun JsonObject.string(vararg names: String): String =
    names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        .orEmpty()

private fun JsonObject.number(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
