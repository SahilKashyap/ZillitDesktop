package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.WindowGeometry
import com.zillit.desktop.core.datastore.saveWindowGeometry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything that has to happen before this process ends, on every way out.
 *
 * ## Why a hook and not just the close button
 *
 * `onCloseRequest` only fires for the window's own close. On macOS ⌘Q and the
 * Dock's Quit go straight to AppKit's terminate sequence, which Compose never
 * sees — so the app's tidy-up simply did not run, and the window geometry the
 * user had just arranged was thrown away every time they quit the normal way.
 *
 * ## Why Chromium is torn down first
 *
 * The embedded browser is the reason this is delicate rather than tidy. On
 * termination `libjcef.dylib` calls back into Java looking for
 * `org.cef.CefApp.handleBeforeTerminate` — a method the JetBrains Runtime's
 * own `CefApp` does not declare (21.0.11 b1163.116 has `dispose`, `N_Shutdown`
 * and `isTerminated`, and nothing of that name). The lookup fails on the
 * AppKit thread, and a `NoSuchMethodError` there can take the process down
 * before any of the app's own shutdown work has run.
 *
 * Disposing CEF ourselves, first, is what avoids it: by the time AppKit starts
 * terminating there is no live CEF for that callback to be made against. It is
 * also what JCEF asks callers to do regardless.
 */
internal object Shutdown {

    private val done = AtomicBoolean(false)

    @Volatile
    private var store: PreferenceStore? = null

    /** The media engine, so its parking window does not outlive the app. */
    @Volatile
    private var engine: KcefCallEngine? = null

    /**
     * The last known window geometry.
     *
     * Snapshotted as it changes rather than read at exit: `WindowState` is
     * Compose state owned by the UI thread, and a shutdown hook is the wrong
     * thread and often the wrong moment to be reading it.
     */
    @Volatile
    private var geometry: WindowGeometry? = null

    /** Arms the hook. Called once, as early as the preference store exists. */
    fun install(preferences: PreferenceStore) {
        store = preferences
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread(::run, "zillit-shutdown"))
        }.onFailure {
            ZillitLog.w(TAG) { "could not arm the shutdown hook: ${it::class.simpleName}" }
        }
    }

    /** The engine whose browser needs letting go of at the end. */
    fun engine(engine: KcefCallEngine) {
        this.engine = engine
    }

    /** Records where the window is now, for whenever the process ends. */
    fun remember(geometry: WindowGeometry) {
        this.geometry = geometry
    }

    /**
     * Writes the remembered geometry now, on the caller's thread.
     *
     * For callers that are still *alive* — the close button and the tray's
     * Quit. Blocking is safe there and wanted: the process ends on the next
     * line, and a save left in flight is a save that does not happen.
     *
     * Deliberately **not** called from [run]. See the note there.
     */
    fun saveGeometryNow() {
        val preferences = store ?: return
        val snapshot = geometry ?: return
        runCatching {
            @Suppress("ForbiddenMethodCall")
            runBlocking { preferences.saveWindowGeometry(snapshot) }
        }.onFailure {
            ZillitLog.w(TAG) { "could not save window geometry: ${it::class.simpleName}" }
        }
    }

    /**
     * Tidies up, once.
     *
     * Idempotent because both the explicit quit and the hook reach it, and on
     * an ordinary ⌘Q they both will. Nothing here throws: a failure on the way
     * out must not become a crash on the way out.
     *
     * ## Why this no longer saves the window geometry
     *
     * It used to, and it did not work. A shutdown hook runs while the JVM is
     * already tearing down, and the class loader will refuse to load anything
     * it has not loaded already — so `runBlocking` here failed with
     * `NoClassDefFoundError` whenever the coroutine machinery still needed a
     * class, and the geometry was silently dropped. It surfaced as a warning
     * because the failure was caught, which is why nobody noticed.
     *
     * The geometry is written while the app is alive instead: continuously as
     * the window is moved or resized, and once more by [saveGeometryNow] on the
     * way out. By the time anything reaches here it is already saved, whichever
     * way the process is ending — ⌘Q and the Dock's Quit included, which is
     * what this hook was originally written for.
     */
    fun run() {
        if (!done.compareAndSet(false, true)) return
        ZillitLog.i(TAG) { "shutting down" }
        engine?.releaseHolder()
        // Before CEF goes: an open editor frame is a displayable AWT window,
        // and one left behind keeps the JVM alive after the main window has
        // closed — an app that appears to ignore ⌘Q.
        DocumentEditorWindow.closeAll()
        // Also closes the loopback gateway behind the budget window, which is
        // kept alive past the window on purpose — see BudgetBuilderWindow.
        BudgetBuilderWindow.close()
        KcefRuntime.stop()
        // The OS frees this when the process ends, so this is only about being
        // prompt: it shortens the window in which a relaunch races the old
        // process's teardown and is told Zillit is still running.
        SingleInstance.release()
    }

    private const val TAG = "Shutdown"
}
