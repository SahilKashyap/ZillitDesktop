package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStatus
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Employee Status (`StepEmployeeStatus.jsx`): the agreement's employment
 * statuses, and the loan-out company when the status is a loan-out.
 */
@Suppress("LongMethod")
@Composable
internal fun EmploymentEditor(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val union = builder.reference.selectedUnionForSteps
    val statuses = (union?.get("emp_statuses") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    CardBlock(title = str(S.dm_crew_emp_status), tag = str(S.desktop_dm_select_status), tone = BuilderTone.Purple) {
        when {
            statuses.isNotEmpty() -> Field(str(S.dm_crew_emp_status), required = true) {
                RichSelect(
                    options = statuses.mapNotNull { row ->
                        val id = DocRead.text(row, "id") ?: return@mapNotNull null
                        val label = DocRead.text(row, "label") ?: id
                        val sub = DocRead.text(row, "sub")
                        PickOption(
                            key = id,
                            label = label,
                            sub = sub,
                            search = listOfNotNull(label, sub).joinToString(" "),
                        )
                    },
                    selectedKey = form.text("employmentStatus").ifEmpty { null },
                    onPick = { ops.set("employmentStatus", it.orEmpty()) },
                    placeholder = str(S.dm_step2_emp_status_placeholder),
                    dropdownWidth = 360.dp,
                )
            }
            union != null -> CenteredNote(str(S.desktop_dm_no_employee_status_present_for_selected_agreement))
        }
    }
    if (CrewStatus.isLoanOut(form.text("employmentStatus"))) {
        CardBlock(title = str(S.dm_loanout_section_title), tag = str(S.company_details), tone = BuilderTone.Blue) {
            val email = form.text("loanOutEmail")
            val phone = form.text("loanOutPhoneNumber")
            BuilderGrid(columns = 2) {
                cell {
                    Field(str(S.dm_loanout_name)) {
                        BuilderInput(
                            value = form.text("loanOutCompanyName"),
                            onValueChange = { ops.set("loanOutCompanyName", it) },
                            placeholder = "Jane Doe Productions Ltd",
                        )
                    }
                }
                cell {
                    Field(
                        str(S.dm_req_email),
                        error = str(S.desktop_dm_enter_a_valid_email_address)
                            .takeUnless { CrewFormRules.validEmail(email) },
                    ) {
                        BuilderInput(
                            value = email,
                            onValueChange = { ops.set("loanOutEmail", it) },
                            placeholder = "accounts@example.com",
                            error = !CrewFormRules.validEmail(email),
                        )
                    }
                }
                cell {
                    Field(str(S.dm_loanout_phone)) {
                        PhoneFields(
                            countries = state.production.countries,
                            storedCode = form.text("loanOutCountryCode"),
                            number = phone,
                            mode = CodeMode.Dial,
                            onCode = { ops.set("loanOutCountryCode", it) },
                            onNumber = { ops.set("loanOutPhoneNumber", it) },
                            error = str(S.desktop_dm_enter_a_valid_phone_number_5_15).takeUnless {
                                CrewFormRules.validPhone(phone)
                            },
                        )
                    }
                }
            }
            Column(Modifier.padding(top = 18.dp)) {
                ZillitText(
                    text = str(S.dm_address_label),
                    style = DmType.sans(13.sp, FontWeight.Bold),
                    color = bp.ink,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                AddressFields(
                    address = CrewFormValues.address(form["loanOutCompanyAddress"]),
                    countries = state.production.countries,
                    onChange = { ops.set("loanOutCompanyAddress", CrewFormValues.addressJson(it)) },
                )
            }
        }
    }
}

/** A centred note in a quiet box — an empty list's explanation. */
@Composable
internal fun CenteredNote(text: String) {
    val p = bp
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.infoBox)
            .border(1.dp, p.infoBorder, shape)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(13.sp), color = p.muted) }
}
