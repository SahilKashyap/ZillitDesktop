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
        val helper = TrayNotifier.macNotifyHelper ?: return UNAVAILABLE
        val answer = withContext(Dispatchers.IO) {
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
        ZillitLog.i(TAG) { "${mode.trimStart('-')} access: $answer" }
        // Only a settled answer is worth remembering; "unavailable" is retried.
        if (answer != UNAVAILABLE && answer != "notDetermined") answers[mode] = answer
        answer
    }

    private const val TAG = "MediaAccess"
    private const val TIMEOUT_SECONDS = 130L
    private const val UNAVAILABLE = "unavailable"
}
