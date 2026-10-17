package com.zillit.desktop.feature.tasks.ui

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
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.feature.tasks.domain.PASTED_FILE_NAME
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** The AWT bridge: the OS hands a `java.io.File` list through the transferable. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.taskFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<PickedTaskFile>) -> Unit,
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
                        ?.filterIsInstance<File>()
                        .orEmpty()
                }.getOrDefault(emptyList()).mapNotNull { it.readPicked() }
                if (dropped.isNotEmpty()) files(dropped)
                return dropped.isNotEmpty()
            }
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

/**
 * A screenshot, or files copied in a file manager.
 *
 * The image flavour is tried first: a screenshot copied from a viewer offers
 * both an image and (sometimes) its temporary file, and the image is the one
 * the user meant. It is written as PNG, which is lossless — the shrinking
 * step decides what finally goes up.
 */
actual suspend fun clipboardAttachments(): List<PickedTaskFile> = withContext(Dispatchers.IO) {
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            val image = clipboard.getData(DataFlavor.imageFlavor) as? RenderedImage
            val bytes = image?.let { it.asPng() }
            if (bytes != null && bytes.isNotEmpty()) {
                return@runCatching listOf(PickedTaskFile(PASTED_FILE_NAME, "image/png", bytes))
            }
        }
        if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
            val list = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>
            return@runCatching list?.filterIsInstance<File>().orEmpty().mapNotNull { it.readPicked() }
        }
        emptyList()
    }.getOrElse { failure ->
        // A clipboard another process is holding, or a flavour that lied
        // about itself: paste does nothing rather than taking the panel down.
        ZillitLog.i(TAG) { "clipboard held nothing attachable: ${failure::class.simpleName}" }
        emptyList()
    }
}

/** Only loose files, and only ones small enough to be worth reading. */
private fun File.readPicked(): PickedTaskFile? {
    if (!isFile || length() <= 0 || length() > MAX_READ_BYTES) return null
    val bytes = runCatching { readBytes() }.getOrNull() ?: return null
    return PickedTaskFile(name, contentTypeFor(name, null), bytes)
}

private fun RenderedImage.asPng(): ByteArray? = runCatching {
    ByteArrayOutputStream().use { out ->
        // A `BufferedImage` with an alpha channel writes fine as PNG; anything
        // else the clipboard offers is converted by ImageIO itself.
        ImageIO.write(this as? BufferedImage ?: return@runCatching null, "png", out)
        out.toByteArray()
    }
}.getOrNull()

/** Past this nothing is attachable anyway (the checks refuse over 25 MB). */
private const val MAX_READ_BYTES = 30L * 1024 * 1024
private const val TAG = "TasksClipboard"
