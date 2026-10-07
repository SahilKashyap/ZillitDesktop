package com.zillit.desktop.feature.permissiongrid.domain

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The smallest workbook Excel, Numbers and LibreOffice all open: a content-type
 * map, two relationship parts, the workbook, a stylesheet with a bold header,
 * and one sheet of inline strings — no shared-string table to keep in step.
 */
actual fun rightsWorkbook(sheetName: String, header: List<String>, rows: List<List<String>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.part("[Content_Types].xml", CONTENT_TYPES)
        zip.part("_rels/.rels", ROOT_RELS)
        zip.part("xl/workbook.xml", workbook(sheetName))
        zip.part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        zip.part("xl/styles.xml", STYLES)
        zip.part("xl/worksheets/sheet1.xml", sheet(header, rows))
    }
    return out.toByteArray()
}

private fun ZipOutputStream.part(name: String, xml: String) {
    putNextEntry(ZipEntry(name))
    write(xml.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun sheet(header: List<String>, rows: List<List<String>>): String = buildString {
    append(XML_HEAD)
    append("<worksheet xmlns=\"$MAIN_NS\"><sheetData>")
    append("<row r=\"1\">")
    header.forEachIndexed { column, text -> append(textCell(column, 1, text, HEADER_STYLE)) }
    append("</row>")
    rows.forEachIndexed { index, cells ->
        val r = index + 2
        append("<row r=\"$r\">")
        cells.forEachIndexed { column, text -> append(textCell(column, r, text)) }
        append("</row>")
    }
    append("</sheetData></worksheet>")
}

/** `A`…`Z`, `AA`…: a production runs forty tools, three columns each. */
internal fun columnName(index: Int): String {
    var n = index + 1
    val name = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % LETTERS
        name.insert(0, 'A' + rem)
        n = (n - 1) / LETTERS
    }
    return name.toString()
}

private fun textCell(column: Int, row: Int, value: String, style: Int = 0): String {
    val styled = if (style == 0) "" else " s=\"$style\""
    return "<c r=\"${columnName(column)}$row\" t=\"inlineStr\"$styled>" +
        "<is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"
}

/** XML-escaped, with the control characters XML 1.0 forbids removed. */
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

/** Excel refuses a sheet name over 31 characters or carrying any of `[]:*?/\`. */
private fun workbook(sheetName: String): String {
    val safe = sheetName.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(MAX_SHEET_NAME).ifBlank { "Sheet1" }
    return XML_HEAD +
        "<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\">" +
        "<sheets><sheet name=\"${escape(safe)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
        "</workbook>"
}

private const val LETTERS = 26
private const val MAX_SHEET_NAME = 31
private const val FIRST_PRINTABLE = 0x20
private const val HEADER_STYLE = 1

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
