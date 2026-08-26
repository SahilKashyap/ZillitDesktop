package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The calling wire.
 *
 * Every reader here is deliberately forgiving, because this wire is not: the
 * ring arrives flat on the socket but wrapped in `{data:{…}}` from REST,
 * `agora_uid` is a number on some payloads and a string on others, the room id
 * appears as `room_id` and as `roomId` in the same family of events, and
 * `call_users` has been observed carrying bare strings alongside objects. A
 * strict reader does not fail loudly on any of that — it drops the call.
 */

/** Descends into `data`/`detail`/`call` wrappers, and unwraps a one-element array. */
internal fun unwrap(payload: JsonElement): JsonObject? {
    val first = (payload as? JsonArray)?.firstOrNull() ?: payload
    val obj = first as? JsonObject ?: return null
    val inner = (obj["data"] ?: obj["detail"]) as? JsonObject ?: obj
    // The REST family wraps once more: `{success, call:{…}}`. Checked on the
    // unwrapped object too, because ApiClient has already peeled `data` off
    // before this reader ever sees the payload.
    return (inner["call"] as? JsonObject) ?: inner
}

internal fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

internal fun JsonObject.bool(vararg keys: String): Boolean =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.booleanOrNull } ?: false

internal fun JsonObject.int(vararg keys: String): Int =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.intOrNull } ?: 0

internal fun JsonObject.long(vararg keys: String): Long =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.longOrNull } ?: 0L

/**
 * A uid as text, whatever the wire made it.
 *
 * `agora_uid` is documented as a string and delivered as a number often
 * enough that Android parses it by hand with a comment saying so. Reading the
 * primitive's content covers both without caring which arrived.
 */
private fun JsonObject.uid(vararg keys: String): String =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotBlank() && it != "null" }
        .orEmpty()

/**
 * The invite, as a session.
 *
 * [selfUserId] is *our* id in the call's production, which is not necessarily
 * the open one — a cross-project call is the case that breaks clients which
 * assume otherwise, because every self-comparison downstream then fails and
 * the user appears to be a stranger in their own call.
 */
/**
 * Whether the server elected a direct connection for this call.
 *
 * Nested under `p2p` rather than a top-level flag, and its absence means "no"
 * — the field only appears on backends that can elect P2P at all.
 */
private fun readP2pEligible(obj: JsonObject): Boolean {
    val envelope = obj["p2p"] as? JsonObject ?: return false
    return envelope.bool("eligible")
}

fun readCallSession(
    payload: JsonElement,
    selfUserId: String,
    selfDeviceId: String,
    direction: CallDirection = CallDirection.Incoming,
): CallSession? {
    val obj = unwrap(payload) ?: return null
    // Line 1's initiate RESPONSE does not always carry a `call_uuid` — it
    // names the call by its room instead, and dropping it loses the call
    // outright. An incoming ring is different: it always carries one, so a
    // ring without it is malformed and must still be refused rather than
    // adopted under a room id that may name something else entirely.
    val uuid = obj.str("call_uuid", "callUuid")
        ?: obj.str("room_id", "roomId")?.takeIf { direction == CallDirection.Outgoing }
        ?: return null
    val participants = readParticipants(obj["call_users"])
    val mine = participants.firstOrNull { it.deviceId == selfDeviceId && selfDeviceId.isNotBlank() }
    return CallSession(
        callUuid = uuid,
        roomId = obj.str("room_id", "roomId").orEmpty(),
        chatRoomId = obj.str("chat_room_id", "chatRoomId").orEmpty(),
        projectId = obj.str("project_id", "projectId").orEmpty(),
        direction = direction,
        provider = CallProvider.ofWire(obj.str("line")),
        mode = CallMode.ofWire(obj.str("call_mode", "callMode")),
        type = CallType.ofWire(obj.str("call_type", "callType")),
        hasVideo = obj.bool("has_video", "hasVideo"),
        channelName = obj.str("agora_channel_name", "agoraChannelName").orEmpty(),
        token = obj.str("agora_token", "agoraToken").orEmpty(),
        localUid = mine?.numericUid ?: 0,
        inviteCode = obj.str("invite_code", "inviteCode").orEmpty(),
        // Line 1. `mediasoup_server_url` is canonical; `sfu_url` is the older
        // alias the phones still accept.
        sfuHost = obj.str("mediasoup_server_url", "mediasoupServerUrl", "sfu_url", "sfuUrl").orEmpty(),
        sfuToken = obj.str("sfu_token", "sfuToken").orEmpty(),
        connectionType = obj.str("connection_type", "connectionType").orEmpty(),
        p2pEligible = readP2pEligible(obj),
        callerUserId = obj.str("sender_user_id", "senderUserId", "user_id").orEmpty(),
        callerDeviceId = obj.str("sender_device_id", "senderDeviceId", "caller_device_id").orEmpty(),
        callerName = obj.str("caller_name", "callerName", "name").orEmpty(),
        callerImage = obj.str("caller_image", "callerImage").orEmpty(),
        // `receiver_user_id` is us only on an INVITE. The same reader serves
        // the create-call response, where the field echoes the callee we just
        // dialled — taking it there makes every self-comparison downstream
        // name the other person, so an outgoing call pins our own id instead.
        selfUserId = when (direction) {
            CallDirection.Incoming -> obj.str("receiver_user_id", "receiverUserId") ?: selfUserId
            CallDirection.Outgoing -> selfUserId
        },
        selfDeviceId = selfDeviceId,
        title = obj.str("chat_room_name", "chatRoomName", "name").orEmpty(),
        participants = participants,
        // Two spellings for one flag: the stored document says
        // `is_random_call`, live payloads say `isRandom`. Missing either
        // misroutes a decline's quick reply into a hidden room.
        isRandomCall = obj.bool("is_random_call", "isRandom"),
        isCalendarCall = obj.bool("is_calendar_call", "isCalendarCall"),
        is247Call = obj.bool("is_247_call", "is247Call"),
        othersCount = obj.int("others_count", "othersCount"),
        startedAtMillis = obj.long("start_time", "startTime", "current_time"),
    )
}

/**
 * The roster.
 *
 * Non-object entries are skipped rather than rejected: the call-logs endpoint
 * is known to mix bare id strings into the same array, and one malformed row
 * must not cost the caller their whole participant list.
 */
fun readParticipants(element: JsonElement?): List<CallParticipant> =
    (element as? JsonArray).orEmpty().mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val userId = row.str("user_id", "userId") ?: return@mapNotNull null
        CallParticipant(
            userId = userId,
            deviceId = row.str("device_id", "deviceId").orEmpty(),
            agoraUid = row.uid("agora_uid", "agoraUid"),
            // Four spellings for one name: invites say `name`/`full_name`,
            // the call-dump rows `display_name`, and the Firestore-shaped
            // ones `user_name`. Reading only the first pair left dump-fed
            // rosters nameless, which the tiles rendered as "Guest".
            name = row.str("name", "full_name", "fullName", "display_name", "displayName", "user_name")
                .orEmpty(),
            image = row.str("image", "profile_image").orEmpty(),
            status = CallStatus.ofWire(row.str("current_status", "currentStatus", "status")),
            missedCall = row.bool("missed_call", "missedCall"),
        )
    }

/** One participant's status moved — `call:update`. */
data class CallStatusChange(
    val roomId: String,
    val userId: String,
    val status: CallStatus,
    val projectId: String = "",
)

fun readStatusChange(payload: JsonElement): CallStatusChange? {
    val obj = unwrap(payload) ?: return null
    val userId = obj.str("userId", "user_id") ?: return null
    // Both spellings appear on this event, and Android's model carries a field
    // for each because the server has sent either depending on the emitter.
    val room = obj.str("roomId", "room_id").orEmpty()
    return CallStatusChange(
        roomId = room,
        userId = userId,
        status = CallStatus.ofWire(obj.str("status", "current_status")),
        projectId = obj.str("projectId", "project_id").orEmpty(),
    )
}

/** The call is over — `call:ended`. */
data class CallEnded(
    val roomId: String,
    val chatRoomId: String = "",
    val projectId: String = "",
    val message: String = "",
)

fun readCallEnded(payload: JsonElement): CallEnded? {
    val obj = unwrap(payload) ?: return null
    val room = obj.str("room_id", "roomId", "call_uuid") ?: return null
    return CallEnded(
        roomId = room,
        chatRoomId = obj.str("chat_room_id", "chatRoomId").orEmpty(),
        projectId = obj.str("project_id", "projectId").orEmpty(),
        message = obj.str("message").orEmpty(),
    )
}

/** Another of this user's devices took the call — `call:handoff-evict`. */
data class HandoffEvict(val roomId: String, val activeDeviceId: String)

fun readHandoffEvict(payload: JsonElement): HandoffEvict? {
    val obj = unwrap(payload) ?: return null
    val room = obj.str("room_id", "roomId") ?: return null
    return HandoffEvict(room, obj.str("active_device_id", "activeDeviceId").orEmpty())
}

/** A guest is asking to be admitted — `call:guest:join:request`. */
data class GuestJoinRequest(
    val roomId: String,
    val requestId: String,
    val guestName: String,
    val projectId: String = "",
)

fun readGuestJoinRequest(payload: JsonElement): GuestJoinRequest? {
    val obj = unwrap(payload) ?: return null
    val requestId = obj.str("request_id", "requestId") ?: return null
    return GuestJoinRequest(
        roomId = obj.str("room_id", "roomId").orEmpty(),
        requestId = requestId,
        guestName = obj.str("guest_name", "guestName").orEmpty(),
        projectId = obj.str("project_id", "projectId").orEmpty(),
    )
}

/** An in-call reaction or ephemeral line — `call:incall-data`. */
/** An emoji thrown at the call. */
const val IN_CALL_KIND_REACTION = "reaction"

/** One ephemeral line of in-call chat. */
const val IN_CALL_KIND_MESSAGE = "message"

data class InCallData(
    val roomId: String,
    val kind: String,
    val fromUserId: String,
    val name: String = "",
    val emoji: String = "",
    val text: String = "",
    val id: String = "",
    val atMillis: Long = 0,
)

fun readInCallData(payload: JsonElement): InCallData? {
    val obj = unwrap(payload) ?: return null
    val kind = obj.str("kind") ?: return null
    return InCallData(
        roomId = obj.str("room_id", "roomId").orEmpty(),
        kind = kind,
        fromUserId = obj.str("from_user_id", "fromUserId").orEmpty(),
        name = obj.str("name").orEmpty(),
        emoji = obj.str("emoji").orEmpty(),
        text = obj.str("text").orEmpty(),
        id = obj.str("id").orEmpty(),
        atMillis = obj.long("ts"),
    )
}

/**
 * Our own status, sent back to the server.
 *
 * Field names are the cross-platform contract — web and iOS read the same
 * keys, so renaming one here silently drops this device out of their rosters.
 */
fun callResponseEnvelope(roomId: String, status: CallStatus, fromUserId: String): JsonObject =
    buildJsonObject {
        put("roomId", roomId)
        put("response", status.wire)
        put("fromUserId", fromUserId)
    }

/**
 * In-call reactions ride the CNC's generic relay rather than a call event.
 *
 * [recipients] are the OTHER participants' project-scoped user ids — the
 * relay routes by user room, so addressing the call room instead delivers to
 * nobody. Composite `userId:deviceId` peer ids are stripped to the plain user
 * id: that exact detail is why the phones' in-call chat once worked one to
 * one and vanished in group calls.
 *
 * An empty list means there is no one to tell; the caller shows its own line
 * locally and skips the emit.
 */
fun inCallDataEnvelope(roomId: String, data: InCallData, recipients: List<String>): JsonObject =
    buildJsonObject {
        put("event", "call:incall-data")
        put(
            "rooms",
            buildJsonArray { recipients.forEach { add(JsonPrimitive(it)) } },
        )
        put(
            "eventData",
            buildJsonObject {
                put("v", IN_CALL_DATA_VERSION)
                put("kind", data.kind)
                put("room_id", roomId)
                if (data.emoji.isNotEmpty()) put("emoji", data.emoji)
                if (data.text.isNotEmpty()) put("text", data.text)
                put("name", data.name)
                put("from_user_id", data.fromUserId)
                put("id", data.id)
                put("ts", data.atMillis)
            },
        )
    }

private const val IN_CALL_DATA_VERSION = 1
