package com.zillit.desktop.feature.invoices.domain

/**
 * What a purchase order has committed but not yet been invoiced for — the
 * web's `AccrualsPage`, on `/invoices/accruals`.
 *
 * The server computes it per PO: the order's total, what has been invoiced
 * against it, and the difference that must be accrued so the cost report is
 * not short at period end.
 */
data class Accrual(
    val id: String,
    val poNumber: String = "",
    val vendorId: String = "",
    val vendorName: String = "",
    val description: String = "",
    val departmentId: String = "",
    val poTotal: Double = 0.0,
    val invoicedAmount: Double = 0.0,
    val accrualAmount: Double = 0.0,
    val currency: String = "",
    val status: AccrualStatus = AccrualStatus.Accrued,
) {
    /** How much of the order has been invoiced, as a fraction — the web's "Used" column. */
    val used: Double get() = if (poTotal <= 0.0) 0.0 else (invoicedAmount / poTotal).coerceIn(0.0, 1.0)
}

/** Whether an accrual still stands, or has been reversed once the invoice arrived. */
enum class AccrualStatus(val wire: String, val label: String) {
    Accrued("accrued", "Accrued"),
    Reversed("reversed", "Reversed"),
    ;

    companion object {
        fun from(wire: String?): AccrualStatus = entries.firstOrNull { it.wire == wire } ?: Accrued
    }
}

/** The web's three chips over the accruals list. */
enum class AccrualFilter(val label: String, val status: AccrualStatus?) {
    All("All", null),
    Active("Active", AccrualStatus.Accrued),
    Reversed("Reversed", AccrualStatus.Reversed),
    ;

    fun keeps(accrual: Accrual): Boolean = status == null || accrual.status == status
}
