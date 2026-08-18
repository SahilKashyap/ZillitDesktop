package com.zillit.desktop.feature.draft.data

import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.Screenplay
import com.zillit.desktop.feature.draft.domain.ScreenplayLayout
import com.zillit.desktop.feature.draft.domain.ScreenplayRenderer
import com.zillit.desktop.feature.draft.domain.ScriptElement
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/**
 * The script on paper: US Letter, Courier 12, the margins and indents in
 * [ScreenplayLayout], a title page when there is one, page numbers top
 * right from page 2 (as Final Draft prints them), and "(MORE)" / "(CONT'D)"
 * when a speech is carried across a page.
 */
class PdfScreenplayRenderer : ScreenplayRenderer {

    override fun pdf(screenplay: Screenplay): ByteArray {
        val document = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.COURIER)
        try {
            titlePage(document, font, screenplay)
            Body(document, font, screenplay).render()
            return ByteArrayOutputStream().also(document::save).toByteArray()
        } finally {
            document.close()
        }
    }

    private fun titlePage(document: PDDocument, font: PDType1Font, screenplay: Screenplay) {
        val page = screenplay.titlePage
        val title = page.title.ifBlank { screenplay.title }
        if (title.isBlank() && page.author.isBlank()) return
        val pdPage = PDPage(PDRectangle.LETTER).also(document::addPage)
        PDPageContentStream(document, pdPage).use { stream ->
            stream.setFont(font, FONT_SIZE)
            var y = PAGE_HEIGHT - TOP_MARGIN - LINE_HEIGHT * TITLE_DROP_LINES
            fun centred(text: String) {
                val width = text.length * CHAR_WIDTH
                stream.text((PAGE_WIDTH - width) / 2, y, text)
                y -= LINE_HEIGHT
            }
            centred(title.uppercase())
            if (page.author.isNotBlank()) {
                y -= LINE_HEIGHT
                centred(page.credit.ifBlank { "Written by" })
                y -= LINE_HEIGHT
                centred(page.author)
            }
            if (page.source.isNotBlank()) {
                y -= LINE_HEIGHT
                centred(page.source)
            }
            if (page.draftDate.isNotBlank()) {
                y -= LINE_HEIGHT
                centred(page.draftDate)
            }
            // Contact block: bottom left, standard.
            val contactLines = page.contact.split('\n').filter { it.isNotBlank() }
            var cy = BOTTOM_MARGIN + LINE_HEIGHT * contactLines.size
            contactLines.forEach { line ->
                stream.text(LEFT_MARGIN, cy, line)
                cy -= LINE_HEIGHT
            }
        }
    }

    /** The body pages, written line by line with the layout's own pagination. */
    private class Body(
        private val document: PDDocument,
        private val font: PDType1Font,
        private val screenplay: Screenplay,
    ) {
        private var stream: PDPageContentStream? = null
        private var pageNumber = 0
        private var line = 0

        fun render() {
            newPage()
            var previous: ElementType? = null
            screenplay.elements.forEach { element ->
                val lines = ScreenplayLayout.wrap(element.text, ScreenplayLayout.widthChars(element.type))
                val before = ScreenplayLayout.spaceBefore(previous, element.type)
                val needed = before + lines.size
                val cueOnLastLine =
                    element.type == ElementType.Character && line + needed >= ScreenplayLayout.LINES_PER_PAGE
                if (line + needed > ScreenplayLayout.LINES_PER_PAGE || cueOnLastLine) {
                    if (element.type == ElementType.Dialogue && line + before + 2 <= ScreenplayLayout.LINES_PER_PAGE) {
                        // Carry a long speech: as much as fits, (MORE), then
                        // the cue again with (CONT'D) on the next page.
                        carrySpeech(element, lines, before)
                        previous = element.type
                        return@forEach
                    }
                    newPage()
                } else {
                    line += before
                }
                write(element.type, lines)
                previous = element.type
            }
            stream?.close()
        }

        private fun carrySpeech(element: ScriptElement, lines: List<String>, before: Int) {
            line += before
            val room = ScreenplayLayout.LINES_PER_PAGE - line - 1
            write(ElementType.Dialogue, lines.take(room))
            write(ElementType.Parenthetical, listOf("(MORE)"))
            newPage()
            val cue = screenplay.elements.lastOrNull { it.type == ElementType.Character && it.id != element.id }
            cue?.let { write(ElementType.Character, listOf(it.text.uppercase() + " (CONT'D)")) }
            write(ElementType.Dialogue, lines.drop(room))
        }

        private fun write(type: ElementType, lines: List<String>) {
            val out = stream ?: return
            val x = ScreenplayLayout.leftInches(type) * POINTS_PER_INCH
            lines.forEach { text ->
                val y = PAGE_HEIGHT - TOP_MARGIN - LINE_HEIGHT * (line + 1)
                val shown = if (type.isUppercase) text.uppercase() else text
                out.text(x, y, shown)
                line++
            }
        }

        private fun newPage() {
            stream?.close()
            val page = PDPage(PDRectangle.LETTER).also(document::addPage)
            pageNumber++
            line = 0
            stream = PDPageContentStream(document, page).also { s ->
                s.setFont(font, FONT_SIZE)
                if (pageNumber > 1) {
                    val label = "$pageNumber."
                    s.text(PAGE_WIDTH - RIGHT_MARGIN - label.length * CHAR_WIDTH, PAGE_HEIGHT - TOP_MARGIN / 2, label)
                }
            }
        }
    }

    private companion object {
        const val POINTS_PER_INCH = 72.0
        const val FONT_SIZE = 12f
        const val CHAR_WIDTH = 7.2 // Courier 12: 10 characters per inch.
        const val LINE_HEIGHT = 12.0 // 6 lines per inch.
        const val PAGE_WIDTH = 612.0
        const val PAGE_HEIGHT = 792.0
        const val TOP_MARGIN = 72.0
        const val BOTTOM_MARGIN = 72.0
        const val LEFT_MARGIN = 108.0
        const val RIGHT_MARGIN = 72.0
        const val TITLE_DROP_LINES = 18
        /** Courier's standard encoding: what WinAnsi can show; anything else is dropped rather than thrown. */
        val PRINTABLE = 32..255

        fun PDPageContentStream.text(x: Double, y: Double, text: String) {
            beginText()
            newLineAtOffset(x.toFloat(), y.toFloat())
            showText(text.filter { it.code in PRINTABLE })
            endText()
        }
    }
}
