package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering

/**
 * Opens a Drive document in the app's own embedded Chromium.
 *
 * ## Why not the system browser
 *
 * The editor URL carries a **WOPI session token** — signed, scoped to one file
 * and one user, valid for eight hours (FR-09.3). Handing that URL to
 * `Desktop.browse` puts it in another application's history, its sync, and its
 * crash reports; anyone who later reads that history holds a working key to
 * that document for the rest of the day. Keeping the page inside the app keeps
 * the token inside the app.
 *
 * ## Why a Swing frame rather than a Compose window
 *
 * JCEF's browser *is* an AWT component, and windowed rendering only creates the
 * native browser once that component has a realised peer. A `JFrame` gives it
 * one directly. Hosting it inside Compose would mean a `SwingPanel` inside a
 * Compose window inside the same AWT hierarchy — more layers for no gain, and
 * the call engine already takes the direct route for the same reason.
 *
 * ## Every window is disposed
 *
 * A displayable AWT window keeps the JVM alive by itself, so an editor left
 * behind is an app that will not quit — the exact symptom the call engine's
 * parking window caused. Each frame disposes its browser on close, and
 * [closeAll] takes the rest at shutdown.
 */
internal object DocumentEditorWindow {

    private val open = mutableListOf<JFrame>()

    /**
     * Opens [url] in a window titled for [fileName].
     *
     * [onUnavailable] is called instead when this runtime has no usable
     * Chromium. It is **not** given the URL: falling back to the system browser
     * there would quietly reinstate the token leak this exists to prevent, so
     * the caller's job is to say the editor cannot open, not to open it
     * elsewhere.
     */
    fun open(
        url: String,
        fileName: String,
        scope: CoroutineScope,
        onUnavailable: (reason: String) -> Unit,
    ) {
        scope.launch {
            val client = KcefRuntime.client()
            if (client == null) {
                val reason = when (val failure = KcefRuntime.failure) {
                    KcefRuntime.Failure.NoJcefRuntime ->
                        "This build has no embedded browser, so documents cannot be edited here."

                    is KcefRuntime.Failure.Broken ->
                        "The embedded browser could not start (${failure.reason})."

                    null -> "The embedded browser is unavailable."
                }
                ZillitLog.w(TAG) { "editor unavailable: $reason" }
                onUnavailable(reason)
                return@launch
            }
            withContext(Dispatchers.Main) { show(client, url, fileName) }
        }
    }

    /**
     * Builds the frame and the browser, on the EDT.
     *
     * Order matters, and it is the same order the call engine had to find: the
     * component goes into a frame, the frame is shown, and only then is it
     * sized. JCEF creates the native browser off the hierarchy-bounds events
     * its component receives, and a frame sized before it had a child sends
     * none of them — the window comes up blank and stays blank.
     */
    private fun show(client: CefClient, url: String, fileName: String) {
        // Load failures and page console errors land in the app log. Without
        // them a Collabora page that 404s or throws is indistinguishable from
        // one that is simply slow, because none of it reaches Kotlin.
        client.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        client.addDisplayHandler(
            KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } },
        )

        val browser: CefBrowser = client.createBrowser(url, CefRendering.DEFAULT, false)
        val frame = JFrame("$fileName — Zillit")
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.contentPane.add(browser.uiComponent)
        frame.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        frame.isVisible = true
        frame.setSize(WIDTH, HEIGHT)
        frame.setLocationRelativeTo(null)
        frame.validate()

        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) {
                    dispose(frame, browser, client)
                }
            },
        )

        open += frame
        // The URL is never logged — it carries the session token.
        ZillitLog.i(TAG) { "editor open for $fileName" }
    }

    private fun dispose(frame: JFrame, browser: CefBrowser, client: CefClient) {
        open -= frame
        // Browser before client before frame: disposing the client out from
        // under a live browser is how JCEF gets asked to render into something
        // that is already gone.
        runCatching { browser.close(true) }
        runCatching { client.dispose() }
        frame.dispose()
        ZillitLog.i(TAG) { "editor closed" }
    }

    /**
     * Closes every editor still open.
     *
     * Called from [Shutdown] for the same reason the call engine's holder is:
     * an undisposed frame keeps the process alive after the main window has
     * gone, which presents as an app that ignores ⌘Q.
     */
    fun closeAll() {
        if (open.isEmpty()) return
        val frames = open.toList()
        open.clear()
        runCatching {
            SwingUtilities.invokeLater { frames.forEach(JFrame::dispose) }
        }
    }

    private const val TAG = "DriveEditor"
    private const val WIDTH = 1280
    private const val HEIGHT = 860
    private const val MIN_WIDTH = 640
    private const val MIN_HEIGHT = 480
}
