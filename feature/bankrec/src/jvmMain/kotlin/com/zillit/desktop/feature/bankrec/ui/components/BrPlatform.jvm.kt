package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import java.awt.datatransfer.DataFlavor
import java.io.File

@Composable
internal actual fun BrHorizontalRail(state: ScrollState, modifier: Modifier) {
    if (!state.canScrollForward && !state.canScrollBackward) return
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        style = ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 6.dp,
            shape = ZillitTheme.shapes.small,
            hoverDurationMillis = HOVER_MILLIS,
            unhoverColor = ZillitTheme.colors.textMuted.copy(alpha = REST_ALPHA),
            hoverColor = ZillitTheme.colors.textMuted,
        ),
    )
}

/**
 * The AWT bridge: the OS hands a `java.io.File` list through the transferable.
 * The file is read at once — a statement moved or locked mid-drag is simply
 * not taken, rather than failing later at import.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal actual fun Modifier.statementDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFile: (PickedStatement) -> Unit,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val hover by rememberUpdatedState(onHover)
    val take by rememberUpdatedState(onFile)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)

            override fun onExited(event: DragAndDropEvent) = hover(false)

            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val file = runCatching {
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.filterIsInstance<File>()
                        ?.firstOrNull { it.isFile }
                }.getOrNull() ?: return false
                val picked = runCatching { PickedStatement(file.name, file.readBytes()) }.getOrNull() ?: return false
                take(picked)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

private const val HOVER_MILLIS = 300
private const val REST_ALPHA = 0.5f
