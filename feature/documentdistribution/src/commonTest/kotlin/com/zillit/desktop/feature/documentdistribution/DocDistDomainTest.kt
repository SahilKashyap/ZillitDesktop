package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.Csv
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.canWatermark
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail
import com.zillit.desktop.feature.documentdistribution.domain.parseAddressList
import com.zillit.desktop.feature.documentdistribution.domain.watermarkedFilename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure rules ported from the web's `utils/` — pinned so they cannot drift. */
class DocDistDomainTest {

    @Test
    fun `an address with a made-up TLD is refused, as the backend refuses it`() {
        assertTrue(isValidEmail("ada@example.com"))
        assertTrue(isValidEmail("  grace@studio.co.uk "))
        // Passes a shape check; the server drops it and fails the whole send.
        assertFalse(isValidEmail("wdd@aa.dd"))
        assertFalse(isValidEmail("no-at-sign"))
        assertFalse(isValidEmail(""))
    }

    @Test
    fun `pasted addresses split on the separators mail clients use, keeping names`() {
        val parsed = parseAddressList("Ada Lovelace <ada@x.com>; grace@x.com, alan@x.com\ttom@x.com")
        assertEquals(listOf("ada@x.com", "grace@x.com", "alan@x.com", "tom@x.com"), parsed.map { it.email })
        assertEquals("Ada Lovelace", parsed.first().name)
    }

    @Test
    fun `only PDF and the bitmap formats the server stamps are watermarkable`() {
        assertTrue(canWatermark("application/pdf"))
        assertTrue(canWatermark("image/png; charset=binary"))
        assertTrue(canWatermark(null, "scan.JPG"))
        assertFalse(canWatermark("image/gif"))
        assertFalse(canWatermark("application/msword", "memo.doc"))
    }

    @Test
    fun `the upload allow-list admits by extension or MIME and partitions a drop`() {
        val pdf = LocalFile("call.pdf", "application/octet-stream", ByteArray(1))
        val exe = LocalFile("setup.exe", "application/octet-stream", ByteArray(1))
        val heic = LocalFile("photo", "image/heic", ByteArray(1))
        val (accepted, rejected) = SupportedUploads.partition(listOf(pdf, exe, heic))
        assertEquals(listOf(pdf, heic), accepted)
        assertEquals(listOf(exe), rejected)
        assertEquals(FileKind.ImageUnsupported, fileKindOf("image/heic", "photo"))
        assertEquals(FileKind.Excel, fileKindOf(null, "budget.xlsx"))
        assertEquals("call_watermarked.pdf", watermarkedFilename("call.pdf"))
    }

    @Test
    fun `a CSV with headers, quotes and duplicates parses into tagged contacts`() {
        val text = "\uFEFFname,email,job\r\n\"Doe, Jane\",JANE@x.com,Director\n" +
            "John,john@x.com,\nJohn,john@x.com,Producer\n,bad@aa.dd,\n"
        val rows = Csv.toContacts(text, existingEmails = listOf("jane@x.com"))
        assertEquals(listOf("jane@x.com", "john@x.com", "john@x.com", "bad@aa.dd"), rows.map { it.email })
        assertEquals("Doe, Jane", rows[0].name)
        assertTrue(rows[0].duplicate, "already on the list")
        assertFalse(rows[1].duplicate)
        assertTrue(rows[2].duplicate, "repeated within the file")
        assertFalse(rows[3].valid)
    }

    @Test
    fun `a Google contact export yields one row per populated email column`() {
        val text = "First Name,Last Name,E-mail Address,E-mail 2 Address\n" +
            "Ada,Lovelace,ada@x.com,ada2@x.com\nGrace,Hopper,grace@x.com,\n"
        val rows = Csv.toContacts(text)
        assertEquals(listOf("ada@x.com", "ada2@x.com", "grace@x.com"), rows.map { it.email })
        assertEquals("Ada Lovelace", rows[0].name)
    }

    @Test
    fun `a single-column file is treated as emails and exports round-trip`() {
        assertEquals(listOf("a@x.com", "b@x.com"), Csv.toContacts("a@x.com\nb@x.com").map { it.email })
        val csv = Csv.recipients(listOf(Recipient("a@x.com", "Alex, Jr.", "AD")))
        assertEquals("name,email,job\n\"Alex, Jr.\",a@x.com,AD", csv)
        assertEquals("Alex, Jr.", Csv.toContacts(csv).single().name)
    }

    @Test
    fun `html bodies flatten to text and plain text becomes paragraphs`() {
        assertEquals(
            "Hi,\n\nPlease find attached.\nThanks",
            HtmlText.toPlainText("<p>Hi,</p><p>Please find attached.<br>Thanks</p>"),
        )
        assertEquals(
            "<p>Hi &amp; hello</p><p>line one<br>line two</p>",
            HtmlText.plainToHtml("Hi & hello\n\nline one\nline two"),
        )
        assertEquals("plain text", HtmlText.toPlainText("plain text"))
        assertEquals("Please find att…", HtmlText.snippet("<div>Please   find <b>attached</b></div>", 15))
    }
}
