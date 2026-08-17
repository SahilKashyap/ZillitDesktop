package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.calls.data.CallCoordinator
import com.zillit.desktop.feature.calls.data.CallEndEvent
import com.zillit.desktop.feature.calls.data.CallEndReason
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/** Whether the stage draws its own faces, or hosts the engine's surface. */
enum class CallStageKind { Avatars, Video }

/**
 * What the call surfaces draw.
 *
 * A projection of [CallCoordinator]'s state plus the presentational pieces the
 * coordinator has no business owning: the running timer, the parting notice,
 * and which of the two stage layouts is up.
 */
data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val session: CallSession? = null,
    val micMuted: Boolean = false,
    val cameraOn: Boolean = false,
    /** Seconds since the call connected; -1 while not connected. */
    val elapsedSeconds: Long = -1,
    /** The parting message, shown briefly after a call ends. */
    val endedNotice: String? = null,
    val media: CallMedia = CallMedia(),
    val tiles: List<CallTile> = emptyList(),
    /** The page's copy of the model. Empty when there is nothing to push. */
    val stageJson: String = "",
    val expanded: Boolean = true,
    val rosterOpen: Boolean = false,
    /** The add-people picker, with the addable crew snapshotted at open. */
    val addPeopleOpen: Boolean = false,
    val addableCrew: List<com.zillit.desktop.feature.calls.domain.CallCrewEntry> = emptyList(),
    /** Latched once video is expected; see `projectCallUi`. */
    val videoSeen: Boolean = false,
) {
    val stage: CallStageKind get() = if (videoSeen) CallStageKind.Video else CallStageKind.Avatars

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
    ) : CallEvent

    data object Accept : CallEvent
    data object Decline : CallEvent
    data object HangUp : CallEvent
    data object ToggleMic : CallEvent
    data object ToggleCamera : CallEvent
    data object DismissNotice : CallEvent

    /** Expand ⇄ minimise. Presentation only; the coordinator never hears it. */
    data object ToggleStage : CallEvent
    data object ToggleRoster : CallEvent
    data object ToggleAddPeople : CallEvent
    data class AddPerson(
        val entry: com.zillit.desktop.feature.calls.domain.CallCrewEntry,
    ) : CallEvent
}

class CallViewModel(
    private val coordinator: CallCoordinator,
    /** The production's crew, for the add-people picker. Host-supplied. */
    private val crew: () -> List<com.zillit.desktop.feature.calls.domain.CallCrewEntry> = { emptyList() },
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
                        phase == CallPhase.InCall && this.phase != CallPhase.InCall ->
                            copy(phase = phase, expanded = session?.type == CallType.Video)
                        else -> copy(phase = phase)
                    }
                }
            }
        }
        launch { coordinator.ended.collect { event -> setState { copy(endedNotice = notice(event)) } } }
        // One collector, not four: the tile list is a function of all of them
        // together, and projecting on each separately would publish states
        // where the roster and the media picture disagree.
        launch {
            combine(
                coordinator.session,
                coordinator.media,
                coordinator.micMuted,
                coordinator.cameraOn,
            ) { session, media, muted, camera -> Inputs(session, media, muted, camera) }
                .collect { inputs ->
                    setState {
                        projectCallUi(
                            previous = this,
                            session = inputs.session,
                            media = inputs.media,
                            micMuted = inputs.micMuted,
                            cameraOn = inputs.cameraOn,
                            selfName = coordinator.selfDisplayName,
                        )
                    }
                }
        }
        launch { runTimer() }
    }

    /** The four coordinator flows, typed, so `combine` needs no array casts. */
    /**
     * Snapshots the addable crew at open: everyone with a device, minus the
     * roster. Snapshot rather than live — people joining mid-scroll reordering
     * the list under the pointer is how the wrong person gets rung.
     */
    private fun toggleAddPeople() {
        setState {
            if (addPeopleOpen) {
                copy(addPeopleOpen = false)
            } else {
                val onCall = session?.participants?.map { it.userId }?.toSet().orEmpty()
                copy(
                    addPeopleOpen = true,
                    addableCrew = crew()
                        .filter { it.deviceId.isNotBlank() && it.userId !in onCall }
                        .sortedBy { it.name.lowercase() },
                )
            }
        }
    }

    private data class Inputs(
        val session: CallSession?,
        val media: CallMedia,
        val micMuted: Boolean,
        val cameraOn: Boolean,
    )

    override fun onEvent(event: CallEvent) {
        when (event) {
            is CallEvent.Place -> coordinator.placeCall(
                event.chatRoomId, event.receiverDeviceId, event.mode, event.type, event.displayName,
            )
            CallEvent.Accept -> coordinator.accept()
            CallEvent.Decline -> coordinator.decline()
            CallEvent.HangUp -> coordinator.hangUp()
            CallEvent.ToggleMic -> coordinator.toggleMicrophone()
            CallEvent.ToggleCamera -> coordinator.toggleCamera()
            CallEvent.DismissNotice -> setState { copy(endedNotice = null) }
            CallEvent.ToggleStage -> setState { copy(expanded = !expanded) }
            CallEvent.ToggleRoster -> setState { copy(rosterOpen = !rosterOpen) }
            CallEvent.ToggleAddPeople -> toggleAddPeople()
            is CallEvent.AddPerson -> {
                coordinator.addUser(event.entry.userId, event.entry.deviceId, event.entry.name)
                setState { copy(addPeopleOpen = false) }
            }
        }
    }

    /** Everything a call leaves behind, cleared in one place. */
    private fun CallUiState.atRest(phase: CallPhase) = copy(
        phase = phase,
        session = null,
        media = CallMedia(),
        tiles = emptyList(),
        stageJson = "",
        expanded = true,
        rosterOpen = false,
        videoSeen = false,
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

    private fun notice(event: CallEndEvent): String {
        val who = event.session.callerName.ifBlank { event.session.title }.ifBlank { "The call" }
        return when (event.reason) {
            CallEndReason.Hungup -> "Call ended"
            CallEndReason.RemoteEnded -> "Call ended"
            CallEndReason.Declined -> "Call declined"
            CallEndReason.Busy -> "$who is on another call"
            CallEndReason.Timeout -> "No answer"
            CallEndReason.PickedElsewhere -> "Answered on another device"
            CallEndReason.Error -> "Call failed"
        }
    }

    private companion object {
        const val TIMER_TICK_MS = 1_000L
    }
}
