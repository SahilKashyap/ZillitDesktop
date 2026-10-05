package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun ZillitSelectable(
    modifier: Modifier,
    probe: TextSelectionProbe?,
    content: @Composable () -> Unit,
) {
    if (probe == null) {
        SelectionContainer(modifier, content)
        return
    }
    // The container hands its selection to the desktop's text menu and to
    // nothing else; standing in for that menu is the one public way to read
    // it. The stand-in only listens, then draws the menu it replaced, and the
    // content gets the original back so a field inside keeps its own.
    val platform = LocalTextContextMenu.current
    val listening = remember(platform, probe) {
        object : TextContextMenu {
            @Composable
            override fun Area(
                textManager: TextContextMenu.TextManager,
                state: ContextMenuState,
                content: @Composable () -> Unit,
            ) {
                probe.read = { textManager.selectedText.text }
                platform.Area(textManager, state, content)
            }
        }
    }
    CompositionLocalProvider(LocalTextContextMenu provides listening) {
        SelectionContainer(modifier) {
            CompositionLocalProvider(LocalTextContextMenu provides platform, content = content)
        }
    }
}
