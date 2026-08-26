package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.AccountHubRepository
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
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
import com.zillit.desktop.feature.accounthub.domain.SortCode
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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

    /** Not on the purchase-order host, despite every caller being a PO screen. */
    private val vendorsBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/vendors"

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

    override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/bank-accounts",
        serializer = ListSerializer(BankAccountDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createBankAccount(account: BankAccount): ZillitResult<BankAccount> =
        writeBank(HttpVerb.Post, "$hubBase/bank-accounts", account)

    override suspend fun updateBankAccount(account: BankAccount): ZillitResult<BankAccount> =
        writeBank(HttpVerb.Patch, "$hubBase/bank-accounts/${account.id}", account)

    override suspend fun deleteBankAccount(id: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "$hubBase/bank-accounts/$id",
            module = RequestModule.ProjectUser,
        ).map { }

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
        return apiClient.request(
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
                        "The server accepted the currencies but stored none of them.",
                    ),
                )
            } else {
                ZillitResult.Success(stored)
            }
        }
    }

    override suspend fun taxTypes(): ZillitResult<List<TaxType>> =
        sliceList("$settingsBase/tax-types", TaxTypeDto.serializer()) { it.toDomain() }

    override suspend fun saveTaxTypes(taxTypes: List<TaxType>): ZillitResult<List<TaxType>> =
        patchSliceList(
            url = "$settingsBase/tax-types",
            body = buildJsonArray { taxTypes.forEach { add(it.toJson()) } },
            element = TaxTypeDto.serializer(),
        ) { it.toDomain() }

    override suspend fun assetTags(): ZillitResult<List<String>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/asset-tags",
        serializer = valueList(String.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.orEmpty().filter(String::isNotBlank) }

    override suspend fun saveAssetTags(tags: List<String>): ZillitResult<List<String>> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$settingsBase/asset-tags",
            serializer = valueList(String.serializer()),
            module = RequestModule.ProjectUser,
            body = buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } },
        ).map { it.value.orEmpty() }

    override suspend fun projectBudget(): ZillitResult<ProjectBudget> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$settingsBase/project-budget",
        serializer = ValueDto.serializer(ProjectBudgetDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value?.toDomain() ?: ProjectBudget() }

    override suspend fun saveProjectBudget(budget: ProjectBudget): ZillitResult<ProjectBudget> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$settingsBase/project-budget",
            serializer = ValueDto.serializer(ProjectBudgetDto.serializer()),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("amount", budget.amount?.let(::JsonPrimitive) ?: JsonNull)
                put("currency", JsonPrimitive(budget.currency))
            },
        ).map { it.value?.toDomain() ?: budget }

    override suspend fun payrollBureaus(): ZillitResult<List<PayrollBureau>> =
        sliceList("$settingsBase/payroll-bureau", PayrollBureauDto.serializer()) { it.toDomain() }

    override suspend fun savePayrollBureaus(
        bureaus: List<PayrollBureau>,
    ): ZillitResult<List<PayrollBureau>> = patchSliceList(
        url = "$settingsBase/payroll-bureau",
        body = buildJsonArray {
            bureaus.forEach { bureau ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(bureau.id))
                        put("title", JsonPrimitive(bureau.title))
                        put("description", JsonPrimitive(bureau.description))
                    },
                )
            }
        },
        element = PayrollBureauDto.serializer(),
    ) { it.toDomain() }

    override suspend fun dealConditions(): ZillitResult<List<DealCondition>> = sliceList(
        "$settingsBase/standard-deal-conditions",
        DealConditionDto.serializer(),
    ) { it.toDomain() }

    override suspend fun saveDealConditions(
        conditions: List<DealCondition>,
    ): ZillitResult<List<DealCondition>> = patchSliceList(
        url = "$settingsBase/standard-deal-conditions",
        body = buildJsonArray {
            conditions.forEachIndexed { index, condition ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(condition.id))
                        // Renumbered from position: the clause order is what the
                        // list shows, and a stale `order` field would reorder the
                        // deal on the next read.
                        put("order", JsonPrimitive(index + 1))
                        put("condition", JsonPrimitive(condition.condition))
                    },
                )
            }
        },
        element = DealConditionDto.serializer(),
    ) { it.toDomain() }

    override suspend fun productionSchedule(): ZillitResult<ProductionSchedule> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$settingsBase/production-schedule",
            serializer = ValueDto.serializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
        ).map { it.value.toProductionSchedule() }

    override suspend fun saveProductionSchedule(
        schedule: ProductionSchedule,
    ): ZillitResult<ProductionSchedule> = apiClient.request(
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
    ): ZillitResult<PayrollDefaults> = apiClient.request(
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

    // -- chart of accounts --------------------------------------------------

    override suspend fun accounts(activeOnly: Boolean): ZillitResult<List<CoaAccount>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$hubBase/chart-of-accounts",
            serializer = ListSerializer(CoaAccountDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("active_only" to if (activeOnly) "true" else null),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createAccount(account: NewAccount): ZillitResult<CoaAccount> =
        writeAccount(
            verb = HttpVerb.Post,
            url = "$hubBase/chart-of-accounts",
            body = buildJsonObject {
                put("code", JsonPrimitive(account.code.trim()))
                put("name", JsonPrimitive(account.name.trim()))
                put("line_type", JsonPrimitive(account.lineType.wire))
                put("cost_type", JsonPrimitive(account.costType.wire))
                // The immediate parent only. The server walks up from it to fill
                // the rest of the breadcrumb, and sending a partial breadcrumb
                // here would have it disagree with the ancestors.
                put("parent_id", account.parentId?.let(::JsonPrimitive) ?: JsonNull)
                put("posting_box", JsonPrimitive(account.isPosting))
            },
        )

    override suspend fun updateAccount(
        id: String,
        name: String,
        costType: CoaCostType,
        isActive: Boolean,
        isPosting: Boolean,
    ): ZillitResult<CoaAccount> = writeAccount(
        verb = HttpVerb.Patch,
        url = "$hubBase/chart-of-accounts/$id",
        // Neither the code nor the line type is sent. Both are immutable after
        // create — changing them would cascade through every descendant's
        // breadcrumb, which the server does not do in place.
        body = buildJsonObject {
            put("name", JsonPrimitive(name.trim()))
            put("cost_type", JsonPrimitive(costType.wire))
            put("is_active", JsonPrimitive(isActive))
            put("posting_box", JsonPrimitive(isPosting))
        },
    )

    override suspend fun deactivateAccount(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/chart-of-accounts/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun trackingSets(): ZillitResult<List<TrackingSet>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/tracking-sets",
        serializer = ListSerializer(TrackingSetDto.serializer()),
        module = RequestModule.ProjectUser,
        // One round trip rather than one per set: the screen always draws the
        // codes under their set, so fetching sets alone is never enough.
        queryParameters = mapOf("include_nodes" to "true"),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    // -- vendors ------------------------------------------------------------

    override suspend fun vendors(search: String): ZillitResult<List<Vendor>> = apiClient.request(
        verb = HttpVerb.Get,
        url = vendorsBase,
        serializer = ListSerializer(VendorDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("search" to search.trim().takeIf { it.isNotEmpty() }),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createVendor(vendor: NewVendor): ZillitResult<Vendor> =
        writeVendor(HttpVerb.Post, vendorsBase, vendor)

    override suspend fun updateVendor(id: String, vendor: NewVendor): ZillitResult<Vendor> =
        writeVendor(HttpVerb.Patch, "$vendorsBase/$id", vendor)

    override suspend fun verifyVendor(id: String): ZillitResult<Vendor> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$vendorsBase/$id/verify",
        serializer = VendorDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toDomain() ?: Vendor(id = id, verified = true) }

    override suspend fun deleteVendor(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$vendorsBase/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun vendorHistory(id: String): ZillitResult<List<VendorChange>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$vendorsBase/$id/history",
            serializer = ListSerializer(VendorChangeDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            // Newest first regardless of what the server returned. The same
            // assumption cost Document Distribution a History screen whose top
            // row was a fortnight old.
            rows.mapNotNull { it.toDomain() }.sortedByDescending { it.at ?: Long.MIN_VALUE }
        }

    // -- approvals ----------------------------------------------------------

    override suspend fun approvalConfigs(module: ApprovalModule): ZillitResult<List<ApprovalConfig>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$hubBase/approval-tiers",
            serializer = ListSerializer(ApprovalConfigDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("module" to module.wire),
        ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveApprovalConfig(config: ApprovalConfig): ZillitResult<ApprovalConfig> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$hubBase/approval-tiers",
            serializer = ApprovalConfigDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("module" to config.module.wire),
            body = buildJsonObject {
                if (config.id.isNotBlank()) put("id", JsonPrimitive(config.id))
                put("scope", JsonPrimitive(config.scope.wire))
                if (config.scope == ApprovalScope.Department) {
                    put("department_id", config.departmentId?.let(::JsonPrimitive) ?: JsonNull)
                }
                put("tiers", config.tiers.toJson())
            },
        ).map { it.toDomain() }

    override suspend fun deleteApprovalConfig(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/approval-tiers/$id",
        module = RequestModule.ProjectUser,
    ).map { }

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

    private suspend fun <D, T> patchSliceList(
        url: String,
        body: JsonArray,
        element: KSerializer<D>,
        toDomain: (D) -> T?,
    ): ZillitResult<List<T>> = apiClient.request(
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
        body = buildJsonObject {
            put("name", JsonPrimitive(account.name.trim()))
            put("account_holder_name", JsonPrimitive(account.accountHolderName.trim()))
            put("entity_id", account.entityId?.let(::JsonPrimitive) ?: JsonNull)
            put("entity_type", JsonPrimitive(account.entityType))
            put("account_number", JsonPrimitive(account.accountNumber.trim()))
            // Digits only. The stored form is canonical and the hyphens are a
            // display convention; persisting the mask would make two accounts
            // with the same sort code compare unequal.
            put("sort_code", JsonPrimitive(SortCode.digits(account.sortCode)))
            put("swift_code", JsonPrimitive(account.swiftCode.trim()))
            put("iban_number", JsonPrimitive(account.ibanNumber.trim()))
            put("nominal_code", JsonPrimitive(account.nominalCode.trim()))
            put("cheque_number", JsonPrimitive(account.chequeNumber.trim()))
            put("wire_number", JsonPrimitive(account.wireNumber.trim()))
        },
    ).map { it.toDomain() ?: account }

    private suspend fun writeAccount(
        verb: HttpVerb,
        url: String,
        body: JsonElement,
    ): ZillitResult<CoaAccount> = apiClient.request(
        verb = verb,
        url = url,
        serializer = CoaAccountDto.serializer(),
        module = RequestModule.ProjectUser,
        body = body,
    ).map { it.toDomain() ?: CoaAccount(id = "") }

    /**
     * Creates or updates a vendor.
     *
     * ## Shapes the service insists on
     *
     * Three corrections, each learned from a 400 on dev (2026-08-12):
     *
     *  - `address` is an **object**, always — `"address" must be of type
     *    object`. An empty string is refused even when there is no address.
     *  - `phone` is `{ country_code, number }` or **null**. An empty pair is
     *    truthy downstream and renders as a bare dial code.
     *  - the tax field is **`vat_number`**, not the `tax_number` its label
     *    suggests.
     *
     * Optional text goes as null rather than `""` for the same reason the
     * address does: this validator types its fields.
     */
    private suspend fun writeVendor(
        verb: HttpVerb,
        url: String,
        vendor: NewVendor,
    ): ZillitResult<Vendor> = apiClient.request(
        verb = verb,
        url = url,
        serializer = VendorDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("name", JsonPrimitive(vendor.name.trim()))
            put("email", vendor.email.trim().orNull())
            put("contact_person", vendor.contactPerson.trim().orNull())
            put("phone", vendor.phone()?.toJson() ?: JsonNull)
            put("address", vendor.address.toJson())
            put("vat_number", vendor.vatNumber.trim().orNull())
            put("department_id", vendor.departmentId?.let(::JsonPrimitive) ?: JsonNull)
            put("currency", vendor.currencyCode.trim().orNull())
        },
    ).map { it.toDomain() ?: Vendor(id = "", name = vendor.name) }
}

// -- outgoing shapes --------------------------------------------------------

private fun Company.toJson(): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name.trim()))
    put("country", JsonPrimitive(country))
    put("country_code", JsonPrimitive(countryCode))
    put("bank_ids", buildJsonArray { bankIds.forEach { add(JsonPrimitive(it)) } })
    put("tax_credits", buildJsonArray { taxCredits.forEach { add(JsonPrimitive(it)) } })
    // No currency: it is derived from the linked banks and the backend owns the
    // canonical value. Sending one from here would invent it.
}

private fun TaxType.toJson(): JsonElement = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("identifier", JsonPrimitive(identifier))
    put("label", JsonPrimitive(label))
    put("value", JsonPrimitive(value))
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
private fun VendorAddress.toJson(): JsonElement = buildJsonObject {
    put("line1", JsonPrimitive(line1.trim()))
    put("line2", JsonPrimitive(line2.trim()))
    put("city", JsonPrimitive(city.trim()))
    put("state", JsonPrimitive(state.trim()))
    put("postal_code", JsonPrimitive(postalCode.trim()))
    put("country", JsonPrimitive(country.trim()))
}

private fun VendorPhone.toJson(): JsonElement = buildJsonObject {
    put("country_code", JsonPrimitive(countryCode.trim()))
    put("number", JsonPrimitive(number.trim()))
}

/** Blank optional text goes as null: this validator types its fields. */
private fun String.orNull(): JsonElement = if (isBlank()) JsonNull else JsonPrimitive(this)

/** Epoch millis on the way out, always — the calendar form is read-only tolerance. */
private fun Long?.asDate(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

private fun List<com.zillit.desktop.feature.accounthub.domain.ApprovalTier>.toJson(): JsonElement =
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
