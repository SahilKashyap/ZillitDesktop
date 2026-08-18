package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builds the encrypted headers every Zillit request carries.
 *
 * Two headers, transcribed from `NetworkClient.getHttpClient` +
 * `AppHelper.getModelData`:
 *
 *  - **`moduledata`** — a small JSON object (device id, project, user,
 *    timestamp) AES-encrypted to hex. Its exact contents vary by
 *    [RequestModule], which is what the Android `MODELDATA` enum selects.
 *  - **`deviceInfo`** — plain JSON describing the client. Not encrypted there,
 *    so not here either.
 *
 * ## This scheme is a compatibility shim
 *
 * The key is shared by every install, so this is transport obfuscation, not
 * authentication — anyone who can read the binary can forge these headers. It
 * is implemented faithfully because the backend requires it, and it is
 * scheduled for replacement by per-device request signing (plan §8.3).
 *
 * Timestamps are generated per request, so two identical calls produce
 * different ciphertext.
 */
class ZillitHeaderProvider(
    private val crypto: HeaderCrypto,
    private val context: () -> HeaderContext,
    private val deviceDescription: DeviceDescription,
    private val nowMillis: () -> Long,
    private val timeZoneId: () -> String,
) : RequestHeaderProvider {

    private val json = Json { explicitNulls = false; encodeDefaults = false }

    override suspend fun headersFor(
        module: RequestModule,
        bodyJson: String?,
        projectId: String?,
        userId: String?,
    ): Map<String, String> {
        val payload = json.encodeToString(HeaderPayload.serializer(), payloadFor(module, projectId, userId))

        val encrypted = when (val result = crypto.encryptToHex(payload)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> {
                // No key, or a broken one. Sending an unencrypted header would
                // be rejected anyway and would put the device id on the wire in
                // clear — better to send nothing and let the 401 be honest.
                ZillitLog.e(TAG) { "could not build moduledata header: ${result.error.technical}" }
                return emptyMap()
            }
        }

        val bodyHash = crypto.bodyHash(bodyJson.orEmpty(), encrypted)

        return buildMap {
            put(ZillitHeaders.MODULE_DATA, encrypted)
            put(ZillitHeaders.TIMEZONE, timeZoneId())
            put(
                ZillitHeaders.DEVICE_INFO,
                json.encodeToString(DeviceInfoPayload.serializer(), deviceDescription.toPayload()),
            )
            if (bodyHash is ZillitResult.Success) {
                put(ZillitHeaders.BODY_HASH, bodyHash.data)
            } else {
                ZillitLog.w(TAG) { "could not compute bodyhash — request will likely be rejected" }
            }
        }
    }

    /**
     * The `moduledata` contents for a given call.
     *
     * Mirrors `AppHelper.getModelData`. Null fields are omitted from the JSON,
     * matching Gson's default on the Android side — a header with extra
     * `"project_id":null` keys is a different string and encrypts differently.
     */
    private fun payloadFor(module: RequestModule, projectOverride: String?, userOverride: String?): HeaderPayload {
        val ambient = context()
        // The override wins where it is given, so a call about another
        // production does not have to move the whole app onto it first. The
        // user id travels with it: it is the person's id on THAT production.
        val current = when {
            projectOverride == null -> ambient
            userOverride == null -> ambient.copy(projectId = projectOverride)
            else -> ambient.copy(projectId = projectOverride, userId = userOverride)
        }
        val timestamp = nowMillis()

        return when (module) {
            // Pre-auth calls (device OTP, registration) — device id only.
            RequestModule.Device,
            RequestModule.Default,
            -> HeaderPayload(deviceId = current.deviceId, timeStamp = timestamp)

            RequestModule.Project -> HeaderPayload(
                deviceId = current.deviceId,
                projectId = current.projectId.orEmpty(),
                timeStamp = timestamp,
            )

            RequestModule.Chat,
            RequestModule.Media,
            RequestModule.LiveKit,
            RequestModule.Configuration,
            RequestModule.ProjectUser,
            -> HeaderPayload(
                deviceId = current.deviceId,
                projectId = current.projectId.orEmpty(),
                userId = current.userId.orEmpty(),
                timeStamp = timestamp,
            )

            // QR device linking: the scanning device's id travels alongside
            // ours (`MODELDATA.SCANNER_DEVICE_ID`).
            RequestModule.ScannerDevice -> HeaderPayload(
                deviceId = current.deviceId,
                scannerDeviceId = current.scannerDeviceId,
                timeStamp = timestamp,
            )

            RequestModule.NotificationAcknowledge -> HeaderPayload(
                projectId = current.projectId.orEmpty(),
                userId = current.userId.orEmpty(),
            )

            // No timestamp: `ReqHeaderForSocket` is device id alone.
            RequestModule.SocketHandshake -> HeaderPayload(deviceId = current.deviceId)

            // Route lookups are unauthenticated on the Android side too.
            RequestModule.MapRoute -> HeaderPayload(deviceId = "", timeStamp = timestamp)
        }
    }

    private companion object {
        const val TAG = "Headers"
    }
}

/**
 * What the header builder needs to know right now.
 *
 * A supplier rather than fixed values: device id arrives at registration and
 * project/user change on every project switch, so a snapshot taken at
 * construction would go stale within one session.
 */
data class HeaderContext(
    val deviceId: String,
    val projectId: String? = null,
    val userId: String? = null,
    val scannerDeviceId: String? = null,
)

/** Static description of this machine, sent as the `deviceInfo` header. */
data class DeviceDescription(
    val network: String,
    val osVersion: String,
    val deviceName: String,
    val deviceType: String,
) {
    internal fun toPayload() = DeviceInfoPayload(
        network = network,
        osversion = osVersion,
        deviceName = deviceName,
        deviceType = deviceType,
    )
}

/**
 * The encryption the header scheme needs, narrowed to one method.
 *
 * `core:network` deliberately does not depend on `core:security`: the transport
 * should not be able to reach the keychain, and a one-method interface makes
 * that boundary obvious.
 */
interface HeaderCrypto {
    fun encryptToHex(plaintext: String): ZillitResult<String>

    /**
     * `bodyhash`: SHA-256 over `{"payload":<body>,"moduledata":<encrypted>}`
     * concatenated with the IV as salt, lowercase hex.
     *
     * Transcribed from the web `generateBodyHash` and Android
     * `generateSHA256WithSalt`, which agree byte for byte. `payload` is the
     * parsed body — an object or array, or `""` for a bodiless request.
     */
    fun bodyHash(bodyJson: String, encryptedModuleData: String): ZillitResult<String>
}

/**
 * The `moduledata` JSON, matching the Android `ReqHeader`.
 *
 * Field names are snake_case on the wire and must stay exactly as they are:
 * this object is encrypted and compared server-side, so a renamed field is not
 * a parse warning, it is a rejected request.
 */
@Serializable
internal data class HeaderPayload(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("time_stamp") val timeStamp: Long? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("scanner_device_id") val scannerDeviceId: String? = null,
)

@Serializable
internal data class DeviceInfoPayload(
    @SerialName("network") val network: String,
    @SerialName("osversion") val osversion: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("deviceType") val deviceType: String,
)
