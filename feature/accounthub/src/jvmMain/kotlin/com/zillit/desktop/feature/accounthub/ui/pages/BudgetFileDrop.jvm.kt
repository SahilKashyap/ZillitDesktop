package com.zillit.desktop.feature.accounthub.ui.pages

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

/**
 * The AWT bridge, as Bank Reconciliation's statement drop does it: the OS hands
 * a `java.io.File` list through the transferable, and the first plain file is
 * read at once — a file moved or locked mid-drag is simply not taken.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal actual fun Modifier.budgetFileDrop(
    enabled: Boolean,
    maxBytes: Long,
    onHover: (Boolean) -> Unit,
    onFile: (name: String, bytes: ByteArray) -> Unit,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val hover by rememberUpdatedState(onHover)
    val take by rememberUpdatedState(onFile)
    val cap by rememberUpdatedState(maxBytes)
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
                val limit = (cap + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val bytes = runCatching { file.inputStream().use { it.readNBytes(limit) } }.getOrNull() ?: return false
                take(file.name, bytes)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}
