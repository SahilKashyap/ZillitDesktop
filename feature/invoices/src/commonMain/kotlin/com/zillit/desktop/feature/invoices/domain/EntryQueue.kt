package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Invoice Entry — the third stage, where an approved invoice is coded and
 * posted to the ledger.
 *
 * The rules here are the web's `lib/entryAccess.js` and `EntryPage.jsx`, kept
 * in the domain so the screen, the bulk bar and the tests all read the same
 * one. Access is deliberately not a screen concern: a non-senior can only
 * touch what is assigned to them, and that has to hold for the row click, the
 * tick box and select-all alike.
 */
enum class EntryFilter(private val labelKey: String) {
    All(S.all),
    AssignedToMe(S.desktop_assigned_to_me),
    Unassigned(S.unassigned),
    Ready(S.ah_ready_to_post),
    NeedsReview(S.desktop_needs_review),
    ;

    val label: String get() = str(labelKey)

    fun keeps(invoice: Invoice, viewerId: String): Boolean = when (this) {
        All -> true
        AssignedToMe -> invoice.assignedTo.isNotBlank() && invoice.assignedTo == viewerId
        Unassigned -> invoice.assignedTo.isBlank()
        Ready -> invoice.status == InvoiceStatus.Approved || invoice.status == InvoiceStatus.Override
        NeedsReview -> invoice.status == InvoiceStatus.UnderReview
    }
}

/** The entry queue's sort box. */
enum class EntrySort(private val labelKey: String) {
    Default(S.desktop_sort_default),
    AmountHighLow(S.desktop_amount_high_low),
    AmountLowHigh(S.desktop_amount_low_high),
    VendorAZ(S.ah_sort_vendor_asc),
    ;

    val label: String get() = str(labelKey)
}

/**
 * Whether this viewer may open and act on the row.
 *
 * Senior accountants (PA/FC by designation, or flagged senior by the module
 * settings) see everything; everyone else only what is assigned to them.
 * There is no admin tier: owning the production is not running its ledger.
 */
fun canAccessEntryRow(invoice: Invoice, isSenior: Boolean, viewerId: String): Boolean =
    isSenior || (invoice.assignedTo.isNotBlank() && invoice.assignedTo == viewerId)

/** Someone the queue can be handed to — the production's accounts team. */
data class InvoiceAssignee(
    val id: String,
    val name: String,
    val role: String = "",
) {
    val label: String get() = if (role.isBlank()) name else "$name — $role"
}

/**
 * Who the tool can name and hand work to — the host's crew list.
 *
 * Two lists, because the web reads two: an invoice is assigned to the
 * accounts team (`AVAILABLE_USERS`), while a payment run can be signed off by
 * anyone on the production (`USERS`) — a producer who never touches an
 * invoice still authorises the money leaving.
 */
class InvoiceDirectory(
    val accountsTeam: () -> List<InvoiceAssignee> = { emptyList() },
    val everyone: () -> List<InvoiceAssignee> = { emptyList() },
)

/** The reasons the web offers for handing an invoice on; "Other" asks for words. */
enum class AssignmentReason(val wire: String, private val labelKey: String) {
    Complex("Complex Invoice - Needs Senior Review", S.desktop_inv_reason_complex),
    Workload("Workload Balancing", S.desktop_inv_reason_workload),
    Cover("Holiday/Absence Cover", S.desktop_inv_reason_cover),
    Escalation("Accountant Escalation", S.desktop_inv_reason_escalation),
    Other("", S.desktop_other_custom_reason),
    ;

    val label: String get() = str(labelKey)

    fun needsNotes(): Boolean = this == Other
}
