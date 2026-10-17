package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitVariant
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

    private fun defaultDirectory() = ZillitVariant.dataDir

    private const val POLL_MILLIS = 1_000L
    private const val TAG = "WidgetLaunch"
}

/**
 * Launching Zillit when Zillit is already running: show the window.
 *
 * Closing the window puts Zillit in the tray rather than quitting it
 * (`CloseToTray`, on by default), so "it is already running" and "you can see
 * it" are different things. Double-clicking the icon then used to produce a
 * dialog saying to switch to the window that is already open — advice nobody
 * could take, because there was no window, only a tray icon they had not
 * noticed. That is the dead end this removes: the second copy asks the
 * running one to show itself, which is what the person meant by launching it.
 *
 * macOS reaches a running app through [DockReopen] instead; it gets a reopen
 * event and never starts a second process at all. Windows and Linux do start
 * one, so they need this.
 *
 * The same marker-file mechanism as [WidgetLaunch], for the same reasons — no
 * firewall prompt, and a stale marker is consumed harmlessly on the next poll.
 * [ask] waits to see its marker taken, so a copy that is *not* watching for
 * one — anything built before this existed — still gets the old dialog rather
 * than exiting silently and looking broken.
 */
internal object MainWindowRequest {

    /**
     * From the copy that could not start. True when the running copy took the
     * request, which means the window is coming up and nothing more is owed to
     * the person; false when nobody answered, and the caller should say so.
     */
    fun ask(directory: File = ZillitVariant.dataDir): Boolean {
        val marker = File(directory, MARKER)
        val written = runCatching {
            directory.mkdirs()
            marker.writeText("show")
        }.isFailure.not()
        if (!written) {
            System.err.println("[$TAG] could not signal the running app")
            return false
        }
        val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (!marker.exists()) return true
            Thread.sleep(ACK_POLL_MILLIS)
        }
        // Nobody is watching. Clear it rather than leave a request that a
        // later, newer copy would act on at a moment nobody asked for.
        runCatching { marker.delete() }
        return false
    }

    /** In the running app: true once, for each request left behind. */
    fun consume(directory: File): Boolean = File(directory, MARKER).let { it.exists() && it.delete() }

    /**
     * A request taken off the disk but not yet acted on. See [startAcknowledging].
     */
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * In the running app: starts taking requests **now**, long before there is
     * a window to show.
     *
     * ## The dead end this removes
     *
     * [ask] waits [ACK_TIMEOUT_MILLIS] to see its marker consumed and shows the
     * "already running" dialog if nobody takes it. Until this existed the only
     * consumer was [watch], which runs from a `LaunchedEffect` inside the
     * composition — so it starts *after* `AppGraph.build()` has finished wiring
     * the application. A launch during those seconds was therefore answered by
     * nobody, and the person who double-clicked a slow-starting app was told to
     * switch to a window that did not exist yet. The copy they had just started
     * was coming up perfectly well the whole time.
     *
     * So the acknowledgement is split from the raising. A daemon thread takes
     * the request the moment it lands — which is all [ask] is waiting for, and
     * all that is needed to keep the dialog away — and [watch] raises the
     * window once there is one. The person sees the splash, then the window,
     * and never the dialog.
     *
     * A thread rather than a coroutine because this runs before the application
     * has a scope, and daemon so it can never hold the process open. Polling
     * faster than [watch] did on purpose: [ask]'s patience is the budget, and
     * spending a tenth of it on one `exists()` is the difference between a
     * second launch feeling answered and feeling ignored.
     */
    fun startAcknowledging(directory: File = ZillitVariant.dataDir) {
        Thread {
            while (true) {
                if (runCatching { consume(directory) }.getOrDefault(false)) {
                    reportTaken()
                    pending.set(true)
                }
                Thread.sleep(ACK_POLL_MILLIS)
            }
        }.apply {
            isDaemon = true
            name = "zillit-main-window-requests"
        }.start()
    }

    /**
     * In the running app: calls [onShow] for each request [startAcknowledging]
     * has taken. Never returns.
     *
     * Reads a flag rather than the directory because the thread above owns the
     * marker file — two consumers would mean a request raising the window twice
     * or not at all. [onShow] touches Compose state, which is why it waits for
     * the composition instead of being called from that thread.
     *
     * Beside [DockReopen.watch] rather than folded into [WidgetLaunch.watch]:
     * the two are the same idea on different platforms — macOS is handed a
     * reopen event, Windows and Linux start a second process that has to leave
     * a note — and reading them side by side at the call site is worth it.
     */
    suspend fun watch(onShow: () -> Unit) {
        raising = true
        while (true) {
            if (pending.getAndSet(false)) onShow()
            delay(RAISE_POLL_MILLIS)
        }
    }

    /** True once [watch] is running, which also means there is a logger. */
    @Volatile
    private var raising = false

    /**
     * Says a request was taken, on whichever channel works at the time.
     *
     * Under `Startup` rather than this object's own tag because production
     * keeps INFO for a short list of tags and that is one of them — and a
     * second launch *is* a launch. The line matters: it is the only evidence in
     * a sent-in log that the person tried to open Zillit again and what the
     * running copy did about it, which is the whole subject here.
     *
     * The stderr copy is the same trick [SingleInstance.report] uses and for
     * the same reason — a request taken on the way up is taken before
     * `ZillitLogging.initialise` has installed a sink, so the `ZillitLog` line
     * goes nowhere at exactly the moment it is most worth having. Once [watch]
     * is running there is a logger, and a second channel would only be noise.
     */
    private fun reportTaken() {
        val message = "a second launch asked for the main window"
        if (!raising) System.err.println("[$TAG] $message")
        runCatching { ZillitLog.i(STARTUP_TAG) { message } }
    }

    private const val MARKER = "show-main-window"

    /**
     * Comfortably longer than [WidgetLaunch]'s one-second poll, so a running
     * copy that is simply between polls is not mistaken for one that is not
     * listening — and short enough that the fallback dialog still feels like a
     * response to the double-click rather than an afterthought.
     */
    private const val ACK_TIMEOUT_MILLIS = 3_000L
    private const val ACK_POLL_MILLIS = 100L

    /**
     * How long a request waits for the window after it has been taken. No file
     * is touched here, so this is a flag read, not a stat.
     */
    private const val RAISE_POLL_MILLIS = 150L
    private const val TAG = "MainWindowRequest"

    /** `ZillitLogging.FIELD_TAGS` keeps this one's INFO in production. */
    private const val STARTUP_TAG = "Startup"
}
