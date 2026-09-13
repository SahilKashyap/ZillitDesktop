package com.zillit.desktop.feature.documentdistribution.ui.pages

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
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URLConnection

/**
 * The AWT bridge: the OS hands a `java.io.File` list through the
 * transferable, and each file is read at once. A file that cannot be read
 * (moved mid-drag, permissions) costs itself only.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<LocalFile>) -> Unit,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val hover by rememberUpdatedState(onHover)
    val files by rememberUpdatedState(onFiles)
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
                }.getOrDefault(emptyList()).mapNotNull { file ->
                    runCatching {
                        LocalFile(
                            name = file.name,
                            contentType = URLConnection.guessContentTypeFromName(file.name) ?: FALLBACK_TYPE,
                            bytes = file.readBytes(),
                        )
                    }.getOrNull()
                }
                if (dropped.isNotEmpty()) files(dropped)
                return dropped.isNotEmpty()
            }
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

private const val FALLBACK_TYPE = "application/octet-stream"
