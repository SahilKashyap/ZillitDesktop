package com.zillit.desktop.feature.accounthub.domain

import kotlinx.serialization.json.JsonObject

import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormTemplate
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

    /** One bank record, for a vendor's bank block. */
    suspend fun bankAccount(id: String): ZillitResult<BankAccount>

    suspend fun deleteBankAccount(id: String): ZillitResult<Unit>

    suspend fun currencies(): ZillitResult<CurrencySettings>

    suspend fun saveCurrencies(settings: CurrencySettings): ZillitResult<CurrencySettings>

    /**
     * The production's budget versions, newest first as the server orders them.
     *
     * Read-only here: a version is created by importing a budget file, which
     * is its own surface, and Live and Archived versions cannot be edited at
     * all — the way to change one is to clone it.
     */
    /**
     * The trial balance over a period.
     *
     * On the cost-report service, not this one — the report is that service's
     * and the hub only presents it, the way the purchase-order and invoice
     * settings above are their services'.
     */
    suspend fun trialBalance(query: TrialBalanceQuery): ZillitResult<TrialBalance>

    /**
     * Every transaction of the period, grouped by account.
     *
     * The production's closeout bible. On the cost-report service, like the
     * trial balance beside it.
     */
    suspend fun bibleReport(query: BibleQuery): ZillitResult<BibleReport>

    /** How far the books are closed. */
    suspend fun periodLock(): ZillitResult<PeriodLock>

    /**
     * Closes the period covering [asOfMillis].
     *
     * The server resolves which week that is from the production's zone and
     * cost-report week, and validates: a close is refused while anything
     * inside the window is still unposted. It only ever moves forward, and
     * there is no way back — the refusal to go backwards is a 409, which the
     * caller shows as it comes.
     */
    suspend fun closePeriod(asOfMillis: Long): ZillitResult<PeriodLock>

    suspend fun budgetVersions(): ZillitResult<List<BudgetVersion>>

    suspend fun budgetLines(versionId: String): ZillitResult<List<BudgetLine>>

    /**
     * Parses an uploaded budget file without writing anything.
     *
     * The whole point of the flow: the parse is a guess at somebody else's
     * spreadsheet and it lands in the chart every other tool codes against, so
     * it is reviewed before it is committed.
     */
    suspend fun dryRunBudgetImport(document: AgreementDocument): ZillitResult<Pair<ParsedBudget, BudgetUpload>>

    /**
     * Writes the reviewed parse: the chart codes, the version, and its lines.
     *
     * [upload] identifies the file — by its audit-row id on older backends and
     * by its stored document on newer ones, so both go.
     */
    suspend fun commitBudgetImport(
        upload: BudgetUpload,
        parsed: ParsedBudget,
        meta: BudgetImportMeta,
        mode: CoaImportMode,
    ): ZillitResult<BudgetVersion?>

    suspend fun nonUnionPay(): ZillitResult<NonUnionPay>

    /** Replaces the whole slice; all three lists go every time. */
    suspend fun saveNonUnionPay(value: NonUnionPay): ZillitResult<NonUnionPay>

    // -- importing union rules into the breakdown (the deal-memo service) --

    /** The territories with a published agreement, lower-cased ids. */
    suspend fun coveredTerritories(): ZillitResult<Set<String>>

    suspend fun unionAgreements(territory: String): ZillitResult<List<UnionAgreementSummary>>

    /** One agreement's rule tables, projected into the breakdown's shape — see `AgreementRuleImport`. */
    suspend fun unionAgreementRules(identifier: String): ZillitResult<ImportedRules>

    suspend fun invoicesSetup(): ZillitResult<InvoicesSetup>

    /** Saves the accounts-payable team, its alerts and the run chain. */
    suspend fun saveInvoicesSetup(setup: InvoicesSetup): ZillitResult<InvoicesSetup>

    suspend fun purchaseOrderSetup(): ZillitResult<PurchaseOrderSetup>

    /**
     * Saves the purchase-order settings.
     *
     * A PATCH of the keys this screen owns. Snake_case throughout: the web
     * shipped these camelCase once, and the server ignored every one of them —
     * a cadence picked here simply never persisted, and the pane read back its
     * own defaults.
     */
    suspend fun savePurchaseOrderSetup(setup: PurchaseOrderSetup): ZillitResult<PurchaseOrderSetup>

    suspend fun payrollSettings(): ZillitResult<PayrollSettings>

    /**
     * Saves the approvers, and the pay period when the server still allows it.
     *
     * `pay_period_locked_at` is never sent back — the validator rejects it —
     * and once it is set the pay period is dropped from the body too, so the
     * server's locked-period refusal cannot fire on a value the screen was
     * already showing as read-only.
     */
    suspend fun savePayrollSettings(settings: PayrollSettings): ZillitResult<PayrollSettings>

    /**
     * Creates, renames or retires the payroll balance-sheet codes in one call.
     *
     * `PATCH /payroll-settings/custom-accounts { rows }`. The rows are real
     * chart entries, so the server writes the chart and keeps
     * `payroll_accounts` in step — which is why the plain save above ignores
     * that list.
     */
    suspend fun updatePayrollAccounts(rows: List<PayrollAccountRow>): ZillitResult<PayrollSettings>

    // -- payroll groups -----------------------------------------------------

    suspend fun payrollGroups(): ZillitResult<List<PayrollGroup>>

    suspend fun createPayrollGroup(group: PayrollGroup): ZillitResult<PayrollGroup>

    suspend fun updatePayrollGroup(group: PayrollGroup): ZillitResult<PayrollGroup>

    suspend fun deletePayrollGroup(id: String): ZillitResult<Unit>

    // -- auto-assignment rules ----------------------------------------------

    /** The module's rules — `?module=purchase_orders`. */
    suspend fun assignmentRules(module: String): ZillitResult<List<AssignmentRule>>

    suspend fun createAssignmentRule(rule: AssignmentRule): ZillitResult<AssignmentRule>

    suspend fun updateAssignmentRule(rule: AssignmentRule): ZillitResult<AssignmentRule>

    suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit>

    suspend fun agreementDocuments(): ZillitResult<List<AgreementDocument>>

    /**
     * Appends [documents] to the list.
     *
     * A batch, because the server adds one row per array entry and the picker
     * takes several files at once. The bytes are already in S3 by this point;
     * this call stores what was uploaded.
     */
    suspend fun addAgreementDocuments(
        documents: List<AgreementDocument>,
    ): ZillitResult<List<AgreementDocument>>

    suspend fun deleteAgreementDocument(id: String): ZillitResult<Unit>

    suspend fun allowancesRentals(): ZillitResult<AllowancesRentals>

    /**
     * Replaces the whole slice.
     *
     * Both lists go every time: the server swaps the slice atomically and
     * sending one list would clear the other.
     */
    suspend fun saveAllowancesRentals(value: AllowancesRentals): ZillitResult<AllowancesRentals>

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
     * Edits a row — only the fields [patch] sets.
     *
     * The line type and parent go only when [AccountPatch.structureChanged] — a
     * manual row may be re-typed or re-parented, the way the web's
     * `AccountFormModal` allows, and the server re-walks the breadcrumb. A class
     * change cascades to every descendant server-side, and the answer says how
     * many ([CoaUpdate.cascadedDescendants]).
     */
    suspend fun updateAccount(id: String, patch: AccountPatch): ZillitResult<CoaUpdate>

    /** Soft delete — the code stays for historical postings to resolve against. */
    suspend fun deactivateAccount(id: String): ZillitResult<Unit>

    suspend fun trackingSets(): ZillitResult<List<TrackingSet>>

    suspend fun createTrackingSet(set: TrackingSet): ZillitResult<TrackingSet>

    suspend fun updateTrackingSet(set: TrackingSet): ZillitResult<TrackingSet>

    /** Hard delete — cascades to every code under the set. */
    suspend fun deleteTrackingSet(id: String): ZillitResult<Unit>

    suspend fun createTrackingNode(node: TrackingNode): ZillitResult<TrackingNode>

    suspend fun updateTrackingNode(node: TrackingNode): ZillitResult<TrackingNode>

    suspend fun deleteTrackingNode(setId: String, id: String): ZillitResult<Unit>

    // -- vendors ------------------------------------------------------------

    suspend fun vendors(search: String = ""): ZillitResult<List<Vendor>>

    suspend fun createVendor(vendor: NewVendor): ZillitResult<Vendor>

    suspend fun updateVendor(id: String, vendor: NewVendor): ZillitResult<Vendor>

    suspend fun verifyVendor(id: String): ZillitResult<Vendor>

    suspend fun deleteVendor(id: String): ZillitResult<Unit>

    suspend fun vendorHistory(id: String): ZillitResult<List<VendorChange>>

    /** The country catalogue every vendor country and dial-code picker reads. */
    suspend fun isdCodes(): ZillitResult<List<IsdCountry>>

    /** Where a postcode is, for the vendor form's city and county. */
    suspend fun postcodePlace(countryCode: String, postcode: String): ZillitResult<PostcodePlace>

    // -- approvals ----------------------------------------------------------

    suspend fun approvalConfigs(module: ApprovalModule): ZillitResult<List<ApprovalConfig>>

    /**
     * Which modules have a chain at all, in one call.
     *
     * The alternative is one [approvalConfigs] per module just to label a tab,
     * which is why the web's own pill used to read "Not started" for every
     * module the user had not clicked. The server does not answer for every
     * module — `timecard` is absent — so a module missing from the result is
     * *unknown*, not unconfigured, and is left out of the map rather than
     * mapped to false.
     */
    suspend fun approvalSummary(): ZillitResult<Map<ApprovalModule, Boolean>>

    suspend fun saveApprovalConfig(config: ApprovalConfig): ZillitResult<ApprovalConfig>

    suspend fun deleteApprovalConfig(id: String): ZillitResult<Unit>

    /**
     * Who may be picked as an approver on [toolIdentifier]: the users holding
     * view access on it — the web's `fetchuserapproveringrights` with
     * `viewing_access=true`. Answered as ids; the roster names them.
     */
    suspend fun approverCandidateIds(toolIdentifier: String): ZillitResult<Set<String>>

    // -- period close -------------------------------------------------------

    /** The Weekly Close Command Centre, from the invoices service's analytics. */
    suspend fun cashClose(): ZillitResult<CashCloseDashboard>

    /** Sends the closing package(s) — `POST /closing-package/publish`. */
    suspend fun publishClosingPackage(packages: List<ClosingPackage>): ZillitResult<Unit>

    // -- form templates -----------------------------------------------------

    /**
     * A module's form, as its sections and fields.
     *
     * The server creates the system defaults on the first ask, so an empty
     * answer means a module with no form rather than one nobody has configured.
     */
    suspend fun formTemplate(module: FormModule): ZillitResult<FormTemplate>

    /** Replaces the module's whole template. */
    suspend fun saveFormTemplate(module: FormModule, template: FormTemplate): ZillitResult<Unit>

    /** Throws the production's changes away and answers with the defaults. */
    suspend fun resetFormTemplate(module: FormModule): ZillitResult<FormTemplate>
}

/**
 * What an edit sends — see [AccountHubRepository.updateAccount].
 *
 * A null field is left out of the body, so the table's cost-type select sends
 * the class alone, as the web's does, rather than re-sending a name it may hold
 * a stale copy of. [lineType] and [parentId] go only when [structureChanged];
 * [code] only when set.
 */
data class AccountPatch(
    val name: String? = null,
    val costType: CoaCostType? = null,
    val isActive: Boolean? = null,
    val isPosting: Boolean? = null,
    val structureChanged: Boolean = false,
    val lineType: CoaLineType? = null,
    val parentId: String? = null,
    val code: String? = null,
)

/**
 * A binary export the hub asks the host to fetch — `ApiClient` speaks
 * envelopes only, and the report routes answer with a file.
 */
fun interface HubExporter {
    suspend fun export(report: HubExportReport, format: ExportFormat, body: JsonObject): ZillitResult<ByteArray>
}

enum class HubExportReport(val path: String, val fileStem: String) {
    TrialBalance("trial-balance", "trial-balance"),
    Bible("bible", "bible-report"),
}

/** Where an exported file goes — the host's Downloads folder, then opened. */
fun interface HubFiles {
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}

/** Opens a stored document — an agreement, a budget's source file — in the OS. */
fun interface HubDocumentOpener {
    suspend fun open(document: AgreementDocument): ZillitResult<Unit>
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
    val isActive: Boolean = true,
) {
    /**
     * What the form refuses to send, or null when it is ready — the web's rules.
     *
     * The name is optional (a code alone is an account) and so is the parent
     * below the top level; a taken code and a parent at the wrong level are not.
     */
    fun validationError(rows: List<CoaAccount>): String? = when {
        code.isBlank() -> "Code is required"
        ChartOfAccounts.codeTaken(rows, code) -> "Code \"${code.trim().uppercase()}\" already exists"
        else -> ChartOfAccounts.parentProblem(lineType, parentId, rows)
    }
}

