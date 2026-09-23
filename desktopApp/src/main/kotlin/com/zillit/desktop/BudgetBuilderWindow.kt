package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.budgetbuilder.server.BudgetBuilderGateway
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering

/**
 * Budget Builder, in the app's own embedded Chromium.
 *
 * The tool is a complete web application hosted rather than reimplemented —
 * the same decision the web client made, for the same reason. The pieces:
 * a [BudgetBuilderGateway] makes the page and its API one loopback origin,
 * and this window is a browser onto it, following [DocumentEditorWindow]'s
 * frame-and-dispose shape exactly.
 *
 * ## One window, not many
 *
 * A second open brings the existing window to the front instead of spawning
 * a sibling. Two copies of the application autosaving one budget document
 * over each other is a data race the server does not referee — last write
 * wins silently.
 *
 * ## The gateway outlives the window
 *
 * Deliberate: the application flushes an unsaved budget on `pagehide`, which
 * fires while the browser is being torn down. A gateway stopped the instant
 * the window closes kills exactly that final save. It stops at [Shutdown]
 * instead, when the process is ending anyway.
 */
internal object BudgetBuilderWindow {

    private var gateway: BudgetBuilderGateway? = null
    private var frame: JFrame? = null

    /**
     * Opens the tool, or fronts it if already open.
     *
     * [onUnavailable] is told, in user-fit words, when this build cannot:
     * no embedded Chromium, or an environment with no Budget Builder
     * endpoints configured.
     */
    fun open(
        ready: AppGraph.Ready,
        scope: CoroutineScope,
        onUnavailable: (reason: String) -> Unit,
    ) {
        val existing = frame
        if (existing != null) {
            SwingUtilities.invokeLater {
                existing.isVisible = true
                existing.toFront()
            }
            return
        }

        val services = ready.config.services
        val web = services[ZillitService.BudgetBuilderWeb]
        val api = services[ZillitService.BudgetBuilder]
        if (web == null || api == null) {
            onUnavailable(str(S.desktop_budget_builder_not_configured))
            return
        }

        scope.launch {
            val client = KcefRuntime.client()
            if (client == null) {
                onUnavailable(str(S.desktop_no_embedded_browser_budget_builder))
                return@launch
            }
            val url = runCatching { gatewayFor(ready, web, api).start() }
                .onFailure { thrown ->
                    ZillitLog.w(TAG) { "gateway failed to start: ${thrown.message}" }
                }
                .getOrNull()
            if (url == null) {
                onUnavailable(str(S.desktop_budget_builder_gateway_failed))
                return@launch
            }
            withContext(Dispatchers.Main) { show(client, url) }
        }
    }

    @Synchronized
    private fun gatewayFor(ready: AppGraph.Ready, web: String, api: String): BudgetBuilderGateway =
        gateway ?: BudgetBuilderGateway(
            pageUpstream = web,
            apiUpstream = api,
            moduledata = {
                // On a gateway worker thread, far from the UI; the handshake
                // waits on this blob and nothing waits on the handshake.
                @Suppress("ForbiddenMethodCall")
                runBlocking {
                    ready.headerProvider
                        .headersFor(RequestModule.ProjectUser, bodyJson = null, projectId = null)[ZillitHeaders.MODULE_DATA]
                }
            },
        ).also { gateway = it }

    /** Frame-then-size ordering per [DocumentEditorWindow.show] — see there. */
    private fun show(client: CefClient, url: String) {
        client.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        client.addDisplayHandler(
            KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } },
        )

        val browser: CefBrowser = client.createBrowser(url, CefRendering.DEFAULT, false)
        val window = JFrame(str(S.desktop_budget_builder_window_title))
        window.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        window.contentPane.add(browser.uiComponent)
        window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        window.isVisible = true
        window.setSize(WIDTH, HEIGHT)
        window.setLocationRelativeTo(null)
        window.validate()

        window.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) {
                    frame = null
                    // Browser before client before frame — the disposal order
                    // JCEF tolerates; see DocumentEditorWindow.dispose.
                    runCatching { browser.close(true) }
                    runCatching { client.dispose() }
                    window.dispose()
                    ZillitLog.i(TAG) { "budget builder closed" }
                }
            },
        )

        frame = window
        ZillitLog.i(TAG) { "budget builder open" }
    }

    /** Shutdown's hook: the window first, then the gateway it saved into. */
    fun close() {
        val open = frame
        frame = null
        if (open != null) {
            runCatching { SwingUtilities.invokeLater(open::dispose) }
        }
        gateway?.stop()
        gateway = null
    }

    private const val TAG = "BudgetBuilder"
    private const val WIDTH = 1440
    private const val HEIGHT = 900
    private const val MIN_WIDTH = 900
    private const val MIN_HEIGHT = 600
}
