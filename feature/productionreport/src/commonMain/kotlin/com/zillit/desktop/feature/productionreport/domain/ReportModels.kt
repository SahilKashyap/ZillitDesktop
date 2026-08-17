package com.zillit.desktop.feature.productionreport.domain

/**
 * A production report's lifecycle state.
 *
 * The wire strings are the state machine the backend runs:
 * `DRAFT → PENDING_INTERNAL_APPROVAL → INTERNAL_APPROVED → PENDING_APPROVAL →
 * (APPROVAL_REJECTED | APPROVED_FOR_PUBLISH) → PUBLISHED`. Saving a revision
 * from any of the middle states restarts the review.
 */
enum class ReportStatus(val wire: String, val label: String) {
    Draft("DRAFT", "Draft"),
    PendingInternalApproval("PENDING_INTERNAL_APPROVAL", "For comments"),
    InternalApproved("INTERNAL_APPROVED", "Comments cleared"),
    PendingApproval("PENDING_APPROVAL", "Pending signature"),
    ApprovalRejected("APPROVAL_REJECTED", "Rejected"),
    ApprovedForPublish("APPROVED_FOR_PUBLISH", "Final approved"),
    Published("PUBLISHED", "Published"),
    Deleted("DELETED", "Deleted"),
    Unknown("", "Unknown"),
    ;

    /** Whether the editor must refuse to open this sheet for changes. */
    val locked: Boolean get() = this == ApprovedForPublish || this == Published

    /** Whether saving a revision restarts an in-flight review. */
    val reviewInFlight: Boolean
        get() = this == PendingInternalApproval || this == InternalApproved ||
            this == PendingApproval || this == ApprovalRejected

    companion object {
        fun fromWire(value: String?): ReportStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) && it.wire.isNotEmpty() }
                ?: Unknown
    }
}

/** One row of a report list. */
data class ReportSummary(
    val id: String,
    val serialNo: String,
    val name: String,
    val status: ReportStatus,
    val createdBy: String,
    val createdById: String,
    val createdAt: String,
    val updatedAt: String,
    val publishedAt: String,
)

/** A sheet opened in full: the list row plus its editable payload and reviews. */
data class ReportDetail(
    val summary: ReportSummary,
    val payload: SheetPayload,
    val approvals: List<ApprovalRequest>,
)

/**
 * One person's pending or answered review of a sheet.
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
    /** `INTERNAL` (comments round) or `FINAL` (signature round). */
    val stage: String,
    val status: String,
    val reason: String,
) {
    val isPending: Boolean get() = status.equals("PENDING", ignoreCase = true)
    val isFinalStage: Boolean get() = !stage.equals("INTERNAL", ignoreCase = true)
}

/**
 * The project's report counters and default recipients, read before a new
 * sheet is composed and written back on every save.
 */
data class SheetMetadata(
    val currentShootDay: Int = 0,
    val totalDays: String = "",
    val dayTypes: List<String> = emptyList(),
    val finalApproverIds: List<String> = emptyList(),
    val internalReceiverIds: List<String> = emptyList(),
) {
    companion object {
        /** The three day types every production gets, custom ones merge after. */
        val STANDARD_DAY_TYPES = listOf("SWD", "CWD", "SCWD")
    }
}

/** A crew member, as the report's crew-times sections and pickers need them. */
data class SheetMember(
    val userId: String,
    val fullName: String,
    val department: String,
    val designation: String,
)

/** What the company-details section is pre-filled from. */
data class CompanySeed(
    val projectName: String = "",
    val companyName: String = "",
    val companyAddress: String = "",
)
