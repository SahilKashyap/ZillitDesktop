package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay as DomainNonUnionPay
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup as DomainInvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings as DomainPayrollSettings
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.DayType as DomainDayType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubItem
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.Vendor

/**
 * Everything the console can be asked to do.
 *
 * Grouped by screen rather than flattened, because the hub is four screens
 * sharing a frame and a single flat list of forty cases stops reading as
 * anything.
 */
sealed interface AccountHubEvent {

    // -- shell --------------------------------------------------------------

    /** Open one of the hub's own screens. */
    data class Open(val area: HubArea) : AccountHubEvent

    /**
     * Follow a sidebar row that points at another film tool.
     *
     * Distinct from [Open] because it is not navigation within this window —
     * it hands off to a tool that owns its own routes and its own view of the
     * user's role.
     */
    data class OpenTool(val item: HubItem) : AccountHubEvent

    data object Refresh : AccountHubEvent

    data object ClearNotice : AccountHubEvent

    // -- production setup ---------------------------------------------------

    data class SwitchSetupTab(val tab: SetupTab) : AccountHubEvent

    data class EditCompanies(val companies: List<Company>) : AccountHubEvent

    data class EditCurrencies(val settings: CurrencySettings) : AccountHubEvent

    data class EditTaxTypes(val taxTypes: List<TaxType>) : AccountHubEvent

    data class EditAssetTags(val tags: List<String>) : AccountHubEvent

    data class EditBudget(val budget: BudgetForm) : AccountHubEvent

    data class EditSchedule(val schedule: ScheduleForm) : AccountHubEvent

    data class EditPayrollDefaults(val defaults: PayrollDefaults) : AccountHubEvent

    data class EditDealConditions(val conditions: List<DealCondition>) : AccountHubEvent

    data class EditPayrollBureaus(val bureaus: List<PayrollBureau>) : AccountHubEvent

    /** Both lists together: the slice is replaced whole on save. */
    data class EditAllowances(val value: AllowancesRentals) : AccountHubEvent

    data class EditPayrollSettings(val value: DomainPayrollSettings) : AccountHubEvent

    data class EditPoSetup(val value: PurchaseOrderSetup) : AccountHubEvent

    data class EditInvoicesSetup(val value: DomainInvoicesSetup) : AccountHubEvent

    data class EditNonUnionPay(val value: DomainNonUnionPay) : AccountHubEvent

    /** Opens a budget version's lines. Null clears the selection. */
    data class SelectBudgetVersion(val id: String?) : AccountHubEvent

    // -- importing a budget --------------------------------------------------

    data object OpenBudgetImport : AccountHubEvent

    data object CloseBudgetImport : AccountHubEvent

    /** Picks a budget file and parses it, writing nothing. */
    data object PickBudgetFile : AccountHubEvent

    data class EditBudgetImportMeta(val meta: BudgetImportMeta) : AccountHubEvent

    data class SetCoaImportMode(val mode: CoaImportMode) : AccountHubEvent

    /** Writes the reviewed parse: chart codes, the version, and its lines. */
    data object CommitBudgetImport : AccountHubEvent

    /** Changes the trial balance's filters without re-running it. */
    data class EditTrialBalanceQuery(val query: TrialBalanceQuery) : AccountHubEvent

    /** Runs the trial balance for whatever the filters now say. */
    data object RefreshTrialBalance : AccountHubEvent

    /** Proposes closing the period covering this instant. Asks before acting. */
    data class ProposePeriodClose(val asOfMillis: Long) : AccountHubEvent

    data object ConfirmPeriodClose : AccountHubEvent

    data object CancelPeriodClose : AccountHubEvent

    data class EditBibleQuery(val query: BibleQuery) : AccountHubEvent

    data object RefreshBibleReport : AccountHubEvent

    /** Folds one account's transactions away, or back. */
    data class ToggleBibleAccount(val code: String) : AccountHubEvent

    /** Picks and uploads the terms document issued with every order. */
    data object PickPoTerms : AccountHubEvent

    data object ClearPoTerms : AccountHubEvent

    /**
     * Opens the Timecard tool from its Production Setup tile.
     *
     * A hand-off, not a page: time-card configuration lives in that tool, and
     * the web's tile navigates there for the same reason.
     */
    data object OpenTimecardSetup : AccountHubEvent

    /** Opens a spend tool on its own settings page. */
    data class OpenSpendSetup(val which: SpendSetup) : AccountHubEvent

    // -- agreements and documents -------------------------------------------

    /** Opens the file dialog and queues whatever is chosen. */
    data object PickAgreementFiles : AccountHubEvent

    data class EditAgreementQueue(val queue: List<QueuedAgreementFile>) : AccountHubEvent

    /** Uploads every queued file, then appends them in one call. */
    data object UploadAgreementFiles : AccountHubEvent

    data class DeleteAgreementDocument(val id: String) : AccountHubEvent

    /** Commit one section. Sections save independently — see [SectionEdit]. */
    data class SaveSection(val section: SetupSection) : AccountHubEvent

    data class RevertSection(val section: SetupSection) : AccountHubEvent

    /** Open the company editor. A null [company] adds. */
    data class EditCompany(val company: Company?) : AccountHubEvent

    data class UpdateCompanyDraft(val company: Company) : AccountHubEvent

    data object CommitCompanyDraft : AccountHubEvent

    data object DismissCompanyDraft : AccountHubEvent

    data class RemoveCompany(val id: String) : AccountHubEvent

    /** Open the bank editor. A null [account] adds. */
    data class EditBank(val account: BankAccount?) : AccountHubEvent

    data class UpdateBankDraft(val account: BankAccount) : AccountHubEvent

    data object CommitBankDraft : AccountHubEvent

    data object DismissBankDraft : AccountHubEvent

    data class DeleteBank(val id: String) : AccountHubEvent

    // -- chart of accounts --------------------------------------------------

    data class SwitchChartView(val view: ChartView) : AccountHubEvent

    data class SearchChart(val term: String) : AccountHubEvent

    data object ToggleInactiveAccounts : AccountHubEvent

    data class ToggleAccountExpanded(val id: String) : AccountHubEvent

    /** Open the account form. [parent] pre-selects; [editing] switches to edit. */
    data class ComposeAccount(
        val parent: CoaAccount? = null,
        val editing: CoaAccount? = null,
    ) : AccountHubEvent

    data class SetAccountCode(val code: String) : AccountHubEvent

    data class SetAccountName(val name: String) : AccountHubEvent

    data class SetAccountLineType(val lineType: CoaLineType) : AccountHubEvent

    data class SetAccountCostType(val costType: CoaCostType) : AccountHubEvent

    data class SetAccountParent(val parentId: String?) : AccountHubEvent

    data object ToggleAccountPosting : AccountHubEvent

    data object ToggleAccountActive : AccountHubEvent

    data object SaveAccount : AccountHubEvent

    data object DismissAccountForm : AccountHubEvent

    data class DeactivateAccount(val id: String) : AccountHubEvent

    // -- vendors ------------------------------------------------------------

    data class SearchVendors(val term: String) : AccountHubEvent

    /** Switches the register's tab: all, verified or not verified. */
    data class FilterVendors(val filter: VendorFilter) : AccountHubEvent

    data class SelectVendor(val id: String?) : AccountHubEvent

    data class ComposeVendor(val editing: Vendor? = null) : AccountHubEvent

    data class UpdateVendorDraft(val draft: NewVendor) : AccountHubEvent

    data object SaveVendor : AccountHubEvent

    /**
     * Save the open edit, then verify it, as one act.
     *
     * The web offers this beside Save on an unverified vendor's form
     * (`VendorsModule.handleSaveAndVerify`), and it is the common case: an
     * accountant opens a vendor precisely to check its details before
     * verifying. Saving and then hunting for the row's verify button is the
     * same two calls with a worse path between them.
     */
    data object SaveAndVerifyVendor : AccountHubEvent

    data object DismissVendorForm : AccountHubEvent

    data class VerifyVendor(val id: String) : AccountHubEvent

    data class DeleteVendor(val id: String) : AccountHubEvent

    // -- approvals ----------------------------------------------------------

    data class SwitchApprovalModule(val module: ApprovalModule) : AccountHubEvent

    /** Open a chain for editing. A null [config] starts the production-wide one. */
    data class EditApprovalConfig(val config: ApprovalConfig?) : AccountHubEvent

    data class UpdateApprovalConfig(val config: ApprovalConfig) : AccountHubEvent

    data object AddApprovalLevel : AccountHubEvent

    data class RemoveApprovalLevel(val order: Int) : AccountHubEvent

    data object SaveApprovalConfig : AccountHubEvent

    data object DismissApprovalConfig : AccountHubEvent

    // -- forms configuration -------------------------------------------------

    /** Applies the pay breakdown to everybody, or to named departments. */
    data class ApplyPayToEveryone(val everyone: Boolean) : AccountHubEvent

    data class TogglePayDepartment(val departmentId: String, val on: Boolean) : AccountHubEvent

    /** The project's day-type catalogue, edited inside the pay breakdown. */
    data class EditDayTypes(val rows: List<DomainDayType>) : AccountHubEvent

    data class OpenFormModule(val module: FormModule) : AccountHubEvent

    /**
     * Opens Forms Configuration on one module, from another screen.
     *
     * Its own event rather than an Open followed by a module switch: the two
     * would each start a fetch, and the second would arrive over the first.
     */
    data class OpenFormConfig(val module: FormModule) : AccountHubEvent

    /** Enters or leaves edit mode. Leaving throws unsaved changes away. */
    data class EditForm(val editing: Boolean) : AccountHubEvent

    data class ToggleFormSection(val key: String) : AccountHubEvent

    data class NudgeFormSection(val key: String, val delta: Int) : AccountHubEvent

    data class ComposeFormSection(val afterKey: String?) : AccountHubEvent
    data class EditFormSectionName(val name: String) : AccountHubEvent
    data object DismissFormSection : AccountHubEvent
    data object AddFormSection : AccountHubEvent

    data class RenameFormSection(val key: String, val label: String) : AccountHubEvent
    data class EditFormSectionRename(val name: String) : AccountHubEvent
    data object DismissFormSectionRename : AccountHubEvent
    data object SaveFormSectionRename : AccountHubEvent

    data class AskRemoveFormSection(val section: FormSection) : AccountHubEvent
    data object DismissRemoveFormSection : AccountHubEvent
    data object ConfirmRemoveFormSection : AccountHubEvent

    /** Opens the inspector on a field, or on a new one when [fieldId] is null. */
    data class FocusFormField(val sectionKey: String, val fieldId: String?) : AccountHubEvent
    data object DismissFormField : AccountHubEvent

    data class EditNewFormField(val draft: NewFieldDraft) : AccountHubEvent
    data object AddFormField : AccountHubEvent

    /**
     * Takes a field off the form.
     *
     * One event for both kinds, because what it does differs by origin and
     * that difference is the design: a custom field is deleted, a system field
     * is hidden and comes back from the add-a-field panel.
     */
    data class RemoveFormField(val sectionKey: String, val fieldId: String) : AccountHubEvent

    data class RestoreFormField(val sectionKey: String, val fieldId: String) : AccountHubEvent

    data class NudgeFormField(
        val sectionKey: String,
        val fieldId: String,
        val delta: Int,
    ) : AccountHubEvent

    data class MoveFormFieldToSection(
        val sectionKey: String,
        val fieldId: String,
        val toKey: String,
    ) : AccountHubEvent

    data class SetFormFieldName(val sectionKey: String, val fieldId: String, val name: String) :
        AccountHubEvent

    data class SetFormFieldType(val sectionKey: String, val fieldId: String, val type: String) :
        AccountHubEvent

    data class SetFormFieldRequired(
        val sectionKey: String,
        val fieldId: String,
        val required: Boolean,
    ) : AccountHubEvent

    data class SetFormFieldSelection(
        val sectionKey: String,
        val fieldId: String,
        val selectionType: String?,
    ) : AccountHubEvent

    data object SaveFormTemplate : AccountHubEvent

    data object AskResetFormTemplate : AccountHubEvent
    data object DismissResetFormTemplate : AccountHubEvent
    data object ConfirmResetFormTemplate : AccountHubEvent
}

/** Which Production Setup section a save or revert applies to. */
enum class SetupSection(val label: String) {
    Companies("Companies"),
    Currencies("Project Currencies"),
    TaxTypes("Tax Types"),
    AssetTags("Asset Tags"),
    Budget("Project Budget"),
    Schedule("Production Schedule"),
    PayrollDefaults("Payroll Defaults"),
    DealConditions("Standard Deal Conditions"),
    PayrollBureaus("Payroll Bureau"),
    Allowances("Allowances & Rentals"),
    PayrollSettings("Payroll Settings"),
    PoSetup("Purchase Order Setup"),
    InvoicesSetup("Invoices Setup"),
    NonUnionPay("Non-Union Pay Breakdown"),
    DayTypes("Day Types"),
}

/** One-shot things the console asks the host to do. */
sealed interface AccountHubEffect {

    data class Failed(val message: String) : AccountHubEffect

    /** Hand off to another film tool, by its workspace path. */
    data class OpenTool(val path: String, val title: String) : AccountHubEffect
}

/**
 * The two spend tools whose setup Production Setup points at.
 *
 * [route] is a deep link: the workspace resolves a tool by longest path
 * prefix, so the tail lands the tool on that page rather than its own default.
 */
enum class SpendSetup(val route: String, val title: String) {
    Cards("/film-tools/card-expenses/settings", "Card Expenses"),
    PettyCash("/film-tools/cash-expenses/settings", "Petty Cash"),
}
