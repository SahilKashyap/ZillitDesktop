package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.EngineConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long a call is allowed to be silently broken.
 *
 * The media stack drops and reconnects on its own all the time — a Wi-Fi
 * handover, a sleeping switch — and ending the call on the first blip would be
 * far worse than the blip. So a lost connection starts a clock instead: if it
 * has not come back by the time it runs out, the call is over in fact and the
 * only useful thing left is to say so, rather than leave two people talking
 * into a dead channel.
 */
class ReconnectWatchdog(
    private val scope: CoroutineScope,
    private val graceMillis: Long,
    /** True while there is still a call to lose. */
    private val stillInCall: () -> Boolean,
    private val onGiveUp: (String) -> Unit,
) {
    private var timer: Job? = null

    /**
     * True while this call is riding out a media outage.
     *
     * Read by anything that would otherwise mistake a reconnection for an
     * ending: during an outage the roster and the media count both go quiet,
     * which looks exactly like everybody having left.
     */
    val isArmed: Boolean get() = timer?.isActive == true

    fun onConnectionChanged(state: EngineConnection) {
        when (state) {
            EngineConnection.Connected -> cancel()
            EngineConnection.Reconnecting, EngineConnection.Disconnected -> start()
            else -> Unit
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }

    private fun start() {
        // Already counting: a Reconnecting followed by a Disconnected is one
        // outage, and restarting the clock on each would let a flapping link
        // hold the call open indefinitely.
        if (timer != null) return
        timer = scope.launch {
            delay(graceMillis)
            if (stillInCall()) {
                ZillitLog.w(TAG) { "media never reconnected; ending the call" }
                onGiveUp("lost connection")
            }
        }
    }

    private companion object {
        const val TAG = "ReconnectWatchdog"
    }
}
