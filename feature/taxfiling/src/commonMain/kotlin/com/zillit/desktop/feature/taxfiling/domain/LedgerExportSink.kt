package com.zillit.desktop.feature.taxfiling.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Where an export lands.
 *
 * The host's job, not this module's: writing to disk and handing a file to
 * the OS is platform work, and the module only knows what the file says.
 */
fun interface LedgerExportSink {
    suspend fun save(fileName: String, csv: String): ZillitResult<Unit>
}

private const val QUOTE = '"'
private val HEADERS = listOf(
    "Box", "Account code", "Debit", "Credit", "Period", "Tracking", "Memo",
)

/**
 * The ledger behind the boxes, as a spreadsheet.
 *
 * CSV rather than the web's `.xlsx`: the desktop has no workbook writer, and
 * every accounting package opens either. The columns are the web's, in the
 * web's order, so a person who has used both is looking at the same sheet.
 */
fun ledgerCsv(rows: List<LedgerLine>): String = buildString {
    appendLine(HEADERS.joinToString(",") { it.csvCell() })
    rows.forEach { row ->
        appendLine(
            listOf(
                row.box,
                row.accountCode,
                row.debit.toString(),
                row.credit.toString(),
                row.periodLabel(),
                row.tracking.entries.joinToString(" ") { "${it.key}:${it.value}" },
                row.memo,
            ).joinToString(",") { it.csvCell() },
        )
    }
}

/** `2026-04`, or blank when the row carries no period. */
private fun LedgerLine.periodLabel(): String {
    val year = periodYear ?: return ""
    val month = periodMonth ?: return year.toString()
    return "$year-${month.toString().padStart(2, '0')}"
}

/**
 * Always quoted, with inner quotes doubled.
 *
 * A memo is free text an accountant typed, and it will contain commas,
 * newlines and quotation marks. Quoting only when it looks necessary is how a
 * ledger export silently gains a column halfway down.
 */
private fun String.csvCell(): String = QUOTE + replace("\"", "\"\"") + QUOTE

/** `vat-ledger_18A1_2026-09-10.csv`. */
fun ledgerFileName(periodKey: String, today: String): String {
    val key = periodKey.filter { it.isLetterOrDigit() }.ifBlank { "period" }
    return "vat-ledger_${key}_$today.csv"
}
