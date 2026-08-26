package com.zillit.desktop.feature.calls.data.protoo

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One frame of the protoo protocol, which is what Line 1 signals over.
 *
 * Three shapes, distinguished by a boolean flag: a request expects exactly one
 * response carrying its id, a response answers one, and a notification expects
 * nothing. That is the whole protocol — everything else about mediasoup rides
 * inside [data].
 */
sealed interface ProtooMessage {

    /** Someone is asking. Both ends send these; the SFU asks us to consume. */
    data class Request(
        val id: Long,
        val method: String,
        val data: JsonObject = JsonObject(emptyMap()),
    ) : ProtooMessage

    /**
     * The answer to one request, matched by [id].
     *
     * [ok] false means [errorCode]/[errorReason] carry the reason. The codes
     * are not all the server's: the client mints its own for the failures the
     * server never gets to answer — a timeout, a closed peer, a send that
     * never left.
     */
    data class Response(
        val id: Long,
        val ok: Boolean,
        val data: JsonObject = JsonObject(emptyMap()),
        val errorCode: Int = 0,
        val errorReason: String = "",
    ) : ProtooMessage

    /** Told, not asked. No id, no answer. */
    data class Notification(
        val method: String,
        val data: JsonObject = JsonObject(emptyMap()),
    ) : ProtooMessage
}

/**
 * Reads a frame, or null if it is not protoo at all.
 *
 * Deliberately lenient in one specific way. The flagged forms are what
 * protoo-client speaks and what this server usually sends, but the phones
 * carry a fallback for frames that arrive with no `request`/`response`/
 * `notification` flag, and they carry it because this deployment has emitted
 * them. Stock protoo-client drops such a frame on the floor and logs; the
 * phones infer the shape from which keys are present. A desktop that did not
 * would lose exactly the frames the phones handle, and only in production.
 */
fun parseProtoo(raw: String): ProtooMessage? {
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: run {
        ZillitLog.w(TAG) { "unparseable protoo frame (${raw.length} chars)" }
        return null
    }

    obj.flagged()?.let { return it }

    // No flag. Infer from what is present, exactly as the phones do.
    val id = obj.id()
    val method = obj.string("method")
    return when {
        id != null && method != null -> ProtooMessage.Request(id, method, obj.payload())
        // `ok` is usually absent here too, so presence of `data` stands in for
        // success — an error frame carries errorCode/errorReason instead.
        id != null -> ProtooMessage.Response(
            id = id,
            ok = obj.boolean("ok") ?: (obj["data"] != null),
            data = obj.payload(),
            errorCode = obj.int("errorCode") ?: 0,
            errorReason = obj.string("errorReason").orEmpty(),
        )
        method != null -> ProtooMessage.Notification(method, obj.payload())
        else -> {
            ZillitLog.w(TAG) { "protoo frame with neither id nor method" }
            null
        }
    }
}

private fun JsonObject.flagged(): ProtooMessage? = when {
    boolean("request") == true -> {
        val id = id()
        val method = string("method")
        if (id == null || method == null) null else ProtooMessage.Request(id, method, payload())
    }

    boolean("response") == true -> id()?.let { id ->
        ProtooMessage.Response(
            id = id,
            ok = boolean("ok") ?: false,
            data = payload(),
            errorCode = int("errorCode") ?: 0,
            errorReason = string("errorReason").orEmpty(),
        )
    }

    boolean("notification") == true -> string("method")?.let {
        ProtooMessage.Notification(it, payload())
    }

    else -> null
}

/** The frame this client sends to ask something. */
fun protooRequestFrame(id: Long, method: String, data: JsonObject): String = buildJsonObject {
    put("request", true)
    put("method", method)
    put("id", id)
    put("data", data)
}.toString()

/** Answers a server request. Accepting with no payload is `{}`, not null. */
fun protooAcceptFrame(id: Long, data: JsonObject = JsonObject(emptyMap())): String = buildJsonObject {
    put("response", true)
    put("id", id)
    put("ok", true)
    put("data", data)
}.toString()

/** Refuses a server request. */
fun protooRejectFrame(id: Long, code: Int, reason: String): String = buildJsonObject {
    put("response", true)
    put("id", id)
    put("ok", false)
    put("errorCode", code)
    put("errorReason", reason)
}.toString()

/** Tells the server something, expecting no answer. */
fun protooNotificationFrame(method: String, data: JsonObject): String = buildJsonObject {
    put("notification", true)
    put("method", method)
    put("data", data)
}.toString()

private fun JsonObject.payload(): JsonObject = this["data"] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/**
 * Ids arrive as numbers, but a JSON number is not guaranteed to be an Int and
 * some servers quote them. Both are accepted; anything else is not an id.
 */
private fun JsonObject.id(): Long? {
    val primitive = this["id"] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
}

private const val TAG = "Protoo"
