package com.zillit.desktop.feature.assetreport.ui.components

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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.assetreport.domain.AssetFileRules
import com.zillit.desktop.feature.assetreport.domain.PickedAssetFile
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal actual fun decodeAssetImage(bytes: ByteArray, maxEdge: Int): ImageBitmap? = try {
    val image = Image.makeFromEncoded(bytes)
    val longEdge = max(image.width, image.height)
    if (longEdge <= maxEdge) {
        image.toComposeImageBitmap()
    } else {
        val scale = maxEdge.toFloat() / longEdge
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                null,
                true,
            )
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
    ZillitLog.d(TAG) { "no picture in this file: ${failure::class.simpleName}" }
    null
}

internal actual fun decodeAssetPages(bytes: ByteArray): List<ImageBitmap> {
    if (bytes.isEmpty()) return emptyList()
    return if (bytes.looksLikePdf()) pdfPages(bytes) else listOfNotNull(decodeAssetImage(bytes, VIEWER_EDGE))
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal actual fun Modifier.assetFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onDrop: (files: List<PickedAssetFile>, tooLarge: List<Pair<String, Long>>) -> Unit,
): Modifier {
    val accepting by rememberUpdatedState(enabled)
    val hover by rememberUpdatedState(onHover)
    val take by rememberUpdatedState(onDrop)
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
                        ?.filter { it.isFile }
                }.getOrNull().orEmpty()
                if (dropped.isEmpty()) return false
                val (big, small) = dropped.partition { it.length() > AssetFileRules.MAX_BYTES }
                // A file moved or locked mid-drag is simply not taken.
                val read = small.mapNotNull { file ->
                    runCatching { PickedAssetFile(file.name, file.readBytes()) }.getOrNull()
                }
                take(read, big.map { it.name to it.length() })
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { accepting }, target = target)
}

/** `%PDF` — the magic number, which is more honest than the file name. */
private fun ByteArray.looksLikePdf(): Boolean =
    size > PDF_MAGIC.size && PDF_MAGIC.indices.all { this[it] == PDF_MAGIC[it] }

/**
 * Pages at reading resolution. [MAX_PAGES] is a guard, not a preference: a
 * receipt with a long appendix would otherwise hold the viewer while it
 * rasterised the lot.
 */
private fun pdfPages(bytes: ByteArray): List<ImageBitmap> = try {
    Loader.loadPDF(bytes).use { document ->
        val renderer = PDFRenderer(document)
        (0 until minOf(document.numberOfPages, MAX_PAGES)).mapNotNull { page ->
            runCatching { renderer.renderImageWithDPI(page, RENDER_DPI).toComposeImageBitmap() }.getOrNull()
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
    ZillitLog.d(TAG) { "no preview for this document: ${failure::class.simpleName}" }
    emptyList()
}

private val PDF_MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())
private const val RENDER_DPI = 110f
private const val MAX_PAGES = 30
private const val VIEWER_EDGE = 2400
private const val TAG = "AssetRegisterMedia"
