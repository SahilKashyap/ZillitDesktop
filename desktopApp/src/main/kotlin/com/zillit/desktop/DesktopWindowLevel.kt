package com.zillit.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.zillit.desktop.core.common.ZillitLog
import java.awt.Window
import java.lang.reflect.Proxy

/**
 * Puts a window on the desktop layer — behind every ordinary window, above
 * the wallpaper — the way macOS's own desktop widgets sit.
 *
 * ## macOS
 *
 * AWT has no public API for `NSWindow.level`, and the JetBrains Runtime's
 * `CWrapper.NSWindow.setLevel` only accepts its own three named levels. So
 * the NSWindow pointer is taken from the AWT peer (the app already opens
 * `sun.awt` / `sun.lwawt.macosx` for JCEF — see the `--add-opens` in
 * build.gradle.kts) and the level is set with `objc_msgSend` through JNA,
 * which is on the classpath anyway. Everything is looked up reflectively and
 * any miss falls back to a normal window: a JDK change must cost the mode,
 * never the widget.
 *
 * Alongside the level, the window is told to join every Space and stay put
 * in Mission Control (`NSWindowCollectionBehavior` CanJoinAllSpaces |
 * Stationary) — which is what makes it feel like a widget rather than a
 * window that happens to be at the back.
 *
 * The level survives activation. `CPlatformWindow.applyWindowLevel` only
 * raises POPUP, always-on-top and owned windows — this is none of those —
 * but it is re-applied on every activation anyway, cheaply, in case a later
 * runtime differs.
 *
 * ## Windows and Linux
 *
 * There is no desktop layer to join, so the window is kept at the bottom of
 * the stacking order (`toBack` on every activation): it never covers another
 * window. Win+D / Show Desktop hide it like any window — the true Windows
 * widget surface is the Store-only Widgets board, which cannot host this.
 */
internal object DesktopWindowLevel {

    /**
     * Just under kCGBackstopMenuLevel (-20): below every normal window (level
     * 0 and up, which is what "Show Desktop" slides away), above the
     * wallpaper and — measured, not assumed — above Finder's desktop icons,
     * which on current macOS sit higher than the header's
     * kCGDesktopIconWindowLevel suggests (that value left the widget under
     * the icons).
     */
    private const val MAC_DESKTOP_LEVEL = -25L

    private const val MAC_NORMAL_LEVEL = 0L

    /** NSWindowCollectionBehaviorCanJoinAllSpaces | NSWindowCollectionBehaviorStationary. */
    private const val MAC_WIDGET_BEHAVIOUR = (1L shl 0) or (1L shl 4)
    private const val MAC_DEFAULT_BEHAVIOUR = 0L

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** Sinks [window] to the desktop layer. Returns whether the platform could. */
    fun sinkToDesktop(window: Window): Boolean {
        if (isMac) return withNsWindow(window) { objc, ptr ->
            objc.send(ptr, "setCollectionBehavior:", MAC_WIDGET_BEHAVIOUR)
            objc.send(ptr, "setLevel:", MAC_DESKTOP_LEVEL)
        }
        window.toBack()
        return true
    }

    /** Back to an ordinary window. */
    fun restore(window: Window) {
        if (isMac) {
            withNsWindow(window) { objc, ptr ->
                objc.send(ptr, "setLevel:", MAC_NORMAL_LEVEL)
                objc.send(ptr, "setCollectionBehavior:", MAC_DEFAULT_BEHAVIOUR)
            }
        } else {
            window.toFront()
        }
    }

    /** The Objective-C runtime, for the two messages this needs. */
    private class ObjC {
        private val lib = NativeLibrary.getInstance("objc")
        private val sel = lib.getFunction("sel_registerName")
        private val msgSend = lib.getFunction("objc_msgSend")

        fun send(receiver: Pointer, selector: String, arg: Long) {
            val selectorPtr = sel.invoke(Pointer::class.java, arrayOf<Any>(selector)) as Pointer
            msgSend.invoke(Void.TYPE, arrayOf<Any>(receiver, selectorPtr, arg))
        }
    }

    /**
     * Peer → platform window → NSWindow pointer, then [action] with it on the
     * AppKit thread (that is what `CFRetainedResource.execute` guarantees).
     */
    private fun withNsWindow(window: Window, action: (ObjC, Pointer) -> Unit): Boolean = runCatching {
        val accessor = Class.forName("sun.awt.AWTAccessor")
            .getMethod("getComponentAccessor").invoke(null)
        // Through the interface (opened `sun.awt`), not the accessor's own
        // class — that is an anonymous class inside `java.awt`, which is not
        // opened, and reflecting on it directly is refused.
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", java.awt.Component::class.java)
            .invoke(accessor, window) ?: return false
        val platformWindow = peer.javaClass.getMethod("getPlatformWindow").apply { isAccessible = true }
            .invoke(peer) ?: return false
        val actionType = Class.forName("sun.lwawt.macosx.CFRetainedResource\$CFNativeAction")
        val objc = ObjC()
        val proxy = Proxy.newProxyInstance(actionType.classLoader, arrayOf(actionType)) { _, method, args ->
            if (method.name == "run") action(objc, Pointer(args[0] as Long))
            null
        }
        val execute = platformWindow.javaClass.getMethod("execute", actionType).apply { isAccessible = true }
        // AppKit only: `execute` guards the pointer's lifetime, not the
        // thread, and NSWindow raises (and the process aborts) when its
        // collection behaviour is set from anywhere else.
        val onAppKit = Class.forName("sun.lwawt.macosx.CThreading")
            .getMethod("executeOnAppKit", Runnable::class.java)
        onAppKit.invoke(
            null,
            Runnable {
                runCatching { execute.invoke(platformWindow, proxy) }
                    .onFailure { ZillitLog.w(TAG) { "desktop level on AppKit failed: ${it.cause ?: it}" } }
            },
        )
        true
    }.onFailure { error ->
        ZillitLog.w(TAG) { "could not set the desktop window level: ${error.cause ?: error}" }
    }.getOrDefault(false)

    private const val TAG = "DesktopWindowLevel"
}
