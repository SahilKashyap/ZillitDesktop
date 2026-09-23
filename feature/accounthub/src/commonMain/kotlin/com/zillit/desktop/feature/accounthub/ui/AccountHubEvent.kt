package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BibleFilters
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.ChartSortKey
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.ClosingReport
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.DayType as DomainDayType
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubItem
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup as DomainInvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay as DomainNonUnionPay
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings as DomainPayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals

/**
 * Everything the console can be asked to do.
 *
 * Grouped by screen rather than flattened, because the hub is nine screens
 * sharing a frame and a single flat list of two hundred cases stops reading as
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

    /** The header card's back arrow — the web's `/film-tools`. */
    data object Back : AccountHubEvent

    /**
     * A report page's back arrow and its "Reports" crumb — the web's
     * `/film-tools/account-hub`, which is the console's own landing, not the
     * tools grid [Back] leaves for.
     */
    data object BackToHub : AccountHubEvent

    /** An embedded tool moved within itself; the shell follows so the sidebar and title agree. */
    data class EmbedRoute(val path: String) : AccountHubEvent

    /** An embedded tool asked to close; the console shows its own area again. */
    data object CloseEmbedded : AccountHubEvent

    data object Refresh : AccountHubEvent

    data object ClearNotice : AccountHubEvent

    /** The setup tour: the intro's "Next →", a step's Next/Back, and any exit. */
    data object TourNext : AccountHubEvent
    data object TourBack : AccountHubEvent
    data object TourClose : AccountHubEvent

    // -- production setup ---------------------------------------------------

    data class SwitchSetupTab(val tab: SetupTab) : AccountHubEvent

    data class EditCompanies(val companies: List<Company>) : AccountHubEvent

    data class EditCurrencies(val settings: CurrencySettings) : AccountHubEvent

    data class SetCurrencyFilter(val filter: CurrencyFilter) : AccountHubEvent
    data class SearchCurrencies(val term: String) : AccountHubEvent

    data class EditTaxTypes(val taxTypes: List<TaxType>) : AccountHubEvent

    data class EditAssetTags(val tags: List<String>) : AccountHubEvent

    data class EditTagDraft(val text: String) : AccountHubEvent

    /** Commits the tag draft — split on commas, upper-cased, deduplicated. */
    data object CommitTagDraft : AccountHubEvent

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

    /** Opens the version's source file in the OS. */
    data object OpenBudgetFile : AccountHubEvent

    /** Opens or closes a group of budget lines — the chevron on a row that has children. */
    data class ToggleBudgetGroup(val id: String) : AccountHubEvent

    // -- importing a budget --------------------------------------------------

    data object OpenBudgetImport : AccountHubEvent

    data object CloseBudgetImport : AccountHubEvent

    /** Chooses a budget file in the picker. Nothing is uploaded until [ParseBudgetFile]. */
    data object PickBudgetFile : AccountHubEvent

    /**
     * A budget file dragged onto the upload step — the web's drop zone.
     *
     * Not a data class: a byte array compares by identity, so generated
     * equality would be wrong rather than merely slow.
     */
    class DropBudgetFile(val name: String, val bytes: ByteArray) : AccountHubEvent

    /** Uploads the chosen file and asks the server what it makes of it, writing nothing. */
    data object ParseBudgetFile : AccountHubEvent

    /** From the preview back to the upload step, keeping the chosen file. */
    data object BackToBudgetUpload : AccountHubEvent

    data class EditBudgetImportMeta(val meta: BudgetImportMeta) : AccountHubEvent

    data class SetCoaImportMode(val mode: CoaImportMode) : AccountHubEvent

    /** Writes the reviewed parse: chart codes, the version, and its lines. */
    data object CommitBudgetImport : AccountHubEvent

    // -- reports --------------------------------------------------------------

    // The trial balance's filters change the bar, never the rows: nothing
    // runs until [RefreshTrialBalance].

    data class SetTrialBalancePeriodMode(val mode: PeriodMode) : AccountHubEvent

    /** The Date Range pickers, as typed. */
    data class EditTrialBalanceDates(val from: String, val to: String) : AccountHubEvent

    /** The account range's two codes, as typed. */
    data class EditTrialBalanceAccounts(val from: String, val to: String) : AccountHubEvent

    /** One legal entity, or blank for every company. */
    data class PickTrialBalanceCompany(val companyId: String) : AccountHubEvent

    data class PickTrialBalanceCurrency(val code: String) : AccountHubEvent

    data class SetTrialBalanceZeroAccounts(val include: Boolean) : AccountHubEvent

    /** Runs the trial balance for whatever the filters now say. */
    data object RefreshTrialBalance : AccountHubEvent

    data class ToggleTrialBalanceExport(val open: Boolean) : AccountHubEvent

    data class ExportTrialBalance(val format: ExportFormat) : AccountHubEvent

    /** Any change to the bible's filter bar, as typed. Nothing runs until [RunBibleReport]. */
    data class EditBibleFilters(val filters: BibleFilters) : AccountHubEvent

    /** Runs the bible for whatever the filter bar now says, with "today" read at the press. */
    data object RunBibleReport : AccountHubEvent

    /** Folds one account's transactions away, or back. */
    data class ToggleBibleAccount(val code: String) : AccountHubEvent

    /** Folds every account away, or opens them all. */
    data class SetAllBibleAccounts(val collapsed: Boolean) : AccountHubEvent

    data class ToggleBibleExport(val open: Boolean) : AccountHubEvent

    data class ExportBible(val format: ExportFormat) : AccountHubEvent

    // -- period close ---------------------------------------------------------

    data class SwitchPeriodCloseTab(val tab: PeriodCloseTab) : AccountHubEvent

    data class EditCloseDate(val text: String) : AccountHubEvent

    /** Proposes closing the period through the picked date. Asks before acting. */
    data class ProposePeriodClose(val asOfMillis: Long) : AccountHubEvent

    data object ConfirmPeriodClose : AccountHubEvent

    data object CancelPeriodClose : AccountHubEvent

    data class ToggleChecklistItem(val label: String) : AccountHubEvent

    data object ResetChecklist : AccountHubEvent

    data class EditPackages(val packages: List<ClosingPackage>) : AccountHubEvent

    data object AddPackage : AccountHubEvent

    data class RemovePackage(val id: Int) : AccountHubEvent

    data class TogglePackageRecipient(val packageId: Int, val userId: String) : AccountHubEvent

    data class AddPackageEmail(val packageId: Int) : AccountHubEvent

    data class TogglePackageReport(val packageId: Int, val report: ClosingReport) : AccountHubEvent

    data class TogglePackageAllReports(val packageId: Int) : AccountHubEvent

    data class OpenPackageMenu(val packageId: Int?) : AccountHubEvent

    data class SearchPackageMenu(val term: String) : AccountHubEvent

    data object PublishPackages : AccountHubEvent

    // -- purchase order terms, tiles and modals --------------------------------

    /** Picks and uploads the terms document issued with every order. */
    data object PickPoTerms : AccountHubEvent

    /** Opens the terms document in the OS — the web's View. There is no remove; a document is only replaced. */
    data object OpenPoTerms : AccountHubEvent

    /**
     * Opens the Timecard tool from its Production Setup tile.
     *
     * A hand-off, not a page: time-card configuration lives in that tool, and
     * the web's tile navigates there for the same reason.
     */
    data object OpenTimecardSetup : AccountHubEvent

    /** Opens a spend tool on its own settings page. */
    data class OpenSpendSetup(val which: SpendSetup) : AccountHubEvent

    /** Opens one of the three drill-down modals, on its first section. */
    data class OpenSetupModal(val modal: SetupModal) : AccountHubEvent

    data object CloseSetupModal : AccountHubEvent

    data class SwitchModalSection(val section: String) : AccountHubEvent

    /** Saves whatever the open modal holds — settings and rules together. */
    data object SaveSetupModal : AccountHubEvent

    data class EditPoRules(val rules: List<AssignmentRule>) : AccountHubEvent

    data class EditInvoiceRules(val rules: List<AssignmentRule>) : AccountHubEvent

    /** The invoices team-member dialog: open on a row (null adds), edit, commit, dismiss. */
    data class ComposeInvoiceMember(val index: Int?) : AccountHubEvent
    data class EditInvoiceMember(val member: InvoiceTeamMember) : AccountHubEvent
    data object CommitInvoiceMember : AccountHubEvent
    data object DismissInvoiceMember : AccountHubEvent

    /** The shared user picker. */
    data class OpenUserPicker(val purpose: UserPickerPurpose, val index: Int = -1) : AccountHubEvent
    data class SearchUserPicker(val term: String) : AccountHubEvent
    data class ToggleUserPick(val userId: String) : AccountHubEvent
    data object ApplyUserPicker : AccountHubEvent
    data object DismissUserPicker : AccountHubEvent

    // -- payroll groups and accounts ---------------------------------------------

    data class ComposePayrollGroup(val group: PayrollGroup?) : AccountHubEvent
    data class EditPayrollGroup(val group: PayrollGroup) : AccountHubEvent
    data object SavePayrollGroup : AccountHubEvent
    data object DismissPayrollGroup : AccountHubEvent

    /** Opens the payroll-accounts grid seeded from the saved codes. */
    data object OpenPayrollAccounts : AccountHubEvent
    data class EditPayrollAccounts(val rows: List<PayrollAccountRow>) : AccountHubEvent
    data object SavePayrollAccounts : AccountHubEvent
    data object DismissPayrollAccounts : AccountHubEvent

    // -- removals, confirmed -------------------------------------------------------

    data class AskRemove(val removal: SetupRemoval) : AccountHubEvent
    data object DismissRemove : AccountHubEvent
    data object ConfirmRemove : AccountHubEvent

    // -- agreements and documents -------------------------------------------

    /** Opens the file dialog and queues whatever is chosen. */
    data object PickAgreementFiles : AccountHubEvent

    data class EditAgreementQueue(val queue: List<QueuedAgreementFile>) : AccountHubEvent

    /** Uploads every queued file, then appends them in one call. */
    data object UploadAgreementFiles : AccountHubEvent

    data class DeleteAgreementDocument(val id: String) : AccountHubEvent

    /** Opens a stored document in the OS, by presigned URL. */
    data class OpenAgreementDocument(val id: String) : AccountHubEvent

    /** Commit one section. Sections save independently — see [SectionEdit]. */
    data class SaveSection(val section: SetupSection) : AccountHubEvent

    data class RevertSection(val section: SetupSection) : AccountHubEvent

    /**
     * Open the company editor. A null [company] adds; [fromBank] is the bank
     * editor's own "+ Add company", which hides the bank block and makes the
     * saved company that bank's holder.
     */
    data class EditCompany(val company: Company?, val fromBank: Boolean = false) : AccountHubEvent

    data class UpdateCompanyDraft(val company: Company) : AccountHubEvent

    /** Persists the draft straight away, as the web's Done does; the dialog stays open on failure. */
    data object CommitCompanyDraft : AccountHubEvent

    data object DismissCompanyDraft : AccountHubEvent

    /** Persists the list without the company straight away. */
    data class RemoveCompany(val id: String) : AccountHubEvent

    /**
     * Open the bank editor. A null [account] adds; [fromCompany] is the
     * company editor's own "Add bank account", stacked over it.
     */
    data class EditBank(val account: BankAccount?, val fromCompany: Boolean = false) : AccountHubEvent

    data class UpdateBankDraft(val account: BankAccount) : AccountHubEvent

    data object CommitBankDraft : AccountHubEvent

    data object DismissBankDraft : AccountHubEvent

    data class DeleteBank(val id: String) : AccountHubEvent

    /** Unmasks one bank card's numbers; the card re-masks itself after five seconds. */
    data class RevealBank(val id: String?) : AccountHubEvent

    // -- non-union pay --------------------------------------------------------------

    /** Opens the "Import union rules" dialog. */
    data object OpenRuleImport : AccountHubEvent

    data object DismissRuleImport : AccountHubEvent

    data class PickImportTerritory(val territory: String) : AccountHubEvent

    data class PickImportAgreement(val identifier: String) : AccountHubEvent

    /** Appends the previewed rules to the breakdown and saves it. */
    data object ConfirmRuleImport : AccountHubEvent

    /** Applies the pay breakdown to everybody, or to named departments. */
    data class ApplyPayToEveryone(val everyone: Boolean) : AccountHubEvent

    data class TogglePayDepartment(val departmentId: String, val on: Boolean) : AccountHubEvent

    data class ToggleDepartmentPicker(val open: Boolean) : AccountHubEvent

    data class SearchDepartmentPicker(val term: String) : AccountHubEvent

    /** Opens the rule editor on a rule (null adds one of [kind]). */
    data class ComposePayRule(val kind: PayRuleKind, val index: Int?) : AccountHubEvent
    data class EditPayRule(val rule: PayRule) : AccountHubEvent
    data object CommitPayRule : AccountHubEvent
    data object DismissPayRule : AccountHubEvent
    data class RemovePayRule(val kind: PayRuleKind, val index: Int) : AccountHubEvent

    /** The project's day-type catalogue, edited inside the pay breakdown. */
    data class EditDayTypes(val rows: List<DomainDayType>) : AccountHubEvent

    // -- chart of accounts --------------------------------------------------

    data class SwitchChartView(val view: ChartView) : AccountHubEvent

    data class SearchChart(val term: String) : AccountHubEvent

    data object ToggleInactiveAccounts : AccountHubEvent

    data class SetChartMode(val mode: ChartMode) : AccountHubEvent

    data class SortChart(val key: ChartSortKey) : AccountHubEvent

    /** Opens or closes one tree row; [depth] decides whether it started open. */
    data class ToggleAccountExpanded(val id: String, val depth: Int = 0) : AccountHubEvent

    /** Expand all when collapsed, collapse all when expanded. */
    data object ToggleExpandAll : AccountHubEvent

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

    /** Asks before deactivating — "Deactivate code" — then does it on confirm. */
    data class AskDeactivateAccount(val account: CoaAccount) : AccountHubEvent
    data object DismissDeactivateAccount : AccountHubEvent
    data object ConfirmDeactivateAccount : AccountHubEvent

    data class DeactivateAccount(val id: String) : AccountHubEvent

    /** The table's inline cost-type select. */
    data class SetAccountCostTypeInline(val id: String, val costType: CoaCostType) : AccountHubEvent

    /** Creates a category code from a typeahead — the accountant's quick create. */
    data class QuickCreateCode(val code: String, val name: String, val costType: CoaCostType) : AccountHubEvent

    // -- bulk add ("New COA Entry") ---------------------------------------------

    data class OpenBulkAdd(val parent: CoaAccount? = null) : AccountHubEvent
    data class EditBulkRow(val row: CoaBulkRow) : AccountHubEvent
    /** [focus] hands the caret to the new row's code — Tab off the last row, not the button. */
    data class AddBulkRows(val count: Int, val focus: Boolean = false) : AccountHubEvent
    data class RemoveBulkRow(val localId: String) : AccountHubEvent
    /** Done: flushes anything still mid-debounce, then closes and reloads. */
    data object FinishBulkAdd : AccountHubEvent

    // -- layers ---------------------------------------------------------------------

    data class ToggleLayerOpen(val setId: String) : AccountHubEvent
    data class ComposeLayerSet(val set: TrackingSet?) : AccountHubEvent
    data class EditLayerSet(val set: TrackingSet) : AccountHubEvent
    data object SaveLayerSet : AccountHubEvent
    data object DismissLayerSet : AccountHubEvent
    data class ComposeLayerNode(val setId: String, val node: TrackingNode?) : AccountHubEvent
    data class EditLayerNode(val node: TrackingNode) : AccountHubEvent
    data object SaveLayerNode : AccountHubEvent
    data object DismissLayerNode : AccountHubEvent
    data class AskDeleteLayer(val delete: LayerDelete) : AccountHubEvent
    data object DismissDeleteLayer : AccountHubEvent
    data object ConfirmDeleteLayer : AccountHubEvent
    data object DismissLayerInUse : AccountHubEvent

    // -- vendors ------------------------------------------------------------

    data class SearchVendors(val term: String) : AccountHubEvent

    /** Switches the register's tab: all, verified, not verified, or mine. */
    data class FilterVendors(val filter: VendorFilter) : AccountHubEvent

    data class SelectVendor(val id: String?) : AccountHubEvent

    /** Opens the vendor detail modal; null closes it. */
    data class OpenVendorDetail(val id: String?) : AccountHubEvent

    data class RevealVendorBank(val revealed: Boolean) : AccountHubEvent

    /** Opens the full-page form. A null [editing] adds. */
    data class ComposeVendor(val editing: Vendor? = null) : AccountHubEvent

    /**
     * Opens the vendor form from a route — the web's `?action=add` and
     * `?action=edit&id=`. Takes an id rather than a row, because the route
     * arrives before the register has loaded; the edit waits for it.
     */
    data class OpenVendorForm(val editId: String? = null) : AccountHubEvent

    data class UpdateVendorDraft(val draft: NewVendor) : AccountHubEvent

    /** A field the person has left, so its error may show. */
    data class TouchVendorField(val field: String) : AccountHubEvent

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

    data class AskDeleteVendorBank(val open: Boolean) : AccountHubEvent
    data object ConfirmDeleteVendorBank : AccountHubEvent

    data class VerifyVendor(val id: String) : AccountHubEvent

    data class AskDeleteVendor(val vendor: Vendor?) : AccountHubEvent

    data class DeleteVendor(val id: String) : AccountHubEvent

    /** The history side panel; null closes it. */
    data class OpenVendorHistory(val id: String?) : AccountHubEvent

    /** A department user's "Create PO" for this vendor — a hand-off. */
    data class CreatePurchaseOrder(val vendorId: String) : AccountHubEvent

    // -- approvals ----------------------------------------------------------

    data class SwitchApprovalModule(val module: ApprovalModule) : AccountHubEvent

    data class SearchApprovalModules(val term: String) : AccountHubEvent

    data class SearchDepartments(val term: String) : AccountHubEvent

    data class FilterDepartments(val filter: DepartmentFilter) : AccountHubEvent

    data class ToggleDepartmentExpanded(val departmentId: String) : AccountHubEvent

    /** Reads the open module's chains again — the Retry under a failed load. */
    data object ReloadApprovalConfigs : AccountHubEvent

    /** Opens the builder on the production-wide chain — the default card's Configure / Edit. */
    data object EditDefaultApprovals : AccountHubEvent

    /** Opens the builder on a department, seeding from its own chain or, failing that, the default's. */
    data class EditDepartmentConfig(val departmentId: String) : AccountHubEvent

    /** Inserts an empty level at [position] (0-based) — the rail between cards. */
    data class InsertApprovalLevel(val position: Int) : AccountHubEvent

    data class RemoveApprovalLevel(val order: Int) : AccountHubEvent

    /** "Add more": an amount rule on a level that has a Default, an untyped one otherwise. */
    data class AddApprovalRule(val tier: Int) : AccountHubEvent

    /** Removes the [rule]th rule (0-based) of level [tier]; a level keeps at least one. */
    data class RemoveApprovalRule(val tier: Int, val rule: Int) : AccountHubEvent

    data class SetApprovalRuleType(val tier: Int, val rule: Int, val type: String) : AccountHubEvent

    data class SetApprovalRuleAmount(val tier: Int, val rule: Int, val amount: Double?) : AccountHubEvent

    /** An approver chip's remove. */
    data class RemoveApprover(val tier: Int, val rule: Int, val userId: String) : AccountHubEvent

    /** Opens the user picker for one rule of one level. */
    data class OpenApproverPicker(val tier: Int, val rule: Int) : AccountHubEvent

    data object CloseApproverPicker : AccountHubEvent

    data class SearchApproverPicker(val term: String) : AccountHubEvent

    /** Ticks a person in the open picker; nobody joins the rule until [AddPickedApprovers]. */
    data class ToggleApproverPick(val userId: String) : AccountHubEvent

    data object AddPickedApprovers : AccountHubEvent

    data object SaveApprovalConfig : AccountHubEvent

    data object ConfirmApprovalSave : AccountHubEvent

    data object DismissApprovalConfirm : AccountHubEvent

    data object DismissApprovalConfig : AccountHubEvent

    // -- forms configuration -------------------------------------------------

    /** Another module's form. With unsaved edits on screen it asks first. */
    data class OpenFormModule(val module: FormModule) : AccountHubEvent

    data class SearchFormModules(val term: String) : AccountHubEvent

    /**
     * Opens Forms Configuration on one module, from another screen.
     *
     * Its own event rather than an Open followed by a module switch: the two
     * would each start a fetch, and the second would arrive over the first.
     */
    data class OpenFormConfig(val module: FormModule) : AccountHubEvent

    /** Reads the module's template again, after a read that failed. */
    data object ReloadFormTemplate : AccountHubEvent

    /** Enters or leaves edit mode. Leaving keeps the edits, as on the web. */
    data class EditForm(val editing: Boolean) : AccountHubEvent

    /** The preview banner's Discard: asks, then puts back what the server holds. */
    data object AskDiscardFormChanges : AccountHubEvent
    data object DismissDiscardFormChanges : AccountHubEvent
    data object ConfirmDiscardFormChanges : AccountHubEvent

    /** Rearrange mode, and which section's fields its panel lists. */
    data class ToggleRearrange(val on: Boolean) : AccountHubEvent
    data class PickRearrangeSection(val key: String?) : AccountHubEvent

    /** A section dropped where [toKey] sits — by a drag, or by its up and down arrows. */
    data class MoveFormSection(val fromKey: String, val toKey: String) : AccountHubEvent

    /** A field dropped where [toId] sits, within one section — by a drag, or by its arrows. */
    data class MoveFormField(val sectionKey: String, val fromId: String, val toId: String) : AccountHubEvent

    /** The insert rail: a new section after [afterKey], or at the top when it is null. */
    data class ComposeFormSection(val afterKey: String?) : AccountHubEvent
    data class EditFormSectionName(val name: String) : AccountHubEvent
    data object DismissFormSection : AccountHubEvent
    data object AddFormSection : AccountHubEvent

    /** A custom section's name, edited in place. */
    data class RenameFormSection(val key: String, val label: String) : AccountHubEvent
    data class EditFormSectionRename(val name: String) : AccountHubEvent
    data object DismissFormSectionRename : AccountHubEvent
    data object SaveFormSectionRename : AccountHubEvent

    data class AskRemoveFormSection(val section: FormSection) : AccountHubEvent
    data object DismissRemoveFormSection : AccountHubEvent
    data object ConfirmRemoveFormSection : AccountHubEvent

    /**
     * Opens the property panel on a field, or the add-a-field panel when
     * [fieldId] is null. The field already open closes it, as a second click
     * does on the web.
     */
    data class FocusFormField(val sectionKey: String, val fieldId: String?) : AccountHubEvent
    data object DismissFormField : AccountHubEvent

    data class EditNewFormField(val draft: NewFieldDraft) : AccountHubEvent

    /** The add-a-field panel's "System Fields" list. */
    data class ToggleSystemFields(val open: Boolean) : AccountHubEvent
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

    /** A custom field to the end of another section. System fields stay where the module reads them. */
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

    /** "Set Approver Level": the scope modal, then the Approvers page's builder on that chain. */
    data class OpenApproverScope(val open: Boolean) : AccountHubEvent
    data class PickApproverScope(val scope: ApprovalScope?, val departmentId: String? = null) : AccountHubEvent
    data object ContinueApproverScope : AccountHubEvent
}

/** Which Production Setup section a save or revert applies to. */
enum class SetupSection(private val labelKey: String) {
    Companies(S.desktop_companies),
    Currencies(S.desktop_project_currencies),
    TaxTypes(S.desktop_tax_types),
    AssetTags(S.desktop_account_tags),
    Budget(S.desktop_project_budget),
    Schedule(S.desktop_production_schedule),
    PayrollDefaults(S.desktop_payroll_defaults),
    DealConditions(S.desktop_standard_deal_conditions),
    PayrollBureaus(S.desktop_payroll_bureau),
    Allowances(S.dm_allow_title),
    PayrollSettings(S.desktop_payroll_settings),
    PoSetup(S.desktop_purchase_order_setup),
    InvoicesSetup(S.desktop_invoices_setup),
    NonUnionPay(S.desktop_hub_non_union_pay_breakdown),
    DayTypes(S.desktop_day_types),
    ;

    val label: String get() = str(labelKey)
}

/** One-shot things the console asks the host to do. */
sealed interface AccountHubEffect {

    data class Failed(val message: String) : AccountHubEffect

    /** Hand off to another film tool, by its workspace path. */
    data class OpenTool(val path: String, val title: String) : AccountHubEffect

    /** The header card's back arrow: leave the console for the tools grid. */
    data object Back : AccountHubEffect
}

/**
 * The two spend tools whose setup Production Setup points at.
 *
 * [route] is a deep link: the workspace resolves a tool by longest path
 * prefix, so the tail lands the tool on that page rather than its own default.
 */
enum class SpendSetup(val route: String, private val titleKey: String) {
    Cards("/film-tools/card-expenses/settings", S.desktop_card_expenses),
    PettyCash("/film-tools/cash-expenses/settings", S.desktop_petty_cash),
    ;

    val title: String get() = str(titleKey)
}
