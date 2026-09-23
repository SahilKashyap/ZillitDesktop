package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
enum class HubArea(val slug: String, private val labelKey: String) {
    ProductionSetup("production-setup", S.ps_production_setup),
    ChartOfAccounts("chart-of-accounts", S.desktop_chart_of_accounts),
    Vendors("vendors", S.ah_vendors),
    Approvers("approvers", S.desktop_approvers),
    Budget("budget", S.budget_text),
    TrialBalance("trial-balance", S.desktop_trial_balance),
    PeriodClose("period-close", S.desktop_period_close),
    BibleReport("bible-report", S.desktop_bible_report),
    FormConfig("form-config", S.desktop_forms_configuration),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromSlug(slug: String?): HubArea? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * One row of the console sidebar.
 *
 * Holds the label's key, not its text: the sidebar is built once when the
 * console starts, and text resolved then stayed in whatever language the app
 * had at that moment.
 */
data class HubItem(
    val id: String,
    private val labelKey: String,
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
) {
    val label: String get() = str(labelKey)
}

/** A titled group of sidebar rows; the title resolves when drawn, as [HubItem.label] does. */
data class HubSection(private val titleKey: String, val items: List<HubItem>) {
    val title: String get() = str(titleKey)
}

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
            titleKey = S.desktop_setup,
            items = listOf(
                HubItem(
                    id = "production-setup",
                    labelKey = S.ps_production_setup,
                    target = HubTarget.Page(HubArea.ProductionSetup),
                ),
            ),
        ),
        HubSection(
            titleKey = S.desktop_transactions,
            items = listOf(
                HubItem(
                    id = "purchase-orders",
                    labelKey = S.ah_purchase_orders,
                    target = HubTarget.Tool("/film-tools/purchase-order", "purchase_order_tool"),
                    gate = "purchase_order_tool",
                    forDepartmentUsers = true,
                ),
                // Accountant-only in the web sidebar (department users get the
                // `invoices_tool` tile instead); no grid gate of its own there.
                HubItem(
                    id = "invoices",
                    labelKey = S.desktop_inv_accounts_payable,
                    target = HubTarget.Tool("/film-tools/invoices", "invoices_tool"),
                ),
                HubItem(
                    id = "card-expenses",
                    labelKey = S.ah_card_expenses,
                    target = HubTarget.Tool("/film-tools/card-expenses", "card_expenses_tool"),
                    gate = "card_expenses_tool",
                    forDepartmentUsers = true,
                ),
                HubItem(
                    id = "cash-expenses",
                    labelKey = S.ah_cash_expenses,
                    target = HubTarget.Tool("/film-tools/cash-expenses", "cash_expenses_tool"),
                    gate = "cash_expenses_tool",
                    forDepartmentUsers = true,
                ),
            ),
        ),
        HubSection(
            titleKey = S.desktop_payroll_management,
            items = listOf(
                HubItem(
                    id = "payroll",
                    labelKey = S.dm_section_payroll,
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
            titleKey = S.reports,
            items = listOf(
                // Cost Report first, then Period Close, which is the web's
                // own order in this group.
                // The accountant's worksheet, as the web's sidebar opens
                // (`${AH}/cost-report`) — not the crew tool at
                // `/film-tools/cost-report`, which is its read-only sibling.
                HubItem(
                    id = "cost-report",
                    labelKey = S.cr_title,
                    target = HubTarget.Tool("/film-tools/account-hub/cost-report", "cost_report_tool"),
                ),
                HubItem(
                    id = "period-close",
                    labelKey = S.desktop_period_close,
                    target = HubTarget.Page(HubArea.PeriodClose),
                ),
            ),
        ),
        HubSection(
            titleKey = S.desktop_management,
            items = listOf(
                HubItem(
                    id = "vendors",
                    labelKey = S.ah_vendors,
                    target = HubTarget.Page(HubArea.Vendors),
                ),
                // The web files this under Management too, between Vendors and
                // the reports it has and this port does not.
                HubItem(
                    id = "trial-balance",
                    labelKey = S.desktop_trial_balance,
                    target = HubTarget.Page(HubArea.TrialBalance),
                ),
                HubItem(
                    id = "bible-report",
                    labelKey = S.desktop_bible_report,
                    target = HubTarget.Page(HubArea.BibleReport),
                ),
                // Rendered inside the console, sidebar and all, as on the web.
                HubItem(
                    id = "bank-reconciliation",
                    labelKey = S.desktop_bank_reconciliation,
                    target = HubTarget.Tool(
                        "/film-tools/account-hub/bank-reconciliation",
                        AccountHubViewer.TOOL_IDENTIFIER,
                    ),
                ),
                // Full-bleed, as the web renders it (see `isFullBleed`): its own
                // top bar carries the way back. No `gate`: the web gates it on
                // being an accountant and no tool right, which the role filter
                // already does.
                HubItem(
                    id = "tax-filing",
                    labelKey = S.desktop_tax_filing,
                    target = HubTarget.Tool(
                        "/film-tools/account-hub/tax-filing",
                        AccountHubViewer.TOOL_IDENTIFIER,
                    ),
                ),
            ),
        ),
        HubSection(
            titleKey = S.desktop_configuration,
            items = listOf(
                HubItem(
                    id = "approvers",
                    labelKey = S.desktop_approvers,
                    target = HubTarget.Page(HubArea.Approvers),
                ),
                // Above Chart of Accounts as on the web, and a page of this
                // console's own — the hub's Budget is the versioned project
                // budget that hangs off the chart and drives Cost Report, not
                // the Main and Department Budget film tools, which are a
                // different thing entirely.
                HubItem(
                    id = "budget",
                    labelKey = S.budget_text,
                    target = HubTarget.Page(HubArea.Budget),
                ),
                HubItem(
                    id = "chart-of-accounts",
                    labelKey = S.desktop_chart_of_accounts,
                    target = HubTarget.Page(HubArea.ChartOfAccounts),
                ),
                // Last under Configuration, as on the web. A page of the
                // console's own: it edits the hub's own documents and reaches
                // nothing outside it.
                HubItem(
                    id = "form-config",
                    labelKey = S.desktop_forms_configuration,
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
                // An accountant's rows ride on the hub's own right, as on the
                // web (`useAccountHubViewGate`: entered through the hub, every
                // module is gated on `account_hub_tool` alone). Requiring each
                // sub-tool's right as well dropped Purchase Orders, the
                // expenses and Payroll — and the PO landing with them — on any
                // production whose tool list omits one; the web calls that
                // "broke entry outright". Everyone else still needs the tool:
                // `mayList`, not `mayOpen`, because a row is an offer.
                allowedByRole && (viewer.isAccountant || viewer.mayList(item.gate))
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

    /**
     * Whether a surface is drawn without the console's sidebar.
     *
     * The web renders two kinds full-bleed (`AccountHubToolHost.jsx`,
     * `AccountHubShell.jsx`), and both carry their own way back:
     *  - the "bare" reports — Cost Report, Trial Balance, Bible Report, Period
     *    Close and Tax Filing — each with its own back button;
     *  - the modules with a deeper sidebar of their own — Invoices and Card
     *    Expenses — where two navigation columns side by side left the content
     *    half the window (`SELF_NAV_MODULE_PREFIXES`). Only for an accountant:
     *    a cardholder's view of Card Expenses has no sidebar to go back with.
     *
     * Keeping the console's sidebar beside them drew a second back arrow and,
     * on Period Close, three columns of navigation.
     */
    fun isFullBleed(area: HubArea?, embeddedPath: String?, viewer: AccountHubViewer): Boolean =
        if (embeddedPath != null) {
            BARE_TOOL_PATHS.any { embeddedPath.startsWith(it) } ||
                (viewer.isAccountant && SELF_NAV_TOOL_PATHS.any { embeddedPath.startsWith(it) })
        } else {
            area in BARE_AREAS
        }

    private val BARE_AREAS = setOf(HubArea.TrialBalance, HubArea.BibleReport, HubArea.PeriodClose)
    private val BARE_TOOL_PATHS = listOf(
        "/film-tools/account-hub/cost-report",
        "/film-tools/account-hub/tax-filing",
    )
    private val SELF_NAV_TOOL_PATHS = listOf("/film-tools/invoices", "/film-tools/card-expenses")

    private const val PURCHASE_ORDERS_ID = "purchase-orders"
}
