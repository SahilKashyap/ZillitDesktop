package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.data.CallCoordinator
import com.zillit.desktop.feature.calls.data.CallEndEvent
import com.zillit.desktop.feature.calls.data.CallEndReason
import com.zillit.desktop.feature.calls.data.IN_CALL_KIND_MESSAGE
import com.zillit.desktop.feature.calls.data.IN_CALL_KIND_REACTION
import com.zillit.desktop.feature.calls.data.InCallData
import com.zillit.desktop.feature.calls.data.livekit.Line3CallState
import com.zillit.desktop.feature.calls.data.livekit.LiveKitCallPolicy
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.ShareSource
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/** Whether the stage draws its own faces, or hosts the engine's surface. */
enum class CallStageKind { Avatars, Video }

/**
 * An emoji someone threw at the call, on its way up the screen.
 *
 * [key] identifies one flight, not one emoji: the same face sent twice must
 * animate twice, so the wire id is what distinguishes them.
 */
data class CallReaction(
    val key: String,
    val emoji: String,
    val name: String,
    /**
     * When this arrived *here*, by this machine's clock.
     *
     * Not the sender's timestamp: two machines' clocks disagree by seconds
     * often enough, and this drives how much of the animation is left, so a
     * skewed stamp would either skip the flight or hold it forever.
     */
    val receivedAtMillis: Long,
)

/** One line of in-call chat. Lives for the call and no longer. */
data class CallChatLine(
    val id: String,
    val text: String,
    val name: String,
    val fromSelf: Boolean,
    val atMillis: Long,
)

/**
 * What the call surfaces draw.
 *
 * A projection of [CallCoordinator]'s state plus the presentational pieces the
 * coordinator has no business owning: the running timer, the parting notice,
 * and which of the two stage layouts is up.
 */
/**
 * What the "choose what to share" dialog is showing.
 *
 * Embedded Chromium draws no picker of its own — Chrome's lives in the
 * browser shell, not the content layer — so this is the app's replacement for
 * it, and the list behind it is a signed helper's work rather than the
 * browser's.
 */
data class SharePicker(
    val sources: List<ShareSource> = emptyList(),
    /** Which tile is selected; blank until the user picks one. */
    val chosenId: String = "",
    /** True until the first list arrives, so the dialog can say it is looking. */
    val loading: Boolean = true,
) {
    val screens: List<ShareSource> get() = sources.filter { it.isScreen }
    val windows: List<ShareSource> get() = sources.filterNot { it.isScreen }

    /** Nothing to choose from — the helper is missing or the OS refused it. */
    val isEmpty: Boolean get() = !loading && sources.isEmpty()
}

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val session: CallSession? = null,
    val micMuted: Boolean = false,
    val cameraOn: Boolean = false,
    /** Seconds since the call connected; -1 while not connected. */
    val elapsedSeconds: Long = -1,
    /** The parting message, shown briefly after a call ends. */
    val endedNotice: String? = null,
    /**
     * Something that did not work while the call carried on — a screen share
     * the OS refused, most often. Dismissible, because it is information
     * rather than a failure the call cannot continue past.
     */
    val notice: String? = null,
    val media: CallMedia = CallMedia(),
    /**
     * The share picker, while it is open.
     *
     * Null is closed. Open-but-empty is the loading state: the helper lists
     * its sources in one go and its pictures afterwards, so the dialog appears
     * immediately rather than after the slowest capture.
     */
    val sharePicker: SharePicker? = null,
    val tiles: List<CallTile> = emptyList(),
    /** The page's copy of the model. Empty when there is nothing to push. */
    val stageJson: String = "",
    val expanded: Boolean = true,
    /**
     * The video is out in its own always-on-top window. The main window then
     * shows the audio pill in its place — one browser component exists, and
     * it can be parented in one place at a time.
     */
    /**
     * The call has its own OS window.
     *
     * Opened automatically when a call connects — a call is a task of its own
     * on a desktop, not a panel inside whatever else Zillit is showing, and
     * the phones' full-screen call surface is the same idea with only one
     * window to give it. The main window keeps the pill as the way back.
     */
    val pipOpen: Boolean = false,
    /** Shrunk to the always-on-top thumbnail rather than the full surface. */
    val pipCompact: Boolean = false,
    /**
     * The user put the call back inside Zillit themselves, so it is not
     * re-opened for the rest of this call. Without this, closing the window
     * would be undone by the next state change that re-evaluates the rule.
     */
    val windowDismissed: Boolean = false,
    /**
     * Where the minimised pill sits, as an offset from its home corner. The
     * pill is draggable so it can be pushed off whatever the user needs to
     * see; zero is its resting place at the bottom right.
     */
    val pillOffsetX: Float = 0f,
    val pillOffsetY: Float = 0f,
    /**
     * The "Call users" panel — who is here, who is ringing, who left, and
     * everyone who could be added — with the addable crew snapshotted at open.
     */
    val rosterOpen: Boolean = false,
    val addableCrew: List<com.zillit.desktop.feature.calls.domain.CallCrewEntry> = emptyList(),
    /**
     * Bumped to ask the call's own window to come to the front, restored
     * and full size. The pill's expand button, pressed while the call has a
     * window of its own: that window is the call, and it was simply behind
     * the main one — flipping `expanded` there changed nothing anyone reads.
     */
    val windowRaise: Int = 0,
    /** This user's hand is up. */
    val handRaised: Boolean = false,
    /** This machine is recording the call. */
    val recording: Boolean = false,
    /** Someone else is — their name, or blank for nobody. */
    val recordedBy: String = "",
    /** The audio picker, and the hardware it offers. */
    val audioPickerOpen: Boolean = false,
    /** The ⋮ panel — recording, people and the window moves — open beside the picture. */
    val moreOpen: Boolean = false,
    val devices: com.zillit.desktop.feature.calls.domain.CallDevices =
        com.zillit.desktop.feature.calls.domain.CallDevices(),
    /** Latched once video is expected; see `projectCallUi`. */
    val videoSeen: Boolean = false,
    /** Reactions still in flight. Pruned by their own ticker, not by arrival. */
    val reactions: List<CallReaction> = emptyList(),
    /** The emoji bar is open under the control row. */
    val reactionBarOpen: Boolean = false,
    /** In-call chat: never persisted, gone when the call is. */
    val chat: List<CallChatLine> = emptyList(),
    val chatOpen: Boolean = false,
    /** Lines that arrived while the panel was shut. */
    val chatUnread: Int = 0,
    /** Line 3's extras: the host's policy, my local mutes, the guests at the door. */
    val line3: Line3CallState = Line3CallState(),
    /** Our own leg is on hold (Line 3). */
    val onHold: Boolean = false,
    /** We hold the host controls — the original caller, on Line 3. */
    val isHost: Boolean = false,
    /** The host-controls panel is open beside the picture. */
    val hostControlsOpen: Boolean = false,
    /** The admit-guests list is open. */
    val guestsOpen: Boolean = false,
    /** A second Line 3 ring while we are on a call — the Decline / End & Accept banner. */
    val secondCall: CallSession? = null,
    /** The roster row whose ⋮ menu is open, by user id. */
    val rosterMenuFor: String = "",
    /**
     * Pinned tiles, by `CallTile.key`, in the order they were pinned: they
     * take the stage and everyone else moves to the strip, as the web's pin
     * does (`CallRoom.tsx` `setPin`). This viewer's choice alone — nobody
     * else is told — and gone with the call.
     */
    val pins: List<String> = emptyList(),
) {
    /** Line 3 only: which controls the host's policy has taken from this user. */
    val handsLocked: Boolean get() = !isHost && line3.policy.handsOff
    val shareLocked: Boolean get() = !isHost && line3.policy.shareLocked
    val recordingLocked: Boolean get() = !isHost && line3.policy.recordingOff
    val chatLocked: Boolean get() = !isHost && line3.policy.chatOff
    val reactionsLocked: Boolean get() = line3.policy.reactionsOff
    val selfChatBlocked: Boolean get() = session?.selfUserId?.let(line3::isChatBlocked) == true

    /** Whether the invite link is offered: Line 3, a named call, link joining not switched off. */
    val inviteLinkOffered: Boolean
        get() = session?.provider == CallProvider.LiveKit && !session.callUuid.isNullOrBlank() &&
            !line3.policy.linkOff

    val stage: CallStageKind get() = if (videoSeen) CallStageKind.Video else CallStageKind.Avatars

    /**
     * Which line this call is on, as the phones label it.
     *
     * Worth showing rather than hiding: the two lines fail differently, and
     * the first question about any call problem is which one it was on.
     */
    val lineLabel: String
        get() = when (session?.provider) {
            CallProvider.Mediasoup -> str(S.txt_line_one)
            CallProvider.Agora -> str(S.txt_line_two)
            CallProvider.LiveKit -> str(S.txt_line_three)
            else -> ""
        }

    /**
     * Whether the engine's page should draw its shrunken layout.
     *
     * The page has one compact mode — a single tile, no name chips, no mute
     * badges — and it is right for exactly two things: the always-on-top
     * thumbnail, and the pill inside the main window.
     *
     * [expanded] alone cannot answer this any more. It describes the MAIN
     * window only, and a call that has its own window always draws the full
     * stage there whatever it says. Reading it unqualified put a 960x640 call
     * window into compact mode after a detach — one tile, everyone else
     * hidden, and no control inside that window able to undo it.
     */
    val pageCompact: Boolean
        get() = pipCompact || (!expanded && !pipOpen)

    /** One mount per call: true from the first video until the call is over. */
    val videoMounted: Boolean
        get() = videoSeen && (phase == CallPhase.InCall || phase == CallPhase.Ending)

    val connected: Int get() = tiles.count { it.presence == CallStatus.InCall }

    /**
     * Signalling ran but the engine never joined — audio will not arrive.
     *
     * Given a few seconds' grace, because a healthy join is not instant and an
     * alarm that fires on every call is one nobody reads.
     */
    val mediaDegraded: Boolean
        get() = phase == CallPhase.InCall && media.channel.isBlank() &&
            elapsedSeconds >= MEDIA_GRACE_SECONDS

    val timerText: String
        get() {
            if (elapsedSeconds < 0) return ""
            val minutes = elapsedSeconds / SECONDS_PER_MINUTE
            val seconds = elapsedSeconds % SECONDS_PER_MINUTE
            val hours = minutes / MINUTES_PER_HOUR
            return if (hours > 0) {
                "$hours:${(minutes % MINUTES_PER_HOUR).pad()}:${seconds.pad()}"
            } else {
                "${minutes.pad()}:${seconds.pad()}"
            }
        }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val MINUTES_PER_HOUR = 60
        const val MEDIA_GRACE_SECONDS = 4L
    }
}

private fun Long.pad(): String = toString().padStart(2, '0')

sealed interface CallEvent {
    data class Place(
        val chatRoomId: String,
        val receiverDeviceId: String,
        val mode: CallMode,
        val type: CallType,
        /** Who is being rung, for the outgoing card — the server won't say. */
        val displayName: String = "",
        /**
         * Which line to place it on. The two are different call plumbing on
         * the server — a separate endpoint each — so this is a real choice and
         * not a preference applied afterwards.
         */
        val provider: CallProvider = CallProvider.Agora,
        /** Line 1 rings a person; Line 2 rings one of their devices. */
        val receiverUserId: String = "",
        /** A room from a calendar or box-schedule event. See placeCall. */
        val isCalendarCall: Boolean = false,
        /** A call to the 24x7 support team. See placeCall. */
        val is247Call: Boolean = false,
        /**
         * The production the call belongs to, when it is not the open one —
         * a call placed from a widget showing another production. Null is
         * every call from the main window.
         */
        val projectId: String? = null,
        /** The caller's id ON [projectId]; project-scoped, so not the ambient one. */
        val callerUserId: String = "",
    ) : CallEvent

    data object Accept : CallEvent
    data object Decline : CallEvent
    data object HangUp : CallEvent
    data object ToggleMic : CallEvent
    data object ToggleCamera : CallEvent
    data object DismissNotice : CallEvent

    /** Expand ⇄ minimise. Presentation only; the coordinator never hears it. */
    data object ToggleStage : CallEvent

    /** Pops the video into a floating always-on-top window, or brings it back. */
    /** Puts the call back inside the main window, or takes it out again. */
    data object TogglePip : CallEvent

    /** Shrinks the call window to the thumbnail, or grows it back. */
    data object ToggleCallCompact : CallEvent

    /** The pill was dragged by this much; accumulated onto its offset. */
    data class DragPill(val dx: Float, val dy: Float) : CallEvent
    data object ToggleRoster : CallEvent
    data object ToggleAddPeople : CallEvent

    data object ToggleScreenShare : CallEvent

    /** Highlights one source in the picker without starting the share. */
    data class ChooseShareSource(val id: String) : CallEvent

    /** Shares whatever the picker has selected, and closes it. */
    data object ConfirmShareSource : CallEvent

    data object DismissSharePicker : CallEvent

    data object ToggleHand : CallEvent

    /** Starts or stops recording the call's audio on this machine. */
    data object ToggleRecording : CallEvent

    /** Opens the microphone/speaker picker, re-reading the hardware as it opens. */
    data object ToggleAudioPicker : CallEvent
    data object ToggleMore : CallEvent
    data class ChooseMicrophone(val deviceId: String) : CallEvent
    data class ChooseSpeaker(val deviceId: String) : CallEvent
    data class AddPerson(
        val entry: com.zillit.desktop.feature.calls.domain.CallCrewEntry,
    ) : CallEvent

    /** Opens or closes the emoji bar. */
    data object ToggleReactionBar : CallEvent

    /** Throws an emoji at everyone on the call. */
    data class SendReaction(val emoji: String) : CallEvent

    /** Opens or closes the in-call chat panel, clearing its unread count. */
    data object ToggleChat : CallEvent

    /** Sends one ephemeral line. */
    data class SendChat(val text: String) : CallEvent

    /** One reaction's flight is over. */
    data class ExpireReaction(val key: String) : CallEvent

    // ── Line 3 ──────────────────────────────────────────────────────────

    /** Holds our own leg, or resumes it. */
    data object ToggleHold : CallEvent

    /** Stops or resumes hearing one person, for me alone. */
    data class SetListen(val userId: String, val listen: Boolean) : CallEvent

    /** Stops or resumes watching one person's camera, for me alone. */
    data class SetWatch(val userId: String, val watch: Boolean) : CallEvent

    /** Host: the SFU mutes their microphone for everyone. */
    data class MuteForEveryone(val userId: String) : CallEvent

    /** Host: the SFU stops their camera for everyone. */
    data class StopCameraForEveryone(val userId: String) : CallEvent

    data class RemoveFromCall(val userId: String) : CallEvent
    data class BlockChat(val userId: String, val blocked: Boolean) : CallEvent

    /** Retracts a still-ringing mid-call invite. */
    data class CancelInvite(val userId: String) : CallEvent

    data class SetCallPolicy(val policy: LiveKitCallPolicy) : CallEvent
    data class HostAction(val action: String) : CallEvent
    data class AdmitGuest(val guestId: String) : CallEvent
    data class DeclineGuest(val guestId: String) : CallEvent
    data object ToggleHostControls : CallEvent
    data object ToggleGuests : CallEvent

    /** Opens or closes one roster row's ⋮ menu; blank closes. */
    data class ToggleRosterMenu(val userId: String) : CallEvent

    /** Pins one tile to the stage, or unpins it; [key] is its `CallTile.key`. */
    data class TogglePin(val key: String) : CallEvent

    /** Puts the invite link on the clipboard, and says so. */
    data object CopyInviteLink : CallEvent

    /** The second-call banner's two answers. */
    data object DeclineSecondCall : CallEvent
    data object EndAndAcceptSecondCall : CallEvent
}

class CallViewModel(
    private val coordinator: CallCoordinator,
    /**
     * The crew of the production the CALL belongs to, for the Users panel.
     * Host-supplied, and suspending because that is not always the open
     * production: a call rung from a widget, or one that carried on while the
     * user switched production, has to ask its own production for its people.
     */
    private val crew: suspend () -> List<com.zillit.desktop.feature.calls.domain.CallCrewEntry> = { emptyList() },
    /**
     * The screens and windows on this machine. Null on a host that cannot
     * enumerate them, where pressing Share sends the whole desktop — what the
     * app did before it had a picker.
     */
    private val screenSources: com.zillit.desktop.feature.calls.domain.ScreenSources? = null,
    /**
     * User id → the name we may show, for roster rows the server left
     * nameless. Host-supplied and already keep-name-private filtered; see
     * `callNameDirectory` in the desktop app.
     *
     * A flow rather than a lambda: the crew arrives in stages — cache first,
     * then a network refresh — and the tiles are rebuilt only when one of the
     * combined flows emits. A pull-lambda would leave a stage full of "Guest"
     * until something unrelated happened to re-emit.
     */
    private val nameDirectory: kotlinx.coroutines.flow.Flow<Map<String, String>> =
        kotlinx.coroutines.flow.flowOf(emptyMap()),
    /** Where "Copy invite link" puts the link. Host-supplied; null on a host without a clipboard. */
    private val copyToClipboard: ((String) -> Unit)? = null,
) : ZillitViewModel<CallUiState, CallEvent, Nothing>(CallUiState()) {

    init {
        launch {
            coordinator.phase.collect { phase ->
                setState {
                    when {
                        phase == CallPhase.Idle -> atRest(phase)
                        // Connecting lands audio calls in the pill: the
                        // workspace stays usable and the call rides along.
                        // Video opens the stage — the picture is the point —
                        // and the minimise control is one click away.
                        // A connected call moves into its own window, audio
                        // and video alike: the window IS the call surface, and
                        // `expanded` now only decides how much of it is drawn.
                        phase == CallPhase.InCall && this.phase != CallPhase.InCall ->
                            copy(
                                phase = phase,
                                expanded = true,
                                pipOpen = !windowDismissed,
                                pipCompact = false,
                            )
                        else -> copy(phase = phase)
                    }
                }
            }
        }
        launch { coordinator.ended.collect { event -> setState { copy(endedNotice = notice(event)) } } }
        // Its own collector rather than a fifth input to the combine above:
        // the hardware list changes on hot-plug, not with the call, and
        // folding it into the media projection would redraw the stage for it.
        launch { coordinator.devices.collect { list -> setState { copy(devices = list) } } }
        launch { coordinator.handRaised.collect { up -> setState { copy(handRaised = up) } } }
        launch {
            coordinator.line3State.collect { extras -> setState { copy(line3 = extras, isHost = coordinator.isHost) } }
        }
        launch { coordinator.onHold.collect { held -> setState { copy(onHold = held) } } }
        launch { coordinator.secondCall.collect { waiting -> setState { copy(secondCall = waiting) } } }
        launch { coordinator.recording.collect { on -> setState { copy(recording = on) } } }
        launch { coordinator.recordedBy.collect { name -> setState { copy(recordedBy = name) } } }
        // The parting-notice bar doubles as the in-call toast: "recording
        // saved to…" is exactly the class of message it exists for.
        launch { coordinator.toasts.collect { text -> setState { copy(endedNotice = text) } } }
        launch { coordinator.inCallData.collect(::receiveInCallData) }
        launch { coordinator.notices.collect { text -> setState { copy(notice = text) } } }
        // The pin drawn inside the video picture: the page can only ask.
        launch { coordinator.pinRequests.collect { key -> setState { togglePin(key) } } }
        // One collector, not four: the tile list is a function of all of them
        // together, and projecting on each separately would publish states
        // where the roster and the media picture disagree.
        launch {
            combine(
                coordinator.session,
                coordinator.media,
                coordinator.micMuted,
                coordinator.cameraOn,
                nameDirectory,
            ) { session, media, muted, camera, directory ->
                Inputs(session, media, muted, camera, directory)
            }
                .collect { inputs ->
                    setState {
                        projectCallUi(
                            previous = this,
                            session = inputs.session,
                            media = inputs.media,
                            micMuted = inputs.micMuted,
                            cameraOn = inputs.cameraOn,
                            selfName = coordinator.selfDisplayName,
                            nameFor = { inputs.directory[it] },
                        ).copy(isHost = coordinator.isHost)
                    }
                }
        }
        launch { runTimer() }
    }

    /** The four coordinator flows, typed, so `combine` needs no array casts. */
    /**
     * Opens or closes the users panel, snapshotting the addable crew as it
     * opens: everyone this line can ring. Snapshot rather than live — people
     * joining mid-scroll reordering the list under the pointer is how the
     * wrong person gets rung. Who is already on the call is filtered out as
     * the panel draws, so someone just added moves to Ringing at once.
     */
    private fun toggleUsers(openOnly: Boolean = false) {
        val state = currentState
        if (state.rosterOpen) {
            if (!openOnly) setState { copy(rosterOpen = false, rosterMenuFor = "") }
            return
        }
        setState { copy(rosterOpen = true) }
        val call = state.session?.callUuid
        val provider = state.session?.provider
        launch {
            val listed = crew().ringableOn(provider)
            setState {
                // The call ended, or became another one, while its crew loaded.
                if (session?.callUuid != call) this else copy(addableCrew = listed)
            }
        }
    }

    /**
     * Files an arriving reaction or line into the right list.
     *
     * The coordinator has already dropped duplicates and our own echoes, so
     * whatever gets here is new and meant to be shown. Our own sends arrive
     * through here too — one path in, so the sender's view and everyone
     * else's are built the same way.
     */
    /**
     * Share pressed: stop if we are already sharing, otherwise ask what to
     * share.
     *
     * The question is new. Chromium used to answer it by itself — badly, as it
     * turned out — and a browser embedded with no chrome has no dialog of its
     * own to offer, so the app asks instead. A host with no source list skips
     * straight to sharing the whole desktop rather than opening a dialog with
     * nothing in it.
     */
    /**
     * Shares what the picker has selected.
     *
     * A picker open with nothing chosen shares the whole desktop: that is what
     * Share means when the list could not be built at all, and it is never the
     * wrong answer to pressing a button labelled Share.
     */
    /**
     * Where the call is drawn: full window, thumbnail, or back inside Zillit.
     *
     * The three gestures are gathered because they are one decision with three
     * doors, and because each of them needs the same log line — the last
     * screen-share report could show the call window being disposed but not
     * which control had asked for it.
     */
    private fun onWindowGesture(event: CallEvent) {
        logWindowEvent(event::class.simpleName.orEmpty())
        setState { afterWindowGesture(event) }
    }

    /**
     * One line per window gesture.
     *
     * Cheap, and the last screen-share report needed it badly: the log could
     * show a call window being disposed and the UI dying with it, but not
     * which control the user had pressed to get there, so working out what
     * they had done took a full trace rather than a glance.
     */
    private fun logWindowEvent(name: String) {
        val state = currentState
        com.zillit.desktop.core.common.ZillitLog.i("CallWindowing") {
            "$name: pip=${state.pipOpen} compact=${state.pipCompact} " +
                "expanded=${state.expanded} sharing=${state.media.selfSharing}"
        }
    }

    private fun onConfirmShareSource() {
        val chosen = currentState.sharePicker?.chosenId?.takeIf(String::isNotBlank)
        coordinator.startScreenShare(chosen)
        setState { copy(sharePicker = null) }
    }

    private fun onToggleScreenShare() {
        if (currentState.media.selfSharing) {
            coordinator.stopScreenShare()
            return
        }
        if (currentState.sharePicker != null) return
        val sources = screenSources ?: run {
            coordinator.startScreenShare(null)
            return
        }
        setState { copy(sharePicker = SharePicker()) }
        launch {
            sources.list().collect { listed ->
                setState {
                    // Dropped if the user closed the dialog while the helper
                    // was still working: a picker that reappears because a
                    // late preview arrived is a ghost.
                    val open = sharePicker ?: return@setState this
                    copy(
                        sharePicker = open.copy(
                            sources = listed,
                            loading = false,
                            // The screen is preselected so Share means
                            // something the instant the dialog appears.
                            chosenId = open.chosenId.ifBlank {
                                listed.firstOrNull { source -> source.isScreen }?.id.orEmpty()
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun receiveInCallData(data: InCallData) {
        val mine = data.fromUserId.isNotBlank() && data.fromUserId == coordinator.session.value?.selfUserId
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        when (data.kind) {
            IN_CALL_KIND_REACTION -> {
                if (data.emoji.isBlank()) return
                setState {
                    copy(
                        reactions = (reactions + CallReaction(
                            key = data.id.ifBlank { "${data.fromUserId}-${data.atMillis}" },
                            emoji = data.emoji,
                            name = if (mine) "" else data.name,
                            receivedAtMillis = now,
                        )).fresh(now).takeLast(MAX_REACTIONS_ON_SCREEN),
                    )
                }
            }

            IN_CALL_KIND_MESSAGE -> {
                if (data.text.isBlank()) return
                setState {
                    copy(
                        chat = (chat + CallChatLine(
                            id = data.id,
                            text = data.text,
                            name = data.name,
                            fromSelf = mine,
                            atMillis = data.atMillis,
                        )).takeLast(MAX_CHAT_LINES),
                        // Our own line is not news, and the panel is open when
                        // we sent it anyway.
                        chatUnread = if (chatOpen || mine) chatUnread else chatUnread + 1,
                    )
                }
            }
        }
    }

    private data class Inputs(
        val session: CallSession?,
        val media: CallMedia,
        val micMuted: Boolean,
        val cameraOn: Boolean,
        val directory: Map<String, String> = emptyMap(),
    )

    // Exhaustive dispatch over the sealed event set — the branch count is the
    // pattern, not a complexity smell (see HomeFeedViewModel's onEvent).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: CallEvent) {
        when (event) {
            is CallEvent.Place -> place(event)
            CallEvent.Accept -> coordinator.accept()
            CallEvent.Decline -> coordinator.decline()
            CallEvent.HangUp -> coordinator.hangUp()
            CallEvent.ToggleMic -> coordinator.toggleMicrophone()
            CallEvent.ToggleCamera -> coordinator.toggleCamera()
            CallEvent.DismissNotice -> setState { copy(endedNotice = null, notice = null) }
            // `expanded` describes the main window. While the call has its own
            // window there is nothing here to expand — the pill IS how the main
            // window represents it — so the flag is left alone rather than
            // moved somewhere nothing reads it back.
            CallEvent.ToggleStage -> onWindowGesture(event)
            CallEvent.TogglePip -> onWindowGesture(event)
            CallEvent.ToggleCallCompact -> onWindowGesture(event)
            is CallEvent.DragPill -> setState {
                copy(pillOffsetX = pillOffsetX + event.dx, pillOffsetY = pillOffsetY + event.dy)
            }
            CallEvent.ToggleRoster -> toggleUsers()
            CallEvent.ToggleScreenShare -> onToggleScreenShare()
            is CallEvent.ChooseShareSource -> setState {
                copy(sharePicker = sharePicker?.copy(chosenId = event.id))
            }
            CallEvent.ConfirmShareSource -> onConfirmShareSource()
            CallEvent.DismissSharePicker -> setState { copy(sharePicker = null) }
            CallEvent.ToggleHand -> coordinator.toggleHand()
            CallEvent.ToggleRecording -> coordinator.toggleRecording()
            // A panel beside the picture, not a popup over it: a heavyweight
            // video surface paints over anything Compose floats above it, so
            // a menu opened upward from the dock was simply never seen.
            CallEvent.ToggleMore -> setState { copy(moreOpen = !moreOpen) }
            CallEvent.ToggleAudioPicker -> {
                // Re-read on open: a headset plugged in while the menu was
                // shut is otherwise invisible until the SDK happens to notice.
                if (!currentState.audioPickerOpen) coordinator.refreshDevices()
                setState { copy(audioPickerOpen = !audioPickerOpen) }
            }
            is CallEvent.ChooseMicrophone -> {
                coordinator.chooseMicrophone(event.deviceId)
                setState { copy(audioPickerOpen = false) }
            }
            is CallEvent.ChooseSpeaker -> {
                coordinator.chooseSpeaker(event.deviceId)
                setState { copy(audioPickerOpen = false) }
            }
            // "Add people" opens the same panel: the addable crew is its last section.
            CallEvent.ToggleAddPeople -> toggleUsers(openOnly = true)
            // The panel stays open — the row moves to Ringing, and adding a
            // second person should not mean opening it again.
            is CallEvent.AddPerson -> coordinator.addUser(event.entry.userId, event.entry.deviceId, event.entry.name)
            CallEvent.ToggleReactionBar -> setState { copy(reactionBarOpen = !reactionBarOpen) }
            is CallEvent.SendReaction -> coordinator.sendReaction(event.emoji)
            CallEvent.ToggleChat -> setState {
                // Opening is reading: the badge goes with the panel.
                copy(chatOpen = !chatOpen, chatUnread = if (chatOpen) chatUnread else 0)
            }
            is CallEvent.SendChat -> coordinator.sendInCallMessage(event.text)
            is CallEvent.ExpireReaction -> setState {
                copy(reactions = reactions.filterNot { it.key == event.key })
            }
            is CallEvent.TogglePin -> setState { togglePin(event.key) }
            else -> onLine3Event(event)
        }
    }

    /** Line 3's verbs — the web's host, hold, local-mute and guest controls — dispatched on their own. */
    @Suppress("CyclomaticComplexMethod") // One branch per verb; see onEvent.
    private fun onLine3Event(event: CallEvent) {
        when (event) {
            CallEvent.ToggleHold -> coordinator.toggleHold()
            is CallEvent.SetListen -> coordinator.setListen(event.userId, event.listen)
            is CallEvent.SetWatch -> coordinator.setWatch(event.userId, event.watch)
            is CallEvent.MuteForEveryone -> coordinator.muteForEveryone(event.userId)
            is CallEvent.StopCameraForEveryone -> coordinator.stopCameraForEveryone(event.userId)
            is CallEvent.RemoveFromCall -> coordinator.removeFromCall(event.userId)
            is CallEvent.BlockChat -> coordinator.blockChat(event.userId, event.blocked)
            is CallEvent.CancelInvite -> coordinator.cancelInvite(event.userId)
            is CallEvent.SetCallPolicy -> coordinator.setCallPolicy(event.policy)
            is CallEvent.HostAction -> coordinator.hostAction(event.action)
            is CallEvent.AdmitGuest -> coordinator.admitGuest(event.guestId)
            is CallEvent.DeclineGuest -> coordinator.declineGuest(event.guestId)
            CallEvent.ToggleHostControls -> setState { copy(hostControlsOpen = !hostControlsOpen) }
            CallEvent.ToggleGuests -> setState { copy(guestsOpen = !guestsOpen) }
            is CallEvent.ToggleRosterMenu -> setState {
                copy(rosterMenuFor = if (rosterMenuFor == event.userId) "" else event.userId)
            }
            CallEvent.CopyInviteLink -> copyInviteLink()
            CallEvent.DeclineSecondCall -> coordinator.declineSecondCall()
            CallEvent.EndAndAcceptSecondCall -> coordinator.endAndAcceptSecondCall()
            else -> Unit
        }
    }

    /** The web's ⋮ row: the link on the clipboard and a line saying so — or the link itself, with no clipboard. */
    private fun copyInviteLink() {
        val link = coordinator.inviteLink() ?: run {
            setState { copy(notice = str(S.desktop_call_no_invite_link)) }
            return
        }
        val clipboard = copyToClipboard
        if (clipboard == null) {
            setState { copy(endedNotice = link) }
            return
        }
        clipboard(link)
        setState { copy(endedNotice = str(S.desktop_call_invite_link_copied)) }
    }

    private fun place(event: CallEvent.Place) = coordinator.placeCall(
        chatRoomId = event.chatRoomId,
        receiverDeviceId = event.receiverDeviceId,
        mode = event.mode,
        type = event.type,
        displayName = event.displayName,
        provider = event.provider,
        receiverUserId = event.receiverUserId,
        isCalendarCall = event.isCalendarCall,
        is247Call = event.is247Call,
        projectId = event.projectId,
        callerUserId = event.callerUserId,
    )

    /** Everything a call leaves behind, cleared in one place. */
    private fun CallUiState.atRest(phase: CallPhase) = copy(
        // The picker is a window of its own, so a call that ends while it is
        // open would otherwise leave it floating over the workspace with
        // nothing behind it — and its source ids frozen at the moment that
        // call ended. Carried into the next call, Share would either capture
        // a window the user never picked or do nothing at all, since
        // onToggleScreenShare returns early while a picker is open.
        sharePicker = null,
        phase = phase,
        session = null,
        media = CallMedia(),
        tiles = emptyList(),
        stageJson = "",
        expanded = true,
        pipOpen = false,
        pipCompact = false,
        // Cleared with the call: dismissing one call's window is not a
        // standing preference against the next one.
        windowDismissed = false,
        pillOffsetX = 0f,
        pillOffsetY = 0f,
        rosterOpen = false,
        moreOpen = false,
        videoSeen = false,
        recording = false,
        recordedBy = "",
        // Nothing said in a call outlives it. There is no store behind these
        // lists, and re-showing the last call's chat in the next one would be
        // the one thing every other client promises not to do.
        reactions = emptyList(),
        reactionBarOpen = false,
        chat = emptyList(),
        chatOpen = false,
        chatUnread = 0,
        notice = null,
        line3 = Line3CallState(),
        onHold = false,
        isHost = false,
        hostControlsOpen = false,
        guestsOpen = false,
        rosterMenuFor = "",
        pins = emptyList(),
        // The next call may belong to another production, with other people.
        addableCrew = emptyList(),
    )

    /**
     * The timer restarts from zero whenever the phase enters InCall, and
     * `collectLatest` retires the previous count the moment the phase moves —
     * a call that reconnects does not inherit the old call's clock.
     */
    private suspend fun runTimer() {
        coordinator.phase.collectLatest { phase ->
            // Ending keeps whatever the clock read. The stage stays on screen
            // through teardown, and a 5:23 call that reads "Connecting…" the
            // instant End call is pressed is telling the user the opposite of
            // what happened.
            if (phase == CallPhase.Ending) return@collectLatest
            if (phase != CallPhase.InCall) {
                setState { copy(elapsedSeconds = -1) }
                return@collectLatest
            }
            var seconds = 0L
            while (true) {
                setState { copy(elapsedSeconds = seconds) }
                delay(TIMER_TICK_MS)
                seconds++
            }
        }
    }

    /** The web's ended wording (`CallOverlays.tsx:132-142`, `App.tsx:2456-2457`). */
    private fun notice(event: CallEndEvent): String =
        when (event.reason) {
            CallEndReason.Hungup -> str(S.txt_call_ended)
            CallEndReason.RemoteEnded -> str(S.txt_call_ended)
            CallEndReason.Declined -> str(S.declined_events)
            CallEndReason.Busy -> str(S.txt_busy)
            CallEndReason.Timeout -> str(S.desktop_no_answer)
            CallEndReason.PickedElsewhere -> str(S.desktop_call_answered_on_another_device)
            CallEndReason.Error -> str(S.desktop_call_failed)
        }

    /**
     * Drops reactions whose flight is already over.
     *
     * Expiry normally comes from the animation itself, but the stage is not
     * composed while the call is a pill or a thumbnail — so reactions arriving
     * then would sit in state and all replay at once on the way back. This is
     * the floor under that: whatever the UI is doing, a reaction older than
     * one flight is gone.
     */
    private fun List<CallReaction>.fresh(nowMillis: Long): List<CallReaction> =
        filter { nowMillis - it.receivedAtMillis < CallReactions.FLIGHT_MILLIS }

    private companion object {
        const val TIMER_TICK_MS = 1_000L

        /**
         * Caps, not policy. Nothing here is meant to accumulate — these stop a
         * long call, or somebody leaning on a key, from growing a list forever.
         */
        const val MAX_REACTIONS_ON_SCREEN = 24
        const val MAX_CHAT_LINES = 300
    }
}

/** The emoji everyone can throw, and how long one stays up. */
object CallReactions {
    /**
     * Fixed and shared with the phones — a reaction is only legible if the
     * other end draws the same face, and a free-text emoji field would let a
     * desktop send something a phone renders as a box.
     */
    val Palette = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🎉", "🔥")

    /**
     * How long one flight lasts: rises and fades over this.
     *
     * The animation's own completion is what drops it from state, so there is
     * no second timer that could disagree with what is on screen.
     */
    const val FLIGHT_MILLIS = 3_200L
}

/**
 * Where the call is drawn after one of the three window gestures: full
 * window, thumbnail, or back inside Zillit.
 *
 * Pure, so the rule is pinned by a test rather than by pressing buttons on a
 * live call — the pill's expand button was ignored for weeks while the call
 * had a window of its own, and nothing but a user noticed.
 */
internal fun CallUiState.afterWindowGesture(event: CallEvent): CallUiState = when (event) {
    // Leaving PiP restores the stage: the user asked to see the video, and
    // the pill is where it was hiding, not where it goes.
    CallEvent.TogglePip ->
        if (pipOpen) {
            // Deliberate: remembered so the auto-open rule does not
            // immediately drag it back out.
            copy(pipOpen = false, expanded = true, windowDismissed = true, pipCompact = false)
        } else {
            copy(pipOpen = true, expanded = false, windowDismissed = false)
        }
    CallEvent.ToggleCallCompact -> copy(pipCompact = !pipCompact)
    // With the call in its own window the pill's expand button means "show
    // me the call": bring that window up, full size. It used to be ignored
    // here, and the window sat behind the main one while the button did
    // nothing however often it was pressed (a dozen presses in two minutes in the
    // 2026-09-23 log).
    CallEvent.ToggleStage ->
        if (pipOpen) copy(pipCompact = false, windowRaise = windowRaise + 1) else copy(expanded = !expanded)
    else -> this
}

/**
 * Pins [key], or unpins it if it already is.
 *
 * Pins held by people no longer on the stage are dropped first, so a pin
 * left behind by someone who hung up neither counts toward the cap nor comes
 * back to life if they are rung again. Past [MAX_PINS] the oldest goes: the
 * press always does what it says, which a refusal would not.
 */
internal fun CallUiState.togglePin(key: String): CallUiState {
    if (key.isBlank()) return this
    val live = pins.filter { pinned -> tiles.any { it.key == pinned } }
    val next = when {
        key in live -> live - key
        tiles.none { it.key == key } -> return this
        else -> (live + key).takeLast(MAX_PINS)
    }
    return copy(
        pins = next,
        stageJson = if (tiles.isEmpty()) "" else stageJson(tiles, columnsFor(tiles.size), next),
    )
}

/** The pinned tiles present on the stage, in pin order. */
fun pinnedTiles(tiles: List<CallTile>, pins: List<String>): List<CallTile> =
    pins.mapNotNull { key -> tiles.firstOrNull { it.key == key } }

/** The web's ceiling on pins (`CallRoom.tsx` `setPin`). */
const val MAX_PINS = 6

/**
 * The crew a call on [provider] can ring, by name. A device id is Line 2's
 * addressing; Lines 1 and 3 ring a person, so someone with no registered
 * device is still reachable there and filtering them out hides a valid
 * invitee.
 */
internal fun List<com.zillit.desktop.feature.calls.domain.CallCrewEntry>.ringableOn(
    provider: CallProvider?,
): List<com.zillit.desktop.feature.calls.domain.CallCrewEntry> =
    filter { entry ->
        if (provider == CallProvider.Mediasoup || provider == CallProvider.LiveKit) {
            entry.userId.isNotBlank()
        } else {
            entry.deviceId.isNotBlank()
        }
    }.sortedBy { it.name.lowercase() }
