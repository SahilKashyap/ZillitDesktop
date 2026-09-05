package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.onFailure
import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.calls.data.livekit.LiveKitActiveCall
import com.zillit.desktop.feature.calls.data.livekit.LiveKitDial
import com.zillit.desktop.feature.calls.data.livekit.LiveKitDismissal
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLine
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLineListener
import com.zillit.desktop.feature.calls.data.livekit.LiveKitRingWatch
import com.zillit.desktop.feature.calls.domain.CallDirection
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

    /** Things that went wrong without ending the call. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

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

    /** The pending "is anyone still here?" re-check, if one is armed. */
    private var emptyRoomCheck: Job? = null

    /** Server truth for a Line 3 ring this device is showing — see [LiveKitRingWatch]. */
    private val line3Ring = LiveKitRingWatch()

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
        line3?.attach(Line3Listener())
        line3?.start()
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
                scope.launch { line3?.decline(invite) }
                return
            }
            scope.launch {
                api.sendCallResponse(
                    roomId = invite.roomId.ifBlank { invite.callUuid },
                    status = CallStatus.Declined,
                    fromUserId = invite.selfUserId,
                    projectId = invite.projectId.takeIf(String::isNotBlank),
                )
            }
            scope.launch { plane.announceSelf(invite, CallStatus.Declined) }
            return
        }

        ZillitLog.i(TAG) { "incoming call ${invite.callUuid} mode=${invite.mode}" }
        _session.value = invite
        _phase.value = CallPhase.Incoming
        // Line 3 acknowledged the ring on its own socket; the v2 response and
        // the Firestore mirror are Lines 1 and 2's.
        if (invite.provider != CallProvider.LiveKit) {
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
        }
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
        if (current.provider == CallProvider.LiveKit) {
            acceptLine3(current)
            return
        }
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
        val raised = !_handRaised.value
        _handRaised.value = raised
        mirrorMediaState(mapOf("raise_hand" to raised))
        // Line 1 additionally announces it over protoo — the phones there
        // learn hands from `peerRaisedHand` broadcasts, not only the mirror.
        engine.setHandRaised(raised)
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
        scope.launch { engine.startScreenShare(sourceId) }
    }

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
        if (current.provider == CallProvider.LiveKit) {
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
            return
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
        if (change.userId == current.selfUserId && current.selfUserId.isNotBlank() &&
            change.status == CallStatus.InCall &&
            _phase.value == CallPhase.Outgoing && !weAreAlsoTheCallee
        ) {
            ZillitLog.i(TAG) { "ignoring our own in_call while ${current.callUuid} is still ringing" }
            return
        }

        _session.value = current.copy(
            participants = current.participants.withStatus(change.userId, change.status),
        )

        rememberIfConnected(change.userId, status = change.status)

        when (change.status) {
            CallStatus.InCall -> onSomeoneAnswered()
            CallStatus.Declined, CallStatus.NotAnswered, CallStatus.Left ->
                onSomeoneUnavailable(change.status)
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
        if (!roomLooksEmpty()) {
            emptyRoomCheck?.cancel()
            emptyRoomCheck = null
            return
        }
        if (emptyRoomCheck?.isActive == true) return
        emptyRoomCheck = scope.launch {
            delay(EMPTY_ROOM_GRACE_MILLIS)
            // Asked a second time, and only the second answer is acted on.
            if (!roomLooksEmpty()) return@launch
            val session = _session.value ?: return@launch
            ZillitLog.i(TAG) { "everyone else left ${session.callUuid}; ending" }
            engine.leave()
            finish(session, CallEndReason.RemoteEnded)
        }
    }

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
        val couldEnd = _phase.value == CallPhase.InCall &&
            !reconnect.isArmed &&
            !current.isCalendarCall
        return couldEnd &&
            allOthersGone(current.participants, everConnected, _media.value.peers.size)
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
                // A peer's media going away is the other half of "is anyone
                // still here": the roster can lag, and on Line 1 a departure
                // reaches this side as a closed consumer well before any row
                // moves.
                is CallEngineEvent.PeerLeft -> checkRoomStillOccupied()
                is CallEngineEvent.Degraded -> onDegraded(event)
                is CallEngineEvent.PeerHand -> onPeerHand(event)
                is CallEngineEvent.PeerRecording -> onPeerRecording(event)
                is CallEngineEvent.RecordingSaved -> onRecordingSaved(event)
                else -> Unit
            }
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
        _toasts.tryEmit("Recording saved to ${event.path}")
        val sender = share ?: return
        // The call the recording belongs to, which by now may not be the live
        // one — see CallRecordingControl.recordedSession.
        val session = recorder.recordedSession ?: return
        val targets = recordingTargets(session, selfUserId())
        if (targets.isEmpty()) {
            ZillitLog.w(TAG) { "recording not sent: no chat recipient on ${session.callUuid}" }
            _toasts.tryEmit("Recording saved, but there was nobody to send it to.")
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
                _toasts.tryEmit("Recording sent to chat.")
            }.onFailure { error ->
                ZillitLog.w(TAG) { "recording not sent: $error" }
                _toasts.tryEmit("Recording saved, but sending it to chat failed.")
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
        if (current.provider == CallProvider.LiveKit) {
            scope.launch {
                engine.leave()
                line3?.cancel(current.callUuid, current.projectId.takeIf(String::isNotBlank), current.selfUserId)
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
    private suspend fun sendFinalStatus(session: CallSession, status: CallStatus) =
        withContext(NonCancellable) {
            if (session.provider == CallProvider.LiveKit) return@withContext
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
        everConnected.clear()
        line3Ring.reset()
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
            fail("Line 3 is not configured on this install")
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
        _cameraOn.value = type == CallType.Video
        scope.launch {
            val placed = line.place(
                LiveKitDial(
                    calleeUserIds = listOfNotNull(receiverUserId.takeIf { it.isNotBlank() }),
                    chatRoomId = chatRoomId.takeIf { it.isNotBlank() },
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
                    startRingTimeout(CallTimeouts.OUTGOING_MS) { outgoingRangOut() }
                    joinMedia(session)
                }
            }
        }
    }

    /** Answers a Line 3 ring: the accept on the line's wire, then the room it hands back. */
    private fun acceptLine3(current: CallSession) {
        val line = line3 ?: run {
            fail("Line 3 is not configured on this install")
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
            onInvite(session.copy(selfDeviceId = selfDeviceId().orEmpty()))
        }

        override fun onRingState(
            callId: String,
            userId: String,
            displayName: String,
            status: CallStatus,
            busy: Boolean,
        ) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
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

        override fun onDismissed(callId: String, why: LiveKitDismissal) {
            val current = _session.value ?: return
            if (!callId.matches(current)) return
            val reason = when (why) {
                LiveKitDismissal.HandledElsewhere -> CallEndReason.PickedElsewhere
                LiveKitDismissal.Cancelled, LiveKitDismissal.Removed -> CallEndReason.RemoteEnded
            }
            scope.launch { engine.leave() }
            finish(current, reason)
        }

        override fun onEnded(reason: String) {
            val current = _session.value ?: return
            if (current.provider != CallProvider.LiveKit) return
            ZillitLog.i(TAG) { "line 3 call ended: $reason" }
            scope.launch { engine.leave() }
            finish(current, CallEndReason.RemoteEnded)
        }

        override fun onRoster(callId: String, participants: List<CallParticipant>) {
            val current = _session.value ?: return
            if (!callId.matches(current) || participants.isEmpty()) return
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

        /**
         * How long an apparently empty room gets to prove it.
         *
         * The roster and the media side do not move together, and a peer
         * mid-reconnection can read as gone for a moment. The phones use the
         * same couple of seconds for the same reason.
         */
        const val EMPTY_ROOM_GRACE_MILLIS = 2_000L


    }
}
