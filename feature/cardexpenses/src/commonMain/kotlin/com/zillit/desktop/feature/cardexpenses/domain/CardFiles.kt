package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Where an export lands: saved on this machine and opened.
 *
 * A host seam, as Bank Reconciliation's `BankRecFiles` is — the file system
 * and the OS's "open with" belong to the application, not to this module.
 */
fun interface CardFiles {
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
