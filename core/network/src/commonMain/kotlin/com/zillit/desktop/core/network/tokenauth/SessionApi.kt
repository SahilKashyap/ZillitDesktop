package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestHeaderProvider
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.headersFor
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** How a session call ended. */
sealed interface SessionCallResult<out T> {
    data class Success<T>(val data: T) : SessionCallResult<T>

    /**
     * [httpStatus] null is a transport failure — offline, a timeout — which
     * is retryable and says nothing about the session. Otherwise the status
     * the server answered, with its `message` word when it sent one.
     */
    data class Failure(val httpStatus: Int?, val serverMessage: String?) : SessionCallResult<Nothing>
}

/** `POST session/device` and `session/device/refresh` answer this. */
data class DeviceSession(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long?)

/** `POST session/project` answers this. */
data class ProjectSession(val accessToken: String, val scope: String?, val expiresInSeconds: Long?)

/**
 * The three session endpoints of the `moduledata` → Bearer migration.
 *
 * `session/device` still authenticates with the legacy headers — the whole
 * `moduledata` / `deviceInfo` / `bodyhash` set over an empty body. The
 * refresh sends the refresh token alone; the project mint sends the device
 * token as its Bearer.
 */
interface SessionApi {
    suspend fun establishDeviceSession(): SessionCallResult<DeviceSession>

    suspend fun refreshDeviceSession(refreshToken: String): SessionCallResult<DeviceSession>

    suspend fun mintProjectToken(deviceAccessToken: String, projectId: String): SessionCallResult<ProjectSession>
}

/**
 * [SessionApi] over Ktor.
 *
 * Give it a lean client — no body logging, no response validator. Every
 * session answer carries live tokens, including the long-lived refresh
 * token, and a 401 here is a routine signal the session manager acts on,
 * not the app-wide "session gone".
 */
class KtorSessionApi(
    private val httpClient: HttpClient,
    private val headerProvider: RequestHeaderProvider,
    /** `https://<host>/api/v2/`, trailing slash included. */
    private val apiV2: String,
) : SessionApi {

    override suspend fun establishDeviceSession(): SessionCallResult<DeviceSession> = post(
        url = apiV2 + "session/device",
        headers = headerProvider.headersFor(RequestModule.SessionBootstrap, bodyJson = null, projectId = null),
        bodyJson = null,
        read = ::readDevice,
    )

    override suspend fun refreshDeviceSession(refreshToken: String): SessionCallResult<DeviceSession> = post(
        url = apiV2 + "session/device/refresh",
        headers = emptyMap(),
        bodyJson = buildJsonObject { put("device_refresh_token", JsonPrimitive(refreshToken)) }.toString(),
        read = ::readDevice,
    )

    override suspend fun mintProjectToken(
        deviceAccessToken: String,
        projectId: String,
    ): SessionCallResult<ProjectSession> = post(
        url = apiV2 + "session/project",
        headers = mapOf(ZillitHeaders.AUTHORIZATION to "Bearer $deviceAccessToken"),
        bodyJson = buildJsonObject { put("project_id", JsonPrimitive(projectId)) }.toString(),
        read = ::readProject,
    )

    private suspend fun <T> post(
        url: String,
        headers: Map<String, String>,
        bodyJson: String?,
        read: (JsonObject) -> T?,
    ): SessionCallResult<T> = try {
        val response = httpClient.request(url) {
            method = HttpMethod.Post
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            if (bodyJson != null) {
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
        }
        val text = response.bodyAsText()
        if (response.status.value in SUCCESS_RANGE) {
            parse(text, read)
        } else {
            SessionCallResult.Failure(response.status.value, messageOf(text))
        }
    } catch (cancellation: CancellationException) {
        // Never a transport failure: a rotation completed server-side would
        // otherwise be reported as failed and its refresh token dropped.
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
        SessionCallResult.Failure(null, thrown.message)
    }

    private companion object {
        val SUCCESS_RANGE = 200..202
        const val STATUS_OK = 1

        /** The envelope: `{status: 1, data: {...}}`; anything else is the server's refusal. */
        fun <T> parse(text: String, read: (JsonObject) -> T?): SessionCallResult<T> {
            val envelope = runCatching { HttpClientFactory.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val status = (envelope?.get("status") as? JsonPrimitive)?.intOrNull
            val data = (envelope?.get("data") as? JsonObject)?.let(read)
            return if (status == STATUS_OK && data != null) {
                SessionCallResult.Success(data)
            } else {
                SessionCallResult.Failure(STATUS_REFUSED, envelope?.word("message") ?: messageOf(text))
            }
        }

        /** An HTTP 2xx whose envelope still said no — the phones' `-1`. */
        const val STATUS_REFUSED = -1

        fun readDevice(data: JsonObject): DeviceSession? {
            val access = data.word("device_access_token") ?: return null
            val refresh = data.word("device_refresh_token") ?: return null
            return DeviceSession(access, refresh, data.seconds("expires_in"))
        }

        fun readProject(data: JsonObject): ProjectSession? {
            val access = data.word("project_access_token") ?: return null
            return ProjectSession(access, data.word("scope"), data.seconds("expires_in"))
        }

        fun messageOf(text: String): String? =
            runCatching { HttpClientFactory.json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.word("message")

        fun JsonObject.word(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        fun JsonObject.seconds(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    }
}
