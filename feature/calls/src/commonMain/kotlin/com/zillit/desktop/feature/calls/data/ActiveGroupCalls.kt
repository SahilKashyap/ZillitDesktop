package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `mediasoup-call/active-group-calls` as sessions a member can walk into —
 * Line 2 (Agora) and Line 3 (mediasoup) group calls still running in rooms
 * this user belongs to, including rooms they were added to mid-call.
 *
 * Each row is the same shape as a ring, so it is read by the ring's own
 * reader, as an OUTGOING one: a mediasoup row may name its call by `room_id`
 * alone (the web joins on `call_uuid || room_id`), which an incoming read
 * refuses, and `receiver_user_id` on a row is whoever was first rung rather
 * than us. Then it is turned round into the incoming session it will be.
 *
 * A mediasoup row counts only while its own status is live, the web's
 * `useActiveGroupCalls` rule; Line 3 (LiveKit) calls never come from here.
 */
fun readActiveGroupCalls(data: JsonElement?, selfUserId: String, selfDeviceId: String): List<CallSession> {
    val rows = ((data as? JsonObject)?.get("calls") as? JsonArray).orEmpty()
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val provider = CallProvider.ofWire(obj.str("line"))
        if (provider == CallProvider.LiveKit) return@mapNotNull null
        if (provider == CallProvider.Mediasoup && obj.str("current_status") !in LIVE_MEDIASOUP) return@mapNotNull null
        val session = readCallSession(obj, selfUserId, selfDeviceId, CallDirection.Outgoing)
            ?: return@mapNotNull null
        if (session.chatRoomId.isBlank()) return@mapNotNull null
        session.copy(
            direction = CallDirection.Incoming,
            provider = provider,
            // Rows live in group rooms; one without the field is still a group's.
            mode = CallMode.Group,
            hasVideo = session.hasVideo || session.type == CallType.Video,
            // Someone else started it; the room's name is the caller line.
            callerName = session.title.ifBlank { session.callerName },
        )
    }
}

/** The web's `LIVE_STATUSES`: a mediasoup row in any other state is over. */
private val LIVE_MEDIASOUP = setOf("call_started", "ringing", "incall")

/**
 * The running Line 2/3 group calls, kept current — the web's
 * `useActiveGroupCalls`: one read up front, then a short debounced re-read
 * whenever the call socket says something moved (a status change, a call
 * ending, a ring). No polling; a screen that needs it fresh asks with
 * [refresh] (a group thread opening, for one).
 */
class ActiveGroupCalls(
    private val api: CallApi,
    private val bus: SocketEventBus,
    private val scope: CoroutineScope,
    private val selfUserId: () -> String?,
    private val selfDeviceId: () -> String?,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    private val _calls = MutableStateFlow<List<CallSession>>(emptyList())
    val calls: StateFlow<List<CallSession>> = _calls.asStateFlow()

    private var pending: Job? = null

    /** Monotonic, so a slow earlier answer cannot overwrite a newer one. */
    private var asked = 0

    fun start() {
        refresh(now = true)
        scope.launch {
            bus.onAny(TRIGGERS).collect { refresh() }
        }
    }

    /** Re-reads the list; several calls in a burst coalesce into one read. */
    fun refresh(now: Boolean = false) {
        pending?.cancel()
        pending = scope.launch {
            if (!now) delay(debounceMillis)
            read()
        }
    }

    private suspend fun read() {
        val ticket = ++asked
        // A failed read keeps the last-known list: a transient error must not
        // blank every Join button on screen.
        api.activeGroupCalls().onSuccess { data ->
            if (ticket != asked) return@onSuccess
            _calls.value = readActiveGroupCalls(data, selfUserId().orEmpty(), selfDeviceId().orEmpty())
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 350L

        /** What the web listens to for the same list (`custom:call:update`, the ends, a ring). */
        val TRIGGERS = listOf(
            ZillitSocketEvents.Calls.Update,
            ZillitSocketEvents.Calls.Response,
            ZillitSocketEvents.Calls.Ended,
            ZillitSocketEvents.Calls.GroupCallEnded,
            ZillitSocketEvents.Calls.Incoming,
        )
    }
}

/** True when this row's own `call_users` puts us in the call already, somewhere. */
internal fun CallSession.selfInCallElsewhere(selfUserId: String): Boolean =
    selfUserId.isNotBlank() && participants.any { it.userId == selfUserId && it.status.isInRoom }

/** In the room: answered (`in_call`/`incall`) or the one who placed it (`caller`). */
internal val CallStatus.isInRoom: Boolean
    get() = this == CallStatus.InCall || this == CallStatus.Caller
