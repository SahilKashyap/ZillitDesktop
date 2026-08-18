package com.zillit.desktop.core.sync

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One answer to "are we online?", built from what the app already knows.
 *
 * There is no platform network callback worth having on desktop — an
 * interface being up says nothing about whether Zillit's hosts answer — so
 * the signal comes from traffic that happens anyway:
 *
 * - **Every REST call reports in** ([report]). A transport failure flips to
 *   offline at once; any success flips back. Zero extra requests.
 * - **The socket coming up** ([socketConnected]) is a reliable "back" signal —
 *   it reconnects with backoff on its own, so it is usually the first thing
 *   to notice a network returning. Its going down is *not* treated as
 *   offline: it drops for reasons that have nothing to do with the network.
 * - **A probe while offline** ([probe]), on a timer, for the quiet case where
 *   nothing else would ever ask again — a laptop closed in a car park and
 *   opened at base camp with the app idle.
 *
 * Starts optimistic. A machine that has never sent a request has no evidence
 * either way, and "offline" is a claim the UI acts on.
 */
class ConnectivityMonitor(
    private val scope: CoroutineScope,
    private val socketConnected: Flow<Boolean>? = null,
    private val probe: (suspend () -> Boolean)? = null,
    private val probeIntervalMillis: Long = DEFAULT_PROBE_INTERVAL_MILLIS,
) {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var probing: Job? = null

    fun start() {
        socketConnected
            ?.distinctUntilChanged()
            ?.filter { it }
            ?.onEach { becameOnline("socket connected") }
            ?.launchIn(scope)
    }

    /** What every REST call says on the way out — null for success. */
    fun report(error: ZillitError?) {
        when (error) {
            null -> becameOnline("request succeeded")
            is ZillitError.NoConnection, is ZillitError.Timeout -> becameOffline(error)
            // A refusal is proof the server is reachable.
            else -> becameOnline("server answered")
        }
    }

    /** Asks the probe now rather than at the next tick — a "Retry" click, say. */
    suspend fun checkNow(): Boolean {
        val reachable = probe?.invoke() ?: return _online.value
        if (reachable) becameOnline("probe answered") else becameOffline(null)
        return reachable
    }

    private fun becameOnline(why: String) {
        if (_online.compareAndSet(expect = false, update = true)) {
            ZillitLog.i(TAG) { "online: $why" }
        }
        probing?.cancel()
        probing = null
    }

    private fun becameOffline(error: ZillitError?) {
        if (_online.compareAndSet(expect = true, update = false)) {
            ZillitLog.i(TAG) { "offline: ${error?.let { it::class.simpleName } ?: "probe failed"}" }
        }
        if (probe != null && probing?.isActive != true) {
            probing = scope.launch { probeUntilBack() }
        }
    }

    private suspend fun probeUntilBack() {
        while (scope.isActive && !_online.value) {
            delay(probeIntervalMillis)
            if (_online.value) return
            val reachable = runCatching { probe?.invoke() ?: false }.getOrDefault(false)
            if (reachable) becameOnline("probe answered")
        }
    }

    companion object {
        const val DEFAULT_PROBE_INTERVAL_MILLIS = 15_000L
        private const val TAG = "Connectivity"
    }
}
