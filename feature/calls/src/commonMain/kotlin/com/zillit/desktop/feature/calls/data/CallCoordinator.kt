package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.onFailure
import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.data.livekit.Line3CallState
import com.zillit.desktop.feature.calls.data.livekit.Line3InCall
import com.zillit.desktop.feature.calls.data.livekit.LiveKitActiveCall
import com.zillit.desktop.feature.calls.data.livekit.LiveKitCallPolicy
import com.zillit.desktop.feature.calls.data.livekit.LiveKitDial
import com.zillit.desktop.feature.calls.data.livekit.LiveKitDismissal
import com.zillit.desktop.feature.calls.data.livekit.LiveKitGuest
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLine
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLineListener
import com.zillit.desktop.feature.calls.data.livekit.LiveKitRoster
import com.zillit.desktop.feature.calls.data.livekit.LiveKitRingWatch
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallCrewEntry
import com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf
import com.zillit.desktop.feature.calls.data.protoo.toJoin
import com.zillit.desktop.feature.calls.domain.CallChatTarget
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallDevices
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.EngineConnection
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.reduce
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallRecording
import com.zillit.desktop.feature.calls.domain.CallRecordingShare
import com.zillit.desktop.feature.calls.domain.CallRingState
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallTimeouts
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Why a call stopped, for the UI's parting message.
 */
enum class CallEndReason { Hungup, RemoteEnded, Declined, Busy, Timeout, PickedElsewhere, Error }

/**
 * Whether everybody who was ever really on this call has since left it.
 *
 * [everConnected] is deliberately monotonic — the people who at some point
 * genuinely answered, never pruned. Asking "is anyone connected right now"
 * instead would be true of a call that has not been answered yet and of one
 * riding out a reconnection, and ending either of those is worse than the bug
 * this closes.
 *
 * Empty means nobody ever answered, which is a ring rather than an ending, so
 * it is never a reason to hang up. [livePeerCount] is the media side's own
 * count and has to agree: a roster row can lag, and a peer still sending
 * audio is still on the call whatever their row says.
 */
internal fun allOthersGone(
    participants: List<CallParticipant>,
    everConnected: Set<String>,
    livePeerCount: Int,
): Boolean {
    if (everConnected.isEmpty() || livePeerCount > 0) return false
    val stillHere = participants
        .filter { it.status.isConnected }
        .flatMap { listOf(it.userId, it.deviceId) }
        .filter(String::isNotBlank)
        .toSet()
    return everConnected.none { it in stillHere }
}

/**
 * Whether everybody else has walked out, judged from the media.
 *
 * [allOthersGone] needs someone to have read `in_call` on the roster, and on a
 * call this device RECEIVED the other person's row says `caller` — a status
 * no update need ever move. With nobody "ever connected", their leaving ended
 * nothing: this side sat in an empty room (Line 2, 2026-10-04). Their media is
 * the other witness: once it has been seen on this call ([sawRemoteMedia]) and
 * none is left, and nobody else's row reads `in_call`, the call is over.
 *
 * Group calls too, as Android's `UserOffline` rule ends them: a Line 1 group
 * whose roster never reached `in_call` — or read a departure as something
 * else — otherwise kept the last person in an empty room for good
 * (2026-10-05). An invitee still ringing does not hold the room open, the
 * same as in [allOthersGone].
 *
 * Only after media was seen, so the moment between answering and the first
 * stream arriving — no peers yet — never reads as an ending.
 */
internal fun callDeserted(
    sawRemoteMedia: Boolean,
    livePeerCount: Int,
    participants: List<CallParticipant>,
    selfUserId: String,
): Boolean =
    sawRemoteMedia &&
        livePeerCount == 0 &&
        participants.none { it.userId != selfUserId && it.status.isConnected }

/** One entry for the "call ended" toast: what happened and to whom. */
data class CallEndEvent(val session: CallSession, val reason: CallEndReason)

/**
 * The call state machine.
 *
 * One instance for the whole app, because a device is in at most one call:
 * every screen asks this object what is happening rather than keeping its own
 * copy. Android splits the same responsibility between a ViewModel, three
 * singletons and a foreground service, and its hardest bugs are those parts
 * disagreeing — two booleans describing different calls, a ring that outlives
 * its call, a status posted for the wrong production.
 *
 * The socket work runs on [scope], which the host ties to the signed-in
 * session: sign-out cancels it and the machine goes quiet.
 */
/*
 * LargeClass: the size IS the design — one phase machine owning every call
 * fact, which is the whole argument of the class comment above. What shares
 * nothing with the phase machine is already outside it (InCallDataChannel,
 * CallAudioDevices, CallRecordingControl, ReconnectWatchdog, CallRoster's
 * reducers); what remains reads or moves the phase, and splitting that is how
 * Android got two booleans describing different calls.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class CallCoordinator(
    private val api: CallApi,
    private val bus: SocketEventBus,
    private val engine: CallEngine,
    private val scope: CoroutineScope,
    private val selfUserId: () -> String?,
    private val selfDeviceId: () -> String?,
    /** The Firestore mirror; [NoopCallStatusPlane] when unconfigured. */
    private val plane: CallStatusPlane = NoopCallStatusPlane(),
    /** Our display name, stamped on our Firestore row to self-heal docs. */
    private val selfName: () -> String? = { null },
    /**
     * Where a finished recording is posted. Null keeps it a local file only,
     * which is what a host without chat wiring can honestly offer.
     */
    private val share: CallRecordingShare? = null,
    /**
     * The audio devices this machine chose last time, and where to put a new
     * choice. A headset picked during one call is still the headset the user
     * expects for the next one, so the selection outlives the call — the
     * engine only ever holds it for as long as its page is alive.
     */
    private val loadAudioDevices: suspend () -> Pair<String, String> = { "" to "" },
    private val saveAudioDevices: suspend (microphoneId: String, speakerId: String) -> Unit =
        { _, _ -> },
    /** Wall clock, stamped onto reactions and lines. Injected so tests can hold it still. */
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /**
     * Line 3, when the install has it. The machine here stays the one machine
     * for all three lines; this only carries a LiveKit call's ring, accept and
     * hang-up on their own wire and reports back in the statuses above.
     */
    private val line3: LiveKitLine? = null,
    /** The web deployment's origin, for Line 3's invite link. Null when unknown. */
    private val webOrigin: () -> String? = { null },
) {

    private val _phase = MutableStateFlow(CallPhase.Idle)
    val phase: StateFlow<CallPhase> = _phase.asStateFlow()

    private val _session = MutableStateFlow<CallSession?>(null)
    val session: StateFlow<CallSession?> = _session.asStateFlow()

    /**
     * Reactions and ephemeral chat. A collaborator rather than more methods
     * here: it shares nothing with the phase machine but the live session.
     */
    private val inCall = InCallDataChannel(
        bus = bus,
        scope = scope,
        session = { _session.value },
        connected = { _phase.value == CallPhase.InCall },
        selfName = selfName,
        selfDeviceId = selfDeviceId,
        now = now,
        // Line 3's chat rides the room's data channel, as the web's does; a
        // socket relay would reach no phone on that line.
        direct = { data ->
            _session.value?.provider == CallProvider.LiveKit &&
                data.kind == IN_CALL_KIND_MESSAGE &&
                engine.sendChat(data.id, data.text, data.atMillis)
        },
        alsoToRoom = { data ->
            if (_session.value?.provider == CallProvider.Mediasoup) {
                engine.sendRoomData(inCallEventData(data.roomId, data).toString())
            }
        },
    )

    /** Reactions and lines, inbound and our own echoed back. Never persisted. */
    val inCallData: SharedFlow<InCallData> get() = inCall.data

    /** Things that went wrong without ending the call. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Tile keys whose pin was pressed on the video page, for the view model to toggle. */
    private val _pinRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pinRequests: SharedFlow<String> = _pinRequests.asSharedFlow()

    /**
     * Line 3's in-call extras — the host's policy, who we muted for
     * ourselves, the guests at the door — and the verbs that move them. A
     * collaborator for the reason [inCall] is: none of it touches the phase.
     */
    private val line3InCall = Line3InCall(
        line = line3,
        engine = engine,
        scope = scope,
        session = { _session.value },
        selfName = selfName,
        notice = { _notices.tryEmit(it) },
        webOrigin = webOrigin,
    )

    /** Policy, local mutes, chat blocks and pending guests on a Line 3 call. */
    val line3State: StateFlow<Line3CallState> get() = line3InCall.state

    /**
     * Who else the SERVER says could be rung into this call — the roster's
     * `available` rows, and on Line 3 the only list the users panel offers.
     *
     * The open production's crew is not it: the call may belong to another
     * production, the server seeds this list from the call's own membership,
     * and it knows people this client never fetched. Empty until a roster
     * answer lands, and cleared with the call.
     */
    private val _addableFromRoster = MutableStateFlow<List<CallCrewEntry>>(emptyList())
    val addableFromRoster: StateFlow<List<CallCrewEntry>> = _addableFromRoster.asStateFlow()

    /**
     * Where each callee's ring has got to, by user id — the web's
     * `ringStatuses`, which is what makes the outgoing screen read
     * "Ringing…" rather than sitting on "Calling…" for the whole ring.
     *
     * Separate from the roster's [CallStatus] on purpose: a row is Ringing
     * from the moment we dial, so the roster cannot tell "we have asked the
     * server" from "their device is showing a popup", and those are the two
     * states the caller most wants told apart.
     */
    private val _ringStatuses = MutableStateFlow<Map<String, CallRingState>>(emptyMap())
    val ringStatuses: StateFlow<Map<String, CallRingState>> = _ringStatuses.asStateFlow()

    /**
     * Re-reads the roster from the server: the users panel's Refresh.
     *
     * [force] is the button — the server re-seeds from project membership and
     * reconciles against the live room before answering. Line 3 only; the
     * other lines have no such route and their roster comes from Firestore.
     */
    fun refreshRoster(force: Boolean = false) {
        val current = _session.value ?: return
        if (current.provider != CallProvider.LiveKit) return
        line3?.refreshRoster(current.callUuid, current.callerUserId.ifBlank { current.selfUserId }, force = force)
    }

    /** Whether this user holds the call's host controls — the original caller, on Line 3. */
    val isHost: Boolean get() = line3InCall.isHost(_session.value)

    /**
     * Sends an emoji to everyone else on the call, and shows it here.
     *
     * On Line 3 the server floats it back to everyone, us included, and the
     * float is drawn from that echo alone — so the sender sees exactly what
     * the others see, and only once the server accepted it.
     */
    fun sendReaction(emoji: String) {
        if (_session.value?.provider == CallProvider.LiveKit) {
            if (line3InCall.reactionsRestricted) {
                line3InCall.lockedNote(S.desktop_call_host_disabled_reactions)
                return
            }
            line3InCall.react(emoji, now())
            return
        }
        inCall.sendReaction(emoji)
    }

    /** Sends one ephemeral line to everyone else on the call. */
    fun sendInCallMessage(text: String) {
        if (_session.value?.provider == CallProvider.LiveKit) {
            if (line3InCall.chatRestricted) {
                line3InCall.lockedNote(S.desktop_call_host_disabled_chat)
                return
            }
            if (line3InCall.selfChatBlocked()) {
                _notices.tryEmit(str(S.desktop_call_host_blocked_you_from_chat))
                return
            }
        }
        inCall.sendMessage(text)
    }

    // ── Line 3 in-call verbs ────────────────────────────────────────────

    /** Our own leg is on hold: sending nothing, hearing nothing, still in the room. */
    private val _onHold = MutableStateFlow(false)
    val onHold: StateFlow<Boolean> = _onHold.asStateFlow()

    /** Mic and camera as they stood when hold was pressed, so resume restores rather than switches on. */
    private var mediaBeforeHold = false to false

    /**
     * Holds or resumes. The room stays connected — a hold is a state change,
     * not a leave — and the server is told on the socket so everyone's
     * roster badges us. Resume puts back exactly what was on before.
     */
    fun toggleHold() = applyHold(on = !_onHold.value, tellServer = true)

    /**
     * The local half of a hold, and the server half when it started here.
     * A hold the server reported (`callHeld` for us, from another device)
     * is only applied — echoing it back would be a second hold request.
     */
    private fun applyHold(on: Boolean, tellServer: Boolean) {
        val current = _session.value ?: return
        if (_phase.value != CallPhase.InCall || current.provider != CallProvider.LiveKit) return
        if (_onHold.value == on) return
        if (on) {
            mediaBeforeHold = !_micMuted.value to _cameraOn.value
            _micMuted.value = true
            _cameraOn.value = false
        } else {
            _micMuted.value = !mediaBeforeHold.first
            _cameraOn.value = mediaBeforeHold.second
        }
        _onHold.value = on
        engine.setHold(on)
        if (!tellServer) return
        scope.launch {
            line3?.hold(current.callUuid, on, current.projectId.takeIf(String::isNotBlank), current.selfUserId)
        }
    }

    fun setListen(userId: String, listen: Boolean) = line3InCall.setListen(userId, listen)

    fun setWatch(userId: String, watch: Boolean) = line3InCall.setWatch(userId, watch)

    fun muteForEveryone(userId: String) = line3InCall.muteForEveryone(userId, camera = false)

    fun stopCameraForEveryone(userId: String) = line3InCall.muteForEveryone(userId, camera = true)

    fun removeFromCall(userId: String) = line3InCall.removeFromCall(userId)

    fun blockChat(userId: String, blocked: Boolean) = line3InCall.blockChat(userId, blocked)

    /**
     * Retracts an invite that is still ringing; the row goes back to addable.
     *
     * Line 3 asks its server. Lines 1 and 2 do what the web's ✕ does
     * (`AddUserDrawer.jsx:504-552`): decline the ring on the invitee's behalf —
     * over REST on Line 1, on their Firestore row on Line 2 — and log it as a
     * missed call for them.
     */
    fun cancelInvite(userId: String) {
        val current = _session.value ?: return
        val row = current.participants.firstOrNull { it.userId == userId && it.status == CallStatus.Ringing }
        if (current.provider == CallProvider.LiveKit) {
            line3InCall.cancelInvite(userId)
        } else if (row != null) {
            withdrawRing(current, row)
        }
        inviteExpiry.remove(userId)?.cancel()
        _session.value = current.copy(
            participants = current.participants.filterNot { it.userId == userId && it.status == CallStatus.Ringing },
        )
    }

    private fun withdrawRing(current: CallSession, row: CallParticipant) {
        scope.launch {
            val withdrawn = if (current.provider == CallProvider.Mediasoup) {
                api.withdrawMediasoupInvite(current, row.userId).isSuccess
            } else {
                true
            }
            // Line 2's withdrawal itself; on Line 1 it retires the seed row
            // [addUser] wrote, which otherwise read "ringing" to every other
            // desktop on the call for as long as it lasted.
            plane.updateUserFields(
                current,
                row.deviceId.ifBlank { row.userId },
                mapOf(FIELD_CURRENT_STATUS to CallStatus.Declined.wire),
            )
            if (!withdrawn) ZillitLog.w(TAG) { "cancel invite not delivered" }
            if (!current.isCalendarCall) api.logMissedInvite(current, row.userId, row.deviceId)
        }
    }

    fun setCallPolicy(policy: LiveKitCallPolicy) = line3InCall.setCallPolicy(policy)

    fun hostAction(action: String) = line3InCall.hostAction(action)

    fun admitGuest(guestId: String) =
        if (isLine1Guest(guestId)) answerLine1Guest(guestId, admit = true) else line3InCall.admitGuest(guestId)

    fun declineGuest(guestId: String) =
        if (isLine1Guest(guestId)) answerLine1Guest(guestId, admit = false) else line3InCall.declineGuest(guestId)

    /**
     * Line 1 link guests waiting at the door, by request id — the web's
     * `mediasoupRequests` (InCallView.jsx). The server asks every member over
     * `call:guest:join:request` and closes the request for all of them with
     * `call:guest:join:responded`; nothing collected either before, so a guest
     * could never be let in from the desktop.
     */
    private val _line1Guests = MutableStateFlow<List<GuestJoinRequest>>(emptyList())
    val line1Guests: StateFlow<List<GuestJoinRequest>> = _line1Guests.asStateFlow()

    private fun isLine1Guest(requestId: String) = _line1Guests.value.any { it.requestId == requestId }

    private suspend fun listenGuestRequests() {
        val events = listOf(ZillitSocketEvents.Calls.GuestJoinRequest, ZillitSocketEvents.Calls.GuestJoinResponded)
        bus.onAny(events).collect { message ->
            val request = message.payload?.let(::readGuestJoinRequest) ?: return@collect
            if (message.event == ZillitSocketEvents.Calls.GuestJoinResponded) {
                _line1Guests.update { list -> list.filterNot { it.requestId == request.requestId } }
                return@collect
            }
            val current = _session.value ?: return@collect
            if (current.provider != CallProvider.Mediasoup || _phase.value != CallPhase.InCall) return@collect
            if (request.roomId.isNotBlank() && !request.roomId.matches(current)) return@collect
            if (isLine1Guest(request.requestId)) return@collect
            _line1Guests.update { it + request }
            _chimes.tryEmit(Unit)
        }
    }

    /** Admits or refuses one guest; their row goes only once the server took the answer. */
    private fun answerLine1Guest(requestId: String, admit: Boolean) {
        val current = _session.value ?: return
        scope.launch {
            api.respondToGuest(
                roomId = current.restRoomId,
                requestId = requestId,
                admit = admit,
                respondedBy = current.selfUserId,
                projectId = current.projectId.takeIf(String::isNotBlank),
            ).onSuccess {
                _line1Guests.update { list -> list.filterNot { it.requestId == requestId } }
            }.onFailure { error ->
                // Kept, so the host can try again: a silent failure here left
                // the web's guests waiting on "pending" for ever (ZL-18656).
                ZillitLog.w(TAG) { "guest answer not delivered: ${error.technical ?: error.userMessage}" }
                _toasts.tryEmit(str(S.something_went_wrong))
            }
        }
    }

    /** The web's invite link for the live Line 3 call, or null when there is none to give. */
    fun inviteLink(): String? = line3InCall.inviteLink()

    /**
     * A second ring while we are on a call — the web's compact
     * "Decline / End & Accept" banner, on every line. Never auto-declined:
     * the server rings busy devices on purpose, and the choice is the user's.
     */
    private val _secondCall = MutableStateFlow<CallSession?>(null)
    val secondCall: StateFlow<CallSession?> = _secondCall.asStateFlow()
    private var secondCallTimeout: Job? = null

    /**
     * One soft chime, once — the web's `guestChime`: a second ring over a
     * live call, or a guest knocking. Never the ringtone over a live call.
     */
    private val _chimes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val chimes: SharedFlow<Unit> = _chimes.asSharedFlow()

    fun declineSecondCall() {
        val waiting = _secondCall.value ?: return
        dismissSecondCall()
        if (waiting.provider == CallProvider.LiveKit) {
            scope.launch { line3?.decline(waiting) }
        } else {
            declineRing(waiting)
        }
    }

    /**
     * Ends the call we are on, then answers the waiting one here. A real
     * hang-up first, not a bare leave: the server must mark us gone before
     * the new call starts, or the old call's `callEnded` — which names no
     * call — lands on the one we are joining and ends it.
     */
    fun endAndAcceptSecondCall() {
        val waiting = _secondCall.value ?: return
        val current = _session.value ?: return
        dismissSecondCall()
        val wasInCall = _phase.value == CallPhase.InCall
        _phase.value = CallPhase.Ending
        scope.launch {
            engine.leave()
            if (current.provider == CallProvider.LiveKit) {
                if (current.direction == CallDirection.Outgoing && !wasInCall) {
                    line3?.cancel(current.callUuid, current.projectId.takeIf(String::isNotBlank), current.selfUserId)
                } else {
                    line3?.leave(current)
                }
            } else {
                plane.announceSelf(current, CallStatus.Left)
                // A Line 1 call of ours still ringing is withdrawn, as a hang-up would.
                val cancelsRing = current.provider == CallProvider.Mediasoup &&
                    current.direction == CallDirection.Outgoing && !wasInCall
                sendFinalStatus(
                    current,
                    CallStatus.Left,
                    word = if (cancelsRing) LINE1_RESPONSE_CANCELLED else CallStatus.Left.line1ResponseWord,
                )
            }
            finish(current, CallEndReason.Hungup)
            onInvite(waiting)
            accept()
        }
    }

    private fun dismissSecondCall() {
        secondCallTimeout?.cancel()
        secondCallTimeout = null
        _secondCall.value = null
    }

    /** The server's list of live Line 3 calls this user may join, return to, or switch here. */
    private val _activeCalls = MutableStateFlow<List<LiveKitActiveCall>>(emptyList())
    val activeCalls: StateFlow<List<LiveKitActiveCall>> = _activeCalls.asStateFlow()

    /**
     * Joins a call already under way — the Calls tab's Join, or Switch here
     * when we are in it on another device. Refused while any call is up: the
     * tab shows Return for the one we are in, and two calls on one
     * microphone is the bug placeCall refuses for the same reason.
     */
    fun joinActiveCall(callId: String) {
        if (_phase.value != CallPhase.Idle) return
        val line = line3 ?: return
        val me = line.identityNow() ?: return
        val info = _activeCalls.value.firstOrNull { it.callId == callId } ?: run {
            _toasts.tryEmit(str(S.desktop_call_that_call_has_ended))
            return
        }
        val callerName = info.callerName.ifBlank { info.inCallUsers.firstOrNull()?.second.orEmpty() }
        _phase.value = CallPhase.Outgoing
        _session.value = CallSession(
            callUuid = info.callId,
            roomId = info.callId,
            chatRoomId = info.chatRoomId,
            projectId = info.projectId.ifBlank { me.projectId },
            direction = CallDirection.Incoming,
            provider = CallProvider.LiveKit,
            mode = info.callMode,
            type = info.callType,
            hasVideo = info.callType == CallType.Video,
            callerUserId = info.callerId,
            callerName = callerName,
            selfUserId = me.userId,
            selfDeviceId = selfDeviceId().orEmpty(),
            title = info.title,
            participants = info.inCallUsers
                .filter { (id, _) -> id != me.userId }
                .map { (id, name) ->
                    CallParticipant(
                        userId = id,
                        name = name,
                        status = if (id == info.callerId) CallStatus.Caller else CallStatus.InCall,
                    )
                },
        )
        _cameraOn.value = info.callType == CallType.Video
        scope.launch {
            when (val joined = line.joinActive(info.callId, me)) {
                is ZillitResult.Failure -> fail(joined.error.userMessage)
                is ZillitResult.Success -> {
                    val live = _session.value ?: return@launch
                    if (live.callUuid != info.callId) return@launch
                    val session = live.copy(livekitUrl = joined.data.url, livekitToken = joined.data.token)
                    _session.value = session
                    // Everyone was in before we arrived: no transition will move us.
                    _phase.value = CallPhase.InCall
                    joinMedia(session)
                }
            }
        }
    }

    private val _ended = MutableSharedFlow<CallEndEvent>(extraBufferCapacity = 4)
    val ended: SharedFlow<CallEndEvent> = _ended.asSharedFlow()

    /** Microphone state, owned here so every surface shows the same value. */
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _cameraOn = MutableStateFlow(false)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

    /** Everything the media stack has told us about this call. */
    private val _handRaised = MutableStateFlow(false)
    val handRaised: StateFlow<Boolean> = _handRaised.asStateFlow()

    /** Recording, a collaborator for the same reason [inCall] is. */
    private val recorder = CallRecordingControl(
        engine = engine,
        plane = plane,
        scope = scope,
        selfDeviceId = selfDeviceId,
    )

    /** This machine is recording the call. */
    val recording: StateFlow<Boolean> get() = recorder.recording

    /** Who else is recording — their display name, or blank for nobody. */
    val recordedBy: StateFlow<String> get() = recorder.recordedBy

    /** One-line notices worth showing mid-call — "recording saved to…". */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** The machine's audio/video hardware, as the engine last reported it. */
    /** The machine's audio hardware and this user's standing choice of it. */
    private val audio = CallAudioDevices(
        engine = engine,
        scope = scope,
        load = loadAudioDevices,
        save = saveAudioDevices,
    )
    val devices: StateFlow<CallDevices> get() = audio.devices

    private val _media = MutableStateFlow(CallMedia())
    val media: StateFlow<CallMedia> = _media.asStateFlow()

    /** Our display name, for the self tile. The same source the plane writes. */
    val selfDisplayName: String get() = selfName().orEmpty()

    private var ringTimeout: Job? = null

    /**
     * Everyone who ever genuinely answered this call, never pruned.
     *
     * See [allOthersGone] for why it is monotonic. Cleared with the call.
     */
    private val everConnected = mutableSetOf<String>()

    /** Somebody's media reached this call at least once — see [callDeserted]. */
    private var sawRemoteMedia = false

    /** The pending "is anyone still here?" re-check, if one is armed. */
    private var emptyRoomCheck: Job? = null

    /** How long [emptyRoomCheck] was told to wait, so a shorter verdict can replace it. */
    private var emptyRoomGrace = 0L

    /**
     * The last Line 1 host the backend elected, for a call that arrives without
     * one. The web falls back to a build-time host (`CallConfig.MEDIASOUP_URL`,
     * which defaults to dev) and joins; the desktop stayed signalling-only, a
     * call with no audio at all. A host this same backend just handed out is
     * the one fallback that cannot point a prod call at a dev SFU.
     */
    private var lastSfuHost = ""

    private fun withKnownSfuHost(session: CallSession): CallSession {
        if (session.provider != CallProvider.Mediasoup) return session
        if (session.sfuHost.isNotBlank()) {
            lastSfuHost = session.sfuHost
            return session
        }
        if (lastSfuHost.isBlank()) return session
        ZillitLog.i(TAG) { "${session.callUuid} named no SFU; using the last one elected, $lastSfuHost" }
        val healed = session.copy(sfuHost = lastSfuHost)
        if (_session.value?.callUuid == session.callUuid) _session.value = healed
        return healed
    }

    /** The outgoing ring clock was restarted on the callee's first `ringing`; see [noteRingProgress]. */
    private var ringRearmed = false

    /** Uids whose media has reached us — on Line 1 the only proof of an answer. See [adoptAnswersFromMedia]. */
    private val mediaUids = mutableSetOf<Int>()

    /** One clock per invitee still ringing; see [watchInviteExpiry]. */
    private val inviteExpiry = mutableMapOf<String, Job>()

    /** Who this device rang mid-call — their ring running out is logged as missed. */
    private val addedByUs = mutableSetOf<String>()

    /** Server truth for a Line 3 ring this device is showing — see [LiveKitRingWatch]. */
    private val line3Ring = LiveKitRingWatch()

    /**
     * Line 3's client → server telemetry. Diagnostics only: nothing it does
     * can change how a call behaves, and it is silent outside a call.
     *
     * Line 3 alone, as on the phones and the web: `clientLog` is the calling
     * backend's route and Lines 1 and 2 have no equivalent, so a `null` line
     * leaves every emit a no-op rather than inventing a second transport.
     */
    private val diagnostics = CallDiagnostics(
        scope = scope,
        now = now,
        socketOnline = { line3?.online?.value == true },
        sendOverSocket = { callId, event, data -> line3?.clientLog(callId, event, data) == true },
        sendOverRest = { callId, event, data ->
            line3?.clientLogOverRest(callId, event, data, selfDeviceId().orEmpty())
        },
    )

    /** Tells two echoed reactions in one millisecond apart; see the listener. */
    private var reactionSequence = 0L

    /** Runs only while the media link is down; cancelled the moment it returns. */
    /** Ends a call the media stack never brought back. */
    private val reconnect = ReconnectWatchdog(
        scope = scope,
        graceMillis = RECONNECT_GRACE_MILLIS,
        stillInCall = { _phase.value == CallPhase.InCall },
        onGiveUp = ::fail,
    )
    private var planeWatch: Job? = null

    /**
     * One-shot latch around the join: the accept path and a racing
     * `call:update` can both conclude "time to join", and joining an Agora
     * channel twice with the same uid kicks the first join out.
     */
    private var joining = false

    /** When [joinMedia] handed the room to the engine — the `warm_connect` clock. */
    private var joinStartedAtMillis = 0L

    fun start() {
        line3?.attach(Line3Listener())
        line3?.start()
        scope.launch { listenIncoming() }
        scope.launch { listenStatusChanges() }
        scope.launch { listenEnded() }
        scope.launch { listenTimeout() }
        scope.launch { listenHandoffEvict() }
        scope.launch { inCall.listen() }
        scope.launch { listenEngine() }
        scope.launch { watchInviteExpiry() }
        scope.launch { listenGuestRequests() }
        scope.launch { logRosterChanges() }
    }

    // ── Outgoing ────────────────────────────────────────────────────────

    /**
     * Places a call.
     *
     * Refused while any call exists — the server would allow it, and the
     * result is two sessions fighting over one microphone.
     */
    @Suppress("LongParameterList")
    fun placeCall(
        chatRoomId: String,
        receiverDeviceId: String,
        mode: CallMode,
        type: CallType,
        displayName: String = "",
        /**
         * Which line to place it on.
         *
         * The caller chooses, because the two lines are separate call plumbing
         * on the server — a different endpoint each — rather than two settings
         * of one thing. [receiverUserId] is what Line 1 needs: it rings a
         * person across their devices, where Line 2 rings one device.
         */
        provider: CallProvider = CallProvider.Agora,
        receiverUserId: String = "",
        /**
         * A room struck from a calendar or box-schedule event.
         *
         * Nothing on the wire says so — the server is told the same thing as
         * for any group call, and the specialness is all local: nobody is
         * being rung, so there is no ring to time out, and the room outlives
         * whoever is currently in it, so leaving must not end it.
         */
        isCalendarCall: Boolean = false,
        /**
         * A call to the 24x7 support team.
         *
         * Also invisible on the wire: it is placed as an ordinary private
         * call and the backend routes it to whichever agent is free. What it
         * changes here is that nobody may be added and nothing may be
         * recorded.
         */
        is247Call: Boolean = false,
        /**
         * The production the call belongs to, when it is not the open one —
         * a call placed from a widget showing another production.
         *
         * Carried on the session from the first moment, because every write
         * that follows (end, miss, status) reads it back off the session.
         */
        projectId: String? = null,
        /** The caller's id ON [projectId]; project-scoped, so not the ambient one. */
        callerUserId: String = "",
    ) {
        if (_phase.value != CallPhase.Idle) return
        if (provider == CallProvider.LiveKit) {
            placeLine3(chatRoomId, receiverUserId, mode, type, displayName, projectId, callerUserId)
            return
        }
        _phase.value = CallPhase.Outgoing
        // A provisional session so the outgoing card and its Cancel exist for
        // the whole create-call round trip. Every overlay branch is gated on a
        // non-null session and hangUp() returns without one, so a stalled POST
        // otherwise means up to a minute of no card, no cancel, and every
        // further call-button press silently refused by the phase check above.
        _session.value = provisionalSession(
            chatRoomId, receiverDeviceId, receiverUserId,
            mode, type, displayName, provider, isCalendarCall, is247Call,
            projectId.orEmpty(),
        )
        _cameraOn.value = type == CallType.Video
        scope.launch {
            api.createOnLine(
                NewCall(
                    provider = provider,
                    chatRoomId = chatRoomId,
                    receiverDeviceId = receiverDeviceId,
                    receiverUserId = receiverUserId,
                    mode = mode,
                    type = type,
                    selfUserId = selfUserId().orEmpty(),
                    selfDeviceId = selfDeviceId().orEmpty(),
                    projectId = projectId,
                    callerUserId = callerUserId,
                ),
            ).onSuccess { session ->
                if (session == null) {
                    fail("the server did not return a call")
                } else if (_phase.value != CallPhase.Outgoing) {
                    // Cancelled while the POST was in flight. The call exists
                    // on the server regardless, so end it rather than adopting
                    // a call the user already walked away from.
                    abandonLateCall(session)
                } else if (session.crossedCall) {
                    answerCrossedCall(session, displayName, projectId)
                } else {
                    // The response describes the caller — us. The callee's
                    // name came from the screen that pressed the button.
                    _session.value = session.copy(
                        title = displayName.ifBlank { session.title },
                        receiverDeviceId = receiverDeviceId,
                        receiverUserId = receiverUserId,
                        // Re-stamped because the create-call response does not
                        // carry them: adopting it wholesale would drop what
                        // the caller just said about what kind of call this is.
                        isCalendarCall = isCalendarCall,
                        is247Call = is247Call,
                        // The create response names no production, and every
                        // later write reads it off the session.
                        projectId = projectId?.takeIf(String::isNotBlank) ?: session.projectId,
                    )
                    _cameraOn.value = session.hasVideo
                    // A calendar room rings nobody — the caller walks into it —
                    // so there is no unanswered call to give up on.
                    if (!isCalendarCall) startRingTimeout(CallTimeouts.OUTGOING_MS) { outgoingRangOut() }
                    // Claim our own row before watching: the caller is a participant
                    // like any other, and iOS/web read the roster to know who is on
                    // the call. Without this the desktop was missing from the call
                    // it had just placed.
                    scope.launch { announceCaller(session) }
                    watchPlane(session)
                    joinMedia(session)
                }
            }.onFailure { error ->
                fail(error.localised())
            }
        }
    }

    // ── Incoming ────────────────────────────────────────────────────────

    private suspend fun listenIncoming() {
        bus.on(ZillitSocketEvents.Calls.Incoming).collect { message ->
            val payload = message.payload ?: return@collect
            val invite = readCallSession(
                payload,
                selfUserId().orEmpty(),
                selfDeviceId().orEmpty(),
            ) ?: return@collect
            // A Line 3 ring arrives on the presence socket; the chat socket's
            // copy, where it sends one, is not a second ring.
            if (invite.provider == CallProvider.LiveKit && line3 != null) return@collect
            onInvite(invite)
        }
    }

    private fun onInvite(invite: CallSession) {
        // Our own ring echoed back: the server rings every device in the
        // room, including the caller's. Without this gate the caller's own
        // screen shows an incoming call from themselves.
        if (invite.callerDeviceId.isNotBlank() && invite.callerDeviceId == selfDeviceId()) return

        if (_phase.value != CallPhase.Idle) {
            // Already busy. Tell the caller so their screen stops ringing —
            // and scope the response to the RINGING call's ids, not the
            // active call's.
            // Two channels, on their own coroutines so neither waits for the
            // other: the REST response is what a Line 1 caller hears, and the
            // roster row is the only thing a Line 2 caller is watching — the
            // response is inert there. Sequencing them would put the write
            // that matters on Agora behind a request that does nothing for it.
            // Both are scoped to the RINGING call's ids, never the live one's.
            if (invite.provider == CallProvider.LiveKit) {
                offerSecondCall(invite)
                return
            }
            // The call we are on, rung again (a re-invite to our own room), is
            // not a second call; neither is anything while we are still
            // ringing or leaving — those keep the old busy decline.
            val current = _session.value
            if (current != null && invite.callUuid.matches(current)) return
            val canChoose = _phase.value == CallPhase.InCall || _phase.value == CallPhase.Outgoing
            if (canChoose && offerSecondCall(invite)) {
                // Ringing, as any ring we show is: the caller hears it ring
                // while the user decides, not an instant refusal.
                acknowledgeRing(invite)
            } else {
                declineRing(invite)
            }
            return
        }

        ZillitLog.i(TAG) { "incoming call ${invite.callUuid} mode=${invite.mode}" }
        _session.value = invite
        _phase.value = CallPhase.Incoming
        // Line 3 acknowledged the ring on its own socket; the v2 response and
        // the Firestore mirror are Lines 1 and 2's.
        if (invite.provider != CallProvider.LiveKit) {
            acknowledgeRing(invite)
            watchPlane(invite)
        }
        startRingTimeout(CallTimeouts.INCOMING_MS) { incomingRangOut() }
    }

    /**
     * A Line 3 ring while we are busy: shown as the second-call banner
     * rather than declined for the user. One at a time, the way the web
     * keeps one pending invite; the ring window is the server's, so a banner
     * nobody answers simply goes away when it closes.
     */
    private fun offerSecondCall(invite: CallSession): Boolean {
        val current = _session.value
        if (current != null && invite.callUuid.matches(current)) return false
        if (_secondCall.value != null) return false
        _secondCall.value = invite
        _chimes.tryEmit(Unit)
        secondCallTimeout?.cancel()
        secondCallTimeout = scope.launch {
            delay(SECOND_CALL_BANNER_MILLIS)
            if (_secondCall.value?.callUuid != invite.callUuid) return@launch
            _secondCall.value = null
            // Lines 1 and 2 ring until somebody says otherwise; Line 3's
            // server closes its own ring window.
            if (invite.provider != CallProvider.LiveKit) {
                // Own coroutines, as [declineRing]: neither channel waits on the other.
                scope.launch { sendFinalStatus(invite, CallStatus.NotAnswered) }
                scope.launch { plane.announceSelf(invite, CallStatus.NotAnswered) }
                toastMissed(invite)
            }
        }
        return true
    }

    /** A Line 1/2 ring this device is showing: `ringing` to the caller, on both channels. */
    private fun acknowledgeRing(invite: CallSession) {
        scope.launch { respond(invite, CallStatus.Ringing) }
        scope.launch { plane.announceSelf(invite, CallStatus.Ringing) }
    }

    /**
     * Refuses a Line 1/2 ring, scoped to the RINGING call's ids, never the
     * live one's. Two channels on their own coroutines so neither waits for
     * the other: the REST response is what a Line 1 caller hears, and the
     * roster row is the only thing a Line 2 caller is watching.
     */
    private fun declineRing(invite: CallSession) {
        scope.launch { respond(invite, CallStatus.Declined) }
        scope.launch { plane.announceSelf(invite, CallStatus.Declined) }
    }

    /**
     * The banner's ring went away on the caller's side — they gave up, it
     * ended, or we answered it elsewhere. The banner's call when [roomId] was it.
     */
    private fun dismissWaitingFor(roomId: String): CallSession? {
        val waiting = _secondCall.value ?: return null
        if (roomId.isBlank() || !roomId.matches(waiting)) return null
        dismissSecondCall()
        return waiting
    }

    /**
     * A status about the banner's call rather than ours: the caller giving up
     * closes it with a missed-call notice, our answering it on another device
     * closes it quietly. True when the change was the banner's.
     */
    private fun onWaitingCallStatus(change: CallStatusChange): Boolean {
        val waiting = _secondCall.value ?: return false
        if (change.roomId.isBlank() || !change.roomId.matches(waiting)) return false
        val callerGaveUp = change.userId == waiting.callerUserId && change.status in CALLER_GAVE_UP
        val answeredElsewhere = change.userId == waiting.selfUserId && change.status.isConnected
        if (callerGaveUp || answeredElsewhere) dismissSecondCall()
        if (callerGaveUp) toastMissed(waiting)
        return true
    }

    private fun toastMissed(waiting: CallSession) {
        _toasts.tryEmit(str(S.desktop_call_missed_call_from, waiting.callerName))
    }

    /** The user pressed accept. */
    fun accept() {
        val current = _session.value ?: return
        if (_phase.value != CallPhase.Incoming) return
        cancelRingTimeout()
        // Accepting IS the transition, rather than waiting for the engine's
        // Joined. Two things go wrong when the phase lags behind the tap: a
        // join that degrades — no channel/token, Chromium not up — strands the
        // machine in Incoming with its ring timeout already cancelled and no
        // way out; and while it lingers there, our own `in_call` echoed back
        // by the socket or the Firestore poll reads as somebody else
        // answering, which tears down the call we just took.
        _phase.value = CallPhase.InCall
        // The server decided this at create time, and the engine joins with
        // it; a false here would show a camera-off button over a live camera.
        _cameraOn.value = current.hasVideo
        if (current.provider == CallProvider.LiveKit) {
            // Before any roster or network work, so a tap with no matching
            // `accept_sent` names the moment the answer was lost.
            diagnostics.startCall(current.callUuid)
            diagnostics.log("accept_tap", mapOf("source" to "inapp"))
            // The devices open now rather than after the room exists: the OS
            // prompt and the capture then overlap the accept and the connect,
            // which is the web's `prewarmMedia` on its answering path.
            engine.prewarmMedia(video = current.hasVideo, audio = true)
            // The pre-warmed room (if the ring warmed one) is this call's now:
            // a terminal event arriving around the accept must not disconnect
            // the very room the accept depends on.
            engine.claimPrewarm(current.callUuid)
            acceptLine3(current)
            return
        }
        // Status and join in parallel: the status is advisory, and joining
        // behind it would make the user's media wait on an HTTP round trip.
        scope.launch {
            // The server can answer that the call is already over — the caller
            // gave up as we pressed accept. Joining it anyway sat the user in an
            // empty room (the web aborts here: callStore.js:1638-1682).
            val verdict = respond(current, CallStatus.InCall)
            if (CALL_ENDED in verdict && _session.value?.callUuid == current.callUuid) {
                ZillitLog.i(TAG) { "accept answered $verdict; ${current.callUuid} is over" }
                _toasts.tryEmit(str(S.desktop_call_that_call_has_ended))
                engine.leave()
                finish(current, CallEndReason.RemoteEnded)
            }
        }
        scope.launch { announceJoined(current) }
        scope.launch { joinMedia(current) }
    }

    /**
     * The caller's row, name included: the row is the one place every platform
     * looks for a caller the invite never named — the "Guest" tile, from the
     * other side.
     */
    private suspend fun announceCaller(session: CallSession) {
        val named = selfName()?.takeIf(String::isNotBlank)
            ?.let { mapOf("user_name" to it as Any) }.orEmpty()
        plane.announceSelf(session, CallStatus.Caller, named)
    }

    /** Android's accept-time write: status, uid, video, and the doc-healing name. */
    private suspend fun announceJoined(session: CallSession) {
        val extra = buildMap<String, Any> {
            if (session.localUid != 0) put("agora_uid", session.localUid)
            put("has_video", session.hasVideo)
            selfName()?.takeIf(String::isNotBlank)?.let { put("user_name", it) }
        }
        plane.announceSelf(session, CallStatus.InCall, extra)
    }

    /** The user pressed decline. */
    fun decline() {
        val current = _session.value ?: return
        if (_phase.value != CallPhase.Incoming) return
        // Separate coroutines, because the mirror is never primary: the plane
        // rides a 60 s client timeout, and a black-holed Firestore holding the
        // decline behind it outlasts the caller's own 60 s ring — they would
        // see "No answer" and log a missed call for a call that was declined.
        if (current.provider == CallProvider.LiveKit) {
            scope.launch { line3?.decline(current) }
            finish(current, CallEndReason.Declined)
            return
        }
        scope.launch { plane.announceSelf(current, CallStatus.Declined) }
        scope.launch { sendFinalStatus(current, CallStatus.Declined) }
        finish(current, CallEndReason.Declined)
    }

    // ── In-call controls ────────────────────────────────────────────────

    /**
     * Rings one more person into the running call.
     *
     * Optimistic: the roster grows a Ringing row immediately, and the server's
     * `call:update` stream corrects it if the invite is refused. Refused for a
     * support call — a 24x7 call goes to a single agent, and the inviter has
     * no business pulling bystanders into it (Android hides the same menu).
     */
    fun addUser(userId: String, deviceId: String, name: String) {
        val current = _session.value ?: return
        if (_phase.value != CallPhase.InCall || current.is247Call) return
        if (!current.canInvite(userId, deviceId)) return
        // Someone who left, declined or never answered is rung again — the
        // web's "Left / Declined" rows carry Add for exactly that. Anyone
        // still here, or still ringing, is not: a second invite would only
        // ring a phone that is already ringing.
        val existing = current.participants.firstOrNull { it.userId == userId }
        if (existing != null && existing.status !in RE_RINGABLE) return

        _session.value = current.withRinging(userId, deviceId, name)
        addedByUs.add(userId)
        scope.launch {
            if (current.provider == CallProvider.LiveKit) {
                // Line 3 invites over its own socket; the roster row above is the seed.
                if (line3?.addToCall(current.callUuid, userId) != true) {
                    ZillitLog.w(TAG) { "add-user over Line 3 failed" }
                }
                return@launch
            }
            // Seeded before the invite goes out, the way the phones do it: on
            // Line 1 the backend writes no roster row for an added person (the
            // Agora side seeds one itself), so until their device rings and
            // writes its own, nobody else on the call knows an invite is
            // pending. Someone who never rings — offline, no push — is
            // otherwise invisible, and the room re-invites them.
            plane.updateUserFields(
                current,
                deviceId.ifBlank { userId },
                mapOf(
                    FIELD_CURRENT_STATUS to STATUS_ADD_IN_CALL,
                    FIELD_USER_NAME to name,
                    FIELD_USER_ID to userId,
                    FIELD_DEVICE_ID to deviceId,
                ),
            )
            api.invite(current, userId, deviceId).onFailure { error ->
                ZillitLog.w(TAG) { "add-user failed: ${error.technical ?: error.userMessage}" }
            }
        }
    }

    fun refreshDevices() = audio.refresh()

    fun chooseMicrophone(deviceId: String) = audio.chooseMicrophone(deviceId)

    fun chooseSpeaker(deviceId: String) = audio.chooseSpeaker(deviceId)

    fun toggleMicrophone() {
        val muted = !_micMuted.value
        _micMuted.value = muted
        engine.setMicrophoneMuted(muted)
        mirrorMediaState(mapOf("isMute" to muted))
    }

    /**
     * Starts or stops sharing this machine's screen.
     *
     * The engine answers with a `ScreenShare` event either way — including
     * when the user cancels — so the button follows what actually happened
     * rather than what was asked for.
     */
    /**
     * Raises or lowers this user's hand.
     *
     * A pure toggle with no timer and no auto-lower, matching the phones: it
     * stays up until the person who raised it puts it down, or the call ends.
     * The roster row is the whole transport on this line.
     */
    fun toggleHand() {
        if (_phase.value != CallPhase.InCall) return
        if (line3InCall.handsRestricted) {
            line3InCall.lockedNote(S.desktop_call_host_disabled_raising_hands)
            return
        }
        val raised = !_handRaised.value
        _handRaised.value = raised
        mirrorMediaState(mapOf("raise_hand" to raised))
        // Line 1 additionally announces it over protoo — the phones there
        // learn hands from `peerRaisedHand` broadcasts, not only the mirror.
        engine.setHandRaised(raised)
    }

    /**
     * Puts our own hand down, whatever the policy says.
     *
     * The host's `lowerHands` and `handsLowered` both land here: complying
     * with an instruction is not the same act as raising a hand, so it is not
     * gated the way [toggleHand] is. The mirror is written too, so the other
     * clients' rosters agree.
     */
    private fun lowerOwnHand() {
        if (!_handRaised.value) return
        _handRaised.value = false
        mirrorMediaState(mapOf("raise_hand" to false))
        engine.setHandRaised(false)
    }

    /**
     * Starts sharing [sourceId], or the whole desktop when it is null.
     *
     * Split from stopping because starting now has a question in front of it —
     * which screen or window — and the answer is the UI's to collect. The
     * engine call is still fire-and-forget: what comes back is a
     * `screen-share` event once the capture actually exists, and a share the
     * user cancelled arrives the same way with `sharing = false`.
     */
    fun startScreenShare(sourceId: String? = null) {
        if (_phase.value != CallPhase.InCall) return
        if (_media.value.selfSharing) return
        if (line3InCall.shareRestricted) {
            line3InCall.lockedNote(S.desktop_call_host_disabled_screen_sharing)
            return
        }
        scope.launch { engine.startScreenShare(sourceId) }
    }

    /** Whether Present may be offered at all: the host may lock it for everyone but themselves. */
    val screenShareRestricted: Boolean get() = line3InCall.shareRestricted

    fun stopScreenShare() {
        if (_media.value.selfSharing) scope.launch { engine.stopScreenShare() }
    }

    /**
     * The session that stands in while the server is still being asked.
     *
     * Built before the round trip so the outgoing card and its Cancel exist
     * for the whole of it: every overlay branch is gated on a non-null
     * session, so a stalled POST would otherwise mean up to a minute with no
     * card, no way to cancel, and every further press of the call button
     * silently refused.
     */
    @Suppress("LongParameterList") // Everything the provisional card needs to draw.
    private fun provisionalSession(
        chatRoomId: String,
        receiverDeviceId: String,
        receiverUserId: String,
        mode: CallMode,
        type: CallType,
        displayName: String,
        provider: CallProvider,
        isCalendarCall: Boolean,
        is247Call: Boolean,
        projectId: String,
    ) = CallSession(
        // Blank until the server names it. Every id-scoped write already
        // guards on blank — the doc's "must have a valid ObjectId" rule — so
        // a provisional session cannot post a status for no call.
        callUuid = "",
        direction = CallDirection.Outgoing,
        // Named now so the outgoing card can say which line it is on before
        // the server answers.
        provider = provider,
        mode = mode,
        type = type,
        hasVideo = type == CallType.Video,
        selfUserId = selfUserId().orEmpty(),
        selfDeviceId = selfDeviceId().orEmpty(),
        receiverDeviceId = receiverDeviceId,
        receiverUserId = receiverUserId,
        chatRoomId = chatRoomId,
        title = displayName,
        // Set before the server answers so the controls are already right
        // while it rings — a support call must not offer Record even for the
        // second the request is in flight.
        isCalendarCall = isCalendarCall,
        is247Call = is247Call,
        projectId = projectId,
    )

    /** Starts or stops recording the call's audio on this machine. */
    /** A Line 3 line, named from the roster where the token carried no name. */
    private fun onChatReceived(event: CallEngineEvent.ChatReceived) {
        if (event.deleted || event.text.isBlank()) return
        val current = _session.value ?: return
        val name = current.participants.firstOrNull { it.userId == event.fromUserId }?.name
            ?.takeIf { it.isNotBlank() } ?: event.name
        inCall.receive(
            InCallData(
                roomId = current.roomId.ifBlank { current.callUuid },
                kind = IN_CALL_KIND_MESSAGE,
                fromUserId = event.fromUserId,
                name = name,
                text = event.text,
                id = event.id.ifBlank { "${event.fromUserId}:${event.atMillis}" },
                atMillis = event.atMillis,
            ),
        )
    }

    fun toggleRecording() {
        if (_phase.value != CallPhase.InCall) return
        val session = _session.value ?: return
        when {
            session.provider != CallProvider.LiveKit -> recorder.toggle(session)
            line3InCall.recordingRestricted -> line3InCall.lockedNote(S.desktop_call_host_disabled_call_recording)
            else -> recorder.toggle(session) { on -> markLine3Recording(session, on) }
        }
    }

    /**
     * Line 3's server-side mark, before the local recorder runs: the web's
     * `setRecording`. A second recorder is refused 409 `already_recording`,
     * and the local recorder must not start for a recording nobody else sees.
     */
    private suspend fun markLine3Recording(session: CallSession, on: Boolean): Boolean {
        val line = line3 ?: return false
        val me = line.identityNow() ?: return false
        val outcome = line.markRecording(session.callUuid, on, me.copy(userId = session.selfUserId))
        if (on && outcome is ZillitResult.Failure) {
            val word = (outcome.error as? ZillitError.Http)?.serverMessage
            _notices.tryEmit(
                if (word == "already_recording") {
                    str(S.desktop_call_someone_already_recording)
                } else {
                    str(S.desktop_call_couldnt_start_recording)
                },
            )
        }
        return !on || outcome is ZillitResult.Success
    }

    fun toggleCamera() {
        val on = !_cameraOn.value
        _cameraOn.value = on
        engine.setCameraEnabled(on)
        mirrorMediaState(mapOf("has_video" to on))
    }

    /**
     * Publishes a live media flag onto our roster row.
     *
     * The phones draw the muted marker and the camera state from `isMute` and
     * `has_video` on each row, so a desktop that changed them locally and told
     * nobody looked permanently unmuted with its camera in whatever state it
     * joined. Only while in the call: the row's status field rides along with
     * the write, and asserting `in_call` from any other phase would contradict
     * the phase we are actually in.
     */
    private fun mirrorMediaState(fields: Map<String, Any>) {
        if (_phase.value != CallPhase.InCall) return
        val session = _session.value ?: return
        scope.launch { plane.announceSelf(session, CallStatus.InCall, fields) }
    }

    /** The user hung up. */
    fun hangUp() {
        val current = _session.value ?: return
        if (_phase.value == CallPhase.Idle || _phase.value == CallPhase.Ending) return
        val wasInCall = _phase.value == CallPhase.InCall
        _phase.value = CallPhase.Ending
        when (current.provider) {
            CallProvider.LiveKit -> return hangUpLine3(current, wasInCall)
            CallProvider.Mediasoup -> return hangUpLine1(current, wasInCall)
            else -> Unit
        }
        // Android's rule: the last one out ends the call for everyone; anyone
        // else merely leaves. Ending a room three people are talking in
        // because one hung up is the bug this avoids.
        val othersActive = current.participants.any {
            it.userId != current.selfUserId && it.status.isConnected
        }
        // A call the server has not named yet cannot be ended by uuid; the
        // create-call response handles the cancel when it lands.
        // A calendar room outlives whoever is in it: the event owns it, and
        // the next person to join expects to find it there. Leaving is the
        // only thing this button may do on one.
        val endsForEveryone = current.callUuid.isNotBlank() && !othersActive &&
            !current.isCalendarCall &&
            (current.direction == CallDirection.Outgoing || wasInCall)
        // The mirror off the critical path: a Firestore write that black-holes
        // must not hold the hang-up the room is waiting on behind a 60 s
        // timeout, leaving the call neither left nor ended for a whole minute.
        scope.launch {
            plane.announceSelf(current, CallStatus.Left)
            if (endsForEveryone) plane.announceCallEnded(current)
        }
        scope.launch {
            engine.leave()
            if (endsForEveryone) {
                api.endCall(
                    callUuid = current.callUuid,
                    deviceId = selfDeviceId().orEmpty(),
                    projectId = current.projectId.takeIf(String::isNotBlank),
                    provider = current.provider,
                )
            }
            sendFinalStatus(current, CallStatus.Left)
            finish(current, CallEndReason.Hungup)
        }
    }

    private fun hangUpLine3(current: CallSession, wasInCall: Boolean) {
        scope.launch {
            engine.leave()
            // Unanswered and ours: a cancel, so the far side stops ringing.
            if (current.direction == CallDirection.Outgoing && !wasInCall) {
                line3?.cancel(current.callUuid, current.projectId.takeIf(String::isNotBlank), current.selfUserId)
            } else {
                line3?.leave(current)
            }
            finish(current, CallEndReason.Hungup)
        }
    }

    /**
     * Line 1 never ends a call by uuid — not the web, not Android. A ring we
     * placed is withdrawn with `cancelled`, the backend's cue to stop the
     * callees and log their misses; anything else is `left`, and the server
     * closes the room when the SFU empties (`callStore.js:2225-2283`).
     */
    private fun hangUpLine1(current: CallSession, wasInCall: Boolean) {
        val cancelsRing = current.direction == CallDirection.Outgoing && !wasInCall
        scope.launch { plane.announceSelf(current, CallStatus.Left) }
        scope.launch {
            engine.leave()
            sendFinalStatus(
                current,
                CallStatus.Left,
                word = if (cancelsRing) LINE1_RESPONSE_CANCELLED else CallStatus.Left.line1ResponseWord,
            )
            finish(current, CallEndReason.Hungup)
        }
    }

    // ── Socket reactions ────────────────────────────────────────────────

    private suspend fun listenStatusChanges() {
        val events = listOf(ZillitSocketEvents.Calls.Update, ZillitSocketEvents.Calls.Response)
        bus.onAny(events).collect { message ->
            val change = message.payload?.let(::readStatusChange) ?: return@collect
            applyStatusChange(change)
        }
    }

    private fun applyStatusChange(change: CallStatusChange) {
        if (!onWaitingCallStatus(change)) applyOwnCallStatus(change)
    }

    /** A status about the call we are on. */
    private fun applyOwnCallStatus(change: CallStatusChange) {
        val current = _session.value ?: return
        if (!change.roomId.matches(current)) return

        // Our own status from another device: answered there means stop
        // ringing here, quietly.
        if (change.userId == current.selfUserId && change.status == CallStatus.InCall &&
            _phase.value == CallPhase.Incoming
        ) {
            // Leave first: accept() starts the join in parallel, so a
            // dismissal landing after it began would otherwise leave this
            // device an invisible, audible participant of a call now being
            // held on the phone.
            scope.launch { engine.leave() }
            finish(current, CallEndReason.PickedElsewhere)
            return
        }

        // The SFU marks every joiner `in_call` — the caller's own join
        // included — and the server broadcasts that straight back while the
        // callee is still ringing. Read as an answer it starts the duration,
        // stops the ringback and cancels the no-answer timeout with nobody on
        // the other end: the call sits on a running timer forever, and neither
        // a later decline nor the 60 s timeout can end it, because both are
        // gated on the phase this just moved. Ignore it — do NOT finish():
        // PickedElsewhere is right only for an incoming ring.
        //
        // A support call is the one case where our own id genuinely IS the far
        // end (`callSupport` rings our own primary device), so it keeps the old
        // behaviour. `receiverUserId` covers the same case when the 24x7 flag
        // was not carried — a redial rebuilds the request without it.
        val weAreAlsoTheCallee = current.is247Call ||
            (current.receiverUserId.isNotBlank() && current.receiverUserId == current.selfUserId)
        val ourOwnJoinEcho = change.userId == current.selfUserId && current.selfUserId.isNotBlank() &&
            change.status == CallStatus.InCall
        if (ourOwnJoinEcho && _phase.value == CallPhase.Outgoing && !weAreAlsoTheCallee) {
            ZillitLog.i(TAG) { "ignoring our own in_call while ${current.callUuid} is still ringing" }
            return
        }
        noteRingProgress(current, change)
        applyRosterChange(current, change)
    }

    /**
     * Lines 1 and 2: the caller's status line follows the callee, as Line 3's
     * ring events already make it — "Ringing…" once their device acknowledges,
     * "Busy" when it is on another call. The roster alone could only ever say
     * "Calling…" (the web's `receiverStatus`, callStore.js:4997-5016).
     *
     * The first `ringing` also re-arms the no-answer clock, once: it counted
     * from the dial, and a phone that took ten seconds to wake got a fifty
     * second ring. The web re-arms on the same moment.
     */
    private fun noteRingProgress(current: CallSession, change: CallStatusChange) {
        if (current.provider == CallProvider.LiveKit || _phase.value != CallPhase.Outgoing) return
        if (change.userId == current.selfUserId || change.userId.isBlank()) return
        CallRingState.of(change.status, busy = change.busy)?.let { moved ->
            _ringStatuses.update { it + (change.userId to moved) }
        }
        if (change.status == CallStatus.Ringing && !ringRearmed && !current.isCalendarCall) {
            ringRearmed = true
            startRingTimeout(CallTimeouts.OUTGOING_MS) { outgoingRangOut() }
        }
    }

    /** The row moves, and whatever the new status means for the call follows. */
    private fun applyRosterChange(current: CallSession, change: CallStatusChange) {
        // A `ringing` that lands after the answer — the phone's ringing-ack
        // overtaking its join — must not put a person whose media is live back
        // on the ring: their tile left the stage for a beat on every Line 1
        // answer, and anything timing the ring started counting (2026-10-05).
        val live = current.participants.firstOrNull { it.userId == change.userId }
            ?.takeIf { it.status.isConnected && mediaUidOf(current, it) in _media.value.peers }
        if (change.status == CallStatus.Ringing && live != null) {
            ZillitLog.i(TAG) { "late ringing for ${change.userId} ignored; their media is live" }
            return
        }
        _session.value = current.copy(
            participants = current.participants.withStatus(change.userId, change.status),
        )

        rememberIfConnected(change.userId, status = change.status)

        when (change.status) {
            CallStatus.InCall -> onSomeoneAnswered()
            CallStatus.Declined, CallStatus.NotAnswered, CallStatus.Left ->
                onSomeoneUnavailable(change.status, busy = change.busy)
            else -> Unit
        }
        checkRoomStillOccupied()
    }

    /** Adds someone to the monotonic answered set. See [allOthersGone]. */
    private fun rememberIfConnected(vararg keys: String, status: CallStatus) {
        if (!status.isConnected) return
        keys.filter(String::isNotBlank).forEach(everConnected::add)
    }

    /**
     * Ends a call everybody else has walked out of.
     *
     * Nothing did this before: a 1:1 desktop-to-desktop call whose other end
     * hung up left this side sitting in an empty room with the timer still
     * running and the microphone still live, until the user noticed. The
     * phones end it from four different places; this is the one that covers
     * the case the desktop can actually observe.
     *
     * Re-checked after a pause rather than acted on at once, because a roster
     * row and the media side do not move together — a peer can read as gone
     * for a moment in the middle of their own reconnection. Skipped entirely
     * while our own reconnect is in flight, which is the other way to see an
     * empty room that is not empty.
     */
    private fun checkRoomStillOccupied() {
        val grace = when {
            roomLooksEmpty() -> EMPTY_ROOM_GRACE_MILLIS
            line1MediaGone() -> MEDIA_GONE_GRACE_MILLIS
            else -> {
                emptyRoomCheck?.cancel()
                emptyRoomCheck = null
                return
            }
        }
        // A shorter verdict replaces a longer one already counting down.
        if (emptyRoomCheck?.isActive == true && grace >= emptyRoomGrace) return
        emptyRoomCheck?.cancel()
        emptyRoomGrace = grace
        emptyRoomCheck = scope.launch {
            delay(grace)
            // Asked a second time, and only the second answer is acted on.
            if (!roomLooksEmpty() && !line1MediaGone()) return@launch
            val session = _session.value ?: return@launch
            ZillitLog.i(TAG) { "everyone else left ${session.callUuid}; ending" }
            engine.leave()
            finish(session, CallEndReason.RemoteEnded)
        }
    }

    /**
     * Line 1's own witness: every other peer's media has left the SFU, whatever
     * the roster still says — Android's `UserOffline` rule, on a 5 s grace.
     *
     * On Line 1 being in the call IS being a peer in the room, so a row still
     * reading `in_call` with no peer behind it is stale. Rows go stale for good
     * there: a phone that joined under a different id than its row carries
     * leaves under that id too, the `left` the SFU writes matches no row, and
     * the call never ended (2026-10-05). The longer grace gives a peer's own
     * reconnect a moment, which the roster-backed rule does not need to.
     *
     * Not while anyone is still ringing — Android's condition too — and only
     * after media was seen, so a room nobody has joined yet is not an ending.
     */
    private fun line1MediaGone(): Boolean {
        val current = _session.value ?: return false
        return current.provider == CallProvider.Mediasoup &&
            couldEndNow(current) &&
            sawRemoteMedia &&
            _media.value.peers.isEmpty() &&
            current.participants.none { it.userId != current.selfUserId && it.status == CallStatus.Ringing }
    }

    /** In the call, not reconnecting, and not a calendar room — see [roomLooksEmpty]. */
    private fun couldEndNow(current: CallSession): Boolean =
        _phase.value == CallPhase.InCall && !reconnect.isArmed && !current.isCalendarCall

    /**
     * Whether this device appears to be the last one on a call it is still in.
     *
     * False while our own media is reconnecting: an outage silences the roster
     * and the peer count together, which is indistinguishable from everybody
     * having walked out and is not it.
     */
    private fun roomLooksEmpty(): Boolean {
        val current = _session.value ?: return false
        // Being first into a calendar room is normal rather than an ending:
        // someone who opens it five minutes early would otherwise be hung up
        // on two seconds later, and the room is the event's, not theirs.
        val peers = _media.value.peers.size
        return couldEndNow(current) && (
            allOthersGone(current.participants, everConnected, peers) ||
                callDeserted(sawRemoteMedia, peers, current.participants, current.selfUserId)
            )
    }

    private fun onSomeoneAnswered() {
        if (_phase.value == CallPhase.Outgoing) {
            cancelRingTimeout()
            _phase.value = CallPhase.InCall
            // The media uid was issued while this call was still ringing, so
            // this is the first moment it can honestly be mirrored.
            announceMediaUid()
        }
    }

    /**
     * A 1:1 call is over when the one other person is out; a group call only
     * ends when the server says so — someone declining while three others
     * talk is not an ending.
     */
    private fun onSomeoneUnavailable(status: CallStatus, busy: Boolean = false) {
        val current = _session.value ?: return
        if (_phase.value != CallPhase.Outgoing) return
        // A group rings on until every invitee has said no — the web's
        // `_declinedUserIds` rule (callStore.js:5030-5085); one refusal is not an ending.
        val everyoneRefused = current.participants
            .filter { it.userId != current.selfUserId && it.status != CallStatus.Caller }
            .let { others -> others.isNotEmpty() && others.all { it.status in REFUSED } }
        if (current.mode != CallMode.Private && !everyoneRefused) return
        // "<name> is busy", not "declined" — the web words them apart (callStore.js:5092).
        val reason = when {
            busy -> CallEndReason.Busy
            status == CallStatus.Declined -> CallEndReason.Declined
            else -> CallEndReason.Timeout
        }
        scope.launch { engine.leave() }
        finish(current, reason)
    }

    /**
     * Gives every ring on the call [CallTimeouts.OUTGOING_MS] to be answered.
     *
     * Whoever is still ringing after that moves to Left / Declined as "No
     * answer" — the web's 60 s timer on a mid-call add (`AddUserDrawer.jsx:469`)
     * and Android's ring expiry, which demotes any row still ringing to
     * `not_answered`. Without it a phone that never picked up sat in the
     * Ringing section for the rest of the call.
     *
     * Keyed off the roster itself, so every way a row starts ringing — the
     * first invite, an Add, a re-ring — gets its own clock, and a row that
     * stops ringing for any reason drops it. Line 3 is left out: its server
     * runs the ring timeout and pushes the missed state itself.
     */
    private suspend fun watchInviteExpiry() {
        _session
            .map { session ->
                session?.participants.orEmpty()
                    .filter { it.status == CallStatus.Ringing && it.userId != session?.selfUserId }
                    .map(CallParticipant::userId)
                    .filter(String::isNotBlank)
                    .toSet()
            }
            .distinctUntilChanged()
            .collect { ringing ->
                (inviteExpiry.keys - ringing).forEach { inviteExpiry.remove(it)?.cancel() }
                (ringing - inviteExpiry.keys).forEach { userId ->
                    inviteExpiry[userId] = scope.launch {
                        delay(CallTimeouts.OUTGOING_MS)
                        inviteExpiry.remove(userId)
                        inviteRangOut(userId)
                    }
                }
            }
    }

    /**
     * Every roster change, as ids and statuses with the uid each row binds to.
     *
     * Ids only, never names. Without this a Line 1 row and the stream it should
     * own could disagree on who someone is and the log could not say so: the
     * `call:update` payloads are not logged, and the Guest-tile bug of
     * 2026-10-05 had to be argued from the code instead of read.
     */
    private suspend fun logRosterChanges() {
        _session
            .map { session ->
                session?.participants.orEmpty().joinToString { row ->
                    "${row.userId}=${row.status.wire}#${mediaUidOf(session ?: return@joinToString "", row)}"
                }
            }
            .distinctUntilChanged()
            .collect { roster -> if (roster.isNotEmpty()) ZillitLog.i(TAG) { "roster: $roster" } }
    }

    private fun inviteRangOut(userId: String) {
        val current = _session.value ?: return
        // While our own call is still ringing out, its ring timeout owns the
        // ending; Line 3's server owns its rings.
        if (_phase.value != CallPhase.InCall || current.provider == CallProvider.LiveKit) return
        val row = current.participants.firstOrNull { it.userId == userId && it.status == CallStatus.Ringing } ?: return
        // Their media is here: they answered and nothing said so. In, not out.
        adoptAnswersFromMedia()
        if (_session.value?.participants?.any { it.userId == userId && it.status == CallStatus.Ringing } != true) return
        ZillitLog.i(TAG) { "invite to $userId rang out; not answered" }
        _session.value = current.copy(
            participants = current.participants.withStatus(userId, CallStatus.NotAnswered),
        )
        if (addedByUs.remove(userId)) {
            scope.launch {
                // The seed row we wrote, retired the same way — see [withdrawRing].
                plane.updateUserFields(
                    current,
                    row.deviceId.ifBlank { userId },
                    mapOf(FIELD_CURRENT_STATUS to CallStatus.NotAnswered.wire),
                )
                if (!current.isCalendarCall) api.logMissedInvite(current, userId, row.deviceId)
            }
        }
        checkRoomStillOccupied()
    }

    private suspend fun listenEnded() {
        val events = listOf(ZillitSocketEvents.Calls.Ended, ZillitSocketEvents.Calls.GroupCallEnded)
        bus.onAny(events).collect { message ->
            val ended = message.payload?.let(::readCallEnded) ?: return@collect
            dismissWaitingFor(ended.roomId)?.let {
                toastMissed(it)
                return@collect
            }
            val current = _session.value ?: return@collect
            if (ended.roomId.matches(current)) {
                engine.leave()
                finish(current, CallEndReason.RemoteEnded)
            }
        }
    }

    private suspend fun listenTimeout() {
        bus.on(ZillitSocketEvents.Calls.Timeout).collect { message ->
            val ended = message.payload?.let(::readCallEnded) ?: return@collect
            dismissWaitingFor(ended.roomId)?.let {
                toastMissed(it)
                return@collect
            }
            val current = _session.value ?: return@collect
            if (ended.roomId.matches(current) && _phase.value != CallPhase.InCall) {
                engine.leave()
                finish(current, CallEndReason.Timeout)
            }
        }
    }

    private suspend fun listenHandoffEvict() {
        bus.on(ZillitSocketEvents.Calls.HandoffEvict).collect { message ->
            val evict = message.payload?.let(::readHandoffEvict) ?: return@collect
            val current = _session.value ?: return@collect
            if (!evict.roomId.matches(current)) return@collect
            // The call is alive on the user's other device. Leave the media,
            // do NOT end the call server-side — it is not ours any more.
            engine.leave()
            finish(current, CallEndReason.PickedElsewhere)
        }
    }

    // ── Firestore plane ─────────────────────────────────────────────────

    private fun watchPlane(session: CallSession) {
        planeWatch?.cancel()
        planeWatch = scope.launch {
            plane.watch(session).collect { event -> onPlaneEvent(event) }
        }
    }

    /**
     * The mirror speaks: same reducer as the socket, so whichever channel
     * delivers a status first wins and the second is a no-op.
     */
    private fun onPlaneEvent(event: PlaneEvent) {
        val current = _session.value ?: return
        when (event) {
            is PlaneEvent.Ended -> {
                if (event.callUuid == current.callUuid) {
                    scope.launch { engine.leave() }
                    finish(current, CallEndReason.RemoteEnded)
                }
            }
            is PlaneEvent.UserStatus -> onPlaneStatus(current, event)
            is PlaneEvent.UserFlags -> onPlaneFlags(current, event)
        }
    }

    /**
     * Sharing and raised hands, off the roster row.
     *
     * This is the only way the desktop can learn either on the Agora line.
     * A screen share in particular is invisible in the media stream — the
     * sharer publishes it on their own uid with the camera unpublished, so a
     * receiver sees an ordinary video track and nothing marks it as a screen.
     * The phones read this same flag for exactly that reason.
     */
    private fun onPlaneFlags(session: CallSession, event: PlaneEvent.UserFlags) {
        // Our own row, echoed back by the two-second poll: the local state is
        // already ahead of it and re-applying would fight the user.
        if (event.deviceId == selfDeviceId()) return

        // The uid on the row is what ties a roster entry to a picture. Zero
        // means they have not joined media yet — except on Line 1, where no
        // server issues one and the desktop numbers peers by a stable hash of
        // their user id; derive the same number so the flag finds its tile.
        val uid = when {
            event.agoraUid != 0 -> event.agoraUid
            session.provider == CallProvider.Mediasoup && event.userId.isNotBlank() ->
                mediasoupUidOf(event.userId)
            else -> 0
        }
        // Lines 1 and 3 learn who is in the room from the SFU alone. A row's
        // flags folded through the reducer CREATE a peer for whoever the row
        // names — and every row has flags: the seed written for someone added
        // mid-call (who may never answer), and the row of someone who already
        // left. Each became a stream nobody owned, drawn as "Guest" or as the
        // departed person's face, and kept the call from ever reading empty
        // (2026-10-05). There, a row may only describe a peer that is live.
        val mediaFromRoom = session.provider == CallProvider.Mediasoup || session.provider == CallProvider.LiveKit
        // On Line 2 the row may still seed a peer — it is the only place a
        // standing mute lives there — but only a row of someone in the call: a
        // departed person's row re-created them the same way.
        val rowInCall = session.participants.any {
            it.status.isConnected && (it.deviceId == event.deviceId || it.userId == event.userId)
        }
        val mayDescribe = uid in _media.value.peers || (!mediaFromRoom && rowInCall)
        rememberPlaneName(uid, event.userId, event.userName)
        if (uid != 0 && mayDescribe) {
            _media.value = _media.value.reduce(CallEngineEvent.PeerScreenShare(uid, event.sharing))
            // Mute and camera ride the same row, and on the Agora line the row
            // is the only place they exist for someone who was already muted
            // when this device joined: the SDK reports mute by publishing and
            // unpublishing, which is a change, so a standing state produces no
            // event at all. Folded in through the same reducer the engine
            // uses, so a live event simply overwrites this the moment one
            // arrives — the row seeds, it does not govern.
            _media.value = _media.value
                .reduce(CallEngineEvent.PeerAudioMuted(uid, event.muted))
                .reduce(CallEngineEvent.PeerVideoMuted(uid, !event.hasVideo))
        }

        val updated = session.participants
            .withHand(event.deviceId, event.handRaised)
            .healedFromPlane(event.deviceId, event.userId, event.userName, event.agoraUid)
        if (updated != session.participants) {
            _session.value = _session.value?.copy(participants = updated)
        }

        recorder.onRemoteFlag(
            key = event.deviceId,
            recording = event.recording,
            name = event.userName.ifBlank {
                updated.firstOrNull { it.deviceId == event.deviceId }?.name.orEmpty()
            },
        )
    }

    private fun onPlaneStatus(current: CallSession, event: PlaneEvent.UserStatus) {
        rememberIfConnected(event.userId, event.deviceId, status = event.status)
        // Our own row, written by another platform: the same person answered
        // or declined on their phone. Stop this ring quietly.
        val self = event.deviceId == selfDeviceId() ||
            (event.userId.isNotBlank() && event.userId == current.selfUserId)
        if (self) {
            // Our own row is never another participant, so this returns either
            // way. `updated_from` only decides whether it was our *other*
            // device answering: a row we wrote ourselves comes back on the
            // next 2 s poll, and reducing that would have this device tear
            // down the call it just accepted as "answered elsewhere".
            if (_phase.value == CallPhase.Incoming && event.answeredOnAnotherDevice()) {
                scope.launch { engine.leave() }
                finish(current, CallEndReason.PickedElsewhere)
            }
            return
        }
        val userId = event.userId.ifBlank {
            current.participants.firstOrNull { it.deviceId == event.deviceId }?.userId ?: return
        }
        applyStatusChange(
            CallStatusChange(
                roomId = current.roomId.ifBlank { current.callUuid },
                userId = userId,
                status = event.status,
                projectId = current.projectId,
            ),
        )
    }

    // ── Engine reactions ────────────────────────────────────────────────

    private fun onConnectionChanged(state: EngineConnection) {
        if (state == EngineConnection.Connected && joinStartedAtMillis > 0) {
            // Once per call: a ring-warmed room connected during the ring and
            // the page has already sent this, so the first writer wins rather
            // than two lines disagreeing.
            diagnostics.logOnce(
                "warm_connect",
                mapOf("ok" to true, "ms" to now() - joinStartedAtMillis),
            )
        }
        // Not between calls: a failed call's page reports its socket closing
        // AFTER the teardown, and a clock armed by that ran on into the next
        // call and ended it 45 s later.
        if (_session.value != null) reconnect.onConnectionChanged(state)
    }

    private suspend fun listenEngine() {
        engine.events.collect { event ->
            // Every event folds into the media picture, including the ones no
            // phase transition cares about — the speaking rings and the link
            // pips are exactly those, and dropping them is what left the UI
            // with nothing live to show.
            foldMedia(event)
            when (event) {
                is CallEngineEvent.Joined -> onMediaJoined(event.uid)
                CallEngineEvent.TokenExpiring ->
                    ZillitLog.w(TAG) { "RTC token entering its grace period" }
                // Nothing to renew with, so end it deliberately instead of
                // leaving a window open on a call whose media has stopped —
                // the phones tear down here too. Reported as an error so the
                // user is told, rather than the call simply vanishing.
                CallEngineEvent.TokenExpired -> fail("call token expired")
                is CallEngineEvent.ConnectionChanged -> onConnectionChanged(event.state)
                // Diagnostics the page measured, and our own speaking edge.
                // Neither touches the call; both are dropped outside one.
                is CallEngineEvent.Telemetry -> diagnostics.log(event.event, event.fields)
                is CallEngineEvent.SelfSpeaking -> diagnostics.selfSpeaking(event.speaking, event.level)
                is CallEngineEvent.ScreenShare -> {
                    // The phones read `screenShare` off the roster row to
                    // badge the sharer and pin their tile; mirrored only once
                    // the engine confirms, so a cancelled picker publishes
                    // nothing.
                    mirrorMediaState(mapOf(FIELD_SHARING_WIRE to event.sharing))
                }
                is CallEngineEvent.Devices -> audio.onEngineDevices(event)
                is CallEngineEvent.DeviceMissing -> audio.forget(event.kind)
                is CallEngineEvent.Failed -> fail(event.message)
                CallEngineEvent.SessionReplaced -> onSessionReplaced()
                is CallEngineEvent.Degraded -> onDegraded(event)
                else -> onPeerEvent(event)
            }
        }
    }

    /**
     * A chat line or reaction the SFU relayed (Line 1). Members receive the
     * socket relay's copy too; the id makes the two one. Our own never comes
     * back from the SFU, but a stray echo is dropped all the same.
     */
    private fun onRoomData(payload: String) {
        val incoming = runCatching { Json.parseToJsonElement(payload) }.getOrNull()?.let(::readInCallData) ?: return
        val self = _session.value?.selfUserId.orEmpty()
        if (self.isNotBlank() && incoming.fromUserId == self) return
        inCall.receive(incoming)
    }

    /**
     * Our other device took this call over. It is alive there, so nothing is
     * sent — no `left`, no hang-up — this device simply lets go of it, as the
     * web does on `SessionReplaced` rather than calling it a failure.
     */
    private fun onSessionReplaced() {
        val current = _session.value ?: return
        ZillitLog.i(TAG) { "call ${current.callUuid} taken over by another device" }
        scope.launch { engine.leave() }
        finish(current, CallEndReason.PickedElsewhere)
    }

    /** Folds an engine event into the media picture, noting the first remote media seen. */
    private fun foldMedia(event: CallEngineEvent) {
        _media.value = _media.value.reduce(event)
        if (_media.value.peers.isNotEmpty()) sawRemoteMedia = true
        when (event) {
            is CallEngineEvent.PeerJoined -> {
                if (event.withMedia) mediaUids += event.uid
                // Line 1 logs its own joins with the peer id; Line 2 has only the uid.
                if (_session.value?.provider == CallProvider.Agora) {
                    ZillitLog.i(TAG) { "line 2 peer joined uid=${event.uid} named=${event.uid in planeNames}" }
                }
                labelPeersFromPlane()
                adoptAnswersFromMedia()
                checkRoomStillOccupied()
            }
            is CallEngineEvent.PeerLeft -> mediaUids -= event.uid
            else -> Unit
        }
    }

    /** The uid [row]'s stream arrives under: hashed on Lines 1 and 3, issued on Line 2. */
    private fun mediaUidOf(session: CallSession, row: CallParticipant): Int =
        if (session.provider == CallProvider.Agora) row.numericUid else mediasoupUidOf(row.userId)

    /**
     * Line 2: who each media uid is, as the rows say — every row, not only the
     * roster's. A phone whose row carries a different user id than the call's
     * roster (a project-scoped id, say) never healed onto any roster row, so
     * its stream had no name and drew as "Guest". The row names it anyway: the
     * stage reads [MediaPeer.identity]/[MediaPeer.displayName] for a stream no
     * row claims.
     */
    private val planeNames = mutableMapOf<Int, Pair<String, String>>()

    private fun rememberPlaneName(uid: Int, userId: String, name: String) {
        if (uid == 0 || (userId.isBlank() && name.isBlank())) return
        planeNames[uid] = userId to name
        labelPeersFromPlane()
    }

    /** Stamps [planeNames] onto live peers that the room itself did not name. */
    private fun labelPeersFromPlane() {
        if (planeNames.isEmpty()) return
        val media = _media.value
        var changed = false
        val peers = media.peers.mapValues { (uid, peer) ->
            val (userId, name) = planeNames[uid] ?: return@mapValues peer
            if (peer.identity.isNotBlank() && peer.displayName.isNotBlank()) return@mapValues peer
            changed = true
            peer.copy(identity = peer.identity.ifBlank { userId }, displayName = peer.displayName.ifBlank { name })
        }
        if (changed) _media.value = media.copy(peers = peers)
    }

    /** See [answeredByMedia]: a stream arriving is an answer nobody announced. */
    private fun adoptAnswersFromMedia() {
        val current = _session.value ?: return
        if (_phase.value == CallPhase.Ending) return
        val before = current.participants
        // Line 1: media, not a join — a ringing phone is already in the room.
        val answered = if (current.provider == CallProvider.Mediasoup) mediaUids else _media.value.peers.keys
        val after = before.answeredByMedia(current.provider, answered, current.selfUserId)
        if (after === before) return
        _session.value = current.copy(participants = after)
        after.filter { it.status.isConnected && before.first { b -> b.userId == it.userId }.status != it.status }
            .forEach {
                ZillitLog.i(TAG) { "${it.userId}'s media arrived; in the call" }
                rememberIfConnected(it.userId, it.deviceId, status = CallStatus.InCall)
                addedByUs.remove(it.userId)
            }
        onSomeoneAnswered()
        checkRoomStillOccupied()
    }

    /** What the other people on the call did, as the engine saw it. */
    private fun onPeerEvent(event: CallEngineEvent) {
        when (event) {
            // A peer's media going away is the other half of "is anyone
            // still here": the roster can lag, and on Line 1 a departure
            // reaches this side as a closed consumer well before any row
            // moves.
            is CallEngineEvent.PeerLeft -> checkRoomStillOccupied()
            is CallEngineEvent.RoomData -> onRoomData(event.payload)
            is CallEngineEvent.PeerHand -> onPeerHand(event)
            is CallEngineEvent.ChatReceived -> onChatReceived(event)
            is CallEngineEvent.PeerRecording -> onPeerRecording(event)
            is CallEngineEvent.RecordingBy -> onRecordingBy(event.userId)
            // The SFU muted us (the host's "mute everyone"): the button follows.
            is CallEngineEvent.SelfMicMuted -> if (_micMuted.value != event.muted) _micMuted.value = event.muted
            is CallEngineEvent.RecordingSaved -> onRecordingSaved(event)
            is CallEngineEvent.PinRequested -> _pinRequests.tryEmit(event.key)
            else -> Unit
        }
    }

    /**
     * A finished recording: filed on this machine, and on its way to the
     * conversation.
     *
     * Sending is the point — the phones and the web both post the recording
     * into the call's chat when it stops, and a recording only the recorder
     * can hear is not what a crew means by "record this call". The local file
     * stays where it was saved: this is a desktop, and a file the user was
     * just told the path of should still be there when they go looking.
     */
    private fun onRecordingSaved(event: CallEngineEvent.RecordingSaved) {
        _toasts.tryEmit(str(S.desktop_call_recording_saved_to, event.path))
        val sender = share ?: return
        // The call the recording belongs to, which by now may not be the live
        // one — see CallRecordingControl.recordedSession.
        val session = recorder.recordedSession ?: return
        val targets = recordingTargets(session, selfUserId())
        if (targets.isEmpty()) {
            ZillitLog.w(TAG) { "recording not sent: no chat recipient on ${session.callUuid}" }
            _toasts.tryEmit(str(S.desktop_call_recording_nobody_to_send))
            return
        }
        scope.launch {
            sender.share(
                CallRecording(
                    path = event.path,
                    contentType = event.contentType,
                    durationMillis = event.durationMillis,
                ),
                targets,
            ).onSuccess {
                _toasts.tryEmit(str(S.desktop_call_recording_sent_to_chat))
            }.onFailure { error ->
                ZillitLog.w(TAG) { "recording not sent: $error" }
                _toasts.tryEmit(str(S.desktop_call_recording_send_failed))
            }
        }
    }

    private fun onMediaJoined(uid: Int) {
        val current = _session.value ?: return
        // A join that lands while we are already leaving is not an arrival.
        if (_phase.value == CallPhase.Ending) return
        recordMediaUid(current, uid)
        // A calendar room has nobody to answer it: walking in IS the
        // connection, and with no ring timeout armed nothing else would ever
        // move the call out of Outgoing.
        val walkedIn = _phase.value == CallPhase.Outgoing && current.isCalendarCall
        if (_phase.value == CallPhase.Incoming || walkedIn) {
            cancelRingTimeout()
            _phase.value = CallPhase.InCall
        }
        // Hydrate the roster from the server's snapshot: everything that
        // happened before we subscribed is invisible on the socket.
        if (current.provider == CallProvider.LiveKit) {
            line3?.refreshRoster(current.callUuid, current.callerUserId)
            line3InCall.onJoined()
            return
        }
        scope.launch {
            api.callDump(
                roomId = current.roomId.ifBlank { current.callUuid },
                projectId = current.projectId.takeIf(String::isNotBlank),
            ).onSuccess { fetched ->
                if (fetched.isEmpty()) return@onSuccess
                val base = _session.value ?: return@onSuccess
                _session.value = base.copy(participants = mergeRoster(base.participants, fetched))
                // Dialling into a room that is already live delivers no status
                // TRANSITION — everyone was `in_call` before we arrived — so
                // nothing else would take this call out of Outgoing, and at
                // T+60s the ring timeout would end a conversation the user is
                // audibly part of, for everyone in it.
                if (_session.value?.participants.orEmpty().any { it.isSomeoneElseLive(base) }) {
                    onSomeoneAnswered()
                }
            }
        }
    }

    /**
     * Records the uid the media stack actually got, and mirrors it.
     *
     * A call that arrives without a uid of ours is joined with none, and the
     * SDK issues one — so the number in the invite is not the number on the
     * wire. Every other platform maps roster rows to media streams by
     * `agora_uid`, and a row carrying the wrong one (or none) shows this
     * device's audio as nobody's. Re-announcing is a masked merge, so it
     * costs one field and disturbs nothing else on the row.
     */
    private fun recordMediaUid(session: CallSession, uid: Int) {
        if (uid == 0 || uid == session.localUid) return
        _session.value = session.copy(localUid = uid)
        // Only once we are actually in the call. The row's status belongs to
        // the accept and hang-up paths, and re-stating `in_call` for an
        // outgoing call still ringing would announce an answer nobody gave —
        // so a caller's uid waits for [onSomeoneAnswered] instead. Ending is
        // excluded for the mirror image of that: hang-up suspends through
        // four round trips, and a Joined already queued behind them would
        // re-stamp `in_call` over the `leave` we are in the middle of writing.
        if (_phase.value != CallPhase.Outgoing && _phase.value != CallPhase.Ending) {
            announceMediaUid()
        }
    }

    /** Mirrors our media uid onto our own row, leaving its other fields alone. */
    private fun announceMediaUid() {
        val session = _session.value ?: return
        if (session.localUid == 0) return
        scope.launch {
            plane.announceSelf(
                session,
                CallStatus.InCall,
                // A STRING, because that is what iOS writes ("\(agoraID)")
                // and what the fleet is therefore full of. It is NOT the
                // catastrophe an earlier comment here claimed: iOS's live
                // reader is a tolerant dictionary parser that accepts String,
                // Int64, Int and NSNumber alike, and the strict `AgoraUserModel`
                // it cited is a legacy stub that never decodes Firestore. So a
                // numeric value would be read correctly too — this is written
                // as a string for consistency with the other clients, not to
                // avoid a row being discarded.
                mapOf("agora_uid" to session.localUid.toString()),
            )
        }
    }

    /**
     * Our row, settled by one of our *other* devices.
     *
     * A row this desktop wrote is excluded by `updated_from`: accept() mirrors
     * `in_call` and the 2 s poll hands it straight back, which would otherwise
     * read as somebody else having answered.
     */
    private fun PlaneEvent.UserStatus.answeredOnAnotherDevice(): Boolean =
        updatedFrom != PLATFORM_SELF &&
            (status == CallStatus.InCall || status == CallStatus.Declined)

    /** Somebody who is not this device, already connected — the room is live. */
    private fun CallParticipant.isSomeoneElseLive(session: CallSession): Boolean =
        status.isConnected && deviceId != selfDeviceId() && userId != session.selfUserId

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun joinMedia(given: CallSession) {
        if (joining) return
        joining = true
        val session = withKnownSfuHost(given)
        if (!session.isJoinable) {
            ZillitLog.w(TAG) {
                "call ${session.callUuid} on ${session.provider.wire} cannot be joined " +
                    "with what the invite carried; staying signalling-only"
            }
            // An attempted join is not an in-flight one: holding the latch
            // would refuse the retry once a token or the engine does arrive.
            joining = false
            return
        }
        if (!engine.initialize()) {
            // First-ever call may catch Chromium mid-download. The signalling
            // call is still real — the far side rings and statuses flow — so
            // degrade rather than hang up on our own user.
            ZillitLog.w(TAG) { "media engine unavailable; call continues signalling-only" }
            joining = false
            return
        }
        audio.restoreBeforeJoin()

        // When the room connect began, for `warm_connect`. On a ring-warmed
        // call the connect already happened during the ring and the page owns
        // that line, so this one is `logOnce` and the first writer wins.
        joinStartedAtMillis = now()
        // A fresh join starts with no outage, whatever the last call left.
        reconnect.cancel()
        engine.join(session.toJoin(selfDeviceId().orEmpty(), selfName().orEmpty()))
        // Publishes the lists so a picker opened mid-call has something to
        // draw without waiting for a hot-plug event.
        audio.refresh()
    }

    /**
     * Ends a call whose media never comes back.
     *
     * The SDK retries a dropped connection on its own and usually wins, so a
     * blip must not end anything — but it retries indefinitely, and a laptop
     * that lost Wi-Fi for good otherwise keeps a dead call on screen with a
     * running timer, its microphone indicator lit, and every peer still
     * counting it as present. The grace period is generous enough to cover a
     * network change and short enough that nobody talks to a room that
     * stopped hearing them minutes ago.
     */
    private fun startRingTimeout(afterMillis: Long, onExpiry: () -> Unit) {
        cancelRingTimeout()
        ringTimeout = scope.launch {
            delay(afterMillis)
            onExpiry()
        }
    }

    private fun cancelRingTimeout() {
        ringTimeout?.cancel()
        ringTimeout = null
    }

    private fun outgoingRangOut() {
        val current = _session.value ?: return
        // The timeout fires into a call that may have been answered a
        // fraction of a second earlier: the socket collector flips the phase
        // while this body is parked in its first round trip, and without the
        // re-check the teardown completes over a live call and reports "No
        // answer" for it.
        if (_phase.value != CallPhase.Outgoing) return
        if (current.provider == CallProvider.LiveKit) {
            scope.launch {
                engine.leave()
                line3?.cancel(current.callUuid, current.projectId.takeIf(String::isNotBlank), current.selfUserId)
                finish(current, CallEndReason.Timeout)
            }
            return
        }
        if (current.provider == CallProvider.Mediasoup) {
            // The same withdrawal a hang-up sends: the backend logs every
            // unanswered callee's miss itself, so none is logged here twice.
            scope.launch {
                engine.leave()
                sendFinalStatus(current, CallStatus.Left, word = LINE1_RESPONSE_CANCELLED)
                finish(current, CallEndReason.Timeout)
            }
            return
        }
        scope.launch {
            engine.leave()
            val projectId = current.projectId.takeIf(String::isNotBlank)
            // Everyone still ringing EXCEPT us. The caller's own roster row is
            // `caller`, so without the self-exclusion the person who placed
            // the call gets a missed-call entry for placing it.
            val me = selfDeviceId().orEmpty()
            val missed = current.participants
                .filter { it.deviceId.isNotBlank() && it.deviceId != me && it.userId != current.selfUserId }
                .filter {
                    it.status == CallStatus.Ringing ||
                        it.status == CallStatus.NotAnswered ||
                        it.status == CallStatus.Caller
                }
                .map(CallParticipant::deviceId)
                .distinct()
            // No roster came back: the device we dialled. A row that exists
            // and was excluded above declined, and must not be re-added as a
            // miss.
            val ids = missed.ifEmpty {
                current.receiverDeviceId
                    .takeIf { it.isNotBlank() && current.participants.none { p -> p.deviceId == it } }
                    ?.let(::listOf)
                    .orEmpty()
            }
            api.logMissed(current, ids, projectId)
            api.endCall(
                callUuid = current.callUuid,
                deviceId = selfDeviceId().orEmpty(),
                projectId = current.projectId.takeIf(String::isNotBlank),
                provider = current.provider,
            )
            finish(current, CallEndReason.Timeout)
        }
    }

    /**
     * Both of us dialled at once and the backend handed back the other
     * person's call: answer it rather than ring them a second time. They hear
     * our `incall` and both sides connect with no ring — the web's
     * mutual-connect. Without this their ring reached us mid-dial and the
     * busy path declined the very call we wanted.
     */
    private fun answerCrossedCall(theirs: CallSession, displayName: String, projectId: String?) {
        ZillitLog.i(TAG) { "${theirs.callUuid} is already ringing us; answering it" }
        cancelRingTimeout()
        val session = theirs.copy(
            crossedCall = false,
            callerName = theirs.callerName.ifBlank { displayName },
            title = displayName.ifBlank { theirs.title },
            projectId = projectId?.takeIf(String::isNotBlank) ?: theirs.projectId,
        )
        _session.value = session
        _phase.value = CallPhase.Incoming
        watchPlane(session)
        accept()
    }

    /** A call the server created after the user had already cancelled it — withdrawn, never adopted. */
    private suspend fun abandonLateCall(session: CallSession) {
        ZillitLog.i(TAG) { "create-call landed after cancel; ending ${session.callUuid}" }
        if (session.provider == CallProvider.Mediasoup) {
            sendFinalStatus(session, CallStatus.Left, word = LINE1_RESPONSE_CANCELLED)
        } else {
            api.endCall(
                callUuid = session.callUuid,
                deviceId = selfDeviceId().orEmpty(),
                projectId = session.projectId.takeIf(String::isNotBlank),
                provider = session.provider,
            )
        }
    }

    private fun incomingRangOut() {
        val current = _session.value ?: return
        // Answered a moment before the timeout fired: accept() has already
        // moved the phase, and reporting "not answered" now would retract it.
        if (_phase.value != CallPhase.Incoming) return
        if (current.provider == CallProvider.LiveKit) {
            // The server logs the miss itself; nothing to send on this side.
            finish(current, CallEndReason.Timeout)
            return
        }
        scope.launch {
            sendFinalStatus(current, CallStatus.NotAnswered)
            // Declined and Left already mirror beside their REST call; this
            // one did not, so a rung-out desktop stayed "ringing" on every
            // other participant's roster for the life of the call document.
            plane.announceSelf(current, CallStatus.NotAnswered)
        }
        finish(current, CallEndReason.Timeout)
    }

    /**
     * A status the far side must see even if our scope is being torn down —
     * the "caller cancelled but callee still rings" class of bug is exactly a
     * final status dying with the coroutine that sent it.
     */
    private suspend fun sendFinalStatus(
        session: CallSession,
        status: CallStatus,
        word: String = status.line1ResponseWord,
    ) =
        withContext(NonCancellable) {
            if (session.provider == CallProvider.LiveKit) return@withContext
            respond(session, status, word)
        }

    /**
     * Our `call-response` for [session], Line 1 socket-first as the web sends
     * it (`emitCallResponse`, cncEmit.js:337): one hop to the CNC, which also
     * tells this user's other devices, and REST only when the socket is down
     * or silent. Lines 2 keeps the REST post it always made.
     *
     * Returns the server's message — blank on a plain success — so a caller
     * can tell `call_ended` apart from delivered.
     */
    private suspend fun respond(
        session: CallSession,
        status: CallStatus,
        word: String = status.line1ResponseWord,
    ): String {
        val roomId = session.roomId.ifBlank { session.callUuid }
        if (session.provider == CallProvider.Mediasoup && roomId.isNotBlank() && session.selfUserId.isNotBlank()) {
            val body = callResponseEnvelope(roomId, status, session.selfUserId, word)
            val ack = withTimeoutOrNull(SOCKET_ACK_MILLIS) {
                bus.emitForAck(ZillitSocketEvents.Calls.Response, body, JsonObject.serializer())
            }
            val reply = ack?.getOrNull()?.let(::ackObject)
            if (reply != null) {
                val message = (reply["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val refused = (reply["success"] as? JsonPrimitive)?.booleanOrNull == false
                // Delivered, or refused for a reason worth reading; anything
                // else is a socket that could not say, and REST is asked.
                if (!refused || message.isNotBlank()) return message
            }
        }
        val result = api.sendCallResponse(
            roomId = roomId,
            status = status,
            fromUserId = session.selfUserId,
            projectId = session.projectId.takeIf(String::isNotBlank),
            word = word,
        )
        return result.errorOrNull()?.let { it.technical ?: it.userMessage }.orEmpty()
    }

    /** A socket ack as one object: the CNC answers either bare or wrapped in an array. */
    private fun ackObject(element: JsonElement): JsonObject? =
        element as? JsonObject ?: (element as? JsonArray)?.firstOrNull() as? JsonObject

    private fun fail(message: String) {
        val current = _session.value
        ZillitLog.w(TAG) { "call failed: $message" }
        if (current == null) {
            reset()
            return
        }
        // Tear down the way every other end-path does. Media nobody left keeps
        // publishing — the microphone indicator stays lit until the next call —
        // and a peer never told we are gone counts this device as an active
        // participant forever, with their own ring timeout already cancelled.
        val announce = _phase.value != CallPhase.Idle && current.callUuid.isNotBlank() &&
            current.provider != CallProvider.LiveKit
        scope.launch {
            engine.leave()
            if (current.provider == CallProvider.LiveKit && current.callUuid.isNotBlank()) line3?.leave(current)
            if (announce) {
                plane.announceSelf(current, CallStatus.Left)
                sendFinalStatus(current, CallStatus.Left)
            }
        }
        finish(current, CallEndReason.Error)
    }

    private fun finish(session: CallSession, reason: CallEndReason) {
        // Teardown runs across several round trips, and the user can start a
        // new call in that time. Ending only the call this coroutine was
        // about stops a stale timeout from resetting its successor.
        val live = _session.value
        if (live != null && live.callUuid.isNotBlank() && live.callUuid != session.callUuid) return
        ZillitLog.i(TAG) { "call ${session.callUuid} ended: $reason (${session.provider.wire}, was ${_phase.value})" }
        cancelRingTimeout()
        // `disconnect` first, then the window shuts — a diagnostic sent after
        // the close has no call id and is dropped, every time.
        diagnostics.endCall(reason.name)
        // Declined, cancelled, rang out, answered elsewhere or simply over:
        // the warm room has nothing left to become. Clearing the claim with it
        // means a re-invite to the same call warms again rather than silently
        // taking the slow path.
        engine.dropPrewarm(session.callUuid)
        _ended.tryEmit(CallEndEvent(session, reason))
        reset()
    }

    private fun reset() {
        planeWatch?.cancel()
        planeWatch = null
        _session.value = null
        _phase.value = CallPhase.Idle
        _micMuted.value = false
        _cameraOn.value = false
        emptyRoomCheck?.cancel()
        emptyRoomCheck = null
        inviteExpiry.values.forEach(Job::cancel)
        inviteExpiry.clear()
        addedByUs.clear()
        mediaUids.clear()
        planeNames.clear()
        _line1Guests.value = emptyList()
        ringRearmed = false
        // The watchdog's only question is "is a call in progress?", so a clock
        // left running would end whichever call came next.
        reconnect.cancel()
        everConnected.clear()
        sawRemoteMedia = false
        line3Ring.reset()
        // Neither the ring's progress nor the server's add-user list means
        // anything outside the call they were said in.
        _ringStatuses.value = emptyMap()
        _addableFromRoster.value = emptyList()
        // A hand does not carry into the next call; neither does a recording.
        _handRaised.value = false
        _onHold.value = false
        recorder.reset()
        line3InCall.reset()
        dismissSecondCall()
        // Reactions and lines never outlive the call that carried them.
        inCall.reset()
        _media.value = CallMedia()
        joining = false
    }

    /** Room ids and uuids are used interchangeably by different emitters. */
    /**
     * Whether this person is addressable on the call's line.
     *
     * Line 2 rings a device and cannot invite without one. Line 1 rings a
     * person, so requiring a device id there refuses invitations the endpoint
     * would have accepted — and the user just sees a shorter list with no
     * reason given.
     */
    private fun CallSession.canInvite(userId: String, deviceId: String): Boolean =
        if (provider == CallProvider.Mediasoup || provider == CallProvider.LiveKit) {
            userId.isNotBlank()
        } else {
            deviceId.isNotBlank()
        }

    /**
     * Something did not work; the call still does.
     *
     * Surfaced rather than logged, because the user asked for the thing that
     * failed and is otherwise left pressing a button that does nothing.
     */
    private suspend fun onDegraded(event: CallEngineEvent.Degraded) {
        ZillitLog.w(TAG) { "degraded: ${event.message}" }
        _notices.emit(event.message)
    }

    /** A remote hand, off the media line rather than the roster row. */
    private fun onPeerHand(event: CallEngineEvent.PeerHand) {
        val current = _session.value ?: return
        val updated = current.participants.withHandByUser(event.userId, event.raised)
        if (updated != current.participants) {
            _session.value = current.copy(participants = updated)
        }
    }

    /**
     * Line 3's room metadata: who the server credits with recording. Our own
     * name there is our own recorder, already shown as such; anyone else's
     * is the banner, and blank clears it whoever held it.
     */
    private var roomRecorder = ""

    private fun onRecordingBy(userId: String) {
        val current = _session.value ?: return
        if (userId.isBlank() || userId == current.selfUserId) {
            if (roomRecorder.isNotBlank()) recorder.onRemoteFlag(roomRecorder, recording = false, name = "")
            roomRecorder = ""
            return
        }
        roomRecorder = userId
        recorder.onRemoteFlag(
            key = userId,
            recording = true,
            name = current.participants.firstOrNull { it.userId == userId }?.name.orEmpty(),
        )
    }

    /** Somebody else started or stopped recording; the banner names them. */
    private fun onPeerRecording(event: CallEngineEvent.PeerRecording) {
        recorder.onRemoteFlag(
            key = event.userId,
            recording = event.recording,
            name = _session.value?.participants
                ?.firstOrNull { it.userId == event.userId }?.name.orEmpty(),
        )
    }

    private fun String.matches(session: CallSession): Boolean =
        isNotBlank() && (this == session.roomId || this == session.callUuid)

    // ── Line 3 ──────────────────────────────────────────────────────────────

    /**
     * Places a Line 3 call: a provisional session the outgoing card can show,
     * then the line's own create-and-ring, and the media join once the room is
     * known. The ring timeout and every status that follows ride the same
     * machinery as the other lines; only the wire differs.
     */
    @Suppress("LongParameterList") // The call's whole description, as placeCall passes it.
    private fun placeLine3(
        chatRoomId: String,
        receiverUserId: String,
        mode: CallMode,
        type: CallType,
        displayName: String,
        projectId: String?,
        callerUserId: String,
    ) {
        val line = line3 ?: run {
            fail(str(S.desktop_call_line3_not_configured))
            return
        }
        val me = callerUserId.ifBlank { selfUserId().orEmpty() }
        _phase.value = CallPhase.Outgoing
        _session.value = provisionalSession(
            chatRoomId, "", receiverUserId,
            mode, type, displayName, CallProvider.LiveKit, isCalendarCall = false, is247Call = false,
            projectId.orEmpty(),
        ).copy(
            selfUserId = me,
            participants = listOfNotNull(
                receiverUserId.takeIf { it.isNotBlank() }?.let {
                    CallParticipant(userId = it, name = displayName, status = CallStatus.Ringing)
                },
            ),
        )
        // The microphone and camera open NOW, in front of the ring rather than
        // behind it: the OS prompt and the capture then overlap the create and
        // the connect, which is what the web's `prewarmMedia` is for on its
        // outgoing screen. A call that is refused releases them again.
        engine.prewarmMedia(video = type == CallType.Video, audio = true)
        // The outgoing screen says "Calling…" from the press, and moves to
        // "Ringing…" on the callee's own ack — the web seeds the same map at
        // the same moment. A group call rings the room, so there is nobody
        // here to seed and the first `callRinging` fills it.
        _ringStatuses.value = listOfNotNull(receiverUserId.takeIf { it.isNotBlank() })
            .associateWith { CallRingState.Calling }
        _cameraOn.value = type == CallType.Video
        scope.launch {
            val placed = line.place(
                LiveKitDial(
                    calleeUserIds = listOfNotNull(receiverUserId.takeIf { it.isNotBlank() }),
                    chatRoomId = chatRoomId.takeIf { it.isNotBlank() },
                    // On a group call the name the button carried IS the room's,
                    // and it is the only label the server can ring with.
                    chatRoomName = displayName.takeIf { mode == CallMode.Group && chatRoomId.isNotBlank() },
                    mode = mode,
                    type = type,
                    callerUserId = me,
                    callerName = selfName().orEmpty(),
                    projectId = projectId,
                    projectName = line.identityNow()?.projectName,
                ),
            )
            when (placed) {
                is ZillitResult.Failure -> fail(placed.error.userMessage)
                is ZillitResult.Success -> {
                    val current = _session.value ?: return@launch
                    if (_phase.value != CallPhase.Outgoing) {
                        // Cancelled while the create was in flight: the call exists on the server, end it.
                        line.cancel(placed.data.callId, projectId, me)
                        return@launch
                    }
                    val session = current.copy(
                        callUuid = placed.data.callId,
                        roomId = placed.data.callId,
                        livekitUrl = placed.data.url,
                        livekitToken = placed.data.token,
                    )
                    _session.value = session
                    // The diagnostics window opens as soon as the call HAS an
                    // id. Without this an outgoing call sent nothing at all —
                    // no `warm_connect`, no `audio`, no `ice`, no reason it
                    // ended — because every emit is dropped outside a window,
                    // and only the ring path had opened one.
                    diagnostics.startCall(session.callUuid)
                    startRingTimeout(CallTimeouts.OUTGOING_MS) { outgoingRangOut() }
                    joinMedia(session)
                }
            }
        }
    }

    /** Answers a Line 3 ring: the accept on the line's wire, then the room it hands back. */
    private fun acceptLine3(current: CallSession) {
        val line = line3 ?: run {
            fail(str(S.desktop_call_line3_not_configured))
            return
        }
        scope.launch {
            when (val accepted = line.accept(current, selfName().orEmpty())) {
                is ZillitResult.Failure -> fail(accepted.error.userMessage)
                is ZillitResult.Success -> {
                    val live = _session.value ?: return@launch
                    if (live.callUuid != current.callUuid) return@launch
                    val session = live.copy(livekitUrl = accepted.data.url, livekitToken = accepted.data.token)
                    _session.value = session
                    joinMedia(session)
                }
            }
        }
    }

    /** What Line 3 reports, in the statuses the machine already speaks. */
    private inner class Line3Listener : LiveKitLineListener {
        override fun onInvite(session: CallSession) {
            line3Ring.reset()
            // The ring is about to be shown. `source` is the transport that
            // delivered it — the desktop has no push, so it is always the
            // socket; the field stays for the sake of one log across clients.
            diagnostics.startCall(session.callUuid)
            diagnostics.log("ring_shown", mapOf("source" to "socket"))
            // PRE-CONNECT while it rings, on the ring's locked token: the
            // accept then upgrades this participant in place instead of
            // starting a connect from nothing. Idempotent by call id, so a
            // re-emitted `incomingCall` does not tear down the one in flight.
            if (session.livekitPreconnectToken.isNotBlank() && session.livekitUrl.isNotBlank()) {
                engine.prewarm(session.callUuid, session.livekitUrl, session.livekitPreconnectToken)
            }
            // Qualified: the unqualified name is this listener's own method, and an
            // incoming ring recursed into it until the stack ran out — every Line 3
            // ring reaching this device died there, silently, in a launched coroutine.
            this@CallCoordinator.onInvite(session.copy(selfDeviceId = selfDeviceId().orEmpty()))
        }

        @Suppress("LongParameterList") // One parameter per field the event carries.
        override fun onRingState(
            callId: String,
            userId: String,
            displayName: String,
            status: CallStatus,
            busy: Boolean,
            unreachable: Boolean,
        ) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            // The outgoing screen's own line, about the CALLEES: our own row
            // going in_call the moment we join our own room is not an answer,
            // and reading it as one said "Joining…" while their phone was
            // still ringing (the web skips us for the same reason).
            if (userId.isNotBlank() && userId != current.selfUserId) {
                CallRingState.of(status, busy, unreachable)?.let { moved ->
                    _ringStatuses.update { it + (userId to moved) }
                }
            }
            // A name the ring did not carry; a row the roster did not have yet.
            if (userId.isNotBlank() && current.participants.none { it.userId == userId }) {
                _session.value = current.copy(
                    participants = current.participants +
                        CallParticipant(userId = userId, name = displayName, status = status),
                )
            } else if (displayName.isNotBlank()) {
                _session.value = current.copy(
                    participants = current.participants.healedFromPlane("", userId, displayName, 0),
                )
            }
            applyStatusChange(CallStatusChange(roomId = callId, userId = userId, status = status))
        }

        override fun onDismissed(callId: String, why: LiveKitDismissal, byName: String) {
            // The banner's call, not ours: it just goes away.
            _secondCall.value?.let { waiting ->
                if (callId.matches(waiting)) {
                    dismissSecondCall()
                    if (why == LiveKitDismissal.Cancelled) {
                        _toasts.tryEmit(str(S.desktop_call_missed_call_from, waiting.callerName))
                    }
                    return
                }
            }
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            // A cancel or a handled-elsewhere only ever dismisses a RING. The
            // server broadcasts `callHandledElsewhere` to every session of the
            // user — the one that just answered included — so acting on it
            // past the ring ended the call this device had taken: the phone
            // showed a connected call, the desktop nothing (2026-09-14). The
            // web clears only its incoming popup; Android gates on its ring
            // phase (`isRingingFor`). A removal is the host's and stands.
            if (why != LiveKitDismissal.Removed && _phase.value != CallPhase.Incoming) {
                ZillitLog.i(TAG) { "line 3 $why for ${current.callUuid} after the ring; ignored" }
                return
            }
            val reason = when (why) {
                LiveKitDismissal.HandledElsewhere -> CallEndReason.PickedElsewhere
                LiveKitDismissal.Cancelled, LiveKitDismissal.Removed -> CallEndReason.RemoteEnded
            }
            when {
                // A cancel while this device was ringing is a missed call, named.
                why == LiveKitDismissal.Cancelled && _phase.value == CallPhase.Incoming ->
                    _toasts.tryEmit(
                        str(S.desktop_call_missed_call_from, current.callerName.ifBlank { str(S.history_someone) }),
                    )
                why == LiveKitDismissal.Removed ->
                    _toasts.tryEmit(
                        if (byName.isNotBlank()) {
                            str(S.desktop_call_name_removed_you, byName)
                        } else {
                            str(S.desktop_call_you_were_removed)
                        },
                    )
            }
            scope.launch { engine.leave() }
            finish(current, reason)
        }

        override fun onUserState(callId: String, participant: CallParticipant) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            // A state-only delta must not lose the picture or the guest flag
            // the roster snapshot carried; merge what the event knew.
            _session.value = current.copy(
                participants = current.participants.map { row ->
                    if (row.userId != participant.userId) {
                        row
                    } else {
                        row.copy(
                            isGuest = row.isGuest || participant.isGuest,
                            image = row.image.ifBlank { participant.image },
                            name = row.name.ifBlank { participant.name },
                        )
                    }
                },
            )
        }

        override fun onReaction(callId: String, userId: String, emoji: String) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            val name = if (userId == current.selfUserId) {
                selfName().orEmpty()
            } else {
                current.participants.firstOrNull { it.userId == userId }?.name.orEmpty()
            }
            val at = now()
            inCall.receive(
                InCallData(
                    roomId = current.callUuid,
                    kind = IN_CALL_KIND_REACTION,
                    fromUserId = userId,
                    name = name,
                    emoji = emoji,
                    // Never de-duped: two taps of the same face are two floats.
                    id = "react-$userId-$at-${reactionSequence++}",
                    atMillis = at,
                ),
            )
        }

        override fun onHeld(callId: String, userId: String, onHold: Boolean) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            if (userId == current.selfUserId) {
                // The server is the authority: a hold from another of our
                // devices lands here too, and a duplicate re-sets the flag.
                applyHold(onHold, tellServer = false)
                return
            }
            _session.value = current.copy(
                participants = current.participants.map { row ->
                    if (row.userId == userId) row.copy(onHold = onHold) else row
                },
            )
        }

        override fun onHandsLowered(callId: String, userIds: List<String>) {
            val current = _session.value ?: return
            if (!callId.matches(current) || userIds.isEmpty()) return
            if (current.selfUserId in userIds) lowerOwnHand()
            _session.value = current.copy(
                participants = current.participants.map { row ->
                    if (row.userId in userIds) row.copy(handRaised = false) else row
                },
            )
        }

        override fun onChatBlock(callId: String, userId: String, blocked: Boolean) =
            line3InCall.onChatBlock(callId, userId, blocked)

        override fun onPolicy(callId: String, policy: LiveKitCallPolicy) {
            line3InCall.onPolicy(callId, policy)
            // A lock that arrives mid-share has to take the share down, not
            // just hide the button: the SFU does not enforce these, so the one
            // person the host is actually trying to stop is the one already
            // presenting. (The web only greys the control, and a presenter
            // there keeps presenting — reported here 2026-09-26.)
            if (line3InCall.shareRestricted && _media.value.selfSharing) {
                ZillitLog.i(TAG) { "host locked screen sharing; stopping ours" }
                _notices.tryEmit(str(S.desktop_call_host_disabled_screen_sharing))
                stopScreenShare()
            }
        }

        override fun onHostAction(callId: String, action: String) {
            val current = _session.value ?: return
            if (!callId.matches(current) || _phase.value != CallPhase.InCall) return
            when (action) {
                // Cooperative: the host asked, this client complies, the user can undo.
                Line3InCall.ACTION_MUTE_ALL -> if (!_micMuted.value) toggleMicrophone()
                // Lowered, not toggled: `toggleHand` refuses under a policy
                // that has turned hand-raising off, so going through it would
                // leave a hand up on exactly the call whose host had just
                // asked for it down.
                Line3InCall.ACTION_LOWER_HANDS -> lowerOwnHand()
                // No background effects on this client; nothing to clear.
                else -> Unit
            }
        }

        override fun onGuestKnocking(callId: String, guestId: String, name: String) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            line3InCall.onGuestKnocking(callId, name)
            _chimes.tryEmit(Unit)
        }

        override fun onGuestList(callId: String, guests: List<LiveKitGuest>) = line3InCall.onGuestList(callId, guests)

        override fun onNotice(text: String, warning: Boolean, sticky: Boolean) {
            _toasts.tryEmit(text)
        }

        /** What only the line can see — which transport carried the accept. */
        override fun onDiagnostic(event: String, data: Map<String, Any?>) = diagnostics.log(event, data)

        override fun onEnded(reason: String) {
            val current = _session.value ?: return
            if (current.provider != CallProvider.LiveKit) return
            ZillitLog.i(TAG) { "line 3 call ended: $reason" }
            scope.launch { engine.leave() }
            finish(current, CallEndReason.RemoteEnded)
        }

        override fun onRoster(callId: String, roster: LiveKitRoster) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            // The addable list is the server's and only the server's — see
            // `LiveKitRoster`. Published even when the people half is empty,
            // which is exactly the state a call has before anyone answers.
            _addableFromRoster.value = roster.addable
            line3InCall.onRoster(roster)
            val participants = roster.participants
            if (participants.isEmpty()) return
            val merged = mergeRoster(current.participants, participants)
            _session.value = current.copy(participants = merged)
            participants.forEach { rememberIfConnected(it.userId, status = it.status) }
            if (merged.any { it.isSomeoneElseLive(current) }) onSomeoneAnswered()
            checkRoomStillOccupied()
        }

        /**
         * A ring this device is showing, judged against the server's list:
         * gone from it, or answered on another of this user's devices, and it
         * stops — the one signal that reaches a device whose socket missed the
         * `callCancelled` or `callHandledElsewhere` that should have.
         */
        override fun onActiveCalls(calls: List<LiveKitActiveCall>) {
            _activeCalls.value = calls
            val current = _session.value ?: return
            if (current.provider != CallProvider.LiveKit || _phase.value != CallPhase.Incoming) return
            val verdict = line3Ring.judge(current.callUuid, current.selfUserId, calls) ?: return
            val reason = when (verdict) {
                LiveKitRingWatch.Verdict.AnsweredElsewhere -> CallEndReason.PickedElsewhere
                LiveKitRingWatch.Verdict.Stale -> CallEndReason.RemoteEnded
            }
            ZillitLog.i(TAG) { "ring ${current.callUuid} is $verdict by the server's active list; stopping" }
            scope.launch { engine.leave() }
            finish(current, reason)
        }
    }

    private companion object {
        const val TAG = "CallCoordinator"

        /** What this client stamps into `updated_from`; matches the plane's. */
        const val PLATFORM_SELF = "Desktop"

        /**
         * How long the media link may stay down before the call is ended.
         *
         * Long enough to survive a Wi-Fi handover or a VPN reconnect, short
         * enough that nobody keeps talking to a room that stopped hearing
         * them. The SDK retries for as long as it is allowed, so without a
         * ceiling a permanently dropped call never ends at all.
         */
        /**
         * The spelling to write. iOS reads BOTH — its row is hand-decoded,
         * `(dict["screenShare"] as? Bool) ?? (dict["screen_share"] as? Bool)`
         * — and the web reads only this one, so this is the form that reaches
         * everybody. (An earlier comment here credited iOS with a
         * `case screenShare = "screen_share"` coding key; there is no such
         * enum, and the claim has since generated two false audit findings.)
         */
        const val FIELD_SHARING_WIRE = "screen_share"

        /** The row fields an added person's seed carries. iOS's spellings. */
        const val FIELD_CURRENT_STATUS = "current_status"
        const val FIELD_USER_NAME = "user_name"
        const val FIELD_USER_ID = "user_id"
        const val FIELD_DEVICE_ID = "device_id"

        /**
         * What an invited-but-not-yet-ringing person's row says.
         *
         * A literal rather than a [CallStatus]: the enum degrades anything it
         * does not know to Ringing, which reads correctly here, and giving it
         * a member would mean revisiting every `when` that matches on it for a
         * state only ever written, never branched on.
         */
        const val STATUS_ADD_IN_CALL = "add_in_call"

        const val RECONNECT_GRACE_MILLIS = 45_000L

        /** How long the second-call banner stays up — the web's `Line2Presence` no-answer timeout. */
        const val SECOND_CALL_BANNER_MILLIS = 45_000L

        /**
         * How long an apparently empty room gets to prove it.
         *
         * The roster and the media side do not move together, and a peer
         * mid-reconnection can read as gone for a moment. The phones use the
         * same couple of seconds for the same reason.
         */
        const val EMPTY_ROOM_GRACE_MILLIS = 2_000L

        /** How long a Line 1 call-response waits on the socket's ack before REST is asked. */
        const val SOCKET_ACK_MILLIS = 3_000L

        /** The server's word for an accept that came too late. */
        const val CALL_ENDED = "call_ended"

        /**
         * How long Line 1's media-only empty room waits before it ends the call.
         * Set by the user (2026-10-05) between the web's 3 s and Android's 30 s:
         * a peer whose socket drops and comes back with a fresh peer id inside
         * this window keeps the call; one that takes longer ends it.
         */
        const val MEDIA_GONE_GRACE_MILLIS = 5_000L


    }
}

/** Who may be rung again from the users panel: gone from the call, never ringing or in it. */
internal val RE_RINGABLE = setOf(CallStatus.Declined, CallStatus.Left, CallStatus.NotAnswered)

/** The caller's own words for walking away from a ring. */
private val CALLER_GAVE_UP = setOf(CallStatus.Left, CallStatus.Declined, CallStatus.Ended)

/** A ring that will not be answered: refused, rung out, or withdrawn. */
private val REFUSED = setOf(CallStatus.Declined, CallStatus.NotAnswered, CallStatus.Left)

/**
 * [userId] as a Ringing row — appended when they were never on the call,
 * flipped back to Ringing when they had dropped out, so a re-ring keeps one
 * row per person rather than a second, duplicate one.
 */
internal fun CallSession.withRinging(userId: String, deviceId: String, name: String): CallSession {
    if (participants.none { it.userId == userId }) {
        return copy(
            participants = participants + CallParticipant(
                userId = userId,
                deviceId = deviceId,
                name = name,
                status = CallStatus.Ringing,
            ),
        )
    }
    return copy(
        participants = participants.map { row ->
            if (row.userId != userId) row
            else row.copy(status = CallStatus.Ringing, deviceId = deviceId.ifBlank { row.deviceId })
        },
    )
}
