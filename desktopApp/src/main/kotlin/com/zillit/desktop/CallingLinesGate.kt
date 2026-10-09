package com.zillit.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.CallEngines
import com.zillit.desktop.core.config.CallLineNumbering
import com.zillit.desktop.core.config.CallingLinesConfig
import com.zillit.desktop.core.config.CallingLinesPolicy
import com.zillit.desktop.core.config.LegacyCallingFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Which call lines this desktop offers, per production, and what each is called.
 *
 * Remote Config's `calling_lines` decides when it is set — the same key and the
 * same rules as Android, see [CallingLinesPolicy]. While it is not, the older
 * flags do ([LegacyCallingFlags]): Agora and mediasoup as they always were,
 * LiveKit where `line_three_enabled_in` names the production.
 *
 * The desktop has no Firebase SDK to push an update, so the key is fetched
 * again on every production switch, whenever a chat's call buttons are drawn
 * and the last fetch is older than [ON_USE], and every [POLL] regardless.
 * A failed fetch keeps the last answer; a run that has never fetched reads
 * the last good value saved on this machine.
 */
class CallingLinesGate(
    /** The fetch: every published entry, the previous answer when offline, null until one succeeds. */
    private val fetch: suspend (minInterval: Duration) -> Map<String, String>?,
    /** Whether a fetch younger than the interval is in hand, so asking again would be wasted. */
    private val isFresh: (minInterval: Duration) -> Boolean,
    /** Whether the install has the LiveKit backend at all — no socket URL, no LiveKit whatever the key says. */
    liveKitConfigured: Boolean,
    private val loadLastGood: suspend () -> String,
    private val saveLastGood: suspend (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val installed =
        if (liveKitConfigured) CallEngines.SUPPORTED else CallEngines.SUPPORTED - CallEngines.LIVEKIT

    // Compose state, so a call menu that read the lines redraws when a fetch changes them.
    private var policy by mutableStateOf(CallingLinesPolicy(config = null, installed = installed))
    private var legacy = LegacyCallingFlags()
    private var parsedRaw: String? = null
    private var parsed: CallingLinesConfig? = null
    private val asking = AtomicBoolean(false)
    private val refreshing = Mutex()

    /** Whether a call may be started on [engine] in [projectId]. */
    fun isAvailable(engine: String, projectId: String?): Boolean = policy.isAvailable(engine, projectId)

    /** The engines offered in [projectId], "Line 1" first; empty hides the call buttons. */
    fun available(projectId: String?): List<String> = policy.available(projectId)

    /** Reads the key now, then every [POLL] for as long as [scope] lives. */
    fun start() {
        scope.launch {
            while (isActive) {
                refresh()
                delay(POLL)
            }
        }
    }

    /** Asks again in the background, unless a fetch younger than [ON_USE] is already in hand. */
    fun refreshIfStale() {
        if (isFresh(ON_USE) || !asking.compareAndSet(false, true)) return
        scope.launch {
            try {
                refresh(ON_USE)
            } finally {
                asking.set(false)
            }
        }
    }

    /** Re-reads the key, fetching unless the last attempt is younger than [minInterval]. */
    suspend fun refresh(minInterval: Duration = Duration.ZERO) = refreshing.withLock {
        val entries = fetch(minInterval)
        val fromFirebase = entries != null
        val raw = if (fromFirebase) entries?.get(CallingLinesConfig.KEY)?.trim().orEmpty() else loadLastGood()
        val config = parse(raw)
        if (fromFirebase && config != null && raw != loadLastGood()) saveLastGood(raw)
        val flags = LegacyCallingFlags.from(entries)
        if (config == policy.config && flags == legacy) return@withLock
        legacy = flags
        policy = CallingLinesPolicy(config = config, legacy = flags, installed = installed)
        CallLineNumbering.update(config)
        ZillitLog.i(TAG) { describe(config, flags, if (fromFirebase) "Firebase" else "the saved copy") }
    }

    /** Parsed once per distinct value: the poll mostly sees the same text again. */
    private fun parse(raw: String): CallingLinesConfig? {
        if (raw != parsedRaw) {
            parsedRaw = raw
            parsed = CallingLinesConfig.parse(raw)
        }
        return parsed
    }

    private fun describe(config: CallingLinesConfig?, flags: LegacyCallingFlags, source: String): String =
        if (config == null) {
            "calling_lines not configured ($source): the older flags decide — $flags"
        } else {
            "calling_lines from $source: " + config.lines.joinToString { line ->
                "${line.engine}=Line ${line.order}${if (line.enabled) "" else " (off)"}" +
                    " projects=${line.projects} platforms=${line.platforms ?: "all"}"
            }
        }

    companion object {
        private const val TAG = "CallingLines"

        /** How stale the key may be when a chat's call buttons are drawn. */
        val ON_USE: Duration = 2.minutes

        /** How often it is re-read with nothing else asking. */
        val POLL: Duration = 15.minutes
    }
}
