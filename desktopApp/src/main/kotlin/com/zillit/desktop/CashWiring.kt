package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cashexpenses.data.CashBinaryPost
import com.zillit.desktop.feature.cashexpenses.data.CashRepositoryImpl
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.ui.CashFiles
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore

/**
 * Petty Cash's host seams for the register exports — the web's Floats and
 * Receipts Register (PDF / XLSX) and the History export.
 *
 * The service answers those with the file's bytes, which the module's JSON
 * client cannot read, so the byte POST comes from the host, as Bank
 * Reconciliation's does. Built here rather than in the graph's constructor
 * because [postForBytes] needs the ready graph.
 */
internal fun AppGraph.Ready.cashRepositoryWithExports(): CashRepository =
    CashRepositoryImpl(apiClient, config, CashBinaryPost { url, body -> postForBytes(url, body) })

/** Exports land in Downloads and open, as every other export in this application does. */
internal fun cashFiles() = CashFiles { fileName, bytes ->
    when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
        is ZillitResult.Failure -> saved
        is ZillitResult.Success -> {
            openSavedFile(saved.data)
            ZillitResult.Success(Unit)
        }
    }
}
