package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A value on the memo card. */
sealed interface MemoValue {
    /** Printed as given — an empty string prints nothing, as the web's `children ?? "—"` does. */
    data class Text(val text: String) : MemoValue

    /** A figure with a quieter suffix — `£600.00 · 10h`. */
    data class Figure(val text: String, val suffix: String?) : MemoValue

    /** `BankIdentity`: the bank's name over its sort code and account lines; all empty is the dash. */
    data class Bank(val name: String, val sortCode: String, val account: String) : MemoValue

    /** An additional bank detail typed as `email`, `phone` or `url`, shown as a link. */
    data class Link(val type: String, val text: String) : MemoValue

    data class Passport(val files: List<DealAttachment>) : MemoValue

    /** Phase rates side by side: `Prep £…`, `Shoot £…`, `Wrap £…`. */
    data class Parts(val parts: List<String>) : MemoValue
}

/** `MF`: a label, its value, and how it lays out in the two-column grid. */
data class MemoField(val label: String, val value: MemoValue, val mono: Boolean = false, val wide: Boolean = false) {
    constructor(label: String, text: String, mono: Boolean = false, wide: Boolean = false) :
        this(label, MemoValue.Text(text), mono, wide)
}

/** A column of a memo table. */
data class MemoColumn(val title: String, val mono: Boolean = false, val alignEnd: Boolean = false)

/** The derived holiday-pay block under Rates. */
data class HolidayPayView(val tag: String, val inclusive: Boolean, val alert: String, val lines: List<RateLine>)

/** One `RateRow`; [derived] rows are the teal HP splits. */
data class RateLine(val label: String, val value: String, val derived: Boolean = false)

/** A section of the card, in the order the web stacks them. */
sealed interface MemoBlock {
    val title: String

    data class Fields(
        override val title: String,
        val fields: List<MemoField>,
        /** The DGA production fee's own grid under Rates. */
        val dga: List<MemoField> = emptyList(),
    ) : MemoBlock

    data class HolidayPay(override val title: String, val view: HolidayPayView) : MemoBlock

    data class Table(
        override val title: String,
        val columns: List<MemoColumn>,
        val rows: List<List<String>>,
        val emptyText: String,
    ) : MemoBlock

    data class Conditions(override val title: String, val items: List<String>) : MemoBlock
}

/** A production company from Production Setup. */
data class DealCompany(val id: String, val name: String)

/** A production unit the crew can join — its `unit_name` is a translation key. */
data class DealUnit(val id: String, val name: String)

/** A country of the ISD list: ISO code, dial code, name. */
data class DealCountry(val code: String, val dialCode: String, val name: String)

/** What the card resolves names against. */
data class MemoContext(
    val labels: DealCrewLabels = DealCrewLabels(),
    val projectName: String = "",
    val companies: List<DealCompany> = emptyList(),
    val units: List<DealUnit> = emptyList(),
    val countries: List<DealCountry> = emptyList(),
    /** The agency vendors, id → name. */
    val agencies: Map<String, String> = emptyMap(),
    val translate: (String) -> String? = DealLabels.translation,
    val zone: TimeZone = TimeZone.currentSystemDefault(),
)

/** The whole card. */
data class MemoCardView(
    val projectName: String,
    val external: Boolean,
    val crewHeading: String,
    val crewSubtitle: String,
    val identity: List<MemoField>,
    val blocks: List<MemoBlock>,
)

/**
 * The deal memo card as data (`DMDealPreviewPage.jsx:3405-4415`) — every row
 * the web prints, in its order, with its fallbacks — so the screen only lays
 * it out and a test can pin what a crew member is shown.
 */
object MemoCard {

    private const val DASH = MemoFormat.DASH

    fun build(deal: DealDoc, context: MemoContext): MemoCardView {
        val names = CrewNames.of(deal, context)
        val person = context.labels.person(deal.userId)
        return MemoCardView(
            projectName = context.projectName,
            external = deal.externalFlag,
            crewHeading = person?.fullName?.takeIf { it.isNotEmpty() } ?: deal.crewName ?: deal.fullLegalName
                ?: str(S.crew_member),
            crewSubtitle = listOf(names.role, names.department).filter { it.isNotEmpty() }.joinToString(" · "),
            identity = identity(deal, context),
            blocks = listOfNotNull(
                crewDetails(deal, context, names),
                bankDetails(deal),
                emergency(deal, context),
                representative(deal, context),
                loanOut(deal),
            ) + MemoTerms.blocks(deal, context),
        )
    }

    private fun identity(deal: DealDoc, context: MemoContext): List<MemoField> {
        val person = context.labels.person(deal.userId)
        val entityId = DocRead.text(DocRead.obj(deal.json, "territory_union"), "prod_entity")
        return listOf(
            MemoField(str(S.desktop_reference), deal.reference ?: DASH, mono = true),
            MemoField(str(S.status), deal.status.label),
            MemoField(
                str(S.desktop_dm_deal_memo_for),
                person?.fullName?.takeIf { it.isNotEmpty() } ?: deal.crewName
                    ?: if (DocRead.flag(deal.json, "is_external")) str(S.dm_step2_external_label) else DASH,
            ),
            MemoField(str(S.drive_created), MemoFormat.dateTime(deal.createdAt, context.zone)),
            MemoField(str(S.desktop_dm_production_entity), entityLabel(entityId, context)),
        )
    }

    /** `resolveProductionEntity`: the company's name, else the id humanised; the dash for none. */
    fun entityLabel(id: String?, context: MemoContext): String {
        if (id.isNullOrEmpty()) return DASH
        return context.companies.firstOrNull { it.id == id }?.name ?: DealLabels.formatLabel(id, context.translate)
    }

    private fun crewDetails(deal: DealDoc, context: MemoContext, names: CrewNames): MemoBlock {
        val cd = deal.crew
        fun text(key: String) = DocRead.text(cd, key) ?: DASH
        val fields = mutableListOf(
            MemoField(str(S.dm_step2_crew_name), text("crew_name")),
            MemoField(str(S.dm_req_full_legal_name), DocRead.text(cd, "full_legal_name") ?: deal.crewName ?: DASH),
            MemoField(str(S.dm_step2_preferred_name), text("preferred_name")),
            MemoField(str(S.dm_step2_screen_credit_designation), text("screen_credit_designation")),
            MemoField(str(S.department), names.department),
            MemoField(str(S.designation), names.role),
        )
        deal.customDesignation?.let { fields += MemoField(str(S.desktop_custom_designation), it) }
        fields += MemoField(str(S.dm_step2_crew_type), crewType(DocRead.text(cd, "crew_type")))
        fields += MemoField(str(S.dm_step2_reports_to_type), text("reports_to"))
        fields += MemoField(str(S.dm_step2_call_sheet_tier), text("call_sheet_tier"))
        fields += MemoField(str(S.dm_crew_emp_status), empStatus(DocRead.text(cd, "emp_status"), context))
        if (DocRead.text(cd, "agency_name") != null || DocRead.text(cd, "agency_id") != null) {
            fields += MemoField(str(S.desktop_agency), names.agency)
        }
        fields += MemoField(str(S.dm_step2_unit), unitName(cd, context).ifEmpty { DASH })
        fields += MemoField(
            str(S.gender),
            DealLabels.formatLabel(DocRead.text(cd, "gender"), context.translate).ifEmpty { DASH },
        )
        fields += MemoField(str(S.dm_step2_dob), MemoFormat.date(DocRead.number(cd, "dob")?.toLong(), context.zone))
        fields += MemoField(str(S.email), text("email"))
        fields += MemoField(str(S.dm_step2_mobile), text("mobile"))
        fields += MemoField(str(S.dm_edit_personal_insurance), text("insurance_no"))
        fields += MemoField(str(S.dm_step2_tax_code), text("tax_code"))
        fields += MemoField(
            str(S.dm_step2_right_to_work),
            DealLabels.formatLabel(DocRead.text(cd, "right_to_work"), context.translate).ifEmpty { DASH },
        )
        fields += MemoField(
            str(S.dm_step2_passport),
            MemoValue.Passport(passportList(cd?.get("passport_attachment"))),
            wide = true,
        )
        fields += MemoField(
            str(S.address),
            DealAddress.of(cd?.get("home_address")).format().ifEmpty { DASH },
            wide = true,
        )
        if (UkPayroll.appliesTo(deal)) {
            UkPayroll.memoRows(
                DocRead.obj(cd, UkPayroll.KEY),
                money = { MemoFormat.money(it, RateFormat.currencySymbol("GBP")) },
                date = { MemoFormat.date(it, context.zone) },
            ).forEach { row -> fields += MemoField(row.label, row.value?.ifEmpty { null } ?: DASH, mono = row.mono) }
        }
        return MemoBlock.Fields(str(S.dm_section_crew_details), fields)
    }

    private fun bankDetails(deal: DealDoc): MemoBlock {
        val bank = DocRead.obj(deal.json, "bank")
        fun text(key: String) = DocRead.text(bank, key) ?: DASH
        val sortRaw = DocRead.text(bank, "sort_code")?.trim().orEmpty()
        val fields = mutableListOf(
            MemoField(str(S.dm_step2_bank_account_holder), text("account_holder_name")),
            MemoField(
                str(S.dm_step2_bank_name),
                MemoValue.Bank(
                    name = (DocRead.text(bank, "name") ?: DocRead.text(bank, "bank_name"))?.trim().orEmpty(),
                    sortCode = MemoFormat.sortCode(sortRaw).ifEmpty { sortRaw },
                    account = DocRead.text(bank, "account_number")?.trim().orEmpty(),
                ),
            ),
            MemoField(str(S.dm_step2_bank_account_number), text("account_number")),
            MemoField(
                str(S.dm_step2_bank_sort_code),
                MemoFormat.sortCode(DocRead.text(bank, "sort_code")).ifEmpty { DASH },
            ),
            MemoField(str(S.dm_step2_bank_iban), text("iban_number")),
            MemoField(str(S.dm_step2_bank_swift), text("swift_code")),
        )
        DocRead.objects(bank?.get("additional_details"))
            .filter { DocRead.text(it, "field") != null || DocRead.text(it, "value") != null }
            .forEach { row ->
                val value = DocRead.text(row, "value")
                val type = DocRead.text(row, "field_type")
                val shown = when {
                    value == null -> MemoValue.Text(DASH)
                    type == "email" || type == "phone" || type == "url" -> MemoValue.Link(type, value)
                    else -> MemoValue.Text(value)
                }
                fields += MemoField(DocRead.text(row, "field") ?: str(S.desktop_detail), shown)
            }
        return MemoBlock.Fields(str(S.dm_step2_card_bank), fields)
    }

    private fun emergency(deal: DealDoc, context: MemoContext): MemoBlock {
        val cd = deal.crew
        val details = DocRead.obj(cd, "emergency_details")
        val number = DocRead.text(cd, "emergency_contact_number") ?: DocRead.text(details, "phone_number")
        return MemoBlock.Fields(
            str(S.dm_crew_step_emergency),
            listOf(
                MemoField(
                    str(S.contact_name),
                    DocRead.text(cd, "emergency_contact_name") ?: DocRead.text(details, "name")
                        ?: DocRead.text(cd, "emergency_contact") ?: DASH,
                ),
                MemoField(str(S.contact_number), phone(dial(DocRead.text(details, "country_code"), context), number)),
                MemoField(str(S.email), DocRead.text(details, "email") ?: DASH),
                MemoField(
                    str(S.address),
                    DealAddress.of(details?.get("address")).format().ifEmpty { DASH },
                    wide = true,
                ),
            ),
        )
    }

    private fun representative(deal: DealDoc, context: MemoContext): MemoBlock {
        val rep = DocRead.obj(deal.crew, "representative_details")
        return MemoBlock.Fields(
            str(S.dm_crew_step_representative),
            listOf(
                MemoField(str(S.desktop_representative_name), DocRead.text(rep, "name") ?: DASH),
                MemoField(
                    str(S.phone),
                    phone(dial(DocRead.text(rep, "country_code"), context), DocRead.text(rep, "phone_number")),
                ),
                MemoField(str(S.email), DocRead.text(rep, "email") ?: DASH),
                MemoField(str(S.address), DealAddress.of(rep?.get("address")).format().ifEmpty { DASH }, wide = true),
            ),
        )
    }

    /** Shown for a loan-out engagement, or whenever any company detail is on file. */
    private fun loanOut(deal: DealDoc): MemoBlock? {
        val company = DocRead.obj(deal.crew, "loan_out_company")
        val hasData =
            listOf("name", "country_code", "phone_number", "email").any { DocRead.text(company, it) != null } ||
            !isValueBlank(company?.get("address") ?: JsonNull)
        if (!CrewStatus.isLoanOut(DocRead.text(deal.crew, "emp_status")) && !hasData) return null
        return MemoBlock.Fields(
            str(S.dm_loanout_section_title),
            listOf(
                MemoField(str(S.dm_loanout_name), DocRead.text(company, "name") ?: DASH),
                // The raw dial code, no country lookup — unlike the two contacts above.
                MemoField(
                    str(S.phone),
                    phone(DocRead.text(company, "country_code"), DocRead.text(company, "phone_number")),
                ),
                MemoField(str(S.email), DocRead.text(company, "email") ?: DASH),
                MemoField(
                    str(S.address),
                    DealAddress.of(company?.get("address")).format().ifEmpty { DASH },
                    wide = true,
                ),
            ),
        )
    }

    private fun phone(code: String?, number: String?): String =
        listOfNotNull(code?.ifEmpty { null }, number?.ifEmpty { null }).joinToString(" ").ifEmpty { DASH }

    /** A stored ISO country as its dial code; anything unknown as it was stored. */
    private fun dial(iso: String?, context: MemoContext): String? =
        iso?.let { code -> context.countries.firstOrNull { it.code == code }?.dialCode ?: code }

    fun crewType(value: String?): String = when (value) {
        "shoot_crew" -> str(S.dm_step2_crew_type_shoot)
        "non_shoot_crew" -> str(S.dm_step2_crew_type_non_shoot)
        else -> DASH
    }

    /** The memo passes a territory string where a status list belongs, so this is always the humanised id. */
    fun empStatus(value: String?, context: MemoContext): String =
        value?.let { DealLabels.formatLabel(it, context.translate) } ?: DASH

    /** `crewUnitName`: the denormalised key translated, else the join unit's name, else the id humanised. */
    fun unitName(cd: JsonObject?, context: MemoContext): String {
        DocRead.text(cd, "unit_name")?.let { key ->
            return context.translate(key)?.takeIf { it.isNotEmpty() } ?: DealLabels.formatLabel(key, context.translate)
        }
        val unit = DocRead.text(cd, "unit") ?: return ""
        val match =
            context.units.firstOrNull { it.id == unit } ?: return DealLabels.formatLabel(unit, context.translate)
        return context.translate(match.name)?.takeIf { it.isNotEmpty() } ?: match.name
    }
}

/**
 * The card's department, designation and agency (`DMDealPreviewPage.jsx:1859-1904`).
 *
 * The deal's own crew details win over the directory (ZL-21120); each
 * resolver answers the dash for a miss, so a candidate counts only when it is
 * neither blank nor that dash. The agency is its name, never the raw id.
 */
data class CrewNames(val department: String, val role: String, val agency: String) {
    companion object {
        fun of(deal: DealDoc, context: MemoContext): CrewNames {
            val person = context.labels.person(deal.userId)
            fun hit(value: String?) = value?.takeIf { it.isNotEmpty() && it != MemoFormat.DASH }
            val customRole = deal.crew?.get("custom_designation")?.takeUnless { it is JsonNull }
            val rateCardRole = if (customRole != null) {
                DocRead.text(deal.crew, "custom_designation")
            } else {
                context.labels.designationLabel(deal.crewDesignationRef)
            }
            val cd = deal.crew
            val agencyId = DocRead.text(cd, "agency_id")
            return CrewNames(
                department = hit(context.labels.departmentLabel(deal.crewDepartmentRef))
                    ?: hit(person?.departmentName?.let { DealLabels.formatLabel(it, context.translate) })
                    ?: MemoFormat.DASH,
                role = hit(rateCardRole)
                    ?: hit(person?.designationName?.let { DealLabels.formatLabel(it, context.translate) })
                    ?: MemoFormat.DASH,
                agency = DocRead.text(cd, "agency_name") ?: agencyId?.let(context.agencies::get) ?: MemoFormat.DASH,
            )
        }
    }
}
