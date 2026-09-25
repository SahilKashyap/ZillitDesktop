package com.zillit.desktop.feature.settings.account

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account pages, against the endpoints the other clients use.
 *
 * | Page | Call |
 * |---|---|
 * | Profile, as an admin | `PUT user/profile` |
 * | Profile, as anyone else | `POST user/profile/change-requests` |
 * | Recovery key and email, read | `GET device` |
 * | Recovery email | `POST device/recovery-email` |
 * | Linked devices | `GET device/linked` |
 * | Unlink one | `POST device/unlink` |
 * | Leave the production | `POST user/leave-project` |
 *
 * All six sit on the core service. The web reaches them through what it calls
 * `profilesettingBase`, which is the same host under a different name — the
 * approval queues in the neighbouring package already prove it.
 *
 * ## Two paths for one edit
 *
 * The split is the server's rule, not a convenience: a non-admin who `PUT`s
 * their own profile is refused. Sending the request to the wrong one of the two
 * is the difference between a name change taking effect and a name change
 * quietly waiting in a queue nobody was told about — which is why
 * [ProfileSaveOutcome] exists rather than a bare `Unit`.
 */
class AccountRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /**
     * This computer's device id, so the list can mark its own row.
     *
     * A lambda: the id is issued during sign-in, which happens after this is
     * built, and it changes when the device is re-registered.
     */
    private val thisDeviceId: () -> String? = { null },
) : AccountRepository {

    private val core get() = config.apiV2(ZillitService.Core)

    override suspend fun saveProfile(
        edit: ProfileEdit,
        asAdmin: Boolean,
    ): ZillitResult<ProfileSaveOutcome> {
        val body = buildJsonObject {
            put("first_name", edit.firstName)
            put("last_name", edit.lastName)
            // Absent rather than null when unset. The change-request endpoint
            // reads a present `department_id` as the department being asked
            // for, and an explicit null has been seen to clear it.
            edit.departmentId?.takeIf { it.isNotBlank() }?.let { put("department_id", it) }
            edit.designationId?.takeIf { it.isNotBlank() }?.let { put("designation_id", it) }
            put("keep_name_private", edit.keepNamePrivate)
            edit.showMailboxInCrewList?.let { put("zillit_email_enable", it) }
        }

        return apiClient.envelope(
            verb = if (asAdmin) HttpVerb.Put else HttpVerb.Post,
            url = if (asAdmin) "${core}user/profile" else "${core}user/profile/change-requests",
            module = RequestModule.ProjectUser,
            body = body,
        ).map { if (asAdmin) ProfileSaveOutcome.Saved else ProfileSaveOutcome.SentForApproval }
    }

    override suspend fun setRecoveryEmail(email: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${core}device/recovery-email",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("email", email.trim()) },
        ).map { }

    override suspend fun recoveryDetails(): ZillitResult<RecoveryDetails> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${core}device",
            serializer = RecoveryDeviceDto.serializer(),
            module = RequestModule.Device,
        ).map { dto ->
            RecoveryDetails(
                key = dto.setting?.recoveryCode?.trim().orEmpty(),
                email = dto.setting?.recoveryEmail?.trim().orEmpty(),
            )
        }

    override suspend fun linkedDevices(): ZillitResult<List<LinkedDevice>> {
        val here = thisDeviceId()
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "${core}device/linked",
            serializer = ListSerializer(LinkedDeviceDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            rows.mapNotNull { it.toDomain(here) }.also { devices -> warnIfUnrecognised(here, devices) }
        }
    }

    /**
     * Says so when this computer is not among its own linked devices.
     *
     * The "This computer" tag is the only thing stopping someone signing
     * themselves out while clearing a phone they lost, so its absence must not
     * be silent. No ids are logged — the device id is redacted everywhere else
     * for good reason, and which of the two cases happened is the whole
     * diagnostic.
     */
    private fun warnIfUnrecognised(here: String?, devices: List<LinkedDevice>) {
        if (devices.isEmpty() || devices.any { it.isThisDevice }) return
        ZillitLog.w(TAG) {
            if (here == null) {
                "this device has no id yet, so its own row cannot be marked"
            } else {
                "this device's id matches none of the ${devices.size} linked rows"
            }
        }
    }


    override suspend fun unlinkDevice(deviceId: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${core}device/unlink",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("device_id", deviceId) },
        ).map { }

    override suspend fun leaveProduction(): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${core}user/leave-project",
            module = RequestModule.ProjectUser,
            // The header carries who and which production; the web posts no
            // body at all here, and adding one has the server reject the call.
            body = null,
        ).map { }

    private companion object {
        const val TAG = "Account"
    }
}

/** The part of `GET device` the Recovery page reads (web `WebrecoveryMail.jsx`). */
@Serializable
internal data class RecoveryDeviceDto(
    @SerialName("device_setting") val setting: RecoverySettingDto? = null,
)

@Serializable
internal data class RecoverySettingDto(
    @SerialName("projects_recovery_code") val recoveryCode: String? = null,
    @SerialName("recovery_email") val recoveryEmail: String? = null,
)

/**
 * One row from `device/linked`.
 *
 * Every field nullable, matching Android's own model — a device registered by
 * an older build carries neither `app_version` nor a `device_info`, and a
 * strict reader would drop the row rather than the field, leaving someone
 * unable to sign out the phone they lost.
 */
@Serializable
internal data class LinkedDeviceDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("device_info") val info: DeviceInfoDto? = null,
    @SerialName("last_activity") val lastActivity: Long? = null,
    @SerialName("parent_device_id") val parentDeviceId: String? = null,
    @SerialName("primary_device_id") val primaryDeviceId: String? = null,
) {
    fun toDomain(thisDeviceId: String?): LinkedDevice? {
        // Unlinking is keyed on the id, so a row without one cannot be acted
        // on. Showing it would be a device nobody can sign out.
        val resolved = deviceId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() }
            ?: return null

        return LinkedDevice(
            id = resolved,
            name = info?.deviceName?.trim().orEmpty(),
            kind = info?.deviceType?.takeIf { it.isNotBlank() },
            osVersion = info?.osVersion?.takeIf { it.isNotBlank() },
            appVersion = appVersion?.takeIf { it.isNotBlank() },
            lastActiveMillis = lastActivity?.takeIf { it > 0 },
            // By either id: the header carries whichever of `device_id`/`_id`
            // the registration answered with, and a row can spell it the other way.
            isThisDevice = thisDeviceId != null && (thisDeviceId == resolved || thisDeviceId == id),
            /*
             * The account's own device — the one the others were linked *to*.
             *
             * A row names its primary in `primary_device_id`, and the primary
             * row leaves that field null because it does not point at anyone.
             * So "no primary named" *is* the primary, and a row naming itself
             * counts too.
             *
             * This first read also fell back to "no `parent_device_id`", which
             * looked equivalent and is not: the field is null on every row this
             * endpoint returns, so every device came back primary and the page
             * offered no way to sign anything out. Caught live on dev,
             * 2026-08-12.
             */
            isPrimary = primaryDeviceId.isNullOrBlank() || primaryDeviceId == resolved,
        )
    }
}

@Serializable
internal data class DeviceInfoDto(
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("deviceType") val deviceType: String? = null,
    @SerialName("osVersion") val osVersion: String? = null,
)
