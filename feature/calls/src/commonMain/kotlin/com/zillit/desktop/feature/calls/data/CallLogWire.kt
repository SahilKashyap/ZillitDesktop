package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Call-history rows, read the way the rest of this feature reads the wire:
 * tolerantly, and never rejecting a whole page because one row is odd.
 *
 * The history endpoint has accumulated rows written by years of clients, so
 * every field here is optional and every direction is inferred rather than
 * trusted — `outgoingCall` and `incomingCall` are separate booleans that older
 * rows set neither of.
 */

/** Every readable row in a `GET call/{ts}/{param}` page. */
fun readCallLogs(payload: JsonElement, selfUserId: String): List<CallLogEntry> {
    val obj = payload as? JsonObject ?: return emptyList()
    // `{data:{calls:[…]}}` on the wire, already `data`-peeled by ApiClient on
    // some paths — accept the row array at either depth.
    val calls = (obj["calls"] as? JsonArray)
        ?: ((obj["data"] as? JsonObject)?.get("calls") as? JsonArray)
        ?: return emptyList()
    return calls.mapNotNull { row -> readCallLog(row, selfUserId) }
}

/** One row, or null when it carries no id to hang the rest on. */
fun readCallLog(row: JsonElement, selfUserId: String): CallLogEntry? {
    val obj = row as? JsonObject ?: return null
    val uuid = obj.text("call_uuid", "callUuid") ?: obj.text("_id", "id") ?: return null
    val from = obj.text("from_user_id", "fromUserId", "user_id").orEmpty()
    val to = obj.text("to_user_id", "toUserId").orEmpty()

    // Three sources, in order of how much they can be trusted. The booleans
    // are what the server means; the ids are what it can still be worked out
    // from when an older row set neither.
    val direction = when {
        obj.flag("outgoingCall", "outgoing_call") -> CallLogDirection.Outgoing
        obj.flag("incomingCall", "incoming_call") -> CallLogDirection.Incoming
        selfUserId.isNotBlank() && from == selfUserId -> CallLogDirection.Outgoing
        else -> CallLogDirection.Incoming
    }

    val mode = CallMode.ofWire(obj.text("call_mode", "callMode"))
    return CallLogEntry(
        callUuid = uuid,
        direction = direction,
        mode = mode,
        type = CallType.ofWire(obj.text("call_type", "callType")),
        missed = obj.flag("missedCall", "missed_call", "missed"),
        durationMillis = obj.number("call_duration", "callDuration"),
        startedAtMillis = obj.number("start_time", "startTime", "created"),
        // The other end of a 1:1 row is whichever id is not ours.
        peerUserId = if (direction == CallLogDirection.Outgoing) to else from,
        peerDeviceId = obj.text(
            if (direction == CallLogDirection.Outgoing) "receiver_device_id" else "caller_device_id",
            "receiverDeviceId",
            "callerDeviceId",
        ).orEmpty(),
        roomId = obj.text("chat_room_id", "chatRoomId").orEmpty(),
        title = obj.text("chat_room_name", "chatRoomName").orEmpty(),
        projectId = obj.text("project_id", "projectId").orEmpty(),
    )
}

private fun JsonObject.text(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotBlank() && it != "null" }

/** Booleans arrive as `true`, `"true"` and `1` depending on the row's vintage. */
private fun JsonObject.flag(vararg keys: String): Boolean = keys.any { key ->
    val value = this[key] as? JsonPrimitive ?: return@any false
    value.booleanOrNull == true || value.contentOrNull == "true" || value.contentOrNull == "1"
}

/** Numbers arrive as numbers or as strings; a missing one is zero, never null. */
private fun JsonObject.number(vararg keys: String): Long =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    } ?: 0L
