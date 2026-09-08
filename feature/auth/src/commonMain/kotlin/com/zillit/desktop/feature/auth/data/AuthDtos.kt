package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.Unit as DomainUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models, transcribed from the Android app's `registration/model` package.
 *
 * Field names must match the server exactly — they are snake_case on the wire
 * and camelCase in Kotlin, which is what `@SerialName` is doing here. Every
 * field is nullable with a default: the envelope's `data` is `{}` on error
 * responses, and a non-nullable field there fails the whole parse and masks the
 * real message.
 */

/** `POST device/unlink` — Android `StartProjectVM.logout`: `{"device_id": …}`. */
@Serializable
internal data class UnlinkDeviceDto(
    @SerialName("device_id") val deviceId: String,
)

@Serializable
internal data class OtpRequestDto(
    @SerialName("email") val email: String,
    @SerialName("language") val language: String? = null,
)

@Serializable
internal data class OtpVerifyDto(
    @SerialName("email") val email: String,
    @SerialName("otp") val otp: String,
)

@Serializable
internal data class ConfirmCodeDto(
    @SerialName("confirm_code") val confirmCode: String? = null,
)

@Serializable
internal data class DeviceDto(
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    /**
     * The account device this one was linked from — what the calling socket
     * registers under (Android `DeviceResponseModel.primaryDeviceId`,
     * `CallingHelper.kt:90-108`). Null on the primary device itself.
     */
    @SerialName("primary_device_id") val primaryDeviceId: String? = null,
    /** Android reads the record under `data.device`; accepted here too, whichever nesting a route uses. */
    @SerialName("device") val device: DeviceDto? = null,
) {
    /**
     * Returns null when the payload has no usable identity.
     *
     * The server sends the id as `device_id` on some endpoints and `_id` on
     * others; accepting either here keeps that inconsistency out of the domain.
     */
    fun toDomain(): DeviceIdentity? {
        val record = device?.takeIf { it.deviceId != null || it.id != null } ?: this
        val resolvedId = record.deviceId ?: record.id ?: return null
        return DeviceIdentity(
            deviceId = resolvedId,
            email = record.email.orEmpty(),
            isPrimary = record.isPrimary,
            primaryDeviceId = record.primaryDeviceId?.takeIf { it.isNotBlank() },
            recordId = record.id?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class ProjectDto(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("project_name") val name: String? = null,
    @SerialName("project_code") val code: String? = null,
    @SerialName("project_type") val type: String? = null,
    // `project_type_id` is what the web filters on — `project_type` is the
    // display label and is localised, so filtering on it would break in any
    // language but English.
    @SerialName("project_type_id") val typeId: String? = null,
    @SerialName("project_sub_type") val subType: String? = null,
    @SerialName("project_region") val region: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("parent_project_name") val parentProjectName: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_favourite") val isFavourite: Boolean = false,
    @SerialName("unread") val unread: Int = 0,
    @SerialName("status") val status: String? = null,
    @SerialName("date_created") val dateCreated: Long? = null,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("deleted") val deleted: Boolean = false,
    @SerialName("mark_deleted") val markDeleted: Boolean = false,
) {
    fun toDomain(): Project? {
        val resolvedId = projectId ?: id ?: return null
        return Project(
            id = resolvedId,
            name = name.orEmpty(),
            code = code.orEmpty(),
            // The filterable id, not the localised label — see `typeId`.
            type = typeId ?: type,
            region = region,
            isFavourite = isFavourite,
            userId = userId,
            parentName = parentProjectName?.takeIf { it.isNotBlank() },
            subType = subType,
            isAdmin = isAdmin,
            isPending = status.equals(STATUS_PENDING, ignoreCase = true),
            unreadCount = unread.coerceAtLeast(0),
            createdOnMillis = dateCreated,
        )
    }

    /**
     * Deleted or disabled projects come back in the list but must not be shown.
     *
     * `mark_deleted` is the soft-delete flag the web renders greyed out; the
     * desktop drops them, because a production scheduled for deletion is not
     * something to invite someone to open.
     */
    val isSelectable: Boolean get() = enabled && !deleted && !markDeleted
}

// Top-level, not a companion: @Serializable generates its own companion to hold
// serializer(), and declaring a private one here shadows it.
private const val STATUS_PENDING = "pending"


@Serializable
internal data class UnitDto(
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("unit_name") val name: String? = null,
) {
    fun toDomain(): DomainUnit? {
        val resolvedId = unitId ?: id ?: return null
        return DomainUnit(id = resolvedId, name = name.orEmpty())
    }
}

/**
 * The create-production payload.
 *
 * Field for field from the web's `buildProjectData`. Two of them look like
 * placeholders and are not: `number_of_users` and `project_region` are sent as
 * 0 by every client, and the server assigns the real values.
 */
@Serializable
internal data class CreateProjectDto(
    @SerialName("project_name") val projectName: String,
    @SerialName("project_type_id") val projectTypeId: String,
    @SerialName("project_type") val projectType: String?,
    @SerialName("project_sub_type") val projectSubType: String?,
    @SerialName("number_of_users") val numberOfUsers: Int = 0,
    @SerialName("email") val email: String,
    @SerialName("project_region") val projectRegion: Int = 0,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("project_language") val projectLanguage: String?,
    @SerialName("project_language_description") val projectLanguageDescription: String?,
    @SerialName("enterprise_client_code") val enterpriseClientCode: String?,
    @SerialName("confirm_code") val confirmCode: String,
    // Both or neither — the backend rejects a lone half, and `explicitNulls`
    // is off so nulls drop out of the JSON entirely rather than being sent.
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("phone") val phone: String? = null,
)

/** `project/favourite-project` — `ProjectFavUnFavRequestModel` on Android. */
@Serializable
internal data class FavouriteRequestDto(
    @SerialName("project_id") val projectId: String,
    @SerialName("favourite") val favourite: Boolean,
)

@Serializable
internal data class JoinStatusDto(
    @SerialName("status") val status: String? = null,
    @SerialName("approved") val approved: Boolean? = null,
) {
    /**
     * Unknown values map to [JoinStatus.Pending], not to an error.
     *
     * If the backend adds a state we do not know, "still waiting" is the safe
     * reading — it keeps the user on a screen that tells the truth rather than
     * showing a failure for something that is merely unfamiliar.
     */
    fun toDomain(): JoinStatus = when {
        approved == true || status.equals("approved", ignoreCase = true) -> JoinStatus.Approved
        status.equals("rejected", ignoreCase = true) -> JoinStatus.Rejected
        status.equals("not_joined", ignoreCase = true) -> JoinStatus.NotJoined
        else -> JoinStatus.Pending
    }
}
