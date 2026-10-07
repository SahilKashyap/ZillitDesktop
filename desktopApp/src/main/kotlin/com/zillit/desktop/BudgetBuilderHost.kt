package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.network.tokenauth.TokenScope
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.budgetbuilder.server.BudgetBuilderGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import kotlin.concurrent.Volatile

/**
 * Budget Builder, inside the app's own window.
 *
 * The tool is a complete web application hosted rather than reimplemented —
 * the same decision the web client made, for the same reason. The pieces: a
 * [BudgetBuilderGateway] makes the page and its API one loopback origin, and
 * this host is a Chromium surface onto it, lent to the tool's pane.
 *
 * ## No window of its own
 *
 * It used to open a `JFrame`. The web embeds it in the shell — a maximized
 * tool, full-bleed, with the application's own "← Film Tools" button as the
 * way out — and this follows, because a tool that leaves the app to be used
 * is a tool that loses the tab strip, the project it belongs to, and every
 * other window the user had arranged.
 *
 * Shape copied from [KcefMapEngine], which solves the same problem: the
 * browser renders into a heavyweight AWT component, and a component with no
 * parent at all stops being a browser. So it lives for the process, parked in
 * an invisible holder window between openings of the tool, and is lent to the
 * pane while it is on screen. Reopening the tool is then instant *and* lands
 * on the same budget, with the same unsaved edits, as leaving it.
 *
 * ## One copy, one production
 *
 * One browser, so there is never a second copy of the application autosaving
 * over the first — a data race the server does not referee; last write wins
 * silently. The production is pinned when the page loads, as the web pins
 * `project_id` at mount, and a switch reloads the page rather than letting
 * this budget's saves be signed for another film.
 *
 * ## The gateway outlives every pane
 *
 * Deliberate: the application flushes an unsaved budget on `pagehide`. Since
 * the browser is parked rather than destroyed, that fires only at process
 * exit — so the gateway stops at [Shutdown], when nothing can still be saving.
 */
class BudgetBuilderHost(
    private val ready: AppGraph.Ready,
    private val scope: CoroutineScope,
) {

    private val _surface = MutableStateFlow<Component?>(null)

    /**
     * The application's AWT surface once it exists. A flow rather than a
     * getter because the tool's pane composes the moment the window opens,
     * which can be seconds before Chromium has finished coming up.
     */
    val surface: StateFlow<Component?> = _surface.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the application is not there, when it is not there. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _exits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The application asked to leave the tool — its own `zillit:exit`. */
    val exits: Flow<Unit> = _exits.asSharedFlow()

    private val initLock = Mutex()
    private var gateway: BudgetBuilderGateway? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var holder: JWindow? = null

    /**
     * The production the loaded page belongs to.
     *
     * The web pins `project_id` at mount so a switch elsewhere cannot start
     * signing this frame's saves for the wrong film; the frame is torn down
     * and remounted on a real switch anyway. Here the browser survives, so
     * the pin is what notices.
     */
    @Volatile
    private var pinnedProject: String? = null

    /**
     * The last Bearer handed to the page.
     *
     * Kept because a `reauth` ask means "the server refused what I have":
     * recovery needs the refused string to tell a token another call already
     * renewed from one that must be re-minted.
     */
    @Volatile
    private var lastBearer: String? = null

    /** Brings the application up. Idempotent; called each time the pane composes. */
    fun open() {
        scope.launch { prepare() }
    }

    private suspend fun prepare() = initLock.withLock {
        if (browser != null) {
            followProjectSwitch()
            return@withLock
        }

        val services = ready.config.services
        val web = services[ZillitService.BudgetBuilderWeb]
        val api = services[ZillitService.BudgetBuilder]
        if (web == null || api == null) {
            // The screen already says this from its own state; this is for the
            // case where configuration changed under an open window.
            _failure.value = str(S.desktop_budget_builder_not_configured)
            return@withLock
        }

        KcefRuntime.start()
        val cefClient = client ?: KcefRuntime.client()?.also { client = it }
        if (cefClient == null) {
            ZillitLog.w(TAG) { "budget builder unavailable (${KcefRuntime.failure})" }
            _failure.value = unavailableReason()
            return@withLock
        }

        val url = withContext(Dispatchers.IO) { runCatching { gatewayFor(web, api).start() } }
            .onFailure { thrown -> ZillitLog.w(TAG) { "gateway failed to start: ${thrown.message}" } }
            .getOrNull()
        if (url == null) {
            _failure.value = str(S.desktop_budget_builder_gateway_failed)
            return@withLock
        }

        // On the EDT: this builds AWT components, and JCEF is unforgiving
        // about being driven from anywhere else.
        withContext(Dispatchers.Swing) {
            runCatching { buildBrowser(cefClient, url) }.onFailure { thrown ->
                val reason = thrown.message ?: thrown::class.simpleName ?: "unknown"
                ZillitLog.w(TAG) { "browser build failed: $reason" }
                _failure.value = str(S.desktop_embedded_browser_failed, reason)
            }
        }
    }

    /**
     * Reloads the page when the open production is no longer the pinned one.
     *
     * The workspace closes tool windows on a project switch, so in practice
     * this is the reopen after one. The page must re-run its handshake for
     * the new production rather than show the previous film's budget.
     */
    private fun followProjectSwitch() {
        val current = activeProject()
        if (current == null || current == pinnedProject) return
        val target = browser ?: return
        val url = gateway?.start() ?: return
        ZillitLog.i(TAG) { "production changed; reloading budget builder" }
        pinnedProject = current
        lastBearer = null
        SwingUtilities.invokeLater { runCatching { target.loadURL(url) } }
    }

    private fun unavailableReason(): String = when (val failure = KcefRuntime.failure) {
        KcefRuntime.Failure.NoJcefRuntime -> str(S.desktop_no_embedded_browser_budget_builder)
        is KcefRuntime.Failure.Broken -> str(S.desktop_embedded_browser_failed, failure.reason)
        null -> str(S.desktop_browser_unavailable)
    }

    private fun gatewayFor(web: String, api: String): BudgetBuilderGateway =
        gateway ?: BudgetBuilderGateway(
            pageUpstream = web,
            apiUpstream = api,
            moduledata = ::mintModuledata,
            bearer = ::mintBearer,
            // Off the gateway's worker thread at once: the page is waiting on
            // this response, and closing a window is the UI thread's business.
            onExit = { _exits.tryEmit(Unit) },
        ).also { gateway = it }

    /**
     * The legacy encrypted blob, for a cached older copy of the page.
     *
     * On a gateway worker thread, far from the UI; the handshake waits on this
     * and nothing waits on the handshake.
     */
    @Suppress("ForbiddenMethodCall")
    private fun mintModuledata(): String? = runBlocking {
        runCatching {
            ready.headerProvider
                .headersFor(RequestModule.ProjectUser, bodyJson = null, projectId = null)[ZillitHeaders.MODULE_DATA]
        }.getOrNull()
    }

    /**
     * A project Bearer for the pinned production, the credential the page
     * prefers and the one the backend is keeping.
     *
     * [reauth] is the page's post-401 ask. Answering it with the cached token
     * would hand back the very string the server just refused, so it goes
     * through recovery instead — which returns a token another call has
     * already renewed, or mints a fresh one.
     */
    @Suppress("ForbiddenMethodCall")
    private fun mintBearer(reauth: Boolean): String? {
        // Pinned here as well as at load, for the case the page beat the
        // production into existence: opening the tool straight after sign-in
        // builds the browser before `project/context` has answered, so the
        // first credential is what fixes which film this budget belongs to.
        val projectId = pinnedProject ?: activeProject()?.also { pinnedProject = it } ?: return null
        val tokenScope = TokenScope.Project(projectId)
        val failed = lastBearer
        val minted = runBlocking {
            runCatching {
                if (reauth && failed != null) {
                    ready.tokenSession.recoverFromUnauthorized(tokenScope, failed)
                } else {
                    ready.tokenSession.bearerTokenFor(tokenScope)
                }
            }.getOrNull()
        }
        return minted?.also { lastBearer = it }
    }

    private fun activeProject(): String? =
        ready.projectContext?.context?.value?.project?.projectId?.takeIf { it.isNotBlank() }

    private fun buildBrowser(cefClient: CefClient, url: String) {
        if (browser != null) return
        cefClient.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        cefClient.addDisplayHandler(
            KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } },
        )
        pinnedProject = activeProject()
        browser = cefClient.createBrowser(url, CefRendering.DEFAULT, false)
        browser?.uiComponent?.let { component ->
            hold(component)
            _failure.value = null
            _surface.value = component
        }
        ZillitLog.i(TAG) { "budget builder loading" }
    }

    /**
     * Parks the browser's component in a window nobody looks at — the map
     * engine's trick, for the same reason: JCEF creates the native browser
     * from a realised AWT peer, and a component with no parent at all stops
     * being a browser. The window is invisible (macOS clamps offscreen
     * windows back onto the desktop) and lent to the tool's pane while open.
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
     * Who owns the component right now — see [SurfaceClaims] for the crash
     * this prevents.
     */
    private val surfaceClaim = SurfaceClaims()

    /**
     * Lends the component to a pane, for as long as that pane lives.
     *
     * Parking is destructive: `Container.addImpl` removes a component from its
     * current parent first, so a leaving pane that parks unconditionally rips
     * the browser out of the pane that now legitimately owns it. That happens
     * for real here — detaching the tool into its own OS window is one host
     * handing the surface to another, with both alive for a frame. So each
     * mount takes a ticket and only the newest ticket may park; the test runs
     * inside the deferred block, because the question is who owns the surface
     * once the frame has settled.
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

    /**
     * Stops the loopback gateway — **last**, after Chromium has gone.
     *
     * Separate from [releaseHolder] for one reason: the application flushes an
     * unsaved budget on `pagehide`, which fires while the browser is being
     * torn down. A gateway stopped first is a gateway that is not there for
     * exactly that final save, so [Shutdown] calls this after
     * [KcefRuntime.stop].
     */
    fun stopGateway() {
        gateway?.stop()
        gateway = null
    }

    private companion object {
        const val TAG = "BudgetBuilder"

        /** Far enough out that no arrangement of displays reaches it. */
        const val HOLDER_OFFSCREEN = -8_000

        /** Big enough that AWT treats the window as real. */
        const val HOLDER_SIZE = 640
    }
}
