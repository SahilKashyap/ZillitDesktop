package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
    private val labelKey: String,
    val section: CashSection,
) {
    // -- petty cash, accounts ---------------------------------------------
    PettyCashOverview("petty-cash/overview", S.ah_overview, CashSection.PettyCashAccounts),
    PostLedger("petty-cash/post", S.desktop_ce_post_and_ledger, CashSection.PettyCashAccounts),
    ActiveFloats("petty-cash/floats", S.desktop_ce_active_floats, CashSection.PettyCashAccounts),
    TopUps("petty-cash/top-ups", S.ah_topups_tab, CashSection.PettyCashAccounts),
    CashReconciliation("petty-cash/cash-recon", S.desktop_ce_cash_recon, CashSection.PettyCashAccounts),

    // -- petty cash, senior ------------------------------------------------
    // Its own "Sign off" key: the tab used to borrow the lifecycle step's
    // description (`ah_step_approval_desc`), whose translations say something
    // else in most languages.
    PettyCashSignOff("petty-cash/sign-off", S.desktop_br_sign_off, CashSection.PettyCashSenior),

    // -- petty cash, crew --------------------------------------------------
    FloatRequest("petty-cash/float-request", S.ah_float_request, CashSection.PettyCashCrew),
    SubmitReceipts("petty-cash/submit-claim", S.desktop_ce_submit_receipts, CashSection.PettyCashCrew),
    ReceiptsHistory("petty-cash/my-claims", S.desktop_ce_receipts_history, CashSection.PettyCashCrew),
    CashExtension("petty-cash/cash-extension", S.desktop_ce_cash_extension, CashSection.PettyCashCrew),

    // -- out of pocket -----------------------------------------------------
    OutOfPocketOverview("out-of-pocket/overview", S.ah_overview, CashSection.OutOfPocketAccounts),
    OutOfPocketPost("out-of-pocket/post", S.desktop_ce_post_and_ledger, CashSection.OutOfPocketAccounts),
    PaymentRouting("out-of-pocket/payment", S.desktop_ce_payment_routing, CashSection.OutOfPocketAccounts),
    OutOfPocketSignOff("out-of-pocket/sign-off", S.desktop_br_sign_off, CashSection.OutOfPocketSenior),
    ClaimReview("out-of-pocket/review", S.desktop_ce_claim_review, CashSection.OutOfPocketApprover),
    OutOfPocketSubmit("out-of-pocket/submit", S.desktop_ce_submit_receipts, CashSection.OutOfPocketCrew),
    OutOfPocketHistory("out-of-pocket/history", S.desktop_ce_receipts_history, CashSection.OutOfPocketCrew),

    // -- shared, reached from the top bar rather than a section ------------
    CodingQueue("coding-queue", S.ah_coding_queue, CashSection.Shared),
    AuditQueue("audit-queue", S.desktop_ce_audit_queue, CashSection.Shared),
    ApprovalQueue("approval-queue", S.ah_approval_queue, CashSection.Shared),
    History("history", S.history, CashSection.Shared),
    Settings("settings", S.settings, CashSection.Shared),
    DepartmentOverview("petty-cash/dept-view", S.desktop_ce_dept_overview, CashSection.Shared),
    MyOverview("petty-cash/my-overview", S.desktop_ce_my_overview, CashSection.Shared),
    ;

    val label: String get() = str(labelKey)

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
            AuditQueue -> listOf(CashBadges.AUDIT_QUEUE)
            ApprovalQueue -> listOf(CashBadges.RECEIPT_APPROVAL, CashBadges.FLOAT_APPROVAL)
            CodingQueue -> listOf(CashBadges.CODING_QUEUE)
            PostLedger -> listOf(CashBadges.PC_POST_LEDGER)
            ActiveFloats, FloatRequest -> listOf(CashBadges.PC_FLOAT)
            TopUps -> listOf(CashBadges.PC_TOPUPS)
            CashReconciliation -> listOf(CashBadges.PC_RECON)
            PettyCashSignOff -> listOf(CashBadges.PC_SIGNOFF)
            OutOfPocketPost -> listOf(CashBadges.OOP_POST_LEDGER)
            OutOfPocketSignOff -> listOf(CashBadges.OOP_SIGNOFF)
            ClaimReview -> listOf(CashBadges.RECEIPT_APPROVAL)
            ReceiptsHistory -> listOf(CashBadges.PC_HISTORY)
            CashExtension -> listOf(CashBadges.CASH_EXTENSION)
            OutOfPocketHistory -> listOf(CashBadges.OOP_HISTORY)
            else -> emptyList()
        }

    /**
     * The `level_1` a batch opened on this page is read under, or null where
     * opening one reads nothing — the Approval Queue reads on the decision,
     * not the open (ZL-20775, `PCApprovalPage.jsx:1080`). The web's
     * `badgeLevel1` (`PCPostLedgerPage.jsx:555-558`), `PCSeniorPage` and
     * `PCMyClaimsPage`.
     */
    val batchBadgeKey: String?
        get() = when (this) {
            CodingQueue -> CashBadges.CODING_QUEUE
            AuditQueue -> CashBadges.AUDIT_QUEUE
            PostLedger -> CashBadges.PC_POST_LEDGER
            OutOfPocketPost -> CashBadges.OOP_POST_LEDGER
            PettyCashSignOff -> CashBadges.PC_SIGNOFF
            OutOfPocketSignOff -> CashBadges.OOP_SIGNOFF
            ReceiptsHistory -> CashBadges.PC_HISTORY
            OutOfPocketHistory -> CashBadges.OOP_HISTORY
            else -> null
        }

    /**
     * Whether arriving here reads the whole tab — only the two pages the web
     * reads on mount (`CashExtensionPage.jsx:38-58`, `PCTopUpsPage.jsx:201-224`);
     * every other page reads one entity as it is opened or acted on.
     */
    val readsWholeTab: Boolean
        get() = this == CashExtension || this == TopUps

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
        ApprovalQueue -> viewer.isApprover || viewer.isAccountant
        // The web declares Claim Review in its Approver group and never draws
        // it: `filterGroups` has no branch for that group
        // (`CashExpensesModule.jsx:397-416`), and a deep link to it bounces.
        // Approvers work the shared Approval Queue.
        ClaimReview -> false
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

    /**
     * Whether [viewer] may be on this page at all — which is wider than the tabs.
     *
     * Float Request sits in the crew group, so an accountant is never offered
     * the tab, but New Float on Active Floats opens it to raise a float for a
     * crew member (`CashExpensesModule.jsx:551-558`).
     */
    fun openableBy(viewer: CashViewer): Boolean =
        visibleTo(viewer) || (this == FloatRequest && viewer.isAccountant)

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
enum class CashSection(private val labelKey: String, val isOutOfPocket: Boolean = false) {
    PettyCashAccounts(S.accounts),
    PettyCashSenior(S.desktop_senior),
    PettyCashCrew(S.crew),
    OutOfPocketAccounts(S.accounts, isOutOfPocket = true),
    OutOfPocketSenior(S.desktop_senior, isOutOfPocket = true),
    OutOfPocketApprover(S.desktop_approver, isOutOfPocket = true),
    OutOfPocketCrew(S.crew, isOutOfPocket = true),

    /** Reached from the top bar, so it belongs to neither pipeline. */
    Shared(S.history_shared),
    ;

    val label: String get() = str(labelKey)
}

/**
 * The notification service's names for this module's rows — the web's
 * `BADGE_CONSTANTS` for cash (`cash-expenses-badge-helpers.js`).
 *
 * A row sits at `level_1` (the page's area), `level_2` (what kind of thing:
 * one of the `KIND_` names) and `level_3` (the thing's id). An accountant's
 * rows file under the Account Hub's tool and everyone else's under this one —
 * except the coding queue, which is always the crew side's.
 */
object CashBadges {
    const val AUDIT_QUEUE = "cash_audit_queue"
    const val CODING_QUEUE = "cash_coding_queue"
    const val RECEIPT_APPROVAL = "receipt_approval"
    const val FLOAT_APPROVAL = "float_approval"
    const val PC_POST_LEDGER = "pc_post_ledger"
    const val OOP_POST_LEDGER = "oop_post_ledger"
    const val PC_SIGNOFF = "pc_signoff"
    const val OOP_SIGNOFF = "oop_signoff"
    const val PC_FLOAT = "pc_float"
    const val PC_TOPUPS = "pc_topups"
    const val PC_RECON = "pc_recon"
    const val PC_HISTORY = "pc_history"
    const val OOP_HISTORY = "oop_history"
    const val CASH_EXTENSION = "cash_extension"

    const val KIND_RECEIPT = "cash_receipt"
    const val KIND_FLOAT = "cash_float"
    const val KIND_TOPUP = "cash_topup"
    const val KIND_RECON = "cash_recon"
    const val KIND_QUERY = "query_chat"

    /** The tool an accountant's rows file under. */
    const val ACCOUNT_HUB_TOOL = "account_hub_label"

    /** The tool everyone else's rows file under, and the unit every cash row shares. */
    const val CASH_TOOL = "cash_expenses_label"

    /** Which tool [level1]'s rows are filed under, for a viewer who [isAccountant] or not. */
    fun toolFor(level1: String, isAccountant: Boolean): String =
        if (isAccountant && level1 != CODING_QUEUE) ACCOUNT_HUB_TOOL else CASH_TOOL
}
