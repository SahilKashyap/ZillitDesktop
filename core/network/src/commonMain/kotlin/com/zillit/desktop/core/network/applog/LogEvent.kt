package com.zillit.desktop.core.network.applog

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `POST location/log` payload — one envelope shared by iOS, Android and
 * web (`schema_version` 1; iOS `LogEvent`, web `log-event.js`).
 *
 * Every server-side log event is built as one of these, and the envelope keys
 * are stamped in exactly one place ([envelope]), so a call site cannot invent a
 * new spelling of an existing concept. Anything event-specific goes into
 * [context], never top-level.
 */
enum class LogCategory(val wire: String) {
    /** An HTTP call worth recording — send [LogEvent.api] too. */
    Api("api"),
    Socket("socket"),
    Screen("screen"),
    Lifecycle("lifecycle"),
    Call("call"),
    Crash("crash"),
}

data class LogError(val message: String, val code: String? = null)

data class LogApi(
    /** Scheme, host and path — never the query string, where ids and tokens ride. */
    val url: String,
    val method: String,
    /** Null when the request never landed (no HTTP response). */
    val status: Int? = null,
)

data class LogEvent(
    val category: LogCategory,
    /** Short, stable name — "PUT /v2/device". For api events the full URL is in [api]. */
    val name: String,
    val error: LogError? = null,
    val api: LogApi? = null,
    val context: Map<String, String> = emptyMap(),
)

/**
 * What the envelope says about this install — the same for every event, so
 * built once by the host rather than per call.
 *
 * `device_id` / `project_id` / `user_id` are deliberately absent: the backend
 * stamps them from the `moduledata` header of the log call itself.
 */
data class LogIdentity(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    /** The install's id as the phones send it (iOS: the encrypted server device id). */
    val installId: () -> String,
    /** "wifi", "cellular", "none" or "unknown" — only what is actually knowable. */
    val network: () -> String,
)

/** The `data` object of one wire record. The record's `unique_id` is the engine's. */
fun LogEvent.envelope(identity: LogIdentity, eventTimeMillis: Long): JsonObject = buildJsonObject {
    put("schema_version", SCHEMA_VERSION)
    put("platform", identity.platform)
    put("app_version", identity.appVersion)
    put("os_version", identity.osVersion)
    put("device_model", identity.deviceModel)
    put("install_id", identity.installId())
    put("network", identity.network())
    put("event_time", eventTimeMillis)
    put("category", category.wire)
    put("name", name)
    error?.let { error ->
        put(
            "error",
            buildJsonObject {
                put("message", error.message)
                error.code?.let { put("code", it) }
            },
        )
    }
    api?.let { api ->
        put(
            "api",
            buildJsonObject {
                put("url", api.url)
                put("method", api.method)
                put("status", api.status?.let(::JsonPrimitive) ?: JsonNull as JsonElement)
            },
        )
    }
    if (context.isNotEmpty()) {
        put("context", JsonObject(context.mapValues { JsonPrimitive(it.value) }))
    }
}

/**
 * "PUT /v2/call/log-miss-call-multiple" — method and path, no host, no query,
 * the `/api` prefix dropped. iOS `apiEventName`: short and stable, so the
 * store groups by endpoint rather than by parameters.
 */
fun apiEventName(method: String, url: String): String {
    var path = pathOf(url)
    if (path.startsWith("/api/")) path = path.removePrefix("/api")
    return "$method ${path.ifEmpty { url }}"
}

/** [url] without its query string or fragment. */
fun withoutQuery(url: String): String = url.substringBefore('?').substringBefore('#')

private fun pathOf(url: String): String {
    val bare = withoutQuery(url)
    val afterScheme = bare.substringAfter("://", missingDelimiterValue = "")
    return if (afterScheme.isEmpty()) bare else "/" + afterScheme.substringAfter('/', missingDelimiterValue = "")
}

private const val SCHEMA_VERSION = 1
