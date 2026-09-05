package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Who this device is on the calling backend: the signed-in user, on the open production. */
data class LiveKitIdentity(
    val userId: String,
    val displayName: String,
    val projectId: String,
    val projectName: String,
)

/** Why a ring this device was showing is over, without the call having been answered here. */
enum class LiveKitDismissal { Cancelled, HandledElsewhere, Removed }

/** What the line tells the call state machine. */
interface LiveKitLineListener {
    /** This device is being rung. The session is Line 3's, with its room credentials on it. */
    fun onInvite(session: CallSession)

    /** A participant's ring moved — the coordinator's own status vocabulary. */
    fun onRingState(callId: String, userId: String, displayName: String, status: CallStatus, busy: Boolean)

    /**
     * The ring this device showed for [callId] is over without an answer here:
     * the caller cancelled, another device took it, or the host removed us.
     */
    fun onDismissed(callId: String, why: LiveKitDismissal)

    /** The call is over for everyone. */
    fun onEnded(reason: String)

    /** The server's roster for a call, when it was asked for. */
    fun onRoster(callId: String, participants: List<CallParticipant>)

    /**
     * The server's active-call list — every heartbeat's answer, and its own
     * broadcast. Server truth for a ring this device is showing: see
     * [LiveKitRingWatch].
     */
    fun onActiveCalls(calls: List<LiveKitActiveCall>)
}

/** The credentials a placed or accepted call joins with. */
data class LiveKitJoin(val callId: String, val url: String, val token: String)

/** Everything a call is, for the create and the ring. */
data class LiveKitDial(
    val calleeUserIds: List<String>,
    val chatRoomId: String?,
    val mode: CallMode,
    val type: CallType,
    /** The caller, on the production the call belongs to. */
    val callerUserId: String,
    val callerName: String,
    val projectId: String?,
    val projectName: String?,
) {
    /** A real chat-group call rings its room; the callees are the room's, not listed. */
    val ringIds: List<String>
        get() = if (mode == CallMode.Group && !chatRoomId.isNullOrBlank()) emptyList() else calleeUserIds
}

/**
 * Line 3 — the LiveKit calling backend, as one line among three.
 *
 * Ported from the phones' `livekit/` package and the web's `lineTwo/`
 * (their name for the same thing): a **presence socket** the device keeps
 * open while signed in, over which calls are started, answered and declined,
 * and a **REST API** that creates calls, mints room tokens, and stands in for
 * the socket when it is down. Media is LiveKit's, joined by URL and token.
 *
 * ## The rule for sending
 *
 * The phones' `CallActionSender` spells it out: if the socket is up, send the
 * event; if it is not — or the send fails — call the REST route. Never drop
 * the action. The dangerous miss is not an unsent ringing-ack, it is an
 * unsent hang-up, where the far side is never told and their call runs on
 * with nobody in it.
 *
 * ## What this does not own
 *
 * The call's state. That stays with the coordinator, which hears this line's
 * events through [LiveKitLineListener] in the same words Lines 1 and 2 use,
 * so ringing, accepting and hanging up are one state machine whichever line
 * carried them.
 */
@Suppress("TooManyFunctions") // One function per wire operation, plus the presence loop; see the interface.
class LiveKitLine(
    private val scope: CoroutineScope,
    private val api: LiveKitApi,
    private val sockets: LiveKitSocketFactory,
    /** `wss://<env>-calls.zillit.com/ws`, or null on an install without Line 3 configured. */
    private val socketUrl: () -> String?,
    /**
     * The encrypted `{primary_device_id, device_id}` blob the handshake
     * carries as `moduledata`, both as a query parameter and a header. Null
     * means nobody is signed in — no socket until someone is.
     */
    private val handshake: suspend () -> String?,
    private val identity: () -> LiveKitIdentity?,
    /**
     * A configured public room URL (`LIVEKIT_URL`). Wins over what the ring
     * carries, as the web's `VITE_LIVEKIT_URL` does, because the server may
     * name the node's internal address in the invite.
     */
    private val roomUrlOverride: () -> String?,
    private val nowMillis: () -> Long,
) {
    private var listener: LiveKitLineListener? = null
    private var peer: LiveKitPeer? = null
    private var socket: LiveKitSocket? = null
    private var presence: Job? = null
    private var heartbeat: Job? = null
    private var attempts = 0

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Whether an install has Line 3 at all — both halves configured. */
    val isConfigured: Boolean get() = !socketUrl().isNullOrBlank()

    /** The coordinator plugs itself in once; the line is built first because the coordinator needs it. */
    fun attach(listener: LiveKitLineListener) {
        this.listener = listener
    }

    /** Who this device is on the calling backend right now, or null signed out. */
    fun identityNow(): LiveKitIdentity? = identity()

    /** Pre-picks the media region for this device — fire and forget on every production open, as the phones do. */
    fun warmRegion() {
        val me = identity() ?: return
        scope.launch { api.warmRegion(me.projectId, me.userId) }
    }

    /**
     * Drops the socket without stopping the loop — sign-out. The loop idles
     * while [handshake] answers null and redials on the next sign-in.
     */
    fun disconnect(reason: String) {
        attempts = 0
        dropConnection(reason)
    }

    // ── Presence ────────────────────────────────────────────────────────────

    /** Keeps the socket up while signed in. Safe to call again — a second start is a no-op. */
    fun start() {
        if (presence?.isActive == true || !isConfigured) return
        presence = scope.launch { presenceLoop() }
    }

    /** The app quitting: the socket comes down and stays down. */
    fun stop() {
        presence?.cancel()
        presence = null
        dropConnection("stopped")
    }

    // A failed upgrade is one more retry, not the end of presence; only the
    // scope's own cancellation may leave the loop, so it is caught by type.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun presenceLoop() {
        while (scope.isActive) {
            val url = socketUrl() ?: return
            val blob = handshake()
            if (blob == null) {
                delay(SIGNED_OUT_POLL_MILLIS)
                continue
            }
            try {
                connectOnce(url, blob)
            } catch (thrown: Throwable) {
                if (thrown is CancellationException) throw thrown
                ZillitLog.w(TAG) { "presence connect failed: ${thrown.message}" }
            }
            _online.value = false
            val wait = backoff(attempts++)
            ZillitLog.i(TAG) { "presence reconnect in ${wait}ms" }
            delay(wait)
        }
    }

    /** One connection, from upgrade to close. Returns when it is gone. */
    private suspend fun connectOnce(url: String, blob: String) {
        val closed = CompletableDeferred<String>()
        val fresh = LiveKitPeer(scope, send = { frame ->
            if (socket?.send(frame) != true) error("presence socket is not connected")
        })
        val opened = sockets.connect(
            url = withModuleData(url, blob),
            headers = mapOf(HEADER_MODULE_DATA to blob),
            onFrame = { frame -> scope.launch { fresh.onFrame(frame) } },
            onClosed = { reason -> closed.complete(reason) },
        )
        socket = opened
        peer = fresh
        attempts = 0
        _online.value = true
        ZillitLog.i(TAG) { "presence online" }
        val events = scope.launch { fresh.events.collect { dispatch(it) } }
        // The first heartbeat doubles as the presence registration check —
        // on its own coroutine, because this one has to be free to notice
        // the close whether or not the server ever answers.
        heartbeat = scope.launch {
            refreshActiveCalls(fresh)
            heartbeatLoop(fresh, opened)
        }

        val reason = closed.await()
        ZillitLog.w(TAG) { "presence offline: $reason" }
        events.cancel()
        heartbeat?.cancel()
        heartbeat = null
        fresh.close(reason)
        if (peer === fresh) peer = null
        if (socket === opened) socket = null
        _online.value = false
    }

    private suspend fun heartbeatLoop(fresh: LiveKitPeer, opened: LiveKitSocket) {
        while (scope.isActive) {
            delay(HEARTBEAT_MILLIS)
            if (peer !== fresh) return
            // Two misses in a row: the socket is half-open. Close it and let the loop redial.
            if (!refreshActiveCalls(fresh) && !refreshActiveCalls(fresh)) opened.close("heartbeat missed")
        }
    }

    private suspend fun refreshActiveCalls(fresh: LiveKitPeer): Boolean {
        val answer = runCatching { fresh.request("listActiveCalls") }.getOrElse { return false }
        // The answer is the same list the server broadcasts on its own; hand it on as that event.
        if (peer === fresh) (answer as? JsonObject)?.let { dispatch(LiveKitEvent.ActiveCalls(readActiveCalls(it))) }
        return true
    }

    private fun dropConnection(reason: String) {
        heartbeat?.cancel()
        heartbeat = null
        socket?.close(reason)
        socket = null
        peer = null
        _online.value = false
    }

    // ── Events ──────────────────────────────────────────────────────────────

    private fun dispatch(event: LiveKitEvent) {
        val sink = listener ?: return
        when (event) {
            is LiveKitEvent.IncomingCall -> onIncoming(event.invite, sink)
            is LiveKitEvent.RingState ->
                sink.onRingState(event.callId, event.userId, "", event.status, event.busy)
            is LiveKitEvent.UserState ->
                sink.onRingState(event.callId, event.userId, event.displayName, event.status, busy = false)
            is LiveKitEvent.Cancelled -> dismissed(event.callId, LiveKitDismissal.Cancelled, sink)
            is LiveKitEvent.HandledElsewhere -> dismissed(event.callId, LiveKitDismissal.HandledElsewhere, sink)
            is LiveKitEvent.Removed -> dismissed(event.callId, LiveKitDismissal.Removed, sink)
            is LiveKitEvent.Ended -> sink.onEnded(event.reason)
            is LiveKitEvent.ActiveCalls -> sink.onActiveCalls(event.calls)
            is LiveKitEvent.Notice -> ZillitLog.i(TAG) { "notice: ${event.text}" }
            is LiveKitEvent.ParticipantJoined,
            is LiveKitEvent.ParticipantLeft,
            -> Unit
        }
    }

    private fun dismissed(callId: String, why: LiveKitDismissal, sink: LiveKitLineListener) {
        markResolved(callId)
        sink.onDismissed(callId, why)
    }

    /**
     * Rings this device has already answered, declined or seen dismissed. The
     * server re-emits `incomingCall` on reconnect, and a push and the socket
     * can both carry one; a ring the user already dealt with must not sound
     * again — the phones' `isCallResolved`.
     */
    private val resolved = ArrayDeque<String>()

    private fun markResolved(callId: String) {
        if (callId.isBlank() || callId in resolved) return
        resolved.addLast(callId)
        while (resolved.size > RESOLVED_REMEMBERED) resolved.removeFirst()
    }

    private fun onIncoming(invite: LiveKitInvite, sink: LiveKitLineListener) {
        if (invite.isExpired(nowMillis())) {
            ZillitLog.i(TAG) { "stale ring dropped ${invite.callId}" }
            return
        }
        if (invite.callId in resolved) {
            ZillitLog.i(TAG) { "ring ${invite.callId} was already dealt with here; not again" }
            return
        }
        val session = invite.toSession(
            selfUserId = invite.toUserId.ifBlank { identity()?.userId.orEmpty() },
            selfDeviceId = "",
        )
        sink.onInvite(session)
        // The popup is up: say so, so the caller's screen moves from "calling" to "ringing".
        scope.launch { ringingAck(invite.callId, invite.projectId, session.selfUserId) }
        // A ring can arrive late — after another device answered, or after the
        // caller gave up — with the event that said so already missed. Ask for
        // the server's list now rather than at the next heartbeat; the listener
        // judges the ring against it.
        scope.launch { peer?.let { refreshActiveCalls(it) } }
    }

    // ── Outgoing ────────────────────────────────────────────────────────────

    /**
     * Places a call: creates it over REST, rings it over the socket, and
     * resolves the room to join. The web's `startCall` sequence, including its
     * two recoveries — a second create when the first is already ringing, and
     * the REST ring when the socket cannot.
     */
    suspend fun place(dial: LiveKitDial): ZillitResult<LiveKitJoin> {
        // Socket down: one REST create that also rings, as the phones do — no
        // point minting an id nobody will `startCall` with.
        val rung = when (val live = peer) {
            null -> create(dial, ring = true)
            else -> mintThenRing(live, dial)
        }
        return when (rung) {
            is ZillitResult.Failure -> rung
            is ZillitResult.Success ->
                resolveJoin(rung.data.callId, rung.data.livekit, dial.callerUserId, dial.callerName, dial.projectId)
        }
    }

    private suspend fun mintThenRing(live: LiveKitPeer, dial: LiveKitDial): ZillitResult<LiveKitCallCredentials> {
        val created = when (val outcome = create(dial, ring = false)) {
            is ZillitResult.Failure -> return outcome
            is ZillitResult.Success -> outcome.data
        }
        created.switchToCallId?.let { existing ->
            ZillitLog.i(TAG) { "createCall -> already in $existing; joining that instead of dialling" }
        }
        return ringOverSocket(live, dial, created)
    }

    /**
     * `POST /v1/calls`. Ringing, it carries the whole call and the server rings
     * the callees itself (the socket-down fallback). Not ringing, it is the
     * bare mint — `group`, nobody named — that the socket's `startCall` then
     * describes; a mint that says `private` with no callees is refused.
     */
    private suspend fun create(dial: LiveKitDial, ring: Boolean): ZillitResult<LiveKitCallCredentials> {
        val outcome = if (ring) {
            api.createCall(
                dial.callerUserId, dial.callerName, dial.mode, dial.type,
                dial.ringIds, dial.chatRoomId, dial.projectId, dial.projectName,
            )
        } else {
            api.mintCall(dial.callerUserId, dial.callerName, dial.projectId)
        }
        if (outcome is ZillitResult.Failure) {
            ZillitLog.w(TAG) { "createCall(ring=$ring) refused: ${outcome.error.technical}" }
        }
        return outcome
    }

    /**
     * `startCall` over the socket, with the web's recoveries: a fresh call
     * when the server still holds a ring for this pair, the REST ring when
     * the socket fails for any reason but "you are already on a call".
     */
    private suspend fun ringOverSocket(
        live: LiveKitPeer,
        dial: LiveKitDial,
        created: LiveKitCallCredentials,
    ): ZillitResult<LiveKitCallCredentials> {
        val first = startCall(live, created.callId, dial)
        return when {
            first.isSuccess -> ZillitResult.Success(created.withRoomFrom(first.getOrNull()))
            first.isAlreadyRinging() -> ringAgain(live, dial)
            first.isCallerBusy() -> first.asFailure()
            else -> {
                ZillitLog.w(TAG) {
                    "startCall over the socket failed (${first.exceptionOrNull()?.message}); ringing over REST"
                }
                create(dial, ring = true)
            }
        }
    }

    /** The server still holds the last ring for this pair; a fresh call id clears it. */
    private suspend fun ringAgain(live: LiveKitPeer, dial: LiveKitDial): ZillitResult<LiveKitCallCredentials> {
        val again = when (val outcome = create(dial, ring = false)) {
            is ZillitResult.Failure -> return outcome
            is ZillitResult.Success -> outcome.data
        }
        val second = startCall(live, again.callId, dial)
        return if (second.isSuccess) {
            ZillitResult.Success(again.withRoomFrom(second.getOrNull()))
        } else {
            second.asFailure()
        }
    }

    private suspend fun startCall(live: LiveKitPeer, callId: String, dial: LiveKitDial): Result<JsonElement?> =
        runCatching {
            live.request(
                "startCall",
                buildJsonObject {
                    put("callId", JsonPrimitive(callId))
                    put("calleeIds", JsonArray(dial.ringIds.map(::JsonPrimitive)))
                    put("callType", JsonPrimitive(dial.type.wire))
                    put("callMode", JsonPrimitive(dial.mode.wire))
                    put("callerId", JsonPrimitive(dial.callerUserId))
                    put("callerName", JsonPrimitive(dial.callerName))
                    dial.projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
                    dial.projectName?.takeIf { it.isNotBlank() }?.let { put("projectName", JsonPrimitive(it)) }
                    dial.chatRoomId?.takeIf { it.isNotBlank() }?.let { put("chatRoomId", JsonPrimitive(it)) }
                },
            )
        }

    /** The caller's own way out while it still rings. */
    suspend fun cancel(callId: String, projectId: String?, userId: String) {
        overSocketOrRest(callId, "cancelCall") { api.hangup(callId, projectId, userId) }
    }

    // ── Incoming ────────────────────────────────────────────────────────────

    /**
     * Answers. Over the socket the accept IS the accept; the room credentials
     * come from the ring when they name a real host, and are minted otherwise.
     * With the socket down, the REST accept both answers and hands back the
     * credentials.
     */
    suspend fun accept(session: CallSession, displayName: String): ZillitResult<LiveKitJoin> {
        val callId = session.callUuid
        markResolved(callId)
        val me = session.selfUserId
        val projectId = session.projectId.takeIf { it.isNotBlank() }
        val bundled = LiveKitCredentials(session.livekitToken, session.livekitUrl).takeIf { it.isUsable }
        val live = peer
        if (live != null) {
            val accepted = runCatching { live.request("acceptCall", callIdFields(callId)) }
            if (accepted.isSuccess) return resolveJoin(callId, bundled, me, displayName, projectId)
            ZillitLog.w(TAG) { "acceptCall over the socket failed (${accepted.exceptionOrNull()?.message}); REST" }
        }
        return when (val rest = api.acceptCall(callId, me, displayName, projectId)) {
            is ZillitResult.Success -> resolveJoin(callId, rest.data.livekit ?: bundled, me, displayName, projectId)
            is ZillitResult.Failure -> rest
        }
    }

    suspend fun decline(session: CallSession) {
        markResolved(session.callUuid)
        overSocketOrRest(session.callUuid, "declineCall") {
            api.hangup(session.callUuid, session.projectId.takeIf { it.isNotBlank() }, session.selfUserId)
        }
    }

    /** Leaves a call this device is in. The server ends it when the last one leaves. */
    suspend fun leave(session: CallSession) {
        overSocketOrRest(session.callUuid, "leaveCall") {
            api.hangup(session.callUuid, session.projectId.takeIf { it.isNotBlank() }, session.selfUserId)
        }
    }

    private suspend fun ringingAck(callId: String, projectId: String, userId: String) {
        overSocketOrRest(callId, "ringingAck") { api.ackRinging(callId, projectId.takeIf { it.isNotBlank() }, userId) }
    }

    /** Pulls someone into a call already under way. */
    suspend fun addToCall(callId: String, userId: String): Boolean {
        val live = peer ?: return false
        return runCatching {
            live.request(
                "addToCall",
                buildJsonObject {
                    put("callId", JsonPrimitive(callId))
                    put("calleeIds", JsonArray(listOf(JsonPrimitive(userId))))
                },
            )
        }.isSuccess
    }

    /** Asks for the server's roster and hands it to the listener. */
    fun refreshRoster(callId: String, callerId: String) {
        val live = peer ?: return
        scope.launch {
            runCatching { live.request("getCallRoster", callIdFields(callId)) }
                .onSuccess { data -> listener?.onRoster(callId, readLiveKitRoster(data, callerId)) }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The phones' rule for the room URL: a configured public one wins; else
     * the bundled one when it names a real host; else mint, which returns the
     * region's public URL with a fresh token.
     */
    private suspend fun resolveJoin(
        callId: String,
        bundled: LiveKitCredentials?,
        userId: String,
        displayName: String,
        projectId: String?,
    ): ZillitResult<LiveKitJoin> {
        val override = roomUrlOverride()?.takeIf { it.isNotBlank() }
        if (bundled != null && bundled.token.isNotBlank()) {
            val url = override ?: bundled.url.takeIf { isRemoteRoomUrl(it) }
            if (url != null) return ZillitResult.Success(LiveKitJoin(callId, url, bundled.token))
        }
        return when (val minted = api.mintToken(callId, userId, displayName, projectId)) {
            is ZillitResult.Success -> ZillitResult.Success(
                LiveKitJoin(callId, override ?: minted.data.url, minted.data.token),
            )
            is ZillitResult.Failure -> minted
        }
    }

    private suspend fun overSocketOrRest(callId: String, type: String, rest: suspend () -> ZillitResult<Unit>) {
        val live = peer
        if (live != null && runCatching { live.request(type, callIdFields(callId)) }.isSuccess) return
        when (val outcome = rest()) {
            is ZillitResult.Success -> Unit
            is ZillitResult.Failure -> ZillitLog.w(TAG) { "$type not delivered: ${outcome.error.technical}" }
        }
    }

    private fun callIdFields(callId: String) = buildJsonObject { put("callId", JsonPrimitive(callId)) }

    /** The ack's room, when it folded one in; else what the create carried. */
    private fun LiveKitCallCredentials.withRoomFrom(ack: JsonElement?): LiveKitCallCredentials =
        copy(livekit = (ack as? JsonObject)?.readLiveKitCredentials() ?: livekit)

    private fun Result<*>.isAlreadyRinging() = exceptionOrNull()?.message?.contains("call_already_ringing") == true

    private fun Result<*>.isCallerBusy() = exceptionOrNull()?.message?.contains("caller_busy") == true

    private fun <T> Result<*>.asFailure(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Unknown(exceptionOrNull()?.message ?: "the call could not be started"))

    companion object {
        const val HEADER_MODULE_DATA = "moduledata"
        private const val HEARTBEAT_MILLIS = 15_000L
        private const val RESOLVED_REMEMBERED = 32
        private const val SIGNED_OUT_POLL_MILLIS = 5_000L
        private const val BACKOFF_BASE_MILLIS = 3_000L
        private const val BACKOFF_CAP_MILLIS = 30_000L
        private const val MAX_BACKOFF_SHIFT = 4
        private const val TAG = "LiveKitLine"

        fun backoff(attempt: Int): Long =
            (BACKOFF_BASE_MILLIS * (1L shl attempt.coerceIn(0, MAX_BACKOFF_SHIFT))).coerceAtMost(BACKOFF_CAP_MILLIS)

        /** `moduledata` rides both the query and a header — the phones send both, so the server accepts either. */
        fun withModuleData(url: String, blob: String): String =
            url + (if (url.contains('?')) "&" else "?") + "moduledata=" + percentEncode(blob)

        /** A URL the desktop can reach: absolute, and not the node's own loopback name. */
        fun isRemoteRoomUrl(url: String): Boolean {
            val host = Regex("^wss?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                ?: return false
            val loopback = Regex("^(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0)(:|$)", RegexOption.IGNORE_CASE)
            return !loopback.containsMatchIn(host)
        }

        private fun percentEncode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val ch = byte.toInt().toChar()
                if (ch.isLetterOrDigit() || ch in UNRESERVED) append(ch) else append(hex(byte))
            }
        }

        private fun hex(byte: Byte): String {
            val value = byte.toInt() and BYTE_MASK
            return "%" + HEX_DIGITS[value shr HEX_SHIFT] + HEX_DIGITS[value and HEX_MASK]
        }

        private const val UNRESERVED = "-_.~"
        private const val HEX_DIGITS = "0123456789ABCDEF"
        private const val BYTE_MASK = 0xFF
        private const val HEX_SHIFT = 4
        private const val HEX_MASK = 0xF
    }
}
