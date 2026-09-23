package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStatus
import com.zillit.desktop.feature.dealmemo.domain.preview.UkPayroll
import com.zillit.desktop.feature.dealmemo.domain.preview.passportList
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.AdditionalDetails
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.PairSeparators
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.PassportUploader
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.UkPayrollFieldset
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Crew Personal Details (`StepPersonalCrewDetails.jsx`): identity and
 * contact, the territory's payroll questions, bank, emergency contact and the
 * representing agency. Any field but the legal name can be marked required
 * for the crew member — the switch shows while the field is hovered.
 */
@Composable
internal fun PersonalEditor(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val form = builder.form
    val marks = remember(form["crewMandatoryFields"]) {
        form.list("crewMandatoryFields").mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    }
    val mark = remember(ops) { MarkOps(ops) }
    PersonalDetailsCard(state, builder, ops, mark, marks, onEvent)
    val territory = form.text("territory").lowercase()
    if (territory == UkPayroll.KEY && CrewStatus.mayBePaye(form.text("employmentStatus"))) {
        // The fieldset's lead question is the heading, so the block carries no title of its own.
        CardBlock(title = null) {
            UkPayrollFieldset(
                block = CrewDraft.ukFromWire(form.obj(UkPayroll.KEY)),
                onPatch = { patch ->
                    ops.edit { current ->
                        current.with(
                            UkPayroll.KEY,
                            JsonObject(CrewDraft.ukFromWire(current.obj(UkPayroll.KEY)) + patch),
                        )
                    }
                },
            )
        }
    }
    BankDetailsCard(form, ops, mark, marks)
    EmergencyCard(state, form, ops, mark, marks)
    AgencyCard(state, form, ops, mark, marks)
}

@Suppress("LongMethod")
@Composable
private fun PersonalDetailsCard(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    mark: MarkOps,
    marks: Set<String>,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val form = builder.form
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()).toString() }
    CardBlock(title = str(S.dm_step2_card_personal)) {
        BuilderGrid(columns = 2) {
            cell {
                Field(str(S.dm_req_full_legal_name), required = true) {
                    BuilderInput(
                        value = form.text("fullLegalName"),
                        onValueChange = { name -> ops.edit { it.withLegalName(name) } },
                        placeholder = str(S.dm_step2_full_legal_name_hint),
                    )
                }
            }
            cell {
                val email = form.text("email")
                MarkableField(
                    label = str(S.dm_req_email),
                    key = "email",
                    marks = marks,
                    mark = mark,
                    alwaysRequired = form.flag("isExternal"),
                    error = CrewFormRules.EMAIL_ERROR.takeUnless { CrewFormRules.validEmail(email) },
                ) {
                    BuilderInput(
                        value = email,
                        onValueChange = { ops.set("email", it) },
                        placeholder = "name@example.com",
                        error = !CrewFormRules.validEmail(email),
                    )
                }
            }
            textCell(
                form,
                ops,
                mark,
                marks,
                str(S.dm_step2_preferred_name),
                "preferredName",
                str(S.dm_step2_preferred_name_hint),
            )
            textCell(
                form,
                ops,
                mark,
                marks,
                str(S.dm_step2_screen_credit_designation),
                "screenCreditDesignation",
                str(S.desktop_dm_as_it_should_appear_in_credits),
            )
            cell {
                MarkableField(str(S.dm_req_dob), "dob", marks, mark) {
                    IsoDateInput(
                        value = form.text("dob"),
                        // A future date is dropped, not stored — `max` alone only bounds the calendar.
                        onChange = { picked -> if (picked.isEmpty() || picked <= today) ops.set("dob", picked) },
                        max = today,
                    )
                }
            }
            textCell(form, ops, mark, marks, str(S.dm_req_ni), "niNumber", str(S.dm_step2_ni_number_hint))
            textCell(form, ops, mark, marks, str(S.dm_crew_tax_code), "taxCode", str(S.dm_step2_tax_code_hint))
            cell {
                MarkableField(str(S.dm_req_rtw), "rightToWork", marks, mark) {
                    NativeSelect(
                        value = form.text("rightToWork"),
                        options = RIGHT_TO_WORK.map { PickOption(it, it) },
                        onPick = { ops.set("rightToWork", it) },
                        placeholder = str(S.desktop_dm_select_placeholder),
                    )
                }
            }
            cell(span = 2) {
                MarkableField(str(S.dm_step2_passport), "passportAttachment", marks, mark) {
                    val files = passportList(form["passportAttachment"])
                    PassportUploader(
                        files = files,
                        uploading = builder.passportUploading,
                        onAdd = { onEvent(BuilderEvent.PickPassport) },
                        onView = { file -> onEvent(BuilderEvent.ViewPassport(files.indexOf(file))) },
                        onRemove = { index -> onEvent(BuilderEvent.RemovePassport(index)) },
                    )
                }
            }
            cell {
                val mobile = CrewFormRules.sanitizePhone(form.text("mobile"), allowPlus = true)
                MarkableField(
                    label = str(S.dm_req_mobile),
                    key = "mobile",
                    marks = marks,
                    mark = mark,
                    error = CrewFormRules.PHONE_ERROR.takeUnless { CrewFormRules.validPhone(mobile) },
                ) {
                    BuilderInput(
                        value = mobile,
                        onValueChange = { ops.set("mobile", CrewFormRules.sanitizePhone(it, allowPlus = true)) },
                        placeholder = "+44 7700 900000",
                        error = !CrewFormRules.validPhone(mobile),
                    )
                }
            }
            cell {
                MarkableField(str(S.dm_step2_gender), "gender", marks, mark) {
                    NativeSelect(
                        value = form.text("gender"),
                        options = GENDERS,
                        onPick = { ops.set("gender", it) },
                        placeholder = str(S.desktop_dm_select_placeholder),
                    )
                }
            }
        }
        AddressBlock(state, form, ops, mark, marks, "homeAddress")
    }
}

/**
 * Bank Details: the production-entered account. Saved with the deal and made
 * an Account Hub account on issue; once linked, a save writes through to it.
 */
@Suppress("LongMethod")
@Composable
private fun BankDetailsCard(form: DealForm, ops: FormOps, mark: MarkOps, marks: Set<String>) {
    val linked = form.text("bankAccId").isNotEmpty()
    val bank = form.obj("bank") ?: DealForm.EMPTY_BANK
    fun setBank(key: String, value: JsonElement) = ops.edit { current ->
        current.with("bank", JsonObject((current.obj("bank") ?: DealForm.EMPTY_BANK) + (key to value)))
    }
    CardBlock(
        title = str(S.dm_crew_step_bank),
        tag = if (linked) str(S.desktop_dm_linked_to_account_hub) else str(S.dm_step9_optional),
        tone = BuilderTone.Teal,
    ) {
        BuilderAlert(
            text = if (linked) {
                str(S.desktop_dm_this_deal_is_issued_this_account_is)
            } else {
                str(S.desktop_dm_bank_account_used_to_pay_this_crew)
            },
            modifier = Modifier.padding(bottom = 16.dp),
        )
        BuilderGrid(columns = 2) {
            BANK_FIELDS.forEach { field ->
                cell {
                    val stored = DocRead.text(bank, field.key).orEmpty()
                    MarkableField(field.label, field.markKey, marks, mark) {
                        when (field.key) {
                            "sort_code" -> {
                                val digits = stored.filter { it.isDigit() }
                                // Six digits drawn as XX-XX-XX; a longer legacy value shows as it was stored.
                                val legacy = digits.length > SORT_CODE_DIGITS
                                BuilderInput(
                                    value = if (legacy) stored else digits,
                                    onValueChange = {
                                        setBank("sort_code", JsonPrimitive(CrewFormRules.stripSortCode(it)))
                                    },
                                    placeholder = field.placeholder,
                                    visualTransformation = if (legacy) VisualTransformation.None else SortCodeDashes,
                                )
                            }
                            "account_number" -> BuilderInput(
                                value = stored,
                                onValueChange = {
                                    setBank("account_number", JsonPrimitive(CrewFormValues.accountNumber(it)))
                                },
                                placeholder = field.placeholder,
                            )
                            else -> BuilderInput(
                                value = stored,
                                onValueChange = { setBank(field.key, JsonPrimitive(it)) },
                                placeholder = field.placeholder,
                            )
                        }
                    }
                }
            }
        }
        ZillitText(
            text = str(S.dm_step2_bank_additional_title),
            style = DmType.mono(11.sp, FontWeight.Bold, 0.09.em),
            color = bp.muted,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        )
        AdditionalDetails(
            rows = DocRead.objects(bank["additional_details"]),
            onChange = { setBank("additional_details", it) },
        )
    }
}

@Composable
private fun EmergencyCard(state: DealMemoUiState, form: DealForm, ops: FormOps, mark: MarkOps, marks: Set<String>) {
    CardBlock(title = str(S.dm_crew_step_emergency)) {
        BuilderGrid(columns = 2) {
            textCell(
                form,
                ops,
                mark,
                marks,
                str(S.desktop_dm_emergency_contact_name),
                "emergencyContactName",
                str(S.dm_step2_agency_name_hint),
            )
            emailCell(form, ops, mark, marks, "emergencyEmail")
            cell {
                val number = CrewFormRules.sanitizePhone(form.text("emergencyContactNumber"), allowPlus = false)
                MarkableField(str(S.desktop_dm_emergency_contact_number), "emergencyContactNumber", marks, mark) {
                    PhoneFields(
                        countries = state.production.countries,
                        storedCode = form.text("emergencyCountryCode"),
                        number = number,
                        mode = CodeMode.Iso,
                        onCode = { ops.set("emergencyCountryCode", it) },
                        onNumber = { ops.set("emergencyContactNumber", it) },
                        error = CrewFormRules.PHONE_ERROR.takeUnless { CrewFormRules.validPhone(number) },
                    )
                }
            }
        }
        AddressBlock(state, form, ops, mark, marks, "emergencyAddress")
    }
}

/**
 * Agency / Representative: a registered agency by id — the one that can later
 * sign for the crew member — or the agent's details typed in by hand.
 */
@Composable
private fun AgencyCard(state: DealMemoUiState, form: DealForm, ops: FormOps, mark: MarkOps, marks: Set<String>) {
    val agencies = state.production.agencies
    CardBlock(title = str(S.dm_step2_card_representative)) {
        Field(str(S.dm_step2_representing_agency)) {
            RichSelect(
                options = agencies.map { (id, name) -> PickOption(id, name) },
                selectedKey = form.text("agencyId").ifEmpty { null },
                onPick = { id ->
                    ops.patch(
                        "agencyId" to JsonPrimitive(id.orEmpty()),
                        "agencyName" to JsonPrimitive(id?.let(agencies::get).orEmpty()),
                    )
                },
                placeholder = if (agencies.isEmpty()) {
                    str(S.dm_step2_no_agencies)
                } else {
                    str(S.desktop_dm_select_agency_placeholder)
                },
                triggerText = form.text("agencyName").ifEmpty { null }?.takeIf { form.text("agencyId").isNotEmpty() },
                dropdownWidth = 360.dp,
            )
        }
        HintText(
            text = str(S.desktop_dm_if_the_crew_member_is_represented_by),
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        BuilderGrid(columns = 2) {
            textCell(
                form,
                ops,
                mark,
                marks,
                str(S.dm_step2_agency_name),
                "representativeName",
                str(S.dm_step2_agency_name_hint),
            )
            emailCell(form, ops, mark, marks, "representativeEmail")
            cell {
                val number = CrewFormRules.sanitizePhone(form.text("representativePhone"), allowPlus = false)
                MarkableField(str(S.dm_step2_representative_phone), "representativePhone", marks, mark) {
                    PhoneFields(
                        countries = state.production.countries,
                        storedCode = form.text("representativeCountryCode"),
                        number = number,
                        mode = CodeMode.Iso,
                        onCode = { ops.set("representativeCountryCode", it) },
                        onNumber = { ops.set("representativePhone", it) },
                        error = CrewFormRules.PHONE_ERROR.takeUnless { CrewFormRules.validPhone(number) },
                    )
                }
            }
        }
        AddressBlock(state, form, ops, mark, marks, "representativeAddress")
    }
}

// -- marked fields ---------------------------------------------------------------------------

/** Writes a key into `crewMandatoryFields` or takes it out — a user edit. */
private class MarkOps(private val ops: FormOps) {
    fun set(key: String, required: Boolean) = ops.edit { form ->
        val current = form.list("crewMandatoryFields").mapNotNull { (it as? JsonPrimitive)?.content }
        val next = if (required) (current + key).distinct() else current - key
        form.with("crewMandatoryFields", JsonArray(next.map { JsonPrimitive(it) }))
    }
}

/**
 * `Field` + `CrewLabel`: the label's red `*` when the field is marked (or
 * [alwaysRequired]), and the Mark Required switch, shown while anywhere on
 * the field is hovered or focused. Always composed — a control that appears
 * only on hover never receives the press.
 */
@Composable
private fun MarkableField(
    label: String,
    key: String,
    marks: Set<String>,
    mark: MarkOps,
    alwaysRequired: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (source, hovered) = rememberHover()
    var focused by remember { mutableStateOf(false) }
    val marked = key in marks
    Column(Modifier.hoverable(source).onFocusChanged { focused = it.hasFocus }) {
        FieldLabel(
            text = label,
            required = alwaysRequired || marked,
            requiredColor = bp.red,
            trailing = { MarkSwitch(marked, visible = hovered || focused) { mark.set(key, it) } },
        )
        content()
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun MarkSwitch(marked: Boolean, visible: Boolean, onChange: (Boolean) -> Unit) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(REVEAL_MILLIS))
    ZillitTooltip(
        text = if (marked) {
            str(S.desktop_dm_required_for_the_crew_member_click_to)
        } else {
            str(S.desktop_dm_click_to_make_this_field_required_for)
        },
    ) {
        Row(
            modifier = Modifier.alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitText(
                text = str(S.dm_step2_mark_required),
                style = DmType.sans(10.sp, FontWeight.Medium),
                color = bp.muted,
                maxLines = 1,
            )
            BuilderSwitch(checked = marked, onChange = onChange)
        }
    }
}

/**
 * An address group: a 13 bold heading that can be marked, over the six
 * address boxes. The heading is not a field label — it names the group.
 */
@Composable
private fun AddressBlock(
    state: DealMemoUiState,
    form: DealForm,
    ops: FormOps,
    mark: MarkOps,
    marks: Set<String>,
    key: String,
) {
    val (source, hovered) = rememberHover()
    var focused by remember { mutableStateOf(false) }
    val marked = key in marks
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().hoverable(source).onFocusChanged { focused = it.hasFocus }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = buildAnnotatedString {
                    append(str(S.dm_address_label))
                    if (marked) withStyle(SpanStyle(color = bp.red)) { append(" *") }
                },
                style = DmType.sans(13.sp, FontWeight.Bold),
                color = bp.ink,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            MarkSwitch(marked, visible = hovered || focused) { mark.set(key, it) }
        }
        AddressFields(
            address = CrewFormValues.address(form[key]),
            countries = state.production.countries,
            onChange = { address -> ops.set(key, CrewFormValues.addressJson(address)) },
        )
    }
}

private fun BuilderGridScope.textCell(
    form: DealForm,
    ops: FormOps,
    mark: MarkOps,
    marks: Set<String>,
    label: String,
    key: String,
    placeholder: String,
) = cell {
    MarkableField(label, key, marks, mark) {
        BuilderInput(value = form.text(key), onValueChange = { ops.set(key, it) }, placeholder = placeholder)
    }
}

private fun BuilderGridScope.emailCell(form: DealForm, ops: FormOps, mark: MarkOps, marks: Set<String>, key: String) =
    cell {
        val email = form.text(key)
        MarkableField(
            str(S.dm_req_email),
            key,
            marks,
            mark,
            error = CrewFormRules.EMAIL_ERROR.takeUnless { CrewFormRules.validEmail(email) },
        ) {
            BuilderInput(
                value = email,
                onValueChange = { ops.set(key, it) },
                placeholder = "name@example.com",
                error = !CrewFormRules.validEmail(email),
            )
        }
    }

/** Project crew: the legal name leads, carrying Crew Name with it until the two part. External crew name themselves. */
private fun DealForm.withLegalName(name: String): DealForm {
    val next = with("fullLegalName", name)
    if (flag("isExternal")) return next
    val crew = text("crewName")
    return if (crew.isEmpty() || crew == text("fullLegalName")) next.with("crewName", name) else next
}

private class BankField(val key: String, val label: String, val placeholder: String, val markKey: String)

/** The fourth slot is the key Mark Required writes — the crew panel's `bank.<field>` map reads it. */
private val BANK_FIELDS get() = listOf(
    BankField(
        "account_holder_name",
        str(S.dm_req_account_holder),
        str(S.desktop_dm_as_it_appears_on_the_account),
        "bankAccountHolderName",
    ),
    BankField("name", str(S.dm_step2_bank_name), str(S.dm_step2_bank_name_hint), "bankName"),
    BankField("account_number", str(S.dm_step2_bank_account_number), "12345678", "bankAccountNumber"),
    BankField("sort_code", str(S.dm_step2_bank_sort_code), "20-48-91", "bankSortCode"),
    BankField("iban_number", "IBAN", "GB29 NWBK 6016 1331 9268 19", "bankIbanNumber"),
    BankField("swift_code", str(S.dm_step2_bank_swift), "BARCGB22", "bankSwiftCode"),
)

private val RIGHT_TO_WORK =
    listOf("Passport", "UK Citizen / Settled Status", "UK Visa", "EU Pre-Settled", "Work Permit")

private val GENDERS get() = listOf(
    PickOption("female", str(S.female)),
    PickOption("male", str(S.male)),
    PickOption("non_binary", str(S.desktop_gender_non_binary)),
    PickOption("other", str(S.other)),
    PickOption("prefer_not_to_say", str(S.desktop_prefer_not_to_say)),
)

private const val SORT_CODE_DIGITS = 6
private const val REVEAL_MILLIS = 120
private val SortCodeDashes = PairSeparators('-')
