package com.zillit.desktop.feature.dealmemo.domain

import kotlinx.serialization.json.JsonObject

/**
 * A deal memo exactly as the server stored it, with readers for the fields the
 * screens use.
 *
 * Kept as the document rather than a model because the server keeps whatever
 * it is sent: a save built from a model forgets every key the model does not
 * name, and on a deal those keys are a crew member's bank details, passport
 * uploads and signatures. The earlier port lost exactly those that way.
 */
data class DealDoc(val json: JsonObject) {

    val id: String get() = DocRead.text(json, "_id") ?: DocRead.text(json, "id").orEmpty()

    val rawStatus: String? get() = DocRead.text(json, "status")

    /** Unknown or missing reads as Draft, the web's `getStatusMeta` fallback. */
    val status: DealStatus get() = DealStatus.from(rawStatus)

    /** `DRFT-…` until promoted, `DM-…` after. */
    val reference: String? get() = DocRead.text(json, "deal_reference")

    /** The crew member the deal is for — not whoever wrote it. */
    val userId: String? get() = DocRead.text(json, "user_id")
    val createdBy: String? get() = DocRead.text(json, "created_by")
    val createdAt: Long? get() = DocRead.epoch(json, "created_at")
    val updatedAt: Long? get() = DocRead.epoch(json, "updated_at")
    val issuedAt: Long? get() = DocRead.epoch(json, "issued_at")

    /** The crew member's Zillit-master department, copied at save — what approval tiers scope by. */
    val departmentId: String? get() = DocRead.text(json, "department_id")
    val designationId: String? get() = DocRead.text(json, "designation_id")

    val crew: JsonObject? get() = DocRead.obj(json, "crew_details")
    val terms: JsonObject? get() = DocRead.obj(json, "deal")
    val rates: JsonObject? get() = DocRead.obj(json, "rates")

    val crewName: String? get() = DocRead.text(crew, "crew_name")
    val fullLegalName: String? get() = DocRead.text(crew, "full_legal_name")
    val customDesignation: String? get() = DocRead.text(crew, "custom_designation")

    /** `department_identifier || department_id` — the identifier first, as every reader takes it. */
    val crewDepartmentRef: String?
        get() = DocRead.text(crew, "department_identifier") ?: DocRead.text(crew, "department_id")

    val crewDesignationRef: String?
        get() = DocRead.text(crew, "designation_identifier") ?: DocRead.text(crew, "designation_id")

    /** `crew_details.is_external ?? is_external` — the explicit flag, for the External chip and portal links. */
    val externalFlag: Boolean
        get() = if (crew?.let { DocRead.present(it, "is_external") } == true) {
            DocRead.flag(crew, "is_external")
        } else {
            DocRead.flag(json, "is_external")
        }

    val dailyRate: Double? get() = DocRead.number(DocRead.obj(rates, "daily"), "rate")
    val contractCurrency: String? get() = DocRead.text(rates, "contract_currency")

    val dealType: String? get() = DocRead.text(terms, "type")
    val startDate: Long? get() = DocRead.epoch(terms, "start_date")
    val endDate: Long? get() = DocRead.epoch(terms, "end_date")

    /** When the crew member's details are due — Chase reads "Overdue" past it. */
    val completionDue: Long? get() = DocRead.epoch(terms, "deal_completion_due")
    val noticePeriod: String? get() = DocRead.text(terms, "notice_period")

    /** A scheduled deactivation — UTC midnight of the chosen day. Not on list rows. */
    val lastPayDate: Long? get() = DocRead.epoch(json, "last_pay_date")

    /** The last pay day a notice named — noon UTC. */
    val lastPayDay: Long? get() = DocRead.epoch(json, "last_pay_day")

    val noticeSent: Boolean get() = DocRead.text(json, "notice_status") == NOTICE_SENT
    val noticeSentAt: Long? get() = DocRead.epoch(json, "notice_sent_at")
    val noticeSentBy: String? get() = DocRead.text(json, "notice_sent_by")
    val noticeSentByName: String? get() = DocRead.text(json, "notice_sent_by_name")

    /** `false` means coding is PENDING; absent is unknown, never pending. */
    val isNominal: Boolean? get() = DocRead.bool(json, "is_nominal")

    val amendmentAck: AmendmentAck get() = AmendmentAck.from(DocRead.text(DocRead.obj(json, "amendment_ack"), "status"))

    /** Still active, with a last pay date set — the client-only "Deactivating" state. */
    val isDeactivating: Boolean get() = rawStatus == DealStatus.Active.wire && lastPayDate != null

    private companion object {
        const val NOTICE_SENT = "sent"
    }
}

/** One line of a deal's audit trail. */
data class DealHistoryEntry(
    val action: String,
    val actionBy: String?,
    val actionAt: Long?,
    val note: String?,
)
