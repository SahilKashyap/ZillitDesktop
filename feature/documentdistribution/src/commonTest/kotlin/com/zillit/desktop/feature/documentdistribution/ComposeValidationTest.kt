package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.MediaKind
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the composer refuses to send, and what it stamps when it does. */
class ComposeValidationTest {

    @Test
    fun `a send with no recipients is refused`() {
        val draft = NewDistribution(
            subject = "Call sheet",
            bodyHtml = "",
            to = emptyList(),
            attachmentIds = listOf("doc1"),
        )

        assertEquals("Add at least one recipient.", draft.validationError())
    }

    @Test
    fun `a send with no attachments is refused`() {
        // This tool distributes documents. An empty send is a mail, and there
        // is a mail client one window over.
        val draft = NewDistribution(
            subject = "Call sheet",
            bodyHtml = "",
            to = listOf(Recipient("ada@example.com")),
        )

        assertEquals("Attach at least one document.", draft.validationError())
    }

    @Test
    fun `an empty subject is allowed`() {
        // Deliberately not a rule: re-issuing yesterday's call sheet under an
        // empty subject is ordinary, and the server fills in "(no subject)".
        val draft = NewDistribution(
            subject = "",
            bodyHtml = "",
            to = listOf(Recipient("ada@example.com")),
            attachmentIds = listOf("doc1"),
        )

        assertNull(draft.validationError())
    }

    @Test
    fun `a malformed address is named in the message`() {
        val draft = NewDistribution(
            subject = "Call sheet",
            bodyHtml = "",
            to = listOf(Recipient("ada@example.com"), Recipient("not-an-address")),
            attachmentIds = listOf("doc1"),
        )

        val problem = assertNotNull(draft.validationError())
        // Naming the offender matters: a sender pasting forty addresses cannot
        // find the bad one from "invalid recipient".
        assertTrue(problem.contains("not-an-address"))
    }

    @Test
    fun `cc and bcc are validated too`() {
        val draft = NewDistribution(
            subject = "Call sheet",
            bodyHtml = "",
            to = listOf(Recipient("ada@example.com")),
            cc = listOf(Recipient("broken@")),
            attachmentIds = listOf("doc1"),
        )

        assertNotNull(draft.validationError())
    }

    @Test
    fun `an ephemeral attachment counts as an attachment`() {
        val draft = NewDistribution(
            subject = "One-off",
            bodyHtml = "",
            to = listOf(Recipient("ada@example.com")),
            ephemeralAttachmentIds = listOf("upload1"),
        )

        assertNull(draft.validationError())
    }

    @Test
    fun `only watermark-capable attachments that were ticked are stamped`() {
        val pdf = LibraryDocument(id = "pdf", name = "script.pdf", mediaKind = MediaKind.Pdf)
        val image = LibraryDocument(id = "img", name = "map.png", mediaKind = MediaKind.Image)
        val sheet = LibraryDocument(id = "xls", name = "budget.xlsx", mediaKind = MediaKind.Document)

        val composer = ComposerState(
            attachments = listOf(pdf, image, sheet),
            // The spreadsheet is ticked, but the pipeline cannot stamp one —
            // sending it in the watermark map would have the server reject the
            // whole distribution rather than skip that file.
            watermarked = setOf("pdf", "xls"),
        )

        assertEquals(setOf("pdf"), composer.effectiveWatermarks.keys)
    }

    @Test
    fun `the default stamp is the recipient's name`() {
        // ZL-19547. Changing this changes what leaves a production, so it is
        // pinned rather than left to the dialog's initialiser.
        val style = WatermarkStyle()

        assertEquals(WatermarkLine.RecipientName, style.line1)
        assertEquals("Ada Lovelace", style.render(listOf(Recipient("ada@x.co", "Ada Lovelace"))))
        assertEquals("2 RECIPIENTS", style.render(listOf(Recipient("a@x.co"), Recipient("b@x.co"))))
        assertEquals("RECIPIENT", style.render(emptyList()))
    }

    @Test
    fun `a custom two-line stamp renders both lines`() {
        val style = WatermarkStyle(
            line1 = WatermarkLine.Custom,
            line1Custom = "CONFIDENTIAL",
            line2 = WatermarkLine.Custom,
            line2Custom = "Do not forward",
        )

        assertEquals("CONFIDENTIAL\nDo not forward", style.render(emptyList()))
        assertEquals("CONFIDENTIAL / Do not forward", style.summary())
    }

    @Test
    fun `a pdf is recognised from its mime type even when typed as a document`() {
        // The backend issues `media_type: document` with `application/pdf`, and
        // a PDF gets its own icon on every other client.
        assertEquals(MediaKind.Pdf, MediaKind.of("document", "application/pdf"))
        assertEquals(MediaKind.Image, MediaKind.of("image", "image/png"))
        assertEquals(MediaKind.Pdf, MediaKind.of(null, null, "script.pdf"))
        assertEquals(MediaKind.Other, MediaKind.of(null, null, "notes"))
    }
}
