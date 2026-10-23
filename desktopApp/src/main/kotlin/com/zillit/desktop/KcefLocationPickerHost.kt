package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitVariant
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.LocationPickerEvent
import com.zillit.desktop.core.locationpicker.LocationPickerWire
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.PickerStart
import com.zillit.desktop.core.locationpicker.PickerTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
// One member per thing the page or the dialog can do, as KcefCallEngine has.
@Suppress("TooManyFunctions")
class KcefLocationPickerHost(
    /** The decrypted Google Maps JS key, or null when the production has none. */
    private val googleMapsKey: suspend () -> String?,
    /**
     * Where this machine is, for "My Location" and for the start position of a
     * picker opened on nothing. Null where the app cannot say — see
     * [DeviceGeolocation], which is what it is in the running app.
     */
    private val locate: suspend () -> GeoPoint?,
    /**
     * The last place this device picked, `"lat,lng"`, and how to write it.
     * A per-device convenience only: losing it costs one position lookup.
     * The web keeps the same thing in `localStorage`
     * (`RecceLocationPicker.jsx`, `recce_map_last_center`).
     */
    private val lastCenter: suspend () -> String = { "" },
    private val rememberLastCenter: suspend (String) -> Unit = {},
    private val scope: CoroutineScope,
) : LocationPicker {

    /** One opening of the dialog: what to call it, where to start, and whether anything may be chosen. */
    data class Request(val title: String, val initial: PickedLocation?, val readOnly: Boolean = false)

    private val _request = MutableStateFlow<Request?>(null)

    /** Non-null while the dialog is on screen. The overlay composes on this. */
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _picked = MutableStateFlow<PickedLocation?>(null)

    /** What "Use this location" would return right now; null until something is chosen. */
    val picked: StateFlow<PickedLocation?> = _picked.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the map is not there, when it is not there. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _locating = MutableStateFlow(false)

    /** True while the position lookup is in flight; the button waits on it. */
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val _startLocating = MutableStateFlow(false)

    /**
     * True while an opening with nothing to go on is waiting for a position
     * fix — the map sits at a world view and says "Finding your location…",
     * as the web's picker does, rather than looking like a broken map.
     */
    val startLocating: StateFlow<Boolean> = _startLocating.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)

    /**
     * One line under the map for something that did not work but must not take
     * the map away — today only a position nobody could find. [failure]
     * replaces the map; this sits beside the pick.
     */
    val hint: StateFlow<String?> = _hint.asStateFlow()

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

    /**
     * Where to open NEXT TO when [seed] has no pin: a place the caller
     * already knows (a neighbouring recce stop). Centres the map and drops
     * no pin, so the user still picks the actual spot.
     */
    @Volatile
    private var nearby: PickerStart? = null

    /** True once Google's map object exists, so a seed can be pushed directly. */
    @Volatile
    private var mapReady = false

    /** The app's colours, restated to the page whenever the theme changes. */
    @Volatile
    private var theme: PickerTheme = DEFAULT_THEME

    private val fixLock = Mutex()

    @Volatile
    private var fix: GeoPoint? = null

    @Volatile
    private var fixAt = 0L

    override suspend fun pick(initial: PickedLocation?, title: String, near: PickerStart?): PickedLocation? =
        suspendCancellableCoroutine { continuation ->
            // Only one dialog can be on screen, so a second ask replaces the
            // first rather than queueing behind it — the first caller's field
            // is no longer the one in front of the user.
            val displaced = synchronized(pendingLock) {
                pending.also { pending = continuation }
            }
            displaced?.takeIf { it.isActive }?.resume(null)

            seed = initial
            nearby = near
            _picked.value = initial
            _failure.value = null
            _hint.value = null
            _request.value = Request(title = title, initial = initial)
            // The field's composition can leave while the dialog is up (its
            // form closed underneath it); the dialog must not outlive it.
            continuation.invokeOnCancellation { withdraw() }
            scope.launch { open() }
        }

    /**
     * The viewing half: the same dialog over the same browser, with the pin
     * already dropped and nothing to choose.
     *
     * It goes through the one pending continuation like a [pick] does, so a
     * viewer and a picker displace each other properly rather than fighting
     * over one dialog — and the resumed value is simply thrown away here.
     */
    override suspend fun view(place: PickedLocation, title: String) {
        // Typed explicitly: it shares the one `pending` slot with pick(), and
        // the value it resumes with is the one nobody here reads.
        suspendCancellableCoroutine<PickedLocation?> { continuation ->
            val displaced = synchronized(pendingLock) {
                pending.also { pending = continuation }
            }
            displaced?.takeIf { it.isActive }?.resume(null)

            seed = place
            nearby = null
            _picked.value = place
            _failure.value = null
            _hint.value = null
            _request.value = Request(title = title, initial = place, readOnly = true)
            continuation.invokeOnCancellation { withdraw() }
            scope.launch { open() }
        }
    }

    /** Hands back the current pick and closes, remembering where it was. */
    fun confirm() {
        _picked.value?.let { place ->
            scope.launch { runCatching { rememberLastCenter("${place.lat},${place.lng}") } }
        }
        finish(_picked.value)
    }

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
        _hint.value = null
        _startLocating.value = false
        seed = null
        nearby = null
        browser?.let { target -> run(target, LocationPickerWire.resetScript) }
    }

    /** Brings the picker up, and seeds it if the page is already live. */
    private suspend fun open() {
        if (!prepare()) return
        if (mapReady) replaySeed()
        if (seed == null) startWithoutAPin()
    }

    /**
     * Where a picker opened on nothing starts, in the order the web settled
     * on (Oct 2026) — and never on a fixed city, which is what it used to do
     * while the first fix was pending:
     *
     *  1. a pin the caller already knows is nearby (the `near` argument);
     *  2. the last place this device picked;
     *  3. a coarse position fix, over a world view, saying so while it waits.
     *
     * A pick made while the lookup is in flight wins: they asked for a
     * picker, not for a fix.
     */
    private suspend fun startWithoutAPin() {
        nearby?.let { place ->
            val target = browser ?: return
            run(target, LocationPickerWire.centerScript(place.lat, place.lng, WIDE_ZOOM))
            return
        }
        readLastCenter()?.let { place ->
            val target = browser ?: return
            run(target, LocationPickerWire.centerScript(place.lat, place.lng, WIDE_ZOOM))
            return
        }
        startOnHere()
    }

    /** The remembered centre, or null when there is none or it will not parse. */
    private suspend fun readLastCenter(): GeoPoint? {
        val parts = runCatching { lastCenter() }.getOrNull()?.split(',')?.takeIf { it.size == 2 } ?: return null
        val lat = parts[0].trim().toDoubleOrNull()
        val lng = parts[1].trim().toDoubleOrNull()
        return if (lat == null || lng == null) null else GeoPoint(lat, lng)
    }

    /**
     * "My Location" — the pin on this machine's position, with its address read
     * back off it (`handleMyLocation` in PlacePicker.jsx:163-175).
     *
     * The failure is said out loud here, unlike [startOnHere]: someone who
     * pressed a button is owed an answer, and silence read as a dead button.
     */
    fun useMyLocation() {
        if (_locating.value) return
        _hint.value = null
        scope.launch {
            _locating.value = true
            val point = try {
                fix()
            } finally {
                _locating.value = false
            }
            if (point == null) {
                _hint.value = str(S.desktop_map_no_current_location)
                return@launch
            }
            val target = browser ?: return@launch
            run(target, LocationPickerWire.hereScript(point.lat, point.lng, PLACE_ZOOM))
        }
    }

    /**
     * A picker opened on nothing opens **on** the user: the pin is dropped
     * where they are and its address read back, so the dialog comes up with a
     * usable pick rather than an empty map over a city nobody chose.
     *
     * This goes further than the web, deliberately (asked for on 2026-10-13).
     * `PlacePicker.jsx:94-98` and transportation's `LocationPicker.jsx:87-90`
     * only *centre* on the user's position and leave the field empty, which on
     * the desktop meant "Use this location" stayed disabled over a map already
     * showing the right place.
     *
     * Two things it will not do. It stays at [WIDE_ZOOM], because the position
     * is a network lookup and street-level zoom would dress a city-level fix up
     * as an address. And it never overrules the user: a pick made while the
     * lookup was in flight wins, as the web's own `if (coords) return` guard
     * has it. Silence on failure is deliberate too — they asked for a picker,
     * not for a fix.
     */
    private suspend fun startOnHere() {
        _startLocating.value = true
        val point = try {
            fix()
        } finally {
            _startLocating.value = false
        }
        if (point == null) return
        if (_request.value == null || _picked.value != null) return
        val target = browser ?: return
        run(target, LocationPickerWire.hereScript(point.lat, point.lng, WIDE_ZOOM))
    }

    /**
     * The position, from at most one lookup every [FIX_MAX_AGE_MS].
     *
     * Both outcomes are cached, as the web's `useUserLocation` caches its
     * `null`: the common failure is a key that cannot do geolocation at all,
     * and asking again on every opening buys a slower picker and nothing else.
     * The lock keeps two openings in the same minute to one call.
     */
    private suspend fun fix(): GeoPoint? = fixLock.withLock {
        val now = System.currentTimeMillis()
        if (fixAt != 0L && now - fixAt < FIX_MAX_AGE_MS) return@withLock fix
        fix = locate()
        fixAt = now
        fix
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
            _failure.value = str(S.desktop_map_page_not_prepared)
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
            str(S.desktop_no_embedded_browser_map_picker)
        is KcefRuntime.Failure.Broken ->
            str(S.desktop_embedded_browser_failed, reason.reason)
        null -> str(S.desktop_browser_unavailable)
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
                _failure.value = str(S.desktop_no_maps_key)
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
        // Stated every time, both ways round: one browser serves pickers and
        // viewers in turn, and it keeps whatever the last opening set.
        val readOnly = _request.value?.readOnly == true
        run(target, LocationPickerWire.viewOnlyScript(readOnly))
        if (!readOnly) run(target, LocationPickerWire.searchTextScript(place.address))
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
        val dir = File(ZillitVariant.dataDir, "mapengine").apply { mkdirs() }
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

        /** And its zoom with no place yet — a city, not a street (PlacePicker.jsx:322). */
        const val WIDE_ZOOM = 11

        /** `maximumAge` on the web's own position lookups. */
        const val FIX_MAX_AGE_MS = 5 * 60 * 1000L

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
