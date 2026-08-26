package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.LocationPickerEvent
import com.zillit.desktop.core.locationpicker.LocationPickerWire
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.PickerTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume

/**
 * The desktop's [LocationPicker]: Google's Places-enabled map inside embedded
 * Chromium, shown as a modal.
 *
 * The JVM has no map widget, so this is the same map the web pickers run —
 * `maps.googleapis.com/maps/api/js` with the `places` library — hosted by the
 * JetBrains Runtime's JCEF ([KcefRuntime], shared with the call and Maps
 * engines; each holds its own `CefClient` and browser, only the process-wide
 * `CefApp` is common).
 *
 * **Its own engine instance, deliberately.** The Maps tool may be open behind
 * the dialog, and a browser cannot be in two places at once: the tool's canvas
 * would go blank the moment the picker borrowed it, and come back centred on
 * whatever the picker last looked at. Two engines cost one extra Chromium
 * render process, which is the cheaper of the two prices.
 *
 * Kotlin drives the page with `zillitPicker.*` calls and hears back over the
 * CEF message router as one-line JSON; both directions are the
 * `LocationPickerWire` contract, where all the parseable logic lives so this
 * class stays a transport — the split [KcefMapEngine] makes with
 * `MapCanvasWire`, and the call engine with `EngineBridge`.
 *
 * ## How the key travels
 *
 * The extracted page carries no key. On its `ready` announcement the key is
 * read from remote config ([googleMapsKey] — already decrypted there, as the
 * web decrypts `google_map_key` in `map-module/hooks/useGoogleMapsKey.js`) and
 * pushed down as one `zillitPicker.boot(key, theme)` script. It exists in the
 * page runtime only: never written to the extracted file, never logged.
 *
 * Lifecycle: nothing starts until the first [pick] — most sessions never open
 * a picker at all. The browser then lives for the process, parked in a hidden
 * holder window between openings (the call and map engines' pattern), so the
 * second picker opens instantly. [releaseHolder] is registered with [Shutdown]
 * because a displayable AWT window keeps the JVM alive on its own.
 */
class KcefLocationPickerHost(
    /** The decrypted Google Maps JS key, or null when the production has none. */
    private val googleMapsKey: suspend () -> String?,
    private val scope: CoroutineScope,
) : LocationPicker {

    /** One opening of the dialog: what to call it, and where to start. */
    data class Request(val title: String, val initial: PickedLocation?)

    private val _request = MutableStateFlow<Request?>(null)

    /** Non-null while the dialog is on screen. The overlay composes on this. */
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _picked = MutableStateFlow<PickedLocation?>(null)

    /** What "Use this location" would return right now; null until something is chosen. */
    val picked: StateFlow<PickedLocation?> = _picked.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the map is not there, when it is not there. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _surface = MutableStateFlow<Component?>(null)

    /**
     * The page's AWT surface once it exists. A flow rather than a getter
     * because the dialog composes the moment the user asks for it, which is
     * before Chromium has finished coming up.
     */
    val surface: StateFlow<Component?> = _surface.asStateFlow()

    private val initLock = Mutex()
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var holder: JWindow? = null

    /** Guards [pending] alone: CEF answers on its own threads, Compose on the EDT. */
    private val pendingLock = Any()
    private var pending: CancellableContinuation<PickedLocation?>? = null

    /**
     * The place the dialog was opened on, replayed onto the page — which may
     * be a page that does not exist yet, or one already showing the *last*
     * pick, depending on whether this is the first opening.
     */
    @Volatile
    private var seed: PickedLocation? = null

    /** True once Google's map object exists, so a seed can be pushed directly. */
    @Volatile
    private var mapReady = false

    /** The app's colours, restated to the page whenever the theme changes. */
    @Volatile
    private var theme: PickerTheme = DEFAULT_THEME

    override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? =
        suspendCancellableCoroutine { continuation ->
            // Only one dialog can be on screen, so a second ask replaces the
            // first rather than queueing behind it — the first caller's field
            // is no longer the one in front of the user.
            val displaced = synchronized(pendingLock) {
                pending.also { pending = continuation }
            }
            displaced?.takeIf { it.isActive }?.resume(null)

            seed = initial
            _picked.value = initial
            _failure.value = null
            _request.value = Request(title = title, initial = initial)
            // The field's composition can leave while the dialog is up (its
            // form closed underneath it); the dialog must not outlive it.
            continuation.invokeOnCancellation { withdraw() }
            scope.launch { open() }
        }

    /** Hands back the current pick and closes. */
    fun confirm() = finish(_picked.value)

    /** Closes with nothing. Also what the scrim and the header's close do. */
    fun cancel() = finish(null)

    /** The app's theme, for the page's own search box. See `picker.html`. */
    fun useTheme(next: PickerTheme) {
        if (next == theme) return
        theme = next
        // Re-boot rather than a theme-only call: `boot` applies the theme
        // before its own idempotence guard, so a second one is a restyle.
        if (browser != null) boot()
    }

    private fun finish(result: PickedLocation?) {
        val continuation = synchronized(pendingLock) { pending.also { pending = null } }
        withdraw()
        continuation?.takeIf { it.isActive }?.resume(result)
    }

    /** Takes the dialog off screen and leaves the page ready for the next opening. */
    private fun withdraw() {
        _request.value = null
        _picked.value = null
        seed = null
        browser?.let { target -> run(target, LocationPickerWire.resetScript) }
    }

    /** Brings the picker up, and seeds it if the page is already live. */
    private suspend fun open() {
        if (!prepare()) return
        if (mapReady) replaySeed()
    }

    private suspend fun prepare(): Boolean = initLock.withLock {
        if (browser != null) return@withLock true
        KcefRuntime.start()
        val cefClient = client ?: KcefRuntime.client()?.also { client = it }
        if (cefClient == null) {
            ZillitLog.w(TAG) { "location picker unavailable (${KcefRuntime.failure})" }
            _failure.value = unavailableReason()
            return@withLock false
        }
        val page = withContext(Dispatchers.IO) { runCatching { extractPage() } }
            .onFailure { thrown -> ZillitLog.w(TAG) { "picker page not extracted: ${thrown.message}" } }
            .getOrNull()
        if (page == null) {
            _failure.value = "The map page could not be prepared."
            return@withLock false
        }
        // On the EDT: this builds AWT components, and JCEF is unforgiving
        // about being driven from anywhere else.
        withContext(Dispatchers.Swing) {
            runCatching { buildBrowser(cefClient, page) }.onFailure { thrown ->
                ZillitLog.w(TAG) { "picker browser build failed: ${thrown.message ?: thrown::class.simpleName}" }
            }
        }
        browser != null
    }

    private fun unavailableReason(): String = when (val reason = KcefRuntime.failure) {
        KcefRuntime.Failure.NoJcefRuntime ->
            "This build has no embedded browser, so the map picker cannot open."
        is KcefRuntime.Failure.Broken ->
            "The embedded browser could not start (${reason.reason})."
        null -> "The embedded browser is unavailable."
    }

    private fun buildBrowser(cefClient: CefClient, page: File) {
        if (browser != null) return
        cefClient.addMessageRouter(KcefPage.messageRouter(::onPageMessage))
        cefClient.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        cefClient.addDisplayHandler(KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } })
        browser = cefClient.createBrowser(
            "file://${page.absolutePath}",
            // Windowed, like the map engine: the map takes Chromium's own GPU
            // path, and the page's search box takes real keyboard focus.
            CefRendering.DEFAULT,
            false,
        )
        browser?.uiComponent?.let { component ->
            hold(component)
            _surface.value = component
        }
        ZillitLog.i(TAG) { "picker page loading from ${page.absolutePath}" }
    }

    /**
     * Parks the browser's component in a window nobody looks at — the call and
     * map engines' trick, for the same reason: JCEF creates the native browser
     * from a realised AWT peer, and a component with no parent at all stops
     * being a browser. The window is invisible (macOS clamps offscreen windows
     * back onto the desktop) and lent to the dialog while it is open.
     */
    private fun hold(component: Component) {
        val window = holder ?: JWindow().also { fresh ->
            fresh.focusableWindowState = false
            runCatching { fresh.opacity = 0f }
            holder = fresh
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

    /** Takes the component back when the dialog leaves the screen. */
    fun releaseSurface() {
        val component = _surface.value ?: return
        SwingUtilities.invokeLater { hold(component) }
    }

    private fun onPageMessage(message: String) {
        if (LocationPickerWire.isPageReady(message)) {
            // The shell is up and keyless; answer with the key and the theme.
            boot()
            return
        }
        when (val event = LocationPickerWire.parse(message)) {
            LocationPickerEvent.MapReady -> {
                mapReady = true
                replaySeed()
            }
            is LocationPickerEvent.Picked -> _picked.value = event.location
            is LocationPickerEvent.Failed -> {
                ZillitLog.w(TAG) { "picker page: ${event.message}" }
                _failure.value = event.message
            }
            null -> Unit
        }
    }

    /**
     * Pushes the key and the theme down to the loaded page.
     *
     * The script carries the decrypted key, so it is executed and forgotten —
     * never logged, never persisted; the extracted `picker.html` stays keyless
     * on disk.
     */
    private fun boot() {
        scope.launch {
            val target = browser ?: return@launch
            val key = runCatching { googleMapsKey() }.getOrNull()
            if (key.isNullOrBlank()) {
                ZillitLog.w(TAG) { "no Google Maps key in remote config; picker stays blank" }
                _failure.value = "This production has no Google Maps key, so the map cannot load."
                return@launch
            }
            run(target, LocationPickerWire.bootScript(key, LocationPickerWire.themeJson(theme)))
        }
    }

    /**
     * Restates the place the dialog was opened on.
     *
     * Runs on the page's map-ready *and* on every later opening, because the
     * browser outlives the dialog: the second time round the page is long
     * since ready and still showing the previous pick.
     */
    private fun replaySeed() {
        val target = browser ?: return
        val place = seed ?: return
        run(target, LocationPickerWire.searchTextScript(place.address))
        run(target, LocationPickerWire.setPinScript(place.lat, place.lng))
        run(target, LocationPickerWire.centerScript(place.lat, place.lng, PLACE_ZOOM))
    }

    private fun run(target: CefBrowser, script: String) {
        target.executeJavaScript(script, target.url, 0)
    }

    /**
     * The page, unpacked where `file://` can reach it. Re-extracted on every
     * launch so a stale copy cannot survive an upgrade. Shares the map
     * engine's directory and no filename with it.
     */
    private fun extractPage(): File {
        val dir = File(System.getProperty("user.home"), ".zillit/mapengine").apply { mkdirs() }
        val resource = checkNotNull(javaClass.getResourceAsStream("/mapengine/$PAGE_FILE")) {
            "missing bundled resource mapengine/$PAGE_FILE"
        }
        val page = File(dir, PAGE_FILE)
        resource.use { input -> page.outputStream().use(input::copyTo) }
        return page
    }

    private companion object {
        const val TAG = "KcefLocationPickerHost"
        const val PAGE_FILE = "picker.html"

        /** The web's zoom once a place is known (PlacePicker.jsx:322). */
        const val PLACE_ZOOM = 15

        /** Far enough out that no arrangement of displays reaches it. */
        const val HOLDER_OFFSCREEN = -8_000

        /** Big enough that AWT treats the window as real. */
        const val HOLDER_SIZE = 640

        /**
         * Stands in until the overlay reports the live theme — which it does
         * on its first composition, before the page can have booted.
         */
        val DEFAULT_THEME = PickerTheme(
            background = "#E5E3DF",
            surface = "#FFFFFF",
            text = "#101828",
            accent = "#F97316",
            border = "#D0D5DD",
            isDark = false,
        )
    }
}
