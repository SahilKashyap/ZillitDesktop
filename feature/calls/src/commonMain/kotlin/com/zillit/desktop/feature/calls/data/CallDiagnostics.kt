package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Line 3 call diagnostics — client → server telemetry, as the phones and the
 * web send it (`livekit/diagnostics/CallDiagnostics.kt`,
 * `lineTwo/engine/clientLog.ts`).
 *
 * The point is that ONE server log explains a call across every client: was
 * TURN used (`ice`), did audio flow (`audio`), who was speaking (`speak`), and
 * — the question it was actually built for — where an answer was lost
 * (`accept_tap` with no matching `accept_sent` is a device that rang and never
 * replied).
 *
 * ## The ten events
 *
 * `ring_shown`, `accept_tap`, `accept_sent`, `warm_connect`, `promoted`,
 * `publish`, `speak`, `audio`, `ice`, `disconnect` — the same set Android
 * sends, so a desktop call reads like a phone call in the same log. Six of
 * them are signalling facts and are emitted from Kotlin; `publish`, `speak`,
 * `audio` and `ice` are WebRTC facts only the page can see, and it samples
 * them and hands them here (see `EngineBridge` `telemetry`), so the budget,
 * the call window and the transport stay in one place.
 *
 * Every line carries `platform: "desktop"`. Nothing else in the payload says
 * which client sent it, and a log that cannot tell a desktop from a phone
 * cannot answer "is this only happening on the desktop", which is the first
 * question asked of it.
 *
 * ## Hard rules, from the backend spec — do not relax
 *
 *  1. It ships ONLY between [startCall] and [endCall]. Idle, presence-only,
 *     between calls: silent. That is what keeps reconnects and an idle app
 *     from filling the log.
 *  2. Diagnostics only. Every send is fire-and-forget on [scope] and nothing
 *     here may alter how a call behaves. Several call sites sit inside the
 *     path that decides whether a call connects at all.
 *  3. Budget: the server SILENTLY drops more than 60 lines/min/device (it
 *     still answers ok, so a retry is pure waste). We cap at
 *     [MAX_PER_MINUTE] over a sliding minute and DISCARD above it — never
 *     queue, never retry. A normal one-minute call is about 30 lines.
 *  4. Transport: the presence socket's `clientLog` request first, `POST
 *     /v1/client-log` only when the socket is down or the request failed.
 *     Never both.
 */
class CallDiagnostics(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** True while the presence socket is up and registered. */
    private val socketOnline: () -> Boolean,
    /** One `clientLog` frame. False (or a throw) sends the line over REST instead. */
    private val sendOverSocket: suspend (callId: String, event: String, data: JsonObject) -> Boolean,
    /** `POST /v1/client-log`, for a line the socket did not carry. */
    private val sendOverRest: suspend (callId: String, event: String, data: JsonObject) -> Unit,
) {
    private var callId: String? = null
    private val once = mutableSetOf<String>()
    private var lastSpeaking: Boolean? = null
    private var lastSpeakAtMillis = 0L

    /** Send times inside the sliding window. Process-wide, not per call, as the budget is. */
    private val sentAt = ArrayDeque<Long>()

    /**
     * Opens the window for a call. Idempotent for the same id; a NEW id resets
     * every per-call fact, so one call's `logOnce` set can never silence the
     * next call's first line.
     */
    fun startCall(id: String) {
        if (id.isBlank() || callId == id) return
        resetPerCall()
        callId = id
    }

    /**
     * Closes the window, after saying why.
     *
     * The `disconnect` line goes out FIRST, while the id is still live — it is
     * the one event that can only be sent by the thing that ends the call, and
     * ordering it after the close would drop it every time.
     */
    fun endCall(reason: String? = null) {
        if (callId != null && reason != null) logOnce("disconnect", mapOf("reason" to reason.take(REASON_MAX)))
        resetPerCall()
        callId = null
    }

    /** True while a call window is open — the only state in which anything is sent. */
    val isActive: Boolean get() = callId != null

    /** One line for the live call. No window, or over budget, and it is dropped in silence. */
    fun log(event: String, data: Map<String, Any?> = emptyMap()) {
        val id = callId ?: return
        val name = event.take(EVENT_MAX)
        if (!admit()) return
        val payload = data.toJson()
        scope.launch { send(id, name, payload) }
    }

    /** [log], but at most once per call for this event — `warm_connect`, `promoted`, `publish`. */
    fun logOnce(event: String, data: Map<String, Any?> = emptyMap()) {
        if (callId == null) return
        if (!once.add(event)) return
        log(event, data)
    }

    /**
     * Our own active-speaker edge → `speak {speaking, level}`.
     *
     * EDGE-triggered and debounced: a chatty room flips the active speaker
     * several times a second, and a line per flip would eat the whole budget
     * on its own.
     */
    fun selfSpeaking(speaking: Boolean, level: Double) {
        if (callId == null) return
        if (lastSpeaking == speaking) return
        val at = now()
        if (lastSpeaking != null && at - lastSpeakAtMillis < SPEAK_DEBOUNCE_MILLIS) return
        lastSpeaking = speaking
        lastSpeakAtMillis = at
        log("speak", mapOf("speaking" to speaking, "level" to level))
    }

    private fun resetPerCall() {
        once.clear()
        lastSpeaking = null
        lastSpeakAtMillis = 0L
    }

    /** Sliding-window admission: [MAX_PER_MINUTE] in any [WINDOW_MILLIS]. */
    private fun admit(): Boolean {
        val at = now()
        while (sentAt.isNotEmpty() && at - sentAt.first() >= WINDOW_MILLIS) sentAt.removeFirst()
        if (sentAt.size >= MAX_PER_MINUTE) return false
        sentAt.addLast(at)
        return true
    }

    /*
     * TooGenericExceptionCaught: catching everything IS the contract here —
     * rule 2, "nothing in this class may alter how a call behaves". Several
     * call sites sit inside the path that decides whether a call connects at
     * all, so a diagnostic that let anything through would take a call down.
     * Cancellation is rethrown, which is the one exception that must travel.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(id: String, event: String, data: JsonObject) {
        try {
            val carried = socketOnline() && runCatching { sendOverSocket(id, event, data) }.getOrDefault(false)
            if (carried) return
            sendOverRest(id, event, data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            // A dropped diagnostic is a non-event. It must never reach a call.
            ZillitLog.d(TAG) { "clientLog $event not delivered: ${thrown.message}" }
        }
    }

    /**
     * The payload, with the platform marker and nothing unserialisable.
     *
     * Numbers and booleans keep their JSON type — the server charts them —
     * and anything else is stringified rather than refused, because a
     * diagnostic that throws on an unexpected value is worse than one that
     * reads a little oddly. The whole thing is bounded: the server truncates
     * beyond about 900 characters, so an over-long payload is replaced by its
     * own head rather than silently cut mid-key.
     */
    private fun Map<String, Any?>.toJson(): JsonObject {
        val body = buildJsonObject {
            put("platform", JsonPrimitive(PLATFORM))
            for ((key, value) in this@toJson) {
                when (value) {
                    null -> Unit
                    is Boolean -> put(key, JsonPrimitive(value))
                    is Int -> put(key, JsonPrimitive(value))
                    is Long -> put(key, JsonPrimitive(value))
                    is Float -> put(key, JsonPrimitive(value))
                    is Double -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
        val rendered = body.toString()
        if (rendered.length <= MAX_DATA_CHARS) return body
        return buildJsonObject {
            put("platform", JsonPrimitive(PLATFORM))
            put("truncated", JsonPrimitive(true))
            put("head", JsonPrimitive(rendered.take(MAX_DATA_CHARS - TRUNCATION_ROOM)))
        }
    }

    companion object {
        private const val TAG = "CallDiagnostics"

        /** Which client sent the line. The one field the server's payload does not otherwise carry. */
        const val PLATFORM = "desktop"

        /** The server drops above 60/min/device; stay well under. */
        const val MAX_PER_MINUTE = 40
        const val WINDOW_MILLIS = 60_000L
        const val EVENT_MAX = 48
        const val REASON_MAX = 64
        const val SPEAK_DEBOUNCE_MILLIS = 300L

        /** The server truncates `data` past roughly 900 characters. */
        const val MAX_DATA_CHARS = 900
        private const val TRUNCATION_ROOM = 48
    }
}
