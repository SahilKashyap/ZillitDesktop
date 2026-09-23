package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.datastore.WidgetKeys
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The widgets a shortcut can ask for, and the three names each one answers to.
 *
 * One entry per widget rather than three copies of the same object: the
 * launch path is identical for all of them, and a fourth widget should be a
 * line here, not a file.
 */
internal enum class ZillitWidget(
    /** `Zillit --chat-widget` — a cold start, and Windows shortcuts. */
    val flag: String,
    /** `zillit://chat-widget` — how macOS reaches a copy already running. */
    val uriHost: String,
    /** The file a second copy leaves for the running one. */
    val marker: String,
    /** The catalogue key for what the tray and Settings call it. */
    private val labelKey: String,
    /** Where its window's size, place and open state are kept. */
    val keys: WidgetKeys,
) {
    Drive("--drive-widget", "drive-widget", "open-drive-widget", S.txt_drive, ZillitPreferences.DriveWidget),
    Chat("--chat-widget", "chat-widget", "open-chat-widget", S.chat, ZillitPreferences.ChatWidget),
    Crew("--crew-widget", "crew-widget", "open-crew-widget", S.crew, ZillitPreferences.CrewWidget),
    ;

    /** What the tray and Settings call it. */
    val label: String get() = str(labelKey)
}

/**
 * How a "Zillit Drive" / "Zillit Chat" / "Zillit Crew" shortcut reaches its
 * widget.
 *
 * `Zillit --chat-widget` opens the app with that widget up. When Zillit is
 * already running, the second copy cannot start (see SingleInstance) — so it
 * leaves a marker file and exits, and the running copy, which watches for
 * them, opens the widget. A file rather than a socket: no firewall prompt,
 * and a stale marker is harmless (it is consumed on the next poll).
 *
 * Shortcuts:
 *  - macOS: `open zillit://chat-widget` — the bundle registers the `zillit`
 *    URL scheme, and macOS hands the URL to the running copy (or launches
 *    one). `open -a Zillit --args …` does NOT reach a running app: macOS
 *    only activates it, so the flag is for a cold start there.
 *  - Windows: a shortcut to `Zillit.exe` with `--chat-widget` as its
 *    argument — a second process starts, finds the lock held, and signals.
 */
internal object WidgetLaunch {

    /**
     * Listens for `zillit://<widget>` (macOS delivers it to the running app
     * through AWT). Installed once, before the windows: a URL that opened the
     * app arrives moments after launch and must find a handler waiting.
     */
    fun installUriHandler() {
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event ->
                    val asked = ZillitWidget.entries.firstOrNull { widget ->
                        event.uri.host.equals(widget.uriHost, ignoreCase = true) ||
                            event.uri.path.trim('/').equals(widget.uriHost, ignoreCase = true)
                    }
                    if (asked != null) signalRunningApp(asked)
                }
            }
        }.onFailure { ZillitLog.d(TAG) { "no URI handler on this platform: $it" } }
    }

    /** The widget named on the command line, if any. */
    fun requestedBy(args: Array<String>): ZillitWidget? = ZillitWidget.entries.firstOrNull { widget ->
        args.any { it.equals(widget.flag, ignoreCase = true) }
    }

    /** From the copy that could not start: asks the running one to open [widget]. */
    fun signalRunningApp(widget: ZillitWidget, directory: File = defaultDirectory()) {
        runCatching {
            directory.mkdirs()
            File(directory, widget.marker).writeText("open")
        }.onFailure { System.err.println("[$TAG] could not signal the running app: $it") }
    }

    /** In the running app: calls [onOpen] each time a marker appears. Never returns. */
    suspend fun watch(directory: File = defaultDirectory(), onOpen: (ZillitWidget) -> Unit) {
        while (true) {
            for (widget in ZillitWidget.entries) {
                val marker = File(directory, widget.marker)
                val seen = withContext(Dispatchers.IO) { marker.exists() && marker.delete() }
                if (seen) {
                    ZillitLog.i(TAG) { "${widget.name} widget requested by a second launch" }
                    onOpen(widget)
                }
            }
            delay(POLL_MILLIS)
        }
    }

    private fun defaultDirectory() = File(System.getProperty("user.home"), ".zillit")

    private const val POLL_MILLIS = 1_000L
    private const val TAG = "WidgetLaunch"
}
