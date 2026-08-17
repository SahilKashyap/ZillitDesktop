package com.zillit.desktop.feature.home.ui

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
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URLConnection

/**
 * The AWT bridge: the OS hands a `java.io.File` list through the transferable,
 * and each file is read immediately — same rules as [DroppedFile] documents.
 * A file that cannot be read (moved mid-drag, permissions) costs itself only.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = onHover(true)
            override fun onExited(event: DragAndDropEvent) = onHover(false)
            override fun onEnded(event: DragAndDropEvent) = onHover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onHover(false)
                val files = runCatching {
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.filterIsInstance<File>()
                        .orEmpty()
                }.getOrDefault(emptyList())

                val dropped = files.mapNotNull { file ->
                    runCatching {
                        DroppedFile(
                            name = file.name,
                            contentType = URLConnection.guessContentTypeFromName(file.name)
                                ?: FALLBACK_TYPE,
                            bytes = file.readBytes(),
                        )
                    }.getOrNull()
                }
                if (dropped.isNotEmpty()) onFiles(dropped)
                return dropped.isNotEmpty()
            }
        }
    }

    return this.dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

private const val FALLBACK_TYPE = "application/octet-stream"
