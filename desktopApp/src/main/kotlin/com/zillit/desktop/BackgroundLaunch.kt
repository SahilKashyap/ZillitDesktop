package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

/**
 * The `--background` launch: Zillit starts with its window hidden and lives
 * in the tray, the way Teams does after a sign-in. Calls and messages still
 * arrive — the socket, the call card and the message card need no window —
 * and the first click on the tray icon or the Dock brings the window up.
 *
 * The login item ([LoginItem]) is the only thing meant to pass this flag.
 * When it finds Zillit already running, `main` says nothing at all: a
 * dialog at every sign-in would be the one thing worse than no login item.
 */
object BackgroundLaunch {
    const val FLAG = "--background"

    fun requestedBy(args: Array<String>): Boolean = args.any { it.equals(FLAG, ignoreCase = true) }
}

/**
 * A click on the Dock icon while the window is hidden.
 *
 * macOS sends "reopen" rather than a new launch, so without this the Dock
 * click of a backgrounded Zillit would do nothing visible — the single
 * instance is already up, and nothing asked it to show itself.
 */
object DockReopen {
    fun watch(onReopen: () -> Unit) {
        runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener(AppReopenedListener { onReopen() })
            }
        }.onFailure { ZillitLog.d(TAG) { "no reopen events on this platform: $it" } }
    }

    private const val TAG = "DockReopen"
}
