package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.localization.localised
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
    /** The status as the server spelled it; the web labels any status it is sent (`formatLabel`). */
    val statusRaw: String = "",
) {
    /** How much of the order has been invoiced, as a fraction, clamped to 0–1. */
    val used: Double get() = if (poTotal <= 0.0) 0.0 else (invoicedAmount / poTotal).coerceIn(0.0, 1.0)

    /**
     * The Used column's figure — `invoiced / po_total × 100`, **not** clamped:
     * an over-invoiced order reads "132.4%" (`AccrualsPage.jsx:337, 527-533`).
     */
    val usedPercent: Double get() = if (poTotal > 0.0) invoicedAmount / poTotal * PERCENT else 0.0

    /** "132.4%" — one decimal, as `toFixed(1)`. */
    val usedLabel: String get() = Accruals.percentLabel(usedPercent)

    /** The status pill's words: the known status, or the server's own humanised. */
    val statusLabel: String
        get() = when {
            statusRaw.isBlank() || AccrualStatus.entries.any { it.wire == statusRaw } -> status.label
            else -> statusRaw.localised()
        }

    private companion object {
        const val PERCENT = 100.0
    }
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

/** The web's four chips over the accruals list (`AccrualsPage.jsx:55-60`). */
enum class AccrualFilter(private val labelKey: String, val status: AccrualStatus?) {
    All(S.all, null),
    Active(S.active, AccrualStatus.Accrued),
    Reversed(S.desktop_reversed, AccrualStatus.Reversed),

    /** `accrual_amount > 50000` — the web's "High Value (>£50k)". */
    HighValue(S.desktop_inv_accruals_high_value, null),
    ;

    val label: String get() = str(labelKey)

    fun keeps(accrual: Accrual): Boolean = when (this) {
        HighValue -> accrual.accrualAmount > Accruals.HIGH_VALUE
        else -> status == null || accrual.status == status
    }
}

/** The Sort select — Accrual ↓ (the default), Accrual ↑, Vendor A-Z, % Invoiced ↓ (`AccrualsPage.jsx:447-457`). */
enum class AccrualSort(private val labelKey: String) {
    AccrualDesc(S.desktop_inv_accrual_sort_desc),
    AccrualAsc(S.desktop_inv_accrual_sort_asc),
    VendorAZ(S.ah_sort_vendor_asc),
    UsedDesc(S.desktop_inv_accrual_sort_used),
    ;

    val label: String get() = str(labelKey)

    fun sort(rows: List<Accrual>, vendorName: (Accrual) -> String): List<Accrual> = when (this) {
        AccrualDesc -> rows.sortedByDescending { it.accrualAmount }
        AccrualAsc -> rows.sortedBy { it.accrualAmount }
        VendorAZ -> rows.sortedBy { vendorName(it).lowercase() }
        UsedDesc -> rows.sortedByDescending { it.usedPercent }
    }
}

/**
 * One accrual opened — `GET /invoices/accruals/:id`, unwrapped from
 * `{accrual, po, vendor, invoices}` (`AccrualsPage.jsx:79-110`).
 */
data class AccrualDetail(
    val accrual: Accrual,
    val po: AccrualPo? = null,
    val vendor: AccrualVendor? = null,
    val invoices: List<Invoice> = emptyList(),
) {
    /** "PO-0042 — Lamps", the web's modal title. */
    val title: String
        get() = "${accrual.poNumber} — " + accrual.description.ifBlank { po?.description.orEmpty() }
            .ifBlank { str(S.desktop_inv_accrual_details) }
}

/** The order behind an accrual, as the detail reads it. */
data class AccrualPo(
    val poNumber: String = "",
    val status: String = "",
    val currency: String = "",
    val effectiveDateMs: Long? = null,
    val deliveryDateMs: Long? = null,
    val vatTreatment: String = "",
    val description: String = "",
    val notes: String = "",
) {
    /** POSTED is green; anything else amber — the web's pill. */
    val isPosted: Boolean get() = status.equals("POSTED", ignoreCase = true)
}

/** The vendor block: name, the address joined, and contact · email · phone. */
data class AccrualVendor(
    val name: String = "",
    val address: String = "",
    val contactLine: String = "",
)

object Accruals {
    /** The High Value chip's floor. */
    const val HIGH_VALUE = 50_000.0

    /**
     * The table as the web draws it (`AccrualsPage.jsx:354-384`): the search
     * over PO, vendor, description and the formatted PO total and accrual,
     * then the chip, then the department, then the sort.
     */
    @Suppress("LongParameterList") // One argument per control over the table.
    fun shown(
        rows: List<Accrual>,
        search: String,
        filter: AccrualFilter,
        departmentId: String?,
        sort: AccrualSort,
        vendorName: (Accrual) -> String,
        money: (Double, String) -> String,
    ): List<Accrual> {
        val needle = search.trim().lowercase()
        val kept = rows.filter { accrual ->
            val matches = needle.isEmpty() ||
                accrual.poNumber.ifBlank { "—" }.lowercase().contains(needle) ||
                vendorName(accrual).lowercase().contains(needle) ||
                accrual.description.lowercase().contains(needle) ||
                money(accrual.poTotal, accrual.currency).contains(needle) ||
                money(accrual.accrualAmount, accrual.currency).contains(needle)
            matches && filter.keeps(accrual) && (departmentId == null || accrual.departmentId == departmentId)
        }
        return sort.sort(kept, vendorName)
    }

    /** `pct.toFixed(1) + "%"`. */
    fun percentLabel(value: Double): String {
        val tenths = kotlin.math.round(value * TENTHS).toLong()
        val sign = if (tenths < 0) "-" else ""
        val abs = kotlin.math.abs(tenths)
        return "$sign${abs / TENTHS.toLong()}.${abs % TENTHS.toLong()}%"
    }

    private const val TENTHS = 10.0
}
