package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.maps.data.MapCanvasWire
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapPinMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import java.awt.Component
import java.io.File
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * The Maps tool's canvas: Google's Maps JS inside embedded Chromium.
 *
 * The JVM has no map widget, so the map is the same one the web client runs —
 * `maps.googleapis.com/maps/api/js` — hosted by the JetBrains Runtime's JCEF
 * (see [KcefRuntime], shared with the call engine; each engine holds its own
 * `CefClient` and browser, only the process-wide `CefApp` is common).
 *
 * Kotlin drives the page with `zillitMap.*` calls and hears back over the CEF
 * message router as one-line JSON; both directions are the [MapCanvasWire]
 * contract, where all the parseable logic lives so this class stays a
 * transport — the same split the call engine makes with `EngineBridge`.
 *
 * ## How the key travels
 *
 * The extracted page carries no key. On its `ready` announcement the key is
 * read from remote config ([googleMapsKey] — already decrypted there, as the
 * web decrypts `google_map_key` in `map-module/hooks/useGoogleMapsKey.js`)
 * and pushed down as one `zillitMap.boot(key)` script. It exists in the page
 * runtime only: never written to the extracted file, never logged.
 *
 * Lifecycle: nothing starts until [open] — the tool is rarely the first thing
 * used, and Chromium may already be up for calling. The browser then lives for
 * the process, parked in a hidden holder window between openings of the tool
 * (the call engine's pattern), so reopening the Maps tool is instant.
 */
class KcefMapEngine(
    /** The decrypted Google Maps JS key, or null when the production has none. */
    private val googleMapsKey: suspend () -> String?,
    private val scope: CoroutineScope,
) : MapCanvasHost {

    private val _events = MutableSharedFlow<MapCanvasEvent>(extraBufferCapacity = 32)
    override val events: Flow<MapCanvasEvent> = _events.asSharedFlow()

    private val initLock = Mutex()
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var holder: JWindow? = null

    /**
     * The last pins and camera pushed down, replayed on the page's map-ready —
     * the tool's ViewModel may have pushed both while Chromium was still
     * coming up, and a page rebuilt later must not open empty.
     */
    @Volatile
    private var lastPins: List<MapPinMarker> = emptyList()

    @Volatile
    private var lastCenter: Center? = null

    private data class Center(val lat: Double, val lng: Double, val zoom: Int)

    private val _surface = MutableStateFlow<Component?>(null)

    /**
     * The page's AWT surface once it exists. A flow rather than a getter
     * because the host composes the canvas pane the moment the tool opens,
     * which can be before Chromium has finished coming up.
     */
    val surface: StateFlow<Component?> = _surface.asStateFlow()

    /** Brings the canvas up. Idempotent; called each time the tool's pane composes. */
    fun open() {
        scope.launch { prepare() }
    }

    private suspend fun prepare(): Boolean = initLock.withLock {
        if (browser != null) return@withLock true
        KcefRuntime.start()
        val cefClient = client ?: KcefRuntime.client()?.also { client = it }
        if (cefClient == null) {
            ZillitLog.w(TAG) { "map canvas unavailable (${KcefRuntime.failure})" }
            _events.tryEmit(MapCanvasEvent.Failed(unavailableReason()))
            return@withLock false
        }
        val page = withContext(Dispatchers.IO) { runCatching { extractPage() } }
            .onFailure { thrown -> ZillitLog.w(TAG) { "map page not extracted: ${thrown.message}" } }
            .getOrNull()
        if (page == null) {
            _events.tryEmit(MapCanvasEvent.Failed("The map page could not be prepared."))
            return@withLock false
        }
        // On the EDT: this builds AWT components, and JCEF is unforgiving
        // about being driven from anywhere else.
        withContext(Dispatchers.Swing) {
            runCatching { buildBrowser(cefClient, page) }.onFailure { thrown ->
                ZillitLog.w(TAG) { "map browser build failed: ${thrown.message ?: thrown::class.simpleName}" }
            }
        }
        browser != null
    }

    private fun unavailableReason(): String = when (val failure = KcefRuntime.failure) {
        KcefRuntime.Failure.NoJcefRuntime ->
            "This build has no embedded browser, so the map cannot be shown."
        is KcefRuntime.Failure.Broken ->
            "The embedded browser could not start (${failure.reason})."
        null -> "The embedded browser is unavailable."
    }

    private fun buildBrowser(cefClient: CefClient, page: File) {
        if (browser != null) return
        cefClient.addMessageRouter(KcefPage.messageRouter(::onPageMessage))
        cefClient.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        cefClient.addDisplayHandler(KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } })
        browser = cefClient.createBrowser(
            "file://${page.absolutePath}",
            // Windowed, like the call engine: the map takes Chromium's own GPU
            // path. The native browser is only created once its AWT component
            // is realised — which [hold] arranges when nothing displays it.
            CefRendering.DEFAULT,
            false,
        )
        browser?.uiComponent?.let { component ->
            hold(component)
            _surface.value = component
        }
        ZillitLog.i(TAG) { "map page loading from ${page.absolutePath}" }
    }

    /**
     * Parks the browser's component in a window nobody looks at — the call
     * engine's trick, for the same reason: JCEF creates the native browser
     * from a realised AWT peer, and a component with no parent at all stops
     * being a browser. The window is invisible (macOS clamps offscreen
     * windows back onto the desktop) and lent to the tool's pane while open.
     */
    private fun hold(component: Component) {
        val window = holder ?: JWindow().also { window ->
            window.focusableWindowState = false
            runCatching { window.opacity = 0f }
            holder = window
        }
        window.contentPane.add(component)
        window.isVisible = true
        window.setBounds(HOLDER_OFFSCREEN, HOLDER_OFFSCREEN, HOLDER_SIZE, HOLDER_SIZE)
        window.validate()
        window.toBack()
    }

    /**
     * Lets go of the parking window. A displayable AWT window keeps the JVM
     * alive on its own; [Shutdown] calls this so the process can end.
     */
    fun releaseHolder() {
        val window = holder ?: return
        holder = null
        runCatching { SwingUtilities.invokeLater { window.dispose() } }
    }

    /** Takes the component back when the tool's pane leaves the screen. */
    fun releaseSurface() {
        val component = _surface.value ?: return
        SwingUtilities.invokeLater { hold(component) }
    }

    private fun onPageMessage(message: String) {
        if (MapCanvasWire.isPageReady(message)) {
            // The shell is up and keyless; answer with the key.
            boot()
            return
        }
        MapCanvasWire.parse(message)?.let { event ->
            if (event is MapCanvasEvent.Ready) replayState()
            if (event is MapCanvasEvent.Failed) ZillitLog.w(TAG) { "map page: ${event.message}" }
            _events.tryEmit(event)
        }
    }

    /**
     * Pushes the key down to the loaded page.
     *
     * The script carries the decrypted key, so it is executed and forgotten —
     * never logged, never persisted; the extracted `map.html` stays keyless
     * on disk.
     */
    private fun boot() {
        scope.launch {
            val target = browser ?: return@launch
            val key = runCatching { googleMapsKey() }.getOrNull()
            if (key.isNullOrBlank()) {
                ZillitLog.w(TAG) { "no Google Maps key in remote config; map stays blank" }
                _events.tryEmit(
                    MapCanvasEvent.Failed("This production has no Google Maps key, so the map cannot load."),
                )
                return@launch
            }
            run(target, MapCanvasWire.bootScript(key))
        }
    }

    /**
     * Restates what Kotlin knows on the page's map-ready: everything told to
     * a page before its map existed — or to a previous page — is replayed so
     * the map never opens empty for whatever was already loaded.
     */
    private fun replayState() {
        val target = browser ?: return
        if (lastPins.isNotEmpty()) run(target, MapCanvasWire.pinsScript(lastPins))
        lastCenter?.let { c -> run(target, MapCanvasWire.centerScript(c.lat, c.lng, c.zoom)) }
    }

    override fun setPins(pins: List<MapPinMarker>) {
        lastPins = pins
        browser?.let { run(it, MapCanvasWire.pinsScript(pins)) }
    }

    override fun center(lat: Double, lng: Double, zoom: Int) {
        lastCenter = Center(lat, lng, zoom)
        browser?.let { run(it, MapCanvasWire.centerScript(lat, lng, zoom)) }
    }

    private fun run(target: CefBrowser, script: String) {
        target.executeJavaScript(script, target.url, 0)
    }

    /**
     * The page, unpacked where `file://` can reach it. Re-extracted on every
     * launch so a stale copy cannot survive an upgrade.
     */
    private fun extractPage(): File {
        val dir = File(System.getProperty("user.home"), ".zillit/mapengine").apply { mkdirs() }
        PAGE_FILES.forEach { name ->
            val resource = checkNotNull(javaClass.getResourceAsStream("/mapengine/$name")) {
                "missing bundled resource mapengine/$name"
            }
            resource.use { input -> File(dir, name).outputStream().use(input::copyTo) }
        }
        return File(dir, "map.html")
    }

    private companion object {
        const val TAG = "KcefMapEngine"

        /** Far enough out that no arrangement of displays reaches it. */
        const val HOLDER_OFFSCREEN = -8_000

        /** Big enough that AWT treats the window as real. */
        const val HOLDER_SIZE = 640

        val PAGE_FILES = listOf("map.html")
    }
}
