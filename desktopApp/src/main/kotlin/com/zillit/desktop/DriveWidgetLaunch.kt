package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How a "Zillit Drive" shortcut reaches the widget.
 *
 * `Zillit --drive-widget` opens the app with the widget up. When Zillit is
 * already running, the second copy cannot start (see SingleInstance) — so it
 * leaves a marker file and exits, and the running copy, which watches for
 * it, opens the widget. A file rather than a socket: no firewall prompt, and
 * a stale marker is harmless (it is consumed on the next poll).
 *
 * Shortcuts:
 *  - macOS: `open zillit://drive-widget` — the bundle registers the `zillit`
 *    URL scheme, and macOS hands the URL to the running copy (or launches
 *    one). `open -a Zillit --args …` does NOT reach a running app: macOS
 *    only activates it, so the flag is for a cold start there.
 *  - Windows: a shortcut to `Zillit.exe` with `--drive-widget` as its
 *    argument — a second process starts, finds the lock held, and signals.
 */
object DriveWidgetLaunch {

    const val FLAG = "--drive-widget"

    /** `zillit://drive-widget`. */
    const val URI_HOST = "drive-widget"

    /**
     * Listens for `zillit://drive-widget` (macOS delivers it to the running
     * app through AWT). Installed once, before the windows: a URL that opened
     * the app arrives moments after launch and must find a handler waiting.
     */
    fun installUriHandler() {
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event ->
                    if (event.uri.host.equals(URI_HOST, ignoreCase = true) ||
                        event.uri.path.trim('/').equals(URI_HOST, ignoreCase = true)
                    ) {
                        signalRunningApp()
                    }
                }
            }
        }.onFailure { ZillitLog.d(TAG) { "no URI handler on this platform: $it" } }
    }

    fun requestedBy(args: Array<String>): Boolean = args.any { it.equals(FLAG, ignoreCase = true) }

    /** From the copy that could not start: asks the running one to open the widget. */
    fun signalRunningApp(directory: File = defaultDirectory()) {
        runCatching {
            directory.mkdirs()
            File(directory, MARKER).writeText("open")
        }.onFailure { System.err.println("[$TAG] could not signal the running app: $it") }
    }

    /** In the running app: calls [onOpen] each time a marker appears. Never returns. */
    suspend fun watch(directory: File = defaultDirectory(), onOpen: () -> Unit) {
        val marker = File(directory, MARKER)
        while (true) {
            val seen = withContext(Dispatchers.IO) { marker.exists() && marker.delete() }
            if (seen) {
                ZillitLog.i(TAG) { "drive widget requested by a second launch" }
                onOpen()
            }
            delay(POLL_MILLIS)
        }
    }

    private fun defaultDirectory() = File(System.getProperty("user.home"), ".zillit")

    private const val MARKER = "open-drive-widget"
    private const val POLL_MILLIS = 1_000L
    private const val TAG = "DriveWidgetLaunch"
}
