package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.WidgetKeys
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.auth.ui.AuthStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The chrome every Zillit widget shares: a small window that either floats on
 * top or lies on the desktop, remembers where it was put, and carries the two
 * controls all of them need.
 *
 * Extracted from the Drive widget when Chat & Calls and the Crew List got the
 * same treatment. What differs between the three is only what they *show* —
 * the window, the mode switch, the drag handle, the geometry and the
 * signed-out page are one implementation, so a fix to any of them is a fix to
 * all three.
 *
 * ## Why a window of the app, not an OS widget
 *
 * macOS Notification Center widgets are Swift extensions built with Xcode,
 * signed, and by design a snapshot surface; Windows 11 widgets need a
 * Store-published web card. Neither can hold this app's session, and neither
 * can send a message or place a call. A window of the running app has the
 * session for free and runs on both platforms unchanged.
 */

/**
 * How a widget sits among other windows.
 *
 * [Floating]: an ordinary titled window kept on top — handy while working in
 * something else. [Desktop]: no title bar, and the window lives on the
 * desktop layer like the OS's own widgets — behind every normal window, above
 * the wallpaper, unmoved by "Show Desktop" (macOS; see [DesktopWindowLevel]
 * for what other platforms can do). Dragged by its bar; resized at its edges.
 */
internal enum class WidgetMode {
    Floating,
    Desktop,
    ;

    fun toggled(): WidgetMode = if (this == Floating) Desktop else Floating

    companion object {
        fun fromId(id: String): WidgetMode = entries.firstOrNull { it.name == id } ?: Floating
    }
}

/** What the shell hands its content: how the window currently sits, and the way to change it. */
internal class WidgetChrome(
    val mode: WidgetMode,
    val onToggleMode: () -> Unit,
)

/**
 * A widget window: [content] inside the shared chrome, remembered in
 * [keys] so it comes back where it was left.
 */
@Composable
@Suppress("LongParameterList") // Every seam is a distinct host concern; a holder object would just rename them.
internal fun ApplicationScope.WidgetWindow(
    title: String,
    keys: WidgetKeys,
    preferences: PreferenceStore,
    visible: Boolean,
    darkTheme: Boolean,
    /** For the crew's faces — a widget on another production draws initials for its strangers. */
    graph: AppGraph,
    onClose: () -> Unit,
    content: @Composable (WidgetChrome) -> Unit,
) {
    if (!visible) return
    val windowState = rememberWidgetWindowState(preferences, keys)
    var mode by remember { mutableStateOf(runBlocking { WidgetMode.fromId(preferences.get(keys.mode)) }) }
    LaunchedEffect(mode) { preferences.set(keys.mode, mode.name) }

    LaunchedEffect(windowState.size, windowState.position) {
        delay(WIDGET_GEOMETRY_SETTLE_MILLIS)
        runCatching { preferences.saveWidgetGeometry(keys, windowState) }
    }

    // Keyed on the mode: decorations cannot change on a shown window, so the
    // switch is a new native window at the same place and size.
    key(mode) {
        Window(
            onCloseRequest = onClose,
            state = windowState,
            title = title,
            icon = androidx.compose.ui.res.painterResource("icons/zillit-icon.png"),
            alwaysOnTop = mode == WidgetMode.Floating,
            undecorated = mode == WidgetMode.Desktop,
            resizable = true,
        ) {
            DesktopLayer(mode)
            ZillitTheme(darkTheme = darkTheme) {
                AvatarFaces(graph) {
                    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
                        // No title bar on the desktop: this strip is the handle
                        // that moves the window, and carries its close.
                        if (mode == WidgetMode.Desktop) {
                            WindowDraggableArea { GripStrip(title = title, onClose = onClose) }
                        }
                        content(WidgetChrome(mode = mode, onToggleMode = { mode = mode.toggled() }))
                    }
                }
            }
        }
    }
}

/**
 * Applies (and re-applies on every activation) the desktop layer for
 * [WidgetMode.Desktop]; puts the window back when the mode leaves.
 */
@Composable
private fun FrameWindowScope.DesktopLayer(mode: WidgetMode) {
    val frame = window
    DisposableEffect(frame, mode) {
        if (mode != WidgetMode.Desktop) return@DisposableEffect onDispose { }
        DesktopWindowLevel.sinkToDesktop(frame)
        val listener = object : WindowAdapter() {
            override fun windowActivated(event: WindowEvent) {
                DesktopWindowLevel.sinkToDesktop(frame)
            }
        }
        frame.addWindowListener(listener)
        onDispose {
            frame.removeWindowListener(listener)
            DesktopWindowLevel.restore(frame)
        }
    }
}

/**
 * The desktop-mode handle: a whole-width strip with nothing on it that eats
 * the pointer except the close — so anywhere on it drags the window. The
 * controls below stay clickable; a drag area wrapped around them would have
 * left no pixel to grab.
 */
@Composable
private fun GripStrip(title: String, onClose: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GRIP_HEIGHT)
            .background(colors.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(GRIP_WIDTH)
                .height(GRIP_THICKNESS)
                .background(colors.textMuted, ZillitTheme.shapes.pill),
        )
        ZillitText(
            text = title,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = ZillitTheme.spacing.sm),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close the widget",
            onClick = onClose,
            size = GRIP_BUTTON,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * The two buttons every widget's bar ends with: how the window sits, and the
 * way back to the main window.
 */
@Composable
internal fun WidgetActions(chrome: WidgetChrome, onOpenZillit: () -> Unit) {
    val desktop = chrome.mode == WidgetMode.Desktop
    ZillitIconButton(
        icon = if (desktop) ZillitIcons.Home else ZillitIcons.Pin,
        contentDescription = if (desktop) "Float on top instead" else "Fix on the desktop",
        onClick = chrome.onToggleMode,
        tint = ZillitTheme.colors.accent,
    )
    ZillitIconButton(
        icon = ZillitIcons.Monitor,
        contentDescription = "Open Zillit",
        onClick = onOpenZillit,
    )
}

/** The bar along the top of a widget: [leading] fills the width, the shared actions close it. */
@Composable
internal fun WidgetBar(
    chrome: WidgetChrome,
    onOpenZillit: () -> Unit,
    leading: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        leading()
        WidgetActions(chrome = chrome, onOpenZillit = onOpenZillit)
    }
}

/**
 * What a widget shows without a session. Says why, and offers the one thing
 * that helps — the main window, which is on the sign-in page.
 *
 * [what] names the widget in the sentence ("The Drive widget"), so the reason
 * reads as this window's own rather than a generic failure.
 */
@Composable
internal fun WidgetSignedOut(what: String, onOpenZillit: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
        ZillitEmptyState(
            title = "Zillit is signed out",
            message = "$what uses the main Zillit app's session. Sign in there — scan the QR " +
                "code — and the widget will work again.",
            icon = ZillitIcons.Shield,
            action = {
                ZillitButton(text = "Open Zillit to sign in", onClick = onOpenZillit, size = ButtonSize.Small)
            },
        )
    }
}

/** Signed in, for a widget's purposes: the device is linked and the session is live. */
internal val AuthStep.isSignedIn: Boolean
    get() = this == AuthStep.Complete || this == AuthStep.ProjectSelection

@Composable
private fun rememberWidgetWindowState(preferences: PreferenceStore, keys: WidgetKeys): WindowState {
    val (size, position) = remember(keys) {
        runBlocking {
            val width = preferences.get(keys.width)
            val height = preferences.get(keys.height)
            val x = preferences.get(keys.x)
            val y = preferences.get(keys.y)
            val hasPosition = x != ZillitPreferences.UNSET_POSITION && y != ZillitPreferences.UNSET_POSITION
            DpSize(width.dp, height.dp) to
                if (hasPosition) WindowPosition(x.dp, y.dp) else WindowPosition.Aligned(Alignment.TopEnd)
        }
    }
    return rememberWindowState(size = size, position = position)
}

private suspend fun PreferenceStore.saveWidgetGeometry(keys: WidgetKeys, state: WindowState) {
    set(keys.width, state.size.width.value.toInt().coerceAtLeast(1))
    set(keys.height, state.size.height.value.toInt().coerceAtLeast(1))
    val position = state.position
    if (position.isSpecified) {
        set(keys.x, position.x.value.toInt())
        set(keys.y, position.y.value.toInt())
    }
}

private val GRIP_HEIGHT = 22.dp
private val GRIP_WIDTH = 40.dp
private val GRIP_THICKNESS = 4.dp
private val GRIP_BUTTON = 18.dp
private const val WIDGET_GEOMETRY_SETTLE_MILLIS = 400L
