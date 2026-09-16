package com.zillit.desktop.feature.formsignature.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.PlacedStamp
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * The tool's PDF work, on PDFBox — the library the app already ships for
 * call-sheet thumbnails.
 *
 * ## The stamp is the signature
 *
 * [stamp] is a faithful port of the web's pdf-lib flow: embed the PNG, draw
 * it on the page at the spot's rectangle, save. PDF pages and [PlacedStamp]
 * share the same space — points, bottom-left origin — so the numbers pass
 * straight through; there is deliberately **no arithmetic here to test in
 * isolation from the geometry**, because the one bug this class could add is
 * a conversion, and the cure is not doing one.
 *
 * ## Appended, not re-rendered
 *
 * `drawImage` on a fresh content stream in APPEND mode leaves every existing
 * byte of the page alone. The document that comes back is the document that
 * went in, plus ink.
 */
class PdfBoxWork : PdfWork {

    override fun renderPages(
        pdf: ByteArray,
        targetWidthPx: Int,
    ): ZillitResult<List<PdfPageImage>> = runCatching {
        Loader.loadPDF(pdf).use { document ->
            val renderer = PDFRenderer(document)
            (0 until document.numberOfPages).map { index ->
                val box = document.getPage(index).mediaBox
                val scale = targetWidthPx / box.width
                val image = renderer.renderImage(index, scale)
                PdfPageImage(
                    page = index + 1,
                    imageBytes = image.toPng(),
                    widthPx = image.width,
                    heightPx = image.height,
                    widthPt = box.width.toDouble(),
                    heightPt = box.height.toDouble(),
                )
            }
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { thrown ->
            ZillitResult.Failure(
                ZillitError.Validation(
                    "This document could not be opened as a PDF (${thrown::class.simpleName}).",
                ),
            )
        },
    )

    override fun renderPage(
        pdf: ByteArray,
        page: Int,
        targetWidthPx: Int,
    ): ZillitResult<PdfPageImage> = runCatching {
        Loader.loadPDF(pdf).use { document ->
            require(page in 1..document.numberOfPages) {
                "page $page is not in this ${document.numberOfPages}-page document"
            }
            val box = document.getPage(page - 1).mediaBox
            val image = PDFRenderer(document).renderImage(page - 1, targetWidthPx / box.width)
            PdfPageImage(
                page = page,
                imageBytes = image.toPng(),
                widthPx = image.width,
                heightPx = image.height,
                widthPt = box.width.toDouble(),
                heightPt = box.height.toDouble(),
            )
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { thrown ->
            ZillitResult.Failure(
                ZillitError.Validation(
                    thrown.message ?: "This page could not be rendered (${thrown::class.simpleName}).",
                ),
            )
        },
    )

    override fun pageCount(pdf: ByteArray): ZillitResult<Int> = runCatching {
        Loader.loadPDF(pdf).use { it.numberOfPages }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Validation("This document could not be opened as a PDF.")) },
    )

    override fun imageSize(png: ByteArray): ZillitResult<Pair<Int, Int>> = runCatching {
        val image = ImageIO.read(png.inputStream()) ?: error("not an image")
        image.width to image.height
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Validation("The signature image could not be read.")) },
    )

    override fun stamp(
        pdf: ByteArray,
        stamps: List<PlacedStamp>,
    ): ZillitResult<ByteArray> = runCatching {
        Loader.loadPDF(pdf).use { document ->
            stamps.forEach { placed ->
                val pageIndex = placed.spot.page - 1
                require(pageIndex in 0 until document.numberOfPages) {
                    "page ${placed.spot.page} is not in this ${document.numberOfPages}-page document"
                }
                val page = document.getPage(pageIndex)
                val image = PDImageXObject.createFromByteArray(document, placed.pngBytes, "signature")
                PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true,
                ).use { stream ->
                    stream.drawImage(
                        image,
                        placed.spot.x.toFloat(),
                        placed.spot.y.toFloat(),
                        placed.spot.width.toFloat(),
                        placed.spot.height.toFloat(),
                    )
                }
            }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { thrown ->
            ZillitResult.Failure(
                ZillitError.Validation(
                    "The signature could not be applied (${thrown::class.simpleName}).",
                ),
            )
        },
    )

    override fun rasterizeStrokes(
        strokes: List<List<StrokePoint>>,
        canvasWidth: Int,
        canvasHeight: Int,
    ): ZillitResult<ByteArray> = runCatching {
        require(strokes.any { it.size > 1 }) { "nothing was drawn" }
        val image = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            // Ink on a transparent ground, so the stamp shows the page
            // through it rather than a white card.
            graphics.color = Color(INK_R, INK_G, INK_B)
            graphics.stroke = BasicStroke(
                STROKE_WIDTH,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )
            strokes.forEach { stroke ->
                stroke.zipWithNext().forEach { (from, to) ->
                    graphics.drawLine(
                        from.x.toInt(),
                        from.y.toInt(),
                        to.x.toInt(),
                        to.y.toInt(),
                    )
                }
            }
        } finally {
            graphics.dispose()
        }
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { thrown ->
            ZillitResult.Failure(
                ZillitError.Validation(
                    thrown.message ?: "The signature could not be drawn (${thrown::class.simpleName}).",
                ),
            )
        },
    )

    private fun BufferedImage.toPng(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(this, "png", it) }.toByteArray()

    private companion object {
        const val STROKE_WIDTH = 4.5f

        // A blue-black ink, matching the web canvas's pen.
        const val INK_R = 22
        const val INK_G = 42
        const val INK_B = 96
    }
}
