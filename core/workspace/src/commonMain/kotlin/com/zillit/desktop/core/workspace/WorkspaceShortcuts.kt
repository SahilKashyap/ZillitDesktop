package com.zillit.desktop.core.workspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Maps key presses to workspace actions.
 *
 * Pure: takes a [KeyEvent], returns an event or null. That makes the whole
 * shortcut table testable without a window, which matters because keyboard
 * handling is the sort of thing that silently stops working.
 *
 * Bindings follow browser and editor convention rather than inventing new ones —
 * on desktop, `Cmd/Ctrl+W` closing the current tab is muscle memory, and an app
 * that repurposes it feels broken.
 */
/**
 * A key press, free of any platform type.
 *
 * `primary` is the platform's main modifier — Command on macOS, Control
 * elsewhere — already resolved, so the shortcut table never has to ask which OS
 * it is on.
 */
data class KeyStroke(
    val key: Key,
    val isKeyDown: Boolean = true,
    val primary: Boolean = false,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
)

object WorkspaceShortcuts {

    /**
     * The platform's primary modifier: Command on macOS, Control elsewhere.
     *
     * Getting this wrong is the classic sign of a port — `Ctrl+W` on a Mac is
     * not what anyone expects.
     */
    fun isPrimaryModifier(event: KeyEvent, os: OperatingSystem): Boolean =
        if (os == OperatingSystem.MacOs) event.isMetaPressed else event.isCtrlPressed

    /**
     * Adapter from a Compose key event. Translates to [KeyStroke] and defers to
     * the pure [resolve] below.
     */
    fun resolve(event: KeyEvent, state: WorkspaceState, os: OperatingSystem): WorkspaceEvent? =
        resolve(
            stroke = KeyStroke(
                key = event.key,
                isKeyDown = event.type == KeyEventType.KeyDown,
                primary = isPrimaryModifier(event, os),
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
            ),
            state = state,
        )

    /**
     * Resolves [stroke] to a workspace action, or null if it is not a shortcut.
     *
     * Takes a plain [KeyStroke] rather than a Compose `KeyEvent` because
     * `KeyEvent` wraps a platform-specific native event that cannot be
     * constructed in common code — which would have forced the whole shortcut
     * table into a per-platform test. This way the table is pure and tested
     * once.
     *
     * Returning null means "not handled", so the key continues to the focused
     * control — a shortcut handler that swallows everything breaks typing.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun resolve(stroke: KeyStroke, state: WorkspaceState): WorkspaceEvent? {
        // KeyDown only: acting on both down and up fires every shortcut twice.
        if (!stroke.isKeyDown) return null

        val activeId = state.activeId

        // Ctrl+Tab cycles regardless of platform — this one is Ctrl even on
        // macOS, matching browsers and IDEs.
        if (stroke.ctrl && stroke.key == Key.Tab) {
            return if (stroke.shift) WorkspaceEvent.FocusPrevious else WorkspaceEvent.FocusNext
        }

        if (!stroke.primary) return null

        return when {
            stroke.key == Key.W && activeId != null -> WorkspaceEvent.Close(activeId)

            stroke.key == Key.T && stroke.shift -> WorkspaceEvent.ReopenLastClosed

            stroke.key == Key.Backslash -> WorkspaceEvent.ToggleLayoutMode

            stroke.key == Key.M && activeId != null -> WorkspaceEvent.Minimize(activeId)

            // Cmd/Ctrl+Enter toggles maximize — Cmd+M is taken by minimize, and
            // the OS owns Cmd+Ctrl+F on macOS.
            stroke.key == Key.Enter && activeId != null -> WorkspaceEvent.ToggleMaximize(activeId)

            stroke.key == Key.P && activeId != null -> WorkspaceEvent.TogglePin(activeId)

            // Cmd/Ctrl+9 jumps to the last window, as browsers do; 1-8 are
            // positional.
            stroke.key == Key.Nine -> WorkspaceEvent.FocusIndex(state.orderedWindows.lastIndex)

            else -> numberKeyIndex(stroke.key)?.let(WorkspaceEvent::FocusIndex)
        }
    }

    /** `Cmd/Ctrl+1`..`8` → zero-based window index. */
    private val POSITIONAL_KEYS = listOf(
        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight,
    )

    private fun numberKeyIndex(key: Key): Int? =
        POSITIONAL_KEYS.indexOf(key).takeIf { it >= 0 }

    /** Human-readable table, for a help overlay and for documentation. */
    fun describe(os: OperatingSystem): List<Pair<String, String>> {
        val mod = if (os == OperatingSystem.MacOs) "⌘" else "Ctrl"
        return listOf(
            "$mod W" to str(S.desktop_shortcut_close_current_tool),
            "$mod ⇧ T" to str(S.desktop_shortcut_reopen_last_closed),
            "$mod 1–8" to str(S.desktop_shortcut_jump_to_tool_by_position),
            "$mod 9" to str(S.desktop_shortcut_jump_to_last_tool),
            "Ctrl ⇥" to str(S.desktop_shortcut_next_tool),
            "Ctrl ⇧ ⇥" to str(S.desktop_shortcut_previous_tool),
            "$mod ⏎" to str(S.desktop_shortcut_maximize_restore),
            "$mod M" to str(S.desktop_shortcut_minimize),
            "$mod P" to str(S.desktop_shortcut_pin_unpin),
            "$mod \\" to str(S.desktop_shortcut_switch_tabs_floating),
        )
    }
}
