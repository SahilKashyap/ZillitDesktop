// One converter per wire shape, plus the readers for the fields this backend
// sends more than one way. Kept in one file because splitting them separates a
// shape from the tolerance it needs — the pairing that makes either readable.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
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
import com.zillit.desktop.feature.accounthub.domain.BibleAccount
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.LedgerTransaction
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

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
        )
    }
}

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
        )
    }
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
        ProjectCurrency(code = it, name = name.orEmpty(), symbol = symbol.orEmpty(), rate = rate)
    }
}

@Serializable
data class TaxTypeDto(
    @SerialName("type") val type: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("is_recoverable") val isRecoverable: Boolean? = null,
    @SerialName("nominal") val nominal: String? = null,
) {
    fun toDomain(): TaxType = TaxType(
        type = type.orEmpty(),
        identifier = identifier.orEmpty(),
        label = label.orEmpty(),
        value = value.orEmpty(),
        // Absence is false. Only an explicit true marks a rate reclaimable —
        // defaulting the other way would let input tax be claimed that is not.
        isRecoverable = isRecoverable == true,
        nominal = nominal.orEmpty(),
    )
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
                    tax.copy(identifier = TaxType.keyFor(code, tax.identifier))
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

@Serializable
data class ProjectBudgetDto(
    @SerialName("amount") val amount: Double? = null,
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
) {
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
        )
    }
}

@Serializable
data class TrackingSetDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("nodes") val nodes: List<TrackingNodeDto>? = null,
) {
    fun toDomain(): TrackingSet? = id?.takeIf { it.isNotBlank() }?.let { setId ->
        TrackingSet(
            id = setId,
            name = name.orEmpty(),
            code = code.orEmpty(),
            isActive = isActive != false,
            nodes = nodes.orEmpty().mapNotNull { it.toDomain(setId) },
        )
    }
}

@Serializable
data class TrackingNodeDto(
    @SerialName("id") val id: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
) {
    fun toDomain(setId: String): TrackingNode? = id?.takeIf { it.isNotBlank() }?.let {
        TrackingNode(
            id = it,
            setId = setId,
            code = code.orEmpty(),
            name = name.orEmpty(),
            parentId = parentId?.takeIf { parent -> parent.isNotBlank() },
            isActive = isActive != false,
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
        )
    }
}

@Serializable
data class VendorChangeDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("created") val created: JsonPrimitive? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("message") val message: String? = null,
) {
    fun toDomain(): VendorChange? {
        val resolved = (id ?: altId)?.takeIf { it.isNotBlank() } ?: return null
        return VendorChange(
            id = resolved,
            at = (createdAt ?: created).epochMillis(),
            byName = userName.orEmpty(),
            summary = (message ?: action).orEmpty(),
        )
    }
}

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
        )
    }
}

// -- purchase order setup ----------------------------------------------------

@Serializable
data class PurchaseOrderSetupDto(
    @SerialName("description_format") val descriptionFormat: String? = null,
    @SerialName("auto_split_rentals") val autoSplitRentals: Boolean? = null,
    @SerialName("default_split_type") val splitType: String? = null,
    @SerialName("po_number_prefix") val numberPrefix: String? = null,
    @SerialName("terms_attachment") val terms: AgreementDocumentDto? = null,
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
    )
}

// -- invoices setup ----------------------------------------------------------

@Serializable
data class InvoiceTeamMemberDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("posting_limit") val postingLimit: Double? = null,
    @SerialName("run_access") val runAccess: Boolean? = null,
    @SerialName("override_access") val overrideAccess: Boolean? = null,
    @SerialName("is_senior") val isSenior: Boolean? = null,
) {
    fun toDomain(): InvoiceTeamMember = InvoiceTeamMember(
        userId = userId.orEmpty(),
        postingLimit = postingLimit.asLimitText(),
        runAccess = runAccess == true,
        overrideAccess = overrideAccess == true,
        isSenior = isSenior == true,
    )
}

/** Whole limits lose the `.0`; an absent one stays blank rather than becoming zero. */
private fun Double?.asLimitText(): String = when {
    this == null -> ""
    this == toLong().toDouble() -> toLong().toString()
    else -> toString()
}

@Serializable
data class RunAuthorisationDto(
    @SerialName("tier") val tier: Int? = null,
    @SerialName("user") val users: List<String>? = null,
)

@Serializable
data class InvoicesSetupDto(
    @SerialName("team_members") val teamMembers: List<InvoiceTeamMemberDto>? = null,
    @SerialName("alerts") val alerts: List<String>? = null,
    @SerialName("run_authorization") val runAuthorisation: List<RunAuthorisationDto>? = null,
    /** The name the UI used before the backend settled on `run_authorization`. */
    @SerialName("run_auth") val legacyRunAuth: List<RunAuthorisationDto>? = null,
) {
    fun toDomain(): InvoicesSetup = InvoicesSetup(
        teamMembers = teamMembers.orEmpty().map { it.toDomain() },
        // An unknown key is dropped rather than kept: this client cannot show
        // a switch for an alert it has no words for, and round-tripping one
        // invisibly would let it be turned off by a save nobody made.
        alerts = alerts.orEmpty().mapNotNull(InvoiceAlert::from).toSet(),
        runAuthorisation = (runAuthorisation ?: legacyRunAuth).orEmpty()
            .mapIndexed { index, row ->
                RunAuthorisationTier(tier = row.tier ?: index + 1, userIds = row.users.orEmpty())
            }
            .sortedBy { it.tier },
    ).renumbered()
}

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
)

@Serializable
data class BudgetVersionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("total") val total: Double? = null,
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
    )
}

@Serializable
data class BudgetLineDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("account") val account: String? = null,
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

@Serializable
data class TrialBalanceRowDto(
    @SerialName("account_code") val accountCode: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("cost_type") val costType: String? = null,
    @SerialName("debit") val debit: Double? = null,
    @SerialName("credit") val credit: Double? = null,
    /** The closing balance, which is the server's, not debit minus credit. */
    @SerialName("ending") val ending: Double? = null,
) {
    fun toDomain(): TrialBalanceRow = TrialBalanceRow(
        // A code can come back as the string "null" as well as absent.
        accountCode = accountCode?.takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
        name = description?.takeIf { it.isNotBlank() } ?: name.orEmpty(),
        costType = costType.orEmpty(),
        debit = debit ?: 0.0,
        credit = credit ?: 0.0,
        ending = ending ?: 0.0,
    )
}

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

// -- bible report ------------------------------------------------------------

@Serializable
data class LedgerTransactionDto(
    @SerialName("src") val source: String? = null,
    @SerialName("eff_date") val effectiveDate: JsonPrimitive? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("po_number") val purchaseOrderNumber: String? = null,
    @SerialName("vendor") val vendor: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("amount") val amount: Double? = null,
) {
    fun toDomain(): LedgerTransaction = LedgerTransaction(
        source = source.orEmpty(),
        effectiveDateMillis = effectiveDate?.contentOrNull?.takeIf { it.isNotBlank() }?.toLongOrNull(),
        invoiceNumber = invoiceNumber.orEmpty(),
        purchaseOrderNumber = purchaseOrderNumber.orEmpty(),
        party = vendor.orEmpty(),
        description = description.orEmpty(),
        originalCurrency = currency.orEmpty(),
        amount = amount ?: 0.0,
    )
}

@Serializable
data class BibleAccountDto(
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("total") val total: Double? = null,
    @SerialName("transactions") val transactions: List<LedgerTransactionDto>? = null,
) {
    fun toDomain(): BibleAccount = BibleAccount(
        code = code.orEmpty(),
        name = name.orEmpty(),
        total = total ?: 0.0,
        transactions = transactions.orEmpty().map { it.toDomain() },
    )
}

@Serializable
data class BibleReportDto(
    @SerialName("accounts") val accounts: List<BibleAccountDto>? = null,
    @SerialName("grand_total") val grandTotal: Double? = null,
    @SerialName("total") val total: Double? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("default_currency") val defaultCurrency: String? = null,
    @SerialName("generatedAt") val generatedAt: JsonPrimitive? = null,
    /** Per-bucket failures, keyed by source. Reported, never swallowed. */
    @SerialName("errors") val errors: Map<String, String>? = null,
) {
    fun toDomain(): BibleReport {
        val accounts = accounts.orEmpty().map { it.toDomain() }
        return BibleReport(
            accounts = accounts,
            // The server's total where it gives one. Only summed here when it
            // does not: a bucket that failed is missing from the accounts, and
            // a client-side sum would quietly report a smaller book.
            grandTotal = grandTotal ?: total ?: accounts.sumOf { it.total },
            currencyCode = currency?.takeIf { it.isNotBlank() } ?: defaultCurrency.orEmpty(),
            generatedAtMillis = generatedAt?.contentOrNull?.takeIf { it.isNotBlank() }?.toLongOrNull(),
            errors = errors.orEmpty(),
        )
    }
}

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
    @SerialName("attachment") val attachment: AgreementDocumentDto? = null,
)

@Serializable
data class BudgetUploadRowDto(
    @SerialName("id") val id: String? = null,
    @SerialName("original_filename") val fileName: String? = null,
    @SerialName("detected_format") val detectedFormat: String? = null,
)

/** What the commit answers with: the version it created. */
@Serializable
data class BudgetImportResultDto(
    @SerialName("budget") val budget: BudgetVersionDto? = null,
)
