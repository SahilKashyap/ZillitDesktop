package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a Line 3 call knows beyond the roster and the media picture — the
 * web's `EngineState` extras (`policy`, `deafened`, `hidden`, `chatBlocked`,
 * `pendingGuests`), which Lines 1 and 2 have no wire for.
 *
 * Cleared with the call: none of it means anything outside the one it was
 * said in.
 */
data class Line3CallState(
    /** The host's call-level controls, as last broadcast. */
    val policy: LiveKitCallPolicy = LiveKitCallPolicy(),
    /** Whose chat the host blocked. Ours included, when it is. */
    val chatBlockedIds: Set<String> = emptySet(),
    /** Link guests waiting at the door, for the admit list. */
    val pendingGuests: List<LiveKitGuest> = emptyList(),
    /** People muted for me alone — they are never told; it is my reminder. */
    val deafened: Set<String> = emptySet(),
    /** People whose video I stopped watching — again, only here. */
    val hidden: Set<String> = emptySet(),
) {
    fun isChatBlocked(userId: String): Boolean = userId in chatBlockedIds
}

/**
 * Line 3's in-call verbs and the state they move, as a collaborator of the
 * coordinator in the way [com.zillit.desktop.feature.calls.data.InCallDataChannel]
 * is: it reads the live session and never touches the phase.
 *
 * Every verb is the web's (`App.tsx` `notifyRef.current?.request(...)` and
 * `LivekitEngine`'s host helpers), on the same wire, so a desktop host and a
 * web host are indistinguishable to the phones.
 *
 * ## Who is the host
 *
 * The original caller (`selfId === state.callerId` on the web). On an
 * outgoing call that is us by direction — the provisional session never
 * names a caller — and on an incoming or joined one it is whoever the ring
 * or the roster stamped Caller. A guest is never a host; the desktop is never
 * a guest, so the rule needs no third clause here.
 */
@Suppress("TooManyFunctions") // One function per host or local verb, plus the events each answers.
class Line3InCall(
    private val line: LiveKitLine?,
    private val engine: CallEngine,
    private val scope: CoroutineScope,
    private val session: () -> CallSession?,
    /** Our display name, for the "muted by X" announcement. */
    private val selfName: () -> String?,
    /** Something to tell the user without ending the call. */
    private val notice: (String) -> Unit,
    /**
     * The web deployment's origin (`https://dev.zillit.com`), for the invite
     * link — `<origin>/call/<callId>`, which the web's `callAppLink` mints
     * and its `/call/:id` route answers. Null when the install cannot say.
     */
    private val webOrigin: () -> String? = { null },
) {
    private val _state = MutableStateFlow(Line3CallState())
    val state: StateFlow<Line3CallState> = _state.asStateFlow()

    /** The live Line 3 call, or null: every verb here is a no-op on the other lines. */
    private fun current(): CallSession? = session()?.takeIf { it.provider == CallProvider.LiveKit }

    /** Whether we hold the call's host controls — see the class comment. */
    fun isHost(session: CallSession?): Boolean {
        session ?: return false
        if (session.provider != CallProvider.LiveKit) return false
        return session.direction == CallDirection.Outgoing ||
            (session.callerUserId.isNotBlank() && session.callerUserId == session.selfUserId)
    }

    // ── What the host's policy lets this user do ────────────────────────

    /** The restrictions bite non-hosts only, except reactions, which the web locks for everyone. */
    private fun restricted(flag: LiveKitCallPolicy.() -> Boolean): Boolean {
        val live = current() ?: return false
        return !isHost(live) && _state.value.policy.flag()
    }

    val handsRestricted: Boolean get() = restricted { handsOff }
    val shareRestricted: Boolean get() = restricted { shareLocked }
    val recordingRestricted: Boolean get() = restricted { recordingOff }
    val chatRestricted: Boolean get() = restricted { chatOff }
    val reactionsRestricted: Boolean get() = current() != null && _state.value.policy.reactionsOff

    /** The web's `lockedNote`: what the user is told when they press a locked control. */
    fun lockedNote(feature: String) = notice("The host has disabled $feature")

    /** Our own chat is blocked — by the host, by name. */
    fun selfChatBlocked(): Boolean {
        val live = current() ?: return false
        return _state.value.isChatBlocked(live.selfUserId)
    }

    // ── Reactions ───────────────────────────────────────────────────────

    private var lastReactionAtMillis = 0L

    /**
     * Throws an emoji. Nothing floats locally: the server echoes it to
     * everyone, us included (`callReaction`), so the sender sees what the
     * others see. Throttled as the phones do, and refused under a policy.
     */
    fun react(emoji: String, nowMillis: Long): Boolean {
        val live = current() ?: return false
        if (emoji.isBlank() || reactionsRestricted) return false
        if (nowMillis - lastReactionAtMillis < REACTION_MIN_GAP_MILLIS) return false
        lastReactionAtMillis = nowMillis
        scope.launch {
            line?.react(live.callUuid, emoji, live.projectId.takeIf(String::isNotBlank), live.selfUserId)
        }
        return true
    }

    // ── Local: mute or hide someone for myself ──────────────────────────

    /** Stops or resumes hearing one person. Nobody else is told. */
    fun setListen(userId: String, listen: Boolean) {
        current() ?: return
        _state.update { it.copy(deafened = if (listen) it.deafened - userId else it.deafened + userId) }
        engine.setPeerSubscribed(userId, video = false, on = listen)
    }

    /** Stops or resumes watching one person's camera. */
    fun setWatch(userId: String, watch: Boolean) {
        current() ?: return
        _state.update { it.copy(hidden = if (watch) it.hidden - userId else it.hidden + userId) }
        engine.setPeerSubscribed(userId, video = true, on = watch)
    }

    // ── Host: someone else's media, chat and seat ───────────────────────

    /**
     * Has the SFU mute someone's microphone, or stop their camera, for
     * everyone. State comes back through the room's own TrackMuted; what the
     * SFU cannot say is who did it, so that goes out over the data channel.
     */
    fun muteForEveryone(userId: String, camera: Boolean) {
        val live = current() ?: return
        val me = line?.identityNow() ?: return
        scope.launch {
            val done = line.muteParticipant(live.callUuid, userId, camera, me.copy(userId = live.selfUserId))
            if (done) {
                engine.announceHostMute(userId, camera, selfName().orEmpty().ifBlank { "The host" })
            } else {
                notice(if (camera) "Couldn't stop camera for that user" else "Couldn't mute that user")
            }
        }
    }

    fun removeFromCall(userId: String) = hostVerb("remove") { live -> line?.removeFromCall(live.callUuid, userId) }

    fun blockChat(userId: String, blocked: Boolean) =
        hostVerb("block chat") { live -> line?.blockChat(live.callUuid, userId, blocked) }

    /** Retracts a ring that has not been answered; any member may. */
    fun cancelInvite(userId: String) = hostVerb("cancel invite") { live -> line?.cancelInvite(live.callUuid, userId) }

    /**
     * Replaces the call's controls. Applied here at once so the panel's
     * switches move under the finger; the server's `callPolicyChanged` echo
     * lands on the same state a moment later.
     */
    fun setCallPolicy(policy: LiveKitCallPolicy) {
        val live = current() ?: return
        if (!isHost(live)) return
        _state.update { it.copy(policy = policy) }
        scope.launch { line?.setCallPolicy(live.callUuid, policy.toPatch()) }
    }

    fun hostAction(action: String) = hostVerb(action) { live -> line?.hostAction(live.callUuid, action) }

    fun admitGuest(guestId: String) = hostVerb("admit") { live -> line?.admitGuest(live.callUuid, guestId) }

    fun declineGuest(guestId: String) = hostVerb("decline guest") { live -> line?.declineGuest(live.callUuid, guestId) }

    private fun hostVerb(what: String, send: suspend (CallSession) -> Boolean?) {
        val live = current() ?: return
        scope.launch {
            if (send(live) != true) ZillitLog.w(TAG) { "line 3 $what not delivered" }
        }
    }

    /** `<web origin>/call/<callId>` — the same link the web's ⋮ copies; null when the link is off or unknown. */
    fun inviteLink(): String? {
        val live = current() ?: return null
        if (live.callUuid.isBlank() || _state.value.policy.linkOff) return null
        val origin = webOrigin()?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return "$origin/call/${live.callUuid}"
    }

    // ── What the server said ────────────────────────────────────────────

    fun onPolicy(callId: String, policy: LiveKitCallPolicy) {
        if (!callId.isThisCall()) return
        _state.update { it.copy(policy = policy) }
    }

    fun onChatBlock(callId: String, userId: String, blocked: Boolean) {
        if (!callId.isThisCall()) return
        _state.update {
            it.copy(chatBlockedIds = if (blocked) it.chatBlockedIds + userId else it.chatBlockedIds - userId)
        }
        val live = current() ?: return
        if (userId == live.selfUserId) {
            notice(if (blocked) "The host blocked you from chat" else "The host unblocked your chat")
        }
    }

    fun onGuestList(callId: String, guests: List<LiveKitGuest>) {
        if (!callId.isThisCall()) return
        _state.update { it.copy(pendingGuests = guests) }
    }

    fun onGuestKnocking(callId: String, name: String) {
        if (!callId.isThisCall()) return
        notice("${name.ifBlank { "A guest" }} wants to join")
    }

    /**
     * The room is up: the SFU is told again who we had muted or hidden.
     * Nothing to do today — the sets only fill once the room exists — but
     * a rejoin after a drop would otherwise forget them.
     */
    fun onJoined() {
        val snapshot = _state.value
        snapshot.deafened.forEach { engine.setPeerSubscribed(it, video = false, on = false) }
        snapshot.hidden.forEach { engine.setPeerSubscribed(it, video = true, on = false) }
    }

    /** Called when a call ends. */
    fun reset() {
        _state.value = Line3CallState()
        lastReactionAtMillis = 0L
    }

    private fun String.isThisCall(): Boolean {
        val live = current() ?: return false
        return isNotBlank() && (this == live.callUuid || this == live.roomId)
    }

    companion object {
        private const val TAG = "Line3InCall"

        /** The phones' outbound reaction throttle, and the web's `allowSend`. */
        const val REACTION_MIN_GAP_MILLIS = 600L

        const val ACTION_MUTE_ALL = "muteAll"
        const val ACTION_LOWER_HANDS = "lowerHands"
        const val ACTION_CLEAR_BACKGROUNDS = "clearBackgrounds"
    }
}
