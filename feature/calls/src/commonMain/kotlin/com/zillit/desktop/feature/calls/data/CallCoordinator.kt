package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.onFailure
import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf
import com.zillit.desktop.feature.calls.data.protoo.toJoin
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Why a call stopped, for the UI's parting message.
 */
enum class CallEndReason { Hungup, RemoteEnded, Declined, Busy, Timeout, PickedElsewhere, Error }

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
    )

    /** Reactions and lines, inbound and our own echoed back. Never persisted. */
    val inCallData: SharedFlow<InCallData> get() = inCall.data

    /** Sends an emoji to everyone else on the call, and shows it here. */
    fun sendReaction(emoji: String) = inCall.sendReaction(emoji)

    /** Sends one ephemeral line to everyone else on the call. */
    fun sendInCallMessage(text: String) = inCall.sendMessage(text)

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

    fun start() {
        scope.launch { listenIncoming() }
        scope.launch { listenStatusChanges() }
        scope.launch { listenEnded() }
        scope.launch { listenTimeout() }
        scope.launch { listenHandoffEvict() }
        scope.launch { inCall.listen() }
        scope.launch { listenEngine() }
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
    ) {
        if (_phase.value != CallPhase.Idle) return
        _phase.value = CallPhase.Outgoing
        // A provisional session so the outgoing card and its Cancel exist for
        // the whole create-call round trip. Every overlay branch is gated on a
        // non-null session and hangUp() returns without one, so a stalled POST
        // otherwise means up to a minute of no card, no cancel, and every
        // further call-button press silently refused by the phase check above.
        _session.value = CallSession(
            // Blank until the server names it. Every id-scoped write already
            // guards on blank — the doc's "must have a valid ObjectId" rule —
            // so a provisional session cannot post a status for no call.
            callUuid = "",
            direction = CallDirection.Outgoing,
            // Named now so the outgoing card can say which line it is on
            // before the server answers.
            provider = provider,
            mode = mode,
            type = type,
            hasVideo = type == CallType.Video,
            selfUserId = selfUserId().orEmpty(),
            selfDeviceId = selfDeviceId().orEmpty(),
            receiverDeviceId = receiverDeviceId,
            chatRoomId = chatRoomId,
            title = displayName,
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
                ),
            ).onSuccess { session ->
                if (session == null) {
                    fail("the server did not return a call")
                } else if (_phase.value != CallPhase.Outgoing) {
                    // Cancelled while the POST was in flight. The call exists
                    // on the server regardless, so end it rather than adopting
                    // a call the user already walked away from.
                    ZillitLog.i(TAG) { "create-call landed after cancel; ending ${session.callUuid}" }
                    api.endCall(
                        callUuid = session.callUuid,
                        deviceId = selfDeviceId().orEmpty(),
                        projectId = session.projectId.takeIf(String::isNotBlank),
                        provider = session.provider,
                    )
                } else {
                    // The response describes the caller — us. The callee's
                    // name came from the screen that pressed the button.
                    _session.value = session.copy(
                        title = displayName.ifBlank { session.title },
                        receiverDeviceId = receiverDeviceId,
                    )
                    _cameraOn.value = session.hasVideo
                    startRingTimeout(CallTimeouts.OUTGOING_MS) { outgoingRangOut() }
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
            scope.launch {
                api.sendCallResponse(
                    roomId = invite.roomId.ifBlank { invite.callUuid },
                    status = CallStatus.Declined,
                    fromUserId = invite.selfUserId,
                    projectId = invite.projectId.takeIf(String::isNotBlank),
                )
            }
            return
        }

        ZillitLog.i(TAG) { "incoming call ${invite.callUuid} mode=${invite.mode}" }
        _session.value = invite
        _phase.value = CallPhase.Incoming
        scope.launch {
            api.sendCallResponse(
                roomId = invite.roomId.ifBlank { invite.callUuid },
                status = CallStatus.Ringing,
                fromUserId = invite.selfUserId,
                projectId = invite.projectId.takeIf(String::isNotBlank),
            )
        }
        scope.launch { plane.announceSelf(invite, CallStatus.Ringing) }
        watchPlane(invite)
        startRingTimeout(CallTimeouts.INCOMING_MS) { incomingRangOut() }
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
        // Status and join in parallel: the status is advisory, and joining
        // behind it would make the user's media wait on an HTTP round trip.
        scope.launch {
            api.sendCallResponse(
                roomId = current.roomId.ifBlank { current.callUuid },
                status = CallStatus.InCall,
                fromUserId = current.selfUserId,
                projectId = current.projectId.takeIf(String::isNotBlank),
            )
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
        if (current.participants.any { it.userId == userId }) return

        _session.value = current.copy(
            participants = current.participants + CallParticipant(
                userId = userId,
                deviceId = deviceId,
                name = name,
                status = CallStatus.Ringing,
            ),
        )
        scope.launch {
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
        val raised = !_handRaised.value
        _handRaised.value = raised
        mirrorMediaState(mapOf("raise_hand" to raised))
        // Line 1 additionally announces it over protoo — the phones there
        // learn hands from `peerRaisedHand` broadcasts, not only the mirror.
        engine.setHandRaised(raised)
    }

    fun toggleScreenShare() {
        if (_phase.value != CallPhase.InCall) return
        val sharing = _media.value.selfSharing
        scope.launch {
            if (sharing) engine.stopScreenShare() else engine.startScreenShare()
        }
    }

    /** Starts or stops recording the call's audio on this machine. */
    fun toggleRecording() {
        if (_phase.value != CallPhase.InCall) return
        val session = _session.value ?: return
        recorder.toggle(session)
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
        // Android's rule: the last one out ends the call for everyone; anyone
        // else merely leaves. Ending a room three people are talking in
        // because one hung up is the bug this avoids.
        val othersActive = current.participants.any {
            it.userId != current.selfUserId && it.status.isConnected
        }
        // A call the server has not named yet cannot be ended by uuid; the
        // create-call response handles the cancel when it lands.
        val endsForEveryone = current.callUuid.isNotBlank() && !othersActive &&
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

    // ── Socket reactions ────────────────────────────────────────────────

    private suspend fun listenStatusChanges() {
        val events = listOf(ZillitSocketEvents.Calls.Update, ZillitSocketEvents.Calls.Response)
        bus.onAny(events).collect { message ->
            val change = message.payload?.let(::readStatusChange) ?: return@collect
            applyStatusChange(change)
        }
    }

    private fun applyStatusChange(change: CallStatusChange) {
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

        _session.value = current.copy(
            participants = current.participants.withStatus(change.userId, change.status),
        )

        when (change.status) {
            CallStatus.InCall -> onSomeoneAnswered()
            CallStatus.Declined, CallStatus.NotAnswered, CallStatus.Left ->
                onSomeoneUnavailable(change.status)
            else -> Unit
        }
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
    private fun onSomeoneUnavailable(status: CallStatus) {
        val current = _session.value ?: return
        if (current.mode != CallMode.Private || _phase.value != CallPhase.Outgoing) return
        val reason = if (status == CallStatus.Declined) CallEndReason.Declined else CallEndReason.Timeout
        scope.launch { engine.leave() }
        finish(current, reason)
    }

    private suspend fun listenEnded() {
        val events = listOf(ZillitSocketEvents.Calls.Ended, ZillitSocketEvents.Calls.GroupCallEnded)
        bus.onAny(events).collect { message ->
            val ended = message.payload?.let(::readCallEnded) ?: return@collect
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
        if (uid != 0) {
            _media.value = _media.value.reduce(CallEngineEvent.PeerScreenShare(uid, event.sharing))
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

    private suspend fun listenEngine() {
        engine.events.collect { event ->
            // Every event folds into the media picture, including the ones no
            // phase transition cares about — the speaking rings and the link
            // pips are exactly those, and dropping them is what left the UI
            // with nothing live to show.
            _media.value = _media.value.reduce(event)
            when (event) {
                is CallEngineEvent.Joined -> onMediaJoined(event.uid)
                CallEngineEvent.TokenExpiring ->
                    ZillitLog.w(TAG) { "RTC token entering its grace period" }
                // Nothing to renew with, so end it deliberately instead of
                // leaving a window open on a call whose media has stopped —
                // the phones tear down here too. Reported as an error so the
                // user is told, rather than the call simply vanishing.
                CallEngineEvent.TokenExpired -> fail("call token expired")
                is CallEngineEvent.ConnectionChanged -> reconnect.onConnectionChanged(event.state)
                is CallEngineEvent.ScreenShare -> {
                    // The phones read `screenShare` off the roster row to
                    // badge the sharer and pin their tile; mirrored only once
                    // the engine confirms, so a cancelled picker publishes
                    // nothing.
                    mirrorMediaState(mapOf(FIELD_SHARING_WIRE to event.sharing))
                }
                is CallEngineEvent.Devices -> audio.onEngineDevices(event)
                is CallEngineEvent.Failed -> fail(event.message)
                is CallEngineEvent.PeerHand -> {
                    val current = _session.value ?: return@collect
                    val updated = current.participants.withHandByUser(event.userId, event.raised)
                    if (updated != current.participants) {
                        _session.value = current.copy(participants = updated)
                    }
                }
                is CallEngineEvent.PeerRecording -> recorder.onRemoteFlag(
                    key = event.userId,
                    recording = event.recording,
                    name = _session.value?.participants
                        ?.firstOrNull { it.userId == event.userId }?.name.orEmpty(),
                )
                is CallEngineEvent.RecordingSaved ->
                    _toasts.tryEmit("Recording saved to ${event.path}")
                else -> Unit
            }
        }
    }

    private fun onMediaJoined(uid: Int) {
        val current = _session.value ?: return
        // A join that lands while we are already leaving is not an arrival.
        if (_phase.value == CallPhase.Ending) return
        recordMediaUid(current, uid)
        if (_phase.value == CallPhase.Incoming) {
            cancelRingTimeout()
            _phase.value = CallPhase.InCall
        }
        // Hydrate the roster from the server's snapshot: everything that
        // happened before we subscribed is invisible on the socket.
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
                // A STRING, not the Int it is. iOS declares `agoraUID: String?`
                // and writes it as "\(agoraID)", and it decodes each row with
                // JSONDecoder — so a numeric `agora_uid` throws typeMismatch
                // and iOS discards the WHOLE row, losing this device's status,
                // raised hand and screen share along with it.
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

    private suspend fun joinMedia(session: CallSession) {
        if (joining) return
        joining = true
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

    private fun incomingRangOut() {
        val current = _session.value ?: return
        // Answered a moment before the timeout fired: accept() has already
        // moved the phase, and reporting "not answered" now would retract it.
        if (_phase.value != CallPhase.Incoming) return
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
    private suspend fun sendFinalStatus(session: CallSession, status: CallStatus) =
        withContext(NonCancellable) {
            api.sendCallResponse(
                roomId = session.roomId.ifBlank { session.callUuid },
                status = status,
                fromUserId = session.selfUserId,
                projectId = session.projectId.takeIf(String::isNotBlank),
            )
        }

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
        val announce = _phase.value != CallPhase.Idle && current.callUuid.isNotBlank()
        scope.launch {
            engine.leave()
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
        cancelRingTimeout()
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
        // A hand does not carry into the next call; neither does a recording.
        _handRaised.value = false
        recorder.reset()
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
        if (provider == CallProvider.Mediasoup) userId.isNotBlank() else deviceId.isNotBlank()

    private fun String.matches(session: CallSession): Boolean =
        isNotBlank() && (this == session.roomId || this == session.callUuid)

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
         * The spelling every other client reads: iOS's row model is
         * `case screenShare = "screen_share"`. The desktop used to write the
         * camelCase form, which nothing on the phones looks at, so a shared
         * screen was simply never flagged there.
         */
        const val FIELD_SHARING_WIRE = "screen_share"

        const val RECONNECT_GRACE_MILLIS = 45_000L


    }
}
