package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaForest
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubSection
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.ProductionSchedule
import com.zillit.desktop.feature.accounthub.domain.ProjectBudget
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorChange

/**
 * One editable section of Production Setup.
 *
 * The whole lifecycle every section repeats — hold an editable mirror, compare
 * it against what was last saved, save, re-snapshot — in one type. The web
 * spreads this across a 300-line hook family because each section grew its own
 * copy first; there is only ever one rule, and it is equality against the
 * snapshot.
 *
 * Sections are independent on purpose: a save in one must not disturb unsaved
 * edits in another, which is also why each has its own endpoint.
 */
data class SectionEdit<T>(
    val saved: T,
    val edited: T = saved,
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = edited != saved

    fun edit(next: T): SectionEdit<T> = copy(edited = next)

    /** Discards the edits, restoring what the server last confirmed. */
    fun reverted(): SectionEdit<T> = copy(edited = saved)

    /**
     * Takes the server's echo as the new truth.
     *
     * The echo rather than what was sent: the server normalises — trimming,
     * minting ids, renumbering — and re-snapshotting from the payload leaves
     * the section permanently dirty against a value it can never reach.
     */
    fun committed(next: T): SectionEdit<T> = SectionEdit(saved = next, edited = next)

    fun loaded(next: T): SectionEdit<T> =
        if (dirty) copy(saved = next) else SectionEdit(saved = next, edited = next)
}

/** Production Setup's two tabs, as the web groups them. */
enum class SetupTab(val slug: String, val label: String) {
    Accounting("acct", "Accounting Setup"),
    DealMemo("deal", "Deal Memo Setup"),
}

/**
 * A field the user types into that the domain stores as something else.
 *
 * ## Why these hold text rather than the parsed value
 *
 * A text field whose displayed value is re-derived from a parsed model destroys
 * partial input. Both failure modes were seen live on 2026-08-12:
 *
 *  - **Budget amount** — bound to `Double?`, so typing `2500000` produced
 *    `25.0`: each keystroke re-rendered the parsed number, and the next
 *    character landed inside the reformatted text.
 *  - **Schedule dates** — bound to an epoch, so `"2026-09-0"` parsed to null,
 *    the field reset to empty on every keystroke, and a date could not be
 *    entered at all.
 *
 * Holding the text and parsing *alongside* it is the fix. It also makes the
 * dirty check honest: half a date typed is an unsaved change.
 */
data class BudgetForm(val amountText: String = "", val currency: String = "") {
    fun toDomain(): ProjectBudget =
        ProjectBudget(amount = amountText.trim().toDoubleOrNull(), currency = currency.trim())

    companion object {
        fun from(budget: ProjectBudget) =
            BudgetForm(amountText = budget.amount.asAmountText(), currency = budget.currency)
    }
}

/** One phase's two dates, as typed. */
data class DateRangeText(val from: String = "", val to: String = "") {
    fun toPhase(): SchedulePhase = SchedulePhase(
        startDate = IsoDate.toEpochMillis(from),
        endDate = IsoDate.toEpochMillis(to),
    )

    companion object {
        fun from(phase: SchedulePhase) = DateRangeText(
            from = EpochDate.isoDate(phase.startDate),
            to = EpochDate.isoDate(phase.endDate),
        )
    }
}

/** The production schedule, as typed. */
data class ScheduleForm(
    val overall: DateRangeText = DateRangeText(),
    val prep: DateRangeText = DateRangeText(),
    val shoot: DateRangeText = DateRangeText(),
    val wrap: DateRangeText = DateRangeText(),
) {
    fun toDomain(): ProductionSchedule {
        val whole = overall.toPhase()
        return ProductionSchedule(
            startDate = whole.startDate,
            endDate = whole.endDate,
            prep = prep.toPhase(),
            shoot = shoot.toPhase(),
            wrap = wrap.toPhase(),
        )
    }

    companion object {
        fun from(schedule: ProductionSchedule) = ScheduleForm(
            overall = DateRangeText.from(SchedulePhase(schedule.startDate, schedule.endDate)),
            prep = DateRangeText.from(schedule.prep),
            shoot = DateRangeText.from(schedule.shoot),
            wrap = DateRangeText.from(schedule.wrap),
        )
    }
}

/**
 * An amount as a person would type it.
 *
 * `Double.toString()` gives "2500000.0" and, past seven digits, "2.5E7" — both
 * of which a user then has to edit around. A whole number is printed whole.
 */
internal fun Double?.asAmountText(): String {
    val amount = this ?: return ""
    val whole = amount.toLong()
    return if (amount == whole.toDouble()) whole.toString() else amount.toString()
}

/** Everything Production Setup holds. */
data class SetupState(
    val tab: SetupTab = SetupTab.Accounting,
    val loading: Boolean = false,
    val companies: SectionEdit<List<Company>> = SectionEdit(emptyList()),
    val currencies: SectionEdit<CurrencySettings> = SectionEdit(CurrencySettings()),
    val taxTypes: SectionEdit<List<TaxType>> = SectionEdit(emptyList()),
    val assetTags: SectionEdit<List<String>> = SectionEdit(emptyList()),
    val budget: SectionEdit<BudgetForm> = SectionEdit(BudgetForm()),
    val schedule: SectionEdit<ScheduleForm> = SectionEdit(ScheduleForm()),
    val payrollDefaults: SectionEdit<PayrollDefaults> = SectionEdit(PayrollDefaults()),
    /**
     * Banks are not a [SectionEdit].
     *
     * Every other section batches into one save; a bank is a first-class record
     * with its own endpoints, so each row commits on its own and there is
     * nothing to be dirty against.
     */
    val banks: List<BankAccount> = emptyList(),
    val banksLoading: Boolean = false,
    val currencyCatalogue: List<ProjectCurrency> = emptyList(),
    val countryTaxes: List<CountryTaxes> = emptyList(),
    val companyDraft: Company? = null,
    val bankDraft: BankAccount? = null,
) {
    /** Sections with unsaved edits, so the shell can warn before leaving. */
    val dirtySections: List<String>
        get() = buildList {
            if (companies.dirty) add("Companies")
            if (currencies.dirty) add("Project Currencies")
            if (taxTypes.dirty) add("Tax Types")
            if (assetTags.dirty) add("Asset Tags")
            if (budget.dirty) add("Project Budget")
            if (schedule.dirty) add("Production Schedule")
            if (payrollDefaults.dirty) add("Payroll Defaults")
        }
}

/** Which classes the chart is filtered to. */
enum class ChartView(val slug: String, val label: String) {
    /** The cost side — what a production spends against. */
    Expense("accounts", "Cost Accounts"),

    /** Everything else: asset, liability, capital, income. */
    BalanceSheet("balance", "Balance Sheet Codes"),

    /** Analytical dimensions parallel to the nominal chart. */
    Layers("tracking", "Layers"),
}

/** The chart of accounts screen. */
data class ChartState(
    val view: ChartView = ChartView.Expense,
    val loading: Boolean = false,
    val accounts: List<CoaAccount> = emptyList(),
    val search: String = "",
    /** Inactive codes are hidden by default; they exist so history resolves. */
    val showInactive: Boolean = false,
    val expanded: Set<String> = emptySet(),
    val form: AccountForm? = null,
) {
    /** The rows this view shows, before the tree is built. */
    val visibleAccounts: List<CoaAccount>
        get() = accounts
            .filter { showInactive || it.isActive }
            .filter { account ->
                when (view) {
                    ChartView.Expense -> account.costType == CoaCostType.Expense
                    ChartView.BalanceSheet -> account.costType.isBalanceSheet
                    ChartView.Layers -> true
                }
            }

    val forest: CoaForest get() = ChartOfAccounts.tree(visibleAccounts)

    /** Search results replace the tree while a term is present. */
    val matches: List<CoaAccount> get() = ChartOfAccounts.search(visibleAccounts, search)
}

/**
 * The add / edit form for one chart row.
 *
 * [editing] is null when adding. Edit deliberately carries fewer fields than
 * add — the code, line type and parent are immutable once the row exists.
 */
data class AccountForm(
    val editing: CoaAccount? = null,
    val draft: NewAccount = NewAccount(),
    val name: String = "",
    val costType: CoaCostType = CoaCostType.Expense,
    val isActive: Boolean = true,
    val isPosting: Boolean = true,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editing != null

    val title: String get() = if (isEdit) "Edit account" else "New account"
}

/** The vendor register. */
data class VendorsState(
    val loading: Boolean = false,
    val rows: List<Vendor> = emptyList(),
    val search: String = "",
    val selectedId: String? = null,
    val history: List<VendorChange> = emptyList(),
    val historyLoading: Boolean = false,
    val form: VendorForm? = null,
) {
    val selected: Vendor? get() = rows.firstOrNull { it.id == selectedId }

    val verifiedCount: Int get() = rows.count { it.verified }
}

data class VendorForm(
    val editingId: String? = null,
    val draft: NewVendor = NewVendor(),
    val saving: Boolean = false,
) {
    val title: String get() = if (editingId == null) "New vendor" else "Edit vendor"
}

/** The approval chains. */
data class ApprovalsState(
    val module: ApprovalModule = ApprovalModule.PurchaseOrders,
    val loading: Boolean = false,
    val configs: List<ApprovalConfig> = emptyList(),
    val editing: ApprovalConfig? = null,
    val saving: Boolean = false,
) {
    /** The production-wide chain, which every department falls back to. */
    val defaultConfig: ApprovalConfig?
        get() = configs.firstOrNull { it.scope == ApprovalScope.All }

    val departmentConfigs: List<ApprovalConfig>
        get() = configs.filter { it.scope == ApprovalScope.Department }
}

/** Everything the console renders. */
data class AccountHubUiState(
    val viewer: AccountHubViewer = AccountHubViewer(),
    val sections: List<HubSection> = emptyList(),
    /**
     * The open screen, or null when this person has no hub screens at all.
     *
     * Null is a real state, not a loading one: a department user reaching the
     * console has the three spend tools and nothing the hub itself renders.
     */
    val area: HubArea? = null,
    val setup: SetupState = SetupState(),
    val chart: ChartState = ChartState(),
    val vendors: VendorsState = VendorsState(),
    val approvals: ApprovalsState = ApprovalsState(),
    val notice: String? = null,
) {
    val loading: Boolean
        get() = setup.loading || chart.loading || vendors.loading || approvals.loading
}
