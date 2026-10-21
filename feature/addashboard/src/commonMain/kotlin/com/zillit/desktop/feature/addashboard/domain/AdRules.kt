package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The rules every AD screen asks rather than deciding for itself: whether the
 * day may still be edited, how far through the day the unit is, and how a
 * submitted day's purchase orders are grouped and referenced.
 */

private const val PHASE_ARRIVAL = 1
private const val PHASE_ATTENDANCE = 2
private const val PHASE_ADDITIONALS = 3
private const val PHASE_SUBMIT_PO = 4

/** One step of the day's phase stepper. */
enum class AdPhase(val number: Int, private val labelKey: String) {
    ArrivalAndLists(1, S.desktop_ad_phase_arrival),
    Attendance(2, S.desktop_ad_attendance),

    /**
     * Labelled "Additionals" but really "attendance complete, not everyone
     * wrapped" — ticking an allowance does not move it. The web's own label.
     */
    Additionals(PHASE_ADDITIONALS, S.desktop_ad_additionals),
    SubmitAndPo(PHASE_SUBMIT_PO, S.desktop_ad_phase_submit_po),
    ;

    val label: String get() = str(labelKey)
}

/** Where one phase stands. */
enum class PhaseState { Done, Active, Todo }

/**
 * How far through the day the unit is, worked out from today's rows.
 *
 * Deliberately not read off the day's status: the status comes from the server
 * and says nothing about whether everybody has been marked.
 */
object AdPhases {

    /**
     * Phase 4 reads done only when **every** row is submitted.
     *
     * Absent, blocked and draft rows included — which is why it rarely does:
     * submit moves only present rows, so one absentee keeps it active and the
     * Submit Day shortcut on screen. That is the web's behaviour, pinned here
     * so the two agree.
     */
    fun active(rows: List<SupportingArtistDay>): Int {
        if (rows.isEmpty()) return 0
        val anyMarked = rows.any { it.isHandled }
        val allHandled = rows.all { it.isHandled }
        val present = rows.filter { it.isPresent }
        val allWrapped = present.isEmpty() || present.all { it.isWrapped }
        val allSubmitted = rows.all { it.isSubmitted }
        return when {
            !anyMarked -> PHASE_ARRIVAL
            !allHandled -> PHASE_ATTENDANCE
            !allWrapped -> PHASE_ADDITIONALS
            !allSubmitted -> PHASE_SUBMIT_PO
            else -> COMPLETE
        }
    }

    /** Each phase with its state; nothing is active until the list has rows. */
    fun of(rows: List<SupportingArtistDay>): List<Pair<AdPhase, PhaseState>> {
        val live = active(rows)
        return AdPhase.entries.map { phase ->
            val state = when {
                live == 0 -> PhaseState.Todo
                phase.number < live -> PhaseState.Done
                phase.number == live -> PhaseState.Active
                else -> PhaseState.Todo
            }
            phase to state
        }
    }

    /** Whether the day is ready to submit — phase 4, the gated Submit step's gate. */
    fun readyToSubmit(rows: List<SupportingArtistDay>): Boolean = active(rows) >= AdPhase.SubmitAndPo.number

    /** Mid-day unlocks once everybody is present, absent or blocked. */
    fun attendanceComplete(rows: List<SupportingArtistDay>): Boolean =
        rows.isNotEmpty() && rows.all { it.isHandled }

    /** Wrap unlocks for submit once every present artiste has a time out. */
    fun everyoneWrapped(rows: List<SupportingArtistDay>): Boolean {
        val present = rows.filter { it.isPresent }
        return present.isEmpty() || present.all { it.isWrapped }
    }

    private const val COMPLETE = PHASE_SUBMIT_PO + 1
}

/**
 * Whether a day's fields are read-only.
 *
 * Both halves matter and they are fixed differently: a missing posting right
 * is something an admin can grant, while a locked day is shut to everyone
 * including them.
 */
fun isDayReadOnly(status: AdDayStatus, dateEditable: Boolean = true, canPost: Boolean = true): Boolean =
    status.locked || !dateEditable || !canPost

/**
 * Whether the day's own date still allows editing.
 *
 * Today and an unsubmitted **past** day may still be run and submitted; only a
 * **future** day is date-locked. Add Extras deliberately allows tomorrow, so it
 * passes its own answer rather than using this.
 */
fun isDateEditable(shootDate: Long, today: Long): Boolean =
    AdDates.utcMidnight(shootDate) <= AdDates.utcMidnight(today)

/** One agency's artistes on a day, in one currency — the unit a PO is raised for. */
data class AgencyGroup(
    val agency: String,
    val agencyId: String,
    val currency: String,
    val artistes: List<SupportingArtistDay>,
) {
    val amount: Double get() = artistes.sumOf { it.payTotal }

    /** No linked vendor means no PO: a PO with a null vendor is not a PO. */
    val hasVendor: Boolean get() = agencyId.isNotBlank()

    /** The key the retry guard and the pending check both use. */
    val key: String get() = "${agency}__$currency"
}

/** A currency's share of the day: the direct-PAYE side and the agency side. */
data class CurrencyTotals(
    val currency: String,
    val directCount: Int = 0,
    val directTotal: Double = 0.0,
    val agencyCount: Int = 0,
    val agencyTotal: Double = 0.0,
    val agencies: Set<String> = emptySet(),
) {
    val total: Double get() = directTotal + agencyTotal
}

/** How a submitted day is split up, and what reference its purchase orders carry. */
object SubmitRules {

    /**
     * One PO per agency **and currency**.
     *
     * One agency paid in two currencies gets two POs; direct-PAYE artistes are
     * never on one, because they go through payroll.
     */
    fun agencyGroups(rows: List<SupportingArtistDay>): List<AgencyGroup> = rows
        .filter { it.isPresent && it.engagement == EngagementType.Agency }
        .groupBy { (it.agencyName.ifBlank { str(S.desktop_dm_agency) }) to it.currency }
        .map { (key, artistes) ->
            AgencyGroup(
                agency = key.first,
                agencyId = artistes.firstOrNull { it.agencyId.isNotBlank() }?.agencyId.orEmpty(),
                currency = key.second,
                artistes = artistes,
            )
        }
        .sortedBy { it.agency }

    /** Per-currency totals, for the review panel above the sign-off. */
    fun currencyTotals(rows: List<SupportingArtistDay>): List<CurrencyTotals> {
        val present = rows.filter { it.isPresent }
        return present
            .groupBy { it.currency }
            .map { (currency, group) ->
                val direct = group.filter { it.engagement == EngagementType.DirectPaye }
                val agency = group.filter { it.engagement == EngagementType.Agency }
                CurrencyTotals(
                    currency = currency,
                    directCount = direct.size,
                    directTotal = direct.sumOf { it.payTotal },
                    agencyCount = agency.size,
                    agencyTotal = agency.sumOf { it.payTotal },
                    agencies = agency.mapNotNull { it.agencyName.takeIf(String::isNotBlank) }.toSet(),
                )
            }
            .sortedBy { it.currency }
    }

    /**
     * The day's PO reference: `PO-D<dayNumber>-<yyyymmdd>`.
     *
     * The day's own stored reference wins when it has one. Each PO's
     * description then carries a `-n` suffix.
     */
    fun poReference(day: AdShootDay?, shootDate: Long, dayNumber: Int?): String {
        day?.poReference?.takeIf { it.isNotBlank() }?.let { return it }
        return "PO-D${dayNumber ?: 0}-${AdDates.formatCompactYmd(day?.shootDate ?: shootDate)}"
    }

    /**
     * Which groups still have no saved PO on the day.
     *
     * A failed create therefore shows **Post PO** again, which is the point —
     * but only once the group has a vendor to raise it against.
     */
    fun pendingGroups(groups: List<AgencyGroup>, saved: List<DayPurchaseOrder>): List<AgencyGroup> =
        groups.filterNot { group ->
            saved.any { it.agency == group.agency && it.currency == group.currency }
        }

    /** The rows a submit would not move: absent, blocked or still draft. */
    fun exceptions(rows: List<SupportingArtistDay>): List<SupportingArtistDay> =
        rows.filterNot { it.isPresent }

    /**
     * Whether submit may go ahead.
     *
     * One present artiste, the posting right and a signature — deliberately
     * **not** a check that everybody is wrapped: present artistes with no time
     * out are submitted as they are, and only the gated runner waits for wraps.
     */
    fun ready(rows: List<SupportingArtistDay>, canEdit: Boolean, hasSignature: Boolean): Boolean =
        rows.any { it.isPresent } && canEdit && hasSignature
}

/** How the merged Wrap + AD report list is ordered and filtered. */
object ReportListRules {

    /** Newest shoot day first; on the same day Wrap before AD, as the phones order it. */
    fun sort(rows: List<ReportSummary>): List<ReportSummary> = rows.sortedWith(
        compareByDescending<ReportSummary> { it.shootDate ?: 0L }
            .thenBy { if (it.kind == ReportKind.WrapReport) 0 else 1 },
    )

    /**
     * The list's filters.
     *
     * A viewer without posting rights sees published reports only — hiding the
     * create and delete buttons alone would still leak a draft by URL.
     */
    @Suppress("LongParameterList")
    fun filter(
        rows: List<ReportSummary>,
        kind: ReportKind? = null,
        status: ReportStatus? = null,
        query: String = "",
        canPost: Boolean = true,
        day: Long? = null,
    ): List<ReportSummary> {
        val needle = query.trim().lowercase()
        return rows.filter { row ->
            when {
                !canPost && !row.status.published -> false
                day != null && row.shootDate != day -> false
                kind != null && row.kind != kind -> false
                status != null && row.status != status -> false
                needle.isEmpty() -> true
                else -> listOf(
                    row.dayNumber?.let { "sd $it" }.orEmpty(),
                    row.name,
                    row.templateName,
                    row.kind.label,
                ).joinToString(" ").lowercase().contains(needle)
            }
        }
    }

    /** The built-in template of each merged kind — the first per kind wins. */
    fun systemTemplates(all: List<ReportTemplate>): List<ReportTemplate> =
        ReportKind.merged.mapNotNull { kind ->
            all.firstOrNull { it.kind == kind && it.isBuiltIn }
        }

    /**
     * What a day link opens.
     *
     * The link names a shoot day but no kind: one report that day opens it,
     * several let the AD pick, none offers the templates.
     */
    fun dayLinkTarget(rows: List<ReportSummary>, day: Long): DayLinkTarget {
        val hits = rows.filter { it.shootDate == day }
        return when (hits.size) {
            0 -> DayLinkTarget.None
            1 -> DayLinkTarget.Open(hits.first())
            else -> DayLinkTarget.Choose
        }
    }
}

/** Where a day link lands. */
sealed interface DayLinkTarget {
    data class Open(val report: ReportSummary) : DayLinkTarget
    data object Choose : DayLinkTarget
    data object None : DayLinkTarget
}

/** Formats an amount the way every AD surface does: grouped, two decimals. */
fun formatAmount(value: Double, decimals: Int = DECIMAL_PLACES): String {
    val negative = value < 0
    val scale = when (decimals) {
        0 -> NO_SCALE
        1 -> TENTHS_SCALE
        else -> HUNDREDTHS_SCALE
    }
    // Rounded half away from zero, which is how every amount on screen reads.
    val scaled = kotlin.math.abs(value * scale + if (value < 0) -HALF else HALF).toLong()
    val whole = scaled / scale
    val fraction = scaled % scale
    val grouped = whole.toString()
        .reversed()
        .chunked(GROUP_DIGITS)
        .joinToString(",")
        .reversed()
    val body = if (decimals == 0) grouped else "$grouped.${fraction.toString().padStart(decimals, '0')}"
    return if (negative && scaled != 0L) "-$body" else body
}

private const val DECIMAL_PLACES = 2
private const val NO_SCALE = 1L
private const val TENTHS_SCALE = 10L
private const val HUNDREDTHS_SCALE = 100L
private const val HALF = 0.5
private const val GROUP_DIGITS = 3

/**
 * An amount with the row's own currency symbol.
 *
 * Money is per row on this service: each artiste-day carries its own
 * `currency`, so a page that used one project-wide symbol would print the
 * wrong one for an agreement paid in another.
 */
fun formatMoney(value: Double, symbol: String, decimals: Int = DECIMAL_PLACES): String =
    "$symbol${formatAmount(value, decimals)}"
