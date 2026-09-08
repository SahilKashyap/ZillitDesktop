package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.data.EngineBridge
import com.zillit.desktop.feature.calls.domain.CallDeviceKind
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.data.livekit.LiveKitScripts
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.data.protoo.MediasoupPage
import com.zillit.desktop.feature.calls.data.protoo.MediasoupScripts
import com.zillit.desktop.feature.calls.data.protoo.MediasoupSession
import com.zillit.desktop.feature.calls.data.protoo.OkHttpProtooSocket
import com.zillit.desktop.feature.calls.data.protoo.ProtooPeer
import com.zillit.desktop.feature.calls.data.protoo.ProtooSignalling
import com.zillit.desktop.feature.calls.data.protoo.TurnCredentials
import kotlinx.serialization.json.JsonObject
import com.zillit.desktop.feature.calls.data.protoo.MediasoupPageEvent
import com.zillit.desktop.feature.calls.data.protoo.parseMediasoupPageEvent
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.RecordedAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import java.awt.Component
import java.io.File
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * The media engine: the Agora Web SDK inside embedded Chromium.
 *
 * Agora publishes no client SDK for a JVM desktop, so the media stack is the
 * same one the web client runs — `agora-rtc-sdk-ng` 4.24.2, bundled as a
 * resource beside a small host page — hosted by the JetBrains Runtime's own
 * JCEF (see [KcefRuntime]). Chromium brings everything a media stack needs and
 * a JVM lacks: capture, echo cancellation, VP8, and rendering.
 *
 * Kotlin drives the page with `zillitCall.*` calls and hears back over the CEF
 * message router as one-line JSON events; both directions are the
 * [EngineBridge] contract, which is where all the parseable logic lives so
 * this class stays a transport.
 *
 * Lifecycle: the whole stack — Chromium ([KcefRuntime]) and the loaded page —
 * comes up shortly after launch rather than at ring time. Starting CEF and
 * loading a page costs seconds, and the moment somebody is calling is the
 * worst one to spend them; [initialize] is then almost always a no-op that
 * returns true.
 */
/*
 * The function count IS the contract: [CallEngine] declares 16 members and this
 * is the one real implementation of them, plus Chromium's own lifecycle. There
 * is no split here that does not end in two objects that must be kept in step
 * by hand — which is how the surface hand-off broke in the first place.
 */
@Suppress("TooManyFunctions")
class KcefCallEngine(
    private val appId: String,
    private val scope: CoroutineScope,
    /**
     * Line 1's relays. Null on a build with no calling API, which leaves
     * mediasoup joins degrading to signalling-only rather than half-working.
     */
    private val turn: (suspend () -> TurnCredentials)? = null,
) : CallEngine {

    private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<CallEngineEvent> = _events.asSharedFlow()

    private val initLock = Mutex()
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private val holder = OffscreenHolder()

    /** Completed when the page announces itself; replaced when torn down. */
    @Volatile
    private var pageReady = CompletableDeferred<Unit>()

    /**
     * The last model and palette pushed down.
     *
     * Kept because the page can be rebuilt at any time — these are replayed on
     * its `ready` so a reloaded page is not left showing its own defaults for
     * whatever remains of the call.
     */
    @Volatile
    private var lastStage: String? = null

    @Volatile
    private var lastTheme: String? = null

    /**
     * The faces pushed so far, so a rebuilt page gets them back.
     *
     * Same reasoning as [lastTheme] and [lastStage]: the page is torn down and
     * rebuilt between calls, and a face fetched during the last call must not
     * have to be fetched again to be shown in the next one.
     */
    private val avatars = mutableMapOf<String, String>()

    override val isReady: Boolean get() = browser != null && pageReady.isCompleted

    /**
     * Which host currently owns the browser component, as a monotonic ticket.
     *
     * Atomic because it is read from the deferred park on the event thread and
     * bumped from composition; a plain var would be a data race on a field
     * whose whole job is deciding whether to re-parent a live component.
     */
    private val surfaceClaim = SurfaceClaims()

    /**
     * The live Line 1 call, or null.
     *
     * Built per call rather than per engine: a mediasoup device can be loaded
     * once, so a second call needs a second everything. Agora calls never
     * touch this.
     */
    /**
     * The microphone the user picked, remembered on this side.
     *
     * Agora's page keeps its own copy and applies it at join; Line 1 needs the
     * id at produce time, which happens here. Blank means the OS default.
     */
    private var chosenMicrophoneId: String = ""

    private var mediasoup: MediasoupSession? = null
    private var protoo: OkHttpProtooSocket? = null

    /**
     * A finished recording, arriving as base64 slices. One at a time by
     * construction — the page runs one MediaRecorder — so a plain builder is
     * enough.
     */
    private val recordingChunks = StringBuilder()

    /** Cancelled with the call, taking its frame collectors with it. */
    private var mediasoupScope: CoroutineScope? = null

    /**
     * True once Line 1's socket has been up at least once this call.
     *
     * Distinguishes the first connect from a recovered one, which need
     * different things: the first joins, the second must rebuild first.
     */
    private var line1Connected = false
    /** A Line 3 room is up in the page; mic, camera and share commands go to `zillitLk`. */
    private var livekitActive = false

    private val _surface = MutableStateFlow<Component?>(null)

    /**
     * The page's own AWT surface — remote tiles and local preview — once it
     * exists.
     *
     * A flow rather than a getter because the host composes the call surface
     * the moment a call starts, which can be before Chromium has finished
     * coming up; a plain getter would read null then and never be asked again.
     */
    val surface: StateFlow<Component?> = _surface.asStateFlow()

    init {
        scope.launch {
            // Staggered past the startup burst: CefApp initialisation has a
            // known intermittent native crash when it races other spawning
            // threads (java-cef #477), and nothing needs Chromium in the
            // first seconds of a launch.
            delay(WARMUP_DELAY_MS)
            prepare()
        }
    }

    /**
     * Gets Chromium and the browser object up, without waiting for the page.
     *
     * Run at startup so a call pays for none of it — CEF start plus a page
     * load is seconds, and the moment somebody is calling is the worst one to
     * spend them. [initialize] is then almost always a no-op returning true.
     */
    private suspend fun prepare(): Boolean = initLock.withLock {
        if (browser != null) return@withLock true

        KcefRuntime.start()
        val cefClient = client ?: KcefRuntime.client()?.also { client = it }
        if (cefClient == null) {
            ZillitLog.w(TAG) { "media engine unavailable (${KcefRuntime.failure})" }
            return@withLock false
        }
        val page = withContext(Dispatchers.IO) { runCatching { extractPage() } }
            .onFailure { thrown -> ZillitLog.w(TAG) { "call page not extracted: ${thrown.message}" } }
            .getOrNull() ?: return@withLock false
        // On the EDT: this builds an AWT window and an AWT component, and
        // JCEF is unforgiving about being driven from anywhere else.
        withContext(Dispatchers.Swing) {
            runCatching {
                val built = buildBrowser(cefClient, page, ::onPageMessage)
                browser = built
                // Parked before anything else: JCEF creates the native browser
                // from a realised AWT peer and not before, so a page nothing
                // displays never loads — and the call page has to be loaded
                // before the call that would display it, a chicken-and-egg the
                // media stack loses. [OffscreenHolder] is the window that
                // satisfies AWT until a real call surface asks for it.
                built.uiComponent?.let { component ->
                    holder.park(component)
                    _surface.value = component
                }
            }.onFailure { thrown ->
                ZillitLog.w(TAG) { "browser build failed: ${thrown.message ?: thrown::class.simpleName}" }
            }
        }
        browser != null
    }

    /**
     * Waits for the call page to be live.
     *
     * By the time a call reaches here the host has the surface on screen, so
     * the page is loading or already loaded; the timeout covers a cold start
     * where [prepare] has not finished either.
     */
    override suspend fun initialize(): Boolean {
        if (isReady) return true
        if (!prepare()) return false
        val ready = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) { pageReady.await() } != null
        if (!ready) ZillitLog.w(TAG) { "call page never reported ready" }
        return ready
    }

    /**
     * Lends the browser component to a Compose surface until it lets go.
     *
     * One call rather than a claim/release pair, so a host cannot release
     * something it never took. The returned lease is the host's turn: while it
     * is the newest, releasing it parks the component; once another host has
     * taken over, releasing is a no-op.
     *
     * That distinction is the whole point. Parking is destructive —
     * `Container.addImpl` removes the component from its current parent — and
     * during a hand-off both hosts exist for one frame, the arriving
     * `SwingPanel` having already adopted the component. A leaving host that
     * parked unconditionally ripped it out of the group that legitimately owned
     * it, leaving a *live* `SwingInteropViewGroup` with zero children. Compose
     * remeasures it, `getPreferredSize` runs `getComponents()[0]` on an empty
     * array, and the app dies mid-call with
     * `Index 0 out of bounds for length 0`.
     *
     * The ownership test happens inside the deferred block, not before it: the
     * question is who owns the surface once the frame has settled.
     */
    fun hostSurface(): SurfaceLease {
        val claim = surfaceClaim.claim()
        return SurfaceLease {
            val component = _surface.value ?: return@SurfaceLease
            SwingUtilities.invokeLater {
                if (!surfaceClaim.mayPark(claim)) return@invokeLater
                holder.park(component)
            }
        }
    }

    /** Drops the parking window so the JVM can exit. See [OffscreenHolder]. */
    fun releaseHolder() = holder.dispose()

    /**
     * One message from the page, to whichever half of the engine wants it.
     *
     * A chain rather than a ladder of early returns: both lines and the
     * recorder share this single channel, and each link takes only what is
     * addressed to it. The Agora parser is last because it is the one that
     * treats an unrecognised message as an event rather than ignoring it.
     */
    private fun onPageMessage(message: String) {
        val handled = handledAsPageReady(message) ||
            handledAsWarning(message) ||
            handledAsRecording(message) ||
            routedToMediasoup(message)
        if (handled) return

        EngineBridge.parse(message)?.let { event ->
            if (event is CallEngineEvent.Failed) {
                ZillitLog.w(TAG) { "page error: ${event.message}" }
            }
            _events.tryEmit(event)
        }
    }

    private fun handledAsPageReady(message: String): Boolean {
        if (!EngineBridge.isReady(message)) return false
        ZillitLog.i(TAG) { "call page ready" }
        pageReady.complete(Unit)
        // A page that has just (re)loaded is holding its built-in defaults.
        // Everything Kotlin told the previous one died with it, so the palette
        // and the tile model are restated rather than waited for — the next
        // push might be a minute away, and until then the stage would be a grey
        // slab with unlabelled tiles.
        lastTheme?.let { theme -> browser?.let { run(it, EngineBridge.themeScript(theme)) } }
        lastStage?.let { stage -> browser?.let { run(it, EngineBridge.stageScript(stage)) } }
        avatars.forEach { (userId, uri) ->
            browser?.let { run(it, EngineBridge.avatarScript(userId, uri)) }
        }
        return true
    }

    private fun handledAsWarning(message: String): Boolean {
        // Degraded, not dead — a missing camera on an audio call lands here.
        val text = EngineBridge.warning(message) ?: return false
        val where = EngineBridge.warningWhere(message).orEmpty()
        ZillitLog.w(TAG) { "page warning${if (where.isBlank()) "" else " ($where)"}: $text" }
        // A screen share the user asked for and did not get is the one warning
        // they need to see. Everything else stays in the log — a camera label
        // arriving late is not worth a banner.
        if (where in SHARE_WARNINGS) {
            _events.tryEmit(CallEngineEvent.Degraded(shareFailureNotice(text)))
        }
        return true
    }

    /**
     * What to tell someone whose screen share did not start.
     *
     * Only a refusal the OS actually made names the OS. An earlier version
     * mapped every unopenable source to "grant Screen Recording permission",
     * and that message was wrong for the failure it fired on most: the source
     * Chromium was handed did not exist, permission having been granted all
     * along. Sending someone to System Settings to fix something that is
     * already correct is worse than saying nothing, so the permission wording
     * is reserved for the phrase macOS itself produces, and anything else is
     * reported as what it was.
     */
    private fun shareFailureNotice(text: String): String = when {
        text.contains("Permission denied by system", ignoreCase = true) ||
            text.contains("NotAllowed", ignoreCase = true) ->
            "Screen sharing needs Screen Recording permission for Zillit in " +
                "System Settings → Privacy & Security, then a restart of the app."
        // A window that closed between the pick and the capture. Nothing is
        // broken; there is just nothing left to share.
        text.contains("NotReadable", ignoreCase = true) ||
            text.contains("video source", ignoreCase = true) ->
            "That screen or window is no longer available. Try sharing again."
        else -> "Screen sharing did not start: $text"
    }

    private fun handledAsRecording(message: String): Boolean {
        EngineBridge.recordingChunk(message)?.let { chunk ->
            recordingChunks.append(chunk)
            return true
        }
        val done = EngineBridge.recordingDone(message) ?: return false
        saveRecording(done)
        return true
    }

    override suspend fun join(params: CallJoin) {
        // Line 1 reaches the page but is not joined from here yet. Saying so
        // and staying signalling-only is the honest degrade: an Agora join
        // built from a mediasoup invite would dial an empty channel, which
        // throws inside the SDK and reads as a broken call rather than an
        // unfinished feature.
        if (params.provider == CallProvider.Mediasoup) {
            joinMediasoup(params)
            return
        }
        if (params.provider == CallProvider.LiveKit) {
            joinLiveKit(params)
            return
        }
        if (params.provider != CallProvider.Agora) {
            ZillitLog.w(TAG) { "no engine for ${params.provider.wire}; staying signalling-only" }
            return
        }
        val target = browser ?: run {
            _events.tryEmit(CallEngineEvent.Failed("media engine is not ready"))
            return
        }
        ZillitLog.i(TAG) {
            "joining channel=${params.channel} uid=${params.uid} video=${params.hasVideo}"
        }
        run(target, EngineBridge.joinScript(appId, params.channel, params.token, params.uid, params.hasVideo))
    }

    /**
     * Dials the SFU and runs the join.
     *
     * The socket is Kotlin's and the media is the page's, so this builds both
     * halves and hands each the other's seam. Nothing here is Agora's — the
     * two lines share a page and a browser and no state beyond that.
     */
    /**
     * Line 3: the page connects the LiveKit room itself — URL and token are
     * the whole handshake, so unlike Line 1 nothing of it runs in Kotlin.
     */
    private fun joinLiveKit(params: CallJoin) {
        val target = browser ?: run {
            _events.tryEmit(CallEngineEvent.Failed("media engine is not ready"))
            return
        }
        if (params.livekitUrl.isBlank() || params.livekitToken.isBlank()) {
            _events.tryEmit(CallEngineEvent.Failed("no LiveKit room to join"))
            return
        }
        livekitActive = true
        ZillitLog.i(TAG) { "line 3: joining ${params.livekitUrl} as ${params.identity} video=${params.hasVideo}" }
        run(target, LiveKitScripts.join(params, chosenMicrophoneId))
    }

    private fun joinMediasoup(params: CallJoin) {
        val relays = turn ?: run {
            ZillitLog.w(TAG) { "no calling API for turn credentials; not joining line 1" }
            return
        }
        if (browser == null) {
            _events.tryEmit(CallEngineEvent.Failed("media engine is not ready"))
            return
        }

        line1Connected = false
        // A scope per call, not the app's. The two collectors below live for as
        // long as their session does, and launching them on the app scope
        // would leave one pair per call running for the life of the process —
        // every one of them still handling frames for a call that ended.
        val callScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.Default)
        mediasoupScope = callScope

        val peer = ProtooPeer(callScope, send = { frame ->
            if (protoo?.send(frame) != true) error("protoo socket is not connected")
        })
        val socket = OkHttpProtooSocket(
            scope = callScope,
            onFrame = { peer.onFrame(it) },
            onConnected = {
                val session = mediasoup ?: return@OkHttpProtooSocket
                // Fires on the first connect AND on every recovered redial.
                // A redial is a whole new server peer under a fresh id, so
                // everything the old one owned — device, transports, producers
                // — has to be rebuilt; resuming into it would join nothing.
                if (line1Connected) {
                    ZillitLog.i(TAG) { "line 1: socket back, rejoining" }
                    session.resetForRejoin()
                }
                line1Connected = true
                session.join(params.displayName, chosenMicrophoneId, params.hasVideo)
            },
            onGiveUp = { reason ->
                _events.emit(CallEngineEvent.Failed(reason))
            },
        )
        protoo = socket

        val session = MediasoupSession(
            scope = callScope,
            peer = peer,
            signalling = object : ProtooSignalling {
                override fun dial(url: () -> String) = socket.dial(url)
                override fun send(frame: String) = socket.send(frame)
                override fun close(reason: String) = socket.close(reason)
            },
            page = PageMedia(),
            turn = relays,
            emit = { _events.emit(it) },
        )
        mediasoup = session

        callScope.launch { peer.notifications.collect { session.onNotification(it) } }
        callScope.launch { peer.requests.collect { session.onServerRequest(it) } }

        ZillitLog.i(TAG) { "line 1: dialling ${params.sfuHost} room=${params.roomId}" }
        session.dial(params)
    }

    /** Drives the page's media module. One script per call, values as JSON. */
    private inner class PageMedia : MediasoupPage {
        override suspend fun load(routerRtpCapabilities: JsonObject) =
            page(MediasoupScripts.load(routerRtpCapabilities))

        override suspend fun createTransports(send: JsonObject, recv: JsonObject, iceServers: String) =
            page(MediasoupScripts.createTransports(send, recv, iceServers))

        override suspend fun produceMic(deviceId: String) =
            page(MediasoupScripts.produceMic(deviceId))

        override suspend fun produceCam(deviceId: String) =
            page(MediasoupScripts.produceCam(deviceId))

        override suspend fun consume(params: JsonObject) =
            page(MediasoupScripts.consume(params))

        override suspend fun closeConsumer(consumerId: String) =
            page(MediasoupScripts.closeConsumer(consumerId))

        override suspend fun settle(askId: Long, ok: Boolean, payload: JsonObject) =
            page(MediasoupScripts.settle(askId, ok, payload))

        override suspend fun leave() = page(MediasoupScripts.LEAVE)
    }

    private fun page(script: String) {
        browser?.let { run(it, script) }
    }

    /** True when the message was Line 1's and has been handled. */
    private fun routedToMediasoup(message: String): Boolean {
        val session = mediasoup ?: return false
        val event = parseMediasoupPageEvent(message) ?: return false
        onMediasoupPageEvent(session, event)
        return true
    }

    /** Routes one page event into the live Line 1 session. */
    private fun onMediasoupPageEvent(session: MediasoupSession, event: MediasoupPageEvent) {
        when (event) {
            is MediasoupPageEvent.Loaded ->
                session.onPageLoaded(event.rtpCapabilities, event.sctpCapabilities)

            is MediasoupPageEvent.Ask ->
                session.onPageAsk(event.askId, event.method, event.data)

            is MediasoupPageEvent.Consumer -> {
                ZillitLog.i(TAG) {
                    "line 1: ${event.kind} from ${event.peerId}${if (event.share) " (screen)" else ""}"
                }
                // Registered with the session so the tiles learn about the
                // track, and so consumerClosed/Paused can find the peer again.
                mediasoupScope?.launch {
                    session.onPageConsumer(event.consumerId, event.peerId, event.kind, event.share)
                }
            }

            is MediasoupPageEvent.Failed -> {
                ZillitLog.w(TAG) { "line 1 page failed at ${event.where}: ${event.message}" }
                _events.tryEmit(CallEngineEvent.Failed(event.message))
            }
        }
    }

    override suspend fun leave() {
        // Whichever line is up. Both are safe to tell when they are not.
        mediasoup?.let { session ->
            runCatching { session.leave() }.onFailure { thrown ->
                ZillitLog.w(TAG) { "line 1 teardown: ${thrown.message}" }
            }
        }
        mediasoup = null
        protoo = null
        line1Connected = false
        mediasoupScope?.cancel()
        mediasoupScope = null
        browser?.let {
            // A recording still running when the call ends is stopped first so
            // its file is delivered rather than dying with the tracks. The
            // page's stop is a no-op when nothing records.
            run(it, EngineBridge.STOP_RECORDING_SCRIPT)
            if (livekitActive) run(it, LiveKitScripts.LEAVE)
            run(it, EngineBridge.LEAVE_SCRIPT)
        }
        livekitActive = false
    }

    /**
     * Mutes on whichever line is live.
     *
     * Not a cosmetic branch: Agora's page function pauses an Agora track, and
     * on a mediasoup call it pauses nothing at all — the producer keeps
     * publishing and the user is still being heard after pressing mute. That
     * is the one bug in a call app that is worse than no audio.
     */
    override fun setMicrophoneMuted(muted: Boolean) {
        val script = when {
            mediasoup != null -> MediasoupScripts.setMic(muted)
            livekitActive -> LiveKitScripts.setMic(muted)
            else -> EngineBridge.micScript(muted)
        }
        browser?.let { run(it, script) }
    }

    override fun setCameraEnabled(enabled: Boolean) {
        val script = when {
            mediasoup != null -> MediasoupScripts.setCam(enabled)
            livekitActive -> LiveKitScripts.setCam(enabled)
            else -> EngineBridge.camScript(enabled)
        }
        browser?.let { run(it, script) }
    }

    override fun setSpeakerEnabled(enabled: Boolean) {
        browser?.let { run(it, EngineBridge.speakerScript(enabled)) }
    }

    override fun switchCamera() {
        browser?.let { run(it, EngineBridge.SWITCH_CAMERA_SCRIPT) }
    }

    override fun listDevices() {
        browser?.let { run(it, EngineBridge.LIST_DEVICES_SCRIPT) }
    }

    override fun setDevice(kind: CallDeviceKind, deviceId: String) {
        if (kind == CallDeviceKind.Microphone) chosenMicrophoneId = deviceId
        browser?.let { target ->
            run(target, EngineBridge.deviceScript(kind, deviceId))
            if (livekitActive) {
                when (kind) {
                    CallDeviceKind.Microphone -> run(target, LiveKitScripts.setMicrophoneDevice(deviceId))
                    CallDeviceKind.Camera -> run(target, LiveKitScripts.setCameraDevice(deviceId))
                    CallDeviceKind.Speaker -> Unit
                }
            }
        }
    }

    /**
     * True means "the page was asked", not "the user shared" — Chromium's own
     * source selection happens after this returns, and a cancelled share
     * comes back as a `screen-share false` event rather than a failure here.
     *
     * Line-branched like the microphone: the Agora function publishes an
     * Agora track, and on a mediasoup call it would silently share nothing.
     */
    override suspend fun startScreenShare(sourceId: String?): Boolean {
        val target = browser ?: return false
        run(
            target,
            when {
                mediasoup != null -> MediasoupScripts.produceScreenScript(sourceId)
                livekitActive -> LiveKitScripts.startScreenShare(sourceId)
                else -> EngineBridge.startScreenShareScript(sourceId)
            },
        )
        return true
    }

    override suspend fun stopScreenShare() {
        val script = when {
            mediasoup != null -> MediasoupScripts.STOP_SCREEN
            livekitActive -> LiveKitScripts.STOP_SCREEN_SHARE
            else -> EngineBridge.STOP_SCREEN_SHARE_SCRIPT
        }
        browser?.let { run(it, script) }
    }

    /** Line 3 only: the other lines' chat rides the Zillit socket relay. */
    override fun sendChat(id: String, text: String, atMillis: Long): Boolean {
        if (!livekitActive) return false
        val target = browser ?: return false
        run(target, LiveKitScripts.sendChat(id, text, atMillis))
        return true
    }

    override fun setHandRaised(raised: Boolean) {
        // Line 1 announces it over protoo, the way the phones do. Line 2 needs
        // nothing here: the coordinator's Firestore mirror is the transport.
        // Line 3 sets a participant attribute, which every LiveKit client reads.
        mediasoup?.sendHandRaise(raised)
        if (livekitActive) browser?.let { run(it, LiveKitScripts.setHandRaised(raised)) }
    }

    /**
     * Records the call's audio inside the page, which is the one place every
     * voice on either line actually flows through.
     */
    override suspend fun startAudioRecording(): Boolean {
        val target = browser ?: return false
        recordingChunks.setLength(0)
        run(target, EngineBridge.START_RECORDING_SCRIPT)
        mediasoup?.sendRecording(true)
        return true
    }

    override suspend fun stopAudioRecording() {
        mediasoup?.sendRecording(false)
        browser?.let { run(it, EngineBridge.STOP_RECORDING_SCRIPT) }
    }

    override fun setStage(json: String) {
        if (json == lastStage) return
        // An empty stage is "nothing to show", not a model. Remembering it
        // would have the ready-replay push `setStage("")` at the next page,
        // which answers with `Unexpected end of JSON input`.
        if (json.isEmpty()) {
            lastStage = null
            return
        }
        lastStage = json
        browser?.let { run(it, EngineBridge.stageScript(json)) }
    }

    override fun setAvatar(userId: String, dataUri: String) {
        if (userId.isBlank() || avatars[userId] == dataUri) return
        avatars[userId] = dataUri
        browser?.let { run(it, EngineBridge.avatarScript(userId, dataUri)) }
    }

    override fun setTheme(json: String) {
        if (json == lastTheme) return
        lastTheme = json
        browser?.let { run(it, EngineBridge.themeScript(json)) }
    }

    override fun setCompact(compact: Boolean) {
        browser?.let { run(it, EngineBridge.compactScript(compact)) }
    }

    override fun showReaction(json: String) {
        browser?.let { run(it, EngineBridge.reactionScript(json)) }
    }

    /**
     * Decodes the assembled slices and files them under Downloads, which is
     * where a desktop user goes looking for "the app saved something".
     *
     * The extension comes from [recorded] rather than a constant: what the
     * page could write is what is in the bytes, and a `.webm` name on an MP4
     * file is the kind of lie that only shows up when someone double-clicks
     * it.
     */
    private fun saveRecording(recorded: RecordedAudio) {
        val encoded = recordingChunks.toString()
        recordingChunks.setLength(0)
        if (encoded.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = java.util.Base64.getDecoder().decode(encoded)
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US)
                    .format(java.util.Date())
                val downloads = File(System.getProperty("user.home"), "Downloads")
                val dir = if (downloads.isDirectory) downloads else File(System.getProperty("user.home"))
                val file = File(dir, "Zillit call recording $stamp.${recorded.extension}")
                file.writeBytes(bytes)
                file
            }.onSuccess { file ->
                ZillitLog.i(TAG) {
                    "call recording saved: ${file.absolutePath} (${file.length()} bytes, ${recorded.mimeType})"
                }
                _events.tryEmit(
                    CallEngineEvent.RecordingSaved(
                        path = file.absolutePath,
                        contentType = recorded.contentType,
                        durationMillis = recorded.durationMillis,
                    ),
                )
            }.onFailure { thrown ->
                ZillitLog.w(TAG) { "call recording not saved: ${thrown.message}" }
            }
        }
    }

    override suspend fun destroy() {
        leave()
        browser?.close(true)
        browser = null
        _surface.value = null
        client?.dispose()
        client = null
        // A fresh latch: the old one is completed, and a rebuilt page that
        // reported ready once must report it again before anyone joins.
        pageReady = CompletableDeferred()
    }

    private companion object {
        const val TAG = "KcefCallEngine"
        const val PAGE_READY_TIMEOUT_MS = 20_000L
        const val WARMUP_DELAY_MS = 8_000L

    }
}

private fun run(target: CefBrowser, script: String) {
    target.executeJavaScript(script, target.url, 0)
}

/**
 * The invisible window the call page lives in when nothing is showing it.
 *
 * JCEF only creates its native browser once its AWT component is in a
 * displayable hierarchy, so the page has to be parented somewhere from the
 * moment it is built — including long before any call. This is that somewhere.
 */
private class OffscreenHolder {
    private var window: JWindow? = null

    fun park(component: Component) {
        val parked = window ?: JWindow().also { fresh ->
            fresh.focusableWindowState = false
            // Invisible rather than merely moved away. macOS clamps a window
            // back onto the desktop rather than honouring a large negative
            // origin, so "parked past the edge of every screen" put a 640 px
            // panel in the top-left corner instead — showing the last call's
            // tiles, and swallowing every click that landed on it.
            runCatching { fresh.opacity = 0f }
            window = fresh
        }
        parked.contentPane.add(component)
        parked.isVisible = true
        // Bounds after the component is in and the window is up, not before:
        // JCEF creates the native browser off the hierarchy-bounds events its
        // component receives, and a window sized before it had a child sends
        // none of them. The size is real because the browser needs one; only
        // the pixels are hidden.
        parked.setBounds(OFFSCREEN, OFFSCREEN, SIZE, SIZE)
        parked.validate()
        parked.toBack()
    }

    /**
     * A displayable AWT window keeps the JVM alive on its own, so a holder
     * left behind outlives the app that made it: the process stays up with no
     * main window, which is exactly what it looked like — an app that would
     * not quit.
     */
    fun dispose() {
        val parked = window ?: return
        window = null
        runCatching { SwingUtilities.invokeLater { parked.dispose() } }
    }

    private companion object {
        /** Far enough out that no arrangement of displays reaches it. */
        const val OFFSCREEN = -8_000

        /** Big enough that AWT treats the window as real. */
        const val SIZE = 640
    }
}

/**
 * Copies the bundled call page onto disk and returns its entry point.
 *
 * Chromium loads it as a `file:` URL rather than from the jar: the SDK inside
 * pulls in its own workers and wasm by relative path, and none of that
 * resolves against a resource stream. Rewritten every start, so an app update
 * cannot leave last version's page behind.
 */
private fun extractPage(): File {
    val dir = File(System.getProperty("user.home"), ".zillit/callengine").apply { mkdirs() }
    PAGE_FILES.forEach { name ->
        val resource = checkNotNull(KcefCallEngine::class.java.getResourceAsStream("/callengine/$name")) {
            "missing bundled resource callengine/$name"
        }
        resource.use { input -> File(dir, name).outputStream().use(input::copyTo) }
    }
    return File(dir, "call.html")
}

private val PAGE_FILES = listOf(
    "call.html",
    "call.js",
    "agora-rtc-sdk-ng-4.24.2.js",
    // Line 1. The bundle is the media half of mediasoup-client only; protoo
    // signalling is Kotlin's, so no WebSocket client is shipped here.
    "mediasoup-client.js",
    "mediasoup.js",
    // Line 3. LiveKit's browser SDK and the page half that drives it.
    "livekit-client-2.22.2.umd.js",
    "livekit.js",
)

/**
 * Wires the handlers and opens the call page.
 *
 * Windowed rendering, so video takes Chromium's own GPU path rather than being
 * copied frame by frame through jogamp. The cost is that the native browser is
 * not created until its AWT component is realised, which is what parking it
 * offscreen exists to arrange.
 *
 * Must run on the EDT: this builds AWT objects, and JCEF is unforgiving about
 * being driven from anywhere else.
 */
private fun buildBrowser(cefClient: CefClient, page: File, onMessage: (String) -> Unit): CefBrowser {
    cefClient.addMessageRouter(KcefPage.messageRouter(onMessage))
    cefClient.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(BROWSER_TAG) { note } })
    cefClient.addDisplayHandler(
        KcefPage.displayHandler { note -> ZillitLog.w(BROWSER_TAG) { "console: $note" } },
    )
    // Without this the page cannot reach a camera, a microphone or the screen
    // at all — CEF denies media requests that nobody answers.
    cefClient.addPermissionHandler(
        KcefPage.permissionHandler { note -> ZillitLog.i(BROWSER_TAG) { note } },
    )
    ZillitLog.i(BROWSER_TAG) { "call page loading from ${page.absolutePath}" }
    return cefClient.createBrowser("file://${page.absolutePath}", CefRendering.DEFAULT, false)
}

/**
 * Page steps whose warnings the user is owed an explanation for.
 *
 * Deliberately a short list. Most page warnings are noise a user can do
 * nothing about — a device label arriving late, a codec preference declined —
 * and a banner for each would train people to ignore the banner.
 */
private val SHARE_WARNINGS = setOf("produce-screen", "startScreenShare")

/*
 * Exactly the two spellings the pages actually send, checked against them
 * rather than guessed: `produce-screen` is mediasoup.js's, `startScreenShare`
 * is call.js's own function name, which is what its warn() now reports. An
 * earlier version of this set listed two steps that no page has ever emitted,
 * so on the Agora line — the default — a failed share matched nothing and the
 * user was told nothing.
 */

private const val BROWSER_TAG = "KcefCallEngine"
