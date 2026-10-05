package com.zillit.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What a [ZillitSelectable] has highlighted right now, for a "Copy" menu
 * item to prefer over the whole message.
 *
 * Read it at the press that opens the menu, not at the item's click: the
 * menu's popup takes focus, and a selection container drops its highlight
 * the moment it loses focus.
 */
class TextSelectionProbe {
    internal var read: () -> String = { "" }

    /** The highlighted words, or empty when nothing is. */
    val selectedText: String get() = read()

    /** The highlighted words when there are any, else [whole]. */
    fun selectedOr(whole: String): String = selectedText.ifEmpty { whole }
}

@Composable
fun rememberTextSelectionProbe(): TextSelectionProbe = remember { TextSelectionProbe() }
