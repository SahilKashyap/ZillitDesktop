package com.zillit.desktop.core.designsystem.component

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
 * A file that cannot be read (moved mid-drag, permissions, a folder) costs
 * itself only.
 *
 * The target is remembered once, so the callbacks it calls are read through
 * `rememberUpdatedState`: a caller whose handlers change between frames (a
 * different view model after a production switch) is called, not its first
 * frame's copy.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<DroppedFile>) -> Unit,
    maxBytes: Long,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val hover by rememberUpdatedState(onHover)
    val deliver by rememberUpdatedState(onFiles)
    val ceiling by rememberUpdatedState(maxBytes)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val files = runCatching {
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.filterIsInstance<File>()
                        .orEmpty()
                }.getOrDefault(emptyList())

                val dropped = files.mapNotNull { file -> runCatching { file.dropped(ceiling) }.getOrNull() }
                if (dropped.isNotEmpty()) deliver(dropped)
                return dropped.isNotEmpty()
            }
        }
    }

    return this.dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

/** Reads [this] into a [DroppedFile]; over [maxBytes] it travels unread, with its size. */
private fun File.dropped(maxBytes: Long): DroppedFile {
    val type = URLConnection.guessContentTypeFromName(name) ?: FALLBACK_TYPE
    val size = length()
    return if (size > maxBytes) {
        DroppedFile(name = name, contentType = type, bytes = ByteArray(0), sizeBytes = size)
    } else {
        DroppedFile(name = name, contentType = type, bytes = readBytes())
    }
}

private const val FALLBACK_TYPE = "application/octet-stream"
