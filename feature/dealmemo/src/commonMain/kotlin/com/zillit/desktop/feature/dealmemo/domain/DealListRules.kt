package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** All Deals' status pills (`DMDealsPage.jsx:49-63`). */
enum class DealQuickFilter(val label: String) {
    All("All"),
    Drafts("Drafts"),
    Issued("Issued to Crew"),
    AwaitingApproval("Awaiting Approval"),

    /** Approved and already active. */
    Approved("Approved"),
    Rejected("Rejected"),

    /** Scheduled (active with a last pay date) and finished deactivations. */
    Deactivated("Deactivated"),
    ;

    fun matches(deal: DealDoc): Boolean = when (this) {
        All -> true
        Drafts -> deal.rawStatus == DealStatus.Draft.wire
        Issued -> deal.rawStatus == DealStatus.Issued.wire
        AwaitingApproval -> deal.rawStatus == DealStatus.AwaitingApproval.wire
        Approved -> deal.rawStatus == DealStatus.Approved.wire || deal.rawStatus == DealStatus.Active.wire
        Rejected -> deal.rawStatus == DealStatus.Rejected.wire
        Deactivated -> deal.isDeactivating || deal.rawStatus == DealStatus.Deactivated.wire
    }
}

/** All Deals' sort select. */
enum class DealSort(val label: String) {
    DateDesc("Date ↓"),
    DateAsc("Date ↑"),
    NameAsc("Crew A–Z"),
    RateDesc("Day Rate ↓"),
}

/** One department option of the All Deals select. */
data class DepartmentOption(val id: String, val label: String)

/**
 * All Deals' client-side pipeline and row rules (`DMDealsPage.jsx`), kept
 * apart from the page so the ordering and gates can be pinned.
 */
object DealListRules {

    /** Status, then department, then search, then sort — over one fetch. */
    fun visible(
        rows: List<DealDoc>,
        filter: DealQuickFilter,
        departmentId: String?,
        query: String,
        sort: DealSort,
        labels: DealCrewLabels,
    ): List<DealDoc> {
        val q = query.trim().lowercase()
        return rows
            .filter(filter::matches)
            .filter { departmentId == null || it.departmentId == departmentId }
            .filter { q.isEmpty() || matchesSearch(it, q, labels) }
            .let { sorted(it, sort) }
    }

    /** Reference, resolved name, crew name, resolved department and role, and the master designation id. */
    fun matchesSearch(deal: DealDoc, q: String, labels: DealCrewLabels): Boolean {
        val person = labels.labels(deal)
        return listOf(deal.reference, person.name, deal.crewName, person.department, person.role, deal.designationId)
            .any { it.orEmpty().lowercase().contains(q) }
    }

    private fun sorted(rows: List<DealDoc>, sort: DealSort): List<DealDoc> = when (sort) {
        DealSort.DateDesc -> rows.sortedByDescending { it.createdAt ?: 0L }
        DealSort.DateAsc -> rows.sortedBy { it.createdAt ?: 0L }
        DealSort.NameAsc -> rows.sortedWith(
            compareBy<DealDoc> { it.crewName.orEmpty().lowercase() }.thenBy { it.crewName.orEmpty() },
        )
        DealSort.RateDesc -> rows.sortedByDescending { it.dailyRate ?: 0.0 }
    }

    /**
     * The Dept select: the loaded rows' top-level master departments in
     * first-seen order, dropping any the catalogue cannot name yet.
     */
    fun departmentOptions(rows: List<DealDoc>, labels: DealCrewLabels): List<DepartmentOption> =
        rows.mapNotNull { it.departmentId }
            .distinct()
            .map { DepartmentOption(it, labels.departmentLabel(it)) }
            .filter { it.label != DealCrewLabels.DASH }

    /** `nominalCodingApplies`: not a draft or rejected deal, and not on its way out. */
    fun nominalCodingApplies(deal: DealDoc): Boolean =
        deal.rawStatus != DealStatus.Draft.wire && deal.rawStatus != DealStatus.Rejected.wire && !deal.isDeactivating

    /** Pending only on an explicit `is_nominal: false` — a missing flag is not pending. */
    fun nominalsPending(deal: DealDoc): Boolean = nominalCodingApplies(deal) && deal.isNominal == false

    fun canDelete(deal: DealDoc, canPost: Boolean, userId: String): Boolean =
        deal.rawStatus in DELETABLE && (canPost || (deal.createdBy != null && deal.createdBy == userId))

    /** Issued deals, posting users only, and never your own. */
    fun canChase(deal: DealDoc, canPost: Boolean, userId: String): Boolean =
        deal.rawStatus == DealStatus.Issued.wire && canPost && deal.userId != userId

    fun chaseOverdue(deal: DealDoc, nowMillis: Long): Boolean = deal.completionDue?.let { it < nowMillis } == true

    fun canActivate(deal: DealDoc): Boolean = deal.rawStatus == DealStatus.Approved.wire

    /** `Weekly Rolling`, `Fixed Term`… — any other type humanised, nothing a dash. */
    fun dealTypeLabel(type: String?): String = when (type) {
        null, "" -> DealCrewLabels.DASH
        "weekly" -> "Weekly Rolling"
        "fixed" -> "Fixed Term"
        "dayplayer", "daily" -> "Day Player"
        "buyout", "buy-out" -> "Buy-Out"
        "picture" -> "Picture Deal"
        "boxrental" -> "Box Rental Only"
        else -> DealLabels.formatLabel(type)
    }

    /** `£1,250.00` — the daily rate at two places, no symbol for a deal with no currency. */
    fun dayRate(deal: DealDoc): String =
        RateFormat.currencySymbol(deal.contractCurrency) + RateFormat.groupAmount(deal.dailyRate ?: 0.0)

    /** The delete confirmation, worded by status. */
    fun deleteMessage(deal: DealDoc): String {
        val what = when (deal.rawStatus) {
            DealStatus.AwaitingApproval.wire -> "the awaiting-approval deal"
            DealStatus.Rejected.wire -> "the rejected deal"
            DealStatus.Issued.wire -> "the ISSUED deal (the crew already have its portal link)"
            else -> "draft"
        }
        val name = deal.reference ?: deal.crewName ?: "this deal memo"
        return "Are you sure you want to delete $what \"$name\"? This action cannot be undone."
    }

    private val DELETABLE = setOf(DealStatus.Draft.wire, DealStatus.Issued.wire, DealStatus.AwaitingApproval.wire)
}

/** The deal-memo date formats (`en-GB`), in the zones the web renders them in. */
object DealDates {

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** `01 Jun 26` in the reader's zone — list dates. */
    fun short(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        millis?.let { format(it, zone, longYear = false) } ?: DealCrewLabels.DASH

    /** `15 Jul 26` in UTC — deactivation dates, which are UTC midnights. */
    fun shortUtc(millis: Long?): String = short(millis, TimeZone.UTC)

    /** `12 Aug 2026` in UTC — the notice letter's dates; blank when missing. */
    fun longUtc(millis: Long?): String = millis?.let { format(it, TimeZone.UTC, longYear = true) }.orEmpty()

    /** `01 Jul 26, 10:00` in the reader's zone — the notice sent stamp. */
    fun shortDateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        millis?.let { "${format(it, zone, longYear = false)}, ${time(it, zone)}" }.orEmpty()

    /** `12 Aug 2026, 14:05` in the reader's zone — history rows. */
    fun longDateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        millis?.let { "${format(it, zone, longYear = true)}, ${time(it, zone)}" } ?: DealCrewLabels.DASH

    /** `YYYY-MM-DD` of the UTC day — what the date inputs are seeded with. */
    fun utcIsoDate(millis: Long): String = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
        .let { "${it.year}-${pad(it.month.ordinal + 1)}-${pad(it.day)}" }

    private fun format(millis: Long, zone: TimeZone, longYear: Boolean): String {
        val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        val year = if (longYear) local.year.toString() else pad(local.year % CENTURY)
        return "${pad(local.day)} ${MONTHS[local.month.ordinal]} $year"
    }

    private fun time(millis: Long, zone: TimeZone): String =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let { "${pad(it.hour)}:${pad(it.minute)}" }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private const val CENTURY = 100
}
