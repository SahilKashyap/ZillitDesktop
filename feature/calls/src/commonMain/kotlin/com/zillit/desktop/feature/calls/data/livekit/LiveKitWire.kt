package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Line 3's wire — the LiveKit calling backend's presence socket and its REST
 * bodies, as the phones and the web speak them (`livekit/protocol/Wire.kt`,
 * `lineTwo/protocol/index.ts`).
 *
 * ## The socket's grammar
 *
 * One JSON object per frame. A client request is `{type, reqId, …fields}` and
 * its answer is `{reqId, ok, data}` or `{reqId, ok:false, error}`; anything
 * without a `reqId` is a server event, discriminated by `type`. That is the
 * whole rule, so the reader here is small and the events are read
 * one-by-one with the fields each actually carries.
 *
 * ## Why events map onto the existing [CallStatus]
 *
 * The coordinator's state machine already knows what "ringing", "in call",
 * "declined" and "not answered" mean for a participant. Line 3 reports the
 * same facts under its own names (`callRinging`, `callAccepted`, `callBusy`…),
 * so they are translated at the edge and the machine stays one machine.
 */

/** A frame off the presence socket. */
sealed interface LiveKitFrame {
    /** The answer to one of our requests. [data] is the server's payload, absent on failure. */
    data class Response(val reqId: String, val ok: Boolean, val data: JsonElement?, val error: String?) : LiveKitFrame

    data class Event(val event: LiveKitEvent) : LiveKitFrame

    /** A `type` this client does not read. Logged, never fatal. */
    data class Unknown(val type: String) : LiveKitFrame
}

/** The server events a call cares about. */
sealed interface LiveKitEvent {
    /** This device is being rung. */
    data class IncomingCall(val invite: LiveKitInvite) : LiveKitEvent

    /**
     * One callee's ring moved — `callRinging`, `callAccepted`, `callDeclined`,
     * `callBusy`, `callUnreachable`, `callMissed`.
     */
    data class RingState(
        val callId: String,
        val userId: String,
        val status: CallStatus,
        val busy: Boolean = false,
    ) : LiveKitEvent

    /** The caller hung up before anyone answered — dismiss the ring. */
    data class Cancelled(val callId: String) : LiveKitEvent

    /** Another of this user's devices answered or declined. */
    data class HandledElsewhere(val callId: String) : LiveKitEvent

    /** The call is over for everyone. */
    data class Ended(val reason: String) : LiveKitEvent

    /** The host removed this participant; [byName] is who, when the server said. */
    data class Removed(val callId: String, val byName: String = "") : LiveKitEvent

    /**
     * A participant's state in the roster moved (`callUserStateChanged`).
     * [onHold] and [isGuest] ride along as the web reads them; a null hold
     * means the event did not say.
     */
    data class UserState(
        val callId: String,
        val userId: String,
        val displayName: String,
        val status: CallStatus,
        val isGuest: Boolean = false,
        val image: String = "",
    ) : LiveKitEvent

    /** Someone threw an emoji — our own come back too, and float only then (`callReaction`). */
    data class Reaction(val callId: String, val userId: String, val emoji: String) : LiveKitEvent

    /** Someone put the call on hold, or took it off (`callHeld` / `callResumed`). */
    data class Held(val callId: String, val userId: String, val onHold: Boolean) : LiveKitEvent

    /** The host lowered exactly these hands (`handsLowered`); never anyone else's. */
    data class HandsLowered(val callId: String, val userIds: List<String>) : LiveKitEvent

    /** The host blocked or unblocked one participant's chat (`chatBlockChanged`). */
    data class ChatBlock(val callId: String, val userId: String, val blocked: Boolean) : LiveKitEvent

    /** The host changed the call's controls (`callPolicyChanged`). */
    data class PolicyChanged(val callId: String, val policy: LiveKitCallPolicy) : LiveKitEvent

    /** A one-shot cooperative host broadcast: `muteAll`, `clearBackgrounds`, `lowerHands`. */
    data class HostAction(val callId: String, val action: String) : LiveKitEvent

    /** A link guest is knocking (`guestKnocking`) — one chime, one toast. */
    data class GuestKnocking(val callId: String, val guestId: String, val name: String) : LiveKitEvent

    /** Everyone waiting at the door right now (`guestListChanged`). */
    data class GuestList(val callId: String, val guests: List<LiveKitGuest>) : LiveKitEvent

    data class ParticipantJoined(val userId: String, val displayName: String) : LiveKitEvent

    data class ParticipantLeft(val userId: String) : LiveKitEvent

    /** The server's list of calls this user is in or invited to — the heartbeat's answer, or its own broadcast. */
    data class ActiveCalls(val calls: List<LiveKitActiveCall>) : LiveKitEvent

    /** A server broadcast for the user to read; sticky ones stay until dismissed. */
    data class Notice(val text: String, val warning: Boolean = false, val sticky: Boolean = false) : LiveKitEvent
}

/** A link guest waiting to be admitted. */
data class LiveKitGuest(val guestId: String, val name: String)

/**
 * The host's call-level controls (`CallPolicy` in the web's protocol).
 *
 * Every restriction only bites while [on] is true, and the defaults are the
 * permissive ones, so a call with no policy behaves as one with none.
 * [reactionsAllowed] is read strictly — an older server omits it, and absent
 * must mean allowed or reactions vanish against a server that predates them.
 */
data class LiveKitCallPolicy(
    val on: Boolean = false,
    val chatEnabled: Boolean = true,
    val bgEffectsAllowed: Boolean = true,
    val screenShareLocked: Boolean = false,
    val linkJoinEnabled: Boolean = true,
    val handRaiseAllowed: Boolean = true,
    val recordingAllowed: Boolean = true,
    val reactionsAllowed: Boolean = true,
) {
    /** The restrictions that apply to someone who is not the host. */
    val chatOff: Boolean get() = on && !chatEnabled
    val shareLocked: Boolean get() = on && screenShareLocked
    val linkOff: Boolean get() = on && !linkJoinEnabled
    val handsOff: Boolean get() = on && !handRaiseAllowed
    val recordingOff: Boolean get() = on && !recordingAllowed
    val reactionsOff: Boolean get() = on && !reactionsAllowed
    val backgroundsOff: Boolean get() = on && !bgEffectsAllowed

    /** The wire's keys, for a `setCallPolicy` patch. */
    fun toPatch(): Map<String, Boolean> = mapOf(
        "on" to on,
        "chatEnabled" to chatEnabled,
        "bgEffectsAllowed" to bgEffectsAllowed,
        "screenShareLocked" to screenShareLocked,
        "linkJoinEnabled" to linkJoinEnabled,
        "handRaiseAllowed" to handRaiseAllowed,
        "recordingAllowed" to recordingAllowed,
        "reactionsAllowed" to reactionsAllowed,
    )
}

/** `{on, chatEnabled, …}` — absent flags keep their permissive default. */
fun readLiveKitPolicy(obj: JsonObject?): LiveKitCallPolicy {
    val base = LiveKitCallPolicy()
    obj ?: return base
    return LiveKitCallPolicy(
        on = obj.bool("on") ?: base.on,
        chatEnabled = obj.bool("chatEnabled") ?: base.chatEnabled,
        bgEffectsAllowed = obj.bool("bgEffectsAllowed") ?: base.bgEffectsAllowed,
        screenShareLocked = obj.bool("screenShareLocked") ?: base.screenShareLocked,
        linkJoinEnabled = obj.bool("linkJoinEnabled") ?: base.linkJoinEnabled,
        handRaiseAllowed = obj.bool("handRaiseAllowed") ?: base.handRaiseAllowed,
        recordingAllowed = obj.bool("recordingAllowed") ?: base.recordingAllowed,
        reactionsAllowed = obj.bool("reactionsAllowed") ?: base.reactionsAllowed,
    )
}

/**
 * One call on the server's active list (`ActiveCallInfo`): what it is, who
 * started it, and who is actually in it as opposed to invited. The Calls tab
 * draws Join / Switch here / Return from this and nothing else.
 */
data class LiveKitActiveCall(
    val callId: String,
    val inCallUserIds: List<String>,
    val callType: CallType = CallType.Audio,
    val callMode: CallMode = CallMode.Private,
    val chatRoomId: String = "",
    val chatRoomName: String = "",
    val projectId: String = "",
    val callerId: String = "",
    val callerName: String = "",
    /** Everyone involved — rung, joined or left — so a member of the call can find it. */
    val userIds: List<String> = emptyList(),
    /** Who is in the room, named, for the join-confirm sheet. */
    val inCallUsers: List<Pair<String, String>> = emptyList(),
) {
    val inCallCount: Int get() = inCallUsers.size.takeIf { it > 0 } ?: inCallUserIds.size

    /** The room's name, else the caller's — what the Calls tab titles it. */
    val title: String get() = chatRoomName.ifBlank { callerName }.ifBlank { "Ongoing call" }
}

/**
 * `{calls: [{callId, inCallUsers: [{userId}]}]}` — the `listActiveCalls`
 * answer and the `activeCallsChanged` event alike.
 */
fun readActiveCalls(obj: JsonObject): List<LiveKitActiveCall> =
    (obj["calls"] as? JsonArray).orEmpty().mapNotNull { call ->
        val row = call as? JsonObject ?: return@mapNotNull null
        val callId = row.text("callId") ?: return@mapNotNull null
        val inCall = (row["inCallUsers"] as? JsonArray).orEmpty().mapNotNull { user ->
            val obj = user as? JsonObject ?: return@mapNotNull null
            val id = obj.text("userId") ?: return@mapNotNull null
            id to obj.text("displayName").orEmpty()
        }
        LiveKitActiveCall(
            callId = callId,
            inCallUserIds = inCall.map { it.first },
            callType = CallType.ofWire(row.text("callType")),
            callMode = CallMode.ofWire(row.text("callMode")),
            chatRoomId = row.text("chatRoomId").orEmpty(),
            chatRoomName = row.text("chatRoomName").orEmpty(),
            projectId = row.text("projectId").orEmpty(),
            callerId = row.text("callerId").orEmpty(),
            callerName = row.text("callerName").orEmpty(),
            userIds = (row["userIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            inCallUsers = inCall,
        )
    }

/** An `incomingCall` event: who is calling, on which production, and the room to join. */
data class LiveKitInvite(
    val callId: String,
    val callType: CallType,
    val callMode: CallMode,
    val fromUserId: String,
    val fromName: String,
    val fromImage: String = "",
    /** The callee this ring is for — the user's id ON [projectId]; echoed back on accept. */
    val toUserId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val chatRoomId: String = "",
    val chatRoomName: String = "",
    val inCallUsers: List<Pair<String, String>> = emptyList(),
    /** How the call started; a "private" one that grew keeps the private look. */
    val startedAs: CallMode? = null,
    val addedByName: String = "",
    val expiresAtMillis: Long? = null,
    val token: String = "",
    val url: String = "",
    val preconnectToken: String = "",
) {
    /** Past its ring window, by the server's clock — a stale push on a cold start. */
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis > expiresAtMillis

    /** The invite as the coordinator holds a call. */
    fun toSession(selfUserId: String, selfDeviceId: String): CallSession {
        val me = toUserId.ifBlank { selfUserId }
        val callerRow = CallParticipant(
            userId = fromUserId, name = fromName, image = fromImage, status = CallStatus.Caller,
        )
        val others = inCallUsers
            .filter { (id, _) -> id != fromUserId && id != me }
            .map { (id, name) -> CallParticipant(userId = id, name = name, status = CallStatus.InCall) }
        return CallSession(
            callUuid = callId,
            roomId = callId,
            chatRoomId = chatRoomId,
            projectId = projectId,
            direction = CallDirection.Incoming,
            provider = CallProvider.LiveKit,
            mode = callMode,
            type = callType,
            hasVideo = callType == CallType.Video,
            callerUserId = fromUserId,
            callerName = fromName,
            callerImage = fromImage,
            selfUserId = me,
            selfDeviceId = selfDeviceId,
            receiverUserId = me,
            // A group call is titled by its room; a 1:1 by whoever is calling.
            title = if (callMode == CallMode.Group) chatRoomName.ifBlank { fromName } else fromName,
            participants = listOf(callerRow) + others,
            livekitUrl = url,
            livekitToken = token,
            livekitPreconnectToken = preconnectToken,
        )
    }
}

/** Reads one frame; null when it is not a JSON object at all. */
fun parseLiveKitFrame(text: String): LiveKitFrame? {
    val obj = runCatching { LIVEKIT_JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    obj.text("reqId")?.let { reqId ->
        return LiveKitFrame.Response(
            reqId = reqId,
            ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
            data = obj["data"],
            error = obj.text("error"),
        )
    }
    val type = obj.text("type") ?: return LiveKitFrame.Unknown("")
    return readEvent(type, obj)?.let(LiveKitFrame::Event) ?: LiveKitFrame.Unknown(type)
}

/** `{type, reqId, …fields}` — the request the server answers by [reqId]. */
fun liveKitRequestFrame(type: String, reqId: String, fields: JsonObject): String =
    buildJsonObject {
        put("type", JsonPrimitive(type))
        put("reqId", JsonPrimitive(reqId))
        fields.forEach { (key, value) -> put(key, value) }
    }.toString()

// One branch per event type; the whole vocabulary in one place is the point.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun readEvent(type: String, obj: JsonObject): LiveKitEvent? = when (type) {
    "incomingCall" -> readInvite(obj)?.let(LiveKitEvent::IncomingCall)
    "callRinging" -> ringState(obj, CallStatus.Ringing)
    "callAccepted" -> ringState(obj, CallStatus.InCall)
    "callDeclined" -> ringState(obj, CallStatus.Declined)
    "callBusy" -> ringState(obj, CallStatus.Declined, busy = true)
    "callUnreachable", "callMissed" -> ringState(obj, CallStatus.NotAnswered)
    "callCancelled" -> obj.text("callId")?.let(LiveKitEvent::Cancelled)
    "callHandledElsewhere" -> obj.text("callId")?.let(LiveKitEvent::HandledElsewhere)
    "callEnded" -> LiveKitEvent.Ended(obj.text("reason").orEmpty())
    "removedFromCall" -> obj.text("callId")?.let {
        LiveKitEvent.Removed(it, (obj["by"] as? JsonObject)?.text("displayName").orEmpty())
    }
    "callUserStateChanged" -> LiveKitEvent.UserState(
        callId = obj.text("callId").orEmpty(),
        userId = obj.text("userId") ?: return null,
        displayName = obj.text("displayName").orEmpty(),
        status = userStateStatus(obj.text("state")) ?: return null,
        isGuest = obj.bool("isGuest") ?: obj.text("userId").orEmpty().startsWith(GUEST_PREFIX),
        image = obj.picture().orEmpty(),
    )
    "callReaction" -> LiveKitEvent.Reaction(
        callId = obj.text("callId") ?: return null,
        userId = obj.text("userId") ?: return null,
        emoji = obj.text("emoji") ?: return null,
    )
    "callHeld", "callResumed" -> LiveKitEvent.Held(
        callId = obj.text("callId") ?: return null,
        userId = obj.text("userId") ?: return null,
        onHold = type == "callHeld",
    )
    "handsLowered" -> LiveKitEvent.HandsLowered(
        callId = obj.text("callId") ?: return null,
        userIds = (obj["userIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )
    "chatBlockChanged" -> LiveKitEvent.ChatBlock(
        callId = obj.text("callId") ?: return null,
        userId = obj.text("userId") ?: return null,
        blocked = obj.bool("blocked") ?: false,
    )
    "callPolicyChanged" -> LiveKitEvent.PolicyChanged(
        callId = obj.text("callId") ?: return null,
        policy = readLiveKitPolicy(obj["policy"] as? JsonObject),
    )
    "hostAction" -> LiveKitEvent.HostAction(
        callId = obj.text("callId") ?: return null,
        action = obj.text("action") ?: return null,
    )
    "guestKnocking" -> LiveKitEvent.GuestKnocking(
        callId = obj.text("callId") ?: return null,
        guestId = obj.text("guestId") ?: return null,
        name = obj.text("name").orEmpty(),
    )
    "guestListChanged" -> LiveKitEvent.GuestList(
        callId = obj.text("callId") ?: return null,
        guests = (obj["guests"] as? JsonArray).orEmpty().mapNotNull { row ->
            val guest = row as? JsonObject ?: return@mapNotNull null
            LiveKitGuest(guest.text("guestId") ?: return@mapNotNull null, guest.text("name").orEmpty())
        },
    )
    "participantJoined" -> (obj["participant"] as? JsonObject)?.let { p ->
        LiveKitEvent.ParticipantJoined(p.text("userId") ?: return null, p.text("displayName").orEmpty())
    }
    "participantLeft" -> obj.text("userId")?.let(LiveKitEvent::ParticipantLeft)
    "activeCallsChanged" -> LiveKitEvent.ActiveCalls(readActiveCalls(obj))
    "notice" -> obj.text("text")?.let {
        LiveKitEvent.Notice(it, warning = obj.text("level") == "warning", sticky = obj.bool("sticky") ?: false)
    }
    else -> null
}

private fun ringState(obj: JsonObject, status: CallStatus, busy: Boolean = false): LiveKitEvent? {
    val callId = obj.text("callId") ?: return null
    return LiveKitEvent.RingState(callId, obj.text("userId").orEmpty(), status, busy)
}

/**
 * The roster's own state words, onto the coordinator's statuses.
 *
 * `available` is a person not on the call at all, which for a roster row is
 * the same as having left; `busy` and `unreachable` are a ring that will not
 * be answered, which is what Declined and NotAnswered already mean.
 */
fun userStateStatus(wire: String?): CallStatus? = when (wire?.trim()?.lowercase()) {
    // `calling` is the callee being rung before their device acknowledged;
    // to everyone watching, that is ringing.
    "calling", "ringing" -> CallStatus.Ringing
    "in_call", "accepted" -> CallStatus.InCall
    "declined", "busy" -> CallStatus.Declined
    "missed", "unreachable" -> CallStatus.NotAnswered
    "left" -> CallStatus.Left
    // `available` is someone NOT on the call — the roster's "could be added"
    // row, broadcast for the whole production the moment a call starts. It
    // is not a departure: read as Left it ended every 1:1 call within a
    // second of dialling (seen on develop, 2026-09-08). Null drops it, as it
    // drops any word this build does not know.
    else -> null
}

/** The `incomingCall` payload, which the phones parse with the same tolerance for absent fields. */
fun readInvite(obj: JsonObject): LiveKitInvite? {
    val callId = obj.text("callId") ?: return null
    val from = obj["from"] as? JsonObject
    val livekit = obj["livekit"] as? JsonObject
    val started = obj.text("initCallStartMode")
    return LiveKitInvite(
        callId = callId,
        callType = CallType.ofWire(obj.text("callType")),
        callMode = CallMode.ofWire(obj.text("callMode")),
        fromUserId = from?.text("userId").orEmpty(),
        fromName = from?.text("displayName").orEmpty(),
        fromImage = from?.picture().orEmpty(),
        toUserId = obj.text("toUserId").orEmpty(),
        projectId = obj.text("projectId").orEmpty(),
        projectName = obj.text("projectName").orEmpty(),
        chatRoomId = obj.text("chatRoomId").orEmpty(),
        chatRoomName = obj.text("chatRoomName").orEmpty(),
        inCallUsers = (obj["inCallUsers"] as? JsonArray).orEmpty().mapNotNull { row ->
            val user = row as? JsonObject ?: return@mapNotNull null
            val id = user.text("userId") ?: return@mapNotNull null
            id to user.text("displayName").orEmpty()
        },
        startedAs = started?.let { CallMode.ofWire(it) },
        addedByName = (obj["addedBy"] as? JsonObject)?.text("displayName").orEmpty(),
        expiresAtMillis = (obj["expiresAt"] as? JsonPrimitive)?.longOrNull,
        token = livekit?.text("token").orEmpty(),
        url = livekit?.text("url").orEmpty(),
        preconnectToken = livekit?.text("preconnectToken").orEmpty(),
    )
}

/** `{token, url}` — a minted LiveKit credential, from the ack or the token route. */
data class LiveKitCredentials(val token: String, val url: String) {
    val isUsable: Boolean get() = token.isNotBlank() && url.isNotBlank()
}

fun JsonObject.readLiveKitCredentials(): LiveKitCredentials? =
    (this["livekit"] as? JsonObject ?: this).let { creds ->
        val token = creds.text("token") ?: return null
        LiveKitCredentials(token, creds.text("url").orEmpty())
    }

/**
 * `GET /v1/calls/{id}` style roster — `getCallRoster`'s `states`, as
 * participants the coordinator can hold. The caller's own row is stamped
 * Caller so the tiles know whose call it is.
 */
fun readLiveKitRoster(data: JsonElement?, callerId: String): List<CallParticipant> {
    val root = data as? JsonObject ?: return emptyList()
    val states = root["states"] as? JsonArray ?: return emptyList()
    return states.mapNotNull { row ->
        val state = row as? JsonObject ?: return@mapNotNull null
        val userId = state.text("userId") ?: return@mapNotNull null
        val status = userStateStatus(state.text("state")) ?: return@mapNotNull null
        CallParticipant(
            userId = userId,
            name = state.text("displayName").orEmpty(),
            image = state.picture().orEmpty(),
            status = if (userId == callerId && status == CallStatus.InCall) CallStatus.Caller else status,
            // Omitted when not on hold — read absent as false, never unknown.
            onHold = state.bool("onHold") ?: false,
            isGuest = state.bool("isGuest") ?: userId.startsWith(GUEST_PREFIX),
            // Spellings vary by backend; the web reads all three. A label key
            // (`gaffer_label`) on every one of them, so it goes through the dictionary.
            designation = (state.text("designationName") ?: state.text("designation_name")
                ?: state.text("designation").orEmpty()).localised(),
        )
    }
}

/** Link guests are minted as `guest_<id>`; the tiles chip them off the prefix even when the roster forgets to. */
const val GUEST_PREFIX = "guest_"

/**
 * A profile picture is either a string or an S3 object `{media, bucket, region}`;
 * the phones flatten the latter to `media|bucket|region`, and so does this.
 */
private fun JsonObject.picture(): String? = when (val pic = this["profilePic"]) {
    is JsonPrimitive -> pic.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> pic.text("media")?.let { media ->
        listOf(media, pic.text("bucket").orEmpty(), pic.text("region").orEmpty()).joinToString("|")
    }
    else -> null
}

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** A JSON boolean, or the string spelling of one — some rows stringify their flags. */
internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

internal val LIVEKIT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
