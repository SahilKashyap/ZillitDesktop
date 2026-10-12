package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.EngineConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a meet's media recovery has got to. */
sealed interface MeetRejoinState {
    /** Media is fine, or recovering has not been needed. */
    data object Idle : MeetRejoinState

    /** Recovering: attempt [attempt] of [of]. */
    data class Recovering(val attempt: Int, val of: Int) : MeetRejoinState

    /** Every automatic attempt is spent: a Rejoin button, never another loop. */
    data object Offered : MeetRejoinState
}

/** What one recovery attempt found. */
sealed interface MeetRecovery {
    /** Fresh credentials for the room: connect with them. */
    data class Reconnect(val url: String, val token: String) : MeetRecovery

    /** `inCall` is still true: the SDK's own reconnect is under way — no new token. */
    data object SdkReconnecting : MeetRecovery

    /** The room is gone (`active: false`): the meeting is over. */
    data object Ended : MeetRecovery

    /** Nothing could be read or minted this time. */
    data object Failed : MeetRecovery
}

/**
 * A Line 1 meet whose media dropped: `GET …/me` first, and only then a token.
 *
 *  - `active: true, inCall: false` — we are out of a running room: mint and
 *    connect again;
 *  - `inCall: true` — the SDK's own reconnect is still holding our place:
 *    let it finish, and never mint over it;
 *  - `active: false` — the meeting is over.
 *
 * Capped at three attempts (1 s, 3 s, 8 s), then a manual Rejoin; one press
 * is one more attempt. Never a loop: a client that rejoins forever is a ghost
 * participant for everybody else. Restored media at any point stops it.
 * Only meets — every other call keeps its reconnect watchdog.
 */
class MeetRejoin(
    private val scope: CoroutineScope,
    /** One attempt's reading of the room — `/me`, and a mint when it says we are out. */
    private val attempt: suspend (meetId: String) -> MeetRecovery,
    /** Connect again with fresh credentials, through the call's own connect. */
    private val reconnect: (url: String, token: String) -> Unit,
    /** The room is gone: end the call as the meeting ended. */
    private val ended: () -> Unit,
    private val delays: List<Long> = DELAYS_MILLIS,
) {
    private val _state = MutableStateFlow<MeetRejoinState>(MeetRejoinState.Idle)
    val state: StateFlow<MeetRejoinState> = _state.asStateFlow()

    private var job: Job? = null
    private var meetId = ""

    /** The engine's link moved, on the meet [callId]. */
    fun onConnection(callId: String, connection: EngineConnection) {
        when (connection) {
            EngineConnection.Connected -> restored()
            EngineConnection.Reconnecting, EngineConnection.Disconnected, EngineConnection.Failed -> dropped(callId)
            EngineConnection.Connecting -> Unit
        }
    }

    /** The Rejoin button: one more attempt, at once. */
    fun rejoinNow() {
        if (_state.value != MeetRejoinState.Offered || meetId.isBlank()) return
        run(listOf(0L))
    }

    /** The call is over, or a new one began: nothing carries over. */
    fun reset() {
        job?.cancel()
        job = null
        meetId = ""
        _state.value = MeetRejoinState.Idle
    }

    private fun dropped(callId: String) {
        // Already recovering — a Reconnecting followed by a Disconnected is one
        // outage — or already offering the button: no second sequence.
        if (job?.isActive == true || _state.value == MeetRejoinState.Offered) return
        meetId = callId
        ZillitLog.i(TAG) { "meet $callId media dropped; recovering" }
        run(delays)
    }

    private fun restored() {
        if (job == null && _state.value == MeetRejoinState.Idle) return
        ZillitLog.i(TAG) { "meet $meetId media restored" }
        job?.cancel()
        job = null
        _state.value = MeetRejoinState.Idle
    }

    private fun run(waits: List<Long>) {
        val target = meetId
        job?.cancel()
        job = scope.launch {
            waits.forEachIndexed { index, wait ->
                delay(wait)
                _state.value = MeetRejoinState.Recovering(attempt = index + 1, of = waits.size)
                when (val found = attempt(target)) {
                    is MeetRecovery.Reconnect -> {
                        ZillitLog.i(TAG) { "meet $target: out of a running room; reconnecting (${index + 1})" }
                        reconnect(found.url, found.token)
                    }
                    MeetRecovery.Ended -> {
                        ZillitLog.i(TAG) { "meet $target is over; ending" }
                        _state.value = MeetRejoinState.Idle
                        ended()
                        return@launch
                    }
                    MeetRecovery.SdkReconnecting, MeetRecovery.Failed ->
                        ZillitLog.i(TAG) { "meet $target attempt ${index + 1}: ${found::class.simpleName}" }
                }
            }
            // Restored media cancels this job before it gets here.
            ZillitLog.w(TAG) { "meet $target not recovered; offering Rejoin" }
            _state.value = MeetRejoinState.Offered
        }
    }

    private companion object {
        const val TAG = "MeetRejoin"

        /** The contract's three attempts, and how long before each. */
        val DELAYS_MILLIS = listOf(1_000L, 3_000L, 8_000L)
    }
}
