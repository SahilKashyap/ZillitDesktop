package com.zillit.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lets the text inside be selected with the mouse — drag, double-click a
 * word, triple-click a line — and copied with ⌘C / Ctrl+C or the right-click
 * Copy. Message bodies are read and quoted elsewhere; a label that can only
 * be copied whole is half a feature.
 *
 * Where the surface already has its own right-click menu, that menu must
 * catch the press on `PointerEventPass.Initial` and consume it — the text
 * menu inside is deeper, so on the main pass it opens first and both show —
 * and should copy through [probe] so a partial highlight is what lands on
 * the clipboard. Wrap only the words: a clock or
 * a name inside would join every "select all".
 */
@Composable
expect fun ZillitSelectable(
    modifier: Modifier = Modifier,
    probe: TextSelectionProbe? = null,
    content: @Composable () -> Unit,
)
