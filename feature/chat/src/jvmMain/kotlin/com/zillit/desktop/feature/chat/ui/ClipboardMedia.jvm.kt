package com.zillit.desktop.feature.chat.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * AWT's clipboard, the same one `copyTextToClipboard` writes — Compose
 * desktop runs on AWT's thread, so both halves are plain calls. Reads
 * re-encode as PNG because the flavor hands back a raw `java.awt.Image`
 * with no file behind it; PNG keeps screenshots lossless, which is what
 * the clipboard mostly carries.
 */
actual fun systemClipboardMedia(): ClipboardMediaSource? =
    if (GraphicsEnvironment.isHeadless()) null else AwtClipboardMedia

private object AwtClipboardMedia : ClipboardMediaSource {

    override fun readImage(): ClipboardImage? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val bytes = ByteArrayOutputStream().also { out ->
            ImageIO.write(image.asBuffered(), "png", out)
        }.toByteArray()
        ClipboardImage(name = "Pasted image.png", bytes = bytes)
    }.getOrNull()

    override fun writeImage(image: ImageBitmap): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image.toAwtImage()), null)
        true
    }.getOrDefault(false)
}

/** The image alone — the one flavor every screenshot-pasting app reads. */
private class ImageSelection(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        require(flavor == DataFlavor.imageFlavor) { "unsupported flavor $flavor" }
        return image
    }
}

/** A drawn copy: the clipboard may hand back an un-buffered toolkit image. */
private fun java.awt.Image.asBuffered(): BufferedImage {
    if (this is BufferedImage) return this
    val buffered = BufferedImage(
        getWidth(null).coerceAtLeast(1),
        getHeight(null).coerceAtLeast(1),
        BufferedImage.TYPE_INT_ARGB,
    )
    val graphics = buffered.createGraphics()
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return buffered
}
