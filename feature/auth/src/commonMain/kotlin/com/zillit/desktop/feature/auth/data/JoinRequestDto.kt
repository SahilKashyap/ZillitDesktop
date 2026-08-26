package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.Designation
import com.zillit.desktop.feature.auth.domain.CodeLookup
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import com.zillit.desktop.feature.auth.domain.Project
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
    @SerialName("profile_picture") val profilePicture: ProfilePictureDto? = null,
)

/**
 * Where the profile picture ended up.
 *
 * `media` is the full image and `thumbnail` the small one, both object keys
 * rather than data — the file itself went straight to S3. iOS spells the first
 * `profilePic` in Swift and `media` on the wire; this is the wire.
 */
@Serializable
internal data class ProfilePictureDto(
    @SerialName("media") val media: String,
    @SerialName("thumbnail") val thumbnail: String,
    @SerialName("bucket") val bucket: String,
    @SerialName("region") val region: String,
)

internal fun JoinDraft.toRequestDto() = JoinRequestDto(
    firstName = firstName.trim(),
    lastName = lastName.trim(),
    keepNamePrivate = keepNamePrivate,
    departmentId = departmentId?.takeIf { it.isNotBlank() },
    designationId = designationId?.takeIf { it.isNotBlank() },
    joinUnitId = unitId?.takeIf { it.isNotBlank() },
    // Absent, not null, when there is no picture: iOS builds its request
    // without the field at all in that case (`if profilePic == "" && thumbnail
    // == ""`), and Android sends a blank object only for a pre-approved join.
    // An empty one here attaches a blank avatar.
    profilePicture = photo?.let {
        ProfilePictureDto(
            media = it.media,
            thumbnail = it.thumbnail,
            bucket = it.bucket,
            region = it.region,
        )
    },
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

/**
 * Reads what a production code resolved to.
 *
 * `data` carries `project` for a production's own code and `user` for one
 * issued to a person; Android branches on exactly these two, in this order,
 * and falls back to the production list when neither is usable.
 *
 * Read leniently for the usual reason — the `user` branch is a hundred-field
 * crew record and this needs two ids out of it.
 */
internal fun JsonElement.toCodeLookup(): CodeLookup? {
    val data = this as? JsonObject ?: return null
    return (data["project"] as? JsonObject)?.toProjectLookup()
        ?: (data["user"] as? JsonObject)?.toMembershipLookup()
}

/**
 * The membership behind a personal code.
 *
 * Null without a production on it: Android sends that case back to the list
 * rather than onward, because there is nothing to open.
 */
private fun JsonObject.toMembershipLookup(): CodeLookup? {
    val projectId = text(listOf("project_id")) ?: return null
    // `user_id` is the id on that production; `_id` is the record's own. Android
    // prefers the first and falls back to the second.
    val userId = text(listOf("user_id", "_id")) ?: return null
    return CodeLookup.AlreadyOn(projectId = projectId, userId = userId)
}

/** The production behind a shared code, when the row names one. */
private fun JsonObject.toProjectLookup(): CodeLookup? {
    val id = text(listOf("project_id", "_id")) ?: return null
    return CodeLookup.NeedsDetails(
        Project(
            id = id,
            name = text(listOf("project_name", "name")).orEmpty(),
            code = text(listOf("project_code", "code")).orEmpty(),
            // The filterable id, not the localised label — as ProjectDto does.
            type = text(listOf("project_type_id")) ?: text(listOf("project_type")),
            region = text(listOf("project_region")),
            subType = text(listOf("project_sub_type")),
        ),
    )
}
