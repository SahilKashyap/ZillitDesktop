package com.zillit.desktop

import com.sun.jna.NativeLibrary
import com.zillit.desktop.core.common.ZillitLog

/**
 * Screen Recording, as macOS holds it for this process.
 *
 * Asked in-process because the permission is judged per process: the same
 * question put through `zillit-notify` — the helper that asks for the camera
 * and microphone on the app's behalf — always came back "denied", and a
 * check built on it refused every share on every line with the permission
 * already on (2026-09-23). This is the app's own identity, the one the toggle
 * in System Settings names, and the process Chromium captures in.
 *
 * Null wherever it cannot be asked (not macOS, or CoreGraphics without the
 * call), which callers treat as "unknown", never as "denied".
 */
internal object ScreenCapturePermission {

    private val coreGraphics: NativeLibrary? by lazy {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return@lazy null
        runCatching { NativeLibrary.getInstance("CoreGraphics") }
            .onFailure { ZillitLog.w(TAG) { "CoreGraphics unavailable: $it" } }
            .getOrNull()
    }

    /** Whether this process may capture the screen; asks nothing. */
    fun isGranted(): Boolean? = call("CGPreflightScreenCaptureAccess")

    /**
     * Asks for it: the system prompt the first time, only an answer after
     * that. A grant takes effect when the app next starts.
     */
    fun request(): Boolean? = call("CGRequestScreenCaptureAccess")

    private fun call(name: String): Boolean? = runCatching {
        // A C `bool` fills only the low byte of the return register.
        coreGraphics?.getFunction(name)?.invokeInt(emptyArray())?.let { it and BOOL_MASK != 0 }
    }.onFailure { ZillitLog.w(TAG) { "$name failed: $it" } }
        .getOrNull()
        .also { answer -> ZillitLog.i(TAG) { "$name -> $answer" } }

    private const val TAG = "ScreenCapture"
    private const val BOOL_MASK = 0xFF
}
