package com.zillit.desktop.feature.home.data

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A poster image for an attachment — one video frame, or a document's first
 * page — as JPEG bytes plus its dimensions.
 */
data class PosterFrame(
    val jpegBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = jpegBytes.size
    override fun toString(): String = "PosterFrame(${widthPx}x$heightPx, ${jpegBytes.size}B)"
}

/** Shrinks to fit [maxEdge] and encodes JPEG. Never enlarges. */
internal fun BufferedImage.toPosterFrame(maxEdge: Int): PosterFrame {
    val scaled = scaledToFit(maxEdge)
    val out = ByteArrayOutputStream()
    ImageIO.write(scaled, "jpg", out)
    return PosterFrame(out.toByteArray(), scaled.width, scaled.height)
}

private fun BufferedImage.scaledToFit(maxEdge: Int): BufferedImage {
    val scale = maxEdge.toDouble() / maxOf(width, height)
    if (scale >= 1.0) {
        // JPEG has no alpha; a page rendered with transparency must land on
        // an opaque canvas or ImageIO writes garbage channels.
        return opaqueCopy()
    }

    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    scaled.createGraphics().apply {
        drawImage(getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        dispose()
    }
    return scaled
}

private fun BufferedImage.opaqueCopy(): BufferedImage {
    if (type == BufferedImage.TYPE_INT_RGB) return this
    val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    copy.createGraphics().apply {
        color = java.awt.Color.WHITE
        fillRect(0, 0, width, height)
        drawImage(this@opaqueCopy, 0, 0, null)
        dispose()
    }
    return copy
}
