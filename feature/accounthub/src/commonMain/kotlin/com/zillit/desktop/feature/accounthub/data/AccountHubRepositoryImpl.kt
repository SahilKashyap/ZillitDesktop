package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.forms.FormTemplateSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.EntitlementRow
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AccountPatch
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.BankDetail
import com.zillit.desktop.feature.accounthub.domain.CashCloseDashboard
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.CustomDay
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BudgetUpload
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.AccountHubRepository
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.ProductionSchedule
import com.zillit.desktop.feature.accounthub.domain.ProjectBudget
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorAddress
import com.zillit.desktop.feature.accounthub.domain.VendorChange
import com.zillit.desktop.feature.accounthub.domain.VendorPhone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The Account Hub's REST surface.
 *
 * ## Three hosts, and only two of them follow the path
 *
 *  - `/api/v2/account-hub/…` → the account-hub service, as you would expect;
 *  - `/api/v2/vendors` → **also** the account-hub service, despite the path
 *    saying nothing about it. Purchase Orders already learned this the hard
 *    way; it is transcribed here rather than rediscovered;
 *  - `/api/v2/preset/…` → the **core** project service. The currency and
 *    country-tax catalogues are shared reference data and were never moved.
 *
 * ## Why every setup slice has its own route
 *
 * There is one settings document and a combined PATCH that returns the whole
 * merged thing. Applying that response overwrites edits another section has in
 * progress, so each slice reads and writes its own route and the response
 * carries only that slice. This is the reason the interface has one method per
 * slice rather than one `saveSettings`.
 *
 * ## Read wrapped, write bare
 *
 * The slice routes answer `{ data: { value: … } }` and take their PATCH body
 * **unwrapped** — for the list slices, a bare JSON array. The asymmetry is
 * theirs, and getting it backwards fails quietly: a wrapped write is accepted
 * and stores an object where a list belongs.
 */
@Suppress("TooManyFunctions") // Mirrors the interface, which mirrors the slices.
class AccountHubRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : AccountHubRepository {

    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"
    private val settingsBase = "$hubBase/project-settings"

    /** The reports, the close boundary and the budget — see [HubReportSource]. */
    private val reports = HubReportSource(apiClient, config)

    /** The per-module form documents — see [FormTemplateSource]. */
    private val formTemplates = FormTemplateSource(apiClient, config)

    /** Payroll groups, auto-assignment rules and the chart's layers — see [HubSetupSource]. */
    private val setupSource = HubSetupSource(apiClient, config)

    /** The union agreements the breakdown imports from — see [HubAgreementSource]. */
    private val agreements = HubAgreementSource(apiClient, config)

    /** The PO, invoices and payroll settings documents — see [HubSettingsSource]. */
    private val settings = HubSettingsSource(apiClient, config)

    /** The chart of accounts — see [HubChartSource]. */
    private val chartSource = HubChartSource(apiClient, config)

    /** The vendor register and the approval chains — see [HubRegisterSource]. */
    private val register = HubRegisterSource(apiClient, config)

    /** The vendor form's country list and postcode lookup — see [VendorPresetSource]. */
    private val presets = VendorPresetSource(apiClient, config)

    /** The invoices service — its cash-and-close analytics. */
    private val invoicesBase = "${config.apiV2(ZillitService.Invoices).trimEnd('/')}/invoices"


    /** Shared reference data, on the core service. */
    private val presetBase = "${config.apiV2(ZillitService.Core)}preset"



    // -- production setup ---------------------------------------------------

    override suspend fun companies(): ZillitResult<List<Company>> =
        sliceList("$settingsBase/companies", CompanyDto.serializer()) { it.toDomain() }

    override suspend fun saveCompanies(companies: List<Company>): ZillitResult<List<Company>> =
        patchSliceList(
            url = "$settingsBase/companies",
            body = buildJsonArray { companies.forEach { add(it.toJson()) } },
            element = CompanyDto.serializer(),
        ) { it.toDomain() }

    /**
     * The production's own banks, as the web lists them (`ProductionBanksContext`):
     * `entity_type=production`, one 200-row page. Unfiltered, the table's vendor
     * and crew banks came back too and were shown and edited as production ones.
     */
    override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/bank-accounts",
        serializer = ListSerializer(BankAccountDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("entity_type" to BankAccount.PRODUCTION, "per_page" to BANKS_PAGE),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createBankAccount(account: BankAccount): ZillitResult<BankAccount> =
        writeBank(HttpVerb.Post, "$hubBase/bank-accounts", account)

    override suspend fun updateBankAccount(account: BankAccount): ZillitResult<BankAccount> =
        writeBank(HttpVerb.Patch, "$hubBase/bank-accounts/${account.id}", account)

    /** One bank record — how a vendor's bank block is read. See `VendorBank`. */
    override suspend fun bankAccount(id: String): ZillitResult<BankAccount> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/bank-accounts/$id",
        serializer = BankAccountDto.serializer(),
        module = RequestModule.ProjectUser,
    ).flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_hub_that_bank_record_could_not_be_read)))
    }

    /**
     * Removes a bank record, refusing where the server does.
     *
     * The service refuses a bank still referenced by an invoice, a card, a cash
     * claim or a timecard — as a 409 with a readable message, and on some
     * deployments as a 200 carrying `status: 0`. Reading the second as a success
     * cleared the form's bank block while the record survived, so the next load
     * brought every detail back with nothing said about why.
     */
    override suspend fun deleteBankAccount(id: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "$hubBase/bank-accounts/$id",
            module = RequestModule.ProjectUser,
        ).flatMap { envelope ->
            if (envelope.status == 0) {
                ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
            } else {
                ZillitResult.Success(Unit)
            }
        }

    override suspend fun currencies(): ZillitResult<CurrencySettings> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/project-currencies",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.toCurrencySettings() }

    override suspend fun saveCurrencies(
        settings: CurrencySettings,
    ): ZillitResult<CurrencySettings> {
        // Rates pinned before sending: a null `exr` is dropped by the service
        // *without* an error — see CurrencySettings.forWire.
        val outgoing = settings.forWire()
        return checked(
            verb = HttpVerb.Patch,
            url = "$settingsBase/project-currencies",
            serializer = ValueDto.serializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put(
                    "currencies",
                    buildJsonArray {
                        outgoing.currencies.forEach { currency ->
                            add(
                                buildJsonObject {
                                    put("code", JsonPrimitive(currency.code))
                                    put("name", JsonPrimitive(currency.name))
                                    put("symbol", JsonPrimitive(currency.symbol))
                                    put("exr", JsonPrimitive(currency.rate ?: CurrencySettings.BASE_RATE))
                                },
                            )
                        }
                    },
                )
                put("default", outgoing.defaultCode?.let(::JsonPrimitive) ?: JsonNull)
            },
        ).flatMap { wrapper ->
            val stored = wrapper.value.toCurrencySettings()
            // The service answers `status: 1` even when it has stored nothing,
            // so success is judged on what came back, not on the status. Without
            // this the section re-snapshots to empty and the save reads as a
            // silent data loss.
            if (outgoing.currencies.isNotEmpty() && stored.currencies.isEmpty()) {
                ZillitResult.Failure(
                    ZillitError.Validation(
                        str(S.desktop_hub_the_server_accepted_the_currencies_but_stored_none_of_them),
                    ),
                )
            } else {
                ZillitResult.Success(stored)
            }
        }
    }







    override suspend fun nonUnionPay(): ZillitResult<NonUnionPay> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/non-union-paybreakdown",
        serializer = ValueDto.serializer(NonUnionPayDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value?.toDomain() ?: NonUnionPay() }

    override suspend fun saveNonUnionPay(value: NonUnionPay): ZillitResult<NonUnionPay> =
        checked(
            verb = HttpVerb.Patch,
            url = "$settingsBase/non-union-paybreakdown",
            serializer = ValueDto.serializer(NonUnionPayDto.serializer()),
            module = RequestModule.ProjectUser,
            // The slice bare, as its sibling sub-routes take it.
            body = buildJsonObject {
                PayRuleKind.entries.forEach { kind ->
                    put(kind.wire, buildJsonArray { value.rulesFor(kind).forEach { add(it.toJson()) } })
                }
                // Sent every time, including as null. The scope decides who
                // these rules pay, and a save that left it out of the body was
                // a production's department scoping quietly widened to
                // everybody by somebody editing an unrelated rule.
                put("apply_mode", value.applyMode.wire?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "department_ids",
                    buildJsonArray { value.departmentIds.forEach { add(JsonPrimitive(it)) } },
                )
            },
        ).map { it.value?.toDomain() ?: value }

    override suspend fun trialBalance(query: TrialBalanceQuery) = reports.trialBalance(query)

    override suspend fun bibleReport(query: BibleQuery) = reports.bibleReport(query)

    override suspend fun periodLock() = reports.periodLock()

    override suspend fun closePeriod(asOfMillis: Long) = reports.closePeriod(asOfMillis)

    override suspend fun budgetVersions() = reports.budgetVersions()

    override suspend fun budgetLines(versionId: String) = reports.budgetLines(versionId)

    override suspend fun dryRunBudgetImport(document: AgreementDocument) =
        reports.dryRunBudgetImport(document)

    override suspend fun commitBudgetImport(
        upload: BudgetUpload,
        parsed: ParsedBudget,
        meta: BudgetImportMeta,
        mode: CoaImportMode,
    ) = reports.commitBudgetImport(upload, parsed, meta, mode)

    // -- the three modal settings documents — see [HubSettingsSource] ----------

    override suspend fun invoicesSetup() = settings.invoicesSetup()

    override suspend fun saveInvoicesSetup(setup: InvoicesSetup) = settings.saveInvoicesSetup(setup)

    override suspend fun purchaseOrderSetup() = settings.purchaseOrderSetup()

    override suspend fun savePurchaseOrderSetup(setup: PurchaseOrderSetup) = settings.savePurchaseOrderSetup(setup)

    override suspend fun payrollSettings() = settings.payrollSettings()

    override suspend fun savePayrollSettings(settings: PayrollSettings) = this.settings.savePayrollSettings(settings)

    override suspend fun updatePayrollAccounts(rows: List<PayrollAccountRow>) = settings.updatePayrollAccounts(rows)

    // -- payroll groups -----------------------------------------------------

    // -- payroll groups, assignment rules and layers — see [HubSetupSource] --------

    override suspend fun payrollGroups() = setupSource.payrollGroups()

    override suspend fun createPayrollGroup(group: PayrollGroup) = setupSource.createPayrollGroup(group)

    override suspend fun updatePayrollGroup(group: PayrollGroup) = setupSource.updatePayrollGroup(group)

    override suspend fun deletePayrollGroup(id: String) = setupSource.deletePayrollGroup(id)

    override suspend fun assignmentRules(module: String) = setupSource.assignmentRules(module)

    override suspend fun createAssignmentRule(rule: AssignmentRule) = setupSource.createAssignmentRule(rule)

    override suspend fun updateAssignmentRule(rule: AssignmentRule) = setupSource.updateAssignmentRule(rule)

    override suspend fun deleteAssignmentRule(id: String) = setupSource.deleteAssignmentRule(id)

    override suspend fun trackingSets() = setupSource.trackingSets()

    override suspend fun createTrackingSet(set: TrackingSet) = setupSource.createTrackingSet(set)

    override suspend fun updateTrackingSet(set: TrackingSet) = setupSource.updateTrackingSet(set)

    override suspend fun deleteTrackingSet(id: String) = setupSource.deleteTrackingSet(id)

    override suspend fun createTrackingNode(node: TrackingNode) = setupSource.createTrackingNode(node)

    override suspend fun updateTrackingNode(node: TrackingNode) = setupSource.updateTrackingNode(node)

    override suspend fun deleteTrackingNode(setId: String, id: String) = setupSource.deleteTrackingNode(setId, id)

    override suspend fun agreementDocuments(): ZillitResult<List<AgreementDocument>> =
        sliceList("$settingsBase/agreements-documents", AgreementDocumentDto.serializer()) { it.toDomain() }

    override suspend fun addAgreementDocuments(
        documents: List<AgreementDocument>,
    ): ZillitResult<List<AgreementDocument>> = apiClient.request(
        // POST, not PATCH: this sub-route appends rather than replacing, so a
        // second person's upload cannot wipe the first's.
        verb = HttpVerb.Post,
        url = "$settingsBase/agreements-documents",
        serializer = ValueDto.serializer(ListSerializer(AgreementDocumentDto.serializer())),
        module = RequestModule.ProjectUser,
        body = buildJsonArray { documents.forEach { add(it.toJson()) } },
    ).map { row -> row.value.orEmpty().map { it.toDomain() } }

    override suspend fun deleteAgreementDocument(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$settingsBase/agreements-documents/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun allowancesRentals(): ZillitResult<AllowancesRentals> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$settingsBase/allowances-rentals",
            serializer = ValueDto.serializer(AllowancesRentalsDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { it.value?.toDomain() ?: AllowancesRentals() }

    override suspend fun saveAllowancesRentals(
        value: AllowancesRentals,
    ): ZillitResult<AllowancesRentals> = checked(
        verb = HttpVerb.Patch,
        url = "$settingsBase/allowances-rentals",
        serializer = ValueDto.serializer(AllowancesRentalsDto.serializer()),
        module = RequestModule.ProjectUser,
        // The slice itself, not wrapped under `value` — this sub-route takes
        // the body bare where the combined PATCH takes `{ key: value }`.
        body = buildJsonObject {
            put("allowances", buildJsonArray { value.allowances.forEach { add(it.toJson(rental = false)) } })
            put("rentals", buildJsonArray { value.rentals.forEach { add(it.toJson(rental = true)) } })
        },
    ).map { it.value?.toDomain() ?: value }

    override suspend fun taxTypes(): ZillitResult<List<TaxType>> =
        sliceList("$settingsBase/tax-types", TaxTypeDto.serializer()) { it.toDomain() }

    override suspend fun saveTaxTypes(taxTypes: List<TaxType>): ZillitResult<List<TaxType>> =
        patchSliceList(
            url = "$settingsBase/tax-types",
            body = buildJsonArray { TaxType.forWire(taxTypes).forEach { add(it.toJson()) } },
            element = TaxTypeDto.serializer(),
        ) { it.toDomain() }

    override suspend fun assetTags(): ZillitResult<List<String>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/asset-tags",
        serializer = valueList(String.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.orEmpty().normalisedTags() }

    override suspend fun saveAssetTags(tags: List<String>): ZillitResult<List<String>> =
        checked(
            verb = HttpVerb.Patch,
            url = "$settingsBase/asset-tags",
            serializer = valueList(String.serializer()),
            module = RequestModule.ProjectUser,
            body = buildJsonArray { tags.normalisedTags().forEach { add(JsonPrimitive(it)) } },
        ).map { it.value.orEmpty().normalisedTags() }

    override suspend fun projectBudget(): ZillitResult<ProjectBudget> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/project-budget",
        serializer = ValueDto.serializer(ProjectBudgetDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value?.toDomain() ?: ProjectBudget() }

    override suspend fun saveProjectBudget(budget: ProjectBudget): ZillitResult<ProjectBudget> =
        checked(
            verb = HttpVerb.Patch,
            url = "$settingsBase/project-budget",
            serializer = ValueDto.serializer(ProjectBudgetDto.serializer()),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("amount", budget.amount?.let(::JsonPrimitive) ?: JsonNull)
                put("currency", JsonPrimitive(budget.currency))
            },
        ).map { it.value?.toDomain() ?: budget }

    override suspend fun payrollBureaus(): ZillitResult<List<PayrollBureau>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/payroll-bureau",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.toPayrollBureaus() }

    /**
     * Rows with a blank title are dropped and the rest trimmed, as the web's
     * `denormalize` does — a bureau with no name is a row somebody started
     * and abandoned, not one to persist.
     */
    override suspend fun savePayrollBureaus(
        bureaus: List<PayrollBureau>,
    ): ZillitResult<List<PayrollBureau>> = checked(
        verb = HttpVerb.Patch,
        url = "$settingsBase/payroll-bureau",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
        body = buildJsonArray {
            bureaus.filter { it.title.isNotBlank() }.forEach { bureau ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(bureau.id))
                        put("title", JsonPrimitive(bureau.title.trim()))
                        put("description", JsonPrimitive(bureau.description.trim()))
                    },
                )
            }
        },
    ).map { it.value.toPayrollBureaus() }

    override suspend fun dealConditions(): ZillitResult<List<DealCondition>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/standard-deal-conditions",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.toDealConditions() }

    /**
     * `{ order, condition }` per clause, order rebuilt from position and blanks
     * dropped — the web's `denormalize`. No id goes: the id is local, and the
     * server stores the list as given.
     */
    override suspend fun saveDealConditions(
        conditions: List<DealCondition>,
    ): ZillitResult<List<DealCondition>> = checked(
        verb = HttpVerb.Patch,
        url = "$settingsBase/standard-deal-conditions",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
        body = buildJsonArray {
            conditions.filter { it.condition.isNotBlank() }.forEachIndexed { index, condition ->
                add(
                    buildJsonObject {
                        put("order", JsonPrimitive(index))
                        put("condition", JsonPrimitive(condition.condition.trim()))
                    },
                )
            }
        },
    ).map { it.value.toDealConditions() }

    override suspend fun productionSchedule(): ZillitResult<ProductionSchedule> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$settingsBase/production-schedule",
            serializer = ValueDto.serializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
        ).map { it.value.toProductionSchedule() }

    override suspend fun saveProductionSchedule(
        schedule: ProductionSchedule,
    ): ZillitResult<ProductionSchedule> = checked(
        // POST, not PATCH. The one slice whose write verb differs, and the
        // server answers 405 rather than falling back.
        verb = HttpVerb.Post,
        url = "$settingsBase/production-schedule",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("start_date", schedule.startDate.asDate())
            put("end_date", schedule.endDate.asDate())
            put("prep", schedule.prep.toJson())
            put("shoot", schedule.shoot.toJson())
            put("wrap", schedule.wrap.toJson())
            // The full list every time — the server replaces it whole.
            put("custom_days", buildJsonArray { schedule.customDays.forEach { add(it.toJson()) } })
        },
    ).map { it.value.toProductionSchedule() }

    override suspend fun payrollDefaults(): ZillitResult<PayrollDefaults> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/payroll-defaults",
        serializer = ValueDto.serializer(PayrollDefaultsDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value?.toDomain() ?: PayrollDefaults() }

    override suspend fun savePayrollDefaults(
        defaults: PayrollDefaults,
    ): ZillitResult<PayrollDefaults> = checked(
        verb = HttpVerb.Patch,
        url = "$settingsBase/payroll-defaults",
        serializer = ValueDto.serializer(PayrollDefaultsDto.serializer()),
        module = RequestModule.ProjectUser,
        // All three go every time: the server takes this as the whole slice,
        // and an omitted flag reads as false rather than as unchanged.
        body = buildJsonObject {
            put("auto_sync", JsonPrimitive(defaults.autoSync))
            put("notify_payroll", JsonPrimitive(defaults.notifyPayroll))
            put("include_pdf", JsonPrimitive(defaults.includePdf))
        },
    ).map { it.value?.toDomain() ?: defaults }

    override suspend fun dayTypes(): ZillitResult<List<DayType>> =
        sliceList("$settingsBase/day-types", DayTypeDto.serializer()) { it.toDomain() }

    override suspend fun saveDayTypes(dayTypes: List<DayType>): ZillitResult<List<DayType>> =
        patchSliceList(
            url = "$settingsBase/day-types",
            body = buildJsonArray { dayTypes.forEach { add(it.toJson()) } },
            element = DayTypeDto.serializer(),
        ) { it.toDomain() }

    // -- reference data -----------------------------------------------------

    override suspend fun currencyCatalogue(): ZillitResult<List<ProjectCurrency>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$presetBase/currencies",
            serializer = ListSerializer(CurrencyDto.serializer()),
            module = RequestModule.Device,
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun taxesByCountry(): ZillitResult<List<CountryTaxes>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$presetBase/taxes-by-country",
        serializer = ListSerializer(CountryTaxesDto.serializer()),
        module = RequestModule.Device,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    // -- chart of accounts — see [HubChartSource] ------------------------------

    override suspend fun accounts(activeOnly: Boolean) = chartSource.accounts(activeOnly)

    override suspend fun createAccount(account: NewAccount) = chartSource.createAccount(account)

    override suspend fun updateAccount(id: String, patch: AccountPatch) = chartSource.updateAccount(id, patch)

    override suspend fun deactivateAccount(id: String) = chartSource.deactivateAccount(id)

    // -- vendors and approvals — see [HubRegisterSource] -------------------------

    override suspend fun vendors(search: String) = register.vendors(search)

    override suspend fun createVendor(vendor: NewVendor) = register.createVendor(vendor)

    override suspend fun updateVendor(id: String, vendor: NewVendor) = register.updateVendor(id, vendor)

    override suspend fun verifyVendor(id: String) = register.verifyVendor(id)

    override suspend fun deleteVendor(id: String) = register.deleteVendor(id)

    override suspend fun vendorHistory(id: String) = register.vendorHistory(id)

    override suspend fun isdCodes() = presets.isdCodes()

    override suspend fun coveredTerritories() = agreements.coveredTerritories()

    override suspend fun unionAgreements(territory: String) = agreements.agreements(territory)

    override suspend fun unionAgreementRules(identifier: String) = agreements.agreementRules(identifier)

    override suspend fun postcodePlace(countryCode: String, postcode: String) =
        presets.postcodePlace(countryCode, postcode)

    override suspend fun approvalConfigs(module: ApprovalModule) = register.approvalConfigs(module)

    override suspend fun approvalSummary() = register.approvalSummary()

    override suspend fun saveApprovalConfig(config: ApprovalConfig) = register.saveApprovalConfig(config)

    override suspend fun deleteApprovalConfig(id: String) = register.deleteApprovalConfig(id)

    override suspend fun approverCandidateIds(toolIdentifier: String) = register.approverCandidateIds(toolIdentifier)

    // -- period close -------------------------------------------------------

    override suspend fun cashClose(): ZillitResult<CashCloseDashboard> = apiClient.request(
        // The invoices service's analytics, as the web's Cash & Close tab reads it.
        verb = HttpVerb.Get,
        url = "$invoicesBase/analytics/cash-close",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toCashClose() }

    override suspend fun publishClosingPackage(packages: List<ClosingPackage>) = reports.publishClosingPackage(packages)

    // -- form templates -----------------------------------------------------

    override suspend fun formTemplate(module: FormModule): ZillitResult<FormTemplate> =
        formTemplates.template(module)

    override suspend fun saveFormTemplate(
        module: FormModule,
        template: FormTemplate,
    ): ZillitResult<Unit> = formTemplates.save(module, template)

    override suspend fun resetFormTemplate(module: FormModule): ZillitResult<FormTemplate> =
        formTemplates.reset(module)

    // -- shared plumbing ----------------------------------------------------

    private suspend fun <D, T> sliceList(
        url: String,
        element: KSerializer<D>,
        toDomain: (D) -> T?,
    ): ZillitResult<List<T>> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = valueList(element),
        module = RequestModule.ProjectUser,
    ).map { wrapper -> wrapper.value.orEmpty().mapNotNull(toDomain) }

    /**
     * A section write, refused on `status: 0` as the web's `handleSave` refuses
     * anything but `status: 1` — `ApiClient.request` never looks at the status,
     * so a refusal carrying data read as a save and re-snapshotted the section.
     */
    private suspend fun <T> checked(
        verb: HttpVerb,
        url: String,
        serializer: KSerializer<T>,
        module: RequestModule,
        body: JsonElement,
    ): ZillitResult<T> = apiClient.envelope(verb = verb, url = url, module = module, body = body).flatMap { envelope ->
        val data = envelope.data
        when {
            envelope.status == REFUSED -> ZillitResult.Failure(
                ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message),
            )
            data == null -> ZillitResult.Failure(ZillitError.Serialization("response had no data field"))
            else -> runCatching { accountHubJson.decodeFromJsonElement(serializer, data) }.fold(
                onSuccess = { ZillitResult.Success(it) },
                onFailure = { ZillitResult.Failure(ZillitError.Serialization(it.message)) },
            )
        }
    }

    private suspend fun <D, T> patchSliceList(
        url: String,
        body: JsonArray,
        element: KSerializer<D>,
        toDomain: (D) -> T?,
    ): ZillitResult<List<T>> = checked(
        verb = HttpVerb.Patch,
        url = url,
        serializer = valueList(element),
        module = RequestModule.ProjectUser,
        body = body,
    ).map { wrapper -> wrapper.value.orEmpty().mapNotNull(toDomain) }

    private suspend fun writeBank(
        verb: HttpVerb,
        url: String,
        account: BankAccount,
    ): ZillitResult<BankAccount> = apiClient.request(
        verb = verb,
        url = url,
        serializer = BankAccountDto.serializer(),
        module = RequestModule.ProjectUser,
        // The web's `BankAccountFormModal` payload, key for key. Blanks go as
        // null rather than `""`: this validator types its fields, and an empty
        // string in a nullable column is a value the reconciliation match then
        // compares against.
        body = buildJsonObject {
            put("name", JsonPrimitive(account.name.trim()))
            put("account_holder_name", JsonPrimitive(account.accountHolderName.trim()))
            put("entity_id", account.entityId?.let(::JsonPrimitive) ?: JsonNull)
            put("entity_type", JsonPrimitive(account.entityType))
            put("account_number", account.accountNumber.trim().orNull())
            // As held: the field strips to digits as it is typed, and a stored
            // value nobody touched goes back untouched — the web sends
            // `form.sort_code.trim()`. Re-stripping on every save cut a legacy
            // or non-UK code to six digits whenever any other field changed.
            put("sort_code", account.sortCode.trim().orNull())
            put("swift_code", account.swiftCode.trim().orNull())
            put("iban_number", account.ibanNumber.trim().orNull())
            put("cheque_number", account.chequeNumber.trim().orNull())
            put("wire_number", account.wireNumber.trim().orNull())
            put("nominal_code", account.nominalCode.trim().orNull())
            put("ap_clearance_nominal_code", account.apClearanceNominalCode.trim().orNull())
            put(
                "currency",
                account.currencyCode.trim().takeIf { it.isNotEmpty() }?.let { code ->
                    buildJsonObject {
                        put("code", JsonPrimitive(code))
                        put("name", JsonPrimitive(account.currencyName))
                        put("symbol", JsonPrimitive(account.currencySymbol))
                    }
                } ?: JsonNull,
            )
            // An array, as the web sends it (`serializeAdditionalDetails`); null
            // when there are none. Untitled rows never persist. The reader still
            // takes the JSON-string form older clients stored.
            put(
                "additional_details",
                BankAccounts.persistable(account.additionalDetails).takeIf { it.isNotEmpty() }?.toJson("field")
                    ?: JsonNull,
            )
        },
    ).map { it.toDomain() ?: account }


}

// -- outgoing shapes --------------------------------------------------------

private fun Company.toJson(): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name.trim()))
    put("legal_name", JsonPrimitive(legalName.trim()))
    put("country", JsonPrimitive(country))
    put("country_code", JsonPrimitive(countryCode))
    put("bank_ids", buildJsonArray { bankIds.forEach { add(JsonPrimitive(it)) } })
    put("tax_credits", buildJsonArray { taxCredits.forEach { add(JsonPrimitive(it)) } })
    // The whole UK block every time, blanks included: the list is written
    // back whole, and a company whose block was omitted had both references
    // cleared (the web's `makeCompanyDraft` sends it for the same reason).
    put(
        "uk",
        buildJsonObject {
            put("paye_ref", JsonPrimitive(ukPayeRef.trim()))
            put("accounts_office_ref", JsonPrimitive(ukAccountsOfficeRef.trim()))
            put("pension_provider", JsonPrimitive(ukPensionProvider.trim()))
            put("pension_scheme_ref", JsonPrimitive(ukPensionSchemeRef.trim()))
        },
    )
    // No currency: it is derived from the linked banks and the backend owns the
    // canonical value. Sending one from here would invent it.
}

/** Typed extra rows as the wire stores them; [titleKey] is `field` on a bank and `label` on a vendor. */
internal fun List<BankDetail>.toJson(titleKey: String): JsonArray = buildJsonArray {
    forEach { row ->
        add(
            buildJsonObject {
                put(titleKey, JsonPrimitive(row.title.trim()))
                put("value", JsonPrimitive(row.value))
                put("field_type", JsonPrimitive(row.fieldType.wire))
            },
        )
    }
}

internal fun List<String>.toJsonArray(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

/** The web's `mapRuleToApi`. */
internal fun AssignmentRule.toJson(): Map<String, JsonElement> = mapOf(
    "departments" to departments.toJsonArray(),
    "vendors" to vendors.toJsonArray(),
    "nominal_codes" to nominalCodes.toJsonArray(),
    "amount_min" to (amountMinValue?.let(::JsonPrimitive) ?: JsonNull),
    "target_user_id" to JsonPrimitive(assignTo),
    "is_active" to JsonPrimitive(isActive),
    "priority" to JsonPrimitive(priority),
)

private fun CustomDay.toJson(): JsonElement = buildJsonObject {
    put("name", JsonPrimitive(name.trim()))
    put("start_date", startDate.asDate())
    put("end_date", endDate.asDate())
}

/**
 * One allowance or rental row, as the server stores it.
 *
 * Only the new field names are written — a doc read with `on` or `rate` is
 * saved back in the current spelling. `cap_type` and `cap_amount` go on a
 * rental only; an allowance has no cap on the wire and sending one would
 * invent a field the validator does not know.
 */
private fun EntitlementRow.toJson(rental: Boolean): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
    put("enable", JsonPrimitive(enabled))
    put("amount", amount.asAmountJson())
    put("basis", JsonPrimitive(basis))
    put("applies_to", JsonPrimitive(appliesTo))
    if (rental) {
        put("cap_type", JsonPrimitive(if (capped) "capped" else "uncapped"))
        put("cap_amount", capAmount.asAmountJson())
    }
    put("nominal_code", JsonPrimitive(nominalCode))
}

/**
 * An empty cell is null, not zero.
 *
 * The web hit this the hard way: `Number("1,500")` is NaN, so a typed
 * thousands separator became a silent zero. Anything that does not parse
 * cleanly is sent as null rather than a number nobody meant.
 */
private fun String.asAmountJson(): JsonElement =
    trim().replace(",", "").toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull

/**
 * One uploaded document, as the append route takes it.
 *
 * `caption` and `description` both go: the attachment contract names it
 * caption, and the agreements row carries an explicit description. The web
 * sends both for the same reason.
 */
internal fun AgreementDocument.toJson(): JsonElement = buildJsonObject {
    put("title", JsonPrimitive(title))
    put("description", JsonPrimitive(description))
    put("caption", JsonPrimitive(description))
    put("name", JsonPrimitive(name))
    put("media", JsonPrimitive(media))
    put("bucket", JsonPrimitive(bucket))
    put("region", JsonPrimitive(region))
    put("content_type", JsonPrimitive(contentType))
    put("content_subtype", JsonPrimitive(contentSubtype))
    put("file_size", JsonPrimitive(fileSize))
}

/**
 * One pay rule.
 *
 * Only the keys this editor understands are written. The engine pads what it
 * needs on read, and inventing a null for every canonical key would put this
 * client's idea of the schema into the stored document.
 */
private fun PayRule.toJson(): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("label", JsonPrimitive(label))
    put("rate_type", JsonPrimitive(rateType.wire))
    put("rate_amount", rateAmount.asAmountJson())
    put("basis", JsonPrimitive(basis.wire))
    put("triggers", buildJsonArray { triggers.forEach { add(it.toJson()) } })
    put("is_enhancement", JsonPrimitive(isEnhancement))
    put("nominal_code", JsonPrimitive(nominalCode))
    put("note", JsonPrimitive(note))
    put("applies_to", JsonPrimitive(appliesTo))
    put("cap_type", JsonPrimitive(if (capped) "capped" else "uncapped"))
    put("cap_amount", if (capped) capAmount.asAmountJson() else JsonNull)
    put("day_type", dayType.trim().orNull())
}

/** A key the condition does not use is omitted, not sent as null. */
@Suppress("CyclomaticComplexMethod") // One line per wire key; the list IS the contract.
private fun PayTrigger.toJson(): JsonElement = buildJsonObject {
    afterMinutes?.let { put("after", JsonPrimitive(it)) }
    beforeMinutes?.let { put("before", JsonPrimitive(it)) }
    lessMinutes?.let { put("less", JsonPrimitive(it)) }
    dayNumber?.let { put("day_number", JsonPrimitive(it)) }
    if (consecutive) put("consecutive", JsonPrimitive(true))
    if (dayKinds.isNotEmpty()) {
        put("day_kind", buildJsonArray { dayKinds.forEach { add(JsonPrimitive(it.wire)) } })
    }
    if (clock) put("clock", JsonPrimitive(true))
    if (meal) put("meal", JsonPrimitive(true))
    if (mealCurtailed) put("meal_curtailed", JsonPrimitive(true))
    if (camera) put("camera", JsonPrimitive(true))
    if (weekly) put("weekly", JsonPrimitive(true))
    incrementMinutes?.let { put("increment", JsonPrimitive(it)) }
    bdrMin?.let { put("bdr_min", JsonPrimitive(it)) }
    bdrMax?.let { put("bdr_max", JsonPrimitive(it)) }
}

/**
 * A rate row as the web's `TaxTypesSection` saves it: a numeric `value`, the
 * country name and code for a catalogue rate, and both null on a custom one.
 */
private fun TaxType.toJson(): JsonElement = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("identifier", JsonPrimitive(identifier))
    put("label", JsonPrimitive(label))
    put("value", rate?.let(::JsonPrimitive) ?: JsonPrimitive(value))
    put("country", if (isCustom) JsonNull else JsonPrimitive(country))
    put("country_code", if (isCustom) JsonNull else JsonPrimitive(countryCode.orEmpty()))
    put("is_recoverable", JsonPrimitive(isRecoverable))
    put("nominal", JsonPrimitive(nominal))
}

private fun DayType.toJson(): JsonElement = buildJsonObject {
    put("day_type", JsonPrimitive(dayType))
    put("label", JsonPrimitive(label))
    put("work_min", workMinutes?.let(::JsonPrimitive) ?: JsonNull)
    // Null stays null. Coercing it to zero would turn "no meal break recorded"
    // into "an explicit zero-minute break", which the penalty engine acts on.
    put("meal_break_min", mealBreakMinutes?.let(::JsonPrimitive) ?: JsonNull)
    put("note", JsonPrimitive(note))
}

private fun SchedulePhase.toJson(): JsonElement = buildJsonObject {
    put("start_date", startDate.asDate())
    put("end_date", endDate.asDate())
}

/**
 * The object the service requires, even when there is nothing in it.
 *
 * Always sent — a blank address is `{}`-shaped, not `""`. See [writeVendor].
 */
internal fun VendorAddress.toJson(): JsonElement = buildJsonObject {
    put("line1", JsonPrimitive(line1.trim()))
    put("line2", JsonPrimitive(line2.trim()))
    put("city", JsonPrimitive(city.trim()))
    put("state", JsonPrimitive(state.trim()))
    put("postal_code", JsonPrimitive(postalCode.trim()))
    put("country", JsonPrimitive(country.trim()))
}

internal fun VendorPhone.toJson(): JsonElement = buildJsonObject {
    put("country_code", JsonPrimitive(countryCode.trim()))
    put("number", JsonPrimitive(number.trim()))

}

/**
 * Tags as the web's `normalizeTags` keeps them, read and written alike:
 * trimmed, upper-cased, no blanks, no repeats — "camera" and "CAMERA" are one tag.
 */
internal fun List<String>.normalisedTags(): List<String> =
    map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

/** Blank optional text goes as null: this validator types its fields. */
internal fun String.orNull(): JsonElement = if (isBlank()) JsonNull else JsonPrimitive(this)

/** Epoch millis on the way out, always — the calendar form is read-only tolerance. */
internal fun Long?.asDate(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun List<com.zillit.desktop.feature.accounthub.domain.ApprovalTier>.toJson(): JsonElement =
    buildJsonArray {
        forEach { tier ->
            add(
                buildJsonObject {
                    put("order", JsonPrimitive(tier.order))
                    put(
                        "rules",
                        buildJsonArray {
                            tier.rules.forEach { rule ->
                                add(
                                    buildJsonObject {
                                        // The validator's whole vocabulary is
                                        // `default` and `amount` (the web's
                                        // ApproversModule.jsx:1699; refusal:
                                        // "tiers[0].rules[0].type must be one
                                        // of [default, amount]") — anything
                                        // else loses the save.
                                        // Blank means the picker was never
                                        // touched — the wire's own default.
                                        put("type", JsonPrimitive(rule.type.ifBlank { "default" }))
                                        if (rule.type == "amount") {
                                            put(
                                                "amount_threshold",
                                                JsonPrimitive(rule.amountThreshold ?: 0.0),
                                            )
                                        }
                                        put(
                                            "user_ids",
                                            buildJsonArray {
                                                rule.userIds.forEach { add(JsonPrimitive(it)) }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

/** A refusal that still answers 200 — the envelope, not the transport, says no. */
internal const val HTTP_OK = 200

/** The web's page size for the production's bank list. */
private const val BANKS_PAGE = 200

/** The envelope's refusal over a 200. */
private const val REFUSED = 0
