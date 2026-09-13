package com.zillit.desktop.feature.callsheet.domain

/**
 * A call sheet's lifecycle state.
 *
 * The wire strings are the state machine the backend runs:
 * `DRAFT → PENDING_INTERNAL_APPROVAL → INTERNAL_APPROVED → PENDING_APPROVAL →
 * (APPROVAL_REJECTED | APPROVED_FOR_PUBLISH) → PUBLISHED`. Saving a revision
 * from any of the middle states moves the sheet back to Draft and restarts the
 * review. Labels are the web's `STATUS_UI` (`callsheetConstants.js:26-35`).
 */
enum class CallSheetStatus(val wire: String, val label: String) {
    Draft("DRAFT", "Draft"),
    PendingInternalApproval("PENDING_INTERNAL_APPROVAL", "For Comments"),
    InternalApproved("INTERNAL_APPROVED", "Comments Approved"),
    PendingApproval("PENDING_APPROVAL", "Pending Signature"),
    ApprovalRejected("APPROVAL_REJECTED", "Final Rejected"),
    ApprovedForPublish("APPROVED_FOR_PUBLISH", "Final Approved"),
    Published("PUBLISHED", "Published"),
    Deleted("DELETED", "Deleted"),
    Unknown("", "-"),
    ;

    /** `LOCKED_STATUSES`: no edit, no delete, no send. */
    val locked: Boolean get() = this == ApprovedForPublish || this == Published

    /** `REVIEW_IN_PROGRESS_STATUSES`: saving a revision restarts the review. */
    val reviewInFlight: Boolean
        get() = this == PendingInternalApproval || this == InternalApproved ||
            this == PendingApproval || this == ApprovalRejected

    /** The comment phase that lives under Drafts' "For Comments" chip. */
    val inCommentPhase: Boolean get() = this == PendingInternalApproval || this == InternalApproved

    companion object {
        fun fromWire(value: String?): CallSheetStatus =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(value?.trim(), ignoreCase = true) }
                ?: Unknown

        /** Drafts holds the whole pre-signature phase, comments included. */
        val DRAFT_TAB = listOf(Draft, PendingInternalApproval, InternalApproved)

        /** Sent and Received are the signature phase, seen from either end. */
        val SIGNATURE_PHASE = listOf(PendingApproval, ApprovalRejected)

        val FINALIZED_TAB = listOf(ApprovedForPublish)
    }
}

/**
 * One row of a call-sheet list.
 *
 * Rows are slim on some queries (the drafts listing omits the payload) and
 * carry the current revision's `shared` block and the review records on
 * others; every optional part is therefore empty rather than assumed.
 */
data class CallSheetSummary(
    val id: String,
    val serialNo: String = "",
    val name: String = "",
    val status: CallSheetStatus = CallSheetStatus.Draft,
    val createdBy: String = "",
    val createdById: String = "",
    val createdOn: Long? = null,
    val updatedOn: Long? = null,
    val publishedOn: Long? = null,
    /** The current revision's header when the row embeds it — the Day column reads it. */
    val shared: SharedHeader? = null,
    /** Whether the row's embedded revision carries cells — the comments preview can then skip a fetch. */
    val hasCells: Boolean = false,
    val approvals: List<ApprovalRequest> = emptyList(),
    /** False when the listing omitted `approval_requests` entirely — Sent then merges them in. */
    val approvalsIncluded: Boolean = true,
    val reminders: List<SheetReminder> = emptyList(),
    /** The raw status string, for a status this client does not know. */
    val rawStatus: String = status.wire,
) {
    val statusLabel: String get() = if (status == CallSheetStatus.Unknown) rawStatus.ifBlank { "-" } else status.label
}

/** A sheet opened in full: the row, its editable payload, and its review history. */
data class CallSheetDetail(
    val summary: CallSheetSummary,
    val payload: SheetPayload,
    val approvals: List<ApprovalRequest> = summary.approvals,
    val reminders: List<SheetReminder> = summary.reminders,
    val revisions: List<SheetRevision> = emptyList(),
    /** Whether the server sent a current revision at all. */
    val hasPayload: Boolean = true,
)

/**
 * One person's pending or answered review of a sheet.
 *
 * Matched by [assigneeId], never by display name.
 */
data class ApprovalRequest(
    val id: String,
    val assigneeId: String,
    val assigneeName: String = "",
    val role: String = "",
    /** `INTERNAL` (comments round) or `FINAL` (signature round); missing means FINAL. */
    val stage: String = "FINAL",
    /** `PENDING`, `APPROVED`, `REJECTED`… missing means PENDING. */
    val status: String = "PENDING",
    val reason: String = "",
    /** Which send this request belongs to; a re-send starts a new round and the old one no longer counts. */
    val round: Int = 1,
    val actedOn: Long? = null,
    val createdOn: Long? = null,
    val revisionId: String = "",
    val revisionVersion: Int? = null,
) {
    val isPending: Boolean get() = status.isBlank() || status.equals("PENDING", ignoreCase = true)
    val isApproved: Boolean get() = status.equals("APPROVED", ignoreCase = true)
    val isRejected: Boolean get() = status.equals("REJECTED", ignoreCase = true)
    val isInternal: Boolean get() = stage.equals("INTERNAL", ignoreCase = true)
    val isFinalStage: Boolean get() = !isInternal
}

/** A reminder an author sent to a pending approver (`reminders[]`). */
data class SheetReminder(
    val id: String,
    val approvalRequestId: String = "",
    val assigneeId: String = "",
    val assigneeName: String = "",
    val sentBy: String = "",
    val sentById: String = "",
    val sentByRole: String = "",
    val message: String = "",
    val createdOn: Long? = null,
)

/** One saved version of the payload; version 1 is the create. */
data class SheetRevision(
    val id: String,
    val version: Int = 0,
    val createdBy: String = "",
    val createdById: String = "",
    val createdOn: Long? = null,
    val notes: String = "",
)

/** One comment in a sheet's thread. */
data class SheetComment(
    val id: String,
    val authorId: String = "",
    val authorName: String = "",
    val authorRole: String = "",
    val text: String = "",
    val createdOn: Long? = null,
    val updatedOn: Long? = null,
) {
    /** The web's "(edited)" rule: an update stamp that differs from the create stamp. */
    val isEdited: Boolean get() = updatedOn != null && createdOn != null && updatedOn != createdOn
}

/**
 * A layout saved for the whole project (`/call-sheets/templates`). Rows
 * arrive without their payload; opening one fetches it. `created_by` is a
 * display NAME, not an id.
 */
data class SavedTemplate(
    val id: String,
    val name: String,
    val createdBy: String = "",
    val createdById: String = "",
    val createdOn: Long? = null,
    val updatedOn: Long? = null,
    val payload: SheetPayload? = null,
)

/**
 * One of the server's stock layouts (`GET /default-template`), prepared the
 * way `prepareDefaultTemplates` names them.
 */
data class StockTemplate(
    val identifier: String,
    val displayName: String,
    val payload: SheetPayload,
    val isCreateYourOwn: Boolean = false,
)

/**
 * The project's call-sheet counters and default recipients, read at start and
 * written back on save.
 */
data class SheetMetadata(
    val currentShootDay: Int = 0,
    val totalDays: String = "",
    val dayTypes: List<String> = STANDARD_DAY_TYPES,
    val finalApproverIds: List<String> = emptyList(),
    val internalReceiverIds: List<String> = emptyList(),
) {
    companion object {
        /** The three day types every production gets; custom ones merge after. */
        val STANDARD_DAY_TYPES = listOf("SWD", "CWD", "SCWD")

        /** The web's `mergeDayTypes`: defaults first, then custom codes, de-duplicated, blanks dropped. */
        fun mergeDayTypes(extra: List<String>): List<String> =
            (STANDARD_DAY_TYPES + extra.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
    }
}

/**
 * A crew member, as the sheet's employee sections, pickers and names need
 * them — department and designation as words, with the label keys they came
 * from kept for grouping.
 */
data class SheetMember(
    val userId: String,
    val fullName: String,
    val department: String = "",
    val departmentKey: String = department,
    val designation: String = "",
    /** Their standing on the production; null on older cached rows, which counts as accepted. */
    val status: String? = null,
    val isAdmin: Boolean = false,
    val avatarUrl: String? = null,
) {
    /** `normalizeIntegrationMembers` keeps accepted people only. */
    val isAccepted: Boolean
        get() = status.isNullOrBlank() || status.equals("accepted", ignoreCase = true)
}

/** What the Company Details section is pre-filled from. */
data class CompanySeed(
    val projectName: String = "",
    val companyName: String = "",
    val companyAddress: String = "",
)

/**
 * The requests of the newest round for a stage — a re-send supersedes the
 * previous round's approvers. A payload with no round is round 1; a missing
 * stage is FINAL; stages are independent.
 */
fun List<ApprovalRequest>.latestRound(stage: String = "FINAL"): List<ApprovalRequest> {
    val internal = stage.equals("INTERNAL", ignoreCase = true)
    val ofStage = filter { it.isInternal == internal }
    val latest = ofStage.maxOfOrNull { it.round } ?: return emptyList()
    return ofStage.filter { it.round == latest }
}
