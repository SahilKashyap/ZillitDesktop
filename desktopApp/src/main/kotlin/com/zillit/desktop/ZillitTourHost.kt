package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.ui.ZILLIT_TOUR_URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * The Zillit Tour's Chromium surface.
 *
 * The tour is a hosted web application — the same page the phones open in
 * their own web views (see `ZillitTourToolProvider`) — so the desktop's half
 * of it is a browser onto a fixed public URL and nothing else: no gateway, no
 * handshake, no credential. It is the smallest of the app's four JCEF hosts
 * and keeps their shape ([KcefMapEngine], [BudgetBuilderHost]), because the
 * constraint is theirs: the browser renders into a heavyweight AWT component,
 * and a component with no parent at all stops being a browser. So it lives
 * for the process, parked in an invisible holder window between openings of
 * the tour, and is lent to the tool's pane while that pane is on screen.
 *
 * Parked rather than reloaded on the way back in, which is where this parts
 * from the phones: they build a fresh web view each time and so always start
 * the tour from the top. Here the pane also remounts when the window is
 * detached or re-docked, and reloading then would throw away the page someone
 * was reading mid-gesture.
 *
 * Nothing here throws. [failure] carries why there is no tour to show, and
 * the pane offers the browser instead.
 */
class ZillitTourHost(private val scope: CoroutineScope) {

    private val _surface = MutableStateFlow<Component?>(null)

    /**
     * The tour's AWT surface once it exists. A flow rather than a getter
     * because the pane composes the moment the window opens, which can be
     * seconds before Chromium has finished coming up.
     */
    val surface: StateFlow<Component?> = _surface.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the tour is not there, when it is not there. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val initLock = Mutex()
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var holder: JWindow? = null
    private val surfaceClaim = SurfaceClaims()

    /** Brings the tour up. Idempotent; called each time the pane composes. */
    fun open() {
        scope.launch { prepare() }
    }

    private suspend fun prepare() = initLock.withLock {
        if (browser != null) return@withLock
        KcefRuntime.start()
        val cefClient = client ?: KcefRuntime.client()?.also { client = it }
        if (cefClient == null) {
            ZillitLog.w(TAG) { "tour unavailable (${KcefRuntime.failure})" }
            _failure.value = unavailableReason()
            return@withLock
        }
        // On the EDT: this builds AWT components, and JCEF is unforgiving
        // about being driven from anywhere else.
        withContext(Dispatchers.Swing) {
            runCatching { buildBrowser(cefClient) }.onFailure { thrown ->
                val reason = thrown.message ?: thrown::class.simpleName ?: "unknown"
                ZillitLog.w(TAG) { "browser build failed: $reason" }
                _failure.value = str(S.desktop_embedded_browser_failed, reason)
            }
        }
    }

    private fun unavailableReason(): String = when (val failure = KcefRuntime.failure) {
        KcefRuntime.Failure.NoJcefRuntime -> str(S.desktop_no_embedded_browser_tour)
        is KcefRuntime.Failure.Broken -> str(S.desktop_embedded_browser_failed, failure.reason)
        null -> str(S.desktop_browser_unavailable)
    }

    private fun buildBrowser(cefClient: CefClient) {
        if (browser != null) return
        cefClient.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        browser = cefClient.createBrowser(ZILLIT_TOUR_URL, CefRendering.DEFAULT, false)
        browser?.uiComponent?.let { component ->
            hold(component)
            _failure.value = null
            _surface.value = component
        }
        ZillitLog.i(TAG) { "tour loading from $ZILLIT_TOUR_URL" }
    }

    /**
     * Parks the browser's component in a window nobody looks at — the map
     * engine's trick, for the reason given in the class comment. The window
     * is invisible (macOS clamps offscreen windows back onto the desktop).
     */
    private fun hold(component: Component) {
        val window = holder ?: JWindow().also { created ->
            created.focusableWindowState = false
            runCatching { created.opacity = 0f }
            holder = created
        }
        window.contentPane.add(component)
        window.isVisible = true
        window.setBounds(HOLDER_OFFSCREEN, HOLDER_OFFSCREEN, HOLDER_SIZE, HOLDER_SIZE)
        window.validate()
        window.toBack()
    }

    /**
     * Lends the component to a pane, for as long as that pane lives.
     *
     * Parking is destructive — see [SurfaceClaims] for the crash this
     * prevents — so each mount takes a ticket and only the newest ticket may
     * park. Detaching the tour into its own OS window is one pane handing the
     * surface to another, with both alive for a frame.
     */
    fun hostSurface(): SurfaceLease {
        val claim = surfaceClaim.claim()
        return SurfaceLease {
            val component = _surface.value ?: return@SurfaceLease
            SwingUtilities.invokeLater {
                if (!surfaceClaim.mayPark(claim)) return@invokeLater
                component.isVisible = true
                hold(component)
            }
        }
    }

    /**
     * Lets go of the parking window. A displayable AWT window keeps the JVM
     * alive on its own; [Shutdown] calls this so the process can end.
     */
    fun releaseHolder() {
        val window = holder ?: return
        holder = null
        runCatching { SwingUtilities.invokeLater(window::dispose) }
    }

    private companion object {
        const val TAG = "ZillitTour"

        /** Far enough out that no arrangement of displays reaches it. */
        const val HOLDER_OFFSCREEN = -8_000

        /** Big enough that AWT treats the window as real. */
        const val HOLDER_SIZE = 640
    }
}
