package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartments
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import kotlinx.coroutines.Job

/**
 * The production's reference data — currencies, departments, the chart of
 * accounts — and the receipt picker, each through its host seam
 * ([CashReferenceSources]).
 *
 * Read quietly: none of it is a page, and a failure degrades to the empty
 * value rather than an error banner over whatever page is open.
 */
internal class ReferenceDesk(private val host: CashHost, private val sources: CashReferenceSources) {

    private var chartJob: Job? = null

    /** Currencies and departments, once per production — from `start`. */
    fun load() {
        chartJob?.cancel()
        host.work {
            val loaded = sources.currencies()
            host.update { copy(currencies = loaded) }
        }
        host.work {
            val loaded = sources.departments()
            host.update { copy(departments = loaded, assignees = CashDepartments.withIds(assignees, loaded)) }
        }
    }

    /**
     * The chart of accounts, once per production. An empty answer is taken
     * for a failed read and left unread, so a later page asks again.
     */
    fun loadChart() {
        if (host.state.chartAccounts != null || chartJob?.isActive == true) return
        chartJob = host.work {
            val loaded = sources.chartAccounts()
            if (loaded.isNotEmpty()) host.update { copy(chartAccounts = loaded) }
        }
    }

    /**
     * Picks a receipt file and stores it against row [index] of the Submit
     * form — the object the claim route takes, and its key. A cancelled
     * picker changes nothing.
     */
    fun attachReceipt(index: Int) {
        val picker = sources.uploader ?: return host.refuse(str(S.desktop_po_attachments_unavailable))
        if (host.state.attachingReceipt != null) return
        host.update { copy(attachingReceipt = index) }
        host.work {
            val picked = picker.pick()
            val stored = (picked as? ZillitResult.Success)?.data
            host.update {
                val receipts = if (stored == null) {
                    draft.receipts
                } else {
                    draft.receipts.mapIndexed { i, receipt ->
                        if (i != index) {
                            receipt
                        } else {
                            receipt.copy(
                                attachment = stored,
                                attachmentKey = stored.media,
                                attachmentName = stored.name,
                            )
                        }
                    }
                }
                copy(attachingReceipt = null, draft = draft.copy(receipts = receipts))
            }
            (picked as? ZillitResult.Failure)?.let { host.report(it.error) }
        }
    }
}
