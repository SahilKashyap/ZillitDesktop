package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetRow
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetUpload
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.CashCloseDashboard
import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.ChartSort
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaForest
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.CoaStats
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.CustomDay
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubBadgeCounts
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubSection
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.accounthub.domain.IsdCountry
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.domain.ProductionSchedule
import com.zillit.desktop.feature.accounthub.domain.ProjectBudget
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.domain.SetupGap
import com.zillit.desktop.feature.accounthub.domain.SetupSnapshot
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorBank
import com.zillit.desktop.feature.accounthub.domain.VendorChange
import com.zillit.desktop.feature.accounthub.domain.asRows
import com.zillit.desktop.feature.accounthub.domain.fieldErrors

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
    ;

    /**
     * The count on the tab's mono chip — the web's `TAB_DEFS`: nine accounting
     * sections plus Companies and Bank Accounts, and seven deal-memo ones.
     */
    val count: Int get() = when (this) {
        Accounting -> ACCOUNTING_SECTIONS
        DealMemo -> DEAL_SECTIONS
    }

    private companion object {
        const val ACCOUNTING_SECTIONS = 11
        const val DEAL_SECTIONS = 7
    }
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

/** One named overlay, as typed. */
data class CustomDayText(
    val id: String,
    val name: String = "",
    val dates: DateRangeText = DateRangeText(),
) {
    fun toDomain(): CustomDay {
        val phase = dates.toPhase()
        return CustomDay(id = id, name = name, startDate = phase.startDate, endDate = phase.endDate)
    }

    companion object {
        fun from(day: CustomDay) = CustomDayText(
            id = day.id,
            name = day.name,
            dates = DateRangeText.from(SchedulePhase(day.startDate, day.endDate)),
        )
    }
}

/** The production schedule, as typed. */
data class ScheduleForm(
    val overall: DateRangeText = DateRangeText(),
    val prep: DateRangeText = DateRangeText(),
    val shoot: DateRangeText = DateRangeText(),
    val wrap: DateRangeText = DateRangeText(),
    val customDays: List<CustomDayText> = emptyList(),
) {
    fun toDomain(): ProductionSchedule {
        val whole = overall.toPhase()
        return ProductionSchedule(
            startDate = whole.startDate,
            endDate = whole.endDate,
            prep = prep.toPhase(),
            shoot = shoot.toPhase(),
            wrap = wrap.toPhase(),
            customDays = customDays.map { it.toDomain() },
        )
    }

    companion object {
        fun from(schedule: ProductionSchedule) = ScheduleForm(
            overall = DateRangeText.from(SchedulePhase(schedule.startDate, schedule.endDate)),
            prep = DateRangeText.from(schedule.prep),
            shoot = DateRangeText.from(schedule.shoot),
            wrap = DateRangeText.from(schedule.wrap),
            customDays = schedule.customDays.map(CustomDayText::from),
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

// -- production setup: the drill-down modals ----------------------------------

/** The three module setups this console edits in a modal — the web's `SETUP_DETAILS`. */
enum class SetupModal(val slug: String, val title: String, val eyebrow: String, val description: String) {
    PurchaseOrders(
        "po_setup",
        "Purchase Order Entry Setup",
        "POs",
        "Defaults for the PO module — description formatting, rental-split handling, and auto-assignment rules.",
    ),
    Invoices(
        "invoices_setup",
        "Invoices Entry Setup",
        "Invoices",
        "AP controls — who can post invoices and at what limit, which events trigger an alert, and the " +
            "sign-off chain that gates payment runs.",
    ),
    Payroll(
        "payroll_settings",
        "Payroll Entry Setup",
        "Payroll",
        "Approvers and the project's pay-cycle window. Drives the approval gate + week boundary used by " +
            "every timecard.",
    ),
}

/** One section in a modal's left nav — `name`, and the mono count chip, or a dash. */
data class SetupModalSection(val id: String, val name: String, val count: Int? = null)

/** A drill-down modal, and which of its sections is open. */
data class SetupModalState(
    val modal: SetupModal,
    val section: String,
    val loading: Boolean = false,
    val loadError: String? = null,
)

/** Who is being picked, and for what — one dialog serves every user field. */
data class UserPickerState(
    val purpose: UserPickerPurpose,
    val selected: List<String> = emptyList(),
    val search: String = "",
    /** The row the pick lands on — a run-authorisation level, a team-member slot. */
    val index: Int = -1,
    val multiple: Boolean = true,
)

enum class UserPickerPurpose {
    PayrollApprovers,
    InvoiceTeamMember,
    RunAuthorisation,
    PayrollGroupAssignee,
    PayrollGroupCrew,
    ClosingRecipients,
}

/** An invoices team member being added or edited in the modal's dialog. */
data class InvoiceMemberDraft(val index: Int?, val member: InvoiceTeamMember = InvoiceTeamMember())

/** The payroll-accounts grid, open over the modal's list. */
data class PayrollAccountsDraft(
    val rows: List<PayrollAccountRow> = emptyList(),
    val saving: Boolean = false,
)

/** A pay rule being added or edited — the web's `RateRowModal`. */
data class PayRuleEditor(
    val kind: PayRuleKind,
    val index: Int?,
    val rule: PayRule,
)

/** A row about to be removed, named so the confirmation can say which. */
sealed interface SetupRemoval {
    data class CompanyRow(val company: Company) : SetupRemoval
    data class BankRow(val bank: BankAccount) : SetupRemoval
    data class AgreementRow(val document: AgreementDocument) : SetupRemoval
    data class PayrollGroupRow(val group: PayrollGroup) : SetupRemoval
    data class PayrollAccountCode(val code: String, val accountId: String?) : SetupRemoval
}

/** The currency picker's two filter chips. */
enum class CurrencyFilter(val label: String) { All("All"), Major("Major") }

/** Everything Production Setup holds. */
data class SetupState(
    val tab: SetupTab = SetupTab.Accounting,
    val loading: Boolean = false,
    /** True once every slice has been asked for — the tour's "unknown ≠ missing" gate. */
    val loaded: Boolean = false,
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
    /** The terms document being fetched for the OS to open — the web's "Opening…". */
    val poTermsOpening: Boolean = false,
    /** Under the terms block, where the web shows its refusals and failures; cleared on the next attempt. */
    val poTermsError: String? = null,
    /**
     * Banks are not a [SectionEdit].
     *
     * Every other section batches into one save; a bank is a first-class record
     * with its own endpoints, so each row commits on its own and there is
     * nothing to be dirty against.
     */
    val banks: List<BankAccount> = emptyList(),
    val banksLoading: Boolean = false,
    /** Null until the bank list has been read at least once — the tour's rule 3. */
    val banksLoaded: Boolean = false,
    val currencyCatalogue: List<ProjectCurrency> = emptyList(),
    val countryTaxes: List<CountryTaxes> = emptyList(),
    val companyDraft: Company? = null,
    val bankDraft: BankAccount? = null,
    val bankSaving: Boolean = false,
    /** Which bank card has been revealed; the card re-masks itself after five seconds. */
    val revealedBankId: String? = null,
    // -- the drill-down modals --
    val modal: SetupModalState? = null,
    val poRules: SectionEdit<List<AssignmentRule>> = SectionEdit(emptyList()),
    val invoiceRules: SectionEdit<List<AssignmentRule>> = SectionEdit(emptyList()),
    val rulesLoading: Boolean = false,
    val invoiceMemberDraft: InvoiceMemberDraft? = null,
    val payrollGroups: List<PayrollGroup> = emptyList(),
    val payrollGroupsLoading: Boolean = false,
    val payrollGroupDraft: PayrollGroup? = null,
    val payrollGroupSaving: Boolean = false,
    val payrollAccounts: PayrollAccountsDraft? = null,
    val userPicker: UserPickerState? = null,
    // -- section-local UI --
    val currencyFilter: CurrencyFilter = CurrencyFilter.All,
    val currencySearch: String = "",
    val currencyPickerOpen: Boolean = false,
    /** The country whose catalogue rates the tax section is showing, by code. */
    val taxCountry: String? = null,
    val tagDraft: String = "",
    val ruleEditor: PayRuleEditor? = null,
    val departmentPickerOpen: Boolean = false,
    val departmentPickerSearch: String = "",
    val removal: SetupRemoval? = null,
) {
    /** Sections with unsaved edits, so the shell can warn before leaving. */
    val dirtySections: List<String>
        get() = buildList {
            if (companies.dirty) add("Companies")
            if (currencies.dirty) add("Project Currencies")
            if (taxTypes.dirty) add("Tax Types")
            if (assetTags.dirty) add("Account Tags")
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

    /** Whether the open modal has unsaved work — its "Unsaved" pill and Save button. */
    val modalDirty: Boolean
        get() = when (modal?.modal) {
            SetupModal.PurchaseOrders -> poSetup.dirty || poRules.dirty
            SetupModal.Invoices -> invoicesSetup.dirty || invoiceRules.dirty
            SetupModal.Payroll -> payrollSettings.dirty
            null -> false
        }

    val modalSaving: Boolean
        get() = when (modal?.modal) {
            SetupModal.PurchaseOrders -> poSetup.saving || poRules.saving
            SetupModal.Invoices -> invoicesSetup.saving || invoiceRules.saving
            SetupModal.Payroll -> payrollSettings.saving
            null -> false
        }

    /** The catalogue rows the currency picker shows, filtered and searched, chosen ones removed. */
    val currencyChoices: List<ProjectCurrency>
        get() {
            val chosen = currencies.edited.currencies.map { it.code }.toSet()
            val needle = currencySearch.trim()
            return currencyCatalogue
                .filter { it.code !in chosen }
                .filter { currencyFilter == CurrencyFilter.All || it.code in CurrencySettings.MAJOR_CODES }
                .filter {
                    needle.isEmpty() || it.code.contains(needle, true) || it.name.contains(needle, true)
                }
        }

    /** The tour's view of what is set up. Nothing counts until the setup has loaded. */
    fun snapshot(coaReady: Boolean, coaEmpty: Boolean): SetupSnapshot = SetupSnapshot(
        ready = loaded,
        companies = companies.saved.size,
        banks = if (banksLoaded) banks.size else null,
        currencies = currencies.saved.currencies.size,
        tags = assetTags.saved.size,
        taxes = taxTypes.saved.size,
        coaReady = coaReady,
        coaEmpty = coaEmpty,
        scheduleSet = schedule.saved.toDomain().isSet,
        payRules = nonUnionPay.saved.let { it.overtimes.size + it.premiums.size + it.penalties.size },
        entitlements = allowances.saved.let { it.allowances.size + it.rentals.size },
        agreements = agreements.size,
        conditions = dealConditions.saved.size,
        bureaus = payrollBureaus.saved.size,
    )
}

// -- chart of accounts ----------------------------------------------------------

/** One tracking code, with the depth it reads at. */
data class TrackingRow(val node: TrackingNode, val depth: Int, val orphaned: Boolean = false)

private const val MAX_TRACKING_DEPTH = 32

/** Which classes the chart is filtered to. */
enum class ChartView(val slug: String, val label: String) {
    /** The cost side — what a production spends against. */
    Expense("accounts", "Cost Accounts"),

    /** Everything else: asset, liability, capital, income. */
    BalanceSheet("balance", "Balance Sheet Codes"),

    /** Analytical dimensions parallel to the nominal chart. */
    Layers("tracking", "Layers"),
}

/** A layer (set) being added or edited — the web's `SetEditorModal`. */
data class LayerSetDraft(val set: TrackingSet, val isNew: Boolean, val saving: Boolean = false)

/** A layer code being added or edited — the web's `NodeEditorModal`. */
data class LayerNodeDraft(val node: TrackingNode, val isNew: Boolean, val saving: Boolean = false)

/** What a delete confirmation on the Layers tab is about. */
sealed interface LayerDelete {
    data class WholeSet(val set: TrackingSet) : LayerDelete
    data class OneNode(val setId: String, val node: TrackingNode) : LayerDelete
}

/** The full-page "New COA Entry" grid. */
data class BulkAddState(
    val parent: CoaAccount? = null,
    val rows: List<CoaBulkRow> = emptyList(),
    /** The class the grid was opened for, so a row defaults to it. */
    val costType: CoaCostType = CoaCostType.Expense,
) {
    val anySaving: Boolean get() = rows.any {
        it.status == com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus.Saving
    }

    val anyError: Boolean get() = rows.any {
        it.status == com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus.Error
    }

    val anySaved: Boolean get() = rows.any {
        it.status == com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus.Saved
    }

    /** The web's save-tone label: "Saving…", "Couldn't save some rows", "All changes saved". */
    val saveLabel: String
        get() = when {
            anySaving -> "Saving…"
            anyError -> "Couldn't save some rows"
            anySaved -> "All changes saved"
            else -> ""
        }
}

/** The chart of accounts screen. */
data class ChartState(
    val view: ChartView = ChartView.Expense,
    val loading: Boolean = false,
    /** True once the chart has answered at least once — the tour's `coaReady`. */
    val loaded: Boolean = false,
    val accounts: List<CoaAccount> = emptyList(),
    val search: String = "",
    /** On by default, as on the web — inactive codes exist so history resolves. */
    val showInactive: Boolean = true,
    val expanded: Set<String> = emptySet(),
    /** True after Expand all, so the toolbar can offer Collapse all. */
    val expandedAll: Boolean = false,
    val mode: ChartMode = ChartMode.Tree,
    val sort: ChartSort = ChartSort(),
    val form: AccountForm? = null,
    val confirmDeactivate: CoaAccount? = null,
    val bulk: BulkAddState? = null,
    /** The analytical dimensions behind the Layers tab. */
    val trackingSets: List<TrackingSet> = emptyList(),
    val layerSetDraft: LayerSetDraft? = null,
    val layerNodeDraft: LayerNodeDraft? = null,
    val layerDelete: LayerDelete? = null,
    /** The server's refusal to delete something in use, shown in its own words. */
    val layerInUse: String? = null,
    val openLayers: Set<String> = emptySet(),
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

    /** The table's rows: searched or all, sorted by the active column. */
    val tableRows: List<CoaAccount>
        get() = (if (search.isBlank()) visibleAccounts else matches).sortedWith(sort.comparator())

    val stats: CoaStats get() = CoaStats.of(visibleAccounts)

    val isEmpty: Boolean get() = loaded && accounts.isEmpty()
}

/**
 * The add / edit form for one chart row.
 *
 * [editing] is null when adding. On edit the line type and parent are offered
 * for a manual row — changing either is a structural edit the server re-walks
 * — and the cost type is locked on a budget-imported row.
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

    /** Whether the edit re-types or re-parents the row. */
    val structureChanged: Boolean
        get() = editing != null && (draft.lineType != editing.lineType || draft.parentId != editing.parentId)
}

// -- vendors --------------------------------------------------------------------

/**
 * Which vendors the register shows.
 *
 * The web's tabs (`VendorsModule.TABS`), applied over the fetched rows rather
 * than re-asked of the server — verification is a boolean on a row already in
 * hand, and a round trip to hide half a list would make the tab feel slower
 * than the search does. "Added by Me" is the one a department user gets.
 */
enum class VendorFilter(val slug: String, val label: String) {
    All("all", "All Vendors"),
    Verified("verified", "Verified"),
    Unverified("unverified", "Non-Verified"),
    Mine("mine", "Added by Me"),
}

/** The full-page vendor form — the web's `VendorForm`. */
data class VendorFormPage(
    val editingId: String? = null,
    val draft: NewVendor = NewVendor(),
    /**
     * The linked bank record this vendor's details live in, when it has one.
     *
     * Deleting bank details deletes *this*, immediately — the web's rule — and
     * the delete is only offered while it is set. See `VendorBank`.
     */
    val bankId: String? = null,
    /** The linked record is being fetched to seed the bank block. */
    val bankLoading: Boolean = false,
    val deletingBank: Boolean = false,
    val saving: Boolean = false,
    val verifying: Boolean = false,
    /** Fields the person has left, so an error shows only once it is theirs to fix. */
    val touched: Set<String> = emptySet(),
    val showErrors: Boolean = false,
    val confirmDeleteBank: Boolean = false,
    /**
     * The postcode lookup is on the wire — only then, not while it waits out
     * the pause after typing. City and county show a spinner meanwhile.
     */
    val postcodeLooking: Boolean = false,
) {
    val title: String get() = if (editingId == null) "New Vendor" else "Editing"

    val errors: Map<String, String> get() = draft.fieldErrors()

    /** An error is shown once the field was touched, or after a refused submit. */
    fun errorFor(field: String): String? = errors[field]?.takeIf { showErrors || field in touched }
}

/** Kept for the collaborators that predate the full-page form; the page is [VendorFormPage]. */
data class VendorForm(
    val editingId: String? = null,
    val draft: NewVendor = NewVendor(),
    val saving: Boolean = false,
) {
    val title: String get() = if (editingId == null) "New vendor" else "Edit vendor"
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
    /** Who is looking, for the "Added by Me" tab. */
    val viewerId: String = "",
    /** The vendor open in the detail modal. */
    val detailId: String? = null,
    /** Bank details unmasked in the detail; re-masked after five seconds. */
    val bankRevealed: Boolean = false,
    /**
     * The detail's vendor's linked bank record, and whether it is on its way.
     *
     * Held separately from the row because the row's own bank columns are the
     * legacy copy — see `VendorBank.resolve`, which reads this first.
     */
    val bankRecord: BankAccount? = null,
    val bankRecordLoading: Boolean = false,
    val page: VendorFormPage? = null,
    val confirmDelete: Vendor? = null,
    /** The vendor whose history side panel is open. */
    val historyFor: String? = null,
    val verifyingId: String? = null,
    /**
     * A vendor a route asked to edit, before the register that holds it has
     * loaded. Opened the moment it arrives, then cleared — the web's
     * `?action=edit&id=` waits for its vendors the same way.
     */
    val pendingEditId: String? = null,
    /**
     * The country catalogue the form's country and dial-code pickers read.
     *
     * The bundled copy until the live one loads, and for good when it cannot,
     * so neither picker is ever empty. See `IsdCountries`.
     */
    val countries: List<IsdCountry> = IsdCountries.bundled,
    val countriesLoaded: Boolean = false,
) {
    val selected: Vendor? get() = rows.firstOrNull { it.id == selectedId }

    val detail: Vendor? get() = rows.firstOrNull { it.id == detailId }

    /** The detail's bank block, from the linked record when it has loaded. */
    val detailBank: VendorBank?
        get() = detail?.let { vendor ->
            VendorBank.resolve(vendor, bankRecord?.takeIf { it.id == vendor.bankId })
        }

    val verifiedCount: Int get() = rows.count { it.verified }

    /** Whether a search or a tab narrows the register, which changes what an empty table means. */
    val isFiltered: Boolean get() = search.isNotBlank() || filter != VendorFilter.All

    /** The rows the open tab shows, before any search. */
    val visibleRows: List<Vendor>
        get() = when (filter) {
            VendorFilter.All -> rows
            VendorFilter.Verified -> rows.filter { it.verified }
            VendorFilter.Unverified -> rows.filterNot { it.verified }
            VendorFilter.Mine -> rows.filter { it.addedBy == viewerId && viewerId.isNotBlank() }
        }

    /**
     * The open tab's rows narrowed by the search, the web's way.
     *
     * Matches the name, the contact person, the email, the tax number and the
     * **department's name** — the last is why this is local: the server holds a
     * department id, so a search for "Art" sent to it could never find the Art
     * Department's suppliers. The tab counts stay about the whole register.
     */
    fun searched(departmentName: (String?) -> String): List<Vendor> {
        val needle = search.trim().lowercase()
        if (needle.isEmpty()) return visibleRows
        return visibleRows.filter { vendor ->
            listOf(
                vendor.name,
                vendor.contactPerson,
                vendor.email,
                vendor.vatNumber,
                departmentName(vendor.departmentId),
            ).any { it.lowercase().contains(needle) }
        }
    }

    /** How many rows each tab would show, for the count chips. */
    fun countFor(tab: VendorFilter): Int = when (tab) {
        VendorFilter.All -> rows.size
        VendorFilter.Verified -> verifiedCount
        VendorFilter.Unverified -> rows.size - verifiedCount
        VendorFilter.Mine -> rows.count { it.addedBy == viewerId && viewerId.isNotBlank() }
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

// -- budget ---------------------------------------------------------------------

/** Which step of the import the accountant is on. */
enum class ImportStep(val label: String) { Upload("Upload"), Preview("Preview"), Done("Commit") }

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
    val commitError: String? = null,
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
    val openingFile: Boolean = false,
) {
    val selected: BudgetVersion? get() = versions.firstOrNull { it.id == selectedId }

    val rows: List<BudgetRow> get() = lines.asRows()

    /** The Live version, of which there is at most one. */
    val live: BudgetVersion? get() = versions.firstOrNull { it.status == BudgetStatus.Live }
}

// -- reports --------------------------------------------------------------------
//
// The trial balance's state is TrialBalanceState.kt's, and the bible's its own file's.

/** The three tabs of the Period Close module. */
enum class PeriodCloseTab(val slug: String, val label: String) {
    Close("close", "Period Close"),
    CashClose("cash-close", "Cash & Close"),
    Publish("publish", "Publish Package"),
}

/** The outcome of the last close attempt, shown in the form's result banner. */
data class CloseResult(val ok: Boolean, val message: String)

data class CashCloseState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val dashboard: CashCloseDashboard = CashCloseDashboard(),
    /** Ticked locally; the web never writes these back. */
    val checked: Set<String> = emptySet(),
)

data class PublishState(
    val packages: List<ClosingPackage> = listOf(ClosingPackage(id = 1)),
    val nextId: Int = 2,
    val publishing: Boolean = false,
    val result: CloseResult? = null,
    /** The package whose recipient menu is open, and its search text. */
    val openMenu: Int? = null,
    val menuQuery: String = "",
) {
    val validPackages: List<ClosingPackage> get() = packages.filter { it.isValid }

    val totalRecipients: Int get() = packages.sumOf { it.recipientCount }

    val totalReports: Int get() = packages.sumOf { it.reports.size }
}

/**
 * Closing a period.
 *
 * [pendingCloseMillis] is a date the accountant has chosen but not confirmed.
 * Closing is irreversible across every source module, so it is always a
 * two-step act — there is no unlock endpoint to undo a slip.
 */
data class PeriodCloseState(
    val loading: Boolean = false,
    val lock: PeriodLock = PeriodLock(),
    val closing: Boolean = false,
    val pendingCloseMillis: Long? = null,
    val tab: PeriodCloseTab = PeriodCloseTab.Close,
    /** The "Close through date" picker, as typed. */
    val closeDateText: String = "",
    val result: CloseResult? = null,
    val cashClose: CashCloseState = CashCloseState(),
    val publish: PublishState = PublishState(),
)

// -- approvers ------------------------------------------------------------------

/** The department toolbar's segmented filter — All / Custom / Default. */
enum class DepartmentFilter(val label: String) { All("All"), Custom("Custom"), Default("Default") }

/** What the builder asks before it saves — the web's `confirmSave`. */
sealed interface BuilderConfirm {
    /**
     * "Empty approval levels": a filled level sits below an empty one, so the
     * empties go and the rest move up. [payload] is what will be sent,
     * already compacted — the web holds the same.
     */
    data class EmptyLevels(val levels: List<Int>, val payload: ApprovalConfig) : BuilderConfirm

    /** "Remove all approvers?" on a department chain — deleting [configId] puts it back on the global one. */
    data class RevertToGlobal(val configId: String) : BuilderConfirm
}

/**
 * The chain builder — a full view over the module page, as on the web.
 *
 * [initial] is what the server holds, so Cancel can drop the edits and the
 * top bar can say whether anything changed.
 */
data class ApprovalBuilder(
    val config: ApprovalConfig,
    val initial: ApprovalConfig,
    /** The level whose user picker is open, by its 1-based order. */
    val pickerTier: Int? = null,
    /** Which of that level's rules the picked people join, 0-based. */
    val pickerRule: Int = 0,
    val pickerSearch: String = "",
    /**
     * Ticked in the open picker and not yet added — the web's `picked`.
     * Closing the picker drops them; "Add N users" commits them.
     */
    val picked: List<String> = emptyList(),
    val confirm: BuilderConfirm? = null,
    /** Why the last save did not go through, shown in the builder — the web's `saveMsg`. */
    val error: String? = null,
) {
    val dirty: Boolean get() = config != initial
}

/** The approval chains. */
data class ApprovalsState(
    val module: ApprovalModule = ApprovalModule.PurchaseOrders,
    /**
     * Which modules have a chain, for the module rail.
     *
     * Absent means unknown, not unconfigured: the summary endpoint does not
     * answer for every module, and seeding the missing ones as false would pin
     * Time Card to "Not set" forever — the mistake the web documents in
     * `mergeModuleConfigSummary`. A module the user has actually opened is
     * answered from its own configs, which is fresher, so those entries win.
     */
    val configured: Map<ApprovalModule, Boolean> = emptyMap(),
    val loading: Boolean = false,
    /**
     * Why the open module's chains could not be read. The page shows it with
     * a Retry in place of the chains: an empty list after a failed read would
     * say every department is "Not configured", which is not what is known.
     */
    val loadError: String? = null,
    /** The module [configs] were read for; re-reading the one on screen is silent. */
    val loadedModule: ApprovalModule? = null,
    val configs: List<ApprovalConfig> = emptyList(),
    val saving: Boolean = false,
    val moduleSearch: String = "",
    val departmentSearch: String = "",
    val departmentFilter: DepartmentFilter = DepartmentFilter.All,
    /** Department rows opened to show their levels. */
    val expanded: Set<String> = emptySet(),
    val builder: ApprovalBuilder? = null,
    /**
     * Who holds view access on the module's tool, by id. Null until the
     * rights call answers and empty when it failed — either way the picker
     * still offers the accounts team, and nobody else (the web fails closed).
     */
    val candidateIds: Set<String>? = null,
) {
    /** The production-wide chain, which every department falls back to. */
    val defaultConfig: ApprovalConfig?
        get() = configs.firstOrNull { it.scope == ApprovalScope.All }

    val departmentConfigs: List<ApprovalConfig>
        get() = configs.filter { it.scope == ApprovalScope.Department }

    fun configFor(departmentId: String): ApprovalConfig? =
        departmentConfigs.firstOrNull { it.departmentId == departmentId }

    /** Departments matching the search and the segmented filter. */
    fun visibleDepartments(departments: List<HubDepartment>): List<HubDepartment> {
        val needle = departmentSearch.trim()
        return departments
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .filter { dept ->
                val custom = configFor(dept.id)?.isConfigured == true
                when (departmentFilter) {
                    DepartmentFilter.All -> true
                    DepartmentFilter.Custom -> custom
                    DepartmentFilter.Default -> !custom
                }
            }
    }

    fun customCount(departments: List<HubDepartment>): Int =
        departments.count { configFor(it.id)?.isConfigured == true }
}

// -- forms configuration --------------------------------------------------------

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

/** The "Set Approver Level" scope choice — everyone, or one department. */
data class ScopeModalState(val mode: ApprovalScope? = null, val departmentId: String? = null) {
    val canContinue: Boolean get() =
        mode == ApprovalScope.All || (mode == ApprovalScope.Department && departmentId != null)
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
    val moduleSearch: String = "",
    /** The Rearrange panel, and the section whose fields it lists. */
    val rearrange: Boolean = false,
    val rearrangeSection: String? = null,
    /** The terms section being edited in the side panel. */
    val termsEditing: Boolean = false,
    val scopeModal: ScopeModalState? = null,
    /** The approval-level builder the Set Approver Level flow opens. */
    val approverBuilder: ApprovalBuilder? = null,
    val approverSaving: Boolean = false,
    val message: CloseResult? = null,
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

    /** The terms section, when the module has one. */
    val termsSection: FormSection? get() = template.section(FormTemplate.TERMS_SECTION)
}

// -- shell ----------------------------------------------------------------------

/** The setup tour — the intro modal, then one step per gap. */
data class TourState(
    val open: Boolean = false,
    val intro: Boolean = true,
    val steps: List<SetupGap> = emptyList(),
    val index: Int = 0,
) {
    val current: SetupGap? get() = steps.getOrNull(index)

    val isLast: Boolean get() = index >= steps.lastIndex
}

/** Everything the console renders. */
/**
 * A film tool shown inside the console — the web's nested routes.
 *
 * On the web, Purchase Orders, Invoices, the spend tools and the reports render
 * inside `AccountHubShell` with the hub sidebar still beside them. [path] is
 * the tool route on screen (a sub-route once the tool navigates within itself),
 * [title] the sidebar row it belongs to.
 */
data class EmbeddedTool(val path: String, val title: String)

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
    /** The sidebar's red counts, fed by the host from the notification ledger. */
    val badges: HubBadgeCounts = HubBadgeCounts.Empty,
    /** The production's crew, for every user picker. */
    val users: List<HubUser> = emptyList(),
    val departmentList: List<HubDepartment> = emptyList(),
    val tour: TourState = TourState(),
    /** The open production's name, for the bible's metadata banner and the exports. */
    val projectName: String = "",
    /** A tool rendered inside the shell, over [area]; null shows the area itself. */
    val embedded: EmbeddedTool? = null,
) {
    val loading: Boolean
        get() = setup.loading || chart.loading || vendors.loading || approvals.loading

    /**
     * A name for a user id.
     *
     * Never the id itself: a 24-character hex string in a table of approvers
     * reads as a broken screen, and the web collapses the same miss to an em
     * dash. A roster that has not landed yet shows dashes and fills in.
     */
    /**
     * A person's name, never their id.
     *
     * Fell back to the raw id for anyone not on the roster, which put a
     * `6a2becfdf0a26d2…` into approver chips, audit rows, period-close sign-offs
     * and vendor history — eleven places across the hub. An id tells nobody
     * anything and reads as corruption; a dash says, honestly, that the name
     * could not be found.
     */
    fun userName(id: String): String =
        users.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_PERSON

    fun user(id: String): HubUser? = users.firstOrNull { it.id == id }

    /** The vendor register as the open tab and the search show it. */
    val visibleVendors: List<Vendor> get() = vendors.searched(::departmentName)

    /**
     * A department's name as a person reads it.
     *
     * The crew directory answers translation keys, not words: live, the vendor
     * register's department column said `direction_label` and
     * `assistant_directors_label`. Translated here, at the one seam every
     * department label passes through, the way the PO tool's `departmentName`
     * does; and an id that was never named is blank rather than 24 hex digits.
     */
    fun departmentName(id: String?): String =
        id?.let { key -> departmentList.firstOrNull { it.id == key }?.name ?: setup.departments[key] }
            ?.localised()
            .orDash("")

    /** The postable chart leaves every code typeahead offers. */
    val codeLeaves: List<CoaAccount> get() = ChartOfAccounts.leaves(chart.accounts)

    /** Whether the chart has been read, so `wrapNominal` can tell a known code from a typed one. */
    val chartKnown: Boolean get() = chart.loaded
}

/** What a person nobody can name reads as. Never the id they were looked up by. */
const val UNKNOWN_PERSON = "—"
