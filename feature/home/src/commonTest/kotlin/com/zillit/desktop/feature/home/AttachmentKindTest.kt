package com.zillit.desktop.feature.home

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.home.ui.FileHue
import com.zillit.desktop.feature.home.ui.fileKindOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a file chip says an attachment is.
 *
 * The board is where call sheets, schedules and contracts arrive, and a column
 * of identical paperclips is a column nobody can scan. The rules that matter:
 * the extension wins over the wire's content type, and an unknown file is named
 * rather than shrugged at.
 */
class AttachmentKindTest {

    @Test
    fun `documents, sheets and slides are told apart`() {
        assertEquals("PDF", fileKindOf("call-sheet-day-14.pdf").label)
        assertEquals("Document", fileKindOf("contract.docx").label)
        assertEquals("Spreadsheet", fileKindOf("budget.xlsx").label)
        assertEquals("Presentation", fileKindOf("pitch.key").label)
        assertEquals("Archive", fileKindOf("dailies.zip").label)
    }

    @Test
    fun `media keeps its own glyph`() {
        assertEquals(ZillitIcons.Photo, fileKindOf("slate.HEIC").icon)
        assertEquals(ZillitIcons.Play, fileKindOf("take3.mov").icon)
        assertEquals(ZillitIcons.Mic, fileKindOf("voice-note.m4a").icon)
        assertEquals(ZillitIcons.Grid, fileKindOf("schedule.csv").icon)
    }

    @Test
    fun `the extension is read regardless of case`() {
        assertEquals("PDF", fileKindOf("SCRIPT.PDF").label)
    }

    @Test
    fun `a name with dots in it is read from the last one`() {
        // Version numbers in filenames are the norm on a production.
        assertEquals("PDF", fileKindOf("callsheet.v2.final.pdf").label)
    }

    @Test
    fun `a file with no name falls back to the wire's subtype`() {
        // Some rows carry only `content_subtype`; a chip still has to say what
        // it is holding.
        assertEquals("PDF", fileKindOf("", subtype = "application/pdf").label)
        assertEquals("Image", fileKindOf("", subtype = "image/png").label)
    }

    @Test
    fun `an unknown extension names itself`() {
        // "XCF" tells the uploader something; "File" tells them nothing.
        assertEquals("XCF", fileKindOf("matte.xcf").label)
        assertEquals(ZillitIcons.Paperclip, fileKindOf("matte.xcf").icon)
    }

    @Test
    fun `the colour follows the family, not the glyph`() {
        // Documents and PDFs share a glyph — the design system has one page
        // icon — so the colour is what tells them apart at a glance.
        assertEquals(FileHue.Red, fileKindOf("script.pdf").hue)
        assertEquals(FileHue.Blue, fileKindOf("script.docx").hue)
        assertEquals(fileKindOf("script.pdf").icon, fileKindOf("script.docx").icon)
    }

    @Test
    fun `an unrecognised file is left uncoloured`() {
        // A colour here would be a claim about a family we could not identify.
        assertEquals(FileHue.Neutral, fileKindOf("matte.xcf").hue)
        assertEquals(FileHue.Neutral, fileKindOf("LICENCE").hue)
    }

    @Test
    fun `every recognised family is told apart by glyph or colour`() {
        // The pair is the identity. Two families sharing both would be two
        // things a reader cannot distinguish without reading the label.
        val samples = listOf(
            "a.pdf", "a.docx", "a.txt", "a.xlsx", "a.pptx",
            "a.zip", "a.png", "a.mp4", "a.mp3",
        )
        val marks = samples.map { fileKindOf(it) }.map { it.icon to it.hue }

        assertEquals(samples.size, marks.toSet().size)
    }

    @Test
    fun `something with no extension at all is still an attachment`() {
        assertEquals("Attachment", fileKindOf("LICENCE").label)
        assertEquals("Attachment", fileKindOf("").label)
    }
}
