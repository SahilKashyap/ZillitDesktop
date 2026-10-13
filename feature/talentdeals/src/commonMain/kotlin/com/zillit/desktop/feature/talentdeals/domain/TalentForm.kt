package com.zillit.desktop.feature.talentdeals.domain

import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers.num
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A file chosen on this machine, not yet uploaded. Bytes never cross the API. */
class PickedTalentFile(val name: String, val bytes: ByteArray, val mimeType: String) {
    val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean =
        other is PickedTalentFile && other.name == name && other.mimeType == mimeType &&
            other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

/**
 * A file slot on the form: the stored envelope an Edit opens with, or a file
 * picked just now. Both read as "uploaded" — name, Replace, remove — and only a
 * pick carries a size. Save uploads the pick and posts the envelope that comes
 * back; a stored envelope is posted back as it is.
 */
sealed interface TalentFileSlot {
    val name: String

    data class Stored(val attachment: TalentAttachment) : TalentFileSlot {
        override val name: String get() = attachment.displayName
    }

    data class Picked(val file: PickedTalentFile) : TalentFileSlot {
        override val name: String get() = file.name
    }
}

/**
 * The Add / Edit form. Camel-cased, strings in the numeric fields the inputs
 * bind to, dates as `yyyy-mm-dd`. [TalentContractBody.build] is the one place
 * this meets the wire.
 */
data class TalentForm(
    val person: String = "",
    val contractFile: TalentFileSlot? = null,
    val territory: String = "",
    val union: String = "",
    val paymentMode: PaymentMode? = null,
    val feeBasis: FeeBasis? = null,
    val fee: String = "",
    /** Blank until chosen: the project's default currency stands in for it. */
    val currency: String = "",
    val weekly: Boolean = false,
    val withholding: Boolean = false,
    val withholdingType: WithholdingType = WithholdingType.Percent,
    val withholdingValue: String = "",
    /** A rate agreed with the tax authority before payment replaces the usual one. */
    val withholdingAuthorised: Boolean = false,
    val authorisedRate: String = "",
    val certificateFile: TalentFileSlot? = null,
    /** The expenses term is ticked — the table exists only under it. */
    val hasExpenses: Boolean = false,
    val expenses: List<ExpenseDraft> = emptyList(),
    val paidAs: PaidAs = PaidAs.Percent,
    val tranches: List<TrancheDraft> = emptyList(),
    val autoTimeCards: Boolean = false,
) {
    /** The two escrow boxes are ONE choice: ticking one unticks the other. */
    fun pickMode(option: PaymentMode): TalentForm {
        val next = if (paymentMode == option) null else option
        return copy(paymentMode = next, feeBasis = if (next == null) null else feeBasis)
    }

    fun pickBasis(option: FeeBasis): TalentForm = copy(feeBasis = if (feeBasis == option) null else option)

    /**
     * Everything under the weekly box belongs to it — switching it off clears it, or a
     * hidden schedule keeps gating the save.
     */
    fun toggleWeekly(on: Boolean, ids: TalentIds): TalentForm = copy(
        weekly = on,
        tranches = if (on) listOf(TrancheDraft(id = ids.next("t"),
            label = TalentSchedule.TRANCHE_TYPES.first())) else emptyList(),
    )

    /** Same shape for expenses: ticking opens the table with one blank row, unticking clears it. */
    fun toggleExpenses(on: Boolean, ids: TalentIds): TalentForm = copy(
        hasExpenses = on,
        expenses = if (on) listOf(ExpenseDraft(id = ids.next("e"))) else emptyList(),
    )

    fun setWithholdingType(type: WithholdingType): TalentForm = copy(withholdingType = type, withholdingValue = "")

    fun setAuthorised(on: Boolean): TalentForm =
        if (on) copy(withholdingAuthorised = true) else copy(withholdingAuthorised = false, authorisedRate = "",
            certificateFile = null)
}

/** Row ids for tranches and expenses; unique within a session, stable inside a recomposition. */
class TalentIds {
    private var counter = 0

    fun next(prefix: String): String {
        counter += 1
        return "tc-$prefix-$counter"
    }
}

/** The field names the form's verdicts are keyed by — the web's `errors` object. */
enum class TalentField {
    Person, Contract, Fee, Territory, Union, PaymentMode, FeeBasis, Withholding, AuthorisedRate, Schedule,
}

/** What is wrong with a form, once someone has tried to save it. */
object TalentValidation {

    /**
     * Every field that blocks a save. A schedule is optional, but a
     * half-allocated one is not — and it is only judged once there is a fee to
     * allocate: with the fee still blank, "allocate the whole fee" would be a
     * second message about the same empty box.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // One rule per field.
    fun errors(form: TalentForm, unionRequired: Boolean): Set<TalentField> {
        val totals = TalentSchedule.totals(form.tranches, form.fee, form.paidAs)
        return buildSet {
            if (form.person.isBlank()) add(TalentField.Person)
            if (form.contractFile == null) add(TalentField.Contract)
            if (num(form.fee) <= 0) add(TalentField.Fee)
            if (form.territory.isBlank()) add(TalentField.Territory)
            if (unionRequired && form.union.isBlank()) add(TalentField.Union)
            if (form.paymentMode == null) add(TalentField.PaymentMode)
            if (form.paymentMode != null && form.feeBasis == null) add(TalentField.FeeBasis)
            if (form.withholding && num(form.withholdingValue) <= 0) add(TalentField.Withholding)
            if (form.withholding && form.withholdingAuthorised && num(form.authorisedRate) <= 0) {
                add(TalentField.AuthorisedRate)
            }
            if (form.weekly && form.tranches.isNotEmpty() && num(form.fee) > 0 && !totals.balanced) {
                add(TalentField.Schedule)
            }
        }
    }
}

/**
 * The POST / PATCH body for `/api/v2/deal-memo/talent-contracts`, in the model's
 * own names — snake_case throughout, dates as epoch ms, attachments as the
 * already-uploaded envelopes — and its exact inverse, [fromContract], which is
 * how Edit reopens the SAME form.
 */
object TalentContractBody {

    /** A reference to quote on the payment, minted before the contract is saved: `TC-` and six digits. */
    fun newReference(random: () -> Int): String = "TC-${random()}"

    fun build(
        form: TalentForm,
        contract: TalentAttachment?,
        certificate: TalentAttachment?,
        reference: String,
        role: String,
        status: String = ISSUED,
    ): JsonObject {
        val withholding = form.withholding
        val authorised = withholding && form.withholdingAuthorised
        return buildJsonObject {
            put("talent_reference", reference)
            put("person", form.person.trim())
            put("role", role)
            put("long_form_contract", contract?.toJson() ?: JsonNull)
            put("territory_code", form.territory)
            put("union_identifier", form.union)
            put("fee", TalentNumbers.jsonNumber(num(form.fee)))
            put("contract_currency", form.currency)
            put("payment_mode", form.paymentMode?.wire.orEmpty())
            put("fee_basis", if (form.paymentMode != null) form.feeBasis?.wire.orEmpty() else "")
            put(
                "withholding",
                buildJsonObject {
                    put("applies", withholding)
                    put("basis", form.withholdingType.wire)
                    put("value", TalentNumbers.jsonNumber(if (withholding) num(form.withholdingValue) else 0.0))
                    put("authorised", authorised)
                    put("authorised_rate", TalentNumbers.jsonNumber(if (authorised) num(form.authorisedRate) else 0.0))
                    put("certificate_attachment", if (authorised) certificate?.toJson() ?: JsonNull else JsonNull)
                },
            )
            put(
                "payment_schedule",
                buildJsonObject {
                    put("cadence", if (form.weekly) WEEKLY else "")
                    put("paid_as", form.paidAs.wire)
                    put(
                        "tranches",
                        JsonArray(
                            if (form.weekly) form.tranches.map(::trancheJson) else emptyList(),
                        ),
                    )
                },
            )
            // A row the expenses term seeded and nobody filled in is not an
            // expense: no name AND nothing to pay drops out rather than reaching
            // the server as a £0 line called "".
            put(
                "expenses",
                JsonArray(
                    form.expenses
                        .filter { it.label.trim().isNotEmpty() || num(it.amount) > 0 }
                        .map(::expenseJson),
                ),
            )
            put("auto_time_cards", form.autoTimeCards)
            put("status", status)
        }
    }

    private fun trancheJson(t: TrancheDraft): JsonObject = buildJsonObject {
        put("label", t.label)
        put("start_date", TalentDates.toEpoch(t.start)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("end_date", TalentDates.toEpoch(t.end)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("weeks", TalentNumbers.jsonNumber(num(t.weeks)))
        put("value", TalentNumbers.jsonNumber(num(t.value)))
    }

    private fun expenseJson(e: ExpenseDraft): JsonObject = buildJsonObject {
        put("label", e.label)
        put("amount", TalentNumbers.jsonNumber(num(e.amount)))
        put("withhold", e.withhold)
        put("gross_up", e.withhold && e.grossUp)
    }

    /**
     * A stored contract → the form's state. Strings in the numeric fields the
     * inputs bind to (blank, not "0", for an untouched figure), yyyy-mm-dd
     * dates, and the stored attachment envelopes in the file slots.
     */
    fun fromContract(row: TalentContract, ids: TalentIds): TalentForm {
        val w = row.withholding
        val schedule = row.schedule
        return TalentForm(
            person = row.person,
            contractFile = row.longFormContract?.takeIf { it.isPresent }?.let { TalentFileSlot.Stored(it) },
            territory = row.territoryCode,
            union = row.unionIdentifier,
            paymentMode = PaymentMode.of(row.paymentMode),
            feeBasis = FeeBasis.of(row.feeBasis),
            fee = TalentNumbers.plain(row.fee),
            currency = row.currency,
            weekly = schedule.isWeekly,
            withholding = w.applies,
            withholdingType = w.basis,
            withholdingValue = TalentNumbers.plain(w.value),
            withholdingAuthorised = w.authorised,
            authorisedRate = TalentNumbers.plain(w.authorisedRate),
            certificateFile = w.certificate?.takeIf { it.isPresent }?.let { TalentFileSlot.Stored(it) },
            hasExpenses = row.expenses.isNotEmpty(),
            expenses = row.expenses.map { e ->
                ExpenseDraft(
                    id = ids.next("e"),
                    label = e.label,
                    amount = TalentNumbers.plain(e.amount),
                    withhold = e.withhold,
                    grossUp = e.grossUp,
                )
            },
            paidAs = schedule.paidAs,
            tranches = schedule.tranches.map { t ->
                TrancheDraft(
                    id = ids.next("t"),
                    label = t.label,
                    start = TalentDates.fromEpoch(t.startDate),
                    end = TalentDates.fromEpoch(t.endDate),
                    weeks = TalentNumbers.plain(t.weeks),
                    value = TalentNumbers.plain(t.value),
                )
            },
            autoTimeCards = row.autoTimeCards,
        )
    }

    const val ISSUED = "issued"
}
