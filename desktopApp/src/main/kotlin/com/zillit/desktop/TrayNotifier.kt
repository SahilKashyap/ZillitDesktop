package com.zillit.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.core.notifications.DesktopNotification
import com.zillit.desktop.core.notifications.NotificationKind
import com.zillit.desktop.core.notifications.Notifier
import java.awt.SystemTray
import java.io.File

/**
 * Posts notifications through the system tray.
 *
 * Compose routes these to the platform's own notification centre — Notification
 * Center on macOS, toasts on Windows, libnotify on Linux and Crostini — which
 * is what makes them arrive when the window is behind something else. That is
 * also why the app now keeps a tray icon: the OS delivers notifications on
 * behalf of a tray presence, and without one there is nothing to deliver from.
 *
 * Nothing here throws. A notification is an aside, and a desktop without a tray
 * (a bare window manager, some Crostini setups) must degrade to silence rather
 * than take down whatever raised the alert.
 */
class TrayNotifier(private val trayState: TrayState) : Notifier {

    override fun post(note: DesktopNotification) {
        // macOS goes out through the helper, because the tray route below is a
        // no-op there — see [macNotifyHelper]. Everywhere else the tray route is
        // the working one, and a packaged macOS build with no helper falls back
        // to it rather than losing the notification silently.
        macNotifyHelper?.let { helper ->
            if (postNatively(helper, note)) return
        }

        if (!isTrayAvailable) {
            ZillitLog.d(TAG) { "No system tray; dropping a ${note.kind} notification" }
            return
        }

        runCatching {
            trayState.sendNotification(
                Notification(title = note.title, message = note.body, type = note.kind.composeType),
            )
        }.onFailure { error ->
            // Never the message: notification bodies are event titles.
            ZillitLog.w(TAG) { "Could not post a ${note.kind} notification: $error" }
        }
    }

    /**
     * Hands the notification to the helper, reporting whether it was accepted.
     *
     * Not waited on: delivery involves the notification daemon and, the first
     * time, a permission prompt the user may leave sitting there. Blocking a
     * chat arrival on that would stall whatever coroutine raised it.
     */
    private fun postNatively(helper: File, note: DesktopNotification): Boolean =
        runCatching {
            val process = ProcessBuilder(helper.absolutePath, note.title, note.body)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

            // The helper explains refusals on stderr — permission denied, a
            // daemon that never answered. Read on a daemon thread so this
            // cannot hold the app open at shutdown.
            Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        ZillitLog.d(TAG) { line }
                    }
                }
            }.apply { isDaemon = true }.start()

            true
        }.getOrElse { error ->
            ZillitLog.w(TAG) { "Notification helper would not start, using the tray: $error" }
            false
        }

    internal companion object {
        private const val TAG = "TrayNotifier"

        /**
         * Read once. `SystemTray.isSupported` touches AWT, and asking it per
         * notification would drag the toolkit in on whatever thread is posting.
         */
        private val isTrayAvailable: Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

        /**
         * The bundled `zillit-notify`, or null when this is not a packaged
         * macOS build.
         *
         * Compose's tray notifications reach macOS through
         * `java.awt.TrayIcon.displayMessage`, which the JDK implements with the
         * long-deprecated `NSUserNotificationCenter`. It reports success and
         * delivers nothing — the reason notifications work in the Windows build
         * and not this one. The helper uses the API that replaced it.
         *
         * Null in a `./gradlew :desktopApp:run` session, and that is correct
         * rather than a gap: macOS attributes a notification to the posting
         * process's bundle identifier and a bare JVM has none, so a dev run
         * cannot show one however it is posted. Notifications are testable only
         * in a packaged build.
         */
        val macNotifyHelper: File? = runCatching {
            if (currentPlatform().os != OperatingSystem.MacOs) return@runCatching null

            // `jpackage.app-path` is the launcher inside the bundle; java.home
            // is `<app>/Contents/runtime/Contents/Home`, three levels under
            // `Contents`, and is the fallback when the property is absent.
            val fromLauncher = System.getProperty("jpackage.app-path")
                ?.let { File(it).parentFile }
            val fromRuntime = System.getProperty("java.home")
                ?.let { File(it).parentFile?.parentFile?.parentFile?.resolve("MacOS") }

            listOfNotNull(fromLauncher, fromRuntime)
                .map { it.resolve("zillit-notify") }
                .firstOrNull { it.canExecute() }
        }.getOrNull()

        private val NotificationKind.composeType: Notification.Type
            get() = when (this) {
                NotificationKind.Info -> Notification.Type.Info
                NotificationKind.Warning -> Notification.Type.Warning
                NotificationKind.Error -> Notification.Type.Error
            }
    }
}
