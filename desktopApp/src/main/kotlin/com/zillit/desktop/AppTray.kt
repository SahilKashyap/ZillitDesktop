package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import java.awt.Desktop

/**
 * The tray icon, its menu, and the reminders it delivers.
 *
 * The icon exists because the OS delivers notifications on behalf of a tray
 * presence — without one, Compose's `sendNotification` goes nowhere and calendar
 * reminders never arrive. Having put an icon in the menu bar, it should do
 * something when clicked, which is what the menu is for.
 *
 * On a desktop with no tray at all (a bare window manager, some Crostini
 * setups) this declines quietly: reminders stop working, the app does not.
 */
@Composable
@Suppress("LongParameterList") // Each is a distinct tray concern.
internal fun ApplicationScope.AppTray(
    graph: AppGraph,
    preferences: PreferenceStore,
    // Hoisted: alerts post through the same tray presence, and a second
    // TrayState would be a second (invisible) delivery channel.
    trayState: TrayState,
    /** Shows the main window, un-hiding it if a close sent it to the tray. */
    onShow: () -> Unit,
    /** The widgets' switches — the tray is where they live when the main window is away. */
    widgets: WidgetSwitches,
    onQuit: () -> Unit,
) {

    if (isTraySupported) {
        Tray(
            state = trayState,
            icon = rememberVectorPainter(ZillitIcons.Mark),
            tooltip = "Zillit-Desktop",
            // The click that does *not* open the menu — right-click on macOS,
            // double-click on Windows — still does the obvious thing rather
            // than nothing.
            onAction = onShow,
        ) {
            Item("Show Zillit", onClick = onShow)
            ZillitWidget.entries.forEach { widget ->
                val shown = widgets.isOpen(widget)
                Item(
                    if (shown) "Hide ${widget.label} widget" else "Show ${widget.label} widget",
                    onClick = { widgets.toggle(widget) },
                )
            }
            Separator()
            // Named, not just "Quit": in a menu bar full of other apps' icons,
            // an unqualified Quit is a coin flip about what is about to close.
            Item("Quit Zillit", onClick = onQuit)
        }
    }

    CalendarReminders(graph = graph, preferences = preferences, trayState = trayState)
}

/**
 * Brings the main frame back to the front.
 *
 * Four steps because "show" fails four different ways: the window can be
 * minimised, hidden, behind another window, or in front but unfocused. Doing
 * only [ComposeWindow.toFront] leaves a minimised window minimised, which reads
 * as a dead menu item.
 *
 * The foreground request is what makes this work on macOS, where a background
 * process cannot raise itself above the active application by calling
 * `toFront` alone — the window would rise within Zillit's own layer and stay
 * behind whatever the user was actually looking at.
 */
fun showMainWindow(frame: ComposeWindow?, windowState: WindowState) {
    windowState.isMinimized = false

    requestForeground()

    frame?.apply {
        isVisible = true
        toFront()
        requestFocus()
    }
}

/**
 * Asks the OS to make this the active application.
 *
 * Absent on most platforms, where activation follows from raising a window;
 * present and necessary on macOS. Failure is not worth reporting to the user —
 * the window still rises, it just may not steal focus.
 */
private fun requestForeground() {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
            desktop.requestForeground(true)
        }
    }.onFailure { error ->
        ZillitLog.d(TAG) { "Could not request foreground: $error" }
    }
}

private const val TAG = "AppTray"
