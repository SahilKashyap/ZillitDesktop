package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where a deal is — the server's statuses in the web's order
 * (`dealStatus.js:18-36`, `STATUS_ORDER` at `:62-72`):
 * draft → issued → awaiting_approval → approved → active → completed, with
 * rejected / cancelled / deactivated as branch states.
 */
enum class DealStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),

    /** With the crew member: fill in details, sign, send for approval. */
    Issued("issued", S.desktop_issued),
    AwaitingApproval("awaiting_approval", S.dm_filter_status_pending),
    Approved("approved", S.approved),
    Active("active", S.active),
    Completed("completed", S.completed),
    Rejected("rejected", S.rejected),
    Cancelled("cancelled", S.cancelled),

    /** Crew let go mid-engagement — terminal. */
    Deactivated("deactivated", S.dm_filter_status_deactivated),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** Unknown, blank or missing reads as Draft — `getStatusMeta`'s fallback. */
        fun from(wire: String?): DealStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Draft
        }
    }
}

/**
 * The `amendment_ack.status` enum: an accountant amended a live deal's pay
 * rules in place, and the crew member has or has not confirmed the change.
 */
enum class AmendmentAck(val wire: String) {
    None("none"),
    Pending("pending"),
    Acknowledged("acknowledged"),
    ;

    companion object {
        fun from(wire: String?): AmendmentAck {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: None
        }
    }
}

/** `GET /deals/overview` — the project-wide dashboard. */
data class DealOverview(
    val total: Double = 0.0,
    val approved: Double = 0.0,
    val awaitingApproval: Double = 0.0,
    /** Summed daily rates across the project, in whatever currencies they were agreed. */
    val totalValue: Double = 0.0,
    val active: Double = 0.0,
    val issued: Double = 0.0,
    val draft: Double = 0.0,
    val recent: List<DealDoc> = emptyList(),
    val departmentBreakdown: List<DepartmentCount> = emptyList(),
)

/** One bar of the overview's By Department card — `department` is the master `_id`. */
data class DepartmentCount(val department: String, val count: Double)

/** A deal-memo setup ("template") in the slim list. */
data class DealTemplateSummary(
    val id: String,
    val name: String,
    val createdBy: String? = null,
    val createdAt: Long? = null,
)

/** The export menu's three files. */
enum class DealExport(val wire: String, val extension: String) {
    RegisterPdf("pdf", "pdf"),
    RegisterExcel("xlsx", "xlsx"),
    StartForms("start-forms", "zip"),
}
