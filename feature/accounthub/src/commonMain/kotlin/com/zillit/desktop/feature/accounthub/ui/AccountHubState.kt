package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetUpload
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.CashCloseDashboard
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
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
import com.zillit.desktop.feature.accounthub.domain.ImportedRules
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.accounthub.domain.IsdCountry
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleTemplate
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
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
import com.zillit.desktop.feature.accounthub.domain.UnionAgreementSummary
import com.zillit.desktop.feature.accounthub.domain.UnionTerritories
import com.zillit.desktop.feature.accounthub.domain.UnionTerritory
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorBank
import com.zillit.desktop.feature.accounthub.domain.VendorChange
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
enum class SetupTab(val slug: String, private val labelKey: String) {
    Accounting("acct", S.desktop_accounting_setup),
    DealMemo("deal", S.dm_setup_title),
    ;

    val label: String get() = str(labelKey)

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

    /** Neither date half-typed — blank or whole. */
    val isComplete: Boolean get() = IsoDate.isBlankOrValid(from) && IsoDate.isBlankOrValid(to)

    companion object {
        /** Read in UTC, as they are written — see [IsoDate.fromEpochMillis]. */
        fun from(phase: SchedulePhase) = DateRangeText(
            from = IsoDate.fromEpochMillis(phase.startDate),
            to = IsoDate.fromEpochMillis(phase.endDate),
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

    /**
     * Whether any date is half-typed. `toDomain` reads such a date as unset, so
     * saving it would clear a stored date the person was in the middle of
     * changing.
     */
    val hasHalfTypedDate: Boolean
        get() = listOf(overall, prep, shoot, wrap).any { !it.isComplete } ||
            customDays.any { !it.dates.isComplete }

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
enum class SetupModal(
    val slug: String,
    private val titleKey: String,
    private val eyebrowKey: String,
    private val descriptionKey: String,
) {
    PurchaseOrders(
        "po_setup",
        S.desktop_hub_purchase_order_entry_setup,
        S.desktop_pos,
        S.desktop_hub_defaults_for_the_po_module_description_formatting_rental_split_handling,
    ),
    Invoices(
        "invoices_setup",
        S.desktop_invoices_entry_setup,
        S.ah_invoices,
        S.desktop_hub_ap_controls_who_can_post_invoices_and_at_what_limit,
    ),
    Payroll(
        "payroll_settings",
        S.desktop_payroll_entry_setup,
        S.dm_step9_title,
        S.desktop_hub_approvers_and_the_projects_pay_cycle_window_drives_the_approval,
    ),
    ;

    val title: String get() = str(titleKey)
    val eyebrow: String get() = str(eyebrowKey)
    val description: String get() = str(descriptionKey)

    companion object {
        /**
         * A deep link's `?setup=` value: the slug, or the short name
         * (`payroll`, `po`, `invoices`). Null for anything else.
         */
        fun fromRoute(value: String?): SetupModal? = when (value?.trim()?.lowercase()) {
            null, "" -> null
            "payroll", Payroll.slug -> Payroll
            "po", "purchase_orders", PurchaseOrders.slug -> PurchaseOrders
            "invoices", Invoices.slug -> Invoices
            else -> null
        }
    }
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
    /** The seeded rows as they opened, by chart id — an unchanged one is not sent. */
    val seeds: Map<String, PayrollAccountRow> = emptyMap(),
    /**
     * Saved codes the chart could not resolve. Listed, never seeded: without a
     * chart id the row would go as a create for an account that exists.
     */
    val unmatched: List<String> = emptyList(),
)

/** A pay rule being added or edited — the web's `RateRowModal`. */
data class PayRuleEditor(
    val kind: PayRuleKind,
    val index: Int?,
    val rule: PayRule,
    /**
     * The condition's one field as typed — hours or `HH:MM` — held beside the
     * trigger it builds. Re-deriving the field from stored minutes on every
     * keystroke turned "5." into "5" and "06:0" into "00:00".
     */
    val conditionText: String = "",
) {
    /** The template the dialog shows: the rule's own, or its list's default. */
    val template: PayRuleTemplate
        get() = PayRuleTemplate.of(rule.singleTrigger) ?: PayRuleTemplate.defaultFor(kind)

    /** Whether the condition is one the dialog's field edits — a single trigger a template recognises. */
    val editsCondition: Boolean get() = PayRuleTemplate.of(rule.singleTrigger) != null

    /** The text re-read from the trigger, but only once it no longer describes it (a type switch, a revert). */
    fun synced(): PayRuleEditor {
        val trigger = rule.singleTrigger ?: return this
        val shown = template
        if (shown.textDescribes(conditionText, trigger)) return this
        return copy(conditionText = shown.conditionText(trigger))
    }

    /** The field typed into: the text kept as typed, the trigger rebuilt from it. */
    fun withConditionText(text: String): PayRuleEditor {
        val trigger = rule.singleTrigger ?: PayTrigger()
        val next = template.conditionFrom(text, trigger.dayKinds, carrying = trigger)
        return copy(conditionText = text, rule = rule.copy(triggers = listOf(next)))
    }

    companion object {
        /** Opens on [rule], its field filled from the stored condition. */
        fun open(kind: PayRuleKind, index: Int?, rule: PayRule): PayRuleEditor {
            val editor = PayRuleEditor(kind, index, rule)
            val trigger = rule.singleTrigger ?: return editor
            return editor.copy(conditionText = editor.template.conditionText(trigger))
        }
    }
}

/** A row about to be removed, named so the confirmation can say which. */
sealed interface SetupRemoval {
    data class CompanyRow(val company: Company) : SetupRemoval
    data class BankRow(val bank: BankAccount) : SetupRemoval
    data class AgreementRow(val document: AgreementDocument) : SetupRemoval
    data class PayrollGroupRow(val group: PayrollGroup) : SetupRemoval
    data class PayrollAccountCode(val code: String, val accountId: String?) : SetupRemoval
}

/** The currency picker's two filter chips. */
enum class CurrencyFilter(private val labelKey: String) {
    All(S.all),
    Major(S.desktop_major),
    ;

    val label: String get() = str(labelKey)
}

/**
 * Which Production Setup slices have been read, and which could not be.
 *
 * Per section, because each reads its own route and any one can fail alone.
 * The distinction is a data-safety rule, not a nicety: a section that never
 * loaded holds the *empty default*, and a save from it writes that default
 * over what the server has — the companies PATCH replaces the whole list, so
 * adding one company to a list that failed to load deleted all the others.
 * Such a section shows an error card with Retry instead of an editor, and
 * refuses to save (the web shows its error panel instead of the page).
 */
data class SliceLoads(
    val loaded: Set<SetupSection> = emptySet(),
    /** Asked for and not yet answered, never having loaded — drawn as a placeholder, not an editor. */
    val pending: Set<SetupSection> = emptySet(),
    val failed: Map<SetupSection, String> = emptyMap(),
) {
    fun isLoaded(section: SetupSection): Boolean = section in loaded

    /** Why the section could not be read, when it never has been. */
    fun failure(section: SetupSection): String? = failed[section]

    fun isLoading(section: SetupSection): Boolean = section in pending

    /** A read begins. One over a slice that already loaded is a refresh, and changes nothing on screen. */
    fun requested(section: SetupSection): SliceLoads =
        if (section in loaded) this else copy(pending = pending + section, failed = failed - section)

    fun succeeded(section: SetupSection): SliceLoads =
        copy(loaded = loaded + section, pending = pending - section, failed = failed - section)

    /**
     * A failed read. One that follows a good read changes nothing: the data on
     * screen is the last the server gave, and blocking it over a refresh that
     * did not land would take away a working editor.
     */
    fun failedWith(section: SetupSection, message: String): SliceLoads =
        if (section in loaded) this else copy(pending = pending - section, failed = failed + (section to message))

    companion object {
        /** Every section the page reads on open. The project budget has no surface here. */
        val TRACKED: Set<SetupSection> = SetupSection.entries.toSet() - SetupSection.Budget
    }
}

/** Everything Production Setup holds. */
data class SetupState(
    val tab: SetupTab = SetupTab.Accounting,
    val loading: Boolean = false,
    /** True once every slice has been asked for — the tour's "unknown ≠ missing" gate. */
    val loaded: Boolean = false,
    /** Per-section read state — see [SliceLoads]. */
    val slices: SliceLoads = SliceLoads(),
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
    /** Why the bank list could not be read, while it never has been. */
    val banksError: String? = null,
    val currencyCatalogue: List<ProjectCurrency> = emptyList(),
    val countryTaxes: List<CountryTaxes> = emptyList(),
    /** Why the currency catalogue could not be read — shown with a Retry, never as endless "Loading…". */
    val currencyCatalogueError: String? = null,
    /** Why the countries' tax catalogue could not be read. */
    val countryTaxesError: String? = null,
    val companyDraft: Company? = null,
    /**
     * The company editor was opened from inside the bank editor's "+ Add
     * company": it then hides its own bank block (no bank → company → bank
     * nesting) and, once saved, becomes that bank's holder.
     */
    val companyDraftFromBank: Boolean = false,
    /** Bumped on every open, so the dialog's own scratch state (the legal-name tick) starts fresh each time. */
    val companyDraftSession: Int = 0,
    val bankDraft: BankAccount? = null,
    /**
     * The bank editor was opened from inside the company editor's "Add bank
     * account": the holder is that company when it already exists, and a
     * newly created bank is linked onto the draft when the save lands.
     */
    val bankDraftFromCompany: Boolean = false,
    val bankSaving: Boolean = false,
    /** A bank delete in flight — the confirm shows it and takes no second press (one DELETE, not two). */
    val bankDeleting: Boolean = false,
    /**
     * The company editor's tax-credit input, as typed and not yet committed.
     * Held here so Done folds it in — the web commits it on blur; a click on
     * Done here never blurred it, and the typed regime was lost.
     */
    val taxCreditDraft: String = "",
    /** Which bank card has been revealed; the card re-masks itself after five seconds. */
    val revealedBankId: String? = null,
    // -- the drill-down modals --
    val modal: SetupModalState? = null,
    val poRules: SectionEdit<List<AssignmentRule>> = SectionEdit(emptyList()),
    val invoiceRules: SectionEdit<List<AssignmentRule>> = SectionEdit(emptyList()),
    val rulesLoading: Boolean = false,
    /**
     * The vendors the assignment rules' multi-select offers, read when a
     * modal opens (the web's `AssignmentRulesSection` loads its own) rather
     * than borrowed from a Vendors page that may never have been opened.
     */
    val ruleVendors: List<Vendor> = emptyList(),
    val invoiceMemberDraft: InvoiceMemberDraft? = null,
    val payrollGroups: List<PayrollGroup> = emptyList(),
    val payrollGroupsLoading: Boolean = false,
    val payrollGroupDraft: PayrollGroup? = null,
    val payrollGroupSaving: Boolean = false,
    val payrollAccounts: PayrollAccountsDraft? = null,
    val userPicker: UserPickerState? = null,
    // -- section-local UI --
    val currencyFilter: CurrencyFilter = CurrencyFilter.All,
    /**
     * Countries picked in the tax editor, kept while none of their rates is
     * ticked — the web holds them apart from the rates (`TaxTypesSection`), so
     * unticking a country's last rate no longer removes the country.
     */
    val taxCountries: Set<String> = emptySet(),
    val currencySearch: String = "",
    val tagDraft: String = "",
    val ruleEditor: PayRuleEditor? = null,
    /** The "Import union rules" dialog, while open. */
    val ruleImport: RuleImportState? = null,
    val departmentPickerOpen: Boolean = false,
    val departmentPickerSearch: String = "",
    val removal: SetupRemoval? = null,
) {
    /** Sections with unsaved edits, so the shell can warn before leaving. */
    val dirtySections: List<String>
        get() = buildList {
            if (companies.dirty) add(str(S.desktop_companies))
            if (currencies.dirty) add(str(S.desktop_project_currencies))
            if (taxTypes.dirty) add(str(S.desktop_tax_types))
            if (assetTags.dirty) add(str(S.desktop_account_tags))
            if (schedule.dirty) add(str(S.desktop_production_schedule))
            if (payrollDefaults.dirty) add(str(S.desktop_payroll_defaults))
            if (dealConditions.dirty) add(str(S.desktop_standard_deal_conditions))
            if (payrollBureaus.dirty) add(str(S.desktop_payroll_bureau))
            if (allowances.dirty) add(str(S.dm_allow_title))
            if (payrollSettings.dirty) add(str(S.desktop_payroll_settings))
            if (poSetup.dirty) add(str(S.desktop_purchase_order_setup))
            if (invoicesSetup.dirty) add(str(S.desktop_invoices_setup))
            if (nonUnionPay.dirty) add(str(S.desktop_hub_non_union_pay_breakdown))
            if (dayTypes.dirty) add(str(S.desktop_day_types))
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

    /**
     * The catalogue tiles the currency picker shows, filtered and searched.
     *
     * Chosen currencies stay in the grid and read as selected, as on the web:
     * a tile is a toggle, and a list that hides what was picked cannot show
     * where a currency went.
     */
    val currencyChoices: List<ProjectCurrency>
        get() {
            val needle = currencySearch.trim()
            return currencyCatalogue
                .filter { currencyFilter == CurrencyFilter.All || it.code in CurrencySettings.MAJOR_CODES }
                .filter {
                    needle.isEmpty() || it.code.contains(needle, true) || it.name.contains(needle, true) ||
                        it.country.contains(needle, true) || it.symbol.contains(needle, true)
                }
        }

    /** A chosen currency as the catalogue describes it — a stored row carries no country. */
    fun currencyMeta(currency: ProjectCurrency): ProjectCurrency =
        currencyCatalogue.firstOrNull { it.code == currency.code }?.let { meta ->
            currency.copy(
                name = currency.name.ifBlank { meta.name },
                symbol = currency.symbol.ifBlank { meta.symbol },
                country = currency.country.ifBlank { meta.country },
            )
        } ?: currency

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

/**
 * The "Import union rules" dialog — the web's `ImportAgreementRulesModal`.
 *
 * Pick a territory, its agreements load; pick an agreement, its rule tables
 * are projected and previewed; Import appends the lot to the breakdown and
 * saves. Read-only preview, all rules import — no per-row selection.
 */
data class RuleImportState(
    /** Null until the registry answers; the whole catalogue is offered meanwhile (fail open). */
    val covered: Set<String>? = null,
    val territory: String? = null,
    val agreements: List<UnionAgreementSummary> = emptyList(),
    val agreementsLoading: Boolean = false,
    val agreementId: String? = null,
    val rules: ImportedRules? = null,
    val rulesLoading: Boolean = false,
    /** The breakdown is being saved with the imported rules appended. */
    val importing: Boolean = false,
) {
    val territories: List<UnionTerritory> get() = UnionTerritories.offered(covered, keep = territory)

    val agreement: UnionAgreementSummary? get() = agreements.firstOrNull { it.identifier == agreementId }

    val total: Int get() = rules?.total ?: 0
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
enum class VendorFilter(val slug: String, private val labelKey: String) {
    All("all", S.desktop_all_vendors),
    Verified("verified", S.ah_verified),
    Unverified("unverified", S.ah_non_verified),
    Mine("mine", S.ah_added_by_me),
    ;

    val label: String get() = str(labelKey)
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
    val title: String get() = if (editingId == null) str(S.desktop_new_vendor) else str(S.desktop_editing)

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
    val title: String get() = if (editingId == null) str(S.desktop_new_vendor) else str(S.ah_edit_vendor_title)
}

data class VendorsState(
    val loading: Boolean = false,
    val rows: List<Vendor> = emptyList(),
    val search: String = "",
    val filter: VendorFilter = VendorFilter.All,
    val selectedId: String? = null,
    val history: List<VendorChange> = emptyList(),
    val historyLoading: Boolean = false,
    /** Why the history panel is empty, when it is because the read failed — the web prints it there. */
    val historyError: String? = null,
    /** Vendors whose delete is in flight: greyed, their actions a spinner, a second delete refused. */
    val deletingIds: Set<String> = emptySet(),
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
enum class ImportStep(private val labelKey: String) {
    Upload(S.upload),
    Preview(S.preview),
    Done(S.desktop_commit),
    ;

    val label: String get() = str(labelKey)
}

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
    /** Whether the host takes a file dragged onto the upload step. */
    val acceptsDrops: Boolean = false,
    /** The file chosen or dropped, not yet uploaded — the web's staged `file`. */
    val picked: PickedAgreementFile? = null,
    val uploading: Boolean = false,
    /** Why the upload or the parse failed, shown on the upload step with a Retry. */
    val parseError: String? = null,
    val committing: Boolean = false,
    val parsed: ParsedBudget? = null,
    val upload: BudgetUpload? = null,
    val meta: BudgetImportMeta = BudgetImportMeta(),
    val mode: CoaImportMode = CoaImportMode.Default,
    /**
     * Every version the production has, read when the wizard opens rather than
     * taken from the page: the suggested version is only safe if it saw every
     * one, including a budget somebody else imported since the page loaded.
     * Null until that read answers.
     */
    val existing: List<BudgetVersion>? = null,
    /** The version the commit created, for the last step to name. */
    val created: BudgetVersion? = null,
    val commitError: String? = null,
) {
    val canParse: Boolean get() = picked != null && !uploading

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
    /** Groups of lines opened on screen; the top level starts open, as on the web. */
    val openGroups: Set<String> = emptySet(),
    val import: BudgetImportState = BudgetImportState(),
    val openingFile: Boolean = false,
) {
    val selected: BudgetVersion? get() = versions.firstOrNull { it.id == selectedId }

    /** The Live version, of which there is at most one. */
    val live: BudgetVersion? get() = versions.firstOrNull { it.status == BudgetStatus.Live }
}

// -- reports --------------------------------------------------------------------
//
// The trial balance's state is TrialBalanceState.kt's, and the bible's its own file's.

/** The three tabs of the Period Close module. */
enum class PeriodCloseTab(val slug: String, private val labelKey: String) {
    Close("close", S.desktop_period_close),
    CashClose("cash-close", S.desktop_cash_close),
    Publish("publish", S.desktop_publish_package),
    ;

    val label: String get() = str(labelKey)
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
enum class DepartmentFilter(private val labelKey: String) {
    All(S.all),
    Custom(S.custom),
    Default(S.desktop_email_format_default),
    ;

    val label: String get() = str(labelKey)
}

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
    /** The page that opened the builder, and so the one that shows it. */
    val origin: BuilderOrigin = BuilderOrigin.Approvers,
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
            // The row shows `name.localised()` — the server sends keys such as
            // `direction_label` — so the search has to match what is on screen.
            .filter {
                needle.isEmpty() ||
                    it.name.localised().contains(needle, ignoreCase = true) ||
                    it.name.contains(needle, ignoreCase = true)
            }
            .filter { dept ->
                // Any saved row is an override, as on the web (`!!configs[id]`) —
                // one with an empty chain too: that department waits forever at a
                // level with nobody in it, and filing it under "Default" hid that.
                val custom = configFor(dept.id) != null
                when (departmentFilter) {
                    DepartmentFilter.All -> true
                    DepartmentFilter.Custom -> custom
                    DepartmentFilter.Default -> !custom
                }
            }
    }

    fun customCount(departments: List<HubDepartment>): Int =
        departments.count { configFor(it.id) != null }
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
