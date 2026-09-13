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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

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
    CardBlock(title = "Personal Details") {
        BuilderGrid(columns = 2) {
            cell {
                Field("Full Legal Name", required = true) {
                    BuilderInput(
                        value = form.text("fullLegalName"),
                        onValueChange = { name -> ops.edit { it.withLegalName(name) } },
                        placeholder = "As on passport",
                    )
                }
            }
            cell {
                val email = form.text("email")
                MarkableField(
                    label = "Email",
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
                "Screen Credit",
                "preferredName",
                "As it should appear on screen / call sheet",
            )
            textCell(
                form,
                ops,
                mark,
                marks,
                "Screen Credit Designation",
                "screenCreditDesignation",
                "As it should appear in credits",
            )
            cell {
                MarkableField("Date of Birth", "dob", marks, mark) {
                    IsoDateInput(
                        value = form.text("dob"),
                        // A future date is dropped, not stored — `max` alone only bounds the calendar.
                        onChange = { picked -> if (picked.isEmpty() || picked <= today) ops.set("dob", picked) },
                        max = today,
                    )
                }
            }
            textCell(form, ops, mark, marks, "National Insurance No.", "niNumber", "AB 12 34 56 C")
            textCell(form, ops, mark, marks, "Tax Code", "taxCode", "e.g. 1257L")
            cell {
                MarkableField("Right to Work", "rightToWork", marks, mark) {
                    NativeSelect(
                        value = form.text("rightToWork"),
                        options = RIGHT_TO_WORK.map { PickOption(it, it) },
                        onPick = { ops.set("rightToWork", it) },
                        placeholder = "— Select —",
                    )
                }
            }
            cell(span = 2) {
                MarkableField("Passport / ID", "passportAttachment", marks, mark) {
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
                    label = "Mobile",
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
                MarkableField("Gender", "gender", marks, mark) {
                    NativeSelect(
                        value = form.text("gender"),
                        options = GENDERS,
                        onPick = { ops.set("gender", it) },
                        placeholder = "— Select —",
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
        title = "Bank Details",
        tag = if (linked) "Linked to Account Hub" else "Optional",
        tone = BuilderTone.Teal,
    ) {
        BuilderAlert(
            text = if (linked) {
                "This deal is issued — this account is linked in Account Hub. Changes saved here are applied there too."
            } else {
                "Bank account used to pay this crew member. Saved with the deal; on issue it becomes an Account Hub " +
                    "bank account."
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
            text = "ADDITIONAL DETAILS",
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
    CardBlock(title = "Emergency Details") {
        BuilderGrid(columns = 2) {
            textCell(form, ops, mark, marks, "Emergency Contact Name", "emergencyContactName", "Full name")
            emailCell(form, ops, mark, marks, "emergencyEmail")
            cell {
                val number = CrewFormRules.sanitizePhone(form.text("emergencyContactNumber"), allowPlus = false)
                MarkableField("Emergency Contact Number", "emergencyContactNumber", marks, mark) {
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
    CardBlock(title = "Agency/Representative Details") {
        Field("Representing Agency") {
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
                    "No agencies registered — enter details below"
                } else {
                    "— Select agency —"
                },
                triggerText = form.text("agencyName").ifEmpty { null }?.takeIf { form.text("agencyId").isNotEmpty() },
                dropdownWidth = 360.dp,
            )
        }
        HintText(
            text = "If the crew member is represented by an agency, select it here — the agency can later be sent " +
                "the deal memo to review and sign on behalf of the crew member. If the agency isn't registered, " +
                "enter its details below.",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        BuilderGrid(columns = 2) {
            textCell(form, ops, mark, marks, "Agency Name", "representativeName", "Full name")
            emailCell(form, ops, mark, marks, "representativeEmail")
            cell {
                val number = CrewFormRules.sanitizePhone(form.text("representativePhone"), allowPlus = false)
                MarkableField("Phone", "representativePhone", marks, mark) {
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
            "Required for the crew member — click to make optional"
        } else {
            "Click to make this field required for the crew member"
        },
    ) {
        Row(
            modifier = Modifier.alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitText(
                text = "Mark Required",
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
                    append("Address")
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
            "Email",
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
private val BANK_FIELDS = listOf(
    BankField("account_holder_name", "Account Holder Name", "As it appears on the account", "bankAccountHolderName"),
    BankField("name", "Bank Name", "e.g. Barclays", "bankName"),
    BankField("account_number", "Account Number", "12345678", "bankAccountNumber"),
    BankField("sort_code", "Sort Code", "20-48-91", "bankSortCode"),
    BankField("iban_number", "IBAN", "GB29 NWBK 6016 1331 9268 19", "bankIbanNumber"),
    BankField("swift_code", "SWIFT / BIC", "BARCGB22", "bankSwiftCode"),
)

private val RIGHT_TO_WORK =
    listOf("Passport", "UK Citizen / Settled Status", "UK Visa", "EU Pre-Settled", "Work Permit")

private val GENDERS = listOf(
    PickOption("female", "Female"),
    PickOption("male", "Male"),
    PickOption("non_binary", "Non-binary"),
    PickOption("other", "Other"),
    PickOption("prefer_not_to_say", "Prefer not to say"),
)

private const val SORT_CODE_DIGITS = 6
private const val REVEAL_MILLIS = 120
private val SortCodeDashes = PairSeparators('-')
