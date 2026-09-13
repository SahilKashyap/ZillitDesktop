package com.zillit.desktop.feature.dealmemo.ui.documents

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun platformPdfWork(): DealPdfWork = PdfBoxDealWork

/**
 * PDFBox for pages and stamping, AWT for signature images.
 *
 * Pages are measured by their crop box — what a viewer shows and what the
 * placement fractions were taken against — and a stamp is offset by the crop
 * box's corner, so a cropped page is signed where the signer put the mark.
 */
private object PdfBoxDealWork : DealPdfWork {

    override fun pages(pdf: ByteArray, widthPx: Int, maxPages: Int): ZillitResult<List<DealPdfPage>> = attempt(
        "This document could not be opened as a PDF.",
    ) {
        Loader.loadPDF(pdf).use { document ->
            val renderer = PDFRenderer(document)
            (0 until minOf(document.numberOfPages, maxPages)).map { index ->
                val box = document.getPage(index).cropBox
                val scale = widthPx / box.width
                val image = renderer.renderImage(index, scale)
                DealPdfPage(image.toComposeImageBitmap(), box.width, box.height)
            }
        }
    }

    override fun stamp(pdf: ByteArray, png: ByteArray, placements: List<DealPlacement>): ZillitResult<ByteArray> =
        attempt("The signature could not be applied to this document.") {
            val signature = ImageIO.read(ByteArrayInputStream(png)) ?: error("signature image unreadable")
            val aspect = signature.height.toDouble() / signature.width
            Loader.loadPDF(pdf).use { document ->
                val image = PDImageXObject.createFromByteArray(document, png, "signature")
                placements.forEach { placement ->
                    require(placement.pageIndex in 0 until document.numberOfPages) { "no page ${placement.pageIndex}" }
                    val page = document.getPage(placement.pageIndex)
                    val box = page.cropBox
                    val width = placement.fw * box.width
                    val height = width * aspect
                    val x = box.lowerLeftX + placement.fx * box.width
                    val y = box.lowerLeftY + box.height - placement.fy * box.height - height
                    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                        it.drawImage(image, x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
                    }
                }
                ByteArrayOutputStream().also(document::save).toByteArray()
            }
        }

    override fun image(bytes: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    override fun ink(strokes: List<List<Offset>>, width: Int, height: Int): ByteArray? {
        if (strokes.none { it.size > 1 } || width <= 0 || height <= 0) return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            graphics.color = INK
            graphics.stroke = BasicStroke(INK_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            strokes.forEach { stroke ->
                stroke.zipWithNext().forEach { (from, to) ->
                    graphics.drawLine(from.x.toInt(), from.y.toInt(), to.x.toInt(), to.y.toInt())
                }
            }
        } finally {
            graphics.dispose()
        }
        return png(image)
    }

    override fun typed(text: String, font: SignatureFont): ByteArray? {
        val name = text.trim().ifEmpty { return null }
        val family = font.families.firstOrNull { it in installedFamilies }
        val style = if (family == null) Font.ITALIC else Font.PLAIN
        val face = family ?: Font.SERIF
        val context = FontRenderContext(AffineTransform(), true, true)
        val usableWidth = (BOX_WIDTH - PAD_X * 2).toDouble()
        val usableHeight = (BOX_HEIGHT - PAD_Y * 2).toDouble()
        var size = Math.round(MAX_FONT * font.scale)
        fun bounds(points: Int) = Font(face, style, points).createGlyphVector(context, name).visualBounds
        while (size > MIN_FONT && bounds(size).width > usableWidth) size -= 2
        bounds(size).height.takeIf { it > usableHeight }?.let { inked ->
            size = maxOf(MIN_FONT, Math.floor(size * (usableHeight / inked)).toInt())
        }
        val measured = bounds(size)
        val image = BufferedImage(BOX_WIDTH * SCALE, BOX_HEIGHT * SCALE, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.scale(SCALE.toDouble(), SCALE.toDouble())
            graphics.font = Font(face, style, size)
            graphics.color = INK
            // Centre the inked box, not the em box: script faces are lopsided about it.
            val squeeze = if (measured.width > usableWidth) usableWidth / measured.width else 1.0
            val baseline = (BOX_HEIGHT - measured.height) / 2 - measured.y
            graphics.translate(BOX_WIDTH / 2.0, 0.0)
            graphics.scale(squeeze, 1.0)
            graphics.drawString(name, (-(measured.width / 2) - measured.x).toFloat(), baseline.toFloat())
        } finally {
            graphics.dispose()
        }
        return png(image)
    }

    private val installedFamilies: Set<String> by lazy {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
            .getOrDefault(emptySet())
    }

    private fun png(image: BufferedImage): ByteArray? =
        runCatching { ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() }.getOrNull()

    private inline fun <T> attempt(message: String, block: () -> T): ZillitResult<T> =
        runCatching(block).fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { thrown ->
                ZillitResult.Failure(ZillitError.Validation(userMessage = message, technical = thrown.toString()))
            },
        )

    private val INK = Color(0x10, 0x18, 0x28)
    private const val INK_WIDTH = 3.2f
    private const val BOX_WIDTH = 600
    private const val BOX_HEIGHT = 200
    private const val PAD_X = 24
    private const val PAD_Y = 10
    private const val SCALE = 2
    private const val MAX_FONT = 96
    private const val MIN_FONT = 14
}
