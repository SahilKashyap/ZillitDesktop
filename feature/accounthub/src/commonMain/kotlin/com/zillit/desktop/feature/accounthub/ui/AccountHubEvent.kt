package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubItem
import com.zillit.desktop.feature.accounthub.domain.NewVendor
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

    data class SelectVendor(val id: String?) : AccountHubEvent

    data class ComposeVendor(val editing: Vendor? = null) : AccountHubEvent

    data class UpdateVendorDraft(val draft: NewVendor) : AccountHubEvent

    data object SaveVendor : AccountHubEvent

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
}

/** One-shot things the console asks the host to do. */
sealed interface AccountHubEffect {

    data class Failed(val message: String) : AccountHubEffect

    /** Hand off to another film tool, by its workspace path. */
    data class OpenTool(val path: String, val title: String) : AccountHubEffect
}
