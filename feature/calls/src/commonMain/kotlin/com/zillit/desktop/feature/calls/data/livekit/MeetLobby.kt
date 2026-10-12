package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.domain.LobbyMediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the lobby's join has got to. */
sealed interface MeetLobbyStatus {
    /** "Join now" — or "Start the meeting"; see [MeetLobbyState.offersStart]. */
    data object Idle : MeetLobbyStatus

    /** The join is in flight; the button is disabled with a spinner. */
    data object Joining : MeetLobbyStatus

    /** Knocked; waiting for someone inside to let us in. Cancel is local. */
    data object Waiting : MeetLobbyStatus

    /** We are already in this meeting on another device; joining here moves us. */
    data object ConfirmSwitch : MeetLobbyStatus

    /** Red text and a retry. [fatal] ones (a dead link, a cancelled meeting) have nothing to retry. */
    data class Error(val problem: MeetProblem, val detail: String = "", val fatal: Boolean = false) : MeetLobbyStatus
}

/**
 * The lobby for one meet: what it shows, and how far the join has got.
 *
 * One at a time for the whole app — a second lobby is a second join, two SFU
 * identities and a ghost participant for everybody else.
 */
data class MeetLobbyState(
    val meetId: String,
    val title: String,
    val projectId: String? = null,
    val selfUserId: String = "",
    /** `GET /v1/meet`, once it answered. */
    val info: MeetInfo? = null,
    val micOn: Boolean = true,
    val camOn: Boolean = false,
    /** The user pressed the camera toggle, so the meet's own call type no longer decides it. */
    val camTouched: Boolean = false,
    /** The OS refused the camera: forced off, the join goes audio-only. */
    val camBlocked: Boolean = false,
    /** The OS refused the microphone: the join is blocked. */
    val micBlocked: Boolean = false,
    /** Who is IN the meeting, from the last roster read that succeeded. */
    val attendees: List<MeetAttendee> = emptyList(),
    /** A roster read has succeeded at least once — until then the room is "unknown", not "empty". */
    val rosterConfirmed: Boolean = false,
    /** `GET …/me` says one of this user's devices is in the room. */
    val inCallElsewhere: Boolean = false,
    val status: MeetLobbyStatus = MeetLobbyStatus.Idle,
    /** The credentials went to the call; the lobby only covers the connect from here, then retires. */
    val handedOff: Boolean = false,
    /** The "See all" sheet. */
    val everyoneOpen: Boolean = false,
) {
    /** Clicks are ignored: a second join would be a second SFU identity. */
    val busy: Boolean get() = status == MeetLobbyStatus.Joining || status == MeetLobbyStatus.Waiting || handedOff

    private val isOwner: Boolean get() = info != null && info.ownerId.isNotBlank() && info.ownerId == selfUserId

    /** Nobody is in, and this user may not start it: the owner has to. */
    val waitsForHost: Boolean
        get() = rosterConfirmed && attendees.isEmpty() && info?.startPolicy == MeetStartPolicy.Owner && !isOwner

    /**
     * "Start the meeting" instead of "Join now": a roster read SUCCEEDED and
     * came back empty, and this user may start it. Never on an unknown roster
     * — telling someone to start a meeting that is already running is what a
     * failing read otherwise says.
     */
    val offersStart: Boolean
        get() = rosterConfirmed && attendees.isEmpty() && info != null &&
            (info.startPolicy == MeetStartPolicy.Anyone || isOwner)

    /** "Switch here" in place of Join, learned before committing rather than after. */
    val offersSwitch: Boolean
        get() = status == MeetLobbyStatus.ConfirmSwitch ||
            (inCallElsewhere && !busy && (status == MeetLobbyStatus.Idle || status is MeetLobbyStatus.Error))

    /** Join can be pressed: nothing in flight, and not a dead link or a cancelled meeting. */
    val mayJoin: Boolean get() = !busy && (status as? MeetLobbyStatus.Error)?.fatal != true

    /** The camera's preview is live. */
    val previewing: Boolean get() = camOn && !camBlocked
}

/** What the lobby asks to open: the event's room and how it was set up. */
data class MeetLobbyRequest(
    val meetId: String,
    val title: String,
    /** The event's call type is video — the camera's default until the meet's own answer lands. */
    val video: Boolean,
    val projectId: String? = null,
)

/** The credentials, and everything the call needs to be seeded with them. */
data class MeetHandOff(
    val meetId: String,
    val title: String,
    val projectId: String?,
    val url: String,
    val token: String,
    val video: Boolean,
    val micMuted: Boolean,
    val ownerId: String,
    val ownerName: String,
)

/** The calling backend, as the lobby uses it — [LiveKitLine] in the app, a fake in tests. */
interface MeetBackend {
    val online: StateFlow<Boolean>

    fun selfUserId(): String?

    fun selfName(): String

    suspend fun info(meetId: String): MeetLookup

    suspend fun presence(meetId: String): MeetPresence?

    suspend fun roster(meetId: String): MeetRosterRead?

    suspend fun refreshRoster(meetId: String)

    suspend fun start(meetId: String, video: Boolean): MeetStart

    suspend fun knock(meetId: String, displayName: String): KnockAnswer

    suspend fun overRest(meetId: String): MeetRest
}

/** The devices, as the lobby uses them — the call engine in the app. */
interface MeetLobbyMedia {
    suspend fun access(video: Boolean): LobbyMediaAccess

    fun open(video: Boolean)

    fun setCamera(on: Boolean)

    fun endPreview()

    fun close()
}

/** [MeetBackend] over the line. */
class LiveKitMeetBackend(private val line: LiveKitLine, private val selfName: () -> String?) : MeetBackend {
    override val online: StateFlow<Boolean> get() = line.online

    override fun selfUserId(): String? = line.identityNow()?.userId

    override fun selfName(): String =
        selfName.invoke()?.takeIf { it.isNotBlank() } ?: line.identityNow()?.displayName.orEmpty()

    override suspend fun info(meetId: String) = line.meets.meetInfo(meetId)

    override suspend fun presence(meetId: String) = line.meets.meetPresence(meetId)

    override suspend fun roster(meetId: String) = line.meets.meetRoster(meetId)

    override suspend fun refreshRoster(meetId: String) = line.meets.refreshMeetRoster(meetId)

    override suspend fun start(meetId: String, video: Boolean) = line.meets.startMeet(meetId, video)

    override suspend fun knock(meetId: String, displayName: String) = line.meets.knock(meetId, displayName)

    override suspend fun overRest(meetId: String) = line.meets.joinMeetOverRest(meetId)
}

/**
 * Whether the lobby, rather than the call's own UI, owns the call surface.
 *
 * Before the hand-off: when there is no call, or only a stale ended one — a
 * different live call (an incoming ring) takes the surface straight back.
 * After it: only while THIS meet is still connecting; otherwise it is retired
 * for good. The trap this exists for: "no call yet, so the lobby owns the
 * screen" is right before joining and wrong after a call ENDS, when the state
 * goes back to nothing and a lobby that was never told otherwise reappears
 * over the ended call — which reads as "the lobby does not close".
 */
fun meetLobbyOwnsSurface(lobby: MeetLobbyState?, phase: CallPhase, session: CallSession?): Boolean {
    if (lobby == null) return false
    if (lobby.handedOff) {
        if (session == null || session.callUuid != lobby.meetId) return false
        return phase == CallPhase.Outgoing
    }
    return session == null || phase == CallPhase.Idle
}

/**
 * The Line 1 meet lobby: a lobby STATE on the call surface, not a screen of
 * its own, so lobby → joining → in-call happens in one place and the call is
 * never owned by two.
 *
 * The join decision tree, in order: resolve the link (`GET /v1/meet`), ask
 * about a second device (`GET …/me`), then the socket's `startCall`
 * (callMode `meet`) — which hands back the room, or says to knock — and only
 * when the socket cannot carry it, the REST mint. The two paths never race,
 * and the REST mint never follows a knock. The credentials are joined
 * verbatim through the call's existing connect ([handOff]).
 */
@Suppress("TooManyFunctions", "LongParameterList") // One verb per lobby control; the collaborators it is wired to.
class MeetLobby(
    private val scope: CoroutineScope,
    private val backend: MeetBackend,
    private val media: MeetLobbyMedia,
    private val callPhase: StateFlow<CallPhase>,
    private val callSession: StateFlow<CallSession?>,
    /** Why a meet cannot be joined from here at all (`calling_lines`), or null. */
    private val refusal: (projectId: String?) -> String?,
    private val toast: (String) -> Unit,
    /** Seeds and connects the call; false when another call got there first. */
    private val handOff: (MeetHandOff) -> Boolean,
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val _state = MutableStateFlow<MeetLobbyState?>(null)
    val state: StateFlow<MeetLobbyState?> = _state.asStateFlow()

    /** The window the lobby is drawn in has focus — polling stops while it does not. */
    private val focused = MutableStateFlow(true)

    private var lobbyJob: Job? = null
    private var joinJob: Job? = null

    /** The hand-off's own call has been seen on the surface — see [watchCall]. */
    private var sawOurCall = false

    /**
     * Opens the lobby — never an immediate join. Refused, with a toast and no
     * lobby, when the event has no meeting, another call is up, or the line is
     * not offered here. The same meet again just keeps the lobby that is up.
     */
    fun open(request: MeetLobbyRequest) {
        val meetId = request.meetId.trim()
        val already = _state.value
        // The same meet again: the lobby that is up is the one.
        if (meetId.isNotBlank() && already?.meetId == meetId) return
        val refused = when {
            meetId.isBlank() -> str(S.desktop_meet_no_meeting)
            already?.busy == true -> str(S.desktop_meet_already_on_a_call)
            else -> busyRefusal() ?: refusal(request.projectId)
        }
        if (refused != null) {
            toast(refused)
            return
        }
        if (already != null) dismiss("another meet opened")
        ZillitLog.i(TAG) { "lobby open for $meetId" }
        sawOurCall = false
        _state.value = MeetLobbyState(
            meetId = meetId,
            title = request.title,
            projectId = request.projectId,
            selfUserId = backend.selfUserId().orEmpty(),
            camOn = request.video,
        )
        lobbyJob = scope.launch {
            launch { openDevices(request.video) }
            launch { resolve(meetId) }
            launch { poll(meetId) }
            launch { refreshAfterReconnect(meetId) }
            launch { watchCall() }
        }
    }

    /** The close button: allowed while knocking, not while the join is in flight. */
    fun close() {
        val current = _state.value ?: return
        if (current.status == MeetLobbyStatus.Joining || current.handedOff) return
        dismiss("closed")
    }

    fun setFocused(on: Boolean) {
        focused.value = on
    }

    fun toggleMicrophone() {
        if (_state.value?.handedOff != false) return
        _state.update { it?.copy(micOn = !it.micOn) }
    }

    /** The user's own choice — from here on the meet's call type no longer moves it. */
    fun toggleCamera() {
        val current = _state.value ?: return
        if (current.handedOff) return
        val on = !current.previewing
        // Flipped now, so a second press reads the first; the OS is asked after.
        _state.update { it?.copy(camTouched = true, camOn = on, camBlocked = it.camBlocked && on) }
        // A child of the lobby, so a close while the OS is still being asked never opens the camera after it.
        val lobby = lobbyJob ?: return
        scope.launch(lobby) { applyCamera(current.meetId, on) }
    }

    fun toggleEveryone() {
        _state.update { it?.copy(everyoneOpen = !it.everyoneOpen) }
    }

    /** Join now / Start the meeting / retry. Ignored while a join is in flight. */
    fun join() = startJoin(switchConfirmed = false)

    /** "Switch here": we are in it on another device, and the user said move it here. */
    fun switchHere() = startJoin(switchConfirmed = true)

    /**
     * Cancel, in the two places it is offered. A knock is cancelled LOCALLY —
     * there is no cancel verb and none is invented — and a later admit finds
     * nobody waiting. The switch question's Cancel declines joining at all.
     */
    fun cancel() {
        val current = _state.value ?: return
        when {
            current.status == MeetLobbyStatus.Waiting -> {
                joinJob?.cancel()
                joinJob = null
                setStatus(current.meetId, MeetLobbyStatus.Idle)
            }
            current.offersSwitch -> dismiss("switch declined")
        }
    }

    // ── The join ──────────────────────────────────────────────────────────

    private fun startJoin(switchConfirmed: Boolean) {
        val current = _state.value
        if (current == null || !current.mayJoin) return
        val other = busyRefusal()
        when {
            current.micBlocked -> setStatus(current.meetId, MeetLobbyStatus.Error(MeetProblem.MicrophoneDenied))
            other != null -> toast(other)
            else -> {
                setStatus(current.meetId, MeetLobbyStatus.Joining)
                joinJob = scope.launch { runJoin(current.meetId, switchConfirmed) }
            }
        }
    }

    private suspend fun runJoin(meetId: String, switchConfirmed: Boolean) {
        // Step 1, if the lobby's own read has not answered: what the link is.
        var info = _state.value?.info
        if (info == null) {
            when (val found = backend.info(meetId)) {
                is MeetLookup.Found -> info = found.info.also { adoptInfo(meetId, it) }
                MeetLookup.NoSuchMeet -> return fail(meetId, MeetProblem.LinkInvalid, fatal = true)
                is MeetLookup.Unknown -> Unit
            }
        }
        if (info?.active == false) return fail(meetId, MeetProblem.Cancelled, fatal = true)

        // Step 2: joining from a second device cuts the first, and the server
        // sends no prompt for a meet — so this client asks.
        if (!switchConfirmed && backend.presence(meetId)?.inCall == true) {
            update(meetId) { copy(inCallElsewhere = true, status = MeetLobbyStatus.ConfirmSwitch) }
            return
        }

        connect(meetId)
    }

    /** Step 3, PATH A: the socket — and PATH B over REST only when the socket could not settle it. */
    private suspend fun connect(meetId: String) {
        val video = _state.value?.previewing == true
        when (val start = backend.start(meetId, video)) {
            is MeetStart.Creds -> deliver(meetId, start.url, start.token)
            MeetStart.MustKnock -> knock(meetId)
            is MeetStart.Refused -> fail(meetId, start.problem, start.detail, fatal = start.problem.isFinal())
            is MeetStart.UseRest -> {
                ZillitLog.i(TAG) { "meet $meetId over REST: ${start.why}" }
                when (val rest = backend.overRest(meetId)) {
                    is MeetRest.Creds -> deliver(meetId, rest.url, rest.token)
                    is MeetRest.Refused -> fail(meetId, rest.problem, rest.detail, fatal = rest.problem.isFinal())
                }
            }
        }
    }

    /** Step 4: not an error — a non-owner on an owner-started meet asks to be let in. */
    private suspend fun knock(meetId: String) {
        setStatus(meetId, MeetLobbyStatus.Waiting)
        when (val answer = backend.knock(meetId, backend.selfName())) {
            is KnockAnswer.Admitted -> if (answer.url.isBlank() || answer.token.isBlank()) {
                fail(meetId, MeetProblem.NoJoinDetails)
            } else {
                deliver(meetId, answer.url, answer.token)
            }
            is KnockAnswer.Refused -> fail(meetId, answer.problem)
        }
    }

    /**
     * Credentials in hand: they go to the call verbatim, through its existing
     * connect. The lobby stays up — button disabled, spinner — until that
     * connect lands, and only then gives the surface to the call.
     */
    private fun deliver(meetId: String, url: String, token: String) {
        val current = _state.value ?: return
        if (current.meetId != meetId || current.handedOff) return
        val info = current.info
        _state.value = current.copy(handedOff = true, status = MeetLobbyStatus.Joining, everyoneOpen = false)
        val seeded = handOff(
            MeetHandOff(
                meetId = meetId,
                title = current.title.ifBlank { info?.title.orEmpty() },
                projectId = current.projectId,
                url = url,
                token = token,
                video = current.previewing,
                micMuted = !current.micOn,
                ownerId = info?.ownerId.orEmpty(),
                ownerName = info?.ownerName.orEmpty(),
            ),
        )
        if (!seeded) {
            ZillitLog.w(TAG) { "meet $meetId: another call took the surface before the hand-off" }
            update(meetId) { copy(handedOff = false, status = MeetLobbyStatus.Error(MeetProblem.AlreadyOnAnotherCall)) }
        } else {
            // The call is seeded synchronously, so from here every reading of
            // the surface that is not "this meet, connecting" retires the lobby.
            sawOurCall = true
            ZillitLog.i(TAG) { "meet $meetId handed to the call" }
        }
    }

    private fun fail(meetId: String, problem: MeetProblem, detail: String = "", fatal: Boolean = false) {
        ZillitLog.w(TAG) { "meet $meetId: $problem ${detail.take(DETAIL_LOGGED)}" }
        setStatus(meetId, MeetLobbyStatus.Error(problem, detail, fatal))
    }

    // ── What the lobby shows ──────────────────────────────────────────────

    /** Opened on arrival, not on Join: a cancelled meeting says so before anyone clicks. */
    private suspend fun resolve(meetId: String) {
        when (val found = backend.info(meetId)) {
            is MeetLookup.Found -> {
                adoptInfo(meetId, found.info)
                if (!found.info.active) fail(meetId, MeetProblem.Cancelled, fatal = true)
            }
            MeetLookup.NoSuchMeet -> fail(meetId, MeetProblem.LinkInvalid, fatal = true)
            is MeetLookup.Unknown -> Unit
        }
    }

    /**
     * The meet's own call type sets the camera — but never over a toggle the
     * user already touched, since this answer lands after the lobby is up.
     */
    private suspend fun adoptInfo(meetId: String, info: MeetInfo) {
        val before = _state.value ?: return
        if (before.meetId != meetId) return
        update(meetId) { copy(info = info, title = title.ifBlank { info.title }) }
        val wanted = info.callType?.let { it == CallType.Video } ?: return
        if (!before.camTouched && !before.handedOff && wanted != before.camOn) {
            update(meetId) { copy(camOn = wanted) }
            applyCamera(meetId, wanted)
        }
    }

    private suspend fun openDevices(video: Boolean) {
        val access = media.access(video)
        val meetId = _state.value?.meetId ?: return
        update(meetId) { copy(micBlocked = !access.microphone, camBlocked = camBlocked || (video && !access.camera)) }
        // The camera as it stands NOW: the meet's own answer, or the user,
        // may have moved it while the OS was being asked.
        media.open(video = _state.value?.previewing == true)
    }

    /**
     * Opens or stops the camera [camOn] already says. Opening asks the OS
     * first; a refusal keeps it dark and the join audio-only.
     */
    private suspend fun applyCamera(meetId: String, on: Boolean) {
        if (!on) {
            media.setCamera(false)
            return
        }
        val allowed = media.access(video = true).camera
        val current = _state.value ?: return
        if (current.meetId != meetId || current.handedOff || !current.camOn) return
        update(meetId) { copy(camBlocked = !allowed) }
        if (allowed) media.setCamera(true)
    }

    /**
     * Who is already in, and whether we are, every [pollMillis] — only while
     * the lobby is up, not joining, and its window has focus. ONE timer,
     * restarted when one of those changes, never per redraw.
     */
    private suspend fun poll(meetId: String) {
        combine(focused, _state.map { it?.pollable(meetId) == true }.distinctUntilChanged()) { focus, up ->
            focus && up
        }.distinctUntilChanged().collectLatest { live ->
            if (!live) return@collectLatest
            while (true) {
                pollOnce(meetId)
                delay(pollMillis)
            }
        }
    }

    private suspend fun pollOnce(meetId: String) = coroutineScope {
        val roster = async { backend.roster(meetId) }
        val me = async { backend.presence(meetId) }
        // A failed read KEEPS what is on screen; only a successful one may say
        // "nobody here". One bad poll otherwise flips the lobby between three
        // people and none.
        roster.await()?.let { read -> update(meetId) { copy(attendees = read.inCall, rosterConfirmed = true) } }
        me.await()?.let { presence -> update(meetId) { copy(inCallElsewhere = presence.inCall) } }
    }

    /** After the presence socket comes back, once: re-seed the roster so the next read is current. */
    private suspend fun refreshAfterReconnect(meetId: String) {
        backend.online.drop(1).filter { it }.collect {
            if (_state.value?.pollable(meetId) == true) {
                backend.refreshRoster(meetId)
                pollOnce(meetId)
            }
        }
    }

    /**
     * The ownership rule, kept by the lobby itself: before the hand-off any
     * other live call (an incoming ring) takes the surface back and the lobby
     * goes; after it, the lobby retires for good the moment its own call is
     * connected, ended, or replaced — it never comes back over an ended call.
     */
    private suspend fun watchCall() {
        combine(callPhase, callSession) { _, _ -> }.collect {
            // The LATEST pair, never the emitted one: the call is seeded in two
            // writes, and a pair caught between them would retire a lobby that
            // is about to own the connect.
            val phase = callPhase.value
            val session = callSession.value
            val lobby = _state.value ?: return@collect
            if (!lobby.handedOff) {
                if (phase != CallPhase.Idle && session != null) dismiss("another call took the surface")
                return@collect
            }
            if (sawOurCall && !meetLobbyOwnsSurface(lobby, phase, session)) retire()
        }
    }

    /** After the hand-off: the call has the surface now. The tracks are the call's; only the preview goes. */
    private fun retire() {
        ZillitLog.i(TAG) { "lobby retired for ${_state.value?.meetId}" }
        media.endPreview()
        teardown()
    }

    /** Before the hand-off: the knock is forgotten locally and every device the lobby opened is let go. */
    private fun dismiss(why: String) {
        val current = _state.value ?: return
        ZillitLog.i(TAG) { "lobby for ${current.meetId} dismissed: $why" }
        if (current.handedOff) media.endPreview() else media.close()
        teardown()
    }

    private fun teardown() {
        joinJob?.cancel()
        joinJob = null
        val job = lobbyJob
        lobbyJob = null
        sawOurCall = false
        _state.value = null
        job?.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** The refusals that stop a lobby from opening or joining: another call on any line. */
    private fun busyRefusal(): String? {
        val session = callSession.value ?: return null
        if (callPhase.value == CallPhase.Idle) return null
        if (session.provider != CallProvider.LiveKit) return str(S.desktop_meet_already_on_a_call)
        val who = session.title.ifBlank { session.callerName }
            .ifBlank { session.participants.firstOrNull { it.userId != session.selfUserId }?.name.orEmpty() }
        return if (who.isBlank()) {
            str(S.desktop_meet_already_in_a_call)
        } else {
            str(S.desktop_meet_already_in_call_with, who)
        }
    }

    private fun MeetLobbyState.pollable(meetId: String): Boolean =
        this.meetId == meetId && !handedOff && status != MeetLobbyStatus.Joining

    private fun setStatus(meetId: String, status: MeetLobbyStatus) = update(meetId) { copy(status = status) }

    private fun update(meetId: String, change: MeetLobbyState.() -> MeetLobbyState) {
        _state.update { current -> if (current != null && current.meetId == meetId) current.change() else current }
    }

    /** Refusals that the same meet will give again on retry. */
    private fun MeetProblem.isFinal(): Boolean =
        this == MeetProblem.LinkInvalid || this == MeetProblem.Cancelled || this == MeetProblem.NotAllowed

    private companion object {
        const val TAG = "MeetLobby"
        const val POLL_MILLIS = 5_000L
        const val DETAIL_LOGGED = 120
    }
}
