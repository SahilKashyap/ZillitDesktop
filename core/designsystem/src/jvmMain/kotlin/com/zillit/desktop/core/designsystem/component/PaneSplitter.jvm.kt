package com.zillit.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

/** See the `expect` declaration. The desktop has a real resize cursor; use it. */
@Composable
actual fun rememberHorizontalResizeCursor(): Modifier = remember {
    Modifier.pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
}
