package com.zillit.desktop.feature.payroll.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The payroll service, as the three payroll screens read and move it.
 *
 * ## The unit is a week of timecards, not a run
 *
 * `/api/v2/payroll/runs` declares a run CRUD surface that does not exist on
 * the server; payroll's real unit is a pay-period week, and every transition
 * is done to timecards (`/payroll/timecards/weekly/…`). The run routes that do
 * exist — the journal ledger and the exports — are in [journal] and
 * [PayrollDocuments].
 */
interface PayrollRepository {

    /**
     * Socket announcements that the week's rows changed somewhere — a final
     * approval, a lock, a payment or a post on another client — answered with
     * a reload rather than an in-place patch (the web's `ah:payroll:list`).
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    /** The journal ledger's reads and writes. */
    val journal: PayrollJournalRepository

    /** Claims and deductions on one timecard. */
    val adjustments: PayrollAdjustmentRepository

    /** The settings, rights and reference data the screens are drawn against. */
    val settings: PayrollSettingsRepository

    /** `/weekly/{ws}/paid` — the history queue: paid rows, and the posted ones for the audit. */
    suspend fun paidCrew(weekStarting: Long): ZillitResult<List<PayrollTimecard>>

    /** `/weekly/{ws}/processing` — the run's week, full documents. */
    suspend fun runQueue(weekStarting: Long): ZillitResult<List<PayrollTimecard>>

    /** `/weekly/processing` — the processing queue; the server picks the week. */
    suspend fun processingQueue(): ZillitResult<ProcessingQueue>

    /** Every crew member's unsettled weeks, one row per timecard. */
    suspend fun outstanding(): ZillitResult<List<PayrollTimecard>>

    /** Every week one crew member has a timecard for, newest first. */
    suspend fun outstandingFor(userId: String): ZillitResult<List<PayrollTimecard>>

    /** One full timecard. */
    suspend fun timecard(timecardId: String): ZillitResult<PayrollTimecard>

    /** `POST /batch/final-approve` — approved rows to ACCT Approved. */
    suspend fun finalApprove(timecardIds: List<String>): ZillitResult<BatchOutcome>

    /** `POST /batch/lock` — ACCT Approved rows to locked. */
    suspend fun lock(timecardIds: List<String>): ZillitResult<BatchOutcome>

    /** `POST /{id}/unlock` — a locked row back to ACCT Approved. */
    suspend fun unlock(timecardId: String): ZillitResult<String?>

    /** `POST /{id}/override` — force-approve past every approval tier. */
    suspend fun override(timecardId: String, reason: String): ZillitResult<String?>

    /** `POST /{id}/mark-paid`. */
    suspend fun markPaid(timecardId: String): ZillitResult<String?>

    /** `POST /batch/mark-paid` — locked and unpaid rows to paid. */
    suspend fun markPaidBatch(timecardIds: List<String>): ZillitResult<BatchOutcome>

    /** `POST /{id}/mark-unpaid` — a paid row reversed. */
    suspend fun markUnpaid(timecardId: String): ZillitResult<String?>

    /**
     * `POST /batch/mark-posted` — paid rows to the ledger with a settling
     * account and an effective date, both of which the server requires.
     */
    suspend fun markPosted(timecardIds: List<String>, bankId: String, effectiveDate: Long): ZillitResult<PostOutcome>
}

/** What the screens are drawn against: settings, rights and reference data from four hosts. */
interface PayrollSettingsRepository {

    /** `/payroll/metadata` merged with the account hub's `/payroll-settings`. */
    suspend fun metadata(): ZillitResult<PayrollMetadata>

    /**
     * Whether Override Approval is offered — the web's
     * `useTimecardOverrideGate`: `/payroll/timecards/metadata`'s
     * `is_accountant` and `is_approver`. Fails closed.
     */
    suspend fun overrideFlags(): ZillitResult<OverrideFlags>

    /** The production's settling accounts (`entity_type=production`). */
    suspend fun bankAccounts(): ZillitResult<List<BankAccount>>

    /**
     * The last closed cost-report date as `YYYY-MM-DD`, or null for none — the
     * lock route and the project settings, whichever is later (the web's
     * `useCrLock`).
     */
    suspend fun lockedDate(): ZillitResult<String?>

    /** Production Setup → Companies: the legal entity a payslip is headed with. */
    suspend fun companies(): ZillitResult<List<PayrollCompany>>

    /** The crew member's active deal's nominal codes, or null for none. */
    suspend fun activeDealCoding(userId: String): ZillitResult<DealCoding?>
}

/** A production company — the payslip's letterhead. */
data class PayrollCompany(val id: String, val name: String, val country: String?)

/** `/payroll/timecards/metadata`'s two flags. */
data class OverrideFlags(val isAccountant: Boolean, val isApprover: Boolean) {
    /** `(server accountant || profile accountant) && !approver` — the web's gate. */
    fun canOverride(profileAccountant: Boolean): Boolean = (isAccountant || profileAccountant) && !isApprover
}

/** What a batch transition moved, and the server's message about it. */
data class BatchOutcome(val marked: List<String>, val skipped: Int, val message: String?)

/** The processing queue and the week the server picked for it. */
data class ProcessingQueue(val weekStarting: Long?, val timecards: List<PayrollTimecard>)

/** The journal ledger's surface — `/payroll/runs/journal-ledger`. */
interface PayrollJournalRepository {

    /**
     * The saved coding for the given timecards, keyed by the timecards — not
     * by week, because a week's queue can hold a late approval from another
     * week.
     */
    suspend fun coding(timecardIds: List<String>, weekStarting: Long): ZillitResult<JournalCoding>

    /** Saves the coding, or posts it: [JournalSubmission.post] decides. */
    suspend fun submit(submission: JournalSubmission): ZillitResult<JournalPosted>
}

/** Claims and deductions on one timecard; each write answers the updated timecard. */
interface PayrollAdjustmentRepository {

    /** The crew member's payroll-routed cash-expense batches, not yet attached. */
    suspend fun pendingClaims(userId: String): ZillitResult<List<PendingClaim>>

    suspend fun attachBatch(timecardId: String, batchId: String): ZillitResult<PayrollTimecard?>

    suspend fun attachManual(timecardId: String, claim: ManualClaim): ZillitResult<PayrollTimecard?>

    suspend fun detachClaim(timecardId: String, claim: ClaimLine): ZillitResult<PayrollTimecard?>

    suspend fun addDeduction(timecardId: String, deduction: ManualClaim): ZillitResult<PayrollTimecard?>

    suspend fun removeDeduction(timecardId: String, deductionId: String): ZillitResult<PayrollTimecard?>
}

/** A cash-expense batch routed to payroll. */
data class PendingClaim(
    val id: String,
    val reference: String?,
    /** `pc` is petty cash (CASH); anything else out of pocket (OOP). */
    val expenseType: String?,
    val claimCount: Int,
    val postedAt: Long?,
    val amount: Double,
    val currency: String?,
) {
    val isCash: Boolean get() = expenseType.equals("pc", ignoreCase = true)
}

/** A one-off line: a manual claim or a flat deduction. */
data class ManualClaim(val name: String, val amount: Double, val currency: String?, val nominalCode: String?)

/**
 * Files the payroll service renders — payslips and the run summary as PDF,
 * the processing workbooks as XLSX. Binary, so outside the envelope client.
 */
interface PayrollDocuments {
    /** `POST /runs/payslip {week_starting, user_id}` — one crew member's A4 payslip. */
    suspend fun payslip(weekStarting: Long, userId: String): ZillitResult<ByteArray>

    /** `POST /runs/export-summary {week_starting, format}`. */
    suspend fun runSummary(weekStarting: Long, format: ExportFormat): ZillitResult<ByteArray>

    /** `GET /timecards/weekly/payroll-processing/{ws}/csv`. */
    suspend fun weekWorkbook(weekStarting: Long): ZillitResult<ByteArray>

    /** `GET /timecards/weekly/payroll-processing/outstanding/csv`. */
    suspend fun outstandingWorkbook(): ZillitResult<ByteArray>
}

/** The run summary's formats, as the web's `normalizeFormat` spells them. */
enum class ExportFormat(val wire: String) { Pdf("pdf"), Excel("xlsx"), Csv("csv") }

/** Saves a rendered file and hands it to the OS. */
fun interface PayrollFiles {
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
