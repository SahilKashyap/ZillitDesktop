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
                HubItem(
                    id = "timecard",
                    label = "Timecard",
                    target = HubTarget.Tool("/film-tools/timecard", "timecard_tool"),
                    gate = "timecard_tool",
                ),
                HubItem(
                    id = "deal-memo",
                    label = "Deal Memo",
                    target = HubTarget.Tool("/film-tools/deal-memo", "deal_memo_tool"),
                    gate = "deal_memo_tool",
                ),
            ),
        ),
        HubSection(
            title = "Reports",
            items = listOf(
                // The web's REPORTS group; Period Close, Trial Balance and Bible
                // Report stay out until their modules exist.
                HubItem(
                    id = "cost-report",
                    label = "Cost Report",
                    target = HubTarget.Tool("/film-tools/cost-report", "cost_report_tool"),
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
                HubItem(
                    id = "chart-of-accounts",
                    label = "Chart of Accounts",
                    target = HubTarget.Page(HubArea.ChartOfAccounts),
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
     * Null means this person has no hub screens at all — a department user who
     * reached the console — and the shell says so rather than rendering blank.
     */
    fun landing(viewer: AccountHubViewer): HubArea? {
        val areas = areasFor(viewer)
        return areas.firstOrNull { it == HubArea.ProductionSetup } ?: areas.firstOrNull()
    }
}
