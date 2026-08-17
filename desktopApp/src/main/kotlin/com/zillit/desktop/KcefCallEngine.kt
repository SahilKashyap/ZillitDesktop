package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.data.EngineBridge
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
    private var holder: JWindow? = null

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
            runCatching { buildBrowser(cefClient, page) }.onFailure { thrown ->
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

    private fun buildBrowser(cefClient: CefClient, page: File) {
        if (browser != null) return
        cefClient.addMessageRouter(KcefPage.messageRouter(::onPageMessage))
        cefClient.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        cefClient.addDisplayHandler(KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } })
        browser = cefClient.createBrowser(
            "file://${page.absolutePath}",
            // Windowed, so video takes Chromium's own GPU path rather than
            // being copied frame by frame through jogamp. The cost is that
            // the native browser is not created until its AWT component is
            // realised — which is what [hold] exists to arrange.
            CefRendering.DEFAULT,
            false,
        )
        browser?.uiComponent?.let { component ->
            hold(component)
            _surface.value = component
        }
        ZillitLog.i(TAG) { "call page loading from ${page.absolutePath}" }
    }

    /**
     * Parks the browser's component in a window nobody looks at.
     *
     * JCEF creates the native browser from a realised AWT peer and not
     * before, so a page nothing displays never loads — and the call page has
     * to be loaded before the call that would display it, which is a
     * chicken-and-egg the media stack loses. This gives it a window that
     * satisfies AWT, parked past the edge of every desktop, and lends the
     * component to the real call surface when a video call wants one.
     */
    private fun hold(component: Component) {
        val window = holder ?: JWindow().also { window ->
            window.focusableWindowState = false
            // Invisible rather than merely moved away. macOS clamps a window
            // back onto the desktop rather than honouring a large negative
            // origin, so "parked past the edge of every screen" put a 640 px
            // panel in the top-left corner instead — showing the last call's
            // tiles, and swallowing every click that landed on it.
            runCatching { window.opacity = 0f }
            holder = window
        }
        window.contentPane.add(component)
        window.isVisible = true
        // Bounds after the component is in and the window is up, not before:
        // JCEF creates the native browser off the hierarchy-bounds events its
        // component receives, and a window sized before it had a child sends
        // none of them. The size is real because the browser needs one; only
        // the pixels are hidden.
        window.setBounds(HOLDER_OFFSCREEN, HOLDER_OFFSCREEN, HOLDER_SIZE, HOLDER_SIZE)
        window.validate()
        window.toBack()
    }

    /**
     * Lets go of the parking window.
     *
     * A displayable AWT window keeps the JVM alive on its own, so a holder
     * left behind outlives the app that made it: the process stays up with no
     * main window, which is exactly what it looked like — an app that would
     * not quit.
     */
    fun releaseHolder() {
        val window = holder ?: return
        holder = null
        runCatching { SwingUtilities.invokeLater { window.dispose() } }
    }

    /**
     * Takes the component back when the call surface goes away.
     *
     * Without this the component is left with no parent once its Compose
     * panel is disposed, and a browser whose peer has been destroyed stops
     * being a media stack — the *next* call would be the one that broke.
     */
    fun releaseSurface() {
        val component = _surface.value ?: return
        SwingUtilities.invokeLater { hold(component) }
    }

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

    /**
     * The page and its two scripts, unpacked where `file://` can reach them.
     *
     * Re-extracted on every launch: the bundle is the versioned copy, and a
     * stale extraction surviving an upgrade is a debugging session nobody
     * needs.
     */
    private fun extractPage(): File {
        val dir = File(System.getProperty("user.home"), ".zillit/callengine").apply { mkdirs() }
        PAGE_FILES.forEach { name ->
            val resource = checkNotNull(javaClass.getResourceAsStream("/callengine/$name")) {
                "missing bundled resource callengine/$name"
            }
            resource.use { input -> File(dir, name).outputStream().use(input::copyTo) }
        }
        return File(dir, "call.html")
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

    private fun run(target: CefBrowser, script: String) {
        target.executeJavaScript(script, target.url, 0)
    }

    private companion object {
        const val TAG = "KcefCallEngine"
        const val PAGE_READY_TIMEOUT_MS = 20_000L
        const val WARMUP_DELAY_MS = 8_000L

        /** Far enough out that no arrangement of displays reaches it. */
        const val HOLDER_OFFSCREEN = -8_000

        /** Big enough that AWT treats the window as real. */
        const val HOLDER_SIZE = 640

        val PAGE_FILES = listOf("call.html", "call.js", "agora-rtc-sdk-ng-4.24.2.js")
    }
}
