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
    Held("held", S.desktop_call_on_hold),
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

/** The separate approval flag; absent on the wire means pending. */
enum class ApprovalStatus(val wire: String, private val labelKey: String) {
    Pending("pending", S.pending),
    Approved("approved", S.approved),
    Rejected("rejected", S.rejected),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): ApprovalStatus =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: Pending
    }
}

/**
 * How the supplier gets paid. `wire` and `cheque` are "urgent": they bypass
 * the PO/approval chain and are released with Override & Pay; `faster` counts
 * as urgent for the override buttons only.
 */
enum class PayMethod(val wire: String, private val labelKey: String) {
    Bacs("bacs", S.ah_run_card_method_bacs),
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
) {
    val label: String get() = poNumber.ifBlank { "PO-" + poId.take(5) }
}

/** One line of a linked order. */
data class PoLine(
    val description: String = "",
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val total: Double? = null,
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
) {
    fun overrideFor(userId: String): Boolean =
        canOverride ?: teamMembers.firstOrNull { it.userId == userId }?.overrideAccess ?: false

    fun seniorFor(userId: String): Boolean =
        isSenior ?: teamMembers.firstOrNull { it.userId == userId }?.isSenior ?: false
}

/** What `POST /upload` read off the document. Every field optional; often the whole call fails. */
data class InvoiceExtraction(
    val uploadId: String = "",
    val supplierName: String = "",
    val invoiceNumber: String = "",
    /** As sent: `YYYY-MM-DD`. */
    val invoiceDate: String = "",
    val dueDate: String = "",
    val gross: Double? = null,
    val currency: String = "",
    val poNumber: String = "",
    val payMethod: String = "",
    /** 0–100. */
    val confidence: Double? = null,
)

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
) {
    val displayNumber: String get() = invoiceNumber.ifBlank { reference }.ifBlank { "—" }

    val statusLabel: String
        get() = if (status == InvoiceStatus.Unknown) statusRaw.ifBlank { str(S.desktop_unknown) } else status.label

    val hasPo: Boolean get() = linkedPos.isNotEmpty() || poId.isNotBlank() || poNumber.isNotBlank()

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

/** What the department's Upload Invoice sheet offers. */
enum class UploadType(val wire: String, private val labelKey: String, private val hintKey: String) {
    Po("po", S.desktop_against_purchase_order, S.desktop_inv_goes_to_accounts_for_matching),
    Cheque("cheque", S.desktop_cheque_request, S.desktop_inv_cheque_request_hint),
    Wire("wire", S.desktop_wire_request, S.desktop_inv_wire_request_hint),
    ;

    val label: String get() = str(labelKey)
    val hint: String get() = str(hintKey)
}
