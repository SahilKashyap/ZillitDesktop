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
    scope: CoroutineScope,
): AppLogger {
    val endpoint = config.apiV2(ZillitService.Lcw) + "location/log"
    val logger = AppLogger(
        queue = database?.let(::SqlLogQueue) ?: InMemoryLogQueue(),
        sender = LogSender { records -> sendLogs(apiClient(), endpoint, records) },
        identity = LogIdentity(
            platform = "desktop",
            appVersion = installedAppVersion(),
            osVersion = "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()}".trim(),
            deviceModel = "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.arch").orEmpty()}".trim(),
            // As iOS: the server device id, encrypted with the header key.
            installId = {
                val id = deviceId()
                id.takeIf { it.isNotBlank() }?.let { (encryptToHex(it) as? ZillitResult.Success)?.data } ?: "N/A"
            },
            // A desktop cannot cheaply tell wifi from ethernet; say only what is known.
            network = { if (online.value) "unknown" else "none" },
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
internal fun ApiFailure.toLogEvent(): LogEvent = LogEvent(
    category = LogCategory.Api,
    name = apiEventName(method, url),
    error = LogError(message = message, code = status?.toString()),
    api = LogApi(url = withoutQuery(url), method = method, status = status),
)

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
