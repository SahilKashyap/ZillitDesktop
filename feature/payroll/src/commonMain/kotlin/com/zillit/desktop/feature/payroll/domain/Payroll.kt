package com.zillit.desktop.feature.payroll.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A timecard's own status, in the web's vocabulary (`lib/timecardStatus.js`).
 *
 * ## The payroll half of the lifecycle
 *
 * After the approval chain a timecard is `approved`; the accountant approver
 * moves it to `final_approved` ("ACCT Approved"); the payroll accountant locks
 * it (`locked`, sealed for the run); it is marked `paid`, which can be reversed
 * to `unpaid` and paid again; and a paid one is `posted` to the ledger. Every
 * transition below is the web's, and the server silently skips a row in the
 * wrong state rather than failing the batch — so the gates are kept here too,
 * or a batch reports fewer moved than selected with nothing to say why.
 */
enum class TimecardStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    AwaitingApproval("awaiting_approval", S.dm_filter_status_pending),
    Submitted("submitted", S.txt_submitted),
    Pending("pending", S.pending),
    Queried("queried", S.ah_queried),
    Approved("approved", S.approved),
    FinalApproved("final_approved", S.desktop_payroll_acct_approved),
    Locked("locked", S.docusign_prop_locked),
    Rejected("rejected", S.rejected),
    Paid("paid", S.desktop_paid),
    Unpaid("unpaid", S.desktop_unpaid),
    Posted("posted", S.ah_step_posted),

    /** The web's legacy spelling of posted; labelled the same. */
    Processed("processed", S.ah_step_posted),
    Received("received", S.received_text),
    Unknown("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    /**
     * Ready to be marked paid: `locked` after the run, or `unpaid` after a
     * reversal. The web's `paidEligibleIds` (`PayrollRunModule.jsx` 6428-6432)
     * and Processing's `canMarkPaid` (`PayrollGridModule.jsx` 1973).
     */
    val isPayable: Boolean get() = this == Locked || this == Unpaid

    /** A paid row can be reversed to unpaid. */
    val isUnpayable: Boolean get() = this == Paid

    /** A paid timecard is ready to post — History counts it; Payroll Run's Journal Ledger posts it. */
    val isPostable: Boolean get() = this == Paid

    /** In the approval chain, before payroll has it: what Override Approval skips. */
    val isAwaitingApproval: Boolean
        get() = this == AwaitingApproval || this == Submitted || this == Pending

    /**
     * The statuses whose timecard the server refuses to change — claims,
     * deductions and edits alike. The web's `TIMECARD_WRITES_REFUSED`
     * (`payrollData.js` 24-29).
     */
    val refusesWrites: Boolean
        get() = this == Locked || this == Paid || this == Posted || this == Rejected

    /** Already in the ledger. */
    val isPosted: Boolean get() = this == Posted || this == Processed

    companion object {
        fun from(wire: String?): TimecardStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/**
 * The production's payroll settings as the payroll screens read them — the
 * web's `usePayrollMetadata`, which merges `/payroll/metadata` with the
 * account hub's `/payroll-settings`.
 */
data class PayrollMetadata(
    /** Whether the production's payroll-approvers list names this user. */
    val isFinalApprover: Boolean = false,
    /** ISO day the pay period starts on: 1 = Monday … 7 = Sunday. */
    val payPeriodStartDay: Int = PayPeriod.MONDAY,
    /** The balance-sheet accounts the journal's credit side posts to. */
    val payrollAccounts: List<PayrollAccount> = emptyList(),
    /** One journal line per pay category rather than per pay code. */
    val journalGroupByCategory: Boolean = false,
    /** `title` rather than `uppercase` — how a journal line's description is cased. */
    val journalTitleCase: Boolean = false,
)

/**
 * A payroll control account: a code from Payroll Entry Setup, named by the
 * chart of accounts — or by its own code when the chart does not know it.
 */
data class PayrollAccount(val code: String, val name: String)

/** A crew member as the production's crew list names them. */
data class PayrollPerson(
    val userId: String,
    val fullName: String,
    /** A label key (`department_camera`), translated at the edge. */
    val department: String?,
    /** A label key (`designation_gaffer_electrical`), translated at the edge. */
    val designation: String?,
)

/**
 * Who is looking at payroll, resolved the way the web resolves it.
 *
 * Department and designation come from the crew list; the final-approver
 * flag from `/payroll/metadata`; the tool rights from the production's
 * permission grid. Every gate the screens draw is a property here, and the
 * view model checks the same property before it acts.
 */
data class PayrollViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    /**
     * The production's payroll-approvers list names this user — from
     * `/api/v2/payroll/metadata`. On its own; see [isFinalApprover].
     */
    val onApproverList: Boolean = false,
    /** `view_access` or `posting_access` on `payroll_tool`. */
    val canView: Boolean = false,
    /** False until the permission grid has answered. */
    val rightsLoaded: Boolean = false,
) {
    /** The web's `currentUser.isAccountant`: the profile department names accounts. */
    val isAccountant: Boolean
        get() = departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /**
     * Production Accountant or Financial Controller — the web's
     * `isSeniorAccountant`. Matched on the normalised value so the
     * identifier and the translated name both work.
     */
    val isSenior: Boolean
        get() = designationIdentifier.normalised().let { value -> SENIOR.any { value.contains(it) } }

    /**
     * The accountant approver: named on the approvers list, or senior. The
     * web's `usePayrollMetadata().isFinalApprover`.
     */
    val isFinalApprover: Boolean get() = onApproverList || isSenior

    /**
     * Allowed to lock and unlock a run — the web's `isPayrollAccountant`: a
     * payroll-accounts or office-assistant designation, or senior.
     */
    val isPayrollAccountant: Boolean
        get() = isSenior || designationIdentifier?.trim() in PAYROLL_ACCOUNTANT_DESIGNATIONS

    /** The accountant surfaces — Processing, Run and History — are theirs. */
    val seesAccountantViews: Boolean get() = isAccountant

    private companion object {
        const val ACCOUNTS = "accounts"
        val SENIOR = setOf("production accountant", "financial controller")
        val PAYROLL_ACCOUNTANT_DESIGNATIONS = setOf(
            "designation_office_production_assistant_additional_crew",
            "designation_payroll_accounts",
        )

        fun String?.normalised(): String = orEmpty().lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
    }
}
