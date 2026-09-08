package com.zillit.desktop.feature.calls.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Something the media stack did.
 *
 * Modelled on Android's `CallEngineEvent`, minus the events that only exist
 * because of Android platform quirks (audio-route re-assertion, foreground
 * service restarts). What survives is the set the call UI actually reacts to.
 */
sealed interface CallEngineEvent {

    /** We are in the channel. Until this arrives, nothing is flowing. */
    data class Joined(val channel: String, val uid: Int) : CallEngineEvent

    /** We left, or were removed. */
    data class Left(val channel: String) : CallEngineEvent

    /** A remote participant's media appeared. */
    data class PeerJoined(val uid: Int, val peerId: String? = null) : CallEngineEvent

    /** A remote participant's media went away. [reason] is the SDK's code. */
    data class PeerLeft(val uid: Int, val reason: Int = 0) : CallEngineEvent

    /** Someone muted or unmuted their microphone. */
    data class PeerAudioMuted(val uid: Int, val muted: Boolean) : CallEngineEvent

    /** Someone turned their camera on or off. */
    data class PeerVideoMuted(val uid: Int, val muted: Boolean) : CallEngineEvent

    /** Who is talking, loudest first. Drives the speaking ring on avatars. */
    data class ActiveSpeakers(val uids: List<Int>) : CallEngineEvent

    /** Link quality for one participant, on the SDK's 0..6 scale. */
    data class NetworkQuality(val uid: Int, val tx: Int, val rx: Int) : CallEngineEvent

    /** A remote participant started or stopped sharing their screen. */
    data class PeerScreenShare(val uid: Int, val sharing: Boolean) : CallEngineEvent

    /**
     * A remote participant raised or lowered their hand, by USER id.
     *
     * Line 1 only: the SFU broadcasts `peerRaisedHand`/`peerLoweredHand` with
     * the composite peer id, and the user half is the identity the roster
     * knows. Line 2 has no media-side signal for this — the Firestore row's
     * `raise_hand` is the whole transport there.
     */
    data class PeerHand(val userId: String, val raised: Boolean) : CallEngineEvent

    /**
     * A line of in-call chat over the media engine's own data channel —
     * Line 3, where the web's `ChatChannel` rides LiveKit data rather than
     * the Zillit socket relay. [fromUserId] is the SFU-verified sender.
     */
    data class ChatReceived(
        val fromUserId: String,
        val name: String,
        val id: String,
        val text: String,
        val atMillis: Long,
        val deleted: Boolean = false,
    ) : CallEngineEvent

    /** A remote participant started or stopped recording the call, by USER id. */
    data class PeerRecording(val userId: String, val recording: Boolean) : CallEngineEvent

    /**
     * A finished local recording landed on disk.
     *
     * Carries its type and length as well as its path, because the file does
     * not only stay here: it is posted into the call's chat, and an audio
     * message needs both to be a playable row rather than an unnamed blob.
     */
    data class RecordingSaved(
        val path: String,
        val contentType: String = "audio/webm",
        val durationMillis: Long = 0L,
    ) : CallEngineEvent

    /** The transport dropped, recovered, or gave up. */
    data class ConnectionChanged(val state: EngineConnection, val reason: String? = null) :
        CallEngineEvent

    /**
     * The engine failed in a way the call cannot continue through.
     *
     * Carries a human-readable message because there is nothing the UI can
     * usefully do with an SDK error code except show it.
     */
    data class Failed(val message: String) : CallEngineEvent

    /**
     * Something went wrong that must NOT end the call.
     *
     * A refused screen share is the case this exists for: the call is fine,
     * one action did not happen, and the user is owed an explanation rather
     * than a button that quietly does nothing.
     */
    data class Degraded(val message: String) : CallEngineEvent

    /**
     * The machine's audio and video hardware, as the page currently sees it.
     *
     * Republished whenever a device is plugged or unplugged mid-call, which on
     * a desktop is the ordinary case rather than an edge one.
     */
    /**
     * The RTC token is within its grace period.
     *
     * There is nothing to renew with — the token is minted once by
     * `call/new-call` and the backend offers no renewal route — so this is a
     * warning that the call has minutes left, not a request for a new one.
     */
    data object TokenExpiring : CallEngineEvent

    /** The token ran out. Media is over; the call has to end deliberately. */
    data object TokenExpired : CallEngineEvent

    /** This machine started or stopped sharing its screen. */
    data class ScreenShare(val sharing: Boolean) : CallEngineEvent

    data class Devices(
        val microphones: List<MediaDevice>,
        val speakers: List<MediaDevice>,
        val cameras: List<MediaDevice>,
        val microphoneId: String,
        val speakerId: String,
    ) : CallEngineEvent
}

/** Transport state, collapsed from each SDK's own enumeration. */
enum class EngineConnection { Connecting, Connected, Reconnecting, Disconnected, Failed }

/**
 * The media stack, behind one interface.
 *
 * Agora publishes no client SDK that runs on a JVM desktop — their desktop
 * targets are native Windows and macOS, and the Java SDK they ship is a Linux
 * server SDK with no capture or rendering. So the media half of calling has to
 * be hosted by something, and which something is a decision with real
 * trade-offs (an embedded Chromium running the Web SDK, or hand-written
 * bindings to the native libraries).
 *
 * Everything above this interface — ringing, accepting, the roster, the
 * timeouts, the state machine — is independent of that choice, so it is
 * written against this and nothing else. [NoopCallEngine] lets the whole
 * signalling path run and be tested before a real engine exists.
 *
 * Implementations must be safe to call from the main thread and must never
 * throw; failures arrive on [events] as [CallEngineEvent.Failed].
 */
/** Which piece of hardware [CallEngine.setDevice] is choosing. */
enum class CallDeviceKind { Microphone, Speaker, Camera }

/**
 * Everything an engine needs to join, whichever line it is.
 *
 * One object rather than a parameter list because the two lines need disjoint
 * things — Agora joins a channel with a token and a numeric uid, mediasoup
 * dials a host and announces a peer id — and a signature carrying both as
 * positional arguments is one where half are always blank and nobody can tell
 * which half is meaningful.
 */
data class CallJoin(
    val provider: CallProvider,

    /**
     * Settled before joining: Agora decides whether to open the camera at join
     * time, and an audio call that joins with video enabled lights the user's
     * camera indicator for no reason.
     */
    val hasVideo: Boolean,

    // ── Line 2 (Agora) ──────────────────────────────────────────────────
    val channel: String = "",
    /** Channel credential. Never logged. */
    val token: String = "",
    val uid: Int = 0,

    // ── Line 1 (mediasoup) ──────────────────────────────────────────────
    /** Bare host, already stripped of any scheme or path. */
    val sfuHost: String = "",
    /** The room on that SFU, elected from invite code, room id or call uuid. */
    val roomId: String = "",
    /** `userId:deviceId`. Both halves do work — see `mediasoupPeerId`. */
    val peerId: String = "",
    /** The SFU's own credential. Empty is a legitimate tokenless dial. */
    val sfuToken: String = "",
    /** What other peers see against this tile. */
    val displayName: String = "",

    // ── Line 3 (LiveKit) ────────────────────────────────────────────────
    /** The room URL, already swapped for a public one where the ring named the node's internal address. */
    val livekitUrl: String = "",
    /** This participant's token for that room. */
    val livekitToken: String = "",
    /** This participant's identity in the room — the user's id on the call's production. */
    val identity: String = "",
)

/*
 * TooManyFunctions: one method per capability the media stack exposes, and the
 * capabilities are the interface. Splitting it — media here, page-chrome there
 * — would mean two objects that must be the same object, since every one of
 * them is served by the one browser page and several are only correct in
 * relation to the others (a stage push and an avatar push both describe the
 * same tile). The no-op defaults keep the cost of a new one at zero for the
 * implementations that do not care.
 */
@Suppress("TooManyFunctions")
interface CallEngine {

    /** Everything the stack reports. Replayed to nobody — subscribe first. */
    val events: Flow<CallEngineEvent>

    /** True once [initialize] has succeeded and the engine can join. */
    val isReady: Boolean

    /** Brings the stack up. Idempotent; safe to call before every call. */
    suspend fun initialize(): Boolean

    /**
     * Joins [channel] as [uid], authenticated by [token].
     *
     * [hasVideo] must be settled before joining: Agora decides whether to open
     * the camera at join time, and an audio call that joins with video enabled
     * lights the user's camera indicator for no reason.
     */
    suspend fun join(params: CallJoin)

    /** Leaves the channel. Safe to call when not in one. */
    suspend fun leave()

    fun setMicrophoneMuted(muted: Boolean)

    fun setCameraEnabled(enabled: Boolean)

    fun setSpeakerEnabled(enabled: Boolean)

    /** Asks the engine to publish [CallEngineEvent.Devices]. */
    fun listDevices() = Unit

    /**
     * Sends one line of in-call chat over the engine's own data channel.
     * True when the engine carried it; false means the caller should relay
     * it the socket way (Lines 1 and 2).
     */
    fun sendChat(id: String, text: String, atMillis: Long): Boolean = false

    /**
     * Switches one live device by id, or arms the choice for the next join.
     *
     * One method with a [CallDeviceKind] rather than three near-identical
     * ones: every implementation routes them to the same place, and three
     * separate names only spread that fact across three call sites.
     */
    fun setDevice(kind: CallDeviceKind, deviceId: String) = Unit

    /** Cycles to the next capture device, where the host has more than one. */
    fun switchCamera()

    /**
     * Starts sharing a screen or window. False when the host cannot.
     *
     * [sourceId] is what the app's own picker chose, in Chromium's
     * DesktopMediaID spelling. Null means "whatever the host would pick",
     * which on embedded Chromium is the whole desktop — the behaviour before
     * there was a picker, and the fallback when the source list cannot be
     * built.
     */
    suspend fun startScreenShare(sourceId: String? = null): Boolean = false

    suspend fun stopScreenShare() {}

    /**
     * Tells the media side this user's hand moved.
     *
     * Line 1 turns it into the protoo `toggleHandRaise` request the phones
     * send; Line 2 needs nothing here — the coordinator's Firestore mirror is
     * the transport there, exactly as it is on the phones.
     */
    fun setHandRaised(raised: Boolean) {}

    /**
     * Starts recording the call's audio — every voice, ours included — on this
     * machine. False when the host cannot record. The finished file arrives
     * later as [CallEngineEvent.RecordingSaved].
     */
    suspend fun startAudioRecording(): Boolean = false

    suspend fun stopAudioRecording() {}

    /**
     * Hands the engine the computed stage model.
     *
     * An engine that renders its own surface — embedded Chromium does — needs
     * to label the tiles it draws, and it cannot be told by drawing on top of
     * it: a heavyweight surface owns every pixel inside its rectangle.
     */
    fun setStage(json: String) {}

    /** Hands the engine the app's colours, so its surface is not a foreign slab. */
    fun setTheme(json: String) {}

    /**
     * Gives the page one person's profile picture, as a data URI.
     *
     * The page draws its own tile chrome — a heavyweight surface owns every
     * pixel inside its rectangle — so a face Compose has fetched has to be
     * handed over rather than drawn on top. Without it a camera-off tile shows
     * initials while the same person on an audio call shows their photograph.
     */
    fun setAvatar(userId: String, dataUri: String) {}

    /** Collapses the engine's surface to one tile, for the minimised pill. */
    fun setCompact(compact: Boolean) {}

    /**
     * Asks the engine's own surface to float a reaction.
     *
     * Only the surface can draw inside its own rectangle — it is a heavyweight
     * native component and app-drawn layers over it are never painted — so a
     * video call's reactions go through here instead.
     */
    fun showReaction(json: String) {}

    /** Releases the stack. The engine is unusable afterwards. */
    suspend fun destroy()
}

/**
 * A [CallEngine] that answers every call successfully and carries no media.
 *
 * Not a test double — it is what the app runs with until an engine is wired
 * in, so the call flow is exercised end to end against the live server rather
 * than sitting unrun until the media work lands. Every seam it stands in for
 * is one that would otherwise be discovered late.
 */
class NoopCallEngine : CallEngine {

    private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 16)
    override val events: Flow<CallEngineEvent> = _events.asSharedFlow()

    private var ready = false
    private var joined: String? = null

    override val isReady: Boolean get() = ready

    override suspend fun initialize(): Boolean {
        ready = true
        return true
    }

    override suspend fun join(params: CallJoin) {
        joined = params.channel
        _events.emit(CallEngineEvent.ConnectionChanged(EngineConnection.Connected))
        _events.emit(CallEngineEvent.Joined(params.channel, params.uid))
    }

    override suspend fun leave() {
        val channel = joined ?: return
        joined = null
        _events.emit(CallEngineEvent.Left(channel))
    }

    override fun setMicrophoneMuted(muted: Boolean) = Unit

    override fun setCameraEnabled(enabled: Boolean) = Unit

    override fun setSpeakerEnabled(enabled: Boolean) = Unit
    override fun listDevices() = Unit
    override fun setDevice(kind: CallDeviceKind, deviceId: String) = Unit

    override fun switchCamera() = Unit

    override suspend fun destroy() {
        leave()
        ready = false
    }
}

/**
 * One selectable piece of hardware.
 *
 * [label] is what the OS calls it ("MacBook Pro Microphone", "AirPods Pro").
 * It can be empty before media permission is granted — browsers withhold
 * device labels until then — so a picker must fall back to something rather
 * than draw a blank row.
 */
data class MediaDevice(val id: String, val label: String) {
    val displayName: String get() = label.ifBlank { "Unnamed device" }
}

/**
 * The hardware a call can use, and what is currently chosen.
 *
 * Empty ids mean "whatever the OS considers default" — the state a call is in
 * before anybody opens a picker, and the one it stays in for users who never
 * touch it.
 */
data class CallDevices(
    val microphones: List<MediaDevice> = emptyList(),
    val speakers: List<MediaDevice> = emptyList(),
    val cameras: List<MediaDevice> = emptyList(),
    val microphoneId: String = "",
    val speakerId: String = "",
) {
    /** Nothing to choose between is nothing to show a picker for. */
    val hasChoice: Boolean get() = microphones.size > 1 || speakers.size > 1
}
