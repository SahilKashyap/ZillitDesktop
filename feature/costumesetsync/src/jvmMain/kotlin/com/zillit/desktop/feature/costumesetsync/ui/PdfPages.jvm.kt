package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val QUARTER_TURN = 90

/** The widest bitmap a page is ever drawn at, so a 300% zoom of a wide window cannot ask for gigabytes. */
private const val MAX_WIDTH_PX = 2600
private const val MIN_WIDTH_PX = 80
private const val BYTES_PER_PIXEL = 4

internal actual fun openPdf(bytes: ByteArray): PdfPages? = runCatching {
    val document = Loader.loadPDF(bytes)
    if (document.numberOfPages < 1) {
        document.close()
        null
    } else {
        PdfBoxPages(document)
    }
}.getOrNull()

/** PDFBox's renderer is not thread-safe, so every page is drawn under one lock, on a worker thread. */
private class PdfBoxPages(private val document: PDDocument) : PdfPages {
    private val renderer = PDFRenderer(document)
    private val lock = Mutex()
    private var closed = false

    override val sizes: List<PageSize> = (0 until document.numberOfPages).map { i ->
        val page = document.getPage(i)
        val box = page.cropBox
        val turned = page.rotation % (QUARTER_TURN * 2) != 0
        if (turned) PageSize(box.height, box.width) else PageSize(box.width, box.height)
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        lock.withLock {
            if (closed || index !in sizes.indices) return@withLock null
            val width = widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
            val scale = width / sizes[index].width.coerceAtLeast(1f)
            runCatching { renderer.renderImage(index, scale, ImageType.RGB).toComposeBitmap() }.getOrNull()
        }
    }

    override fun close() {
        // Under the lock, so a page in flight finishes before the document goes; never on the caller's thread.
        CoroutineScope(Dispatchers.Default).launch {
            lock.withLock {
                closed = true
                runCatching { document.close() }
            }
        }
    }
}

/** An opaque RGB page as a Skia bitmap (BGRA, the order Skia's N32 wants on little-endian machines). */
private fun BufferedImage.toComposeBitmap(): ImageBitmap {
    val pixels = getRGB(0, 0, width, height, null, 0, width)
    val buffer = ByteBuffer.allocate(pixels.size * BYTES_PER_PIXEL).order(ByteOrder.LITTLE_ENDIAN)
    pixels.forEach { buffer.putInt(it or OPAQUE) }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
    bitmap.installPixels(buffer.array())
    return bitmap.asComposeImageBitmap()
}

private const val OPAQUE = 0xFF000000.toInt()
