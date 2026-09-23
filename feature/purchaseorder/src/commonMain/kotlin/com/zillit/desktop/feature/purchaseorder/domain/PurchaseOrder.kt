package com.zillit.desktop.feature.purchaseorder.domain

import com.zillit.desktop.core.forms.CustomFieldGroup

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A purchase order: a commitment to a vendor, approved before the money is
 * spent rather than after.
 *
 * That ordering is the point of the tool and it shapes everything here — a PO
 * only becomes real when it has cleared its approval chain, and only reaches
 * the ledger once it is posted.
 */
@Serializable
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
    /**
     * The server-maintained gross (net + tax) — the figure the coded ledger
     * must reconcile to, and the one the Posted tab measures relief against.
     * Zero when the server sent none, which is why [total] stays the figure
     * every list shows: it falls back through the lines.
     */
    val grossAmount: Double = 0.0,
    /** Running invoiced-against-this-order sum, gross. The Posted tab's "Relieved". */
    val paidAmount: Double = 0.0,
    val paidAt: Long? = null,
    /**
     * When the order last reached its vendor's inbox, and who sent it.
     *
     * Approval does not email anybody, so this is the only record that the
     * vendor has seen the order at all — and the reason the send action is
     * one-shot everywhere except the processing page.
     */
    val emailAt: Long? = null,
    val emailBy: String? = null,
    val vatAmount: Double = 0.0,
    val deliveryDate: Long? = null,
    val deliveryAddressId: String? = null,
    val closureReason: String? = null,
    val closedBy: String? = null,
    val closedAt: Long? = null,
    val rejectedBy: String? = null,
    val rejectedAt: Long? = null,
    val rejectionReason: String? = null,
    val reassignedBy: String? = null,
    val reassignedAt: Long? = null,
    val updatedAt: Long? = null,
    val updatedBy: String? = null,
    /** The order's paperwork, as it rides the record — not a second call. */
    val attachments: List<PoAttachment> = emptyList(),
    /** The extra fields this production added to the form, grouped by section. */
    val customFields: List<CustomFieldGroup> = emptyList(),
    /**
     * Set when this order exists only on this computer so far — raised while
     * offline and waiting in the outbox. Such a row has no number and no
     * server id ([id] is the local operation's), and nothing can be done to it
     * but wait, retry or discard.
     */
    val local: LocalCopy? = null,
    /**
     * `delivery_address` exactly as the server sent it — an object from the
     * form, a plain string on older orders. Posting sends it back untouched
     * (the web's `poDetails.delivery_address`), so neither shape is flattened.
     */
    val deliveryAddressRaw: JsonElement? = null,
) {
    val isLocalOnly: Boolean get() = local != null

    /** The VAT treatment the processing page works under — `pending` until someone sets one (`mapApiPO`). */
    val vatTreatmentOrPending: String get() = vatTreatment?.takeIf { it.isNotBlank() } ?: "pending"

    /** Sum of the lines, for checking the header total against its detail. */
    val lineTotal: Double get() = lines.sumOf { it.total }

    /** The authoritative gross, server figure first. */
    val gross: Double get() = if (grossAmount > 0) grossAmount else total

    /** Still to be invoiced against — what the commitment is holding. */
    val remaining: Double get() = (gross - paidAmount).coerceAtLeast(0.0)

    /**
     * How much of a posted order has been invoiced against it.
     *
     * The web derives this label rather than storing it (`POPosted.enrichPO`),
     * and the Posted tab's filter chips read it, so it lives on the order.
     */
    val relief: PoRelief
        get() = when {
            status == PoStatus.Closed -> PoRelief.Closed
            paidAmount <= 0.0 -> PoRelief.Open
            paidAmount + PENNY >= gross -> PoRelief.FullyRelieved
            else -> PoRelief.PartiallyRelieved
        }

    /** Whether the vendor has been sent this order. */
    val emailed: Boolean get() = emailAt != null

    /**
     * The status as a list shows it, with the approval progress on a pending
     * order — the web's `resolvePoStatus`, `Pending (1/2)`.
     *
     * The two numbers come from two places, as they do on the web
     * (`lib/poStatus.js`): how many tiers have approved is the order's own
     * `approvals` (the server keeps one entry per *approved* tier, never a
     * placeholder for a pending one), and how many there are is the project's
     * tier configuration resolved for this order's department and amount. So
     * the old reading — the chain's length as the total — reported every
     * pending order as fully approved. With no configuration the label is
     * plain "Pending", which is the web's `totalT > 0` fallback.
     */
    fun statusLabel(tiers: PoApprovalTiers): String {
        if (status != PoStatus.AwaitingApproval) return status.label
        val total = tiers.resolve(departmentId, gross).size
        return if (total > 0) str(S.ah_status_pending_progress, approvals.count { it.decided }, total) else status.label
    }

    /**
     * Whether the header total and the lines disagree.
     *
     * Surfaced rather than silently corrected: a mismatch usually means a line
     * was edited after the header was set, and which of the two is right is a
     * question only the person raising it can answer.
     */
    val totalsDisagree: Boolean
        get() = lines.isNotEmpty() && kotlin.math.abs(lineTotal - total) > PENNY

    companion object {
        internal const val PENNY = 0.005
    }
}

/** An order that has not reached the server: where it is in the outbox. */
@Serializable
data class LocalCopy(
    val operationId: String,
    /** True once the server refused it and it needs the user; false while it waits or sends. */
    val failed: Boolean,
    val error: String? = null,
)

/**
 * One costed line of a purchase order.
 *
 * Carries the whole of the web editor's row, not just money: the expenditure
 * type gates the rental date pickers, the rental window gates the split-by-period
 * action, and a line with a [splitParentId] is a child that must never be summed
 * beside its parent.
 */
@Serializable
data class PoLine(
    val id: String?,
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
    val nominalCode: String?,
    val vatRate: Double?,
    /** The stored line total where the server gave one — a split child's own figure. */
    val amount: Double? = null,
    val expenditureType: String? = null,
    val taxType: String? = null,
    val departmentId: String? = null,
    val rentalStart: String? = null,
    val rentalEnd: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Set on a child produced by splitting a line — evenly, or by period.
     *
     * Children carry their parent's tax, so summing their rate too would
     * double-count it: every total on this tool skips them.
     */
    val splitParentId: String? = null,
    /**
     * The persisted mirror of a typed tax amount.
     *
     * Not an expense line: it exists so a tax the accountant typed (on lines
     * carrying no rate of their own) survives a save. Skipped by the net and
     * tax sums, counted by the gross — see [PoTotals].
     */
    val isTax: Boolean = false,
    /**
     * The line's analysis codes (`tracking_codes`, set id → node id) and its
     * extra fields (`custom_fields`), kept exactly as the server sent them.
     *
     * This client edits neither, but the processing page rewrites the whole
     * `line_items` array on every save — so anything a line carries that is not
     * written back is deleted. Held raw for that reason alone.
     */
    val trackingCodes: JsonObject? = null,
    val customFields: JsonArray? = null,
) {
    val total: Double get() = amount ?: (quantity * unitPrice)

    /** A child of a split — never summed beside its parent. */
    val isSplitChild: Boolean get() = !splitParentId.isNullOrBlank()

    /**
     * A rental with a window wide enough to divide.
     *
     * The web's `isRentalLine`: the exact expenditure type "Rent" plus both
     * dates, end strictly after start — the same gate the split-by-period
     * action uses, so the button is never offered on a window it cannot cut.
     */
    val isDivisibleRental: Boolean
        get() = expenditureType == RENTAL_EXPENDITURE_TYPE &&
            !rentalStart.isNullOrBlank() &&
            !rentalEnd.isNullOrBlank() &&
            rentalEnd > rentalStart
}

/** The expenditure type the rental date pickers and the period split hang off. */
const val RENTAL_EXPENDITURE_TYPE = "Rent"

/**
 * Net, tax and gross for a set of lines — one formula, every surface.
 *
 * Taxes parent lines only (a child carries its parent's rate) and skips the
 * persisted tax row, which is the mirror of this very sum. The web reached the
 * same shape after four hand-rolled reduces disagreed with each other.
 */
data class PoTotals(val net: Double, val tax: Double) {
    val gross: Double get() = net + tax

    /**
     * The part of [tax] the production can reclaim.
     *
     * Read off the tax *type*, not the rate: a line at 20% on a non-recoverable
     * type is 20% the production pays and never sees again, and treating it as
     * reclaimable overstates what comes back. A line whose type is not in
     * [recoverable] counts as not reclaimable, which is the safe direction.
     */
    fun reclaimable(lines: List<PoLine>, recoverable: Set<String>): Double = lines
        .filterNot { it.isSplitChild || it.isTax }
        .filter { it.taxType != null && it.taxType in recoverable }
        .sumOf { it.total * (it.vatRate ?: 0.0) / RECLAIM_PERCENT }

    companion object {
        private const val RECLAIM_PERCENT = 100.0

        fun of(lines: List<PoLine>): PoTotals {
            var net = 0.0
            var tax = 0.0
            lines.forEach { line ->
                if (line.isSplitChild || line.isTax) return@forEach
                net += line.total
                tax += line.total * (line.vatRate ?: 0.0) / PERCENT
            }
            return PoTotals(net = net, tax = tax)
        }

        private const val PERCENT = 100.0
    }
}

/**
 * How much of a posted order has been invoiced against it.
 *
 * The web's Posted tab shows these as both a column and its filter chips, and
 * the wording is theirs.
 */
enum class PoRelief(private val labelKey: String) {
    Open(S.dd_action_open),
    PartiallyRelieved(S.desktop_partially_relieved),
    FullyRelieved(S.desktop_fully_relieved),
    Closed(S.ah_status_closed),
    ;

    /** What the column and its chip show. */
    val label: String get() = str(labelKey)

    /**
     * Whether an order at this relief can still be closed — the web's Posted
     * row rule (`open` or `partially_relieved`). A fully relieved order has no
     * remaining commitment to release, so Close has nothing to do there.
     */
    val isCloseable: Boolean get() = this == Open || this == PartiallyRelieved
}

/**
 * One step of a PO's approval chain, and whether it has been taken.
 *
 * The web's shape is `{ user_id, tier_number, approved_at }` — an entry exists
 * only once that tier has approved — so [level] is the tier number and an entry
 * with a time and no stated decision is an approval.
 */
@Serializable
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

/**
 * Where a purchase order is in its lifecycle.
 *
 * The wire values are the server's, as Android's `POMapper` and the web's
 * `PurchaseOrdersModule` read them (upper-case on the wire, compared
 * case-insensitively): `DRAFT`, `PENDING` (awaiting approval), `ACCT_ENTERED`
 * (raised by accounts, no approval chain), `QUEUED` (approved and queued for
 * posting), `APPROVED`, `REJECTED`, `POSTED`, `CLOSED`, `CANCELLED`.
 */
@Serializable
enum class PoStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    AwaitingApproval("pending", S.pending),

    /**
     * `ACCT_ENTERED` — raised by accounts, with no approval chain.
     *
     * Two spellings on the wire and both read "Acct Entered": the web's
     * `mapApiPO` turns `ACCT_ENTERED` into `queued`, while half a dozen of its
     * own call sites still check the raw value. Both are modelled, both are
     * labelled the same, and every predicate here pairs them — because getting
     * it wrong fails silently on orders already entered in the books.
     */
    AccountsEntered("acct_entered", S.desktop_acct_entered),
    Approved("approved", S.approved),
    Queued("queued", S.desktop_acct_entered),
    Rejected("rejected", S.rejected),
    Posted("posted", S.ah_posted_label),
    Closed("closed", S.ah_status_closed),
    Cancelled("cancelled", S.cancelled),
    Unknown("", S.desktop_unknown),
    ;

    /** What a list shows for this status. */
    val label: String get() = str(labelKey)

    /** Still open to being edited by whoever raised it. */
    val isEditable: Boolean get() = this == Draft || this == Rejected

    /** Committed: the vendor can be told to proceed. */
    val isCommitted: Boolean
        get() = this == Approved || this == AccountsEntered || this == Queued || this == Posted

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
    /**
     * `posting_access` on `purchase_order_tool`, as the project's tool-rights
     * payload issued it.
     *
     * Only one gate reads it — the department view's All POs tab, which shows
     * every order on the production — and the web's hook fails **closed** while
     * the payload is in flight for that reason: a brief flicker-in for an
     * entitled user is cheaper than leaking the production's whole spend for
     * the length of a fetch. So the default here is false, not true.
     */
    val canPostPurchaseOrders: Boolean = false,
    /**
     * This person's department **id**, not its identifier.
     *
     * Both are needed and they are not interchangeable: the identifier is how
     * seniority is judged (it carries the word "accounts"), the id is what an
     * order's `department_id` holds and what the department list is scoped by.
     * The web keeps the same pair — `department_identifier` on the auth user,
     * `department_id` for every PO it writes.
     */
    val departmentId: String? = null,
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
     * Production Accountant or Financial Controller — senior by role.
     *
     * The same two designations the web treats as senior everywhere
     * (`isSeniorAccountant` in `accountHub/utils/po-permissions.js`): they are
     * final approver and payroll accountant across PO, invoices, card and cash
     * as well as here.
     *
     * **Exact**, never a substring. The web matches two identifiers with a set
     * lookup, and its own test says `designation_assistant_production_accountant_accounts`
     * is *not* senior. A `contains("production accountant")` handed every
     * assistant the Posted and Settings tabs, full access and delete-any. The
     * one allowance is spelling: the host passes the crew list's designation,
     * which arrives as the identifier, a translation key
     * (`production_accountant_label`) or a display name depending on the
     * payload, so all three are reduced to the same bare role before the
     * equality check — see [seniorRole].
     */
    val isSeniorAccountant: Boolean
        get() = designationIdentifier.seniorRole() in SENIOR_DESIGNATIONS

    /**
     * Who sees every order on the production — and it is not one rule.
     *
     * The web gates the two views differently and says so twice:
     * `canAccessAnyPO_accountant` is the senior designation **and explicitly
     * not `is_admin`** ("Admin alone must NOT bypass the assignment-based row
     * gate"), while `canAccessAnyPO_department` is `is_admin` alone. Folding
     * them into one `isProjectAdmin || senior` handed the accountant console
     * to any admin in the accounts department, which is the half the web
     * refuses.
     */
    val hasFullAccess: Boolean
        get() = if (isAccountant) isSeniorAccountant else isProjectAdmin

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

        /** The web's `PO_FULL_ACCESS_DESIGNATIONS`, as [seniorRole] reduces them. */
        val SENIOR_DESIGNATIONS = setOf("production_accountant", "financial_controller")
    }
}

/** Lowercased words, from either an identifier or a display name. */
internal fun String?.normalisedRole(): String =
    orEmpty().lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()

/**
 * A designation reduced to its bare role, for an **exact** comparison.
 *
 * `designation_production_accountant_accounts` (the web's identifier),
 * `production_accountant_label` (the crew list's translation key) and
 * `Production Accountant` (a display name) all become `production_accountant`;
 * `designation_assistant_production_accountant_accounts` becomes
 * `assistant_production_accountant`, which is a different role and stays one.
 * Only the wrapper the three spellings add is stripped — never a word of the
 * role itself.
 */
internal fun String?.seniorRole(): String {
    val words = normalisedRole().split(' ').filter { it.isNotEmpty() }.toMutableList()
    if (words.firstOrNull() == DESIGNATION_PREFIX) words.removeAt(0)
    if (words.lastOrNull() == LABEL_SUFFIX) words.removeAt(words.lastIndex)
    // The identifier's department suffix — never the whole role.
    if (words.size > 1 && words.last() == ACCOUNTS_SUFFIX) words.removeAt(words.lastIndex)
    return words.joinToString("_")
}

private const val DESIGNATION_PREFIX = "designation"
private const val LABEL_SUFFIX = "label"
private const val ACCOUNTS_SUFFIX = "accounts"

/** A purchase order as the form filled it in. */
@Serializable
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
    val vatTreatment: String? = null,
    val deliveryDate: Long? = null,
    /** The saved address this one came from, so the server can link them. */
    val deliveryAddressId: String? = null,
    val deliveryAddress: PoAddress? = null,
    /** Quotes and paperwork, uploaded before the order is sent. */
    val attachments: List<PoAttachment> = emptyList(),
    /**
     * The status the order is created in — the server's own vocabulary
     * (`PENDING` for crew, `ACCT_ENTERED` when accounts raise it, `DRAFT` to
     * hold it). Null on an update, which leaves the status alone.
     */
    val status: String? = null,
    /**
     * The extra fields this production added to the form.
     *
     * Grouped by section, as the service stores them. Empty when the form
     * template has none, which is every production that has not configured
     * one.
     */
    val customFields: List<CustomFieldGroup> = emptyList(),
) {
    /** Net of the parent lines — what the server stores as `net_amount`. */
    val total: Double get() = PoTotals.of(lines).net

    /** Net, tax and gross, for the form's footer. */
    val totals: PoTotals get() = PoTotals.of(lines)

    /**
     * The first reason this order cannot be raised, or null.
     *
     * Only the lines. Vendor and description are required **when the form
     * template says so**, and that is judged beside the template (the form's
     * `validate`) — the web's `POForm.validate` checks them only for a field the
     * template shows and marks required. Requiring them here unconditionally
     * made an order unsubmittable on any production whose Form Configuration
     * hid either field: the rule refused it, and no control was on screen to
     * satisfy it.
     */
    fun validationError(): String? = when {
        lines.isEmpty() -> str(S.desktop_po_needs_a_line)
        lines.any { it.description.isBlank() } -> str(S.desktop_po_line_needs_description)
        lines.any { it.total <= 0 } -> str(S.desktop_po_line_needs_qty_price)
        else -> null
    }
}

/** A vendor the production can raise orders against. */
@Serializable
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

/**
 * Which of the tool's reads a socket announcement invalidates.
 *
 * Only two: every order event stales whichever list is on screen, and the
 * vendor events stale the picker. Nothing is patched in place — the wire says
 * *that* something changed, the reload learns *what*.
 */
enum class PoRefresh {
    Orders,
    Vendors,
    FormTemplate,

    /** A template or a saved delivery address changed — the register tabs re-read. */
    Register,

    /** The settings document or an assignment rule changed elsewhere — the Settings tab re-reads. */
    Settings,
}

/**
 * A file attached to a purchase order — a quote, a signed copy, a delivery
 * note. The wire keeps the storage key under `media` and the display name
 * under `name`, and the two are not the same string.
 */
@Serializable
data class PoAttachment(
    val id: String = "",
    val media: String,
    val name: String = "",
    val contentType: String = "",
    val bucket: String = "",
    val region: String = "",
) {
    val displayName: String get() = name.ifBlank { media.substringAfterLast('/') }
}

/** Everything the purchase order tool asks the server for. */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface PurchaseOrderRepository {

    /**
     * Socket announcements that this viewer's lists are stale — another
     * client's raise, decision or post, refetched rather than patched, which
     * is the web's own pattern (`accountHubListeners.js` bridges every
     * `po:*` event to a parameterless refetch). Defaulted empty for tests and
     * hosts without a socket.
     */
    val refreshes: Flow<PoRefresh> get() = emptyFlow()

    /**
     * Every order this viewer may see, newest first.
     *
     * [departmentId] is the "My Department POs" tab's whole implementation:
     * the server scopes the list, rather than the client fetching everything
     * and hiding rows it was not entitled to.
     */
    suspend fun orders(status: PoStatus?, departmentId: String? = null): ZillitResult<List<PurchaseOrder>>

    /** Orders routed to this viewer for a decision. */
    suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>>

    /** Orders this viewer raised. */
    suspend fun myOrders(): ZillitResult<List<PurchaseOrder>>

    suspend fun order(id: String): ZillitResult<PurchaseOrder>

    suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>>

    /**
     * Replaces the files on an order.
     *
     * Attachments are a column of the order, not a sub-resource: they arrive
     * with `GET /{id}` and are written by patching the record. The three
     * dedicated routes the old web module declared
     * (`/v2/list|add|delete/attachments/…`) answer 404 on every verb on
     * develop, verified 2026-09-12 — see the endpoint-existence probe.
     */
    suspend fun saveAttachments(id: String, files: List<PoAttachment>): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("purchase-order attachments are not wired"))

    /**
     * Emails the order to its vendor (`POST /{id}/send-vendor-email`).
     *
     * No body — the server builds the mail itself: the order's PDF plus the
     * production's terms document, from the production's accounts mailbox,
     * copying whoever raised it. Approval does **not** auto-send, so this call
     * is the only thing that puts an order in front of a vendor.
     *
     * Answers the send stamp, which the caller folds into the order so the
     * action can relabel itself without a refetch.
     */
    suspend fun sendVendorEmail(id: String): ZillitResult<PoEmailReceipt> =
        ZillitResult.Failure(ZillitError.Unknown("sending to a vendor is not wired"))

    /**
     * Renders the order's PDF and answers where it was stored.
     *
     * `POST /{id}/pdf` takes the production's display names — the server has no
     * project-info endpoint, so the client passes what it holds — and answers
     * an S3 attachment the host presigns like any other document.
     */
    suspend fun pdf(id: String, projectName: String, companyName: String): ZillitResult<PoAttachment> =
        ZillitResult.Failure(ZillitError.Unknown("purchase-order PDFs are not wired"))

    suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit>

    suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    /**
     * Approves the order's next tier — `{ tier_number, total_tiers }`, the web's
     * body. The tier is the one the viewer was cleared to decide (see
     * [PoApprovalTiers.visibility]); the server records one approval per tier.
     */
    suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit>

    suspend fun reject(id: String, reason: String): ZillitResult<Unit>

    /** Posts the coded order to the ledger, lines and header with it. */
    suspend fun post(id: String, request: PoPostRequest): ZillitResult<Unit>

    /**
     * Closes one order — `{ reason, effective_date }`. The date is the period
     * the released commitment lands in, which is the whole decision; the web's
     * Close PO dialog asks for both and sends both.
     */
    suspend fun close(id: String, reason: String, effectiveDate: Long?): ZillitResult<Unit>

    /** The processing page's Save — see [PoEntryUpdate]. */
    suspend fun saveEntry(id: String, update: PoEntryUpdate): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("saving a processed order is not wired"))

    /**
     * Hands many orders to one accountant in one call — `PATCH /bulk` with
     * `{ po_ids, data: { assigned_to, reassignment_reason } }`, the web's bulk
     * Reassign. The server's 100-id cap is checked before the call.
     */
    suspend fun bulkReassign(ids: List<String>, userId: String, reason: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("bulk reassigning is not wired"))

    // -- the account hub's workflow reads: PoWorkflowSource's --------------------

    /** Who approves which tier; empty when the production has configured none. */
    suspend fun approvalTiers(): ZillitResult<PoApprovalTiers> = ZillitResult.Success(PoApprovalTiers())

    /** The cost-report lock; unlocked when it cannot be read. */
    suspend fun periodLock(): ZillitResult<PoPeriodLock> = ZillitResult.Success(PoPeriodLock())

    /** The order's query thread, or null when nobody has asked anything yet. */
    suspend fun queryThread(orderId: String): ZillitResult<PoQueryThread?> = ZillitResult.Success(null)

    /** Sends a message, opening the thread when [threadId] is null. Answers the thread as it now stands. */
    suspend fun sendQuery(orderId: String, threadId: String?, text: String): ZillitResult<PoQueryThread?> =
        ZillitResult.Failure(ZillitError.Unknown("order queries are not wired"))

    /** The rates a mixed-currency total converts through. */
    suspend fun currencyRates(): ZillitResult<PoCurrencyRates> = ZillitResult.Success(PoCurrencyRates())

    /**
     * Sets one field across many orders (`PATCH /bulk`).
     *
     * The server caps the batch at 100 ids and accepts `CLOSED` as the only
     * status it will bulk-set, so this is in practice the bulk effective-date
     * action: the accountant picks a period and stamps a selection with it.
     * The cap is checked here rather than discovered as a 400.
     */
    suspend fun bulkSetEffectiveDate(ids: List<String>, effectiveDate: Long): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("bulk editing is not wired"))

    /** Closes several at once — the month-end sweep. */
    /**
     * Closes many orders into one accounting period.
     *
     * [effectiveDate] is the period the write-off lands in, not a comment —
     * see the implementation. Null leaves the period to the server.
     */
    suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit>

    /**
     * Hands the order to another accountant, with the reason on the record.
     *
     * A PATCH of two fields rather than its own verb, which is what the web
     * sends: `assigned_to` plus `reassignment_reason`.
     */
    suspend fun reassign(id: String, userId: String, reason: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("reassigning an order is not wired"))

    suspend fun vendors(): ZillitResult<List<Vendor>>

    // -- the Templates tab -----------------------------------------------------

    suspend fun templates(): ZillitResult<List<PoTemplate>> = ZillitResult.Success(emptyList())

    suspend fun saveTemplate(template: PoTemplate): ZillitResult<PoTemplate> = noRegister()

    suspend fun deleteTemplate(id: String): ZillitResult<Unit> = noRegister()

    // -- the Delivery Addresses tab -------------------------------------------

    suspend fun deliveryAddresses(): ZillitResult<List<PoDeliveryAddress>> = ZillitResult.Success(emptyList())

    suspend fun saveDeliveryAddress(id: String?, address: PoAddress): ZillitResult<PoDeliveryAddress> = noRegister()

    private fun <T> noRegister(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_po_register_unavailable)))

    // -- the Settings tab: the web's `poSettingsApi` and `assignmentRulesApi` ---
    // Defaulted to a refusal so a host or a test double without settings still
    // composes; the real repository answers every one.

    /** The settings document and the module's rules, as one GET answers both. */
    suspend fun settings(): ZillitResult<PoSettingsBundle> = noSettings()

    suspend fun saveDescriptionFormat(format: PoDescriptionFormat): ZillitResult<PoSettings> = noSettings()

    suspend fun saveRentalSplit(autoSplit: Boolean, splitType: PoSplitType): ZillitResult<PoSettings> = noSettings()

    suspend fun saveNumbering(prefix: String, allowAmendAfterApproval: Boolean): ZillitResult<PoSettings> =
        noSettings()

    suspend fun saveTermsDocument(document: PoAttachment): ZillitResult<PoSettings> = noSettings()

    suspend fun saveAssetFilters(filters: AssetFilters): ZillitResult<PoSettings> = noSettings()

    suspend fun createRule(rule: PoAssignmentRule): ZillitResult<PoAssignmentRule> = noSettings()

    suspend fun updateRule(rule: PoAssignmentRule): ZillitResult<PoAssignmentRule> = noSettings()

    suspend fun deleteRule(id: String): ZillitResult<Unit> = noSettings()

    /** The chart's postable lines, for a rule's nominal codes. */
    suspend fun nominalCodes(): ZillitResult<List<PoNominal>> = noSettings()

    /** The production's line-item tags, for the asset register rule. */
    suspend fun assetTags(): ZillitResult<List<String>> = noSettings()

    private fun <T> noSettings(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_po_settings_unavailable)))
}

/**
 * What the server says about a vendor email that went out.
 *
 * Folded into the order on return so the action can relabel itself — "Send to
 * Vendor" becomes "Resend to Vendor" — without a second read of the list.
 */
data class PoEmailReceipt(
    val sent: Boolean,
    val to: String,
    val at: Long?,
    val by: String?,
)
