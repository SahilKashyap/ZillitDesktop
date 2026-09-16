package com.zillit.desktop.feature.drive.ui.pages

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
import com.zillit.desktop.feature.drive.ui.PickedFile
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URLConnection

/**
 * The AWT bridge: the OS hands a `java.io.File` list through the
 * transferable. Directories are walked so every file beneath arrives with
 * its path relative to the dropped folder — `photos/day-1/a.jpg` — which is
 * what recreates the tree server-side. Paths only; nothing is read here.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
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
                }.getOrDefault(emptyList()).flatMap { it.describeTree() }
                if (dropped.isNotEmpty()) files(dropped)
                return dropped.isNotEmpty()
            }
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

/** A loose file as itself; a directory as every file beneath it with a relative path. */
private fun File.describeTree(): List<PickedFile> = when {
    isFile -> listOf(describe(relativePath = ""))
    isDirectory -> walkTopDown()
        .filter { it.isFile && !it.name.startsWith('.') }
        .map { file ->
            file.describe(relativePath = file.relativeTo(parentFile ?: this).path.replace(File.separatorChar, '/'))
        }
        .toList()
    else -> emptyList()
}

internal fun File.describe(relativePath: String): PickedFile = PickedFile(
    path = absolutePath,
    name = name,
    sizeBytes = length(),
    mimeType = URLConnection.guessContentTypeFromName(name) ?: FALLBACK_TYPE,
    relativePath = relativePath,
)

private const val FALLBACK_TYPE = "application/octet-stream"
