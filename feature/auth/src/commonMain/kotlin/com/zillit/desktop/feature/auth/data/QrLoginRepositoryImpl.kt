package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.QrLoginRepository
import com.zillit.desktop.feature.auth.domain.QrLoginSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * QR device linking against the existing backend contract.
 *
 * Endpoint paths and payload shapes are transcribed from `StartProjectVM` and
 * `AdminVM` so the desktop and Android clients speak to the same API.
 */
class QrLoginRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val deviceInfo: DeviceInfo,
    private val randomCode: () -> String = ::generateLoginCode,
    private val nowMillis: () -> Long,
    private val newDeviceId: () -> String,
    private val onDeviceIdGenerated: suspend (String) -> Unit = {},
    private val onScannerIdentified: suspend (String) -> Unit = {},
    private val onLinked: suspend (DeviceIdentity) -> Unit = {},
) : QrLoginRepository {

    private val endpoints = QrEndpoints(config)

    override suspend fun startSession(): ZillitResult<QrLoginSession> {
        val code = randomCode()

        // The device id is minted here, before the first call, because the
        // `moduledata` header on that very call must already carry it — the web
        // client does the same (`Login.jsx: generateAndStoreDeviceId()` runs
        // inside `fetchData`, ahead of `saveQr`). A fresh id per login attempt
        // is deliberate, not an oversight: this device has no identity until the
        // scan links it to one.
        onDeviceIdGenerated(newDeviceId())

        return apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.qrCode,
            serializer = QrCodeDto.serializer(),
            module = RequestModule.Device,
            body = jsonBody(QrCodeRequestDto(code = code, fileName = FILE_NAME)),
        ).flatMapNotNull { dto ->
            val id = dto.id
            if (id.isNullOrBlank()) {
                ZillitResult.Failure(ZillitError.Serialization("qrcode response carried no id"))
            } else {
                ZillitResult.Success(
                    QrLoginSession(
                        id = id,
                        // Prefer the server's code if it echoes one back — it is
                        // authoritative, and a mismatch would produce a QR that
                        // scans to something the backend does not recognise.
                        code = dto.code?.takeIf { it.isNotBlank() } ?: code,
                        expiresAtMillis = nowMillis() + QrLoginSession.LIFETIME_MILLIS,
                    ),
                )
            }
        }
    }

    override suspend fun pollScanned(session: QrLoginSession): ZillitResult<String?> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.qrCodeById(session.id),
            serializer = QrCodeDto.serializer(),
            module = RequestModule.Device,
            // `code` alone — the web poll omits `file_name`, and the extra
            // field is only meaningful to the endpoint that renders the image.
            body = jsonBody(QrPollRequestDto(code = session.code)),
        ).map { dto ->
            // `scanner_device_id`, NOT `scanned_device_id`. The response carries
            // both, and only the former means "a phone scanned this". Reading
            // the latter reports a scan on the very first poll and drives
            // link-scanned into a 406. Web checks `res?.scanner_device_id`;
            // Android checks `response.data?.scannerDeviceId`.
            dto.scannerDeviceId?.takeIf { it.isNotBlank() }
        }

    override suspend fun completeLink(
        session: QrLoginSession,
        scannerDeviceId: String,
    ): ZillitResult<DeviceIdentity> {
        // Must land before the request is built: the header for this call
        // carries the scanning device's id, and without it the server answers
        // 406.
        onScannerIdentified(scannerDeviceId)

        return apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.linkScanned,
            serializer = DeviceDto.serializer(),
            // MODELDATA.SCANNER_DEVICE_ID (`StartProjectVM.linkedQrCode`).
            module = RequestModule.ScannerDevice,
            body = jsonBody(
                LinkDeviceDto(
                    deviceName = deviceInfo.name,
                    deviceType = deviceInfo.type,
                    osVersion = deviceInfo.osVersion,
                ),
            ),
        ).flatMapNotNull { dto ->
            val identity = dto.toDomain()
            if (identity == null) {
                ZillitResult.Failure(ZillitError.Serialization("link-scanned returned no device id"))
            } else {
                onLinked(identity)
                ZillitResult.Success(identity)
            }
        }
    }

    private companion object {
        /**
         * What the web client sends (`Login.jsx`). The server uses it to name
         * the PNG it renders; the desktop encodes the QR locally and never
         * fetches that image, but the field is still required by the endpoint.
         */
        const val FILE_NAME = "qrcode.png"
    }
}

/** How this machine identifies itself when linking. */
data class DeviceInfo(
    val name: String,
    val type: String,
    val osVersion: String,
)

/**
 * Generates a login code in the shape the backend expects:
 * 125 random characters, the literal `ZILLIT`, then 125 more.
 *
 * Taken from the **web** client (`Login.jsx`: `generateRandomString(125)` around
 * `"ZILLIT"`), which is the flow this screen reproduces. Android uses 64/`zillit`
 * — the separator differs in case, so the two are not interchangeable and this
 * must not be "tidied" to match the Android source.
 *
 * **Not** cryptographically seeded here — the platform supplies the randomness;
 * this only assembles the shape. See the JVM `SecureRandom`-backed provider
 * wired in `AppGraph`.
 */
internal fun generateLoginCode(random: () -> Char = { CODE_ALPHABET.random() }): String {
    fun segment() = buildString(CODE_SEGMENT_LENGTH) { repeat(CODE_SEGMENT_LENGTH) { append(random()) } }
    return segment() + CODE_SEPARATOR + segment()
}

internal const val CODE_SEGMENT_LENGTH = 125
internal const val CODE_SEPARATOR = "ZILLIT"
internal val CODE_ALPHABET: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')

internal class QrEndpoints(private val config: AppConfig) {
    private val device get() = "${config.apiV2()}device/"

    val qrCode get() = "${device}qrcode"
    fun qrCodeById(id: String) = "${device}qrcode/$id"
    val linkScanned get() = "${device}link-scanned"
}

@Serializable
internal data class QrCodeRequestDto(
    @SerialName("code") val code: String,
    @SerialName("file_name") val fileName: String,
)

/** The poll body: `code` only — see `retriveScannedQr` in the web client. */
@Serializable
internal data class QrPollRequestDto(
    @SerialName("code") val code: String,
)

@Serializable
internal data class QrCodeDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("code") val code: String? = null,
    /** Present from creation — NOT a scan signal. See `pollScanned`. */
    @SerialName("scanned_device_id") val scannedDeviceId: String? = null,

    /** Set once a signed-in device scans the code. This is the signal. */
    @SerialName("scanner_device_id") val scannerDeviceId: String? = null,
)

@Serializable
internal data class LinkDeviceDto(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("os_version") val osVersion: String,
)
