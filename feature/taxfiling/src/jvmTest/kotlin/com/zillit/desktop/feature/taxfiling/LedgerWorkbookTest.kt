package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.ledgerWorkbook
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ledger export is a real workbook: the parts a spreadsheet application
 * looks for, well-formed XML in each, and the figures as numbers.
 */
class LedgerWorkbookTest {

    private fun parts(bytes: ByteArray): Map<String, String> {
        val parts = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                parts[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        return parts
    }

    private val line = LedgerLine(
        box = "box1",
        accountCode = "4000",
        debit = 0.0,
        credit = 12_000_000.5,
        periodYear = 2026,
        periodMonth = 4,
        tracking = mapOf("dept" to "CAM"),
        memo = "Invoice 12, \"rush\" <fee> & more",
    )

    @Test
    fun `the workbook carries every part a spreadsheet opens`() {
        val parts = parts(ledgerWorkbook(listOf(line)))

        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
            ),
            parts.keys,
        )
        // Every part parses: one stray character and the whole file refuses to open.
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        parts.values.forEach { xml -> builder.parse(ByteArrayInputStream(xml.encodeToByteArray())) }
        assertTrue(parts.getValue("xl/workbook.xml").contains("name=\"Ledger\""))
    }

    /** The web's columns, in order; amounts as plain numbers; free text escaped and cleaned. */
    @Test
    fun `the sheet holds the web's columns with numbers as numbers`() {
        val sheet = parts(ledgerWorkbook(listOf(line))).getValue("xl/worksheets/sheet1.xml")

        listOf("Box", "Account code", "Debit", "Credit", "Period", "Tracking", "Memo").forEach { header ->
            assertTrue(sheet.contains(">$header</t>"), "missing header $header")
        }
        assertTrue(sheet.contains("<c r=\"D2\"><v>12000000.5</v></c>"))
        assertTrue(sheet.contains("<c r=\"C2\"><v>0</v></c>"))
        assertTrue(sheet.contains(">2026-04</t>"))
        assertTrue(sheet.contains(">dept:CAM</t>"))
        assertTrue(sheet.contains("Invoice 12, &quot;rush&quot; &lt;fee&gt; &amp; more</t>"))
    }
}
