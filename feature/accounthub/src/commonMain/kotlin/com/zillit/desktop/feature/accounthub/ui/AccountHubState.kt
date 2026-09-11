package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.BudgetUpload
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.asRows
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetRow
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
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
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubSection
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.ProductionSchedule
import com.zillit.desktop.feature.accounthub.domain.ProjectBudget
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
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
    val dealConditions: SectionEdit<List<DealCondition>> = SectionEdit(emptyList()),
    val payrollBureaus: SectionEdit<List<PayrollBureau>> = SectionEdit(emptyList()),
    val allowances: SectionEdit<AllowancesRentals> = SectionEdit(AllowancesRentals()),
    val payrollSettings: SectionEdit<PayrollSettings> = SectionEdit(PayrollSettings()),
    val poSetup: SectionEdit<PurchaseOrderSetup> = SectionEdit(PurchaseOrderSetup()),
    val invoicesSetup: SectionEdit<InvoicesSetup> = SectionEdit(InvoicesSetup()),
    val nonUnionPay: SectionEdit<NonUnionPay> = SectionEdit(NonUnionPay()),
    /**
     * The project's day-type catalogue.
     *
     * Its own slice beside the pay breakdown it is rendered inside, because it
     * has its own endpoint: editing a day type must not re-save the overtime,
     * premium and penalty rules next to it.
     */
    val dayTypes: SectionEdit<List<DayType>> = SectionEdit(DayTypes.defaults),
    /**
     * Department id to name, for the pay breakdown's scope picker.
     *
     * The hub's own service does not list departments, so the host supplies
     * them. Empty is a working state, not a broken one: the picker then shows
     * the ids it already holds rather than dropping a scope it cannot name.
     */
    val departments: Map<String, String> = emptyMap(),
    /**
     * Agreement documents are not a [SectionEdit] either.
     *
     * There is no combined save: uploading appends and the bin removes, each
     * on its own route, so there is nothing to be dirty against — the same
     * reason banks sit outside the section machinery.
     */
    val agreements: List<AgreementDocument> = emptyList(),
    val agreementsLoading: Boolean = false,
    /** Picked, described, not yet uploaded. The section's only pending state. */
    val agreementQueue: List<QueuedAgreementFile> = emptyList(),
    val agreementsUploading: Boolean = false,
    /** The one terms document, mid-upload. */
    val poTermsUploading: Boolean = false,
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
            if (schedule.dirty) add("Production Schedule")
            if (payrollDefaults.dirty) add("Payroll Defaults")
            if (dealConditions.dirty) add("Standard Deal Conditions")
            if (payrollBureaus.dirty) add("Payroll Bureau")
            if (allowances.dirty) add("Allowances & Rentals")
            if (payrollSettings.dirty) add("Payroll Settings")
            if (poSetup.dirty) add("Purchase Order Setup")
            if (invoicesSetup.dirty) add("Invoices Setup")
            if (nonUnionPay.dirty) add("Non-Union Pay Breakdown")
            if (dayTypes.dirty) add("Day Types")
        }
}

/** Which classes the chart is filtered to. */
/** One tracking code, with the depth it reads at. */
data class TrackingRow(val node: TrackingNode, val depth: Int, val orphaned: Boolean = false)

private const val MAX_TRACKING_DEPTH = 32

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
    /** The analytical dimensions behind the Layers tab. Read-only in this build. */
    val trackingSets: List<TrackingSet> = emptyList(),
) {
    /**
     * One set's codes as a flat, indented reading order.
     *
     * Tracking codes nest by [TrackingNode.parentId] — unlike the nominal
     * chart, which nests by code prefix — so the depth has to be walked
     * rather than counted out of the code itself.
     */
    fun rows(set: TrackingSet): List<TrackingRow> {
        val byParent = set.nodes.groupBy { it.parentId }
        val out = mutableListOf<TrackingRow>()
        fun walk(parentId: String?, depth: Int) {
            if (depth > MAX_TRACKING_DEPTH) return
            byParent[parentId].orEmpty()
                .filter { showInactive || it.isActive }
                .sortedBy { it.code }
                .forEach { node ->
                    out += TrackingRow(node, depth)
                    walk(node.id, depth + 1)
                }
        }
        walk(null, 0)
        // A code whose parent is inactive (or missing) would otherwise vanish
        // from a screen meant to show every dimension — the same orphan rule
        // the nominal chart applies.
        val shown = out.map { it.node.id }.toSet()
        val orphans = set.nodes
            .filter { it.id !in shown && (showInactive || it.isActive) }
            .sortedBy { it.code }
            .map { TrackingRow(it, depth = 0, orphaned = true) }
        return out + orphans
    }
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
/**
 * Which vendors the register shows.
 *
 * The web's three top-level tabs (`VendorsModule.TABS`), applied over the
 * fetched rows rather than re-asked of the server — verification is a boolean
 * on a row already in hand, and a round trip to hide half a list would make
 * the tab feel slower than the search does.
 */
enum class VendorFilter(val slug: String, val label: String) {
    All("all", "All Vendors"),
    Verified("verified", "Verified"),
    Unverified("unverified", "Non-Verified"),
}

data class VendorsState(
    val loading: Boolean = false,
    val rows: List<Vendor> = emptyList(),
    val search: String = "",
    val filter: VendorFilter = VendorFilter.All,
    val selectedId: String? = null,
    val history: List<VendorChange> = emptyList(),
    val historyLoading: Boolean = false,
    val form: VendorForm? = null,
) {
    val selected: Vendor? get() = rows.firstOrNull { it.id == selectedId }

    val verifiedCount: Int get() = rows.count { it.verified }

    /** The rows the open tab shows. */
    val visibleRows: List<Vendor>
        get() = when (filter) {
            VendorFilter.All -> rows
            VendorFilter.Verified -> rows.filter { it.verified }
            VendorFilter.Unverified -> rows.filterNot { it.verified }
        }

    /** How many rows each tab would show, for the count chips. */
    fun countFor(tab: VendorFilter): Int = when (tab) {
        VendorFilter.All -> rows.size
        VendorFilter.Verified -> verifiedCount
        VendorFilter.Unverified -> rows.size - verifiedCount
    }
}

/**
 * One file waiting to be uploaded.
 *
 * Title and description are editable before the upload, not after: the append
 * route is the only write, so a description typed later would have nowhere to
 * go — the web sets both on the pending row for the same reason.
 */
data class QueuedAgreementFile(
    val file: PickedAgreementFile,
    val title: String = file.name.substringBeforeLast('.'),
    val description: String = "",
)

data class VendorForm(
    val editingId: String? = null,
    val draft: NewVendor = NewVendor(),
    val saving: Boolean = false,
) {
    val title: String get() = if (editingId == null) "New vendor" else "Edit vendor"
}

/** Which step of the import the accountant is on. */
enum class ImportStep { Upload, Preview, Done }

/**
 * Importing a budget file.
 *
 * Three steps, and the middle one is the point: the parse is a guess at
 * somebody else's spreadsheet, and committing it writes codes into the chart
 * every other tool codes against. Nothing is written until [ImportStep.Preview]
 * is confirmed.
 */
data class BudgetImportState(
    val open: Boolean = false,
    val step: ImportStep = ImportStep.Upload,
    val uploading: Boolean = false,
    val committing: Boolean = false,
    val parsed: ParsedBudget? = null,
    val upload: BudgetUpload? = null,
    val meta: BudgetImportMeta = BudgetImportMeta(),
    val mode: CoaImportMode = CoaImportMode.Default,
    /** The version the commit created, for the last step to name. */
    val created: BudgetVersion? = null,
) {
    /**
     * Whether the import can be written.
     *
     * A version and a name, and something to save — a file that parsed to no
     * codes at all is a parse that failed quietly, and committing it would add
     * an empty version to the production's history.
     */
    val canCommit: Boolean
        get() = parsed?.isEmpty == false && meta.isComplete && !committing
}

/**
 * The versioned project budget.
 *
 * Read-only: a version is created by importing a budget file, and Live and
 * Archived ones cannot be edited at all.
 */
data class BudgetState(
    val loading: Boolean = false,
    val versions: List<BudgetVersion> = emptyList(),
    val selectedId: String? = null,
    val lines: List<BudgetLine> = emptyList(),
    val linesLoading: Boolean = false,
    val import: BudgetImportState = BudgetImportState(),
) {
    val selected: BudgetVersion? get() = versions.firstOrNull { it.id == selectedId }

    val rows: List<BudgetRow> get() = lines.asRows()

    /** The Live version, of which there is at most one. */
    val live: BudgetVersion? get() = versions.firstOrNull { it.status == BudgetStatus.Live }
}

/**
 * The trial balance, and the period it is asked for.
 *
 * [applied] is what the rows on screen came from; [draft] is what the filters
 * say now. They differ while the user is choosing, and the report is only
 * re-asked for on an explicit refresh — a report that re-ran on every keystroke
 * would spend the server's time on periods nobody meant.
 */
data class TrialBalanceState(
    val loading: Boolean = false,
    val report: TrialBalance = TrialBalance(),
    val draft: TrialBalanceQuery = TrialBalanceQuery(0, 0),
    val applied: TrialBalanceQuery? = null,
) {
    val isDirty: Boolean get() = applied != null && applied != draft
}

/**
 * The closeout bible.
 *
 * Same shape as the trial balance beside it: filters are a draft until the
 * report is explicitly re-run, because this is the heaviest read on the
 * service and a period nobody meant is not worth asking for.
 */
data class BibleReportState(
    val loading: Boolean = false,
    val report: BibleReport = BibleReport(),
    val draft: BibleQuery = BibleQuery(0, 0),
    val applied: BibleQuery? = null,
    /** Accounts the reader has folded away, by code. */
    val collapsed: Set<String> = emptySet(),
) {
    val isDirty: Boolean get() = applied != null && applied != draft
}

/**
 * Closing a period.
 *
 * [pending] is a date the accountant has chosen but not confirmed. Closing is
 * irreversible across every source module, so it is always a two-step act —
 * there is no unlock endpoint to undo a slip.
 */
data class PeriodCloseState(
    val loading: Boolean = false,
    val lock: PeriodLock = PeriodLock(),
    val closing: Boolean = false,
    val pendingCloseMillis: Long? = null,
)

/** The approval chains. */
data class ApprovalsState(
    val module: ApprovalModule = ApprovalModule.PurchaseOrders,
    /**
     * Which modules have a chain, for the tab strip.
     *
     * Absent means unknown, not unconfigured: the summary endpoint does not
     * answer for every module, and seeding the missing ones as false would pin
     * Time Card to "Not set" forever — the mistake the web documents in
     * `mergeModuleConfigSummary`. A module the user has actually opened is
     * answered from its own configs, which is fresher, so those entries win.
     */
    val configured: Map<ApprovalModule, Boolean> = emptyMap(),
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

/** A field being added or inspected, addressed by section and key. */
data class FieldFocus(val sectionKey: String, val fieldId: String? = null) {
    val isNew: Boolean get() = fieldId == null
}

/** What the add-a-field panel is holding before it is added. */
data class NewFieldDraft(
    val name: String = "",
    val type: String = FormFieldType.Text.wire,
    val required: Boolean = false,
    val selectionType: String? = null,
) {
    val isReady: Boolean get() = name.isNotBlank()
}

/**
 * The per-module form editor.
 *
 * [saved] is what the server last answered with, kept beside [template] so
 * the page can say whether anything is unsaved. A form template is a document
 * that other people's screens read from, so leaving without saving is a real
 * thing to be told about.
 */
data class FormConfigState(
    val module: FormModule = FormModule.PurchaseOrders,
    val template: FormTemplate = FormTemplate(),
    val saved: FormTemplate = FormTemplate(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** Edit mode, as against the read-only preview the page opens on. */
    val editing: Boolean = false,
    val collapsed: Set<String> = emptySet(),
    val focus: FieldFocus? = null,
    val draft: NewFieldDraft = NewFieldDraft(),
    /** A section being added: the key to insert after, or null for the top. */
    val addingSectionAfter: String? = null,
    val addingSectionName: String = "",
    val renamingSection: String? = null,
    val renamingSectionName: String = "",
    val removingSection: FormSection? = null,
    val confirmingReset: Boolean = false,
) {
    val dirty: Boolean get() = template != saved

    val sectionCount: Int get() = template.configurable.size

    val fieldCount: Int get() = template.fieldCount

    val customCount: Int get() = template.customFieldCount

    fun isCollapsed(key: String): Boolean = key in collapsed

    /** The field the inspector is showing, or null when it is adding one. */
    val focusedField: FormField?
        get() = focus?.fieldId?.let { id ->
            template.section(focus.sectionKey)?.fields?.firstOrNull { it.id == id }
        }

    val focusedSection: FormSection?
        get() = focus?.let { template.section(it.sectionKey) }
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
    val budget: BudgetState = BudgetState(),
    val trialBalance: TrialBalanceState = TrialBalanceState(),
    val periodClose: PeriodCloseState = PeriodCloseState(),
    val bible: BibleReportState = BibleReportState(),
    val approvals: ApprovalsState = ApprovalsState(),
    val formConfig: FormConfigState = FormConfigState(),
    val notice: String? = null,
) {
    val loading: Boolean
        get() = setup.loading || chart.loading || vendors.loading || approvals.loading
}
