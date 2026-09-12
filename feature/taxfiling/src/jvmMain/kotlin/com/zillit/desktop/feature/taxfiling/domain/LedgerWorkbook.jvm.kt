package com.zillit.desktop.feature.taxfiling.domain

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The smallest workbook Excel, Numbers and LibreOffice all open: a content-type
 * map, two relationship parts, the workbook, a stylesheet with a bold header,
 * and one sheet of inline strings — no shared-string table to keep in step.
 */
actual fun ledgerWorkbook(rows: List<LedgerLine>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.part("[Content_Types].xml", CONTENT_TYPES)
        zip.part("_rels/.rels", ROOT_RELS)
        zip.part("xl/workbook.xml", WORKBOOK)
        zip.part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        zip.part("xl/styles.xml", STYLES)
        zip.part("xl/worksheets/sheet1.xml", sheet(rows))
    }
    return out.toByteArray()
}

private fun ZipOutputStream.part(name: String, xml: String) {
    putNextEntry(ZipEntry(name))
    write(xml.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun sheet(rows: List<LedgerLine>): String = buildString {
    append(XML_HEAD)
    append("<worksheet xmlns=\"$MAIN_NS\">")
    append("<cols>")
    COLUMN_WIDTHS.forEachIndexed { index, width ->
        append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
    }
    append("</cols><sheetData>")
    append("<row r=\"1\">")
    LEDGER_HEADERS.forEachIndexed { column, header -> append(textCell(column, 1, header, HEADER_STYLE)) }
    append("</row>")
    rows.forEachIndexed { index, line ->
        val r = index + FIRST_DATA_ROW
        append("<row r=\"$r\">")
        // In LEDGER_HEADERS order: Box, Account code, Debit, Credit, Period, Tracking, Memo.
        val cells = listOf<Any>(
            line.box, line.accountCode, line.debit, line.credit, line.periodLabel, line.trackingLabel, line.memo,
        )
        cells.forEachIndexed { column, value ->
            append(if (value is Double) numberCell(column, r, value) else textCell(column, r, value.toString()))
        }
        append("</row>")
    }
    append("</sheetData></worksheet>")
}

private fun reference(column: Int, row: Int): String = "${'A' + column}$row"

private fun textCell(column: Int, row: Int, value: String, style: Int = 0): String {
    val styled = if (style == 0) "" else " s=\"$style\""
    return "<c r=\"${reference(column, row)}\" t=\"inlineStr\"$styled>" +
        "<is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"
}

/** Plain decimal notation: `1.0E7` is a number to Kotlin and a string to some readers. */
private fun numberCell(column: Int, row: Int, value: Double): String {
    val safe = if (value.isFinite()) value else 0.0
    val text = BigDecimal.valueOf(safe).stripTrailingZeros().toPlainString()
    return "<c r=\"${reference(column, row)}\"><v>$text</v></c>"
}

/**
 * XML-escaped, with the control characters XML 1.0 forbids removed.
 *
 * A memo is free text, and one stray form-feed pasted into it would otherwise
 * make the whole workbook refuse to open.
 */
private fun escape(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when {
            char == '&' -> append("&amp;")
            char == '<' -> append("&lt;")
            char == '>' -> append("&gt;")
            char == '"' -> append("&quot;")
            char == '\t' || char == '\n' || char == '\r' -> append(char)
            char.code < FIRST_PRINTABLE -> Unit
            else -> append(char)
        }
    }
}

private const val FIRST_PRINTABLE = 0x20
private const val FIRST_DATA_ROW = 2
private const val HEADER_STYLE = 1
private val COLUMN_WIDTHS = listOf(8, 16, 14, 14, 10, 30, 48)

private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

private const val CONTENT_TYPES = XML_HEAD +
    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
    "<Override PartName=\"/xl/workbook.xml\" " +
    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
    "<Override PartName=\"/xl/worksheets/sheet1.xml\" " +
    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
    "<Override PartName=\"/xl/styles.xml\" " +
    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
    "</Types>"

private const val ROOT_RELS = XML_HEAD +
    "<Relationships xmlns=\"$PKG_REL_NS\">" +
    "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
    "</Relationships>"

private const val WORKBOOK = XML_HEAD +
    "<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\">" +
    "<sheets><sheet name=\"Ledger\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
    "</workbook>"

private const val WORKBOOK_RELS = XML_HEAD +
    "<Relationships xmlns=\"$PKG_REL_NS\">" +
    "<Relationship Id=\"rId1\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
    "<Relationship Id=\"rId2\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>" +
    "</Relationships>"

private const val STYLES = XML_HEAD +
    "<styleSheet xmlns=\"$MAIN_NS\">" +
    "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
    "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
    "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
    "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
    "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
    "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
    "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
    "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
    "</styleSheet>"
