package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A purchase order as the vendor detail's history lists it — a row of
 * `GET /api/v2/purchase-orders?per_page=200` (`SuppliersPage.jsx:321-364`).
 */
data class VendorPo(
    val id: String,
    val poNumber: String = "",
    val vendorId: String = "",
    val description: String = "",
    val grossTotal: Double = 0.0,
    val currency: String = "",
    val status: String = "",
) {
    /** `resolvePoStatus` without a tier config: its label and pill tone key. */
    val statusLabel: String
        get() = when (statusKey) {
            "posted" -> str(S.ah_status_posted)
            "rejected" -> str(S.rejected)
            "approved" -> str(S.approved)
            "pending" -> str(S.pending)
            "acct_entered" -> str(S.desktop_acct_entered)
            "closed" -> str(S.ah_status_closed)
            "draft" -> str(S.draft)
            "" -> "—"
            else -> status.localised()
        }

    /** The web's status key: `queued` and `acct_entered` are one. */
    val statusKey: String
        get() = when (val s = status.trim().lowercase()) {
            "queued", "acct_entered" -> "acct_entered"
            else -> s
        }
}

/** One PO of the vendor's history: its value, what was invoiced against it, and the rest. */
data class VendorPoRow(
    val po: VendorPo,
    val invoiced: Double,
) {
    val remaining: Double get() = po.grossTotal - invoiced

    /** Rounded, capped at 100 — `Math.min(100, Math.round(invoiced / value × 100))`. */
    val percentUsed: Int
        get() = if (po.grossTotal > 0) minOf(FULL, kotlin.math.round(invoiced / po.grossTotal * FULL).toInt()) else 0

    private companion object {
        const val FULL = 100
    }
}

/** What the vendor detail shows under "Vendor History" (`SuppliersPage.jsx:282-410`). */
data class VendorHistory(
    val invoices: List<Invoice>,
    val purchaseOrders: List<VendorPoRow>,
) {
    val paid: List<Invoice> get() = invoices.filter { it.status == InvoiceStatus.Paid }

    /** Not paid, cancelled or rejected. */
    val outstanding: List<Invoice>
        get() = invoices.filter {
            it.status != InvoiceStatus.Paid && it.status != InvoiceStatus.Cancelled && it.status != InvoiceStatus.Rejected
        }

    /** Credits / Disputes: the disputed invoices, or none. */
    val disputedCount: Int get() = invoices.count { it.status == InvoiceStatus.Disputed }

    companion object {
        /**
         * The supplier's invoices — by vendor id, or by the supplier name the
         * invoice carries — and the vendor's orders with what their invoices
         * booked against each (`po_id`).
         */
        fun of(row: VendorRow, invoices: List<Invoice>, orders: List<VendorPo>): VendorHistory {
            val vendorId = row.vendor.id
            val name = row.vendor.name.trim().lowercase()
            val theirs = invoices.filter {
                (vendorId.isNotBlank() && it.vendorId == vendorId) ||
                    (name.isNotBlank() && it.supplierName.trim().lowercase() == name)
            }
            val invoicedByPo = theirs.filter { it.poId.isNotBlank() }
                .groupBy { it.poId }
                .mapValues { (_, rows) -> rows.sumOf { it.grossAmount } }
            val pos = if (vendorId.isBlank()) {
                emptyList()
            } else {
                orders.filter { it.vendorId == vendorId }.map { VendorPoRow(it, invoicedByPo[it.id] ?: 0.0) }
            }
            return VendorHistory(theirs, pos)
        }
    }
}

/** One of the five AI compliance checks the vendor detail runs (`SuppliersPage.jsx:893-954`). */
enum class VendorComplianceCheck(private val titleKey: String, private val descKey: String) {
    TaxId(S.desktop_inv_vendor_alert_tax_title, S.desktop_inv_vendor_alert_tax_desc),
    Bank(S.desktop_inv_vendor_alert_bank_title, S.desktop_inv_vendor_alert_bank_desc),
    Type(S.desktop_inv_vendor_alert_type_title, S.desktop_inv_vendor_alert_type_desc),
    Terms(S.desktop_inv_vendor_alert_terms_title, S.desktop_inv_vendor_alert_terms_desc),
    DefaultCode(S.desktop_inv_vendor_alert_code_title, S.desktop_inv_vendor_alert_code_desc),
    ;

    fun title(name: String): String = str(titleKey, name)

    val description: String get() = str(descKey)

    companion object {
        /** The checks [row] fails, in the web's order. */
        fun of(row: VendorRow): List<VendorComplianceCheck> = buildList {
            if (!row.hasTaxId) add(TaxId)
            if (!row.hasBank) add(Bank)
            if (row.vendor.type.isBlank()) add(Type)
            if (row.termsLabel == "—") add(Terms)
            if (row.vendor.defaultNominalCode.isBlank()) add(DefaultCode)
        }
    }
}
