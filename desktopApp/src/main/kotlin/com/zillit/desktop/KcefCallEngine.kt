package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.data.EngineBridge
import com.zillit.desktop.feature.calls.domain.CallDeviceKind
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
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
class KcefCallEngine(
    private val appId: String,
    scope: CoroutineScope,
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

    override val isReady: Boolean get() = browser != null && pageReady.isCompleted

    /**
     * Which host currently owns the browser component, as a monotonic ticket.
     *
     * Atomic because it is read from the deferred park on the event thread and
     * bumped from composition; a plain var would be a data race on a field
     * whose whole job is deciding whether to re-parent a live component.
     */
    private val surfaceClaim = SurfaceClaims()

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

    private fun onPageMessage(message: String) {
        if (EngineBridge.isReady(message)) {
            ZillitLog.i(TAG) { "call page ready" }
            pageReady.complete(Unit)
            // A page that has just (re)loaded is holding its built-in defaults.
            // Everything Kotlin told the previous one died with it, so the
            // palette and the tile model are restated rather than waited for —
            // the next push might be a minute away, and until then the stage
            // would be a grey slab with unlabelled tiles.
            lastTheme?.let { theme -> browser?.let { run(it, EngineBridge.themeScript(theme)) } }
            lastStage?.let { stage -> browser?.let { run(it, EngineBridge.stageScript(stage)) } }
            return
        }
        EngineBridge.warning(message)?.let { text ->
            // Degraded, not dead — a missing camera on an audio call lands here.
            ZillitLog.w(TAG) { "page warning: $text" }
            return
        }
        EngineBridge.parse(message)?.let { event ->
            if (event is CallEngineEvent.Failed) {
                ZillitLog.w(TAG) { "page error: ${event.message}" }
            }
            _events.tryEmit(event)
        }
    }

    override suspend fun join(channel: String, token: String, uid: Int, hasVideo: Boolean) {
        val target = browser ?: run {
            _events.tryEmit(CallEngineEvent.Failed("media engine is not ready"))
            return
        }
        ZillitLog.i(TAG) { "joining channel=$channel uid=$uid video=$hasVideo" }
        run(target, EngineBridge.joinScript(appId, channel, token, uid, hasVideo))
    }

    override suspend fun leave() {
        browser?.let { run(it, EngineBridge.LEAVE_SCRIPT) }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        browser?.let { run(it, EngineBridge.micScript(muted)) }
    }

    override fun setCameraEnabled(enabled: Boolean) {
        browser?.let { run(it, EngineBridge.camScript(enabled)) }
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
        browser?.let { run(it, EngineBridge.deviceScript(kind, deviceId)) }
    }

    /**
     * True means "the page was asked", not "the user shared" — Chromium's own
     * source selection happens after this returns, and a cancelled share
     * comes back as a `screen-share false` event rather than a failure here.
     */
    override suspend fun startScreenShare(): Boolean {
        val target = browser ?: return false
        run(target, EngineBridge.START_SCREEN_SHARE_SCRIPT)
        return true
    }

    override suspend fun stopScreenShare() {
        browser?.let { run(it, EngineBridge.STOP_SCREEN_SHARE_SCRIPT) }
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

    override fun setTheme(json: String) {
        if (json == lastTheme) return
        lastTheme = json
        browser?.let { run(it, EngineBridge.themeScript(json)) }
    }

    override fun setCompact(compact: Boolean) {
        browser?.let { run(it, EngineBridge.compactScript(compact)) }
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

private val PAGE_FILES = listOf("call.html", "call.js", "agora-rtc-sdk-ng-4.24.2.js")

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
    ZillitLog.i(BROWSER_TAG) { "call page loading from ${page.absolutePath}" }
    return cefClient.createBrowser("file://${page.absolutePath}", CefRendering.DEFAULT, false)
}

private const val BROWSER_TAG = "KcefCallEngine"
