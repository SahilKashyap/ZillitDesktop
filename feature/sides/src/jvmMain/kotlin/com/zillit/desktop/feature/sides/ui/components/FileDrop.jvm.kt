package com.zillit.desktop.feature.sides.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import com.zillit.desktop.feature.sides.ui.PickedDoc
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * The AWT bridge: the OS hands a `java.io.File` list through the
 * transferable and the first readable file is handed on.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.sidesFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFile: (PickedDoc) -> Unit,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val hover by rememberUpdatedState(onHover)
    val file by rememberUpdatedState(onFile)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val dropped = runCatching {
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.filterIsInstance<File>().orEmpty()
                }.getOrDefault(emptyList()).firstNotNullOfOrNull { candidate ->
                    runCatching { PickedDoc(name = candidate.name, bytes = candidate.readBytes()) }.getOrNull()
                }
                if (dropped != null) file(dropped)
                return dropped != null
            }
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}
