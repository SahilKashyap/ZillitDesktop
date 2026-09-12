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
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The vendor register and the approval chains — the two hub resources with a
 * life of their own beside the settings slices.
 *
 * Split out of [AccountHubRepositoryImpl] the way [HubReportSource] and
 * [HubSetupSource] were: the repository mirrors an interface with one method
 * per slice, and these carried enough verbs of their own to make it the
 * largest class in the module. The repository still owns the interface; this
 * is where the calls live.
 */
@Suppress("TooManyFunctions") // Two registers, every verb each.
internal class HubRegisterSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) {
    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    /** Not on the purchase-order host, despite every caller being a PO screen. */
    private val vendorsBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/vendors"

    /** The core access service, which knows who may view a tool. */
    private val accessBase = "${config.apiV2(ZillitService.Core)}access"

    // -- vendors ------------------------------------------------------------

    suspend fun vendors(search: String): ZillitResult<List<Vendor>> = apiClient.request(
        verb = HttpVerb.Get,
        url = vendorsBase,
        serializer = ListSerializer(VendorDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("search" to search.trim().takeIf { it.isNotEmpty() }),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun createVendor(vendor: NewVendor): ZillitResult<Vendor> =
        writeVendor(HttpVerb.Post, vendorsBase, vendor)

    suspend fun updateVendor(id: String, vendor: NewVendor): ZillitResult<Vendor> =
        writeVendor(HttpVerb.Patch, "$vendorsBase/$id", vendor)

    suspend fun verifyVendor(id: String): ZillitResult<Vendor> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$vendorsBase/$id/verify",
        serializer = VendorDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toDomain() ?: Vendor(id = id, verified = true) }

    /**
     * A refusal here arrives as a 200. The server answers `{status: 0, message:
     * "vendor_in_use_by_purchase_orders"}` for a vendor a purchase order still
     * names, and reporting that as a deletion leaves the row on screen with a
     * success notice against it (ZL-21088; Android `VendorRepository.kt:273`).
     */
    suspend fun deleteVendor(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$vendorsBase/$id",
        module = RequestModule.ProjectUser,
    ).flatMap { envelope ->
        if (envelope.status == 0) {
            ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
        } else {
            ZillitResult.Success(Unit)
        }
    }

    suspend fun vendorHistory(id: String): ZillitResult<List<VendorChange>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$vendorsBase/$id/history",
            serializer = ListSerializer(VendorChangeDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            // Newest first regardless of what the server returned. The same
            // assumption cost Document Distribution a History screen whose top
            // row was a fortnight old.
            rows.mapIndexed { index, row -> row.toDomain(index) }.sortedByDescending { it.at ?: Long.MIN_VALUE }
        }

    // -- approvals ----------------------------------------------------------

    suspend fun approvalConfigs(module: ApprovalModule): ZillitResult<List<ApprovalConfig>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$hubBase/approval-tiers",
            serializer = ListSerializer(ApprovalConfigDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("module" to module.wire),
        ).map { rows -> rows.map { it.toDomain() } }

    suspend fun approvalSummary(): ZillitResult<Map<ApprovalModule, Boolean>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$hubBase/approval-tiers/summary",
            serializer = ListSerializer(ApprovalSummaryDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            // A row naming a module this client does not model is dropped, not
            // guessed at; `from()` would fold every one of them onto POs.
            rows.mapNotNull { row ->
                ApprovalModule.entries.firstOrNull { it.wire == row.module?.lowercase() }
                    ?.let { it to (row.configured == true) }
            }.toMap()
        }

    suspend fun saveApprovalConfig(config: ApprovalConfig): ZillitResult<ApprovalConfig> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$hubBase/approval-tiers",
            serializer = ApprovalConfigDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("module" to config.module.wire),
            // The web's upsert body, and nothing else: the server keys a chain
            // by module, scope and department, and the web has never sent an
            // `id` — so one here is at best ignored and at worst refused.
            body = buildJsonObject {
                put("scope", JsonPrimitive(config.scope.wire))
                if (config.scope == ApprovalScope.Department) {
                    put("department_id", config.departmentId?.let(::JsonPrimitive) ?: JsonNull)
                }
                put("tiers", config.tiers.toJson())
            },
        ).map { it.toDomain() }

    suspend fun deleteApprovalConfig(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$hubBase/approval-tiers/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    /**
     * `GET v2/access/users?toolIdentifier=…&viewing_access=true` — the web's
     * `fetchuserapproveringrights({ view_access: true })`. The answer is an id
     * array; ids that arrive as objects are read by their `user_id`.
     */
    suspend fun approverCandidateIds(toolIdentifier: String): ZillitResult<Set<String>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$accessBase/users",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("toolIdentifier" to toolIdentifier, "viewing_access" to "true"),
    ).map { payload ->
        val data = (payload as? JsonObject)?.get("data") ?: payload
        (data as? JsonArray).orEmpty().mapNotNullTo(mutableSetOf()) { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> ((element["user_id"] ?: element["_id"]) as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }


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
            // The web's `buildPayload` bank and classification keys, null when blank.
            put("bank_name", vendor.bankName.trim().orNull())
            put("account_holder_name", vendor.accountHolderName.trim().orNull())
            put("account_number", vendor.accountNumber.trim().orNull())
            put("sort_code", vendor.sortCode.trim().orNull())
            put("iban_code", vendor.ibanCode.trim().orNull())
            put("swift_code", vendor.swiftCode.trim().orNull())
            // Rows with a title persist even without a value — a field committed
            // via "Done" before it is filled must not be silently dropped.
            put("additional_info", BankAccounts.persistable(vendor.additionalInfo).toJson("label"))
            put("vendor_type", vendor.vendorType.trim().orNull())
            put("company_type", vendor.companyType.trim().orNull())
            put("terms", vendor.terms.trim().orNull())
            put("default_code", vendor.defaultCode.trim().orNull())
            put("compliance", vendor.compliance.trim().orNull())
        },
    ).map { it.toDomain() ?: Vendor(id = "", name = vendor.name) }
}
