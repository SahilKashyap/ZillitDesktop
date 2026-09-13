package com.zillit.desktop.feature.esignature.domain

/**
 * The bulk-send CSV reader — the web's `csvParse.js`, reduced to what the
 * send needs: the separator is sniffed (comma, semicolon or tab, so Excel's
 * regional exports all work), quoted cells may hold separators and doubled
 * quotes, a byte-order mark is dropped, and trailing blank rows — the usual
 * spreadsheet export noise — are ignored.
 */
object CsvParse {

    data class Result(
        val headers: List<String>,
        val rows: List<Map<String, String>>,
        val delimiter: Char,
    )

    fun parse(text: String): Result {
        val clean = text.removePrefix("﻿")
        val lines = splitRecords(clean)
        val delimiter = sniff(lines.firstOrNull().orEmpty())
        val records = lines.map { splitFields(it, delimiter) }
            .filter { record -> record.any { it.isNotBlank() } }
        val headers = records.firstOrNull()?.map { it.trim() } ?: emptyList()
        val rows = records.drop(1).map { record ->
            headers.mapIndexed { index, header -> header to record.getOrNull(index).orEmpty().trim() }.toMap()
        }
        return Result(headers, rows, delimiter)
    }

    /** Re-serialises only the rows the sender approved, comma-separated and quoted where needed. */
    fun serialise(headers: List<String>, rows: List<Map<String, String>>): String {
        val out = StringBuilder()
        out.append(headers.joinToString(",") { quote(it) }).append('\n')
        rows.forEach { row -> out.append(headers.joinToString(",") { quote(row[it].orEmpty()) }).append('\n') }
        return out.toString()
    }

    private fun sniff(headerLine: String): Char {
        val candidates = listOf(',', ';', '\t')
        return candidates.maxByOrNull { c -> headerLine.count { it == c } } ?: ','
    }

    /** Records end at a newline outside quotes. */
    private fun splitRecords(text: String): List<String> {
        val records = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    current.append(c)
                }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    records += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) records += current.toString()
        return records
    }

    private fun splitFields(record: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < record.length) {
            val c = record[i]
            when {
                c == '"' && inQuotes && i + 1 < record.length && record[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value
}
