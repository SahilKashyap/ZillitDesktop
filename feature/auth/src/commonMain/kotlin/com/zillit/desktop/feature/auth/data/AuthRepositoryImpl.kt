package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.auth.domain.DeviceStatus
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.AuthSession
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.DeviceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device registration and recovery against the Zillit backend.
 *
 * Session state is a `StateFlow` so the shell can react to sign-out without
 * polling — a revoked device (`ZillitError.Unauthorized` from any call) must
 * take the user back to the sign-in screen immediately, not on next navigation.
 */
class AuthRepositoryImpl(
    private val apiClient: ApiClient,
    private val secureStore: SecureStore,
    config: AppConfig,
    private val onSignOut: suspend () -> kotlin.Unit = {},
    /**
     * Called the moment this device gains an identity.
     *
     * The header builder needs the device id for every subsequent request, and
     * it is only known after registration, recovery or session restore — so it
     * is pushed rather than polled.
     */
    private val onDeviceIdentified: suspend (DeviceIdentity) -> kotlin.Unit = {},
    /** This machine, for `PUT device`. Null sends nothing. */
    private val deviceReport: (() -> DeviceReport)? = null,
    /**
     * Where that report runs, so signing in never waits on it. Null sends
     * nothing; a test that wants the call makes it through [reportDevice].
     */
    private val reportScope: CoroutineScope? = null,
) : AuthRepository {

    /** The device id this launch has already described to the server. */
    private var reportedFor: String? = null

    private val endpoints = AuthEndpoints(config)
    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: Flow<AuthSession?> = _session.asStateFlow()

    override suspend fun requestOtp(email: String, language: String): ZillitResult<kotlin.Unit> {
        val normalised = email.trim().lowercase()
        if (!normalised.looksLikeEmail()) {
            return ZillitResult.Failure(ZillitError.Validation(str(S.docusign_role_email_invalid)))
        }

        // Pre-auth: no session headers, because there is no session yet.
        return apiClient.envelope(
            verb = HttpVerb.Post,
            url = endpoints.requestOtp,
            module = RequestModule.Device,
            body = jsonBody(OtpRequestDto(email = normalised, language = language)),
        ).map { }
    }

    override suspend fun verifyOtp(email: String, otp: String): ZillitResult<String> {
        if (otp.isBlank()) {
            return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_enter_code_from_email)))
        }

        return apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.verifyOtp,
            serializer = ConfirmCodeDto.serializer(),
            module = RequestModule.Device,
            body = jsonBody(OtpVerifyDto(email = email.trim().lowercase(), otp = otp.trim())),
        ).flatMapNotNull { dto ->
            val confirmCode = dto.confirmCode?.takeIf { it.isNotBlank() }
            if (confirmCode == null) {
                ZillitResult.Failure(ZillitError.Serialization("verify returned no confirm_code"))
            } else {
                ZillitResult.Success(confirmCode)
            }
        }
    }

    override suspend fun registerDevice(email: String, confirmCode: String): ZillitResult<DeviceIdentity> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.device,
            serializer = DeviceDto.serializer(),
            module = RequestModule.Device,
            body = jsonBody(mapOf("email" to email.trim().lowercase(), "confirm_code" to confirmCode)),
        ).flatMapNotNull { dto ->
            val identity = dto.toDomain()
            if (identity == null) {
                ZillitResult.Failure(ZillitError.Serialization("device response carried no id"))
            } else {
                persist(identity)
                ZillitResult.Success(identity)
            }
        }

    override suspend fun requestRecovery(email: String): ZillitResult<kotlin.Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = endpoints.recoveryByEmail,
            module = RequestModule.Device,
            body = jsonBody(OtpRequestDto(email = email.trim().lowercase())),
        ).map { }

    override suspend fun recoverWithCode(code: String): ZillitResult<DeviceIdentity> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.recoveryByCode,
            serializer = DeviceDto.serializer(),
            module = RequestModule.Device,
            body = jsonBody(mapOf("recover_code" to code.trim())),
        ).flatMapNotNull { dto ->
            val identity = dto.toDomain()
            if (identity == null) {
                ZillitResult.Failure(ZillitError.Serialization("recovery response carried no id"))
            } else {
                persist(identity)
                ZillitResult.Success(identity)
            }
        }

    /**
     * Tells the server first — while the headers still carry the device and
     * the session — then clears local state.
     *
     * The server call is best-effort: a failure does not un-sign-out, because
     * signing out only on a successful round trip leaves someone stuck signed
     * in on a machine they are trying to leave (Android likewise clears
     * `SharedPref` whatever the answer, `ProjectListActivity.kt:194-211`).
     * But it has to go *before* the local wipe: `POST device/unlink` needs the
     * device id in its body and the project-user headers, and both are gone a
     * line later.
     */
    /**
     * The device id reaches the headers first — the report is authenticated
     * by it — and then the server is told what this device is, once a launch.
     */
    private suspend fun identified(identity: DeviceIdentity) {
        onDeviceIdentified(identity)
        if (reportedFor == identity.deviceId) return
        val scope = reportScope ?: return
        reportedFor = identity.deviceId
        scope.launch { reportDevice() }
    }

    override suspend fun reportDevice(): ZillitResult<kotlin.Unit> {
        val report = deviceReport?.invoke() ?: return ZillitResult.Success(kotlin.Unit)
        val answer = apiClient.envelope(
            verb = HttpVerb.Put,
            url = endpoints.device,
            // `MODELDATA.DEFAULT`, as `CommonApis.updateDevice` sends it.
            module = RequestModule.Default,
            body = jsonBody(
                DeviceUpdateDto(
                    deviceName = report.name,
                    deviceType = report.type,
                    osVersion = report.osVersion,
                    appVersion = report.appVersion,
                ),
            ),
        )
        return when (answer) {
            // A 200 can still say no (`status:0`); say so rather than believe it.
            is ZillitResult.Success -> {
                if (answer.data.status == 0) {
                    ZillitLog.w(TAG) { "device details refused: ${answer.data.message}" }
                } else {
                    ZillitLog.i(TAG) {
                        "device details sent: ${report.type}, ${report.osVersion}, ${report.appVersion}"
                    }
                }
                ZillitResult.Success(kotlin.Unit)
            }
            is ZillitResult.Failure -> {
                ZillitLog.w(TAG) { "device details not sent: ${answer.error.technical}" }
                answer
            }
        }
    }

    override suspend fun signOut(): ZillitResult<kotlin.Unit> {
        val deviceId = _session.value?.device?.deviceId
            ?: runCatching { storedDeviceId() }.getOrNull()
        if (deviceId != null) {
            runCatching {
                apiClient.envelope(
                    HttpVerb.Post,
                    endpoints.unlinkDevice,
                    module = RequestModule.ProjectUser,
                    body = jsonBody(UnlinkDeviceDto(deviceId)),
                )
            }.onFailure { ZillitLog.w(TAG) { "sign-out notification failed: ${it::class.simpleName}" } }
        }

        _session.value = null
        val cleared = secureStore.clear()
        onSignOut()
        return cleared
    }

    /**
     * `GET device` — does the server still know us?
     *
     * Deliberately opts out of unauthorized reporting: this call is what
     * *answers* the question a 401 raised, and reporting its own 401 would make
     * the question ask itself again, forever.
     */
    override suspend fun confirmDevice(): DeviceStatus {
        val answer = apiClient.envelope(
            verb = HttpVerb.Get,
            url = endpoints.device,
            module = RequestModule.Device,
            options = CallOptions(reportUnauthorized = false),
        )

        return when (answer) {
            is ZillitResult.Success -> DeviceStatus.Valid
            is ZillitResult.Failure ->
                if (answer.error is ZillitError.Unauthorized) DeviceStatus.Revoked else DeviceStatus.Unknown
        }
    }

    override suspend fun deviceRecord(): ZillitResult<DeviceIdentity?> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.device,
            serializer = DeviceDto.serializer(),
            module = RequestModule.Device,
            options = CallOptions(reportUnauthorized = false),
        ).map { it.toDomain() }

    /** Restores a session from the keychain at launch, if one exists. */
    suspend fun restoreSession(): ZillitResult<AuthSession?> {
        val stored = when (val result = secureStore.get(SecureKey.DeviceKey)) {
            is ZillitResult.Failure -> return result
            is ZillitResult.Success -> result.data ?: return ZillitResult.Success(null)
        }

        return apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.device,
            serializer = DeviceDto.serializer(),
            module = RequestModule.Device,
        ).flatMapNotNull { dto ->
            val identity = dto.toDomain()
            if (identity == null) {
                ZillitResult.Success(null)
            } else {
                _session.value = AuthSession(identity, activeProject = null, activeUnit = null)
                identified(identity)
                ZillitResult.Success(_session.value)
            }
        }.also { stored.fill(0) }
    }

    fun updateSession(transform: (AuthSession?) -> AuthSession?) {
        _session.value = transform(_session.value)
    }

    /**
     * Reads the linked device back from the keychain.
     *
     * Without this the app asks for a QR scan on every launch even though it
     * already knows the device — the id was written at link time and never read.
     */
    override suspend fun restoreDevice(): ZillitResult<DeviceIdentity?> {
        val stored = secureStore.get(SecureKey.DeviceKey).getOrNull()
            ?: return ZillitResult.Success(null)

        val deviceId = stored.decodeToString().also { stored.fill(0) }
        if (deviceId.isBlank()) return ZillitResult.Success(null)

        val identity = DeviceIdentity(deviceId = deviceId, email = "", isPrimary = false)
        _session.value = AuthSession(identity, activeProject = null, activeUnit = null)
        // Puts the device id back into outgoing headers; nothing authenticated
        // works until it is there.
        identified(identity)
        return ZillitResult.Success(identity)
    }

    /** The keychain's device id, when the session was never restored into memory. */
    private suspend fun storedDeviceId(): String? {
        val stored = secureStore.get(SecureKey.DeviceKey).getOrNull() ?: return null
        return stored.decodeToString().also { stored.fill(0) }.takeIf { it.isNotBlank() }
    }

    override suspend fun rememberDevice(identity: DeviceIdentity): ZillitResult<kotlin.Unit> {
        persist(identity)
        return ZillitResult.Success(kotlin.Unit)
    }

    private suspend fun persist(identity: DeviceIdentity) {
        secureStore.put(SecureKey.DeviceKey, identity.deviceId.encodeToByteArray())
        _session.value = AuthSession(identity, activeProject = null, activeUnit = null)
        identified(identity)
    }

    private companion object {
        const val TAG = "Auth"
    }
}

/**
 * `flatMap` whose transform may suspend — repositories routinely need to persist
 * something before returning, and `ZillitResult.map` cannot express that.
 */
internal suspend inline fun <T, R> ZillitResult<T>.flatMapNotNull(
    crossinline transform: suspend (T) -> ZillitResult<R>,
): ZillitResult<R> = when (this) {
    is ZillitResult.Success -> transform(data)
    is ZillitResult.Failure -> this
}

/**
 * Deliberately permissive.
 *
 * Client-side email validation exists to catch typos, not to enforce RFC 5322 —
 * a stricter regex rejects valid addresses (new TLDs, plus-addressing, unicode
 * domains) and the server validates properly anyway.
 */
internal fun String.looksLikeEmail(): Boolean =
    contains('@') &&
        substringAfter('@').contains('.') &&
        none { it.isWhitespace() } &&
        length >= MIN_EMAIL_LENGTH

/** `a@b.c` is the shortest thing that could plausibly be an address. */
private const val MIN_EMAIL_LENGTH = 5
