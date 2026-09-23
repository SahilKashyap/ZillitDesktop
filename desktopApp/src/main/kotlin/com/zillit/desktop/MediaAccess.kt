package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Asks macOS for camera and microphone access as this app, before the page does.
 *
 * Chromium never asks. Chrome's browser process calls
 * `AVCaptureDevice.requestAccess` ahead of a capture; CEF does not, and its
 * capture helper simply opens the device. On macOS 26 a capture opened without
 * the responsible app ever having asked is not refused — the session runs and
 * delivers no frames, so `getUserMedia` succeeds, the track reads live at
 * 1280x720, the camera light stays off and every tile stays black. The request
 * goes through `zillit-notify --camera`, a process whose `Bundle.main` is the
 * app, so the grant is filed under the app's identity.
 *
 * Asked once per launch and remembered: a grant does not change while the app
 * runs, and the system prompt must not reappear at every camera toggle.
 */
object MediaAccess {
    private val lock = Mutex()
    private val answers = mutableMapOf<String, String>()

    /** The camera grant, asking the system if it is still undecided. */
    suspend fun ensureCamera(): String = ensure("--camera")

    /** The microphone grant, asking the system if it is still undecided. */
    suspend fun ensureMicrophone(): String = ensure("--microphone")

    private suspend fun ensure(mode: String): String = lock.withLock {
        answers[mode]?.let { return it }
        val answer = ask(mode)
        ZillitLog.i(TAG) { "${mode.trimStart('-')} access: $answer" }
        // Only a settled answer is worth remembering; "unavailable" is retried.
        if (answer != UNAVAILABLE && answer != "notDetermined") answers[mode] = answer
        answer
    }

    /** Screen Recording as it stood when this process started; null until read. */
    @Volatile
    private var screenAtLaunch: String? = null

    /**
     * Reads — never asks — Screen Recording once, early. A grant made while
     * the app runs does not reach this process until it restarts, and the
     * only way to tell that case from "allowed all along" is to have looked
     * before.
     */
    suspend fun noteScreenAtLaunch() {
        if (screenAtLaunch == null) screenAtLaunch = ask("--screen-status")
    }

    /**
     * Whether this process can capture the screen, asking macOS when it is
     * not allowed. Asked on every share rather than remembered: the person
     * may have gone to System Settings between two attempts.
     *
     * Chromium's desktop capture never asks for Screen Recording; without it
     * the capture fails with "Could not start video source", which the call
     * used to report as the chosen window having closed.
     */
    suspend fun screenAccess(): ScreenAccess {
        val now = lock.withLock { ask("--screen") }
        ZillitLog.i(TAG) { "screen access: $now (at launch: $screenAtLaunch)" }
        return when {
            now == UNAVAILABLE -> ScreenAccess.Unknown
            now != AUTHORIZED -> ScreenAccess.NeedsGrant
            screenAtLaunch != null && screenAtLaunch != AUTHORIZED && screenAtLaunch != UNAVAILABLE ->
                ScreenAccess.NeedsRestart
            else -> ScreenAccess.Ready
        }
    }

    /** One run of the helper in [mode]: its one-word answer, or [UNAVAILABLE]. */
    private suspend fun ask(mode: String): String {
        val helper = TrayNotifier.macNotifyHelper ?: return UNAVAILABLE
        return withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder(helper.absolutePath, mode).redirectErrorStream(false).start()
                val out = process.inputStream.bufferedReader().readText().trim()
                process.errorStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
                    .forEach { line -> ZillitLog.d(TAG) { line } }
                process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                out.ifBlank { UNAVAILABLE }
            }.getOrElse { error ->
                ZillitLog.w(TAG) { "$mode failed: $error" }
                UNAVAILABLE
            }
        }
    }

    /** Where Screen Recording is switched on — opened when a share is refused for it. */
    fun openScreenRecordingSettings() {
        runCatching {
            ProcessBuilder("open", SCREEN_RECORDING_PANE).start()
        }.onFailure { ZillitLog.w(TAG) { "could not open Screen Recording settings: $it" } }
    }

    private const val TAG = "MediaAccess"
    private const val AUTHORIZED = "authorized"
    private const val SCREEN_RECORDING_PANE =
        "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
    private const val TIMEOUT_SECONDS = 130L
    private const val UNAVAILABLE = "unavailable"
}

/** What stands between this process and a screen capture. */
enum class ScreenAccess {
    /** Allowed since before the app started: capture can begin. */
    Ready,

    /** Not allowed: macOS has been asked and System Settings is the way. */
    NeedsGrant,

    /** Allowed only since the app started — macOS applies it after a restart. */
    NeedsRestart,

    /** No helper to ask (not macOS, or not packaged): try, as before. */
    Unknown,
}
