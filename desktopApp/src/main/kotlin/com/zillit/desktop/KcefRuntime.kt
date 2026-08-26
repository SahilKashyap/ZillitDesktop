package com.zillit.desktop

import com.jetbrains.cef.JCefAppConfig
import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cef.CefApp
import org.cef.CefClient
import org.cef.SystemBootstrap

/**
 * The embedded Chromium behind the call media engine, brought up once.
 *
 * **This uses the JetBrains Runtime's own JCEF, not a downloaded one.** The app
 * already runs and ships on JBR, and JBR carries a complete CEF: the framework,
 * the helper apps, and the `org.cef` classes as a platform module. That module
 * sits ahead of the classpath, so a downloaded CEF cannot win even when one is
 * present — its natives and the `org.cef` classes actually in use come from
 * different builds, and the result is a browser object that is constructed,
 * reports no error, and never loads a page. Taking JBR's CEF makes the two
 * halves match by construction, and costs no download at all.
 *
 * Split from [KcefCallEngine] because starting CEF is a process-wide, once-ever
 * concern, while the engine is a per-call object.
 *
 * Nothing here throws. [start] records why it could not finish in [failure],
 * and [client] answers null when Chromium is unusable.
 */
internal object KcefRuntime {

    /** Why Chromium is unusable, for the log and the degraded-call notice. */
    sealed interface Failure {

        /** Not a JBR, or a JBR built without JCEF. Calling has no media here. */
        data object NoJcefRuntime : Failure

        data class Broken(val reason: String) : Failure
    }

    private val startLock = Mutex()
    private var started = false
    private var app: CefApp? = null

    @Volatile
    var failure: Failure? = null
        private set

    /**
     * Starts CEF. Idempotent and safe to call from anywhere: the first caller
     * does the work and the rest wait for it.
     */
    suspend fun start() = startLock.withLock {
        if (started) return@withLock
        started = true
        withContext(Dispatchers.IO) { bootstrap() }
    }

    private fun bootstrap() {
        val config = runCatching { JCefAppConfig.getInstance() }.getOrNull()
        if (config == null) {
            failure = Failure.NoJcefRuntime
            ZillitLog.w(TAG) { "no JCEF in this runtime; calling is signalling-only" }
            return
        }
        runCatching {
            // JBR resolves the framework out of its own bundle; without its
            // loader, CEF's libraries are looked for where they are not.
            SystemBootstrap.setLoader(config.getLoader())
            val args = config.appArgsAsList + MEDIA_ARGS
            check(CefApp.startup(args.toTypedArray())) { "CefApp.startup refused" }
            app = CefApp.getInstance(args.toTypedArray(), config.cefSettings)
            ZillitLog.i(TAG) { "chromium up (jcef ${JCefAppConfig.getVersion()})" }
        }.onFailure { thrown ->
            failure = Failure.Broken(thrown.message ?: thrown::class.simpleName ?: "unknown")
            ZillitLog.w(TAG) { "chromium init failed: ${thrown.message}" }
        }
    }

    /**
     * Shuts Chromium down. Safe to call when it never started, and only once.
     *
     * Called from [Shutdown] before the process ends. Leaving CEF alive into
     * AppKit's terminate sequence is what lets `libjcef.dylib` reach for
     * `CefApp.handleBeforeTerminate`, which this runtime's `CefApp` does not
     * have — and the resulting error on the AppKit thread can end the process
     * before anything else gets to run.
     */
    fun stop() {
        val running = app ?: return
        app = null
        runCatching { running.dispose() }
            .onSuccess { ZillitLog.i(TAG) { "chromium down" } }
            .onFailure { thrown -> ZillitLog.w(TAG) { "chromium teardown: ${thrown.message}" } }
    }

    /** A CEF client, or null when Chromium is unusable. */
    suspend fun client(): CefClient? {
        start()
        return runCatching { app?.createClient() }
            .onFailure { thrown -> ZillitLog.w(TAG) { "no chromium client: ${thrown.message}" } }
            .getOrNull()
    }

    private const val TAG = "KcefRuntime"

    /**
     * Chromium switches the call page needs.
     *
     * Deliberately almost empty. Two switches used to live here and both are
     * gone, because together they were the reason screen sharing never once
     * worked in this app:
     *
     * `--use-fake-ui-for-media-stream` looked like it only auto-answered the
     * camera prompt an embedded browser has nowhere to draw. It does more than
     * that: for a display-capture request it answers in place of the browser's
     * own path and substitutes the placeholder source id `screen:0:0`. Zero is
     * not a `CGDirectDisplayID`, so ScreenCaptureKit matches no display and
     * gives up before building a stream — surfacing in the page as
     * `NotReadableError: Could not start video source`, which reads like a
     * permission failure and is not one. [KcefPage.permissionHandler] answers
     * those requests properly instead.
     *
     * `--auto-select-desktop-capture-source=Entire screen` was inert: the
     * switch's only consumer is Chrome's own picker controller, which this
     * framework does not build. It never selected anything.
     */
    private val MEDIA_ARGS = listOf(
        "--autoplay-policy=no-user-gesture-required",
    )
}
