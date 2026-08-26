package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.feature.calls.domain.CallDeviceKind
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.EngineConnection
import com.zillit.desktop.feature.calls.domain.MediaDevice
import com.zillit.desktop.feature.calls.domain.RecordedAudio
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The wire between Kotlin and the call page (`callengine/call.js`).
 *
 * Pure functions, deliberately: the CEF half of the engine cannot run in a
 * unit test, so everything that *can* be wrong in a testable way — event
 * parsing, JS command construction, string escaping — lives here, and the
 * KCEF class stays a thin transport.
 *
 * Event shapes are the contract with `call.js`; change either side only with
 * the other, and keep `EngineBridgeTest` as the pin.
 */
/*
 * One function per page API, which is what the count is measuring. Splitting
 * the bridge would put half the page's surface in one file and half in
 * another, with nothing to say which half anything belongs to.
 */
@Suppress("TooManyFunctions")
object EngineBridge {

    /** The page announced itself. Not a [CallEngineEvent]; the engine gates on it. */
    const val TYPE_READY = "ready"

    /** The channel token is about to lapse — surface for a future refresh path. */
    const val TYPE_TOKEN_EXPIRING = "token-expiring"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One JSON line from the page, as the engine event it means.
     *
     * Null for the messages that are not engine events ([TYPE_READY], unknown
     * types, malformed JSON) — the transport drops them, it never throws.
     */
    @Suppress("CyclomaticComplexMethod")
    fun parse(message: String): CallEngineEvent? {
        val obj = runCatching { json.parseToJsonElement(message) as? JsonObject }
            .getOrNull() ?: return null
        return when (obj.str("type")) {
            "joined" -> CallEngineEvent.Joined(obj.str("channel").orEmpty(), obj.int("uid"))
            "left" -> CallEngineEvent.Left(obj.str("channel").orEmpty())
            "peer-joined" -> CallEngineEvent.PeerJoined(obj.int("uid"))
            "peer-left" -> CallEngineEvent.PeerLeft(obj.int("uid"))
            "peer-audio" -> CallEngineEvent.PeerAudioMuted(obj.int("uid"), obj.bool("muted"))
            "peer-video" -> CallEngineEvent.PeerVideoMuted(obj.int("uid"), obj.bool("muted"))
            "speakers" -> CallEngineEvent.ActiveSpeakers(obj.intList("uids"))
            "network" -> CallEngineEvent.NetworkQuality(obj.int("uid"), obj.int("tx"), obj.int("rx"))
            "connection" -> CallEngineEvent.ConnectionChanged(
                connection(obj.str("state")),
                obj.str("reason"),
            )
            "token-expiring" -> CallEngineEvent.TokenExpiring
            "token-expired" -> CallEngineEvent.TokenExpired
            "screen-share" -> CallEngineEvent.ScreenShare(obj.bool("sharing"))
            "devices" -> CallEngineEvent.Devices(
                microphones = obj.devices("microphones"),
                speakers = obj.devices("speakers"),
                cameras = obj.devices("cameras"),
                microphoneId = obj.str("microphoneId").orEmpty(),
                speakerId = obj.str("speakerId").orEmpty(),
            )
            "error" -> CallEngineEvent.Failed(obj.str("message") ?: "call page error")
            else -> null
        }
    }

    /** The page's non-fatal complaint, or null when [message] is not one. */
    fun warning(message: String): String? {
        val obj = runCatching { json.parseToJsonElement(message) as? JsonObject }
            .getOrNull() ?: return null
        if (obj.str("type") != "warning") return null
        return obj.str("message") ?: "unspecified page warning"
    }

    /**
     * Which step warned, when the page said.
     *
     * Kept apart from the text because the step is what code branches on and
     * the text is only for a human — a screen share the OS refused needs to
     * say something specific, and matching on prose would be fragile.
     */
    fun warningWhere(message: String): String? {
        val obj = runCatching { json.parseToJsonElement(message) as? JsonObject }
            .getOrNull() ?: return null
        if (obj.str("type") != "warning") return null
        return obj.str("where")
    }

    /** True when [message] is the page's ready announcement. */
    fun isReady(message: String): Boolean =
        runCatching { (json.parseToJsonElement(message) as? JsonObject)?.str("type") }
            .getOrNull() == TYPE_READY

    /**
     * The Agora SDK's connection states, folded to ours.
     *
     * Unknown states read as [EngineConnection.Connecting] — the SDK has
     * grown states before, and "something transitional" is the reading that
     * neither tears down a live call nor pretends a dead one is fine.
     */
    private fun connection(state: String?): EngineConnection = when (state) {
        "CONNECTED" -> EngineConnection.Connected
        "RECONNECTING" -> EngineConnection.Reconnecting
        "DISCONNECTED" -> EngineConnection.Disconnected
        "DISCONNECTING" -> EngineConnection.Disconnected
        else -> EngineConnection.Connecting
    }

    // ── Kotlin → page ───────────────────────────────────────────────────

    fun joinScript(appId: String, channel: String, token: String, uid: Int, withVideo: Boolean): String =
        "zillitCall.join(${quote(appId)}, ${quote(channel)}, ${quote(token)}, $uid, $withVideo)"

    const val LEAVE_SCRIPT = "zillitCall.leave()"

    fun micScript(muted: Boolean): String = "zillitCall.setMic($muted)"

    fun camScript(enabled: Boolean): String = "zillitCall.setCam($enabled)"

    const val SWITCH_CAMERA_SCRIPT = "zillitCall.switchCamera()"

    fun speakerScript(enabled: Boolean): String = "zillitCall.setSpeaker($enabled)"

    const val LIST_DEVICES_SCRIPT = "zillitCall.listDevices()"

    /**
     * Starts the Agora line's share, on [sourceId] when the picker named one.
     *
     * The id is a Chromium DesktopMediaID and is spliced as a JSON string for
     * the same reason tokens are: it is opaque text from outside, and a page
     * that concatenates opaque text into a call is one quote away from being
     * an injection into our own script.
     */
    fun startScreenShareScript(sourceId: String?): String =
        "zillitCall.startScreenShare(${sourceId?.asJsString() ?: "null"})"
    const val STOP_SCREEN_SHARE_SCRIPT = "zillitCall.stopScreenShare()"

    const val START_RECORDING_SCRIPT = "zillitCall.startRecording()"
    const val STOP_RECORDING_SCRIPT = "zillitCall.stopRecording()"

    /**
     * One slice of a finished recording, or null when [message] is not one.
     *
     * The file crosses the bridge in base64 slices because the router carries
     * strings, and one message holding a whole call's audio would be tens of
     * megabytes through a channel sized for events.
     */
    fun recordingChunk(message: String): String? {
        val obj = runCatching { json.parseToJsonElement(message) as? JsonObject }
            .getOrNull() ?: return null
        if (obj.str("type") != "recording-chunk") return null
        return obj.str("data")
    }

    /**
     * The frame that closes a recording's chunk stream, or null for anything
     * else.
     *
     * Carries what only the page knows: which container it managed to write
     * (see `RECORDER_TYPES` in call.js — MP4 where this build can encode AAC,
     * WebM otherwise) and how long the take ran. Both ride the last frame
     * rather than the first because the recorder picks its container at start
     * and its length is not known until stop.
     */
    fun recordingDone(message: String): RecordedAudio? {
        val obj = runCatching { json.parseToJsonElement(message) as? JsonObject }
            .getOrNull() ?: return null
        if (obj.str("type") != "recording-done") return null
        return RecordedAudio(
            // Pre-MIME pages sent the frame bare; WebM is what they recorded.
            mimeType = obj.str("mime")?.takeIf(String::isNotBlank) ?: "audio/webm",
            extension = obj.str("ext")?.takeIf(String::isNotBlank) ?: "webm",
            durationMillis = obj.long("duration"),
        )
    }

    /**
     * Device ids are opaque strings from the browser, so they are passed as
     * JSON rather than spliced raw — the same reasoning as the stage model.
     */
    fun deviceScript(kind: CallDeviceKind, deviceId: String): String {
        val setter = when (kind) {
            CallDeviceKind.Microphone -> "setMicrophoneDevice"
            CallDeviceKind.Speaker -> "setSpeakerDevice"
            CallDeviceKind.Camera -> "setCameraDevice"
        }
        return "zillitCall.$setter(${deviceId.asJsString()})"
    }

    private fun String.asJsString(): String = Json.encodeToString(String.serializer(), this)

    /**
     * Pushes the stage model the page draws its tile chrome from.
     *
     * Passed as one JSON *string* and parsed inside the page: splicing an
     * object literal into a script would make every display name an injection
     * site, and names come off the wire.
     */
    fun stageScript(json: String): String = "zillitCall.setStage(${quote(json)})"

    fun themeScript(json: String): String = "zillitCall.setTheme(${quote(json)})"

    fun compactScript(compact: Boolean): String = "zillitCall.setCompact($compact)"

    /** Floats one emoji over the page's own picture. See `showReaction` in call.js. */
    fun reactionScript(json: String): String = "zillitCall.showReaction(${quote(json)})"

    /**
     * A JS string literal that cannot break out of itself.
     *
     * Tokens are server-minted opaque strings; one containing a quote or a
     * backslash must arrive intact, not become an injection into our own
     * page. JSON string encoding is exactly that escaping.
     */
    private fun quote(value: String): String =
        json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.devices(key: String): List<MediaDevice> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = row.str("id").orEmpty()
            if (id.isBlank()) null else MediaDevice(id = id, label = row.str("label").orEmpty())
        }

    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonPrimitive)?.intOrNull
                ?: (entry as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }
}
