package com.zillit.desktop.feature.productionreport.domain

/**
 * A production report's lifecycle state.
 *
 * The wire strings are the state machine the backend runs:
 * `DRAFT → PENDING_INTERNAL_APPROVAL → INTERNAL_APPROVED → PENDING_APPROVAL →
 * (APPROVAL_REJECTED | APPROVED_FOR_PUBLISH) → PUBLISHED`. Saving a revision
 * from any of the middle states restarts the review. Labels are the web's
 * `STATUS_UI` (`productionReportConstants.js:26-35`).
 */
enum class ReportStatus(val wire: String, val label: String) {
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
        fun fromWire(value: String?): ReportStatus =
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
 * One row of a report list.
 *
 * List rows are slim on some queries and carry the current revision's
 * `shared` block and the review records on others; every optional part is
 * therefore empty rather than assumed.
 */
data class ReportSummary(
    val id: String,
    val serialNo: String,
    val name: String,
    val status: ReportStatus,
    val createdBy: String,
    val createdById: String,
    val createdOn: Long? = null,
    val updatedOn: Long? = null,
    val publishedOn: Long? = null,
    /** `shared.reportType` of the current revision — which tool this row belongs to. */
    val reportType: String = "",
    /** The current revision's header when the row embeds it — the Day column reads it. */
    val shared: SharedHeader? = null,
    val approvals: List<ApprovalRequest> = emptyList(),
    val reminders: List<ReportReminder> = emptyList(),
    /** The raw status string, for a status this client does not know. */
    val rawStatus: String = status.wire,
) {
    val statusLabel: String get() = if (status == ReportStatus.Unknown) rawStatus.ifBlank { "-" } else status.label
}

/** A report opened in full: the row, its editable payload, and its review history. */
data class ReportDetail(
    val summary: ReportSummary,
    val payload: SheetPayload,
    val approvals: List<ApprovalRequest> = summary.approvals,
    val reminders: List<ReportReminder> = summary.reminders,
    val revisions: List<ReportRevision> = emptyList(),
    /** Whether the server sent a current revision at all — a report without one opens on the local template. */
    val hasPayload: Boolean = true,
)

/**
 * One person's pending or answered review of a report.
 *
 * Matched by [assigneeId], never by display name: the web's approvals tab
 * compares names case-insensitively and desynchronises the moment two people
 * share one, or one is renamed.
 */
data class ApprovalRequest(
    val id: String,
    val assigneeId: String,
    val assigneeName: String,
    val role: String,
    /** `INTERNAL` (comments round) or `FINAL` (signature round); missing means FINAL. */
    val stage: String = "FINAL",
    /** `PENDING`, `APPROVED`, `REJECTED`, `SUPERSEDED`… */
    val status: String = "PENDING",
    val reason: String = "",
    /** Which send this request belongs to; a re-send starts a new round and the old one no longer counts. */
    val round: Int = 1,
    val actedOn: Long? = null,
    val createdOn: Long? = null,
    val revisionId: String = "",
    val revisionVersion: Int? = null,
) {
    val isPending: Boolean get() = status.equals("PENDING", ignoreCase = true)
    val isApproved: Boolean get() = status.equals("APPROVED", ignoreCase = true)
    val isRejected: Boolean get() = status.equals("REJECTED", ignoreCase = true)
    val isInternal: Boolean get() = stage.equals("INTERNAL", ignoreCase = true)
    val isFinalStage: Boolean get() = !isInternal
}

/** A reminder an author sent to a pending approver (`reminders[]` on the detail). */
data class ReportReminder(
    val id: String,
    val approvalRequestId: String = "",
    val assigneeId: String = "",
    val assigneeName: String = "",
    /** The sender's MEMBER ID since Sep 2026; a display NAME on older rows — see `reminderSender`. */
    val sentBy: String = "",
    val sentById: String = "",
    /** A designation KEY since ZL-20648 — translated on render, raw when unknown. */
    val sentByRole: String = "",
    val message: String = "",
    val createdOn: Long? = null,
)

/** One saved version of the payload; version 1 is the create. */
data class ReportRevision(
    val id: String,
    val version: Int = 0,
    val createdBy: String = "",
    val createdOn: Long? = null,
    val notes: String = "",
)

/** One comment in a report's thread. */
data class ReportComment(
    val id: String,
    val authorId: String = "",
    val authorName: String = "",
    val authorRole: String = "",
    val text: String = "",
    val createdOn: Long? = null,
    val updatedOn: Long? = null,
)

/**
 * A layout saved for the whole project (`/production-reports/templates`).
 * Rows arrive without their payload; opening one fetches it.
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
 * One of the server's stock layouts (`GET /default-template`).
 *
 * [identifier] `standard` is the blank "Create your own template" seed the
 * picker lifts out of the radio list; the others are named by the backend.
 */
data class StockTemplate(
    val identifier: String,
    val displayName: String,
    val payload: SheetPayload,
    val isCreateYourOwn: Boolean = false,
)

/**
 * The project's report counters and default recipients, read before a new
 * report is composed and written back on save.
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

/** A crew member, as the report's crew sections, pickers and names need them. */
data class SheetMember(
    val userId: String,
    val fullName: String,
    val department: String = "",
    val designation: String = "",
    /**
     * The designation's untranslated label KEY (`2nd_assistant_director_label`)
     * — what a reminder's `sent_by_role` carries (ZL-20648), so the reader
     * translates it in their own locale.
     */
    val designationKey: String = "",
    /**
     * Their standing on the production. Null on older cached rows, which is
     * treated as accepted — never a reason to hide someone.
     */
    val status: String? = null,
    val isAdmin: Boolean = false,
    val avatarUrl: String? = null,
) {
    /** `status === 'accepted'` on the web (ZL-20647), with a missing status counted as accepted. */
    val isAccepted: Boolean
        get() = status.isNullOrBlank() || status.equals("accepted", ignoreCase = true) ||
            status.equals("approved", ignoreCase = true)
}

/**
 * The requests of the newest round for a stage — a re-send supersedes the
 * previous round's approvers (web `latestRoundRequests`). A payload with no
 * round is a single round 1; a missing stage is FINAL; stages are independent.
 */
fun List<ApprovalRequest>.latestRound(stage: String = "FINAL"): List<ApprovalRequest> {
    val internal = stage.equals("INTERNAL", ignoreCase = true)
    val ofStage = filter { it.isInternal == internal }
    val latest = ofStage.maxOfOrNull { it.round } ?: return emptyList()
    return ofStage.filter { it.round == latest }
}

/** Whether [userId] is named by the newest round of either stage — the web's `isCurrentApprover`. */
fun List<ApprovalRequest>.namesInCurrentRound(userId: String?): Boolean {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return false
    return listOf("FINAL", "INTERNAL").any { stage -> latestRound(stage).any { it.assigneeId.trim() == me } }
}

/**
 * The request waiting on [userId] in the newest round — what Approve and
 * Reject act on. FINAL is preferred, as on the web; an INTERNAL-only request
 * is returned so callers can tell "comments only" apart from "nothing".
 */
fun List<ApprovalRequest>.pendingFor(userId: String?): ApprovalRequest? {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return null
    return listOf("FINAL", "INTERNAL").firstNotNullOfOrNull { stage ->
        latestRound(stage).firstOrNull { it.isPending && it.assigneeId.trim() == me }
    }
}
