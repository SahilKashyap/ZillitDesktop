package com.zillit.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.notifications.DesktopNotification
import com.zillit.desktop.core.notifications.NotificationKind
import com.zillit.desktop.core.notifications.Notifier
import java.awt.SystemTray

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

    private companion object {
        const val TAG = "TrayNotifier"

        /**
         * Read once. `SystemTray.isSupported` touches AWT, and asking it per
         * notification would drag the toolkit in on whatever thread is posting.
         */
        val isTrayAvailable: Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

        val NotificationKind.composeType: Notification.Type
            get() = when (this) {
                NotificationKind.Info -> Notification.Type.Info
                NotificationKind.Warning -> Notification.Type.Warning
                NotificationKind.Error -> Notification.Type.Error
            }
    }
}
