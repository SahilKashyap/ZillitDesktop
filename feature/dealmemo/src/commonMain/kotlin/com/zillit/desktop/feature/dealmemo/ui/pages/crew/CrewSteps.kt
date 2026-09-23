@file:Suppress("MatchingDeclarationName") // The crew form's steps; CrewFormModel is only what they share.

package com.zillit.desktop.feature.dealmemo.ui.pages.crew

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewNames
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStep
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoCard
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoContext
import com.zillit.desktop.feature.dealmemo.domain.preview.UkPayroll
import com.zillit.desktop.feature.dealmemo.domain.preview.passportList
import com.zillit.desktop.feature.dealmemo.ui.CrewFormEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Everything a step needs: the draft and the deal it edits, what is required, and which errors show. */
internal class CrewFormModel(
    val gate: DealDoc,
    val draft: CrewDraft,
    val context: MemoContext,
    val countries: List<DealCountry>,
    val required: Set<String>,
    val shownErrors: Map<CrewField, String>,
    val uploading: Boolean,
    val onEvent: (DealMemoEvent) -> Unit,
) {
    val details: JsonObject get() = draft.crewDetails

    fun req(path: String): Boolean = path in required

    fun text(key: String): String = DocRead.text(details, key).orEmpty()

    fun section(name: String): JsonObject? = DocRead.obj(details, name)

    fun error(field: CrewField): String? = shownErrors[field]

    fun crew(key: String, value: String) = onEvent(CrewFormEvent.Crew(key, JsonPrimitive(value)))

    fun crew(key: String, value: JsonElement) = onEvent(CrewFormEvent.Crew(key, value))

    fun section(name: String, key: String, value: String) =
        onEvent(CrewFormEvent.Section(name, key, JsonPrimitive(value)))

    fun section(name: String, key: String, value: JsonElement) = onEvent(CrewFormEvent.Section(name, key, value))

    fun bank(key: String, value: String) = onEvent(CrewFormEvent.Bank(key, JsonPrimitive(value)))

    fun touch(field: CrewField) = onEvent(CrewFormEvent.Touch(field))
}

/** The step on screen, its card and — on the first step — the UK payroll card under it. */
@Composable
internal fun CrewStepContent(step: CrewStep, model: CrewFormModel) {
    when (step) {
        CrewStep.Crew -> CrewDetailsStep(model)
        CrewStep.Emergency -> EmergencyStep(model)
        CrewStep.Representative -> RepresentativeStep(model)
        CrewStep.Bank -> BankStep(model)
        CrewStep.LoanOut -> LoanOutStep(model)
    }
}

/** Step 1, in the memo's own row order; accountant-owned values are printed, never boxed. */
@Suppress("LongMethod")
@Composable
private fun CrewDetailsStep(m: CrewFormModel) {
    val cd = m.gate.crew
    val names = remember(m.gate, m.context) { CrewNames.of(m.gate, m.context) }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val passports = passportList(m.details["passport_attachment"])
    SectionCard(title = str(S.dm_crew_step_crew), tag = str(S.dm_nda_fill_title)) {
        FormGrid {
            half { ReadOnlyRow(str(S.dm_step2_crew_name), DocRead.text(cd, "crew_name")) }
            half {
                LabelledInput(
                    label = str(S.dm_req_full_legal_name),
                    value = m.text("full_legal_name"),
                    onValueChange = { m.crew("full_legal_name", it) },
                    placeholder = DocRead.text(cd, "crew_name") ?: str(S.desktop_dm_name_as_it_appears_on_id),
                    required = m.req("full_legal_name"),
                )
            }
            half {
                CrewText(m, str(S.dm_step2_preferred_name), "preferred_name", str(S.desktop_dm_name_shown_on_credits))
            }
            half {
                CrewText(
                    m,
                    str(S.dm_step2_screen_credit_designation),
                    "screen_credit_designation",
                    str(S.desktop_dm_as_shown_in_credits),
                )
            }
            half { ReadOnlyRow(str(S.dm_nom_dept), names.department) }
            half { ReadOnlyRow(str(S.dm_label_designation), names.role) }
            DocRead.text(cd, "custom_designation")?.let { custom ->
                half { ReadOnlyRow(str(S.desktop_dm_custom_designation), custom) }
            }
            half { ReadOnlyRow(str(S.dm_step2_crew_type), MemoCard.crewType(DocRead.text(cd, "crew_type"))) }
            half { ReadOnlyRow(str(S.dm_step2_reports_to_type), DocRead.text(cd, "reports_to")) }
            half { ReadOnlyRow(str(S.dm_step2_call_sheet_tier), DocRead.text(cd, "call_sheet_tier")) }
            half {
                ReadOnlyRow(str(S.dm_crew_emp_status), MemoCard.empStatus(DocRead.text(cd, "emp_status"), m.context))
            }
            if (DocRead.text(cd, "agency_name") != null || DocRead.text(cd, "agency_id") != null) {
                half { ReadOnlyRow(str(S.desktop_dm_agency), names.agency) }
            }
            half { ReadOnlyRow(str(S.dm_step2_unit), MemoCard.unitName(cd, m.context)) }
            half {
                LabelledSelect(
                    str(S.dm_step2_gender),
                    m.text("gender"),
                    CrewFormValues.GENDERS,
                    { m.crew("gender", it) },
                    m.req("gender"),
                )
            }
            half {
                Column {
                    RowLabel(str(S.dm_req_dob), m.req("dob"))
                    DateInput(
                        // A date of birth before 1970 is a negative epoch — still a date.
                        millis = DocRead.number(m.details, "dob")?.toLong(),
                        onChange = { m.crew("dob", JsonPrimitive(it)) },
                        zone = TimeZone.currentSystemDefault(),
                        max = today,
                    )
                }
            }
            half {
                LabelledInput(
                    label = str(S.dm_req_email),
                    value = m.text("email"),
                    onValueChange = { m.crew("email", it) },
                    placeholder = "name@example.com",
                    required = m.req("email"),
                    error = m.error(CrewField.Email),
                    onBlur = { m.touch(CrewField.Email) },
                )
            }
            half {
                LabelledInput(
                    label = str(S.dm_req_mobile),
                    value = CrewFormRules.sanitizePhone(m.text("mobile"), allowPlus = true),
                    onValueChange = { m.crew("mobile", CrewFormRules.sanitizePhone(it, allowPlus = true)) },
                    placeholder = "+44 7700 900000",
                    required = m.req("mobile"),
                    error = m.error(CrewField.Mobile),
                    onBlur = { m.touch(CrewField.Mobile) },
                )
            }
            half { CrewText(m, str(S.dm_edit_personal_insurance), "insurance_no", str(S.dm_step2_ni_number_hint)) }
            half { CrewText(m, str(S.dm_crew_tax_code), "tax_code", str(S.dm_step2_tax_code_hint)) }
            half {
                LabelledSelect(
                    label = str(S.dm_req_rtw),
                    value = m.text("right_to_work"),
                    options = CrewFormValues.RIGHT_TO_WORK.map { it to it },
                    onPick = { m.crew("right_to_work", it) },
                    required = m.req("right_to_work"),
                )
            }
            wide {
                Column {
                    RowLabel(str(S.dm_step2_passport), m.req("passport_attachment"))
                    PassportUploader(
                        files = passports,
                        uploading = m.uploading,
                        onAdd = { m.onEvent(CrewFormEvent.AddPassport) },
                        onView = { m.onEvent(PreviewEvent.ViewPassport(it)) },
                        onRemove = { m.onEvent(CrewFormEvent.RemovePassport(it)) },
                    )
                }
            }
            wide {
                AddressBlock(
                    label = str(S.dm_address_label),
                    address = CrewFormValues.address(m.details["home_address"]),
                    countries = m.countries,
                    onChange = { m.crew("home_address", CrewFormValues.addressJson(it)) },
                    required = m.req("home_address"),
                )
            }
        }
    }
    if (UkPayroll.appliesTo(m.gate)) {
        Spacer(Modifier.height(16.dp))
        // The fieldset's first question is the card's heading, so the card has no title of its own.
        SectionCard(title = null, accent = CardAccent.Teal) {
            UkPayrollFieldset(
                block = CrewDraft.ukFromWire(DocRead.obj(m.details, UkPayroll.KEY)),
                onPatch = { m.onEvent(CrewFormEvent.UkPatch(it)) },
            )
        }
    }
}

@Composable
private fun CrewText(m: CrewFormModel, label: String, key: String, placeholder: String) {
    LabelledInput(
        label = label,
        value = m.text(key),
        onValueChange = { m.crew(key, it) },
        placeholder = placeholder,
        required = m.req(key),
    )
}

/** Step 2: the name and number are written flat and nested together. */
@Composable
private fun EmergencyStep(m: CrewFormModel) {
    val emergency = m.section("emergency_details")
    SectionCard(
        title = str(S.dm_crew_step_emergency),
        tag = str(S.desktop_dm_next_of_kin),
        tone = TagTone.Red,
        accent = CardAccent.Red,
    ) {
        FormGrid {
            half {
                LabelledInput(
                    label = str(S.contact_name),
                    value = m.text("emergency_contact_name"),
                    onValueChange = { m.onEvent(CrewFormEvent.EmergencyName(it)) },
                    placeholder = str(S.dm_step2_agency_name_hint),
                    required = m.req("emergency_contact_name"),
                )
            }
            wide {
                PhoneRow(
                    label = str(S.contact_number),
                    countries = m.countries,
                    storedCode = DocRead.text(emergency, "country_code").orEmpty(),
                    number = CrewFormRules.sanitizePhone(m.text("emergency_contact_number"), allowPlus = false),
                    mode = DialMode.Iso,
                    onCode = { m.section("emergency_details", "country_code", it) },
                    onNumber = {
                        m.onEvent(CrewFormEvent.EmergencyNumber(CrewFormRules.sanitizePhone(it, allowPlus = false)))
                    },
                    onBlur = { m.touch(CrewField.EmergencyPhone) },
                    error = m.error(CrewField.EmergencyPhone),
                    required = m.req("emergency_contact_number"),
                )
            }
            half {
                LabelledInput(
                    label = str(S.dm_req_email),
                    value = DocRead.text(emergency, "email").orEmpty(),
                    onValueChange = { m.section("emergency_details", "email", it) },
                    placeholder = "name@example.com",
                    required = m.req("emergency_details.email"),
                    error = m.error(CrewField.EmergencyEmail),
                    onBlur = { m.touch(CrewField.EmergencyEmail) },
                )
            }
            wide {
                AddressBlock(
                    label = str(S.dm_address_label),
                    address = CrewFormValues.address(emergency?.get("address")),
                    countries = m.countries,
                    onChange = { m.section("emergency_details", "address", CrewFormValues.addressJson(it)) },
                    required = m.req("emergency_details.address"),
                )
            }
        }
    }
}

/** Step 3: an agent or representative, if there is one. */
@Suppress("LongMethod")
@Composable
private fun RepresentativeStep(m: CrewFormModel) {
    val rep = m.section("representative_details")
    val section = "representative_details"
    SectionCard(
        title = str(S.dm_crew_step_representative),
        tag = str(S.desktop_dm_if_applicable),
        tone = TagTone.Blue,
        accent = CardAccent.Blue,
    ) {
        FormGrid {
            half {
                LabelledInput(
                    label = str(S.desktop_representative_name),
                    value = DocRead.text(rep, "name").orEmpty(),
                    onValueChange = { m.section(section, "name", it) },
                    placeholder = str(S.dm_step2_agency_name_hint),
                    required = m.req("$section.name"),
                )
            }
            wide {
                PhoneRow(
                    label = str(S.dm_step2_representative_phone),
                    countries = m.countries,
                    storedCode = DocRead.text(rep, "country_code").orEmpty(),
                    number = CrewFormRules.sanitizePhone(
                        DocRead.text(rep, "phone_number").orEmpty(),
                        allowPlus = false,
                    ),
                    mode = DialMode.Iso,
                    onCode = { m.section(section, "country_code", it) },
                    onNumber = {
                        m.section(section, "phone_number", CrewFormRules.sanitizePhone(it, allowPlus = false))
                    },
                    onBlur = { m.touch(CrewField.RepresentativePhone) },
                    error = m.error(CrewField.RepresentativePhone),
                    required = m.req("$section.phone_number"),
                )
            }
            half {
                LabelledInput(
                    label = str(S.dm_req_email),
                    value = DocRead.text(rep, "email").orEmpty(),
                    onValueChange = { m.section(section, "email", it) },
                    placeholder = "name@example.com",
                    required = m.req("$section.email"),
                    error = m.error(CrewField.RepresentativeEmail),
                    onBlur = { m.touch(CrewField.RepresentativeEmail) },
                )
            }
            wide {
                AddressBlock(
                    label = str(S.dm_address_label),
                    address = CrewFormValues.address(rep?.get("address")),
                    countries = m.countries,
                    onChange = { m.section(section, "address", CrewFormValues.addressJson(it)) },
                    required = m.req("$section.address"),
                )
            }
        }
    }
}

/** Step 4: six account fields and the additional details — no currency row, no format checks. */
@Composable
private fun BankStep(m: CrewFormModel) {
    val bank = m.draft.bank
    fun bankText(key: String) = DocRead.text(bank, key).orEmpty()
    SectionCard(
        title = str(S.dm_crew_step_bank),
        tag = str(S.desktop_dm_payment_account),
        tone = TagTone.Teal,
        accent = CardAccent.Teal,
    ) {
        FormGrid {
            half {
                BankText(
                    m,
                    str(S.dm_req_account_holder),
                    "account_holder_name",
                    str(S.desktop_dm_as_it_appears_on_the_account),
                )
            }
            half { BankText(m, str(S.dm_step2_bank_name), "name", str(S.dm_step2_bank_name_hint)) }
            half {
                LabelledInput(
                    label = str(S.dm_step2_bank_account_number),
                    value = bankText("account_number"),
                    onValueChange = { m.bank("account_number", CrewFormValues.accountNumber(it)) },
                    placeholder = "12345678",
                    required = m.req("bank.account_number"),
                )
            }
            half {
                val stored = bankText("sort_code")
                val digits = stored.filter { it.isDigit() }
                // Stored as up to six digits and drawn as XX-XX-XX; a longer legacy value shows as it was stored.
                val legacy = digits.length > SORT_CODE_DIGITS
                Column {
                    RowLabel(str(S.dm_step2_bank_sort_code), m.req("bank.sort_code"))
                    FormInput(
                        value = if (legacy) stored else digits,
                        onValueChange = { m.bank("sort_code", CrewFormRules.stripSortCode(it)) },
                        placeholder = "20-48-91",
                        visualTransformation = if (legacy) VisualTransformation.None else SortCodeDashes,
                    )
                }
            }
            half { BankText(m, "IBAN", "iban_number", "GB29 NWBK 6016 1331 9268 19") }
            half { BankText(m, str(S.dm_step2_bank_swift), "swift_code", "BARCGB22") }
        }
        AdditionalDetails(
            rows = DocRead.objects(bank["additional_details"]),
            onChange = { m.onEvent(CrewFormEvent.Bank("additional_details", it)) },
        )
    }
}

private const val SORT_CODE_DIGITS = 6
private val SortCodeDashes = PairSeparators('-')

@Composable
private fun BankText(m: CrewFormModel, label: String, key: String, placeholder: String) {
    LabelledInput(
        label = label,
        value = DocRead.text(m.draft.bank, key).orEmpty(),
        onValueChange = { m.bank(key, it) },
        placeholder = placeholder,
        required = m.req("bank.$key"),
    )
}

/** Step 5, loan-out engagements only: no asterisks, never a blocker; the phone code is stored as its dial. */
@Composable
private fun LoanOutStep(m: CrewFormModel) {
    val company = m.section("loan_out_company")
    val section = "loan_out_company"
    SectionCard(
        title = str(S.dm_loanout_section_title),
        tag = str(S.company_details),
        tone = TagTone.Blue,
        accent = CardAccent.Blue,
    ) {
        FormGrid {
            half {
                LabelledInput(
                    label = str(S.dm_loanout_name),
                    value = DocRead.text(company, "name").orEmpty(),
                    onValueChange = { m.section(section, "name", it) },
                    placeholder = "Jane Doe Productions Ltd",
                )
            }
            wide {
                PhoneRow(
                    label = str(S.dm_loanout_phone),
                    countries = m.countries,
                    storedCode = DocRead.text(company, "country_code").orEmpty(),
                    number = CrewFormRules.sanitizePhone(
                        DocRead.text(company, "phone_number").orEmpty(),
                        allowPlus = false,
                    ),
                    mode = DialMode.Dial,
                    onCode = { m.section(section, "country_code", it) },
                    onNumber = {
                        m.section(section, "phone_number", CrewFormRules.sanitizePhone(it, allowPlus = false))
                    },
                    onBlur = { m.touch(CrewField.LoanOutPhone) },
                    error = m.error(CrewField.LoanOutPhone),
                )
            }
            half {
                LabelledInput(
                    label = str(S.dm_req_email),
                    value = DocRead.text(company, "email").orEmpty(),
                    onValueChange = { m.section(section, "email", it) },
                    placeholder = "accounts@example.com",
                    error = m.error(CrewField.LoanOutEmail),
                    onBlur = { m.touch(CrewField.LoanOutEmail) },
                )
            }
            wide {
                AddressBlock(
                    label = str(S.dm_address_label),
                    address = CrewFormValues.address(company?.get("address")),
                    countries = m.countries,
                    onChange = { m.section(section, "address", CrewFormValues.addressJson(it)) },
                )
            }
        }
    }
}
