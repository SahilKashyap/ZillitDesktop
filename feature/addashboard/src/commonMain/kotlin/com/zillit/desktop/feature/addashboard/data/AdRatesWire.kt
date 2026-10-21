package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgencyHistoryEntry
import com.zillit.desktop.feature.addashboard.domain.AgreementAllowanceRow
import com.zillit.desktop.feature.addashboard.domain.AgreementRateRow
import com.zillit.desktop.feature.addashboard.domain.BankDetails
import com.zillit.desktop.feature.addashboard.domain.DayKind
import com.zillit.desktop.feature.addashboard.domain.DistributionEntry
import com.zillit.desktop.feature.addashboard.domain.NominalCode
import com.zillit.desktop.feature.addashboard.domain.NonUnionRates
import com.zillit.desktop.feature.addashboard.domain.PayRule
import com.zillit.desktop.feature.addashboard.domain.PayRuleField
import com.zillit.desktop.feature.addashboard.domain.PayRuleTemplates
import com.zillit.desktop.feature.addashboard.domain.RateBasis
import com.zillit.desktop.feature.addashboard.domain.RateConfig
import com.zillit.desktop.feature.addashboard.domain.RateKind
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementDocument
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementRef
import com.zillit.desktop.feature.addashboard.domain.UnionPayBreakdown
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rates & Config's wire shapes, which are the deal memo's.
 *
 * A pay rule travels as `rateRowOverrideSchema` — `{ row_id, source,
 * nominal_code }` — where everything the AD can change sits one level down
 * inside `source`, and the rule's *template* rides in `source.id`. That is why
 * the editor's [PayRule] is flat and this file is the codec.
 */

/** What makes a rule fire. `triggerSchema` is loose, so every key is optional. */
@Serializable
internal data class TriggerDto(
    @Serializable(with = LooseInt::class)
    @SerialName("day_number") val dayNumber: Int? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("consecutive") val consecutive: Boolean? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("after") val after: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("before") val before: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("less") val less: Int? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("clock") val clock: Boolean? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("meal") val meal: Boolean? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("meal_curtailed") val mealCurtailed: Boolean? = null,
    @SerialName("day_kind") val dayKind: List<String>? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("increment") val increment: Int? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("bdr_min") val bdrMin: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("bdr_max") val bdrMax: Double? = null,
)

/** The agreement rate row a rule's `source` is. */
@Serializable
internal data class RateRowDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("raw_label") val rawLabel: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("rate_type") val rateType: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("rate_amount") val rateAmount: Double? = null,
    @SerialName("basis") val basis: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("max") val max: Double? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("triggers") val triggers: List<TriggerDto>? = null,
) {
    fun toAgreementRow(index: Int): AgreementRateRow = AgreementRateRow(
        id = id ?: "r$index",
        label = label ?: rawLabel.orEmpty(),
        rateKind = RateKind.from(rateType),
        rateAmount = rateAmount,
        basis = RateBasis.from(basis),
        nominalCode = nominalCode.orEmpty(),
        note = note.orEmpty(),
    )
}

@Serializable
internal data class RateOverrideDto(
    @Serializable(with = LooseString::class)
    @SerialName("row_id") val rowId: String? = null,
    @SerialName("source") val source: RateRowDto? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("pay_frequency") val payFrequency: String? = null,
) {
    /**
     * The stored rule back into the flat editor row.
     *
     * The template is whatever `source.id` names, with an unknown id falling
     * to the first — the inverse of the trigger each template writes.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per template's trigger shape.
    fun toDomain(index: Int): PayRule {
        val row = source ?: RateRowDto()
        val trigger = row.triggers?.firstOrNull() ?: TriggerDto()
        val template = PayRuleTemplates.byId(row.id)
        return PayRule(
            id = rowId ?: "r$index",
            templateId = template.id,
            label = row.label.orEmpty(),
            rateKind = RateKind.from(row.rateType),
            amount = row.rateAmount?.let(::trimNumber).orEmpty(),
            basis = RateBasis.from(row.basis),
            formValue = when (template.field) {
                PayRuleField.Hours -> when (template.id) {
                    "broken_turnaround" -> trigger.less?.let { PayRuleTemplates.formatHours(it) }
                    else -> trigger.after?.let { PayRuleTemplates.formatHours(it) }
                }.orEmpty()

                PayRuleField.Clock -> when (template.id) {
                    "night_work" -> PayRuleTemplates.formatClock(trigger.after ?: 0)
                    else -> PayRuleTemplates.formatClock(trigger.before ?: 0)
                }

                else -> ""
            },
            dayKinds = trigger.dayKind?.mapNotNull { DayKind.from(it) }.orEmpty(),
            increments = trigger.increment?.toString().orEmpty(),
            cap = row.max?.let(::trimNumber).orEmpty(),
            bdrMin = trigger.bdrMin?.let(::trimNumber).orEmpty(),
            bdrMax = trigger.bdrMax?.let(::trimNumber).orEmpty(),
            nominalCode = nominalCode.orEmpty(),
            note = row.note.orEmpty(),
        )
    }
}

@Serializable
internal data class DayRateDto(
    @SerialName("category") val category: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
)

@Serializable
internal data class DistributionDto(
    @SerialName("kind") val kind: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("ref") val ref: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
) {
    fun toDomain(): DistributionEntry = DistributionEntry(
        kind = kind ?: "user",
        ref = ref,
        name = name.orEmpty(),
        email = email.orEmpty(),
    )
}

@Serializable
internal data class NonUnionDto(
    @SerialName("day_rates") val dayRates: List<DayRateDto>? = null,
    @SerialName("day_types") val dayTypes: List<DayTypeDto>? = null,
    @SerialName("overtimes") val overtimes: List<RateOverrideDto>? = null,
    @SerialName("premiums") val premiums: List<RateOverrideDto>? = null,
    @SerialName("penalties") val penalties: List<RateOverrideDto>? = null,
    @SerialName("allowances") val allowances: List<RateLineDto>? = null,
    @SerialName("additional_fees") val additionalFees: List<RateLineDto>? = null,
    @SerialName("distribution") val distribution: List<DistributionDto>? = null,
    @SerialName("report_approvers") val reportApprovers: List<DistributionDto>? = null,
) {
    fun toDomain(): NonUnionRates = NonUnionRates(
        dayRates = dayRates.orEmpty()
            .mapNotNull { row ->
                val category = row.category?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                category to (row.amount?.let(::trimNumber) ?: "")
            }
            .toMap(),
        dayTypes = dayTypes?.mapNotNull { it.toDomain() }.orEmpty(),
        overtimes = overtimes.orEmpty().mapIndexed { index, row -> row.toDomain(index) },
        premiums = premiums.orEmpty().mapIndexed { index, row -> row.toDomain(index) },
        penalties = penalties.orEmpty().mapIndexed { index, row -> row.toDomain(index) },
        allowances = allowances?.map { it.toDomain() }.orEmpty(),
        additionalFees = additionalFees?.map { it.toDomain() }.orEmpty(),
        distribution = distribution?.map { it.toDomain() }.orEmpty(),
        reportApprovers = reportApprovers?.map { it.toDomain() }.orEmpty(),
    )
}

@Serializable
internal data class UnionRefDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("territory") val territory: String? = null,
) {
    fun toDomain(): UnionAgreementRef? {
        val resolved = id ?: return null
        return UnionAgreementRef(
            id = resolved,
            label = label ?: name ?: resolved,
            territory = territory.orEmpty(),
        )
    }
}

/** The saved union snapshot's allowance row, which keeps the agreement's floor. */
@Serializable
internal data class SavedAllowanceDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("rate_type") val rateType: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("rate_pct") val ratePct: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("floor") val floor: Double? = null,
    @SerialName("basis") val basis: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("cap_amount") val capAmount: Double? = null,
) {
    /**
     * The row as the editor shows it: the **agreement's** amount is the floor,
     * and the production's chosen amount comes separately.
     */
    fun toAgreementRow(): AgreementAllowanceRow = AgreementAllowanceRow(
        id = id.orEmpty(),
        label = name ?: label.orEmpty(),
        rateKind = if (rateType == "pct") RateKind.Percentage else RateKind.Flat,
        floor = floor ?: if (rateType == "pct") ratePct else amount,
        basis = basis.orEmpty(),
        cap = capAmount,
    )

    /** What the production actually chose, which fills the editor's own field. */
    fun chosenAmount(): String? {
        val value = if (rateType == "pct") ratePct else amount
        return value?.let(::trimNumber)
    }
}

@Serializable
internal data class HolidayPayDto(
    @SerialName("type") val type: String? = null,
    @SerialName("rate_type") val rateType: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("rate_amount") val rateAmount: Double? = null,
) {
    val kind: String get() = if ((type ?: rateType) == "percentage") "percentage" else "flat"
    val value: Double? get() = amount ?: rateAmount
}

/** One saved union document, as `GET /ad-rate-config/union/:id` answers it. */
@Serializable
internal data class SavedUnionDto(
    @Serializable(with = LooseString::class)
    @SerialName("agreement_identifier") val agreementId: String? = null,
    @SerialName("agreement_label") val label: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("day_rates") val dayRates: List<DayRateDto>? = null,
    @SerialName("overtimes") val overtimes: List<RateOverrideDto>? = null,
    @SerialName("premiums") val premiums: List<RateOverrideDto>? = null,
    @SerialName("penalties") val penalties: List<RateOverrideDto>? = null,
    @SerialName("turnaround") val turnaround: List<RateOverrideDto>? = null,
    @SerialName("day_types") val dayTypes: List<DayTypeDto>? = null,
    @SerialName("fringes") val fringes: List<SavedAllowanceDto>? = null,
    @SerialName("holiday_pay") val holidayPay: HolidayPayDto? = null,
    @SerialName("allowances") val allowances: List<SavedAllowanceDto>? = null,
    @SerialName("additional_fees") val additionalFees: List<SavedAllowanceDto>? = null,
) {
    /**
     * A saved union renders from its own snapshot, with no deal-memo call.
     *
     * Everything the editor needs survived the save: the rules kept their
     * verbatim `source`, and each allowance kept the agreement's amount as its
     * `floor`.
     */
    fun toDomain(): UnionPayBreakdown? {
        val resolved = agreementId ?: return null
        return UnionPayBreakdown(
            agreementId = resolved,
            label = label ?: resolved,
            currency = currency.orEmpty(),
            dayRates = dayRates.orEmpty()
                .mapNotNull { row ->
                    val category = row.category?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    category to (row.amount?.let(::trimNumber) ?: "")
                }
                .toMap(),
            allowanceAmounts = allowances.orEmpty().chosenByRow(),
            feeAmounts = additionalFees.orEmpty().chosenByRow(),
            document = UnionAgreementDocument(
                currency = currency.orEmpty(),
                overtimes = overtimes.orEmpty().asAgreementRows(),
                premiums = premiums.orEmpty().asAgreementRows(),
                penalties = penalties.orEmpty().asAgreementRows(),
                turnaround = turnaround.orEmpty().asAgreementRows(),
                allowances = allowances.orEmpty().map { it.toAgreementRow() },
                extraFees = additionalFees.orEmpty().map { it.toAgreementRow() },
                fringes = fringes.orEmpty().map { it.toAgreementRow() },
                dayTypes = dayTypes?.mapNotNull { it.toDomain() }.orEmpty(),
                holidayPayKind = holidayPay?.kind.orEmpty(),
                holidayPayAmount = holidayPay?.value,
            ),
        )
    }

    private fun List<SavedAllowanceDto>.chosenByRow(): Map<String, String> =
        mapNotNull { row ->
            val id = row.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to (row.chosenAmount() ?: "")
        }.toMap()

    private fun List<RateOverrideDto>.asAgreementRows(): List<AgreementRateRow> =
        mapIndexed { index, override ->
            (override.source ?: RateRowDto()).toAgreementRow(index)
                .copy(nominalCode = override.nominalCode.orEmpty())
        }
}

/** A block of rows, which is how the deal-memo agreement nests them. */
@Serializable
internal data class AgreementBlockDto(
    @SerialName("rows") val rows: List<RateRowDto>? = null,
)

@Serializable
internal data class AgreementAllowanceBlockDto(
    @SerialName("rows") val rows: List<SavedAllowanceDto>? = null,
)

/** A fringe package: a label and its items. */
@Serializable
internal data class FringePackageDto(
    @SerialName("label") val label: String? = null,
    @SerialName("items") val items: List<SavedAllowanceDto>? = null,
)

/** The live deal-memo agreement, for one the project has not saved yet. */
@Serializable
internal data class AgreementDocDto(
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("overtimes") val overtimes: AgreementBlockDto? = null,
    @SerialName("premiums") val premiums: AgreementBlockDto? = null,
    @SerialName("penalties") val penalties: AgreementBlockDto? = null,
    @SerialName("turnaround") val turnaround: AgreementBlockDto? = null,
    @SerialName("allowances") val allowances: AgreementAllowanceBlockDto? = null,
    @SerialName("extra_fees") val extraFees: AgreementAllowanceBlockDto? = null,
    @SerialName("fringes") val fringes: Map<String, FringePackageDto>? = null,
    @SerialName("holiday_pay") val holidayPay: HolidayPayDto? = null,
    @SerialName("day_types") val dayTypes: List<DayTypeDto>? = null,
) {
    fun toDomain(): UnionAgreementDocument = UnionAgreementDocument(
        currency = currency.orEmpty(),
        overtimes = overtimes.rows(),
        premiums = premiums.rows(),
        penalties = penalties.rows(),
        turnaround = turnaround.rows(),
        allowances = allowances?.rows?.map { it.toAgreementRow() }.orEmpty(),
        extraFees = extraFees?.rows?.map { it.toAgreementRow() }.orEmpty(),
        // Flattened on save, which loses the packages — so they are flattened
        // on read too and the editor shows one group either way.
        fringes = fringes.orEmpty().values.flatMap { pkg ->
            pkg.items.orEmpty().map { it.toAgreementRow() }
        },
        dayTypes = dayTypes?.mapNotNull { it.toDomain() }.orEmpty(),
        holidayPayKind = holidayPay?.kind.orEmpty(),
        holidayPayAmount = holidayPay?.value,
    )

    private fun AgreementBlockDto?.rows(): List<AgreementRateRow> =
        this?.rows.orEmpty().mapIndexed { index, row -> row.toAgreementRow(index) }
}

@Serializable
internal data class RateConfigDto(
    @SerialName("non_union") val nonUnion: NonUnionDto? = null,
    @SerialName("union_agreements") val unionAgreements: List<UnionRefDto>? = null,
    @SerialName("vendors") val vendors: List<AgencyDto>? = null,
) {
    fun toDomain(): RateConfig = RateConfig(
        nonUnion = nonUnion?.toDomain() ?: NonUnionRates(),
        unionAgreements = unionAgreements?.mapNotNull { it.toDomain() }.orEmpty(),
        vendors = vendors?.mapNotNull { it.toDomain() }.orEmpty(),
    )
}

/**
 * An agency, in the vendors module's shape.
 *
 * Address and phone come back as stringified JSON on some rows, which is read
 * as the text it is rather than parsed: the forms show one line either way,
 * and a half-parsed object would print `[object Object]`.
 */
@Serializable
internal data class AgencyDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("contactPerson") val contactPersonCamel: String? = null,
    @SerialName("contact_person") val contactPerson: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("phone") val phone: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("address") val address: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("verified") val verified: Boolean? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("created_by") val createdBy: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("createdBy") val createdByCamel: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("department_id") val departmentId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("departmentId") val departmentIdCamel: String? = null,
    @SerialName("bank") val bank: BankDto? = null,
    @Serializable(with = LooseString::class)
    @SerialName("bank_id") val bankId: String? = null,
) {
    fun toDomain(): Agency? {
        val resolved = underscoreId ?: id ?: return null
        return Agency(
            id = resolved,
            name = name.orEmpty(),
            email = email.orEmpty(),
            contactPerson = contactPersonCamel ?: contactPerson.orEmpty(),
            phone = phone.orEmpty(),
            address = address.orEmpty(),
            // A row that carries no boolean says so with its status instead.
            verified = verified ?: (status == "VERIFIED"),
            createdBy = createdBy ?: createdByCamel.orEmpty(),
            departmentId = departmentId ?: departmentIdCamel,
            bank = bank?.toDomain() ?: BankDetails(id = bankId.orEmpty()),
        )
    }
}

@Serializable
internal data class AgencyHistoryDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("event") val event: String? = null,
    @SerialName("by_name") val byName: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("detail") val detail: String? = null,
) {
    fun toDomain(): AgencyHistoryEntry = AgencyHistoryEntry(
        id = id.orEmpty(),
        action = action ?: event.orEmpty(),
        byName = byName ?: userName.orEmpty(),
        at = createdAt?.toLong(),
        detail = detail.orEmpty(),
    )
}

@Serializable
internal data class NominalCodeDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("code") val code: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("account_code") val accountCode: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("account_name") val accountName: String? = null,
) {
    fun toDomain(): NominalCode? {
        val resolvedCode = code ?: accountCode ?: return null
        return NominalCode(
            id = id.orEmpty(),
            code = resolvedCode,
            name = name ?: accountName.orEmpty(),
        )
    }
}

/**
 * A number as a field shows it: `142` rather than `142.0`, `12.5` kept.
 *
 * The editor's fields are strings all the way through, so a stored amount has
 * to come back as the text somebody would have typed.
 */
internal fun trimNumber(value: Double): String {
    val whole = value.toLong()
    return if (value == whole.toDouble()) whole.toString() else value.toString()
}
