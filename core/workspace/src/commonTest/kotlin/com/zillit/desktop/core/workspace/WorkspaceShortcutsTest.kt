package com.zillit.desktop.core.workspace

import androidx.compose.ui.input.key.Key
import com.zillit.desktop.core.common.OperatingSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The shortcut table is pure, so all of it is covered here — keyboard handling
 * is exactly the sort of thing that silently stops working and is never noticed
 * until a user complains.
 */
class WorkspaceShortcutsTest {

    private val state = WorkspaceState(
        windows = listOf(
            toolWindow("w1", "/film-tools/budget", zIndex = 0),
            toolWindow("w2", "/film-tools/callsheet", zIndex = 1),
            toolWindow("w3", "/cnc", zIndex = 2),
        ),
    )

    @Test
    fun `only the platform primary modifier triggers a shortcut`() {
        // The OS-specific part (Cmd on macOS, Ctrl elsewhere) is resolved into
        // `primary` by the KeyEvent adapter; the table only sees the result.
        assertIs<WorkspaceEvent.Close>(WorkspaceShortcuts.resolve(stroke(Key.W, primary = true), state))
        assertNull(
            WorkspaceShortcuts.resolve(stroke(Key.W, ctrl = true), state),
            "a non-primary modifier must not close the window",
        )
    }

    @Test
    fun `close targets the active window`() {
        val event = WorkspaceShortcuts.resolve(stroke(Key.W, primary = true), state)

        assertEquals(WindowId("w3"), (event as WorkspaceEvent.Close).id)
    }

    @Test
    fun `key up is ignored`() {
        // Acting on both down and up fires every shortcut twice.
        assertNull(WorkspaceShortcuts.resolve(stroke(Key.W, primary = true, isKeyDown = false), state))
    }

    @Test
    fun `an unmodified key is not a shortcut`() {
        // Otherwise typing a W into a form would close the window.
        assertNull(WorkspaceShortcuts.resolve(stroke(Key.W), state))
    }

    @Test
    fun `Ctrl+Tab cycles on every platform including macOS`() {
        // This one really is Ctrl on macOS too, matching browsers and IDEs.
        assertIs<WorkspaceEvent.FocusNext>(WorkspaceShortcuts.resolve(stroke(Key.Tab, ctrl = true), state))
        assertIs<WorkspaceEvent.FocusPrevious>(
            WorkspaceShortcuts.resolve(stroke(Key.Tab, ctrl = true, shift = true), state),
        )
    }

    @Test
    fun `number keys jump by position`() {
        val first = WorkspaceShortcuts.resolve(stroke(Key.One, primary = true), state)
        assertEquals(0, (first as WorkspaceEvent.FocusIndex).index)

        val second = WorkspaceShortcuts.resolve(stroke(Key.Two, primary = true), state)
        assertEquals(1, (second as WorkspaceEvent.FocusIndex).index)
    }

    @Test
    fun `nine jumps to the last window as browsers do`() {
        val event = WorkspaceShortcuts.resolve(stroke(Key.Nine, primary = true), state)

        assertEquals(state.orderedWindows.lastIndex, (event as WorkspaceEvent.FocusIndex).index)
    }

    @Test
    fun `reopen requires shift so it cannot be hit by accident`() {
        assertIs<WorkspaceEvent.ReopenLastClosed>(
            WorkspaceShortcuts.resolve(stroke(Key.T, primary = true, shift = true), state),
        )
    }

    @Test
    fun `layout toggle, maximize, minimize and pin are bound`() {
        fun resolve(key: Key) = WorkspaceShortcuts.resolve(stroke(key, primary = true), state)

        assertIs<WorkspaceEvent.ToggleLayoutMode>(resolve(Key.Backslash))
        assertIs<WorkspaceEvent.ToggleMaximize>(resolve(Key.Enter))
        assertIs<WorkspaceEvent.Minimize>(resolve(Key.M))
        assertIs<WorkspaceEvent.TogglePin>(resolve(Key.P))
    }

    @Test
    fun `window-targeted shortcuts do nothing when nothing is open`() {
        val empty = WorkspaceState()

        assertNull(WorkspaceShortcuts.resolve(stroke(Key.W, primary = true), empty))
        assertNull(WorkspaceShortcuts.resolve(stroke(Key.M, primary = true), empty))
    }

    @Test
    fun `the described table matches the platform modifier`() {
        assertEquals("⌘ W", WorkspaceShortcuts.describe(OperatingSystem.MacOs).first().first)
        assertEquals("Ctrl W", WorkspaceShortcuts.describe(OperatingSystem.Windows).first().first)
    }

    // -- helpers ----------------------------------------------------------

    private fun stroke(
        key: Key,
        primary: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        isKeyDown: Boolean = true,
    ) = KeyStroke(key = key, isKeyDown = isKeyDown, primary = primary, ctrl = ctrl, shift = shift)

    private fun toolWindow(id: String, path: String, zIndex: Int) = ToolWindow(
        id = WindowId(id),
        title = path.substringAfterLast('/'),
        iconKey = path,
        zIndex = zIndex,
        history = listOf(WorkspaceRoute.Tool(path)),
    )
}
