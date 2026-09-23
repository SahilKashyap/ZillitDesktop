package com.zillit.desktop.feature.cardexpenses.domain

/**
 * A receipt's query thread — the Account Hub's shared `/queries` service, the
 * web's `QueryPanel` (`ReceiptDetailModal.jsx:406-415`,
 * `ProcessReceiptModal.jsx:519-522`).
 *
 * One thread per entity; [id] is null until the first message creates it.
 */
data class QueryThread(val id: String? = null, val messages: List<QueryMessage> = emptyList())

/** One message on a thread: who asked, what, and when. */
data class QueryMessage(val userId: String, val text: String, val at: Long?)

/**
 * A request for money to be paid into a production bank account — the
 * register's "Funds" (`RequestFundsModal.jsx`).
 *
 * Raising one has no accounting effect; marking it received is what posts:
 * debit the bank's nominal, credit [fundAccount].
 */
data class FundRequest(
    val id: String,
    val bankId: String,
    val fundAccount: String,
    val amount: Double,
    val receivedAmount: Double?,
    val currency: String?,
    /** `requested`, `received` or `cancelled` — the service has exactly three. */
    val status: String,
    val requestedBy: String?,
    val requestedAt: Long?,
    val receivedBy: String?,
    val receivedAt: Long?,
) {
    val open: Boolean get() = status == REQUESTED

    companion object {
        const val REQUESTED = "requested"
        const val RECEIVED = "received"
        const val CANCELLED = "cancelled"
    }
}

/** A production bank account money can be requested into; supplied by the host. */
data class CardBank(val id: String, val name: String, val currency: String? = null)

/** The fund request form. */
data class FundRequestDraft(val bankId: String = "", val fundAccount: String = "", val amount: String = "") {
    val amountValue: Double get() = amount.trim().replace(",", "").toDoubleOrNull() ?: 0.0

    /** The web's `canSubmit`: a bank, an account to draw from, and a positive amount. */
    val complete: Boolean get() = bankId.isNotBlank() && fundAccount.isNotBlank() && amountValue > 0
}
