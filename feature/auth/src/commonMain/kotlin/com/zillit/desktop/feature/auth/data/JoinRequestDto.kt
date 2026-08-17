package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.Designation
import com.zillit.desktop.feature.auth.domain.JoinDraft
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The join request body.
 *
 * Field names verified against both live clients — iOS's `JoinUserRequestModel`
 * `CodingKeys` and Android's `JoinProjectRequest`. Optional fields are omitted
 * rather than sent null: the server treats absent and null differently on this
 * endpoint's profile picture, and sending an empty one attaches a blank avatar.
 */
@Serializable
internal data class JoinRequestDto(
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("join_unit_id") val joinUnitId: String? = null,
)

internal fun JoinDraft.toRequestDto() = JoinRequestDto(
    firstName = firstName.trim(),
    lastName = lastName.trim(),
    keepNamePrivate = keepNamePrivate,
    departmentId = departmentId?.takeIf { it.isNotBlank() },
    designationId = designationId?.takeIf { it.isNotBlank() },
    joinUnitId = unitId?.takeIf { it.isNotBlank() },
)

/**
 * Reads the department list, however it is wrapped.
 *
 * Lenient for the reason every reader here is lenient: a typed DTO has failed
 * against an HTTP 200 on this API four separate times. A join form that cannot
 * offer a department is a crew member who cannot join the production.
 */
internal fun JsonElement.toDepartments(): List<Department> =
    rowsOf(DEPARTMENT_LIST_KEYS).mapNotNull { row ->
        val id = row.text(ID_KEYS) ?: return@mapNotNull null
        Department(
            id = id,
            name = row.text(NAME_KEYS) ?: id,
            designations = row.designations(),
        )
    }

private fun JsonObject.designations(): List<Designation> =
    DESIGNATION_KEYS.firstNotNullOfOrNull { this[it] as? JsonArray }
        .orEmptyRows()
        .mapNotNull { row ->
            val id = row.text(ID_KEYS) ?: return@mapNotNull null
            Designation(id = id, name = row.text(NAME_KEYS) ?: id)
        }

private fun JsonElement.rowsOf(wrapperKeys: List<String>): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> wrapperKeys.firstNotNullOfOrNull { this[it] as? JsonArray }.orEmptyRows()
    else -> emptyList()
}

private fun JsonArray?.orEmptyRows(): List<JsonObject> =
    this?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonObject.text(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

private val DEPARTMENT_LIST_KEYS = listOf("departments", "data", "entries", "list")
private val DESIGNATION_KEYS = listOf("designations", "designation", "roles")
private val ID_KEYS = listOf("_id", "id", "department_id", "designation_id")
private val NAME_KEYS = listOf("department_name", "designation_name", "name", "title")
