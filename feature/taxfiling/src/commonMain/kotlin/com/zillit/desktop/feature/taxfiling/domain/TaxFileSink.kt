package com.zillit.desktop.feature.taxfiling.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Where a file this tool produces lands — the web's browser download.
 *
 * The host's job, not this module's: writing to disk and handing a file to the
 * OS is platform work, and the module only knows what the file says.
 */
fun interface TaxFileSink {
    /**
     * Saves [bytes] as [fileName]; [open] hands the saved file to the OS as
     * well, for a file the accountant asked to look at rather than to keep.
     */
    suspend fun save(fileName: String, bytes: ByteArray, open: Boolean): ZillitResult<Unit>
}

/** The ledger export's columns, in the web's order and with the web's names. */
val LEDGER_HEADERS: List<String> = listOf("Box", "Account code", "Debit", "Credit", "Period", "Tracking", "Memo")

/**
 * The ledger lines behind the boxes, as an `.xlsx` workbook with one sheet
 * named "Ledger" — what the web builds with SheetJS.
 *
 * Debit and credit are written as numbers rather than text, so the sheet sums
 * without anyone converting a column first.
 */
expect fun ledgerWorkbook(rows: List<LedgerLine>): ByteArray

/**
 * `vat-ledger_18A1_2026-09-10_1430.xlsx` — the web's name, with the period key
 * kept to letters and digits so a key from the server cannot become a path.
 */
fun ledgerFileName(periodKey: String, stamp: String): String {
    val key = periodKey.filter { it.isLetterOrDigit() }.ifBlank { "period" }
    val suffix = stamp.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return if (suffix.isBlank()) "vat-ledger_$key.xlsx" else "vat-ledger_${key}_$suffix.xlsx"
}

/** `tax-filing-zillit-films-ltd-123456789.json` — the data-portability export's name. */
fun exportFileName(registration: TaxRegistration): String {
    val company = TaxFormat.slug(registration.companyName.ifBlank { "company" })
    val number = registration.registrationNumber.ifBlank { registration.id }.filter { it.isLetterOrDigit() }
    return "tax-filing-$company-$number.json"
}
