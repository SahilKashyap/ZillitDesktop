package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.database.ZillitDatabase
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiFailure
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.applog.AppLogger
import com.zillit.desktop.core.network.applog.LogApi
import com.zillit.desktop.core.network.applog.LogCategory
import com.zillit.desktop.core.network.applog.LogError
import com.zillit.desktop.core.network.applog.LogEvent
import com.zillit.desktop.core.network.applog.LogIdentity
import com.zillit.desktop.core.network.applog.LogSender
import com.zillit.desktop.core.network.applog.QueuedLog
import com.zillit.desktop.core.network.applog.apiEventName
import com.zillit.desktop.core.network.applog.withoutQuery
import com.zillit.desktop.core.network.tokenauth.TokenSessionManager
import com.zillit.desktop.core.socket.SocketFailureReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The error log (`POST lcwapi/api/v2/location/log`), as iOS sends it: every
 * failed API call, on the spot, in the shared `schema_version` 1 envelope.
 *
 * Sent with [RequestModule.Telemetry] — always `moduledata` — and never
 * allowed to sign the user out: the log is hit after every failure, and a
 * broken log endpoint must stay invisible (iOS `isTelemetryRequest`).
 */
@Suppress("LongParameterList") // The graph's pieces the log needs, named where they are wired.
internal fun appLogger(
    apiClient: () -> ApiClient,
    config: AppConfig,
    database: ZillitDatabase?,
    encryptToHex: (String) -> ZillitResult<String>,
    deviceId: () -> String,
    online: StateFlow<Boolean>,
    /** The open production's name; null between productions. */
    projectName: () -> String?,
    /** The signed-in person's name; null before a production is open. */
    userName: () -> String?,
    scope: CoroutineScope,
): AppLogger {
    fun osLabel(second: String?) = "${System.getProperty("os.name").orEmpty()} ${second.orEmpty()}".trim()
    val endpoint = config.apiV2(ZillitService.Lcw) + "location/log"
    val logger = AppLogger(
        queue = database?.let(::SqlLogQueue) ?: InMemoryLogQueue(),
        sender = LogSender { records -> sendLogs(apiClient(), endpoint, records) },
        identity = LogIdentity(
            platform = "desktop",
            appVersion = installedAppVersion(),
            osVersion = osLabel(System.getProperty("os.version")),
            deviceModel = osLabel(System.getProperty("os.arch")),
            // As iOS: the server device id, encrypted with the header key.
            installId = {
                val id = deviceId()
                id.takeIf { it.isNotBlank() }?.let { (encryptToHex(it) as? ZillitResult.Success)?.data } ?: "N/A"
            },
            // A desktop cannot cheaply tell wifi from ethernet; say only what is known.
            network = { if (online.value) "unknown" else "none" },
            // Read at event time, as the web reads them out of storage: the
            // log carries names now, not only ids (web PR #6523).
            projectName = projectName,
            userName = userName,
            // What the device is registered under at sign-in — the phones
            // send their own name here too.
            deviceDisplayName = DesktopDevice.name,
        ),
        scope = scope,
        newId = { UUID.randomUUID().toString().lowercase() },
        nowMillis = System::currentTimeMillis,
        isOnline = { online.value },
    )
    // Whatever the last session could not send, and whatever piled up while offline.
    logger.flush()
    scope.launch { online.drop(1).filter { it }.collect { logger.flush() } }
    return logger
}

/** One failed call, as iOS `AnalyticsManager.logApiFailure` records it. */
internal fun ApiFailure.toLogEvent(): LogEvent = apiLogEvent(method, url, status, message)

/**
 * The `api` envelope every failed call is recorded as, whoever noticed it.
 *
 * `ApiClient` reports the envelope calls; the raw export and download helpers
 * and the token session report their own, because neither rides that client —
 * one reads bytes instead of an envelope, the other's answers carry live
 * credentials. They land in the same shape on purpose: a failed PDF belongs
 * beside the failed GET next to it, which is where the web's own blob calls
 * land (they ride its axios interceptor like everything else).
 *
 * GDPR, as the web states it at its own single sink: the PATH and the status,
 * never a body, a header, a credential or a query string — ids and tokens ride
 * in those — and the server's message capped rather than carried whole.
 */
internal fun apiLogEvent(method: String, url: String, status: Int?, message: String): LogEvent = LogEvent(
    category = LogCategory.Api,
    name = apiEventName(method, url),
    error = LogError(message = message.take(MESSAGE_MAX), code = status?.toString()),
    api = LogApi(url = withoutQuery(url), method = method, status = status),
)

/**
 * `session_refresh_reuse_detected`, the one session verdict the web reports
 * too (`tokenSession`).
 *
 * It earns its line: the server has seen this refresh token twice, so it is
 * about to revoke every session on this install, and the sign-out that follows
 * has no other explanation anywhere. The rest of the session family stays
 * unrecorded on both clients — a `/session/device` that fails while the
 * network is down says nothing a retry does not.
 */
internal fun refreshReuseLogEvent(url: String, status: Int?): LogEvent =
    apiLogEvent(method = "POST", url = url, status = status, message = TokenSessionManager.MSG_REFRESH_REUSE)

/**
 * Socket failures in the error log — a refused handshake and a payload no
 * subscriber could read, the two the web reports from `SocketManager` and its
 * own listeners (`category: socket`).
 *
 * The socket layer names the failure and knows nothing else; the category, the
 * cap and the reading of [appLog] belong here, beside every other event this
 * app sends. [appLog] is a function because the socket is built before the log
 * is — the same slot `ApiClient` reports into.
 */
internal fun socketFailureReporter(
    appLog: () -> AppLogger?,
    nowMillis: () -> Long = System::currentTimeMillis,
): SocketFailureReporter {
    val cap = RepeatedFailureCap(nowMillis)
    return SocketFailureReporter { name, message, context ->
        val log = appLog()
        // `attempt` is deliberately NOT in the signature: it changes on every
        // retry, so a cap keyed on it would cap nothing at all.
        val signature = "$name|$message|${context["kind"].orEmpty()}|${context["event"].orEmpty()}"
        if (log != null && cap.allows(signature)) {
            log.log(
                LogEvent(
                    category = LogCategory.Socket,
                    name = name,
                    error = LogError(message = message),
                    context = context,
                ),
            )
        }
    }
}

/**
 * How often the same failure may be recorded.
 *
 * A socket that cannot connect retries for as long as the app is open and the
 * backoff caps at a minute, so one offline night would write a thousand
 * identical rows into a store with no retention policy. The web caps its own
 * catch-all reporting for exactly this reason (`global-error-capture`: three
 * per signature), and a desktop session outlives a browser tab by days — so
 * the cap is per rolling hour rather than per session: an outage is recorded
 * when it starts, and again if it is still going an hour later.
 *
 * Synchronised because the socket.io thread raises these, not a coroutine.
 */
internal class RepeatedFailureCap(
    private val nowMillis: () -> Long,
    private val perSignature: Int = PER_SIGNATURE,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private val seen = mutableMapOf<String, Int>()
    private var windowStart = 0L

    @Synchronized
    fun allows(signature: String): Boolean {
        val at = nowMillis()
        if (at - windowStart >= windowMillis) {
            windowStart = at
            seen.clear()
        }
        val count = (seen[signature] ?: 0) + 1
        seen[signature] = count
        return count <= perSignature
    }

    private companion object {
        const val PER_SIGNATURE = 3
        const val WINDOW_MILLIS = 60L * 60 * 1000
    }
}

/** As the web caps a server message before sending it to the log store. */
private const val MESSAGE_MAX = 300

/**
 * `{"data":[{"unique_id":…, "data":{envelope}}]}` — each record's `data` an
 * object, never a string (unqueryable server-side). Answers the ids the
 * server took: its `data` list when it sends one, else the whole batch.
 */
private suspend fun sendLogs(apiClient: ApiClient, endpoint: String, records: List<QueuedLog>): List<String>? {
    val body = buildJsonObject {
        put(
            "data",
            buildJsonArray {
                records.forEach { record ->
                    add(
                        buildJsonObject {
                            put("unique_id", record.uniqueId)
                            put("data", Json.parseToJsonElement(record.data))
                        },
                    )
                }
            },
        )
    }
    val outcome = apiClient.envelope(
        verb = HttpVerb.Post,
        url = endpoint,
        module = RequestModule.Telemetry,
        body = body,
        options = CallOptions(reportUnauthorized = false, readCache = false),
    )
    val envelope = (outcome as? ZillitResult.Success)?.data ?: return null
    val acknowledged = (envelope.data as? JsonArray)?.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element["unique_id"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }
    return acknowledged ?: records.map { it.uniqueId }
}
