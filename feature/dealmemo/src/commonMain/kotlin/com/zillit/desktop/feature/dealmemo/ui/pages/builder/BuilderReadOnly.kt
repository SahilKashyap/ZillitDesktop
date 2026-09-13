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
        field("Production Entity", company?.let { DocRead.text(it, "name") ?: DocRead.text(it, "company_name") })
        field("Production Type", form.text("productionType").takeIf { it.isNotEmpty() }?.let(::localised))
        val known = TerritoryCatalogue.territory(territory)
        field(
            "Territory",
            known?.label ?: "🌐 $territory",
            leading = known?.let { { TerritoryFlag(it.id, 16.dp) } },
        )
        field("Agreement", agreementLabel(form.text("union"), union))
        if ((pact?.get("bands") as? JsonArray).orEmpty().isNotEmpty()) field("Budget Band", form.text("pactBand"))
        if ((pact?.get("special_depts") as? JsonArray).orEmpty().isNotEmpty()) {
            field("Special Department", yesNo(form.flag("pactSpecialDept")))
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
        ?: if (form.flag("isExternal")) "External crew member" else ""
    val role = when {
        form.text("jobTitle") == DealForm.CUSTOM_JOB_TITLE -> form.text("customJobTitle").ifEmpty { "Custom" }
        form.text("designation").isNotEmpty() -> catalogue.designationLabel(form.text("designation"))
        else -> form.text("jobTitle").ifEmpty { DASH }
    }
    RoGrid {
        field("Crew Name", crewName)
        field("Department", state.labels.departmentLabel(form.text("department").ifEmpty { null }))
        field("Designation", role)
        if (form.text("jobTitle") == DealForm.CUSTOM_JOB_TITLE) field("Custom Designation", form.text("customJobTitle"))
        field(
            "Crew Type",
            when (form.text("crewType")) {
                "shoot_crew" -> "Shooting Crew"
                "non_shoot_crew" -> "Non-Shooting Crew"
                else -> DASH
            },
        )
        field("Reports To", if (form.text("reportsToType") == "HOD") "HOD" else form.text("reportsTo"))
        field("Call Sheet Tier", form.text("callSheetTier"))
        field("Unit", unitLabel(form.text("unit"), state))
    }
}

@Suppress("LongMethod")
@Composable
private fun PersonalReadOnly(form: DealForm) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RoGrid {
            field("Full Legal Name", form.text("fullLegalName"))
            field("Screen Credit", form.text("preferredName"))
            field("Screen Credit Designation", form.text("screenCreditDesignation"))
            field("Gender", DealLabels.formatLabel(form.text("gender")))
            field("Date of Birth", dayDate(form.text("dob")))
            field("Email", form.text("email"), required = form.flag("isExternal"))
            field("Mobile", form.text("mobile"))
            field("Insurance / NI No.", form.text("niNumber"))
            field("Tax Code", form.text("taxCode"))
            field("Right to Work", DealLabels.formatLabel(form.text("rightToWork")))
            val passports = DealPayload.passports(form["passportAttachment"])
            val names = passports.mapNotNull { (it as? JsonObject)?.let { att -> DocRead.text(att, "name") } }
            field(
                "Passport / ID",
                names.joinToString(", ").ifEmpty { if (passports.isNotEmpty()) "Attached" else DASH },
            )
            field("Address", CrewFormValues.address(form["homeAddress"]).format(), wide = true)
            if (UkPayroll.appliesTo(form.text("territory"), form.text("employmentStatus"))) {
                UkPayroll.memoRows(
                    block = form.obj("uk"),
                    money = { value -> moneyGbp(value) },
                    date = { millis -> millis?.let { MemoFormat.date(it, TimeZone.UTC) } ?: DASH },
                ).forEach { row -> field(row.label, row.value) }
            }
        }
        Column {
            SubsectionLabel("Bank Details")
            val bank = form.obj("bank")
            RoGrid {
                field("Account Holder Name", bankText(bank, "account_holder_name"))
                field("Bank Name", bankText(bank, "name"))
                field("Account Number", bankText(bank, "account_number"))
                field("Sort Code", bankText(bank, "sort_code"))
                field("IBAN", bankText(bank, "iban_number"))
                field("SWIFT / BIC", bankText(bank, "swift_code"))
            }
        }
        Column {
            SubsectionLabel("Emergency Details")
            RoGrid {
                field("Contact Name", form.text("emergencyContactName").ifEmpty { form.text("emergencyContact") })
                field(
                    "Contact Number",
                    phoneText(form.text("emergencyCountryCode"), form.text("emergencyContactNumber")),
                )
                field("Email", form.text("emergencyEmail"))
                field("Address", CrewFormValues.address(form["emergencyAddress"]).format(), wide = true)
            }
        }
        Column {
            SubsectionLabel("Agency/Representative Details")
            RoGrid {
                field("Representing Agency", form.text("agencyName").ifEmpty { form.text("agencyId") })
                field("Agency Name", form.text("representativeName"))
                field("Phone", phoneText(form.text("representativeCountryCode"), form.text("representativePhone")))
                field("Email", form.text("representativeEmail"))
                field("Address", CrewFormValues.address(form["representativeAddress"]).format(), wide = true)
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
        RoGrid { field("Employment Status", label) }
        if (CrewStatus.isLoanOut(status)) {
            Column {
                SubsectionLabel("Loan Out Company")
                RoGrid {
                    field("Company Name", form.text("loanOutCompanyName"))
                    field("Phone", phoneText(form.text("loanOutCountryCode"), form.text("loanOutPhoneNumber")))
                    field("Email", form.text("loanOutEmail"))
                    field("Address", CrewFormValues.address(form["loanOutCompanyAddress"]).format(), wide = true)
                }
            }
        }
    }
}

@Composable
private fun DealStructureReadOnly(form: DealForm) {
    RoGrid {
        field("Deal Type", DEAL_TYPES[form.text("dealType")] ?: form.text("dealType"))
        field("Billing Basis", DealLabels.formatLabel(form.text("billingBasis")))
        field("Start Date", dayDate(form.text("dealStart")))
        field("End Date", dayDate(form.text("dealEnd")))
        field("Date of Deal Memo", dayDate(form.text("dealMemoDate")))
        field("Date Due", dayDate(form.text("completionDue")))
        if (form.flag("schedOn")) {
            listOf("Prep", "Shoot", "Wrap").forEach { phase ->
                val start = form.text("sched${phase}Start")
                val end = form.text("sched${phase}End")
                field(phase, if (start.isEmpty() && end.isEmpty()) DASH else "${dayDate(start)} → ${dayDate(end)}")
            }
        }
        field(
            "Notice Period",
            if (form.text("noticeType") == "custom") {
                "${form.text("noticeCustomValue").ifEmpty { DASH }} ${form.text("noticeCustomUnit")}".trim()
            } else {
                DealLabels.formatLabel(form.text("noticeType"))
            },
        )
        if (form.text("noticeReminderValue").isNotEmpty() || form.text("noticeReminderUnit").isNotEmpty()) {
            field(
                "Notice Reminder",
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
        field("Contract Currency", form.text("currency"))
        field("Payment Currency", form.text("paymentCurrency").ifEmpty { form.text("currency") })
        when (form.text("dealType")) {
            "picture" -> field("Picture Fee", money("pictureFee"))
            "buyout" -> {
                field("Buy-Out Rate (weekly)", money("buyoutRate"))
                field("Buy-Out Day Rate", money("buyoutDailyRate"))
            }
            else -> {
                field("Day Rate", money("dayRate"))
                val weekly = money("weeklyRate") ?: Js.parseFloat(form["dayRate"])?.let {
                    "$symbol${RateFormat.groupAmount(it * WEEK_DAYS)}"
                }
                field("Weekly Rate", weekly)
                field("Working Hours / Day", form.text("basicWorkingHoursPerDay"))
            }
        }
        field("HP Treatment", DealLabels.formatLabel(form.text("hpMode")))
        field(
            "Phase Rates",
            if (form.flag("phaseRatesOn")) {
                "Prep $symbol${form.text("prepRate").ifEmpty { DASH }} · Shoot " +
                    "$symbol${form.text("shootRate").ifEmpty { DASH }} · " +
                    "Wrap $symbol${form.text("wrapRate").ifEmpty { DASH }}"
            } else {
                "Off"
            },
        )
        if (form.flag("dgaWeeklyFee") || form.flag("dgaNegotiatedCOA")) {
            field("Weekly Fee", money("dgaWeeklyFee"))
            field("COA Basis", DealLabels.formatLabel(form.text("dgaCOABasis")))
            field("COA Amount", money("dgaNegotiatedCOA"))
        }
        field("Travel Day Paid in Full", yesNo(form.flag("travelDayFull")))
        field("Rest Day at Double", yesNo(form.flag("restDayDouble")))
        field(
            "Overtimes · Premiums · Penalties · Turnarounds · Fringes",
            "From the agreement's working rules" + if (form.flag("rulesCustomized")) {
                " — customised for this deal (open Edit to review)"
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
            add("cap $symbol${text(row["cap_amount"])}")
        }
        text(row["nominal"]).takeIf { it.isNotEmpty() }?.let { add("nominal $it") }
    }.joinToString(" · ")
    val allowances = enabled("allowances")
    val rentals = enabled("rentals")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoStack("Allowances (${allowances.size})", allowances.map(::line), empty = "None enabled")
        RoStack("Rentals (${rentals.size})", rentals.map(::line), empty = "None enabled")
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
            field("Work Location", DealLabels.formatLabel(form.text("workLocationType")))
            field("Travel Zone", DealLabels.formatLabel(form.text("travelZone")))
            field("Distant Location", yesNo(form.flag("distantLocation")))
        }
        RoStack("Conditions (${conditions.size})", conditions, numbered = true, empty = "None")
        RoStack(
            "Documents (${documents.size})",
            listOfNotNull(documents.joinToString(" · ").ifEmpty { null }),
            empty = "None",
        )
        if (form.text("additionalNotes").isNotEmpty()) {
            RoGrid { field("Additional Notes", form.text("additionalNotes"), wide = true) }
        }
    }
}

@Composable
private fun PayrollReadOnly(state: DealMemoUiState, form: DealForm) {
    val defaults = state.projectSettings.view.payrollDefaults
    RoGrid {
        field("Bureau", form.text("bureau"))
        field("Pay Frequency", DealLabels.formatLabel(form.text("payFrequency")))
        field("First Pay Period", dayDate(form.text("firstPayPeriod")))
        field("Auto-sync", yesNo(Js.truthy(defaults["auto_sync"])))
        field("Notify Payroll", yesNo(Js.truthy(defaults["notify_payroll"])))
        field("Include PDF", yesNo(Js.truthy(defaults["include_pdf"])))
    }
}

// -- formatters --------------------------------------------------------------------------------------------

/** `dashDate`: a `YYYY-MM-DD` day as `31 Aug 2026`; anything unreadable as it is. */
internal fun dayDate(value: String): String {
    if (value.isEmpty()) return DASH
    val date = runCatching { LocalDate.parse(value.take(ISO_DATE_LENGTH)) }.getOrNull() ?: return value
    return "${date.day.toString().padStart(2, '0')} ${MONTHS[date.month.ordinal]} ${date.year}"
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

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
    isNonUnionId(union) -> "Non-Union"
    else -> agreement?.let { DocRead.text(it, "label") ?: DocRead.text(it, "short_label") } ?: union
}

private fun unitLabel(unit: String, state: DealMemoUiState): String {
    if (unit.isEmpty()) return DASH
    val match = state.production.units.firstOrNull { it.id == unit } ?: return DealLabels.formatLabel(unit)
    return DealLabels.translation(match.name)?.takeIf { it.isNotEmpty() } ?: match.name
}

private val DEAL_TYPES = mapOf(
    "weekly" to "Weekly Rolling",
    "fixed" to "Fixed Term",
    "dayplayer" to "Day Player",
    "buyout" to "Buy-Out",
    "picture" to "Picture Deal",
    "boxrental" to "Box Rental Only",
)

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private const val DASH = MemoFormat.DASH
private const val LABEL_WIDTH = 210
private const val NARROW_LABEL_WIDTH = 150
private const val ISO_DATE_LENGTH = 10
private const val WEEK_DAYS = 5
private val NARROW = 640.dp
