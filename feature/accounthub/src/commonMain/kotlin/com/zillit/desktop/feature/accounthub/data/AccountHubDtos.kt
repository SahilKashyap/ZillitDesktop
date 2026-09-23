// One converter per wire shape, plus the readers for the fields this backend
// sends more than one way. Kept in one file because splitting them separates a
// shape from the tolerance it needs — the pairing that makes either readable.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.BankDetail
import com.zillit.desktop.feature.accounthub.domain.BankDetailType
import com.zillit.desktop.feature.accounthub.domain.CashCloseDashboard
import com.zillit.desktop.feature.accounthub.domain.ChecklistItem
import com.zillit.desktop.feature.accounthub.domain.CommitmentWeek
import com.zillit.desktop.feature.accounthub.domain.CustomDay
import com.zillit.desktop.feature.accounthub.domain.HeatCell
import com.zillit.desktop.feature.accounthub.domain.HeatRow
import com.zillit.desktop.feature.accounthub.domain.JournalDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.PayrollGroup
import com.zillit.desktop.feature.accounthub.domain.ReconRow
import com.zillit.desktop.feature.accounthub.domain.WaterfallBar
import com.zillit.desktop.feature.accounthub.domain.PoDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.PoSplitType
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.InvoiceAlert
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.RunAuthorisationTier
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.PayDayKind
import com.zillit.desktop.feature.accounthub.domain.PayRateBasis
import com.zillit.desktop.feature.accounthub.domain.PayRateType
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.ParsedCode
import com.zillit.desktop.feature.accounthub.domain.ParsedSection
import com.zillit.desktop.feature.accounthub.domain.ParsedUncoded
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceRow
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.EntitlementRow
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.PayApplyMode
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.PayrollDefaults
import com.zillit.desktop.feature.accounthub.domain.ProductionSchedule
import com.zillit.desktop.feature.accounthub.domain.ProjectBudget
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorAddress
import com.zillit.desktop.feature.accounthub.domain.VendorChange
import com.zillit.desktop.feature.accounthub.domain.VendorPhone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.zillit.desktop.feature.accounthub.domain.AssetFilters

/**
 * The Account Hub's wire shapes.
 *
 * ## Every field is nullable
 *
 * Not defensiveness — measurement. The same exercise on Drive and Document
 * Distribution turned up eight fields that decoded cleanly and rendered blank
 * because the name was wrong, and a non-null field would have failed the whole
 * page instead of one cell. Nullable-plus-fallback keeps a wrong guess visible
 * as one empty column rather than as a dead screen.
 *
 * ## The `value` envelope
 *
 * Every per-slice project-settings route answers `{ data: { value: … } }` and
 * takes its PATCH body **unwrapped**. Reading `data.<slice>` instead of
 * `data.value` fails silently — an empty catalogue and no error — which is how
 * the allowances slice was lost once already.
 */

/** The one-key wrapper every project-settings slice answers with. */
@Serializable
data class ValueDto<T>(@SerialName("value") val value: T? = null)

// -- production setup -------------------------------------------------------

@Serializable
data class CompanyDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("bank_ids") val bankIds: List<String>? = null,
    @SerialName("tax_credits") val taxCredits: List<String>? = null,
    @SerialName("legal_name") val legalName: String? = null,
    @SerialName("uk") val uk: CompanyUkDto? = null,
) {
    fun toDomain(): Company? {
        val resolved = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return Company(
            id = resolved,
            name = name.orEmpty(),
            country = country.orEmpty(),
            countryCode = countryCode.orEmpty(),
            bankIds = bankIds.orEmpty().filter { it.isNotBlank() },
            taxCredits = taxCredits.orEmpty().filter { it.isNotBlank() },
            legalName = legalName.orEmpty(),
            ukPayeRef = uk?.payeRef.orEmpty(),
            ukAccountsOfficeRef = uk?.accountsOfficeRef.orEmpty(),
            ukPensionProvider = uk?.pensionProvider.orEmpty(),
            ukPensionSchemeRef = uk?.pensionSchemeRef.orEmpty(),
        )
    }
}

/**
 * The UK payroll block nested under `uk` on a company.
 *
 * Every key, every time: a key omitted from the block is **cleared**
 * server-side, so a client that did not know about the pension pair wiped
 * both on each save.
 */
@Serializable
data class CompanyUkDto(
    @SerialName("paye_ref") val payeRef: String? = null,
    @SerialName("accounts_office_ref") val accountsOfficeRef: String? = null,
    @SerialName("pension_provider") val pensionProvider: String? = null,
    @SerialName("pension_scheme_ref") val pensionSchemeRef: String? = null,
)

/**
 * A bank row.
 *
 * `currency` is a nested object, not a code — a company's currency is read off
 * its linked banks, so flattening it here is what makes that derivation
 * possible without a second lookup.
 */
@Serializable
data class BankAccountDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("account_holder_name") val accountHolderName: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("swift_code") val swiftCode: String? = null,
    @SerialName("iban_number") val ibanNumber: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("cheque_number") val chequeNumber: String? = null,
    @SerialName("wire_number") val wireNumber: String? = null,
    @SerialName("currency") val currency: CurrencyDto? = null,
    @SerialName("ap_clearance_nominal_code") val apClearanceNominalCode: String? = null,
    /** An array of typed rows, or that array JSON-encoded into a string. */
    @SerialName("additional_details") val additionalDetails: JsonElement? = null,
) {
    fun toDomain(): BankAccount? {
        val resolved = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return BankAccount(
            id = resolved,
            name = name.orEmpty(),
            accountHolderName = accountHolderName.orEmpty(),
            entityId = entityId?.takeIf { it.isNotBlank() },
            entityType = entityType.orEmpty().ifBlank { BankAccount.PRODUCTION },
            accountNumber = accountNumber.orEmpty(),
            sortCode = sortCode.orEmpty(),
            swiftCode = swiftCode.orEmpty(),
            ibanNumber = ibanNumber.orEmpty(),
            nominalCode = nominalCode.orEmpty(),
            chequeNumber = chequeNumber.orEmpty(),
            wireNumber = wireNumber.orEmpty(),
            currencyCode = currency?.code.orEmpty(),
            currencySymbol = currency?.symbol.orEmpty(),
            currencyName = currency?.name.orEmpty(),
            apClearanceNominalCode = apClearanceNominalCode.orEmpty(),
            additionalDetails = additionalDetails.toBankDetails(),
        )
    }
}

/**
 * Typed extra rows — `[{ field, value, field_type }]` — which arrive as an
 * array, or as that array encoded into a string on rows saved by older
 * clients. Vendors spell the title `label`; both are read.
 */
internal fun JsonElement?.toBankDetails(): List<BankDetail> = when (this) {
    is JsonArray -> mapNotNull { row ->
        (row as? JsonObject)?.let {
            BankDetail(
                title = it.str("field").ifBlank { it.str("label") },
                value = it.str("value"),
                fieldType = BankDetailType.from(it.str("field_type").ifBlank { null }),
            )
        }
    }.filter { it.isTitled }
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
        runCatching { accountHubJson.parseToJsonElement(text) }.getOrNull()
            ?.takeIf { it is JsonArray }
            ?.toBankDetails()
    }.orEmpty()
    else -> emptyList()
}

@Serializable
data class CurrencyDto(
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("symbol") val symbol: String? = null,
    @SerialName("country") val country: String? = null,
    /** Rate against the project default. Named `exr` on the wire. */
    @SerialName("exr") val rate: Double? = null,
) {
    fun toDomain(): ProjectCurrency? = code?.takeIf { it.isNotBlank() }?.let {
        ProjectCurrency(
            code = it,
            name = name.orEmpty(),
            symbol = symbol.orEmpty(),
            rate = rate,
            country = country.orEmpty(),
        )
    }
}

@Serializable
data class TaxTypeDto(
    @SerialName("type") val type: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("label") val label: String? = null,
    /** Sent as a string, echoed as a number — read either way. */
    @SerialName("value") val value: JsonPrimitive? = null,
    @SerialName("is_recoverable") val isRecoverable: Boolean? = null,
    @SerialName("nominal") val nominal: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
) {
    fun toDomain(): TaxType = TaxType(
        type = type.orEmpty(),
        identifier = identifier.orEmpty(),
        label = label.orEmpty(),
        value = value?.contentOrNull.orEmpty().asRateText(),
        // Absence is false. Only an explicit true marks a rate reclaimable —
        // defaulting the other way would let input tax be claimed that is not.
        isRecoverable = isRecoverable == true,
        nominal = nominal.orEmpty(),
        country = country.orEmpty(),
        storedCountryCode = countryCode?.takeIf { it.isNotBlank() },
    )
}

/** `20.0` reads back as `20`; anything that is not a number is kept as typed. */
private fun String.asRateText(): String {
    val number = toDoubleOrNull() ?: return this
    return if (number == number.toLong().toDouble()) number.toLong().toString() else this
}

@Serializable
data class CountryTaxesDto(
    @SerialName("country") val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("taxes") val taxes: List<TaxTypeDto>? = null,
) {
    fun toDomain(): CountryTaxes? {
        val code = countryCode?.takeIf { it.isNotBlank() } ?: return null
        return CountryTaxes(
            country = country.orEmpty(),
            countryCode = code,
            // Re-keyed to `{CC}_{sourceId}` on the way in, so a rate ticked from
            // the catalogue round-trips through a save and back.
            taxes = taxes.orEmpty().map { row ->
                row.toDomain().let { tax ->
                    tax.copy(
                        identifier = TaxType.keyFor(code, tax.identifier),
                        country = country.orEmpty(),
                        storedCountryCode = code,
                    )
                }
            },
        )
    }
}

@Serializable
data class PayrollBureauDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
) {
    fun toDomain(): PayrollBureau? = id?.takeIf { it.isNotBlank() }?.let {
        PayrollBureau(id = it, title = title.orEmpty(), description = description.orEmpty())
    }
}

@Serializable
data class DealConditionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("order") val order: Int? = null,
    @SerialName("condition") val condition: String? = null,
) {
    fun toDomain(): DealCondition? = id?.takeIf { it.isNotBlank() }?.let {
        DealCondition(id = it, order = order ?: 0, condition = condition.orEmpty())
    }
}

/**
 * The clause list, in the three shapes it has been persisted in.
 *
 * `{ order, condition }` is current; `{ id, text }` is the pre-typed-schema
 * row; a bare string is the pre-historic flat array. None of them promises an
 * id, so one is minted per position — the id is local, and the order is
 * rebuilt from position on save (the web's `normalize`/`denormalize`).
 */
internal fun JsonElement?.toDealConditions(): List<DealCondition> {
    val rows = this as? JsonArray ?: return emptyList()
    return rows.mapIndexed { index, row ->
        when (row) {
            is JsonPrimitive -> DealCondition(
                id = "cond-$index",
                order = index + 1,
                condition = row.contentOrNull.orEmpty(),
            )
            is JsonObject -> DealCondition(
                id = row.str("id").ifBlank { "cond-$index" },
                order = (row["order"] as? JsonPrimitive)?.intOrNull ?: (index + 1),
                condition = row.str("condition").ifBlank { row.str("text") },
            )
            else -> DealCondition(id = "cond-$index", order = index + 1)
        }
    }
}

/**
 * The bureau list — an array, or the legacy `{ bureau: [...] }` wrapper — with
 * rows that may or may not carry an id. An id-less row is still a bureau.
 */
internal fun JsonElement?.toPayrollBureaus(): List<PayrollBureau> {
    // The oldest shape, `{ bureau: "Name" }`: one bureau by name, as the web's
    // `normalize` reads it. Dropped, the next save stored an empty list over
    // the production's bureau.
    val legacyName = ((this as? JsonObject)?.get("bureau") as? JsonPrimitive)?.contentOrNull
    if (!legacyName.isNullOrBlank()) return listOf(PayrollBureau(id = "bureau-0", title = legacyName))
    val rows = when (this) {
        is JsonArray -> this
        is JsonObject -> this["bureau"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return rows.mapIndexedNotNull { index, row ->
        val obj = row as? JsonObject ?: return@mapIndexedNotNull null
        PayrollBureau(
            id = obj.str("id").ifBlank { obj.str("_id") }.ifBlank { "bureau-$index" },
            title = obj.str("title").ifBlank { obj.str("name") },
            description = obj.str("description"),
        )
    }
}

@Serializable
data class ProjectBudgetDto(
    @SerialName("amount") val amount: Double? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
) {
    fun toDomain(): ProjectBudget = ProjectBudget(amount = amount, currency = currency.orEmpty())
}

@Serializable
data class PayrollDefaultsDto(
    @SerialName("auto_sync") val autoSync: Boolean? = null,
    @SerialName("notify_payroll") val notifyPayroll: Boolean? = null,
    @SerialName("include_pdf") val includePdf: Boolean? = null,
) {
    /**
     * Seeded server-side to `(true, true, false)` on a fresh project, so those
     * are the fallbacks rather than all-false.
     */
    fun toDomain(): PayrollDefaults = PayrollDefaults(
        autoSync = autoSync ?: true,
        notifyPayroll = notifyPayroll ?: true,
        includePdf = includePdf ?: false,
    )
}

@Serializable
data class DayTypeDto(
    @SerialName("day_type") val dayType: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("work_min") val workMinutes: Int? = null,
    @SerialName("meal_break_min") val mealBreakMinutes: Int? = null,
    @SerialName("note") val note: String? = null,
) {
    fun toDomain(): DayType? = dayType?.takeIf { it.isNotBlank() }?.let {
        DayType(
            dayType = it,
            label = label.orEmpty(),
            workMinutes = workMinutes,
            // Left null rather than defaulted to zero: null is "unspecified"
            // and zero is "no formal break", and the penalty engine treats them
            // differently.
            mealBreakMinutes = mealBreakMinutes,
            note = note.orEmpty(),
        )
    }
}

// -- chart of accounts ------------------------------------------------------

@Serializable
data class CoaAccountDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("line_type") val lineType: String? = null,
    @SerialName("cost_type") val costType: String? = null,
    @SerialName("head_id") val headId: String? = null,
    @SerialName("sec_id") val sectionId: String? = null,
    @SerialName("cat_id") val categoryId: String? = null,
    @SerialName("sub_cat_id") val subCategoryId: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("posting_box") val postingBox: Boolean? = null,
    @SerialName("source") val source: String? = null,
    /**
     * On an edit's answer: how many descendants a change of class reached.
     * An element, because a count that arrives quoted must not fail the save.
     */
    @SerialName("_cascaded_descendants") val cascadedDescendants: JsonElement? = null,
) {
    fun cascadedCount(): Int {
        val primitive = cascadedDescendants as? JsonPrimitive ?: return 0
        return (primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)
    }

    fun toDomain(): CoaAccount? {
        val resolved = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return CoaAccount(
            id = resolved,
            code = code.orEmpty(),
            name = name.orEmpty(),
            // An unrecognised level is filed as a category rather than dropped:
            // a code missing from the chart is a code no line item can pick.
            lineType = CoaLineType.from(lineType) ?: CoaLineType.Category,
            costType = CoaCostType.from(costType),
            headId = headId?.takeIf { it.isNotBlank() },
            sectionId = sectionId?.takeIf { it.isNotBlank() },
            categoryId = categoryId?.takeIf { it.isNotBlank() },
            subCategoryId = subCategoryId?.takeIf { it.isNotBlank() },
            // Both flags are opt-out: absent reads as on.
            isActive = isActive != false,
            isPosting = postingBox != false,
            source = source.orEmpty(),
        )
    }
}

@Serializable
data class TrackingSetDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("prefix") val prefix: String? = null,
    @SerialName("color") val color: String? = null,
    /** `active` is what the route speaks; `is_active` is the older spelling. */
    @SerialName("active") val active: Boolean? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("nodes") val nodes: List<TrackingNodeDto>? = null,
) {
    fun toDomain(): TrackingSet? = (id ?: altId)?.takeIf { it.isNotBlank() }?.let { setId ->
        TrackingSet(
            id = setId,
            name = name.orEmpty(),
            code = code.orEmpty(),
            isActive = (active ?: isActive) != false,
            nodes = nodes.orEmpty().mapNotNull { it.toDomain(setId) },
            prefix = prefix.orEmpty(),
            color = color.orEmpty(),
        )
    }
}

@Serializable
data class TrackingNodeDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("code") val code: String? = null,
    /** The route's `label`; `name` is the older spelling. */
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("active") val active: Boolean? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    /** The layer's own ordering, ahead of the code — the web sorts by it first. */
    @SerialName("sort_order") val sortOrder: JsonElement? = null,
) {
    fun toDomain(setId: String): TrackingNode? = (id ?: altId)?.takeIf { it.isNotBlank() }?.let {
        TrackingNode(
            id = it,
            setId = setId,
            code = code.orEmpty(),
            name = (label ?: name).orEmpty(),
            parentId = parentId?.takeIf { parent -> parent.isNotBlank() },
            isActive = (active ?: isActive) != false,
            description = description.orEmpty(),
            sortOrder = (sortOrder as? JsonPrimitive)?.let { order ->
                order.intOrNull ?: order.contentOrNull?.trim()?.toIntOrNull()
            } ?: 0,
        )
    }
}

// -- vendors ----------------------------------------------------------------

/**
 * A vendor row.
 *
 * [status] is the only signal for verification — there is no boolean on the
 * wire. Reading a `verified` field straight off the row is how every vendor in
 * the web's register once showed as non-verified at the same time.
 *
 * [phone] and [address] are [JsonElement] because some routes send them as
 * objects and others as JSON encoded into a string.
 */
@Serializable
data class VendorDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("contact_person") val contactPerson: String? = null,
    @SerialName("contactPerson") val contactPersonCamel: String? = null,
    @SerialName("phone") val phone: JsonElement? = null,
    @SerialName("address") val address: JsonElement? = null,
    @SerialName("department_id") val departmentId: String? = null,
    /** `vat_number`, not `tax_number` — the obvious name is the wrong one. */
    @SerialName("vat_number") val vatNumber: String? = null,
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("verified") val verified: Boolean? = null,
    @SerialName("bank_account_id") val bankAccountId: String? = null,
    @SerialName("added_by") val addedBy: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("verified_by") val verifiedBy: String? = null,
    @SerialName("verified_at") val verifiedAt: JsonPrimitive? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("updated_at") val updatedAt: JsonPrimitive? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("account_holder_name") val accountHolderName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("iban_code") val ibanCode: String? = null,
    @SerialName("iban_number") val ibanNumber: String? = null,
    @SerialName("swift_code") val swiftCode: String? = null,
    @SerialName("additional_info") val additionalInfo: JsonElement? = null,
    @SerialName("bank_id") val bankId: String? = null,
    @SerialName("vendor_type") val vendorType: String? = null,
    @SerialName("company_type") val companyType: String? = null,
    @SerialName("terms") val terms: String? = null,
    @SerialName("default_code") val defaultCode: String? = null,
    @SerialName("compliance") val compliance: JsonElement? = null,
) {
    fun toDomain(): Vendor? {
        val resolved = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return Vendor(
            id = resolved,
            name = name.orEmpty(),
            email = email.orEmpty(),
            contactPerson = (contactPerson ?: contactPersonCamel).orEmpty(),
            phone = phone.toVendorPhone(),
            address = address.toVendorAddress(),
            departmentId = departmentId?.takeIf { it.isNotBlank() },
            vatNumber = vatNumber.orEmpty(),
            currencyCode = currency.currencyCode(),
            // The boolean when a route sends one; otherwise derived from the
            // status string, which is what most of them send.
            verified = verified ?: status.equals(Vendor.VERIFIED_STATUS, ignoreCase = true),
            bankAccountId = bankAccountId?.takeIf { it.isNotBlank() },
            status = status.orEmpty(),
            addedBy = (addedBy ?: createdBy)?.takeIf { it.isNotBlank() },
            verifiedBy = verifiedBy?.takeIf { it.isNotBlank() },
            verifiedAtMillis = verifiedAt.epochMillis(),
            updatedBy = updatedBy?.takeIf { it.isNotBlank() },
            createdAtMillis = createdAt.epochMillis(),
            updatedAtMillis = updatedAt.epochMillis(),
            bankName = bankName.orEmpty(),
            accountHolderName = accountHolderName.orEmpty(),
            accountNumber = accountNumber.orEmpty(),
            sortCode = sortCode.orEmpty(),
            ibanCode = (ibanCode ?: ibanNumber).orEmpty(),
            swiftCode = swiftCode.orEmpty(),
            additionalInfo = additionalInfo.toBankDetails(),
            bankId = bankId?.takeIf { it.isNotBlank() },
            vendorType = vendorType.orEmpty(),
            companyType = companyType.orEmpty(),
            terms = terms.orEmpty(),
            defaultCode = defaultCode.orEmpty(),
            compliance = (compliance as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }
}

@Serializable
data class VendorChangeDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("created") val created: JsonPrimitive? = null,
    /** The web's history rows: `action`, `action_by`, `action_at`, `note`. */
    @SerialName("action_at") val actionAt: JsonPrimitive? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("action_by_name") val actionByName: String? = null,
    @SerialName("action_by") val actionBy: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("note") val note: String? = null,
) {
    fun toDomain(index: Int = 0): VendorChange {
        val resolved = (id ?: altId)?.takeIf { it.isNotBlank() } ?: "change-$index"
        return VendorChange(
            id = resolved,
            at = (createdAt ?: created ?: actionAt).epochMillis(),
            byName = (userName ?: actionByName).orEmpty(),
            byId = actionBy.orEmpty(),
            // The web titles the row from `action` ("vendor_verified" →
            // "Vendor Verified") and never prints `message`; that stays the
            // fallback for a row with no action.
            summary = action?.takeIf { it.isNotBlank() }?.let(::titleCaseAction) ?: message.orEmpty(),
            note = note.orEmpty(),
        )
    }
}

/** The web's `fmtAction`: underscores to spaces, each word capitalised. */
internal fun titleCaseAction(action: String): String =
    action.replace('_', ' ').split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

// -- approvals --------------------------------------------------------------

/**
 * One row of `GET approval-tiers/summary`.
 *
 * The server also sends `label`, `config_count`, `scopes` and
 * `approver_count`; only the two read here are needed to say whether a module
 * has a chain, and the rest would be a second, staler copy of what the tab
 * itself loads.
 */
@Serializable
data class ApprovalSummaryDto(
    val module: String? = null,
    val configured: Boolean? = null,
)

@Serializable
data class ApprovalConfigDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("module") val module: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("tiers") val tiers: List<ApprovalTierDto>? = null,
) {
    fun toDomain(): ApprovalConfig = ApprovalConfig(
        id = (id ?: altId).orEmpty(),
        module = ApprovalModule.from(module),
        scope = ApprovalScope.from(scope),
        departmentId = departmentId?.takeIf { it.isNotBlank() },
        departmentName = departmentName.orEmpty(),
        // Ordered by the server's `order`, not by array position: a config
        // saved after a level was removed can arrive with the two disagreeing,
        // and the chain then runs in the wrong order.
        tiers = tiers.orEmpty().map { it.toDomain() }.sortedBy { it.order },
    )
}

@Serializable
data class ApprovalTierDto(
    @SerialName("order") val order: Int? = null,
    @SerialName("rules") val rules: List<ApprovalRuleDto>? = null,
) {
    fun toDomain(): ApprovalTier = ApprovalTier(
        order = order ?: 0,
        rules = rules.orEmpty().map { it.toDomain() },
    )
}

@Serializable
data class ApprovalRuleDto(
    @SerialName("type") val type: String? = null,
    @SerialName("user_ids") val userIds: List<String>? = null,
    @SerialName("amount_threshold") val amountThreshold: Double? = null,
) {
    fun toDomain(): ApprovalRule = ApprovalRule(
        type = type.orEmpty(),
        userIds = userIds.orEmpty().filter { it.isNotBlank() },
        amountThreshold = amountThreshold,
    )
}

// -- shape-tolerant readers -------------------------------------------------

/**
 * A timestamp that is sometimes a number and sometimes a string.
 *
 * Both appear on this backend, occasionally on the same field across two
 * routes. Declaring it `Long?` decodes one and fails the whole row on the
 * other.
 */
internal fun JsonPrimitive?.epochMillis(): Long? =
    this?.longOrNull ?: this?.contentOrNull?.toLongOrNull()

/**
 * An address, which arrives as an object, as JSON encoded into a string, or
 * (on older rows) as free text.
 *
 * All three are in play across these routes, so all three are read. The write
 * side always sends the object — see [VendorAddress].
 */
internal fun JsonElement?.toVendorAddress(): VendorAddress = when (this) {
    is JsonObject -> VendorAddress(
        line1 = str("line1"),
        line2 = str("line2"),
        city = str("city"),
        state = str("state"),
        postalCode = str("postal_code").ifBlank { str("postalCode") },
        country = str("country"),
    )
    is JsonPrimitive -> contentOrNull.orEmpty().let { text ->
        // A JSON object that arrived as a string, or genuinely free text. The
        // former is re-read; the latter becomes the first line rather than
        // being dropped.
        runCatching { accountHubJson.parseToJsonElement(text) }.getOrNull()
            ?.takeIf { it is JsonObject }
            ?.toVendorAddress()
            ?: VendorAddress(line1 = text)
    }
    else -> VendorAddress()
}

/** A phone, as `{ country_code, number }`, a string, or absent. */
internal fun JsonElement?.toVendorPhone(): VendorPhone? = when (this) {
    is JsonObject -> VendorPhone(
        countryCode = str("country_code").ifBlank { str("countryCode") },
        number = str("number"),
    ).takeIf { !it.isEmpty }
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
        runCatching { accountHubJson.parseToJsonElement(text) }.getOrNull()
            ?.takeIf { it is JsonObject }
            ?.toVendorPhone()
            ?: VendorPhone(number = text)
    }
    else -> null
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** A currency that arrives as a bare code on some routes and an object on others. */
internal fun JsonElement?.currencyCode(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonObject -> (this["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    else -> ""
}

/**
 * The currency slice, in any of the three shapes it has been persisted in.
 *
 * Current is `{ currencies: [{ code, name, symbol, exr }], default }`. Two
 * older ones are still on live projects — `{ codes: [...], default }` and a
 * bare `string[]` — and a project that has not been re-saved since is still
 * serving them. Failing to read those would show an accountant an empty
 * currency list for a production that plainly has currencies.
 */
internal fun JsonElement?.toCurrencySettings(): CurrencySettings = when (this) {
    is JsonArray -> CurrencySettings(currencies = codesToCurrencies(this))
    is JsonObject -> {
        val rows = this["currencies"] as? JsonArray
        val legacyCodes = this["codes"] as? JsonArray
        CurrencySettings(
            currencies = when {
                rows != null -> rows.mapNotNull { row ->
                    runCatching {
                        accountHubJson.decodeFromJsonElement(CurrencyDto.serializer(), row)
                    }.getOrNull()?.toDomain()
                }
                legacyCodes != null -> codesToCurrencies(legacyCodes)
                else -> emptyList()
            },
            defaultCode = (this["default"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() },
        )
    }
    else -> CurrencySettings()
}

private fun codesToCurrencies(codes: JsonArray): List<ProjectCurrency> =
    codes.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
        .map { ProjectCurrency(code = it) }

/**
 * The production schedule, whose dates are epoch millis on write and either
 * that or `YYYY-MM-DD` on read.
 */
internal fun JsonElement?.toProductionSchedule(): ProductionSchedule {
    val root = this as? JsonObject ?: return ProductionSchedule()
    return ProductionSchedule(
        startDate = root.dateAt("start_date"),
        endDate = root.dateAt("end_date"),
        prep = root.phaseAt("prep"),
        shoot = root.phaseAt("shoot"),
        wrap = root.phaseAt("wrap"),
        customDays = (root["custom_days"] as? JsonArray).orEmpty().mapIndexedNotNull { index, row ->
            (row as? JsonObject)?.let {
                CustomDay(
                    id = "custom-$index",
                    name = it.str("name"),
                    startDate = it.dateAt("start_date"),
                    endDate = it.dateAt("end_date"),
                )
            }
        },
    )
}

private fun JsonObject.phaseAt(key: String): SchedulePhase {
    val phase = this[key] as? JsonObject ?: return SchedulePhase()
    return SchedulePhase(startDate = phase.dateAt("start_date"), endDate = phase.dateAt("end_date"))
}

/**
 * A date that is an epoch number, an epoch string, or `YYYY-MM-DD`.
 *
 * The calendar form is converted rather than rejected: rows written before the
 * change to epoch are still on live projects, and a schedule that silently
 * reads as unset would pre-fill every new deal memo with blank dates.
 */
private fun JsonObject.dateAt(key: String): Long? {
    val raw = (this[key] as? JsonPrimitive) ?: return null
    val text = raw.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    // A number, or a numeric string, before a calendar date: `"1754300000000"`
    // is an epoch, and reading it as a date would land in the year 1754.
    return raw.longOrNull ?: text.toLongOrNull() ?: IsoDate.toEpochMillis(text)
}

/**
 * Lenient by construction.
 *
 * `ignoreUnknownKeys` because this backend adds fields without notice, and a
 * strict reader turns a new column somewhere else into a dead screen here.
 */
internal val accountHubJson = Json { ignoreUnknownKeys = true; isLenient = true }


// -- test seams -------------------------------------------------------------
//
// Decoding a captured response is the only way to prove a field name is right.
// A test that builds a DTO and asserts it round-trips passes just as happily
// when the field is read under the wrong key — which is exactly the bug these
// exist to catch. See AccountHubWireShapeTest.

/** `{ "value": [ … ] }`, the shape every list-valued setup slice answers with. */
internal fun <T> valueList(element: KSerializer<T>): KSerializer<ValueDto<List<T>>> =
    ValueDto.serializer(ListSerializer(element))

internal fun decodeCompanies(json: String): List<Company> =
    accountHubJson.decodeFromString(valueList(CompanyDto.serializer()), json)
        .value.orEmpty().mapNotNull { it.toDomain() }

internal fun decodeBankAccounts(json: String): List<BankAccount> =
    accountHubJson.decodeFromString(ListSerializer(BankAccountDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodeCurrencies(json: String): CurrencySettings =
    accountHubJson.decodeFromString(ValueDto.serializer(JsonElement.serializer()), json)
        .value.toCurrencySettings()

internal fun decodeSchedule(json: String): ProductionSchedule =
    accountHubJson.decodeFromString(ValueDto.serializer(JsonElement.serializer()), json)
        .value.toProductionSchedule()

internal fun decodeAccounts(json: String): List<CoaAccount> =
    accountHubJson.decodeFromString(ListSerializer(CoaAccountDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodeVendors(json: String): List<Vendor> =
    accountHubJson.decodeFromString(ListSerializer(VendorDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodeApprovalConfigs(json: String): List<ApprovalConfig> =
    accountHubJson.decodeFromString(ListSerializer(ApprovalConfigDto.serializer()), json)
        .map { it.toDomain() }

internal fun decodeDayTypes(json: String): List<DayType> =
    accountHubJson.decodeFromString(valueList(DayTypeDto.serializer()), json)
        .value.orEmpty().mapNotNull { it.toDomain() }

internal fun decodeTaxTypes(json: String): List<TaxType> =
    accountHubJson.decodeFromString(valueList(TaxTypeDto.serializer()), json)
        .value.orEmpty().map { it.toDomain() }

internal fun decodeDealConditions(json: String): List<DealCondition> =
    accountHubJson.decodeFromString(ValueDto.serializer(JsonElement.serializer()), json)
        .value.toDealConditions()

internal fun decodePayrollBureaus(json: String): List<PayrollBureau> =
    accountHubJson.decodeFromString(ValueDto.serializer(JsonElement.serializer()), json)
        .value.toPayrollBureaus()

internal fun decodeTrackingSets(json: String): List<TrackingSet> =
    accountHubJson.decodeFromString(ListSerializer(TrackingSetDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodeAssignmentRules(json: String): List<AssignmentRule> =
    accountHubJson.decodeFromString(ListSerializer(AssignmentRuleDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodePayrollGroups(json: String): List<PayrollGroup> =
    accountHubJson.decodeFromString(ListSerializer(PayrollGroupDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodePayrollSettings(json: String): PayrollSettings =
    accountHubJson.decodeFromString(PayrollSettingsDto.serializer(), json).toDomain()

internal fun decodeNonUnionPay(json: String): NonUnionPay =
    accountHubJson.decodeFromString(NonUnionPayDto.serializer(), json).toDomain()

internal fun decodeCashClose(json: String): CashCloseDashboard =
    accountHubJson.parseToJsonElement(json).toCashClose()

// -- allowances and rentals --------------------------------------------------

/**
 * One allowance or rental row.
 *
 * Both the new field names and the legacy ones are read: a half-migrated doc
 * carries `on` for `enable` and `rate`/`nominal` for `amount`/`nominal_code`,
 * and the web reads both for the same reason. Only the new names are written.
 *
 * `amount` is a JSON number or null on the wire and a string in the editor —
 * an empty cell is not zero, and coercing it to one would quietly agree a rate
 * nobody typed.
 */
@Serializable
data class EntitlementRowDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("enable") val enable: Boolean? = null,
    @SerialName("on") val legacyOn: Boolean? = null,
    @SerialName("amount") val amount: Double? = null,
    @SerialName("rate") val legacyRate: Double? = null,
    @SerialName("basis") val basis: String? = null,
    @SerialName("applies_to") val appliesTo: String? = null,
    @SerialName("cap_type") val capType: String? = null,
    @SerialName("cap_amount") val capAmount: Double? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("nominal") val legacyNominal: String? = null,
) {
    fun toDomain(index: Int, kind: String): EntitlementRow = EntitlementRow(
        id = id?.takeIf { it.isNotBlank() } ?: "$kind-legacy-$index",
        name = name.orEmpty(),
        enabled = enable ?: legacyOn ?: true,
        amount = (amount ?: legacyRate).asAmountText(),
        basis = basis.orEmpty(),
        appliesTo = appliesTo.orEmpty(),
        capped = capType == "capped",
        capAmount = capAmount.asAmountText(),
        nominalCode = nominalCode?.takeIf { it.isNotBlank() } ?: legacyNominal.orEmpty(),
    )
}

/** Whole-number amounts lose the `.0` a Double would print. */
private fun Double?.asAmountText(): String = when {
    this == null -> ""
    this == toLong().toDouble() -> toLong().toString()
    else -> toString()
}

@Serializable
data class AllowancesRentalsDto(
    @SerialName("allowances") val allowances: List<EntitlementRowDto>? = null,
    @SerialName("rentals") val rentals: List<EntitlementRowDto>? = null,
) {
    fun toDomain(): AllowancesRentals = AllowancesRentals(
        allowances = allowances.orEmpty().mapIndexed { i, row -> row.toDomain(i, "allow") },
        rentals = rentals.orEmpty().mapIndexed { i, row -> row.toDomain(i, "rental") },
    )
}

// -- agreements and documents ------------------------------------------------

/**
 * One agreements-documents row.
 *
 * The metadata is flat on the row, but pre-migration data nests it under
 * `document`, so both are read — the web falls back the same way, and a doc
 * that renders blank is indistinguishable from one that was never uploaded.
 */
@Serializable
data class AgreementDocumentDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("caption") val caption: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("media") val media: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_subtype") val contentSubtype: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("document") val nested: AgreementDocumentDto? = null,
) {
    fun toDomain(): AgreementDocument {
        // Flat first, then the pre-migration nesting — one lookup per field
        // rather than a chain of elvises repeated ten times.
        val flat = copy(nested = null)
        val inner = nested ?: EMPTY
        fun pick(of: AgreementDocumentDto.() -> String?): String =
            flat.of()?.takeIf { it.isNotBlank() } ?: inner.of().orEmpty()

        val fileName = pick { name }
        return AgreementDocument(
            id = id.orEmpty(),
            title = pick { title },
            // `caption` is what the upload contract calls it; the row also
            // carries an explicit description. Either may be the one set.
            description = pick { description }.ifBlank { pick { caption } },
            name = fileName,
            media = pick { media },
            bucket = pick { bucket },
            region = pick { region },
            contentType = pick { contentType },
            contentSubtype = pick { contentSubtype }
                .ifBlank { fileName.substringAfterLast('.', "") },
            fileSize = fileSize ?: inner.fileSize ?: 0,
        )
    }

    companion object {
        /** Stands in for an absent nested block, so every lookup is uniform. */
        private val EMPTY = AgreementDocumentDto()
    }
}

// -- payroll settings --------------------------------------------------------

@Serializable
data class PayPeriodDto(
    @SerialName("start_day_of_week") val startDay: Int? = null,
    @SerialName("end_day_of_week") val endDay: Int? = null,
)

@Serializable
data class PayrollSettingsDto(
    @SerialName("payroll_approvers") val approvers: List<String>? = null,
    @SerialName("pay_period") val payPeriod: PayPeriodDto? = null,
    /**
     * A bigint that can arrive as a JSON string.
     *
     * Read as a string and parsed, because decoding it as a number fails the
     * whole payload when the server quotes it — and a failed decode here reads
     * as "no payroll settings", which is a different thing from "not locked".
     */
    @SerialName("pay_period_locked_at") val lockedAt: JsonPrimitive? = null,
    @SerialName("journal_description_format") val journalDescriptionFormat: String? = null,
    @SerialName("journal_group_by_category") val journalGroupByCategory: Boolean? = null,
    /** Bare code strings, or `{ code }` objects on some answers. */
    @SerialName("payroll_accounts") val payrollAccounts: JsonElement? = null,
) {
    fun toDomain(): PayrollSettings {
        val (start, end) = PayrollSettings.sanitise(payPeriod?.startDay, payPeriod?.endDay)
        return PayrollSettings(
            // Trimmed and de-duplicated: the list is a set on the screen, and
            // the same person twice would read as two approvers.
            approverIds = approvers.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            payPeriodStartDay = start,
            payPeriodEndDay = end,
            payPeriodLockedAt = lockedAt?.contentOrNull?.takeIf { it.isNotBlank() }?.toLongOrNull(),
            journalDescriptionFormat = JournalDescriptionFormat.from(journalDescriptionFormat),
            journalGroupByCategory = journalGroupByCategory == true,
            payrollAccounts = (payrollAccounts as? JsonArray).orEmpty().mapNotNull { row ->
                when (row) {
                    is JsonPrimitive -> row.contentOrNull
                    is JsonObject -> row.str("code")
                    else -> null
                }?.takeIf { it.isNotBlank() }
            },
        )
    }
}

/** One payroll group — `/api/v2/payroll/payroll-groups`. */
@Serializable
data class PayrollGroupDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("id") val altId: String? = null,
    @SerialName("assignee_id") val assigneeId: String? = null,
    @SerialName("user_ids") val userIds: List<String>? = null,
    @SerialName("department_ids") val departmentIds: List<String>? = null,
    @SerialName("designation_ids") val designationIds: List<String>? = null,
) {
    fun toDomain(): PayrollGroup? = (id ?: altId)?.takeIf { it.isNotBlank() }?.let {
        PayrollGroup(
            id = it,
            assigneeId = assigneeId.orEmpty(),
            userIds = userIds.orEmpty().filter(String::isNotBlank),
            departmentIds = departmentIds.orEmpty().filter(String::isNotBlank),
            designationIds = designationIds.orEmpty().filter(String::isNotBlank),
        )
    }
}

/**
 * One auto-assignment rule.
 *
 * The three lists occasionally arrive as JSON encoded into a string — an
 * unparsed jsonb column — so each is read through [asStringList].
 */
@Serializable
data class AssignmentRuleDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("module") val module: String? = null,
    @SerialName("departments") val departments: JsonElement? = null,
    @SerialName("vendors") val vendors: JsonElement? = null,
    @SerialName("nominal_codes") val nominalCodes: JsonElement? = null,
    @SerialName("amount_min") val amountMin: JsonPrimitive? = null,
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("priority") val priority: Int? = null,
) {
    fun toDomain(): AssignmentRule? = (id ?: altId)?.takeIf { it.isNotBlank() }?.let {
        AssignmentRule(
            id = it,
            module = module.orEmpty(),
            departments = departments.asStringList(),
            vendors = vendors.asStringList(),
            nominalCodes = nominalCodes.asStringList(),
            amountMin = amountMin?.contentOrNull.orEmpty().asRateText(),
            assignTo = targetUserId.orEmpty(),
            isActive = isActive != false,
            priority = priority ?: 0,
            persisted = true,
        )
    }
}

/** A list, or a list encoded into a string; anything else is empty. */
internal fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
    is JsonPrimitive -> contentOrNull?.let { text ->
        runCatching { accountHubJson.parseToJsonElement(text) }.getOrNull()?.takeIf { it is JsonArray }?.asStringList()
    }.orEmpty()
    else -> emptyList()
}

/**
 * The Weekly Close Command Centre, pre-shaped by the server.
 *
 * Read off the raw tree rather than a typed DTO: every panel's rows are the
 * server's own presentation (a bar height, a cell tint), and a strict reader
 * would fail the whole dashboard on the first panel it renames.
 */
internal fun JsonElement?.toCashClose(): CashCloseDashboard {
    val data = (this as? JsonObject)?.let { it["data"] as? JsonObject ?: it } ?: return CashCloseDashboard()
    val progress = data["closeProgress"] as? JsonObject
    return CashCloseDashboard(
        progressPercent = progress?.int("pct") ?: 0,
        progressTotal = progress?.int("total") ?: 0,
        waterfall = data.rows("waterfall") { row ->
            WaterfallBar(
                label = row.str("label"),
                type = row.str("type"),
                height = row.int("h") ?: 0,
                marginBottom = row.int("mb") ?: 0,
                amount = row.str("amount"),
            )
        },
        heatRows = data.rows("heatRows") { row ->
            HeatRow(
                label = row.str("label"),
                cells = (row["cells"] as? JsonArray).orEmpty().mapNotNull { cell ->
                    (cell as? JsonObject)?.let {
                        HeatCell(value = it.str("val"), background = it.str("bg"), color = it.str("color"))
                    }
                },
            )
        },
        checklist = data.rows("checklist") { row ->
            ChecklistItem(
                label = row.str("label"),
                done = (row["done"] as? JsonPrimitive)?.booleanOrNull == true,
                badge = row.str("badge"),
                badgeTone = row.str("badgeV"),
                meta = row.str("meta"),
            )
        },
        recon = data.rows("recon") { row ->
            ReconRow(supplier = row.str("supplier"), status = row.str("status"), detail = row.str("detail"))
        },
        weeks = data.rows("weeks") { row ->
            CommitmentWeek(
                label = row.str("label").ifBlank { row.str("week") },
                amount = row.str("amount"),
                percent = row.int("pct") ?: 0,
                color = row.str("color"),
                detail = row.str("detail"),
                peak = (row["peak"] as? JsonPrimitive)?.booleanOrNull == true,
                netflix = (row["netflix"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        },
    )
}

private fun <T> JsonObject.rows(key: String, read: (JsonObject) -> T): List<T> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(read) }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

// -- purchase order setup ----------------------------------------------------

@Serializable
data class PurchaseOrderSetupDto(
    @SerialName("description_format") val descriptionFormat: String? = null,
    @SerialName("auto_split_rentals") val autoSplitRentals: Boolean? = null,
    @SerialName("default_split_type") val splitType: String? = null,
    @SerialName("po_number_prefix") val numberPrefix: String? = null,
    @SerialName("terms_attachment") val terms: AgreementDocumentDto? = null,
    /** Each sub-rule independently nullable; the server normalises on read. Read leniently. */
    @SerialName("asset_filters") val assetFilters: JsonElement? = null,
) {
    fun toDomain(): PurchaseOrderSetup = PurchaseOrderSetup(
        descriptionFormat = PoDescriptionFormat.from(descriptionFormat),
        // Absent is on. Only an explicit false turns it off, which is how the
        // web reads it and how a project that has never opened this screen
        // ends up splitting rentals rather than not.
        autoSplitRentals = autoSplitRentals != false,
        splitType = PoSplitType.from(splitType),
        numberPrefix = PurchaseOrderSetup.normalisePrefix(numberPrefix.orEmpty()),
        // A terms row with no key is not a document — the server sends `{}`
        // where the web sends null, and a card for a file that is not there
        // reads as one that failed to upload.
        termsDocument = terms?.toDomain()?.takeIf { it.media.isNotBlank() },
        assetFilters = assetFilters.toAssetFilters(),
    )
}

/**
 * The web's `mapAssetFiltersFromDb`: `["*"]` is the server's every-type
 * sentinel, the same as choosing none; a bound arrives as a number or a
 * string; a list occasionally arrives JSON-encoded into a string (an unparsed
 * jsonb column), so each is read through [asStringList].
 */
internal fun JsonElement?.toAssetFilters(): AssetFilters {
    val obj = this as? JsonObject ?: return AssetFilters()
    val price = obj["price"] as? JsonObject
    val types = obj["exp_type"].asStringList()
    return AssetFilters(
        priceLow = price?.get("low").asAmountText(),
        priceHigh = price?.get("high").asAmountText(),
        expTypes = if ("*" in types) emptyList() else types,
        tags = obj["tags"].asStringList(),
    )
}

/** The web's `mapAssetFiltersToDb`: all three unset is null, which clears the rule server-side. */
internal fun AssetFilters.toJson(): JsonElement {
    if (isEmpty) return JsonNull
    val lowBound = low
    val highBound = high
    return buildJsonObject {
        put(
            "price",
            if (lowBound == null && highBound == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("low", lowBound?.let(::JsonPrimitive) ?: JsonNull)
                    put("high", highBound?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )
        put("exp_type", if (expTypes.isEmpty()) JsonNull else JsonArray(expTypes.map(::JsonPrimitive)))
        put("tags", if (tags.isEmpty()) JsonNull else JsonArray(tags.map(::JsonPrimitive)))
    }
}

private fun JsonElement?.asAmountText(): String = when (this) {
    is JsonPrimitive -> if (this is JsonNull) "" else contentOrNull.orEmpty().trim()
    else -> ""
}


// -- invoices setup ----------------------------------------------------------

/**
 * The invoices settings document.
 *
 * Its three lists arrive as arrays **or as a JSON string holding one** (an
 * unparsed jsonb column) — the web and the desktop Invoices tool both read
 * either (`InvoiceSetupWire.arrayOrEncoded`). Typed as lists, one encoded
 * column failed the whole document, and the modal then opened on defaults.
 */
@Serializable
data class InvoicesSetupDto(
    @SerialName("team_members") val teamMembers: JsonElement? = null,
    @SerialName("alerts") val alerts: JsonElement? = null,
    @SerialName("run_authorization") val runAuthorisation: JsonElement? = null,
    /** The name the UI used before the backend settled on `run_authorization`. */
    @SerialName("run_auth") val legacyRunAuth: JsonElement? = null,
) {
    fun toDomain(): InvoicesSetup = InvoicesSetup(
        teamMembers = teamMembers.arrayOrEncoded().mapNotNull { (it as? JsonObject)?.toTeamMember() },
        // An unknown key is dropped rather than kept: this client cannot show
        // a switch for an alert it has no words for, and round-tripping one
        // invisibly would let it be turned off by a save nobody made.
        alerts = alerts.arrayOrEncoded()
            .mapNotNull { InvoiceAlert.from((it as? JsonPrimitive)?.contentOrNull) }
            .toSet(),
        runAuthorisation = (runAuthorisation.takeUnless { it == null || it is JsonNull } ?: legacyRunAuth)
            .arrayOrEncoded()
            .mapIndexedNotNull { index, row ->
                val tier = row as? JsonObject ?: return@mapIndexedNotNull null
                RunAuthorisationTier(
                    tier = tier.int("tier") ?: (index + 1),
                    userIds = tier["user"].arrayOrEncoded()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) },
                )
            }
            .sortedBy { it.tier },
    ).renumbered()
}

/**
 * One team member. The posting limit reads as the live Invoices settings
 * page reads it: null, absent, blank or `"unlimited"` is **Unlimited** (blank
 * here); a number — zero included, which is submit-only — is kept as typed.
 */
private fun JsonObject.toTeamMember(): InvoiceTeamMember? {
    val id = str("user_id").ifBlank { str("id") }.takeIf { it.isNotBlank() } ?: return null
    val limit = (this["posting_limit"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()
    return InvoiceTeamMember(
        userId = id,
        postingLimit = when {
            limit.isNullOrBlank() || limit.equals("unlimited", ignoreCase = true) -> ""
            else -> limit.toDoubleOrNull()?.asLimitText() ?: ""
        },
        runAccess = flag("run_access"),
        overrideAccess = flag("override_access"),
        isSenior = flag("is_senior"),
    )
}

/** `true`, or the string `"true"` an unparsed column sends. */
private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)
        ?.let { it.booleanOrNull ?: it.contentOrNull.equals("true", ignoreCase = true) } == true

/** An array, or an array encoded into a string; anything else is empty. */
internal fun JsonElement?.arrayOrEncoded(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        ?.let { text -> runCatching { accountHubJson.parseToJsonElement(text) }.getOrNull() as? JsonArray }
        ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

/** Whole limits lose the `.0`. */
private fun Double.asLimitText(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

// -- non-union pay breakdown -------------------------------------------------

/**
 * One trigger entry.
 *
 * The server pads every key it knows, so most arrive null or false. That
 * padding is dropped on the way in — except a zero, which is a real value.
 */
@Serializable
data class PayTriggerDto(
    @SerialName("after") val after: Int? = null,
    @SerialName("before") val before: Int? = null,
    @SerialName("less") val less: Int? = null,
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("consecutive") val consecutive: Boolean? = null,
    @SerialName("day_kind") val dayKind: JsonElement? = null,
    @SerialName("clock") val clock: Boolean? = null,
    @SerialName("meal") val meal: Boolean? = null,
    @SerialName("meal_curtailed") val mealCurtailed: Boolean? = null,
    @SerialName("camera") val camera: Boolean? = null,
    @SerialName("weekly") val weekly: Boolean? = null,
    @SerialName("increment") val increment: Int? = null,
    @SerialName("bdr_min") val bdrMin: Double? = null,
    @SerialName("bdr_max") val bdrMax: Double? = null,
) {
    fun toDomain(): PayTrigger = PayTrigger(
        afterMinutes = after,
        beforeMinutes = before,
        lessMinutes = less,
        dayNumber = dayNumber,
        consecutive = consecutive == true,
        // A single day kind is sometimes stored bare rather than in an array.
        dayKinds = dayKind.asDayKinds(),
        clock = clock == true,
        meal = meal == true,
        mealCurtailed = mealCurtailed == true,
        camera = camera == true,
        weekly = weekly == true,
        incrementMinutes = increment,
        bdrMin = bdrMin,
        bdrMax = bdrMax,
    )
}

private fun JsonElement?.asDayKinds(): List<PayDayKind> = when (this) {
    is JsonArray -> mapNotNull { PayDayKind.from((it as? JsonPrimitive)?.contentOrNull) }
    is JsonPrimitive -> listOfNotNull(PayDayKind.from(contentOrNull))
    else -> emptyList()
}

@Serializable
data class PayRuleDto(
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("rate_type") val rateType: String? = null,
    @SerialName("rate_amount") val rateAmount: Double? = null,
    @SerialName("basis") val basis: String? = null,
    @SerialName("triggers") val triggers: List<PayTriggerDto>? = null,
    @SerialName("is_enhancement") val isEnhancement: Boolean? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("applies_to") val appliesTo: String? = null,
    @SerialName("cap_type") val capType: String? = null,
    @SerialName("cap_amount") val capAmount: Double? = null,
    @SerialName("day_type") val dayType: String? = null,
) {
    fun toDomain(index: Int, kind: String): PayRule = PayRule(
        id = id?.takeIf { it.isNotBlank() } ?: "$kind-legacy-$index",
        label = label.orEmpty(),
        rateType = PayRateType.from(rateType),
        rateAmount = rateAmount.asRateText(),
        basis = PayRateBasis.from(basis),
        triggers = triggers.orEmpty().map { it.toDomain() },
        isEnhancement = isEnhancement == true,
        nominalCode = nominalCode.orEmpty(),
        note = note.orEmpty(),
        appliesTo = appliesTo.orEmpty(),
        capped = capType == "capped",
        capAmount = capAmount.asRateText(),
        dayType = dayType.orEmpty(),
    )
}

/** A whole rate loses the `.0`; an absent one stays blank rather than zero. */
private fun Double?.asRateText(): String = when {
    this == null -> ""
    this == toLong().toDouble() -> toLong().toString()
    else -> toString()
}

@Serializable
data class NonUnionPayDto(
    @SerialName("overtimes") val overtimes: List<PayRuleDto>? = null,
    @SerialName("premiums") val premiums: List<PayRuleDto>? = null,
    @SerialName("penalties") val penalties: List<PayRuleDto>? = null,
    /** Who the breakdown applies to — `all`, `departments`, or absent. */
    @SerialName("apply_mode") val applyMode: String? = null,
    @SerialName("department_ids") val departmentIds: List<String>? = null,
) {
    fun toDomain(): NonUnionPay = NonUnionPay(
        overtimes = overtimes.orEmpty().mapIndexed { i, row -> row.toDomain(i, "ot") },
        premiums = premiums.orEmpty().mapIndexed { i, row -> row.toDomain(i, "premium") },
        penalties = penalties.orEmpty().mapIndexed { i, row -> row.toDomain(i, "penalty") },
        applyMode = PayApplyMode.from(applyMode),
        departmentIds = departmentIds.orEmpty().filter { it.isNotBlank() },
    )
}

// -- budgets -----------------------------------------------------------------

@Serializable
data class BudgetAttachmentDto(
    @SerialName("name") val name: String? = null,
    @SerialName("media") val media: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_subtype") val contentSubtype: String? = null,
) {
    /** The stored file, when the row points at one. */
    fun toDocument(): AgreementDocument? = media?.takeIf { it.isNotBlank() }?.let {
        AgreementDocument(
            name = name.orEmpty(),
            media = it,
            bucket = bucket.orEmpty(),
            region = region.orEmpty(),
            contentType = contentType.orEmpty(),
            contentSubtype = contentSubtype.orEmpty().ifBlank { name.orEmpty().substringAfterLast('.', "") },
        )
    }
}

@Serializable
data class BudgetVersionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("total") val total: Double? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("currency_code") val currencyCode: String? = null,
    /** Milliseconds, sometimes quoted — read as a string and parsed. */
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("attachment") val attachment: BudgetAttachmentDto? = null,
) {
    fun toDomain(): BudgetVersion = BudgetVersion(
        id = id?.takeIf { it.isNotBlank() } ?: altId.orEmpty(),
        version = version.orEmpty(),
        name = label.orEmpty(),
        status = BudgetStatus.from(status),
        total = total ?: 0.0,
        // The budget's own currency wins; blank falls through to the
        // production's default where the figure is shown.
        currencyCode = currency?.takeIf { it.isNotBlank() } ?: currencyCode.orEmpty(),
        createdAtMillis = createdAt?.contentOrNull?.takeIf { it.isNotBlank() }?.toLongOrNull(),
        sourceFileName = attachment?.name.orEmpty(),
        attachment = attachment?.toDocument(),
    )
}

@Serializable
data class BudgetLineDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("account") val account: String? = null,
    /** The chart's name for the code — what the web's Name column prints. */
    @SerialName("name") val name: String? = null,
    @SerialName("uncoded_name") val uncodedName: String? = null,
    @SerialName("amount") val amount: Double? = null,
    @SerialName("rollup_total") val rollupTotal: Double? = null,
    @SerialName("line_type") val lineType: String? = null,
    @SerialName("head_id") val headId: String? = null,
    @SerialName("sec_id") val sectionId: String? = null,
    @SerialName("cat_id") val categoryId: String? = null,
) {
    fun toDomain(): BudgetLine = BudgetLine(
        id = id?.takeIf { it.isNotBlank() } ?: altId.orEmpty(),
        account = account.orEmpty(),
        name = name.orEmpty(),
        uncodedName = uncodedName.orEmpty(),
        amount = amount ?: 0.0,
        rollupTotal = rollupTotal,
        // Unknown or absent reads as the deepest level: a line with no level
        // is a leaf carrying money, and filing it as a header would make it a
        // parent that swallows its siblings' totals.
        lineType = CoaLineType.from(lineType) ?: CoaLineType.SubCategory,
        headId = headId?.takeIf { it.isNotBlank() },
        sectionId = sectionId?.takeIf { it.isNotBlank() },
        categoryId = categoryId?.takeIf { it.isNotBlank() },
    )
}

// -- trial balance -----------------------------------------------------------

/**
 * One account's row, read as loosely as the web reads it.
 *
 * The figures and the code are raw JSON on purpose. The web takes every amount
 * through `Number(v) || 0` and the code through `String(v)`, so a figure sent as
 * `"1250.00"` — which is how a Postgres NUMERIC leaves a Node service — or a
 * code sent as the number `4000` still shows. Typed fields would fail that
 * row's decode, and the list reader skips a row it cannot decode: an account
 * would vanish from the ledger without a word, and the totals with it.
 */
@Serializable
data class TrialBalanceRowDto(
    @SerialName("account_code") val accountCode: JsonElement? = null,
    @SerialName("description") val description: JsonElement? = null,
    @SerialName("name") val name: JsonElement? = null,
    @SerialName("cost_type") val costType: JsonElement? = null,
    @SerialName("debit") val debit: JsonElement? = null,
    @SerialName("credit") val credit: JsonElement? = null,
    /** The closing balance, which is the server's, not debit minus credit. */
    @SerialName("ending") val ending: JsonElement? = null,
) {
    fun toDomain(): TrialBalanceRow = TrialBalanceRow(
        // A code can come back as the string "null" as well as absent.
        accountCode = accountCode.looseText().takeIf { it != "null" }.orEmpty(),
        name = description.looseText().ifBlank { name.looseText() },
        costType = costType.looseText(),
        debit = debit.looseAmount(),
        credit = credit.looseAmount(),
        ending = ending.looseAmount(),
    )
}

/** A string or a number as its text; blank for null, an object or an array. */
private fun JsonElement?.looseText(): String = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/** The web's `Number(v) || 0`: a number, or a numeric string, or nothing at all. */
private fun JsonElement?.looseAmount(): Double =
    (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0

// -- period close ------------------------------------------------------------

@Serializable
data class PeriodWeekDto(
    @SerialName("start_day_of_week") val startDay: Int? = null,
    @SerialName("end_day_of_week") val endDay: Int? = null,
)

@Serializable
data class PeriodLockDto(
    @SerialName("lockedDate") val lockedDate: String? = null,
    /** What the write route answers with, where the read route says `lockedDate`. */
    @SerialName("last_cr_locked_date") val lastLockedDate: String? = null,
    @SerialName("previous") val previous: String? = null,
    @SerialName("tz") val timeZone: String? = null,
    @SerialName("week") val week: PeriodWeekDto? = null,
) {
    fun toDomain(): PeriodLock = PeriodLock(
        lockedThrough = lockedDate?.takeIf { it.isNotBlank() } ?: lastLockedDate.orEmpty(),
        timeZone = timeZone.orEmpty(),
        weekStartDay = week?.startDay,
        weekEndDay = week?.endDay,
    )
}

// The bible report's DTOs live in BibleReportDtos.kt, beside the envelope and
// lock readers they need.

// -- budget import -----------------------------------------------------------

@Serializable
data class ParsedSectionDto(
    @SerialName("section_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
) {
    fun toDomain() = ParsedSection(id = id.orEmpty(), name = name.orEmpty())
}

@Serializable
data class ParsedCodeDto(
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("amount") val amount: Double? = null,
    @SerialName("section_id") val sectionId: String? = null,
    @SerialName("parent_code") val parentCode: String? = null,
) {
    fun toDomain() = ParsedCode(
        code = code.orEmpty(),
        name = name.orEmpty(),
        amount = amount ?: 0.0,
        sectionId = sectionId.orEmpty(),
        parentCode = parentCode.orEmpty(),
    )
}

@Serializable
data class ParsedUncodedDto(
    @SerialName("name") val name: String? = null,
    @SerialName("amount") val amount: Double? = null,
) {
    fun toDomain() = ParsedUncoded(name = name.orEmpty(), amount = amount ?: 0.0)
}

@Serializable
data class ParsedBudgetDto(
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("sections") val sections: List<ParsedSectionDto>? = null,
    @SerialName("headers") val headers: List<ParsedCodeDto>? = null,
    @SerialName("nominals") val nominals: List<ParsedCodeDto>? = null,
    @SerialName("uncodedItems") val uncoded: List<ParsedUncodedDto>? = null,
    @SerialName("warnings") val warnings: List<String>? = null,
    @SerialName("grandTotal") val grandTotal: Double? = null,
) {
    fun toDomain(): ParsedBudget = ParsedBudget(
        currency = currency.orEmpty(),
        sections = sections.orEmpty().map { it.toDomain() },
        headers = headers.orEmpty().map { it.toDomain() },
        nominals = nominals.orEmpty().map { it.toDomain() },
        uncoded = uncoded.orEmpty().map { it.toDomain() },
        warnings = warnings.orEmpty().filter { it.isNotBlank() },
        serverTotal = grandTotal,
    )
}

/**
 * The dry run's answer.
 *
 * The audit row it used to carry went away when ingestion moved to an S3
 * pointer (observed on the web 2026-06-10), so `upload` is optional and the
 * attachment identifies the file instead. Both shapes are read: a backend that
 * goes back to sending the row must not break this.
 */
@Serializable
data class BudgetDryRunDto(
    @SerialName("parsed") val parsed: ParsedBudgetDto? = null,
    @SerialName("upload") val upload: BudgetUploadRowDto? = null,
    @SerialName("upload_id") val uploadId: String? = null,
    @SerialName("detectedFormat") val detectedFormat: String? = null,
    @SerialName("sourceTemplate") val sourceTemplate: String? = null,
    @SerialName("attachment") val attachment: AgreementDocumentDto? = null,
)

@Serializable
data class BudgetUploadRowDto(
    @SerialName("id") val id: String? = null,
    @SerialName("original_filename") val fileName: String? = null,
    @SerialName("detected_format") val detectedFormat: String? = null,
    @SerialName("source_template") val sourceTemplate: String? = null,
)

/** What the commit answers with: the version it created. */
@Serializable
data class BudgetImportResultDto(
    @SerialName("budget") val budget: BudgetVersionDto? = null,
)
