package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Everything the Account Hub console reads and writes.
 *
 * ## Why the setup slices are separate calls
 *
 * There is one combined `project-settings` document, and it would be one
 * fetch. Each slice is asked for on its own anyway, because a combined save
 * returns the whole merged document — and applying that response would
 * overwrite whatever another section had unsaved. The web hit exactly that and
 * moved to dedicated per-slice routes; the split is a correctness property, not
 * a fetching preference.
 */
@Suppress("TooManyFunctions") // One method per slice; see the doc above.
interface AccountHubRepository {

    // -- production setup ---------------------------------------------------

    suspend fun companies(): ZillitResult<List<Company>>

    /** Full-list replace: the server diffs, creating, updating and deleting. */
    suspend fun saveCompanies(companies: List<Company>): ZillitResult<List<Company>>

    suspend fun bankAccounts(): ZillitResult<List<BankAccount>>

    suspend fun createBankAccount(account: BankAccount): ZillitResult<BankAccount>

    suspend fun updateBankAccount(account: BankAccount): ZillitResult<BankAccount>

    suspend fun deleteBankAccount(id: String): ZillitResult<Unit>

    suspend fun currencies(): ZillitResult<CurrencySettings>

    suspend fun saveCurrencies(settings: CurrencySettings): ZillitResult<CurrencySettings>

    suspend fun taxTypes(): ZillitResult<List<TaxType>>

    suspend fun saveTaxTypes(taxTypes: List<TaxType>): ZillitResult<List<TaxType>>

    suspend fun assetTags(): ZillitResult<List<String>>

    suspend fun saveAssetTags(tags: List<String>): ZillitResult<List<String>>

    suspend fun projectBudget(): ZillitResult<ProjectBudget>

    suspend fun saveProjectBudget(budget: ProjectBudget): ZillitResult<ProjectBudget>

    suspend fun payrollBureaus(): ZillitResult<List<PayrollBureau>>

    suspend fun savePayrollBureaus(bureaus: List<PayrollBureau>): ZillitResult<List<PayrollBureau>>

    suspend fun dealConditions(): ZillitResult<List<DealCondition>>

    suspend fun saveDealConditions(
        conditions: List<DealCondition>,
    ): ZillitResult<List<DealCondition>>

    suspend fun productionSchedule(): ZillitResult<ProductionSchedule>

    /** Posted, not patched — the one slice whose write verb differs. */
    suspend fun saveProductionSchedule(
        schedule: ProductionSchedule,
    ): ZillitResult<ProductionSchedule>

    suspend fun payrollDefaults(): ZillitResult<PayrollDefaults>

    suspend fun savePayrollDefaults(defaults: PayrollDefaults): ZillitResult<PayrollDefaults>

    suspend fun dayTypes(): ZillitResult<List<DayType>>

    suspend fun saveDayTypes(dayTypes: List<DayType>): ZillitResult<List<DayType>>

    // -- reference data -----------------------------------------------------

    /**
     * The currency catalogue.
     *
     * Served by the **core** API, not the account hub: `/v2/preset/currencies`
     * sits with the other presets on the main project service. Reaching for it
     * on the hub host 404s.
     */
    suspend fun currencyCatalogue(): ZillitResult<List<ProjectCurrency>>

    /** Standard rates per country, from the same preset family. */
    suspend fun taxesByCountry(): ZillitResult<List<CountryTaxes>>

    // -- chart of accounts --------------------------------------------------

    /**
     * The flat chart.
     *
     * [activeOnly] filters server-side, which is what produces orphans — a live
     * row whose parent is inactive. [ChartOfAccounts.tree] surfaces those
     * rather than dropping them.
     */
    suspend fun accounts(activeOnly: Boolean = false): ZillitResult<List<CoaAccount>>

    suspend fun createAccount(account: NewAccount): ZillitResult<CoaAccount>

    /**
     * Name, cost type and the two flags only.
     *
     * Re-parenting or re-typing a row would cascade through every descendant's
     * breadcrumb, which the server does not support in place. The workflow for a
     * structural change is deactivate-and-recreate.
     */
    suspend fun updateAccount(
        id: String,
        name: String,
        costType: CoaCostType,
        isActive: Boolean,
        isPosting: Boolean,
    ): ZillitResult<CoaAccount>

    /** Soft delete — the code stays for historical postings to resolve against. */
    suspend fun deactivateAccount(id: String): ZillitResult<Unit>

    suspend fun trackingSets(): ZillitResult<List<TrackingSet>>

    // -- vendors ------------------------------------------------------------

    suspend fun vendors(search: String = ""): ZillitResult<List<Vendor>>

    suspend fun createVendor(vendor: NewVendor): ZillitResult<Vendor>

    suspend fun updateVendor(id: String, vendor: NewVendor): ZillitResult<Vendor>

    suspend fun verifyVendor(id: String): ZillitResult<Vendor>

    suspend fun deleteVendor(id: String): ZillitResult<Unit>

    suspend fun vendorHistory(id: String): ZillitResult<List<VendorChange>>

    // -- approvals ----------------------------------------------------------

    suspend fun approvalConfigs(module: ApprovalModule): ZillitResult<List<ApprovalConfig>>

    suspend fun saveApprovalConfig(config: ApprovalConfig): ZillitResult<ApprovalConfig>

    suspend fun deleteApprovalConfig(id: String): ZillitResult<Unit>
}

/**
 * A chart row being created.
 *
 * Separate from [CoaAccount] because create takes what edit cannot change —
 * the code, the line type and the parent — and omits the id the server mints.
 */
data class NewAccount(
    val code: String = "",
    val name: String = "",
    val lineType: CoaLineType = CoaLineType.Category,
    val costType: CoaCostType = CoaCostType.Expense,
    val parentId: String? = null,
    val isPosting: Boolean = true,
) {
    /** What the form refuses to send, or null when it is ready. */
    fun validationError(rows: List<CoaAccount>): String? {
        val parent = rows.firstOrNull { it.id == parentId }
        return when {
            code.isBlank() -> "Give the account a code."
            name.isBlank() -> "Give the account a name."
            ChartOfAccounts.codeTaken(rows, code) -> "Code $code is already in use."
            else -> ChartOfAccounts.parentProblem(lineType, parent)
        }
    }
}
