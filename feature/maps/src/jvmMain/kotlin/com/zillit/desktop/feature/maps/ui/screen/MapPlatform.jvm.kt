package com.zillit.desktop.feature.maps.ui.screen

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
import com.zillit.desktop.feature.maps.domain.PickedPhoto
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal actual fun Modifier.photoDrop(
    onHover: (Boolean) -> Unit,
    onDrop: (photos: List<PickedPhoto>, refused: List<String>) -> Unit,
): Modifier {
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
                val (images, others) = dropped.partition { it.extension.lowercase() in PHOTO_EXTENSIONS }
                // A file moved or locked mid-drag is simply not taken.
                val read = images.mapNotNull { file ->
                    runCatching { PickedPhoto(file.name, imageType(file.extension), file.readBytes()) }.getOrNull()
                }
                take(read, others.map { it.name })
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}

actual fun decodeMapImage(bytes: ByteArray, maxEdge: Int): ImageBitmap? = try {
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
    // Skia has no HEIC decoder; those show a glyph until stored and fetched.
    ZillitLog.d(TAG) { "no picture in this file: ${failure::class.simpleName}" }
    null
}

private fun imageType(extension: String): String = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> "application/octet-stream"
}

private const val TAG = "MapPhotos"
