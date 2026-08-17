package com.zillit.desktop.feature.purchaseorder.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * A purchase order: a commitment to a vendor, approved before the money is
 * spent rather than after.
 *
 * That ordering is the point of the tool and it shapes everything here — a PO
 * only becomes real when it has cleared its approval chain, and only reaches
 * the ledger once it is posted.
 */
data class PurchaseOrder(
    val id: String,
    val number: String,
    val vendorId: String?,
    val vendorName: String,
    val description: String,
    val departmentId: String?,
    val companyId: String?,
    val status: PoStatus,
    val currency: String?,
    val total: Double,
    val vatTreatment: String?,
    val nominalCode: String?,
    val episode: String?,
    val notes: String?,
    val effectiveDate: Long?,
    val createdAt: Long?,
    val raisedBy: String?,
    val assignedTo: String?,
    val reassignmentReason: String?,
    val deliveryAddress: String?,
    val lines: List<PoLine> = emptyList(),
    val approvals: List<PoApproval> = emptyList(),
    val attachmentCount: Int = 0,
) {
    /** Sum of the lines, for checking the header total against its detail. */
    val lineTotal: Double get() = lines.sumOf { it.total }

    /**
     * Whether the header total and the lines disagree.
     *
     * Surfaced rather than silently corrected: a mismatch usually means a line
     * was edited after the header was set, and which of the two is right is a
     * question only the person raising it can answer.
     */
    val totalsDisagree: Boolean
        get() = lines.isNotEmpty() && kotlin.math.abs(lineTotal - total) > PENNY

    private companion object {
        const val PENNY = 0.005
    }
}

/** One costed line of a purchase order. */
data class PoLine(
    val id: String?,
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
    val nominalCode: String?,
    val vatRate: Double?,
) {
    val total: Double get() = quantity * unitPrice
}

/** One step of a PO's approval chain, and whether it has been taken. */
data class PoApproval(
    val userId: String?,
    val name: String,
    val level: Int,
    val decision: String?,
    val note: String?,
    val at: Long?,
) {
    val decided: Boolean get() = !decision.isNullOrBlank()
}

/** Where a purchase order is in its lifecycle. */
enum class PoStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Submitted("submitted", "Submitted"),
    AwaitingApproval("awaiting_approval", "Awaiting approval"),
    Approved("approved", "Approved"),
    Rejected("rejected", "Rejected"),
    Queried("queried", "Queried"),
    Posted("posted", "Posted"),
    PartiallyInvoiced("partially_invoiced", "Part invoiced"),
    Closed("closed", "Closed"),
    Cancelled("cancelled", "Cancelled"),
    Unknown("", "Unknown"),
    ;

    /** Still open to being edited by whoever raised it. */
    val isEditable: Boolean get() = this == Draft || this == Rejected || this == Queried

    /** Committed: the vendor can be told to proceed. */
    val isCommitted: Boolean
        get() = this == Approved || this == Posted || this == PartiallyInvoiced

    val isFinished: Boolean get() = this == Closed || this == Cancelled

    companion object {
        fun from(wire: String?): PoStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** Who is looking at the purchase order tool. */
data class PoViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    val isProjectAdmin: Boolean = false,
    val enteredAsTool: Boolean = false,
) {
    /**
     * Whether this person processes other people's orders.
     *
     * Matched on the department containing `accounts`, for the reason given in
     * the cash module: productions name the department differently and an
     * equality check has demoted whole accounts teams before.
     */
    val isAccountant: Boolean
        get() = !enteredAsTool && departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /**
     * Production Accountant and Financial Controller see every order on the
     * production; everyone else sees their own and whatever is routed to them.
     */
    val hasFullAccess: Boolean
        get() = isProjectAdmin || designationIdentifier.normalisedRole().let { value ->
            value.isNotEmpty() && SENIOR_DESIGNATIONS.any { value.contains(it) }
        }

    /**
     * Whether [order] is this person's to act on.
     *
     * Three ways in: they raised it, it was assigned to them, or they hold
     * full access. Anything else is somebody else's order, and the queues
     * filter accordingly.
     */
    fun owns(order: PurchaseOrder): Boolean =
        hasFullAccess ||
            (order.raisedBy != null && order.raisedBy == userId) ||
            (order.assignedTo != null && order.assignedTo == userId)

    private companion object {
        const val ACCOUNTS = "accounts"
        val SENIOR_DESIGNATIONS = setOf("production accountant", "financial controller")
    }
}

/** Lowercased words, from either an identifier or a display name. */
internal fun String?.normalisedRole(): String =
    orEmpty().lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()

/** A purchase order as the form filled it in. */
data class NewPurchaseOrder(
    val vendorId: String?,
    val vendorName: String,
    val description: String,
    val departmentId: String?,
    val companyId: String?,
    val currency: String?,
    val nominalCode: String?,
    val episode: String?,
    val notes: String?,
    val effectiveDate: Long?,
    val lines: List<PoLine>,
) {
    val total: Double get() = lines.sumOf { it.total }

    /** The first reason this order cannot be raised, or null. */
    fun validationError(): String? = when {
        vendorName.isBlank() -> "Choose the vendor this order is with."
        description.isBlank() -> "Describe what is being ordered."
        lines.isEmpty() -> "Add at least one line."
        lines.any { it.description.isBlank() } -> "Every line needs a description."
        lines.any { it.total <= 0 } -> "Every line needs a quantity and a price."
        else -> null
    }
}

/** A vendor the production can raise orders against. */
data class Vendor(
    val id: String,
    val name: String,
    val currency: String?,
    val defaultNominalCode: String?,
)

/** One line of a PO's audit trail. */
data class PoHistoryEntry(
    val action: String,
    val userId: String?,
    val note: String?,
    val at: Long?,
)

/** Everything the purchase order tool asks the server for. */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface PurchaseOrderRepository {

    /** Every order this viewer may see, newest first. */
    suspend fun orders(status: PoStatus?): ZillitResult<List<PurchaseOrder>>

    /** Orders routed to this viewer for a decision. */
    suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>>

    /** Orders this viewer raised. */
    suspend fun myOrders(): ZillitResult<List<PurchaseOrder>>

    suspend fun order(id: String): ZillitResult<PurchaseOrder>

    suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>>

    suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit>

    suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    suspend fun approve(id: String, note: String?): ZillitResult<Unit>

    suspend fun reject(id: String, reason: String): ZillitResult<Unit>

    suspend fun post(id: String, note: String?): ZillitResult<Unit>

    suspend fun close(id: String, note: String?): ZillitResult<Unit>

    /** Closes several at once — the month-end sweep. */
    /**
     * Closes many orders into one accounting period.
     *
     * [effectiveDate] is the period the write-off lands in, not a comment —
     * see the implementation. Null leaves the period to the server.
     */
    suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit>

    suspend fun vendors(): ZillitResult<List<Vendor>>
}
