package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType

/**
 * Every page this tool can show.
 *
 * An enum rather than free-form route strings so the shell cannot navigate
 * somewhere that does not render, and so [visibleTo] is the single place that
 * decides who sees what — the web spreads that decision across a tab list, a
 * `filterGroups` callback and a `visibleSharedTabs` memo, and they have
 * disagreed.
 *
 * [slug] matches the web's tab ids so a deep link works identically on both
 * clients.
 */
enum class CashDestination(
    val slug: String,
    val label: String,
    val section: CashSection,
) {
    // -- petty cash, accounts ---------------------------------------------
    PettyCashOverview("petty-cash/overview", "Overview", CashSection.PettyCashAccounts),
    PostLedger("petty-cash/post", "Post & Ledger", CashSection.PettyCashAccounts),
    ActiveFloats("petty-cash/floats", "Active Floats", CashSection.PettyCashAccounts),
    TopUps("petty-cash/top-ups", "Top-Ups", CashSection.PettyCashAccounts),
    CashReconciliation("petty-cash/cash-recon", "Cash Recon", CashSection.PettyCashAccounts),

    // -- petty cash, senior ------------------------------------------------
    PettyCashSignOff("petty-cash/sign-off", "Sign-off", CashSection.PettyCashSenior),

    // -- petty cash, crew --------------------------------------------------
    FloatRequest("petty-cash/float-request", "Float Request", CashSection.PettyCashCrew),
    SubmitReceipts("petty-cash/submit-claim", "Submit Receipts", CashSection.PettyCashCrew),
    ReceiptsHistory("petty-cash/my-claims", "Receipts History", CashSection.PettyCashCrew),
    CashExtension("petty-cash/cash-extension", "Cash Extension", CashSection.PettyCashCrew),

    // -- out of pocket -----------------------------------------------------
    OutOfPocketOverview("out-of-pocket/overview", "Overview", CashSection.OutOfPocketAccounts),
    OutOfPocketPost("out-of-pocket/post", "Post & Ledger", CashSection.OutOfPocketAccounts),
    PaymentRouting("out-of-pocket/payment", "Payment Routing", CashSection.OutOfPocketAccounts),
    OutOfPocketSignOff("out-of-pocket/sign-off", "Sign-off", CashSection.OutOfPocketSenior),
    ClaimReview("out-of-pocket/review", "Claim Review", CashSection.OutOfPocketApprover),
    OutOfPocketSubmit("out-of-pocket/submit", "Submit Receipts", CashSection.OutOfPocketCrew),
    OutOfPocketHistory("out-of-pocket/history", "Receipts History", CashSection.OutOfPocketCrew),

    // -- shared, reached from the top bar rather than a section ------------
    CodingQueue("coding-queue", "Coding Queue", CashSection.Shared),
    AuditQueue("audit-queue", "Audit Queue", CashSection.Shared),
    ApprovalQueue("approval-queue", "Approval Queue", CashSection.Shared),
    History("history", "History", CashSection.Shared),
    Settings("settings", "Settings", CashSection.Shared),
    DepartmentOverview("petty-cash/dept-view", "Dept Overview", CashSection.Shared),
    MyOverview("petty-cash/my-overview", "My Overview", CashSection.Shared),
    ;

    /** Which of the two pipelines this page belongs to, for the header. */
    val expenseType: ExpenseType
        get() = if (section.isOutOfPocket) ExpenseType.OutOfPocket else ExpenseType.PettyCash

    /**
     * The `level_1` keys the notification service files this page's rows
     * under — the web's `TAB_BADGE_CONFIG` (`CashExpensesModule.jsx:92-120`),
     * keyed by the same slugs. Empty for a page that carries no chip
     * (overviews, Payment Routing, History, Settings — notification-only).
     */
    val badgeKeys: List<String>
        get() = when (this) {
            AuditQueue -> listOf("cash_audit_queue")
            ApprovalQueue -> listOf("receipt_approval", "float_approval")
            CodingQueue -> listOf("cash_coding_queue")
            PostLedger -> listOf("pc_post_ledger")
            ActiveFloats, FloatRequest -> listOf("pc_float")
            TopUps -> listOf("pc_topups")
            CashReconciliation -> listOf("pc_recon")
            PettyCashSignOff -> listOf("pc_signoff")
            OutOfPocketPost -> listOf("oop_post_ledger")
            OutOfPocketSignOff -> listOf("oop_signoff")
            ClaimReview -> listOf("receipt_approval")
            ReceiptsHistory -> listOf("pc_history")
            CashExtension -> listOf("cash_extension")
            OutOfPocketHistory -> listOf("oop_history")
            else -> emptyList()
        }

    /**
     * Whether [viewer] may open this page.
     *
     * The rules are the web's, consolidated:
     *
     *  - **Accounts** pages need an accountant. An accountant who entered from
     *    the tools grid is not one here — see [CashViewer.isAccountant].
     *  - **Senior** pages additionally need sign-off to be switched on and the
     *    viewer to be senior.
     *  - **Approver** and **Coordinator** pages need the grant from metadata.
     *  - **Crew** pages are for everyone who is *not* processing cash, which is
     *    why an accountant does not see a Submit Receipts tab in the accounts
     *    view — they see it by entering the tool from the grid.
     *  - **Coding Queue** additionally requires the production to use coding at
     *    all; offering it where nothing is ever coded is an empty page with no
     *    explanation.
     */
    @Suppress("CyclomaticComplexMethod") // A rights table; one branch per exception.
    fun visibleTo(viewer: CashViewer): Boolean = when (this) {
        PettyCashSignOff, OutOfPocketSignOff -> viewer.canSeeSignOff
        Settings -> viewer.canOpenSettings
        CodingQueue -> viewer.isCoordinator && viewer.metadata.codingRequired
        AuditQueue, History -> viewer.isAccountant
        // Every accountant sees the approval queue — read-only unless they hold
        // override rights — plus any non-accountant sitting in an approval tier.
        ApprovalQueue, ClaimReview -> viewer.isApprover || viewer.isAccountant
        DepartmentOverview -> viewer.isCoordinator
        MyOverview -> !viewer.isAccountant
        ActiveFloats -> viewer.isAccountant || (viewer.isCoordinator && viewer.metadata.viewDepartmentFloats)
        else -> when (section) {
            CashSection.PettyCashAccounts, CashSection.OutOfPocketAccounts -> viewer.isAccountant
            CashSection.PettyCashSenior, CashSection.OutOfPocketSenior -> viewer.canSeeSignOff
            CashSection.OutOfPocketApprover -> viewer.isApprover
            CashSection.PettyCashCrew, CashSection.OutOfPocketCrew -> !viewer.isAccountant
            CashSection.Shared -> true
        }
    }

    companion object {
        fun fromSlug(slug: String?): CashDestination? =
            entries.firstOrNull { it.slug == slug }

        /**
         * Where this viewer lands.
         *
         * An accountant opens on the dashboard they came for; everyone else
         * opens on the form they came to fill in. Falls back to the first page
         * they can see, so a viewer with unusual rights never lands on a blank
         * screen.
         */
        fun landing(viewer: CashViewer, type: ExpenseType): CashDestination {
            val preferred = when {
                viewer.isAccountant && type == ExpenseType.PettyCash -> PettyCashOverview
                viewer.isAccountant -> OutOfPocketOverview
                type == ExpenseType.PettyCash -> SubmitReceipts
                else -> OutOfPocketSubmit
            }
            if (preferred.visibleTo(viewer)) return preferred
            return entries.firstOrNull { it.expenseType == type && it.visibleTo(viewer) }
                ?: entries.first { it.visibleTo(viewer) }
        }
    }
}

/** The group a page appears under in the sub-navigation. */
enum class CashSection(val label: String, val isOutOfPocket: Boolean = false) {
    PettyCashAccounts("Accounts"),
    PettyCashSenior("Senior"),
    PettyCashCrew("Crew"),
    OutOfPocketAccounts("Accounts", isOutOfPocket = true),
    OutOfPocketSenior("Senior", isOutOfPocket = true),
    OutOfPocketApprover("Approver", isOutOfPocket = true),
    OutOfPocketCrew("Crew", isOutOfPocket = true),

    /** Reached from the top bar, so it belongs to neither pipeline. */
    Shared("Shared"),
}
