package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where an invoice sits in the accounts-payable pipeline. `Unknown` keeps a
 * status the server adds later from crashing a list; [raw] survives on the
 * invoice so it can still be shown.
 */
enum class InvoiceStatus(val wire: String, private val labelKey: String) {
    Inbox("inbox", S.inbox_text),
    Matching("matching", S.desktop_matching),
    Approval("approval", S.pending),
    Entry("entry", S.desktop_in_entry),
    ReadyToPay("ready_to_pay", S.desktop_ready_to_pay),
    Paid("paid", S.desktop_paid),
    Held("held", S.desktop_on_hold_title),
    Disputed("disputed", S.desktop_disputed),
    Approved("approved", S.approved),
    Rejected("rejected", S.rejected),
    Cancelled("cancelled", S.cancelled),
    Override("override", S.dm_nom_table_override),
    Posted("posted", S.ah_step_posted),
    UnderReview("under_review", S.ah_under_review),
    Unknown("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): InvoiceStatus {
            val code = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it != Unknown && it.wire == code } ?: Unknown
        }
    }
}

/**
 * The separate approval flag; absent on the wire means pending.
 *
 * Only an absent (or empty) flag is pending — the web's
 * `(inv.approval_status || "pending") === "pending"`. A value the client does
 * not know is [Other]: it is neither pending, approved nor rejected, so the
 * "Pending" quick filter and the owner-only delete leave it alone.
 */
enum class ApprovalStatus(val wire: String, private val labelKey: String) {
    Pending("pending", S.pending),
    Approved("approved", S.approved),
    Rejected("rejected", S.rejected),
    Other("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): ApprovalStatus {
            val code = wire?.trim()?.lowercase().orEmpty()
            if (code.isEmpty()) return Pending
            return entries.firstOrNull { it != Other && it.wire == code } ?: Other
        }
    }
}

/**
 * How the supplier gets paid. `wire` and `cheque` are "urgent": they bypass
 * the PO/approval chain and are released with Override & Pay; `faster` counts
 * as urgent for the override buttons only.
 */
enum class PayMethod(val wire: String, private val labelKey: String) {
    Bacs("bacs", S.desktop_inv_method_bacs),
    Wire("wire", S.ah_run_card_method_wire),
    Cheque("cheque", S.ah_run_card_method_cheque),
    Faster("faster", S.desktop_faster_payment),
    DirectDebit("direct_debit", S.desktop_direct_debit),
    AlreadyPaid("already_paid", S.desktop_already_paid),
    ;

    val label: String get() = str(labelKey)

    /** Wire or cheque: off the approval chain, an "urgent" request. */
    val isUrgent: Boolean get() = this == Wire || this == Cheque

    /** Wire, cheque or faster: released with Override & Pay, never Approve. */
    val isOverridePayable: Boolean get() = isUrgent || this == Faster

    companion object {
        /** `faster_payment` is the legacy spelling of `faster`; blank is BACS. */
        fun normalise(wire: String?): String = when (val code = wire?.trim()?.lowercase().orEmpty()) {
            "faster_payment", "faster-payment", "fasterpayment" -> Faster.wire
            "" -> Bacs.wire
            else -> code
        }

        fun from(wire: String?): PayMethod {
            val code = normalise(wire)
            return entries.firstOrNull { it.wire == code } ?: Bacs
        }
    }
}

/** A purchase order the invoice has been matched to. */
data class LinkedPo(
    val poId: String,
    val poNumber: String = "",
    val poVendorId: String = "",
    val poGrossTotal: Double? = null,
    /** The match notes stored on this link — the review pre-fills its Match Notes from them. */
    val notes: List<String> = emptyList(),
)

/**
 * A linked order as `GET /:id/linked-pos` answers it — enough to check the
 * invoice against without leaving the review.
 *
 * The list rows on the invoice itself carry only an id, a number and a total;
 * this is the order's own record, so the two can be compared line for line.
 */
data class LinkedPoDetail(
    val poId: String,
    val poNumber: String = "",
    val vendorName: String = "",
    val description: String = "",
    val status: String = "",
    val currency: String = "",
    val grossTotal: Double? = null,
    val netTotal: Double? = null,
    val raisedBy: String = "",
    val raisedAtMs: Long? = null,
    val lines: List<PoLine> = emptyList(),
    /** The ledger's PO details card (`EntryDetailModal.jsx:1539-1767`). */
    val vendorId: String = "",
    val departmentId: String = "",
    val effectiveDateMs: Long? = null,
    val deliveryDateMs: Long? = null,
    val deliveryAddress: PoDeliveryAddress? = null,
) {
    val label: String get() = poNumber.ifBlank { "PO-" + poId.take(5) }
}

/** A PO's delivery address — an object on newer orders, a plain string (all in [lines]) on older ones. */
data class PoDeliveryAddress(
    val name: String = "",
    /** Line 1, line 2, city, state and postcode, joined. */
    val lines: String = "",
    val email: String = "",
    val phone: String = "",
)

/** One line of a linked order. */
data class PoLine(
    val description: String = "",
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val total: Double? = null,
    /** The coding the order was raised with — what Invoice Entry starts from. */
    val id: String = "",
    val account: String = "",
    val taxRate: Double? = null,
    val taxType: String = "",
    val expenditureType: String = "",
    val splitParentId: String? = null,
    /** Layers, tags and the untouched extras, so a line seeded from the PO keeps them. */
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val carried: CarriedFields? = null,
)

/**
 * One purchase order the server offers as a match — the web's
 * `po-suggestions` rows.
 *
 * [score] and [confidence] are the server's own judgement and ride back out
 * on the match so the link records why it was made.
 */
data class PoSuggestion(
    val poId: String,
    val poNumber: String = "",
    val reference: String = "",
    val vendorName: String = "",
    val grossAmount: Double? = null,
    val currency: String = "",
    val score: Double? = null,
    val confidence: String = "",
) {
    val label: String get() = poNumber.ifBlank { reference }.ifBlank { "PO-" + poId.take(5) }
}

/** The two lists the route answers: this vendor's orders, and the reader's own. */
data class PoSuggestions(
    val vendorPos: List<PoSuggestion> = emptyList(),
    val userPos: List<PoSuggestion> = emptyList(),
    /** Every other open order — the inbox review's "All Purchase Orders" search. */
    val allPos: List<PoSuggestion> = emptyList(),
) {
    val total: Int get() = vendorPos.size + userPos.size

    val isEmpty: Boolean get() = total == 0
}

/**
 * The stored file, in the shape the invoices service keeps and echoes.
 * `contentType` is a CATEGORY (`document`, `image`), not a MIME type; the
 * extension in `contentSubtype` is what says how to render it.
 */
data class InvoiceAttachment(
    val media: String,
    val bucket: String,
    val region: String,
    val name: String,
    val contentType: String,
    val contentSubtype: String,
    val caption: String = "",
) {
    val extension: String get() = contentSubtype.lowercase().ifBlank { name.substringAfterLast('.', "").lowercase() }
    val isPdf: Boolean get() = extension == "pdf"
    val isImage: Boolean get() = contentType.equals("image", ignoreCase = true) || extension in IMAGE_EXTENSIONS

    /** A real MIME, derived from the extension because `contentType` is not one. */
    val mimeType: String
        get() = when (extension) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> "application/octet-stream"
        }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
    }
}

/** One signed-off tier. */
data class Approval(val userId: String, val tierNumber: Int, val approvedAtMs: Long?)

/** One audit line from `history`. */
data class HistoryEntry(val action: String, val actionBy: String, val actionAtMs: Long?, val note: String = "")

/** The supplier, as far as the invoice screens need it. */
data class Vendor(
    val id: String,
    val name: String,
    val terms: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    /** The Vendors page's columns: who they are, how they are paid, and whether that is on file. */
    val country: String = "",
    val taxNumber: String = "",
    val type: String = "",
    val bankName: String = "",
    val defaultNominalCode: String = "",
    val currency: String = "",
    /** The linked bank record; blank when the vendor has none on file. */
    val bankId: String = "",
    val contactPerson: String = "",
    /** The address's city — the vendor detail's City line (`SuppliersPage.jsx:220`). */
    val city: String = "",
) {
    /** `net_30` → "30 days", the SLA column. */
    val slaLabel: String?
        get() = terms.trim().lowercase().removePrefix("net_").removePrefix("net").trim().toIntOrNull()?.let {
            "$it days"
        }
}

/** A production bank account, for the detail's "Bank" line and the Enter form. */
data class BankAccount(val id: String, val name: String, val bankName: String = "", val entityId: String? = null) {
    val displayName: String get() = name.ifBlank { bankName }.ifBlank { id }
}

/** One row of the module's `/settings` `team_members`. */
data class TeamMember(
    val userId: String,
    val overrideAccess: Boolean = false,
    val isSenior: Boolean = false,
    val runAccess: Boolean = false,
    /**
     * Settings gives this member a posting right: an unlimited limit (null,
     * or the tolerated `"unlimited"`) or one above nothing. An absent limit is
     * no grant — the web's `hasUnlimitedPostingLimit`.
     */
    val postingRight: Boolean = false,
)

/** The module settings — only the parts that gate buttons here. */
data class InvoiceSettings(
    /** `me.can_override`; null when the backend predates the `me` block. */
    val canOverride: Boolean? = null,
    /** `me.is_senior`; null when absent. */
    val isSenior: Boolean? = null,
    val teamMembers: List<TeamMember> = emptyList(),
    /** Every user id in `run_authorization[].user`. */
    val runApprovers: Set<String> = emptySet(),
    /** The run authorisation chain itself, tier by tier — who signs a run at each level. */
    val runAuthorisation: List<RunAuthLevel> = emptyList(),
    /**
     * Whether the response carried a `me` object at all. The web tests the
     * object's presence, not its keys, before it falls back to `team_members`
     * (`InvoicesModule.jsx:293-318`).
     */
    val hasMe: Boolean = canOverride != null || isSenior != null,
) {
    /**
     * Override rights as the accountant console derives them: with a `me`
     * block, `can_override || is_senior`; only without one, the legacy
     * `team_members[me].override_access`.
     */
    fun overrideFor(userId: String): Boolean = if (hasMe) {
        canOverride == true || isSenior == true
    } else {
        teamMembers.firstOrNull { it.userId == userId }?.overrideAccess ?: false
    }

    /**
     * `me.is_senior` and nothing else — the web's `serverIsSenior`. The legacy
     * `team_members[].is_senior` is never read for seniority: it would unlock
     * Settings, every entry row and posting for somebody the server does not
     * call senior.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter") // Kept so every rights read takes the same argument.
    fun seniorFor(userId: String): Boolean = isSenior == true

    /**
     * The department board's override: `me.can_override || me.is_senior`,
     * with no fallback of any kind (`DepartmentInvoiceModule.jsx:615`).
     */
    val serverOverride: Boolean get() = canOverride == true || isSenior == true

    /** Settings → Team gives this person a posting limit — the other half of `canPostToLedger`. */
    fun postingRightFor(userId: String): Boolean = teamMembers.any { it.userId == userId && it.postingRight }

    /** Settings → Team lists this person with "Can authorise payment runs". */
    fun runAccessFor(userId: String): Boolean = teamMembers.any { it.userId == userId && it.runAccess }

    /** Whether anybody at all may operate runs, bar the seniors who always can. */
    val hasRunAuthoriser: Boolean get() = teamMembers.any { it.runAccess }
}

/** One supplier invoice. */
data class Invoice(
    val id: String,
    val invoiceNumber: String = "",
    val reference: String = "",
    val vendorId: String = "",
    val supplierName: String = "",
    val description: String = "",
    val grossAmount: Double = 0.0,
    /** Null = not captured; show "—", never 0. */
    val netAmount: Double? = null,
    /** `tax_amount`, falling back to the older `vat_amount`. */
    val taxAmount: Double? = null,
    val currency: String = "",
    val invoiceDateMs: Long? = null,
    val dueDateMs: Long? = null,
    val effectiveDateMs: Long? = null,
    val payMethod: PayMethod = PayMethod.Bacs,
    /** `pay_method` exactly as stored; blank when the list row had none. */
    val payMethodRaw: String = "",
    val status: InvoiceStatus = InvoiceStatus.Unknown,
    val statusRaw: String = "",
    val approvalStatus: ApprovalStatus = ApprovalStatus.Pending,
    val departmentId: String = "",
    val companyId: String = "",
    val bankId: String = "",
    val episode: String = "",
    val poId: String = "",
    val poNumber: String = "",
    val linkedPos: List<LinkedPo> = emptyList(),
    /**
     * `po_ids` — the ids the server counts as matched. Pre-approval's
     * Matched / Unmatched tiles count on this and nothing else
     * (`MatchingPage.jsx:605-607`).
     */
    val poIds: List<String> = emptyList(),
    val attachments: List<InvoiceAttachment> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val rejectionReason: String = "",
    val rejectedBy: String = "",
    val rejectedAtMs: Long? = null,
    val holdReason: String = "",
    val holdNote: String = "",
    val ocrConfidence: Double? = null,
    /** The creator. */
    val userId: String = "",
    /** Whose desk this invoice is on during entry; blank = nobody's. */
    val assignedTo: String = "",
    val createdAtMs: Long? = null,
    val updatedBy: String = "",
    val updatedAtMs: Long? = null,
    /** The coded lines as saved, bar the reclaimable-tax line; empty until coded. */
    val lineItems: List<CodedLine> = emptyList(),
    /** The saved reclaimable-tax line, when there is one. */
    val taxLine: TaxLine? = null,
    /**
     * `line_items` exactly as the server sent it, so a save can carry back
     * every field this client does not edit (layers, tags, custom fields,
     * rental dates) instead of dropping them.
     */
    val lineItemsJson: String = "",
    /** `nominal_code` — the vendor history's Nominal column; blank on most rows. */
    val nominalCode: String = "",
    /**
     * The pending payment run this invoice already sits in (`active_run_id`);
     * blank = none. Payment Runs leaves such a row out of Open Items so it
     * cannot be put in a second run (`PaymentsPage.jsx:1197`).
     */
    val activeRunId: String = "",
    /** When it was marked paid (`paid_at`) — Posted sorts on it first. */
    val paidAtMs: Long? = null,
    /** The bank confirmations filed on a paid wire (`wire_attachments`). */
    val wireAttachments: List<WireAttachment> = emptyList(),
    /** A CIS supplier's invoice (`cisApplies` / `cis`) — Creditors tags the vendor. */
    val cis: Boolean = false,
    /** The terms written on the invoice (`paymentTerms` / `terms`) — Creditors' Terms column. */
    val paymentTerms: String = "",
) {
    val displayNumber: String get() = invoiceNumber.ifBlank { reference }.ifBlank { "—" }

    /**
     * The canonical pay-method code — the web's `payMethodCode`: the stored
     * value lower-cased with `faster_payment` folded onto `faster`, BACs when
     * blank. Unlike [payMethod] a code this client does not know stays itself,
     * so Payment Runs keeps it out of a BACs run (it is only "Process").
     */
    val payCode: String
        get() = if (payMethodRaw.isBlank()) payMethod.wire else PayMethod.normalise(payMethodRaw)

    /** A status the client does not know reads upper-cased, as the web's `STATUS_MAP` fallback does. */
    val statusLabel: String
        get() = if (status == InvoiceStatus.Unknown) {
            statusRaw.trim().uppercase().ifBlank { str(S.desktop_unknown) }
        } else {
            status.label
        }

    val hasPo: Boolean get() = linkedPos.isNotEmpty() || poId.isNotBlank() || poNumber.isNotBlank()

    /**
     * Pre-approval's rule — `linked_pos` or `po_id`, never a bare typed
     * `po_number`: that one is unconfirmed, shown amber, and the row is still
     * advanced as one with no PO (`MatchingPage.invoiceToRow`).
     */
    val hasMatchedPo: Boolean get() = linkedPos.isNotEmpty() || poId.isNotBlank()

    /** The PO column: "N POs", the number, or a truncated id; null = no PO. */
    val poLabel: String?
        get() = when {
            linkedPos.size > 1 -> str(S.desktop_po_count_pos, linkedPos.size)
            linkedPos.size == 1 -> linkedPos.first().poNumber.ifBlank {
                poNumber }.ifBlank { "PO-" + linkedPos.first().poId.take(PO_ID_CHARS)
            }
            poNumber.isNotBlank() -> poNumber
            poId.isNotBlank() -> "PO-" + poId.take(PO_ID_CHARS)
            else -> null
        }

    val isApproved: Boolean
        get() = approvalStatus == ApprovalStatus.Approved ||
            status == InvoiceStatus.Approved ||
            status == InvoiceStatus.Override

    val isRejected: Boolean get() = approvalStatus == ApprovalStatus.Rejected || status == InvoiceStatus.Rejected

    /**
     * The Approval Queue's `isApproved` — `status === "approved"` and nothing
     * else (`ApprovalPage.jsx:422`). [isApproved] also counts an approved
     * flag and an override, which would hide Override & Pay on exactly the
     * rows it exists for.
     */
    val isApprovedStatus: Boolean get() = status == InvoiceStatus.Approved

    /**
     * The web's `linkedPoLabel`: "N POs", the first linked order's number, or
     * the typed `po_number`; null when there is none of those
     * (`RegisterPage.jsx:221-223`, `ApprovalPage.jsx:461-463`).
     */
    val linkedPoLabel: String?
        get() = when {
            linkedPos.size > 1 -> str(S.desktop_po_count_pos, linkedPos.size)
            else -> linkedPos.firstOrNull()?.poNumber?.takeIf { it.isNotBlank() }
                ?: poNumber.takeIf { it.isNotBlank() }
        }

    /** Wire or cheque as stored — the web's raw `pay_method === "wire" || "cheque"` urgency test. */
    val isUrgentRaw: Boolean
        get() {
            val raw = payMethodRaw.ifBlank { payMethod.wire }
            return raw == PayMethod.Wire.wire || raw == PayMethod.Cheque.wire
        }

    /**
     * The pay method as the web prints it: the known label, or the stored
     * code humanised when it is one this client does not know
     * (`lib/payMethod.js` `payMethodLabel`).
     */
    val payMethodLabel: String
        get() {
            val code = PayMethod.normalise(payMethodRaw)
            return if (payMethodRaw.isBlank() || PayMethod.entries.any { it.wire == code }) {
                payMethod.label
            } else {
                InvoiceLabels.format(payMethodRaw)
            }
        }

    val approvedCount: Int get() = approvals.size

    val firstAttachment: InvoiceAttachment? get() = attachments.firstOrNull()

    /** Paid, cancelled or rejected rows are not overdue whatever the due date says. */
    val isUnsettled: Boolean
        get() = status !in setOf(InvoiceStatus.Paid, InvoiceStatus.Cancelled, InvoiceStatus.Rejected)

    private companion object {
        const val PO_ID_CHARS = 5
    }
}

/** A file the host picked, bytes and all. */
data class PickedInvoiceFile(val name: String, val contentType: String, val bytes: ByteArray) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    override fun equals(other: Any?): Boolean =
        other is PickedInvoiceFile && other.name == name && other.bytes.size == bytes.size
    override fun hashCode(): Int = name.hashCode() * HASH_PRIME + bytes.size

    private companion object {
        const val HASH_PRIME = 31
    }
}

