package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealPayload
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStatus
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.domain.preview.UkPayroll
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderSection
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.TerritoryFlag
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A deal section while its editor is closed (`renderSectionRO`): label and
 * value pairs, two to a row, a wide value on a row of its own.
 */
@Composable
internal fun BuilderReadOnlyView(state: DealMemoUiState, builder: BuilderState, sectionId: Int) {
    val form = builder.form
    when (sectionId) {
        DealValidators.TERRITORY -> TerritoryReadOnly(state, builder)
        DealValidators.CREW -> CrewReadOnly(state, builder)
        DealValidators.PERSONAL -> PersonalReadOnly(form)
        DealValidators.EMPLOYMENT -> EmploymentReadOnly(builder)
        DealValidators.DEAL -> DealStructureReadOnly(form)
        DealValidators.RATES -> RatesReadOnly(form)
        DealValidators.ALLOWANCES -> AllowancesReadOnly(form)
        BuilderSection.CONDITIONS -> ConditionsReadOnly(form)
        BuilderSection.PAYROLL -> PayrollReadOnly(state, form)
        else -> Unit
    }
}

// -- the grid -------------------------------------------------------------------------------------

private class RoField(
    val label: String,
    val value: String,
    val wide: Boolean,
    val required: Boolean,
    val leading: (@Composable () -> Unit)?,
)

private class RoScope {
    val fields = mutableListOf<RoField>()

    fun field(
        label: String,
        value: String?,
        wide: Boolean = false,
        required: Boolean = false,
        leading: (@Composable () -> Unit)? = null,
    ) {
        fields += RoField(label, value?.takeIf { it.isNotEmpty() } ?: DASH, wide, required, leading)
    }
}

/** `RO_GRID`: 210 px label, value, 210 px label, value — two columns under 640. */
@Composable
private fun RoGrid(build: RoScope.() -> Unit) {
    val fields = RoScope().apply(build).fields
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < NARROW
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (narrow) {
                fields.forEach { field ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RoLabel(field, Modifier.width(NARROW_LABEL_WIDTH.dp))
                        RoValue(field, Modifier.weight(1f))
                    }
                }
            } else {
                roRows(fields).forEach { row ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (row.size == 1 && row[0].wide) {
                            RoLabel(row[0], Modifier.width(LABEL_WIDTH.dp))
                            RoValue(row[0], Modifier.weight(1f))
                        } else {
                            row.forEach { field ->
                                RoLabel(field, Modifier.width(LABEL_WIDTH.dp))
                                RoValue(field, Modifier.weight(1f))
                            }
                            if (row.size == 1) {
                                Box(Modifier.width(LABEL_WIDTH.dp))
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun roRows(fields: List<RoField>): List<List<RoField>> {
    val rows = mutableListOf<List<RoField>>()
    var pending: RoField? = null
    fields.forEach { field ->
        val open = pending
        when {
            field.wide -> {
                if (open != null) rows += listOf(open)
                pending = null
                rows += listOf(field)
            }
            open == null -> pending = field
            else -> {
                rows += listOf(open, field)
                pending = null
            }
        }
    }
    pending?.let { rows += listOf(it) }
    return rows
}

@Composable
private fun RoLabel(field: RoField, modifier: Modifier) {
    val p = bp
    ZillitText(
        text = buildAnnotatedString {
            append(field.label.uppercase())
            if (field.required) withStyle(SpanStyle(color = p.red)) { append("*") }
        },
        style = DmType.sans(12.5.sp, FontWeight.SemiBold, 0.04.em),
        color = p.ink2,
        modifier = modifier.padding(top = 1.dp),
    )
}

@Composable
private fun RoValue(field: RoField, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field.leading?.invoke()
        ZillitText(text = field.value, style = DmType.sans(14.sp).copy(lineHeight = 21.sp), color = bp.ink)
    }
}

/** A stacked label over its value — the list-style sections. */
@Composable
private fun RoStack(label: String, lines: List<String>, numbered: Boolean = false, empty: String) {
    val p = bp
    Column {
        ZillitText(text = label.uppercase(), style = DmType.sans(12.5.sp, FontWeight.SemiBold, 0.04.em), color = p.ink2)
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(if (numbered) 2.dp else 4.dp)) {
            if (lines.isEmpty()) {
                ZillitText(text = empty, style = DmType.sans(14.sp), color = p.ink)
            } else {
                lines.forEachIndexed { index, line ->
                    Row {
                        if (numbered) {
                            ZillitText(
                                text = "${index + 1}.",
                                style = DmType.sans(14.sp),
                                color = p.ink,
                                modifier = Modifier.width(20.dp),
                            )
                        }
                        ZillitText(
                            text = line,
                            style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
                            color = p.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// -- sections -----------------------------------------------------------------------------------------

@Composable
private fun TerritoryReadOnly(state: DealMemoUiState, builder: BuilderState) {
    val form = builder.form
    val union = builder.reference.selectedUnion
    val pact = union?.get("pact") as? JsonObject
    val territory = form.text("territory")
    val company = state.projectSettings.view.companies.firstOrNull { row ->
        listOf("id", "_id").any { key ->
            row[key]?.takeUnless { it is JsonNull }?.let(Js::text) == form.text("productionEntity")
        }
    }
    RoGrid {
        field(
            str(S.desktop_dm_production_entity),
            company?.let { DocRead.text(it, "name") ?: DocRead.text(it, "company_name") },
        )
        field(str(S.dm_step1_production_type), form.text("productionType").takeIf { it.isNotEmpty() }?.let(::localised))
        val known = TerritoryCatalogue.territory(territory)
        field(
            str(S.dm_section_territory),
            known?.label ?: "🌐 $territory",
            leading = known?.let { { TerritoryFlag(it.id, 16.dp) } },
        )
        field(str(S.dm_rule_import_agreement), agreementLabel(form.text("union"), union))
        if ((pact?.get("bands") as? JsonArray).orEmpty().isNotEmpty()) field(
            str(S.dm_step1_budget_band_title),
            form.text("pactBand"),
        )
        if ((pact?.get("special_depts") as? JsonArray).orEmpty().isNotEmpty()) {
            field(str(S.desktop_dm_special_department), yesNo(form.flag("pactSpecialDept")))
        }
    }
}

@Composable
private fun CrewReadOnly(state: DealMemoUiState, builder: BuilderState) {
    val form = builder.form
    val catalogue = state.catalogue
    val user = state.crewDirectory?.firstOrNull { it.userId == form.text("userId") }
    val crewName = user?.fullName?.takeIf { it.isNotEmpty() }
        ?: form.text("crewName").ifEmpty { null }
        ?: form.text("fullLegalName").ifEmpty { null }
        ?: if (form.flag("isExternal")) str(S.dm_step2_external_label) else ""
    val role = when {
        form.text("jobTitle") == DealForm.CUSTOM_JOB_TITLE -> form.text("customJobTitle").ifEmpty { str(S.custom) }
        form.text("designation").isNotEmpty() -> catalogue.designationLabel(form.text("designation"))
        else -> form.text("jobTitle").ifEmpty { DASH }
    }
    RoGrid {
        field(str(S.dm_step2_crew_name), crewName)
        field(str(S.dm_nom_dept), state.labels.departmentLabel(form.text("department").ifEmpty { null }))
        field(str(S.dm_label_designation), role)
        if (form.text("jobTitle") == DealForm.CUSTOM_JOB_TITLE) field(
            str(S.desktop_dm_custom_designation),
            form.text("customJobTitle"),
        )
        field(
            str(S.dm_step2_crew_type),
            when (form.text("crewType")) {
                "shoot_crew" -> str(S.dm_step2_crew_type_shoot)
                "non_shoot_crew" -> str(S.dm_step2_crew_type_non_shoot)
                else -> DASH
            },
        )
        field(
            str(S.dm_step2_reports_to_type),
            if (form.text("reportsToType") == "HOD") "HOD" else form.text("reportsTo"),
        )
        field(str(S.dm_step2_call_sheet_tier), form.text("callSheetTier"))
        field(str(S.dm_step2_unit), unitLabel(form.text("unit"), state))
    }
}

@Suppress("LongMethod")
@Composable
private fun PersonalReadOnly(form: DealForm) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RoGrid {
            field(str(S.dm_req_full_legal_name), form.text("fullLegalName"))
            field(str(S.dm_step2_preferred_name), form.text("preferredName"))
            field(str(S.dm_step2_screen_credit_designation), form.text("screenCreditDesignation"))
            field(str(S.dm_step2_gender), DealLabels.formatLabel(form.text("gender")))
            field(str(S.dm_req_dob), dayDate(form.text("dob")))
            field(str(S.dm_req_email), form.text("email"), required = form.flag("isExternal"))
            field(str(S.dm_req_mobile), form.text("mobile"))
            field(str(S.dm_edit_personal_insurance), form.text("niNumber"))
            field(str(S.dm_crew_tax_code), form.text("taxCode"))
            field(str(S.dm_req_rtw), DealLabels.formatLabel(form.text("rightToWork")))
            val passports = DealPayload.passports(form["passportAttachment"])
            val names = passports.mapNotNull { (it as? JsonObject)?.let { att -> DocRead.text(att, "name") } }
            field(
                str(S.dm_step2_passport),
                names.joinToString(", ").ifEmpty { if (passports.isNotEmpty()) str(S.dm_docs_attached) else DASH },
            )
            field(str(S.dm_address_label), CrewFormValues.address(form["homeAddress"]).format(), wide = true)
            if (UkPayroll.appliesTo(form.text("territory"), form.text("employmentStatus"))) {
                UkPayroll.memoRows(
                    block = form.obj("uk"),
                    money = { value -> moneyGbp(value) },
                    date = { millis -> millis?.let { MemoFormat.date(it, TimeZone.UTC) } ?: DASH },
                ).forEach { row -> field(row.label, row.value) }
            }
        }
        Column {
            SubsectionLabel(str(S.dm_crew_step_bank))
            val bank = form.obj("bank")
            RoGrid {
                field(str(S.dm_req_account_holder), bankText(bank, "account_holder_name"))
                field(str(S.dm_step2_bank_name), bankText(bank, "name"))
                field(str(S.dm_step2_bank_account_number), bankText(bank, "account_number"))
                field(str(S.dm_step2_bank_sort_code), bankText(bank, "sort_code"))
                field("IBAN", bankText(bank, "iban_number"))
                field(str(S.dm_step2_bank_swift), bankText(bank, "swift_code"))
            }
        }
        Column {
            SubsectionLabel(str(S.dm_crew_step_emergency))
            RoGrid {
                field(str(S.contact_name), form.text("emergencyContactName").ifEmpty { form.text("emergencyContact") })
                field(
                    str(S.contact_number),
                    phoneText(form.text("emergencyCountryCode"), form.text("emergencyContactNumber")),
                )
                field(str(S.dm_req_email), form.text("emergencyEmail"))
                field(str(S.dm_address_label), CrewFormValues.address(form["emergencyAddress"]).format(), wide = true)
            }
        }
        Column {
            SubsectionLabel(str(S.dm_step2_card_representative))
            RoGrid {
                field(str(S.dm_step2_representing_agency), form.text("agencyName").ifEmpty { form.text("agencyId") })
                field(str(S.dm_step2_agency_name), form.text("representativeName"))
                field(
                    str(S.dm_step2_representative_phone),
                    phoneText(form.text("representativeCountryCode"), form.text("representativePhone")),
                )
                field(str(S.dm_req_email), form.text("representativeEmail"))
                field(
                    str(S.dm_address_label),
                    CrewFormValues.address(form["representativeAddress"]).format(),
                    wide = true,
                )
            }
        }
    }
}

@Composable
private fun EmploymentReadOnly(builder: BuilderState) {
    val form = builder.form
    val status = form.text("employmentStatus")
    val label = if (status.isEmpty()) {
        DASH
    } else {
        (builder.reference.selectedUnion?.get("emp_statuses") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { DocRead.text(it, "id") == status }
            ?.let { DocRead.text(it, "label") } ?: status
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RoGrid { field(str(S.dm_crew_emp_status), label) }
        if (CrewStatus.isLoanOut(status)) {
            Column {
                SubsectionLabel(str(S.dm_loanout_section_title))
                RoGrid {
                    field(str(S.dm_loanout_name), form.text("loanOutCompanyName"))
                    field(
                        str(S.dm_step2_representative_phone),
                        phoneText(form.text("loanOutCountryCode"), form.text("loanOutPhoneNumber")),
                    )
                    field(str(S.dm_req_email), form.text("loanOutEmail"))
                    field(
                        str(S.dm_address_label),
                        CrewFormValues.address(form["loanOutCompanyAddress"]).format(),
                        wide = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DealStructureReadOnly(form: DealForm) {
    RoGrid {
        field(str(S.dm_ds_card_type), DEAL_TYPES[form.text("dealType")] ?: form.text("dealType"))
        field(str(S.dm_ds_billing_basis), DealLabels.formatLabel(form.text("billingBasis")))
        field(str(S.dm_ds_start_date), dayDate(form.text("dealStart")))
        field(str(S.dm_label_end_date), dayDate(form.text("dealEnd")))
        field(str(S.desktop_dm_date_of_deal_memo), dayDate(form.text("dealMemoDate")))
        field(str(S.dm_prev_date_due), dayDate(form.text("completionDue")))
        if (form.flag("schedOn")) {
            listOf("Prep" to S.dm_ds_phase_prep, "Shoot" to S.dm_ds_phase_shoot, "Wrap" to S.dm_ds_phase_wrap)
                .forEach { (phase, labelKey) ->
                    val start = form.text("sched${phase}Start")
                    val end = form.text("sched${phase}End")
                    val range = if (start.isEmpty() && end.isEmpty()) DASH else "${dayDate(start)} → ${dayDate(end)}"
                    field(str(labelKey), range)
                }
        }
        field(
            str(S.dm_ds_card_notice),
            if (form.text("noticeType") == "custom") {
                "${form.text("noticeCustomValue").ifEmpty { DASH }} ${form.text("noticeCustomUnit")}".trim()
            } else {
                DealLabels.formatLabel(form.text("noticeType"))
            },
        )
        if (form.text("noticeReminderValue").isNotEmpty() || form.text("noticeReminderUnit").isNotEmpty()) {
            field(
                str(S.desktop_dm_notice_reminder),
                "${form.text("noticeReminderValue").ifEmpty { DASH }} ${form.text("noticeReminderUnit")}".trim(),
            )
        }
    }
}

@Composable
private fun RatesReadOnly(form: DealForm) {
    val symbol = RateFormat.currencySymbol(form.text("currency"))
    fun money(key: String) = form.text(key).takeIf { it.isNotEmpty() }?.let { "$symbol$it" }
    RoGrid {
        field(str(S.dm_rates_currency), form.text("currency"))
        field(str(S.dm_rates_payment_currency), form.text("paymentCurrency").ifEmpty { form.text("currency") })
        when (form.text("dealType")) {
            "picture" -> field(str(S.dm_rates_card_picture), money("pictureFee"))
            "buyout" -> {
                field(str(S.dm_rates_buyout_rate_weekly), money("buyoutRate"))
                field(str(S.desktop_dm_buy_out_day_rate), money("buyoutDailyRate"))
            }
            else -> {
                field(str(S.dm_rates_day_rate), money("dayRate"))
                val weekly = money("weeklyRate") ?: Js.parseFloat(form["dayRate"])?.let {
                    "$symbol${RateFormat.groupAmount(it * WEEK_DAYS)}"
                }
                field(str(S.dm_rates_weekly_rate), weekly)
                field(str(S.desktop_dm_working_hours_day), form.text("basicWorkingHoursPerDay"))
            }
        }
        field(str(S.desktop_dm_hp_treatment), DealLabels.formatLabel(form.text("hpMode")))
        field(
            str(S.dm_rates_card_phases),
            if (form.flag("phaseRatesOn")) {
                str(
                    S.desktop_dm_phase_rates_summary,
                    "$symbol${form.text("prepRate").ifEmpty { DASH }}",
                    "$symbol${form.text("shootRate").ifEmpty { DASH }}",
                    "$symbol${form.text("wrapRate").ifEmpty { DASH }}",
                )
            } else {
                str(S.desktop_off)
            },
        )
        if (form.flag("dgaWeeklyFee") || form.flag("dgaNegotiatedCOA")) {
            field(str(S.desktop_dm_weekly_fee), money("dgaWeeklyFee"))
            field(str(S.desktop_dm_coa_basis), DealLabels.formatLabel(form.text("dgaCOABasis")))
            field(str(S.desktop_dm_coa_amount), money("dgaNegotiatedCOA"))
        }
        field(str(S.desktop_dm_travel_day_paid_in_full), yesNo(form.flag("travelDayFull")))
        field(str(S.desktop_dm_rest_day_at_double), yesNo(form.flag("restDayDouble")))
        field(
            str(S.desktop_dm_overtimes_premiums_penalties_turnarounds_fringes),
            str(S.desktop_dm_from_the_agreements_working_rules) + if (form.flag("rulesCustomized")) {
                " " + str(S.desktop_dm_customised_for_this_deal_suffix)
            } else {
                ""
            },
            wide = true,
        )
    }
}

@Composable
private fun AllowancesReadOnly(form: DealForm) {
    val symbol = RateFormat.currencySymbol(form.text("currency"))
    fun enabled(key: String) = form.objects(key).filter { row -> !isFalse(row["on"]) && Js.truthy(row["name"]) }
    fun line(row: JsonObject): String = buildList {
        add(text(row["name"]))
        text(row["rate"]).takeIf { it.isNotEmpty() }?.let { add("$symbol$it/${text(row["basis"]).ifEmpty { "day" }}") }
        if (text(row["cap_type"]) == "capped" && text(row["cap_amount"]).isNotEmpty()) {
            add(str(S.desktop_dm_cap_amount_line, "$symbol${text(row["cap_amount"])}"))
        }
        text(row["nominal"]).takeIf { it.isNotEmpty() }?.let { add(str(S.desktop_dm_nominal_code_line, it)) }
    }.joinToString(" · ")
    val allowances = enabled("allowances")
    val rentals = enabled("rentals")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoStack(
            str(S.desktop_dm_allowances_count, allowances.size),
            allowances.map(::line),
            empty = str(S.desktop_dm_none_enabled),
        )
        RoStack(
            str(S.desktop_dm_rentals_count, rentals.size),
            rentals.map(::line),
            empty = str(S.desktop_dm_none_enabled),
        )
    }
}

@Composable
private fun ConditionsReadOnly(form: DealForm) {
    val conditions = form.list("customConditions").map { condition ->
        when (condition) {
            is JsonPrimitive -> if (condition.isString) condition.content.trim() else ""
            is JsonObject -> text(condition["condition"]).trim()
            else -> ""
        }
    }.filter { it.isNotEmpty() }
    val documents = form.objects("documents").mapNotNull { doc ->
        text(doc["title"]).ifEmpty { null }
            ?: (doc["attachment"] as? JsonObject)?.let { DocRead.text(it, "name") }
            ?: (doc["file"] as? JsonObject)?.let { DocRead.text(it, "name") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoGrid {
            field(str(S.dm_cond_card_location), DealLabels.formatLabel(form.text("workLocationType")))
            field(str(S.desktop_dm_travel_zone), DealLabels.formatLabel(form.text("travelZone")))
            field(str(S.desktop_dm_distant_location), yesNo(form.flag("distantLocation")))
        }
        RoStack(
            str(S.desktop_dm_conditions_count, conditions.size),
            conditions,
            numbered = true,
            empty = str(S.dm_rule_increment_none),
        )
        RoStack(
            str(S.desktop_dm_documents_count, documents.size),
            listOfNotNull(documents.joinToString(" · ").ifEmpty { null }),
            empty = str(S.dm_rule_increment_none),
        )
        if (form.text("additionalNotes").isNotEmpty()) {
            RoGrid { field(str(S.dm_ds_card_additional_notes), form.text("additionalNotes"), wide = true) }
        }
    }
}

@Composable
private fun PayrollReadOnly(state: DealMemoUiState, form: DealForm) {
    val defaults = state.projectSettings.view.payrollDefaults
    RoGrid {
        field(str(S.dm_pay_preview_bureau), form.text("bureau"))
        field(str(S.dm_allow_basis), DealLabels.formatLabel(form.text("payFrequency")))
        field(str(S.desktop_dm_first_pay_period), dayDate(form.text("firstPayPeriod")))
        field(str(S.desktop_dm_auto_sync), yesNo(Js.truthy(defaults["auto_sync"])))
        field(str(S.desktop_dm_notify_payroll), yesNo(Js.truthy(defaults["notify_payroll"])))
        field(str(S.desktop_dm_include_pdf), yesNo(Js.truthy(defaults["include_pdf"])))
    }
}

// -- formatters --------------------------------------------------------------------------------------------

/** `dashDate`: a `YYYY-MM-DD` day as `31 Aug 2026`; anything unreadable as it is. */
internal fun dayDate(value: String): String {
    if (value.isEmpty()) return DASH
    val date = runCatching { LocalDate.parse(value.take(ISO_DATE_LENGTH)) }.getOrNull() ?: return value
    return "${date.day.toString().padStart(2, '0')} ${MONTHS[date.month.ordinal]} ${date.year}"
}

private fun yesNo(value: Boolean) = if (value) str(S.yes) else str(S.no)

private fun phoneText(code: String, number: String): String = if (number.isEmpty()) "" else "$code $number".trim()

private fun bankText(bank: JsonObject?, key: String): String =
    bank?.get(key)?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()

private fun text(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    else -> Js.text(value)
}

private fun isFalse(value: JsonElement?): Boolean =
    value is JsonPrimitive && !value.isString && value.contentOrNull == "false"

private fun moneyGbp(value: JsonElement?): String {
    if (Js.isNullishOrEmpty(value)) return DASH
    return Js.toNumber(value)?.let { "£${RateFormat.groupAmount(it)}" } ?: DASH
}

/** A translation key through the translations, else humanised. */
internal fun localised(key: String): String =
    DealLabels.translation(key)?.takeIf { it.isNotEmpty() && it != key } ?: DealLabels.formatLabel(key)

/** `agreementLabel`: Non-Union for the synthetic id, else the agreement's label, short label or id. */
internal fun agreementLabel(union: String, agreement: JsonObject?): String = when {
    isNonUnionId(union) -> str(S.dm_create_non_union)
    else -> agreement?.let { DocRead.text(it, "label") ?: DocRead.text(it, "short_label") } ?: union
}

private fun unitLabel(unit: String, state: DealMemoUiState): String {
    if (unit.isEmpty()) return DASH
    val match = state.production.units.firstOrNull { it.id == unit } ?: return DealLabels.formatLabel(unit)
    return DealLabels.translation(match.name)?.takeIf { it.isNotEmpty() } ?: match.name
}

private val DEAL_TYPES get() = mapOf(
    "weekly" to str(S.desktop_dm_deal_type_weekly_rolling),
    "fixed" to str(S.desktop_dm_deal_type_fixed_term),
    "dayplayer" to str(S.desktop_dm_deal_type_day_player),
    "buyout" to str(S.desktop_dm_deal_type_buy_out),
    "picture" to str(S.desktop_dm_deal_type_picture_deal),
    "boxrental" to str(S.desktop_dm_deal_type_box_rental_only),
)

private val MONTHS get() = listOf(
    str(S.desktop_month_short_jan),
    str(S.desktop_month_short_feb),
    str(S.desktop_month_short_mar),
    str(S.desktop_month_short_apr),
    str(S.desktop_month_short_may),
    str(S.desktop_month_short_jun),
    str(S.desktop_month_short_jul),
    str(S.desktop_month_short_aug),
    str(S.desktop_month_short_sep),
    str(S.desktop_month_short_oct),
    str(S.desktop_month_short_nov),
    str(S.desktop_month_short_dec),
)
private const val DASH = MemoFormat.DASH
private const val LABEL_WIDTH = 210
private const val NARROW_LABEL_WIDTH = 150
private const val ISO_DATE_LENGTH = 10
private const val WEEK_DAYS = 5
private val NARROW = 640.dp
