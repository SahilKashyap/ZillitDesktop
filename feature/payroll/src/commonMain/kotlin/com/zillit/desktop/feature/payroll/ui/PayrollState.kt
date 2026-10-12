package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.DealCoding
import com.zillit.desktop.feature.payroll.domain.DealRates
import com.zillit.desktop.feature.payroll.domain.EstimatedDay
import com.zillit.desktop.feature.payroll.domain.PayrollCrewRow
import com.zillit.desktop.feature.payroll.domain.PayrollCurrencyRates
import com.zillit.desktop.feature.payroll.domain.Employment
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalCoding
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalReference
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.OverrideFlags
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollCompany
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.PendingClaim
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.RunRow
import com.zillit.desktop.feature.payroll.domain.SendToRun
import com.zillit.desktop.feature.payroll.domain.TimecardNote
import com.zillit.desktop.feature.payroll.domain.TimecardStatus

/** Everything the payroll tool is showing. */
data class PayrollUiState(
    val viewer: PayrollViewer,
    val destination: PayrollDestination = PayrollDestination.Landing,
    /** The route carried the web's `?entry=tool` marker — the producer's entry. */
    val enteredAsTool: Boolean = false,
    val metadata: PayrollMetadata = PayrollMetadata(),
    /** False until `/payroll/metadata` has answered: the week boundary depends on it. */
    val metadataLoaded: Boolean = false,
    val overrideFlags: OverrideFlags? = null,
    /** The last closed cost-report date, `YYYY-MM-DD`; null for no lock. */
    val lockedDate: String? = null,
    val companies: List<PayrollCompany> = emptyList(),
    /**
     * The production's default currency — the last tier of the timecard
     * currency policy, and what a manual claim is saved in when the timecard
     * itself states none.
     */
    val defaultCurrency: String? = null,
    /** The project currencies' rates against the default, for converting a mixed-currency total. */
    val currencyRates: PayrollCurrencyRates = PayrollCurrencyRates(),
    val people: Map<String, PayrollPerson> = emptyMap(),
    val projectName: String = "",
    /** Epoch millis of now, read when the tool opened — the current week and today's date. */
    val now: Long = 0L,
    /**
     * The production's pay engine is available. False leaves the surfaces that
     * price locally saying so, which is what the web does when its own bundle
     * fails to load.
     */
    val hasPayEngine: Boolean = false,
    /**
     * Each crew member's holiday-pay rate, once resolved. Holiday pay is never
     * stored on a timecard — the only read endpoint is scoped to the
     * requesting user — so any surface showing someone else's accrual derives
     * it as `basic × rate` from their deal (the web's `useCrewHolidayPay`).
     */
    val holidayPayRates: Map<String, Double> = emptyMap(),
    val history: HistoryState = HistoryState(),
    val run: RunState = RunState(),
    val processing: ProcessingState = ProcessingState(),
    val producer: ProducerBoardState = ProducerBoardState(),
    val estimate: ProductionReportState = ProductionReportState(),
    /** Override Approval, over whichever screen asked for it. */
    val override: OverridePrompt? = null,
    /** Claims or deductions, over whichever screen asked for them. */
    val adjustment: AdjustmentDialog? = null,
    /** The Notes on a week, over whichever screen asked. */
    val notesView: NotesView? = null,
    /** A week read-only in the wide modal — "View Time Card" — over whichever screen asked. */
    val timecardView: TimecardView? = null,
    val notice: String? = null,
) {
    /** Override Approval is offered — fails closed until the flags have answered. */
    val canOverride: Boolean get() = overrideFlags?.canOverride(viewer.isAccountant) == true

    /** The current pay period's start, in UTC, on the production's own start day. */
    val currentWeek: Long get() = PayPeriod.startOf(now, metadata.payPeriodStartDay)

    /** The first date a post may carry: the day after the lock, or any day when there is none. */
    val earliestEffectiveDate: String? get() = PayPeriod.dayAfter(lockedDate)

    /** Today, `YYYY-MM-DD` in UTC — the default effective date, as the web's `toISOString()` gives it. */
    val todayIso: String get() = PayPeriod.isoDate(now)

    /** A crew member's name — never their raw id, which the web's fallback would print. */
    fun nameOf(userId: String): String = people[userId]?.fullName?.takeIf { it.isNotBlank() } ?: userId.orDash()

    /** A crew member's designation, translated, or blank. */
    fun roleOf(userId: String): String = people[userId]?.designation?.localised().orEmpty()

    /** A crew member's department, translated, or "Unassigned". */
    fun departmentOf(userId: String): String =
        people[userId]?.department?.takeIf { it.isNotBlank() }?.localised() ?: str(S.unassigned)

    /**
     * The week's holiday-pay accrual for a timecard, or zero when the deal has
     * no holiday-pay line (or the rate has not been resolved). Σ(basic × rate)
     * is rate × Σ basic, so the weekly figure matches what the accrual
     * collection records for the same week.
     */
    fun holidayPayFor(timecard: PayrollTimecard?): Double {
        val rate = holidayPayRates[timecard?.userId] ?: return 0.0
        val basic = timecard?.basicPay ?: return 0.0
        return if (rate > 0 && basic > 0) PayrollTimecard.round2(basic * rate) else 0.0
    }
}

// -- Payroll History -----------------------------------------------------------------------

enum class HistoryTab { PayCode, Payslip, Audit }

/**
 * Payroll History — the web's `AccountantPayrollModule`: the week's paid
 * timecards (and the posted ones, for the record), one crew member opened
 * beside them.
 */
data class HistoryState(
    val weekStarting: Long? = null,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    val rows: List<PayrollTimecard> = emptyList(),
    val search: String = "",
    /** The row open on the right. */
    val selectedId: String? = null,
    val detail: PayrollTimecard? = null,
    val detailLoading: Boolean = false,
    val deal: DealCoding? = null,
    val tab: HistoryTab = HistoryTab.PayCode,
    val payslipBusy: Boolean = false,
) {
    /** Paid rows not yet posted — History's "ready to post" count (posting itself is the Run's Journal Ledger). */
    val readyIds: List<String> get() = rows.filter { it.status.isPostable }.map { it.id }

    val postedCount: Int get() = rows.count { it.status.isPosted }

    val selectedRow: PayrollTimecard? get() = rows.firstOrNull { it.id == selectedId }
}

// -- Payroll Run ---------------------------------------------------------------------------

/** The Run's two status filters, shared by the chips and the sidebar. */
enum class RunStatusFilter { All, Pending, Approved }

/** Payroll Run — the web's `PayrollRunModule`: the week's crew, their approvals, and the journal. */
data class RunState(
    val weekStarting: Long? = null,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    val timecards: List<PayrollTimecard> = emptyList(),
    val search: String = "",
    val status: RunStatusFilter = RunStatusFilter.All,
    val department: String? = null,
    val employment: Employment? = null,
    val selected: Set<String> = emptySet(),
    val confirm: RunConfirm? = null,
    /** The row whose own button is working. */
    val busyRowId: String? = null,
    val exporting: Boolean = false,
    val drawerId: String? = null,
    val drawer: PayrollTimecard? = null,
    val drawerLoading: Boolean = false,
    val journalOpen: Boolean = false,
    val journal: JournalState = JournalState(),
)

/**
 * A bulk transition waiting on confirmation. [thenPost] is the journal's
 * "…& Post": the lock blockers are moved first and the post is retried.
 * [thenExport] is Export CSV's "…& Export": the same, then the workbook
 * downloads — or, from "Export Only", just the workbook.
 */
data class RunConfirm(
    val action: RunAction,
    val ids: List<String>,
    /** For approve-and-lock: the ACCT Approved rows locked alongside. */
    val alsoLockIds: List<String> = emptyList(),
    val selectedTotal: Int,
    val runScope: Boolean = false,
    val thenPost: Boolean = false,
    val thenExport: Boolean = false,
    val working: Boolean = false,
)

/** Anything a row of the grid can be — crew row with the names already resolved. */
fun PayrollUiState.runRows(): List<RunRow> = run.timecards.map { timecard ->
    RunRow(
        timecard = timecard,
        name = nameOf(timecard.userId),
        role = people[timecard.userId]?.designation.orEmpty(),
        department = people[timecard.userId]?.department.orEmpty(),
    )
}

// -- Journal Ledger ------------------------------------------------------------------------

/** The journal's working state: the accountant's edits over the rows the week derives. */
data class JournalState(
    val coding: JournalCoding =
        JournalCoding(),
    val edits: Map<String, JournalEdit> = emptyMap(),
    /** The header's effective date, `YYYY-MM-DD` or blank. */
    val headerDate: String = "",
    val saving: Boolean = false,
    val post: JournalPostDialog? = null,
    /** A reason the post cannot go yet — the web's alert. */
    val alert: JournalAlert? = null,
    /** Rows whose missing fields are drawn red after a refused post. */
    val flagged: Set<String> = emptySet(),
    /** Bumped by a refused post so every collapsed company group opens onto its red cells. */
    val expandSignal: Int = 0,
    /** The production's tracking sets and account tags, for the Layers and Tags cells. */
    val reference: JournalReference = JournalReference(),
    /**
     * Timecards the accountant has added a tax line to, and removed one from.
     *
     * Held apart from the rows because a tax line's absence is not something
     * the wire can state: the builder makes one for every timecard, and these
     * two sets decide which are on screen until the next save settles it.
     */
    val taxAdded: Set<String> = emptySet(),
    val taxRemoved: Set<String> = emptySet(),
    /**
     * The tax additions and removals a save has just stored. They stay in force
     * until the re-read lands — dropping them sooner would show a deleted line
     * again, or hide an added one, for the moment the old coding is still on
     * screen — and are then forgotten, since the server now holds the answer.
     */
    val taxSettling: TaxSettling? = null,
) {
    /** What waits to be saved: edited rows on screen, and each tax line added or removed. */
    fun pendingCount(rows: List<JournalRow>): Int = Journal.pendingCount(rows, edits, taxAdded, taxRemoved)

    /** The re-read coding landed: it holds what the last save stored, so that tax bookkeeping is spent. */
    fun settled(fresh: JournalCoding): JournalState = copy(
        coding = fresh,
        taxAdded = taxAdded - taxSettling?.added.orEmpty(),
        taxRemoved = taxRemoved - taxSettling?.removed.orEmpty(),
        taxSettling = null,
    )
}

/** The tax lines a save stored, to be released once the week has been re-read. */
data class TaxSettling(val added: Set<String>, val removed: Set<String>)

data class JournalPostDialog(
    val timecardIds: List<String>,
    val lineCount: Int,
    val gross: Double,
    val currency: String?,
    /** `YYYY-MM-DD` — the default for lines without their own date. */
    val effectiveDate: String,
    val saving: Boolean = false,
    val error: String? = null,
)

data class JournalAlert(val title: String, val message: String, val fix: RunConfirm? = null)

// -- Payroll Processing --------------------------------------------------------------------

enum class ProcessingView { Daily, WeekToDate, Weekly, Outstanding }

enum class ProcessingNav { All, Pending, Approved }

/** Payroll Processing — the web's `PayrollGridModule`. */
data class ProcessingState(
    val weekStarting: Long? = null,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    val timecards: List<PayrollTimecard> = emptyList(),
    /** The web opens on Outstanding. */
    val view: ProcessingView = ProcessingView.Outstanding,
    val nav: ProcessingNav = ProcessingNav.All,
    val department: String? = null,
    val search: String = "",
    /** 0-6 within the week, for Day View. */
    val day: Int = 0,
    val outstanding: List<PayrollTimecard> = emptyList(),
    val outstandingLoading: Boolean = false,
    val outstandingLoaded: Boolean = false,
    val busyRowId: String? = null,
    val drawerId: String? = null,
    val drawer: PayrollTimecard? = null,
    val drawerLoading: Boolean = false,
    val exportOpen: Boolean = false,
    val exporting: Boolean = false,
    val detail: OutstandingDetail? = null,
)

/** One crew member's unsettled weeks, opened from the Outstanding view. */
data class OutstandingDetail(
    val userId: String,
    val weeks: List<PayrollTimecard> = emptyList(),
    val loading: Boolean = true,
    val error: ZillitError? = null,
    /** The weeks ticked for Send to Payroll Run, by timecard id. */
    val picked: Set<String> = emptySet(),
    val send: SendToRunPrompt? = null,
) {
    /** The weeks the Run would accept — only these are offered a checkbox. */
    val sendable: List<PayrollTimecard> get() = weeks.filter(SendToRun::canSend)

    /** The ticked weeks that are still sendable, in the list's order. */
    val chosen: List<PayrollTimecard> get() = sendable.filter { it.id in picked }
}

/** Send to Payroll Run's confirm: the weeks it names, and the optional reason. */
data class SendToRunPrompt(
    val timecardIds: List<String>,
    val weekLabels: List<String>,
    val reason: String = "",
    val sending: Boolean = false,
)

// -- Producer Board ------------------------------------------------------------------------

/**
 * Producer Board Payroll Status — the web's `ProducerBoardModule`: the week's
 * timecards down the left, the one that is selected read-only on the right.
 *
 * Read-only by design. The web removed the board's override / approve /
 * submit actions when the producer flow was respecified: "the producer
 * reviews and adds claims; approval lives elsewhere in the chain"
 * (`api/payroll/producer-board.js`).
 */
data class ProducerBoardState(
    val weekStarting: Long? = null,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    val crew: List<PayrollCrewRow> = emptyList(),
    /** The row open on the right — a timecard id. */
    val selectedId: String? = null,
    val timecard: PayrollTimecard? = null,
    val timecardLoading: Boolean = false,
) {
    val selectedRow: PayrollCrewRow? get() = crew.firstOrNull { it.id == selectedId }
}

// -- Production Report Payroll -------------------------------------------------------------

/**
 * Production Report Payroll — the web's `ProductionReportPayrollModule`: every
 * crew member who HAS A DEAL, week by week, whether or not they have a
 * timecard yet. A blank day can be filled from the unit's production report
 * and priced locally; nothing is ever saved.
 */
data class ProductionReportState(
    val weekStarting: Long? = null,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    /** Project members with a deal — the roster, independent of timecards. */
    val crew: List<PayrollPerson> = emptyList(),
    /** The week's existing timecards, by crew member, from the slim crew list. */
    val existing: Map<String, PayrollCrewRow> = emptyMap(),
    val selectedUserId: String? = null,
    /** The selected crew member's real timecard, when they have one. */
    val timecard: PayrollTimecard? = null,
    val timecardLoading: Boolean = false,
    /** Their active deal, loaded so the estimate has rates. */
    val deal: DealRates? = null,
    val dealLoading: Boolean = false,
    /** Filled days per crew member, seven slots each; null where nothing is filled. */
    val filled: Map<String, List<EstimatedDay?>> = emptyMap(),
    /** The day epoch currently being filled, or null. */
    val fillingDate: Long? = null,
    /** A whole-week fill is running for the selected crew member. */
    val fillingAll: Boolean = false,
    /**
     * Auto-fill mode: each crew member opened from here on is estimated from
     * the report on arrival. Session-scoped, as the web's is — it survives
     * week paging, not a restart.
     */
    val autoFill: Boolean = false,
    /** Crew attempted this week, whatever the outcome — the web's `autoFilledRef`. */
    val attempted: Set<String> = emptySet(),
) {
    /** The selected crew member's filled days, seven slots. */
    val selectedDays: List<EstimatedDay?> get() = filled[selectedUserId] ?: EMPTY_WEEK

    val selectedExisting: PayrollCrewRow? get() = existing[selectedUserId]

    val selectedPerson: PayrollPerson? get() = crew.firstOrNull { it.userId == selectedUserId }

    /** Any fill in flight — every other action waits, because the pay engine is one engine. */
    val fillBusy: Boolean get() = fillingAll || fillingDate != null

    /** The status the sidebar pill shows — the web's `rowStatus`. */
    fun rowStatus(userId: String): EstimateStatus {
        existing[userId]?.let { return EstimateStatus.Real(it.status) }
        if (filled[userId].orEmpty().any { it != null }) return EstimateStatus.Estimate
        return if (autoFill) EstimateStatus.Auto else EstimateStatus.None
    }

    private companion object {
        val EMPTY_WEEK: List<EstimatedDay?> = List(PayPeriod.DAYS_IN_WEEK) { null }
    }
}

/** What a Production Report Payroll sidebar row says about a crew member's week. */
sealed interface EstimateStatus {
    /** They have a timecard; it speaks for itself. */
    data class Real(val status: TimecardStatus) : EstimateStatus

    /** Days have been priced from the report — money, locally computed. */
    data object Estimate : EstimateStatus

    /** Auto-fill is armed and will estimate them when they are opened. */
    data object Auto : EstimateStatus

    /** No timecard and no estimate. */
    data object None : EstimateStatus
}

// -- dialogs over any screen ---------------------------------------------------------------

/** Override Approval — force-approves a week past every approval tier. */
data class OverridePrompt(
    val timecardId: String,
    val name: String,
    val weekLabel: String,
    val reason: String = "",
    val saving: Boolean = false,
)

/** Claims or deductions on one timecard, edited in place. */
data class AdjustmentDialog(
    val kind: AdjustmentKind,
    val timecard: PayrollTimecard,
    val pending: List<PendingClaim> = emptyList(),
    val loading: Boolean = false,
    val name: String = "",
    val amount: String = "",
    val nominal: String = "",
    val busyKey: String? = null,
    val error: String? = null,
    /** The last refusal was the timecard being locked — the web's "Unlock this week". */
    val lockedRefusal: Boolean = false,
) {
    val readOnly: Boolean get() = timecard.status.refusesWrites

    /** A line needs a description and an amount above zero — the web's check. */
    val canAdd: Boolean get() = name.isNotBlank() && (amount.trim().toDoubleOrNull() ?: 0.0) > 0.0
}

enum class AdjustmentKind { Claims, Deductions }

/** The notes a crew member left on their week, read-only. */
data class NotesView(val crewName: String, val notes: List<TimecardNote>)

/**
 * A crew member's week in the wide read-only modal. A list row is a slim
 * projection with no days on it, so the full document is fetched while
 * [loading]; the row stands in meanwhile for the header.
 */
data class TimecardView(val timecard: PayrollTimecard, val loading: Boolean = false)

/** The statuses whose timecard the server refuses to change. */
internal val TimecardStatus.readOnly: Boolean get() = refusesWrites
