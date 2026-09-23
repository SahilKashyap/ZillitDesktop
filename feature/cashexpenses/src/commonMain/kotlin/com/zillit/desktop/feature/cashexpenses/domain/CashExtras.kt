package com.zillit.desktop.feature.cashexpenses.domain

/**
 * The ceiling on a float request — the web's `request_cap`
 * (`ui/requestCap.js`), identical on the cash and card settings.
 *
 * Under `weekly_salary` the flat [maxAmount] doubles as the fallback for a
 * requester with no usable weekly rate, which is why the section asks for it
 * on both bases.
 */
data class RequestCap(
    val enabled: Boolean = false,
    val basis: String = MAX_AMOUNT,
    val maxAmount: Double = 0.0,
    val salaryMultiplier: Double = 1.0,
) {
    val isWeekly: Boolean get() = basis == WEEKLY_SALARY

    /** A switched-on cap of nothing would refuse every request, so it cannot be saved. */
    val blocked: Boolean
        get() = enabled && (maxAmount <= 0 || (isWeekly && salaryMultiplier <= 0))

    companion object {
        const val MAX_AMOUNT = "max_amount"
        const val WEEKLY_SALARY = "weekly_salary"
    }
}

/**
 * One auto-assignment rule for cash batches.
 *
 * `/api/v2/account-hub/assignment-rules`, module `cash_expenses`. Read off the
 * cash settings document (`assignment_rules`), written through the hub's own
 * route — the web's `PCSettingsPage` does both.
 */
data class CashAssignmentRule(
    val id: String,
    val departments: List<String> = emptyList(),
    val nominalCodes: List<String> = emptyList(),
    /** Blank while empty; the wire takes a number or null. */
    val amountMin: String = "",
    val assignTo: String = "",
    val isActive: Boolean = true,
    val priority: Int = 0,
    /** Whether the server holds this row; a new one has a local id. */
    val persisted: Boolean = false,
) {
    val amountMinValue: Double? get() = amountMin.trim().replace(",", "").toDoubleOrNull()
}

/** A production company a float can be pinned to — Production Setup's list. */
data class CashCompany(val id: String, val name: String, val country: String = "")

/**
 * A request for money into the float custodian — the web's Fund Requests.
 *
 * Raising one records nothing in the ledger; marking it received is what posts.
 */
data class FundRequest(
    val id: String,
    val fundAccount: String,
    val currency: String?,
    val amount: Double,
    val receivedAmount: Double?,
    /** `requested`, `received` or `cancelled` — the only three the service has. */
    val status: String,
    val requestedBy: String?,
    val requestedAt: Long?,
    val receivedBy: String?,
    val receivedAt: Long?,
) {
    val isOpen: Boolean get() = status == REQUESTED

    companion object {
        const val REQUESTED = "requested"
        const val RECEIVED = "received"
        const val CANCELLED = "cancelled"
    }
}

/** One message on a batch's query thread (`/account-hub/queries`). */
data class QueryMessage(val userId: String?, val text: String, val at: Long?)

/** A batch's query thread; [id] is null until the first message opens it. */
data class QueryThread(val id: String?, val messages: List<QueryMessage> = emptyList())

/**
 * A manual cash return, as the web's `RecordCashReturnModal` sends it.
 *
 * [reason] is the wire key — `close_full_return`, `continue_partial_return`,
 * `overspend_settlement`, `cancel_float_return`, or `close_other` /
 * `continue_other` for "Other".
 */
data class CashReturn(
    val amount: Double,
    val receivedDate: Long,
    val reason: String,
    val notes: String?,
)

/**
 * What a post sends with it.
 *
 * Post & Ledger sends the batch's claims and the ledger date; the senior
 * sign-off sends its notes and the date. The server refuses a post with no
 * `effective_date` — the desktop used to send `{note}` and nothing else.
 */
data class PostBatchRequest(
    val effectiveDate: Long,
    val claims: List<Claim>? = null,
    val seniorNotes: String? = null,
)

/** The register exports, PDF or spreadsheet. */
enum class ExportFormat(val wire: String) {
    Pdf("pdf"),
    Xlsx("xlsx"),
}
