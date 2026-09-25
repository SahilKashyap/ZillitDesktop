package com.zillit.desktop.feature.invoices.domain

/**
 * `GET /invoices/posted` — the rows, newest posting first, and the `total`
 * the endpoint reports, so a ledger bigger than the page says "Showing N of
 * {total}" instead of silently stopping (`PostedPage.jsx:193-210, 272`).
 */
data class PostedLedger(val rows: List<Invoice> = emptyList(), val total: Int = rows.size) {
    val truncated: Boolean get() = rows.size < total

    companion object {
        /** What the page reads — the web's `perPage: 500`. */
        const val PAGE_SIZE = 500

        /**
         * When an invoice reached the ledger, for the newest-first order —
         * the web's `postedTs`: paid, else effective, else invoice date, else
         * last updated.
         */
        fun postedAt(invoice: Invoice): Long =
            invoice.paidAtMs ?: invoice.effectiveDateMs ?: invoice.invoiceDateMs ?: invoice.updatedAtMs ?: 0L

        /** Newest first; ties keep the server's order, as a stable sort does on the web. */
        fun sorted(rows: List<Invoice>): List<Invoice> = rows.sortedByDescending(::postedAt)
    }
}
