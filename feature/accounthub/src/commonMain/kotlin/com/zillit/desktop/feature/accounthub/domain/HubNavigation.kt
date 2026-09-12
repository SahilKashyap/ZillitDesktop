package com.zillit.desktop.feature.accounthub.domain

/**
 * What a sidebar entry opens.
 *
 * The distinction is the whole shape of this module: about a third of the
 * console's entries are screens the hub owns, and the rest are separate film
 * tools that the hub merely lists. Modelling both as "a route" is what let the
 * web open Payroll from the sidebar in its *producer* view instead of its
 * accountant one — the entry looked like navigation, but it was a hand-off.
 */
sealed interface HubTarget {

    /** A screen this module renders. */
    data class Page(val area: HubArea) : HubTarget

    /**
     * Another film tool, opened as itself.
     *
     * [toolPath] is the workspace route the tool registers, so the hub does not
     * need to know anything about how that tool is built — only where it lives.
     */
    data class Tool(val toolPath: String, val identifier: String) : HubTarget
}

/** A screen the hub itself renders. */
enum class HubArea(val slug: String, val label: String) {
    ProductionSetup("production-setup", "Production Setup"),
    ChartOfAccounts("chart-of-accounts", "Chart of Accounts"),
    Vendors("vendors", "Vendors"),
    Approvers("approvers", "Approvers"),
    Budget("budget", "Budget"),
    TrialBalance("trial-balance", "Trial Balance"),
    PeriodClose("period-close", "Period Close"),
    BibleReport("bible-report", "Bible Report"),
    FormConfig("form-config", "Forms Configuration"),
    ;

    companion object {
        fun fromSlug(slug: String?): HubArea? = entries.firstOrNull { it.slug == slug }
    }
}

/** One row of the console sidebar. */
data class HubItem(
    val id: String,
    val label: String,
    val target: HubTarget,
    /**
     * The tool identifier that grants entry, or null for an entry gated only by
     * the hub itself.
     */
    val gate: String? = null,
    /**
     * Whether a department user — someone outside accounts — sees this.
     *
     * Three entries are: the places a department raises its own spend. The rest
     * of the console is the accountant's, and listing it for everybody would
     * advertise screens that answer 403.
     */
    val forDepartmentUsers: Boolean = false,
)

/** A titled group of sidebar rows. */
data class HubSection(val title: String, val items: List<HubItem>)

/**
 * The console's navigation.
 *
 * Ported from `AccountHubSidebar.SIDEBAR_ITEMS`, including which entries are
 * deliberately absent: Deal Memo and Timecard were **moved out** of this
 * sidebar into their own tools, and Journal Ledger, Gov. Reporting and Ledger
 * Properties are commented out there pending their modules. Adding them back
 * here would put rows in front of users that lead nowhere.
 */
object HubNavigation {

    /** Areas this module renders, versus tools it hands off to. */
    val sections: List<HubSection> = listOf(
        HubSection(
            title = "Setup",
            items = listOf(
                HubItem(
                    id = "production-setup",
                    label = "Production Setup",
                    target = HubTarget.Page(HubArea.ProductionSetup),
                ),
            ),
        ),
        HubSection(
            title = "Transactions",
            items = listOf(
                HubItem(
                    id = "purchase-orders",
                    label = "Purchase Orders",
                    target = HubTarget.Tool("/film-tools/purchase-order", "purchase_order_tool"),
                    gate = "purchase_order_tool",
                    forDepartmentUsers = true,
                ),
                // Accountant-only in the web sidebar (department users get the
                // `invoices_tool` tile instead); no grid gate of its own there.
                HubItem(
                    id = "invoices",
                    label = "Invoices / Accounts Payable",
                    target = HubTarget.Tool("/film-tools/invoices", "invoices_tool"),
                ),
                HubItem(
                    id = "card-expenses",
                    label = "Production Expense Cards",
                    target = HubTarget.Tool("/film-tools/card-expenses", "card_expenses_tool"),
                    gate = "card_expenses_tool",
                    forDepartmentUsers = true,
                ),
                HubItem(
                    id = "cash-expenses",
                    label = "Petty Cash Expenses",
                    target = HubTarget.Tool("/film-tools/cash-expenses", "cash_expenses_tool"),
                    gate = "cash_expenses_tool",
                    forDepartmentUsers = true,
                ),
            ),
        ),
        HubSection(
            title = "Payroll Management",
            items = listOf(
                HubItem(
                    id = "payroll",
                    label = "Payroll",
                    target = HubTarget.Tool("/film-tools/payroll", "payroll_tool"),
                    gate = "payroll_tool",
                ),
                // Timecard and Deal Memo are NOT here. Both were removed from
                // the web's sidebar when they became their own zillit tools
                // (`AccountHubSidebar.jsx`, dated 2026-05-21 and 2026-05-18),
                // and this file said so in its own header while listing them
                // anyway — found 2026-09-09 reviewing against the web. They
                // reach their tools from the Film Tools grid, as there.
            ),
        ),
        HubSection(
            title = "Reports",
            items = listOf(
                // Cost Report first, then Period Close, which is the web's
                // own order in this group.
                // The accountant's worksheet, as the web's sidebar opens
                // (`${AH}/cost-report`) — not the crew tool at
                // `/film-tools/cost-report`, which is its read-only sibling.
                HubItem(
                    id = "cost-report",
                    label = "Cost Report",
                    target = HubTarget.Tool("/film-tools/account-hub/cost-report", "cost_report_tool"),
                ),
                HubItem(
                    id = "period-close",
                    label = "Period Close",
                    target = HubTarget.Page(HubArea.PeriodClose),
                ),
            ),
        ),
        HubSection(
            title = "Management",
            items = listOf(
                HubItem(
                    id = "vendors",
                    label = "Vendors",
                    target = HubTarget.Page(HubArea.Vendors),
                ),
                // The web files this under Management too, between Vendors and
                // the reports it has and this port does not.
                HubItem(
                    id = "trial-balance",
                    label = "Trial Balance",
                    target = HubTarget.Page(HubArea.TrialBalance),
                ),
                HubItem(
                    id = "bible-report",
                    label = "Bible Report",
                    target = HubTarget.Page(HubArea.BibleReport),
                ),
                // Also a hand-off. Eight tabs with a two-panel reconciliation
                // among them: the console's sidebar beside it would leave the
                // workspace half a screen wide.
                HubItem(
                    id = "bank-reconciliation",
                    label = "Bank Reconciliation",
                    target = HubTarget.Tool(
                        "/film-tools/account-hub/bank-reconciliation",
                        AccountHubViewer.TOOL_IDENTIFIER,
                    ),
                ),
                // A hand-off rather than a page, unlike the web, where it
                // renders inside the hub shell. It reaches HMRC and files a
                // legal return, so it gets a window of its own here — the same
                // shape as every other hand-off in this sidebar, and the same
                // reason the console does not land people in Purchase Orders.
                //
                // No `gate`: the web gates it on being an accountant and on no
                // tool right at all (`protectedRoute.jsx`), which the role
                // filter above already does. Bank Reconciliation sits above it
                // on the web too.
                HubItem(
                    id = "tax-filing",
                    label = "Tax Filing",
                    target = HubTarget.Tool(
                        "/film-tools/account-hub/tax-filing",
                        AccountHubViewer.TOOL_IDENTIFIER,
                    ),
                ),
            ),
        ),
        HubSection(
            title = "Configuration",
            items = listOf(
                HubItem(
                    id = "approvers",
                    label = "Approvers",
                    target = HubTarget.Page(HubArea.Approvers),
                ),
                // Above Chart of Accounts as on the web, and a page of this
                // console's own — the hub's Budget is the versioned project
                // budget that hangs off the chart and drives Cost Report, not
                // the Main and Department Budget film tools, which are a
                // different thing entirely.
                HubItem(
                    id = "budget",
                    label = "Budget",
                    target = HubTarget.Page(HubArea.Budget),
                ),
                HubItem(
                    id = "chart-of-accounts",
                    label = "Chart of Accounts",
                    target = HubTarget.Page(HubArea.ChartOfAccounts),
                ),
                // Last under Configuration, as on the web. A page of the
                // console's own: it edits the hub's own documents and reaches
                // nothing outside it.
                HubItem(
                    id = "form-config",
                    label = "Forms Configuration",
                    target = HubTarget.Page(HubArea.FormConfig),
                ),
            ),
        ),
    )

    /**
     * The sections [viewer] should be shown, with unreachable rows removed and
     * empty sections dropped.
     *
     * A department user gets only the three spend areas — and gets them without
     * section headings, since one heading over one row is noise. The console's
     * own areas are withheld entirely rather than shown disabled: an accountant
     * screen listed but greyed out reads as a permission to ask for, when in
     * fact it is not that person's screen at all.
     */
    fun visibleTo(viewer: AccountHubViewer): List<HubSection> =
        sections.mapNotNull { section ->
            val items = section.items.filter { item ->
                val allowedByRole = viewer.isAccountant || viewer.isAdmin || item.forDepartmentUsers
                // `mayList`, not `mayOpen`: a row here is an offer, and the
                // permissive entry rule would advertise every hosted tool to
                // anyone holding the hub's own right.
                allowedByRole && viewer.mayList(item.gate)
            }
            section.copy(items = items).takeIf { items.isNotEmpty() }
        }

    /** Every hub-owned area [viewer] may open, in sidebar order. */
    fun areasFor(viewer: AccountHubViewer): List<HubArea> =
        visibleTo(viewer).flatMap { it.items }
            .mapNotNull { (it.target as? HubTarget.Page)?.area }

    /**
     * Where the console opens.
     *
     * Production Setup when it is reachable, because that is the configuration
     * everything else in the hub reads from; otherwise the first area that is.
     * Null means this person has no hub screens at all — see [landingTool].
     *
     * The web lands *everyone* on Purchase Orders
     * (`poEntryPath.js`: "the Account Hub's default landing is the Purchase
     * Orders module"). That is not copied for someone who has console screens,
     * and the reason is the ports' different shapes: on the web PO renders
     * inside the hub shell with the sidebar still showing, so landing there
     * costs nothing. Here PO is a separate tool window, so doing the same
     * would close the console the user just opened. An accountant reaches PO
     * in one click instead.
     */
    fun landing(viewer: AccountHubViewer): HubArea? {
        val areas = areasFor(viewer)
        return areas.firstOrNull { it == HubArea.ProductionSetup } ?: areas.firstOrNull()
    }

    /**
     * The tool to open for somebody the console has no screen for.
     *
     * A department user has the three spend rows and nothing the hub renders,
     * and used to arrive at "Nothing here for you yet" — a dead end in front
     * of a sidebar full of working rows. The web puts exactly this person in
     * Purchase Orders, so this does too, falling back to whichever spend tool
     * they do hold if PO is not one of them.
     *
     * Null only when they have no rows at all, which is the one case the empty
     * state is honest about.
     */
    fun landingTool(viewer: AccountHubViewer): HubItem? {
        if (landing(viewer) != null) return null
        return purchaseOrdersRow(viewer) ?: toolRows(viewer).firstOrNull()
    }

    /** The Purchase Orders row, when this person may open it — the web's landing for everyone. */
    fun purchaseOrdersRow(viewer: AccountHubViewer): HubItem? =
        toolRows(viewer).firstOrNull { it.id == PURCHASE_ORDERS_ID }

    private fun toolRows(viewer: AccountHubViewer): List<HubItem> =
        visibleTo(viewer).flatMap { it.items }.filter { it.target is HubTarget.Tool }

    private const val PURCHASE_ORDERS_ID = "purchase-orders"
}
