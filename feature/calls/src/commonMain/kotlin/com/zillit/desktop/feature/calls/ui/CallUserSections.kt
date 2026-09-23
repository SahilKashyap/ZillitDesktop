package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.feature.calls.domain.CallCrewEntry
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallStatus

/**
 * What the users panel lists, section by section — the web's order
 * (`CallOverlays.tsx:246-257`): in call, ringing, left or declined, then
 * everyone else who could be added.
 */
internal data class CallUserSections(
    val inCall: List<CallTile>,
    val ringing: List<CallTile>,
    /** Participants who dropped out; re-ringable, so they sit apart from the room. */
    val dropped: List<CallParticipant>,
    /** The crew not on the call at all. Empty whenever [canAdd] is false. */
    val addable: List<CallCrewEntry>,
    /** Whether this user may ring anyone in — never on a 24x7 support call. */
    val canAdd: Boolean,
)

/**
 * The panel's sections, as a pure function of the call and the search.
 *
 * Kept out of the composable because it is the part that can be quietly
 * wrong — somebody listed twice, ourselves offered as someone to ring, a
 * person who just left still counted in the room — and none of that is
 * visible without a call with three people on it.
 *
 * [query] matches name or designation, case-insensitively, across every
 * section; blank shows everyone.
 */
internal fun callUserSections(state: CallUiState, query: String): CallUserSections {
    val session = state.session
    val selfId = session?.selfUserId.orEmpty()
    val needle = query.trim()
    fun matches(name: String, designation: String) = needle.isEmpty() ||
        name.contains(needle, ignoreCase = true) || designation.contains(needle, ignoreCase = true)

    val onStage = state.tiles.filter { matches(it.name, it.designation) }
    val staged = state.tiles.map(CallTile::userId).filter(String::isNotBlank).toSet()
    val participantIds = session?.participants.orEmpty().map(CallParticipant::userId).toSet()
    val canAdd = session != null && !session.is247Call
    return CallUserSections(
        inCall = onStage.filter { it.presence == CallStatus.InCall || it.presence == CallStatus.Caller },
        ringing = onStage.filter { it.presence == CallStatus.Ringing },
        dropped = session?.participants.orEmpty()
            .filter { it.status in DROPPED && it.userId.isNotBlank() && it.userId != selfId }
            .filter { it.userId !in staged && !it.isGuest }
            .distinctBy(CallParticipant::userId)
            .filter { matches(it.name, it.designation) }
            .sortedBy { it.name.lowercase() },
        addable = if (!canAdd) {
            emptyList()
        } else {
            state.addableCrew
                .filter { it.userId.isNotBlank() && it.userId != selfId }
                .filter { it.userId !in participantIds && it.userId !in staged }
                .distinctBy(CallCrewEntry::userId)
                .filter { matches(it.name, it.designation) }
                .sortedBy { it.name.lowercase() }
        },
        canAdd = canAdd,
    )
}

private val DROPPED = setOf(CallStatus.Declined, CallStatus.NotAnswered, CallStatus.Left)
