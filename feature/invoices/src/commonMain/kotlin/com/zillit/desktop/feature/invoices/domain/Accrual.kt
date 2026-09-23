package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
enum class AccrualStatus(val wire: String, private val labelKey: String) {
    Accrued("accrued", S.desktop_accrued),
    Reversed("reversed", S.desktop_reversed),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): AccrualStatus = entries.firstOrNull { it.wire == wire } ?: Accrued
    }
}

/** The web's three chips over the accruals list. */
enum class AccrualFilter(private val labelKey: String, val status: AccrualStatus?) {
    All(S.all, null),
    Active(S.active, AccrualStatus.Accrued),
    Reversed(S.desktop_reversed, AccrualStatus.Reversed),
    ;

    val label: String get() = str(labelKey)

    fun keeps(accrual: Accrual): Boolean = status == null || accrual.status == status
}
