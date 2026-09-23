package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.authoring.CrewRoles
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmPersonAvatar
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import kotlinx.serialization.json.JsonPrimitive

/**
 * Crew Details (`Step2Crew.jsx`): who the deal is for — a production member,
 * or an external crew member by name — and the role the rate card is read for.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun CrewEditor(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val mode = builder.mode
    val catalogue = state.catalogue
    val covered = builder.reference.coveredRoles
    val status = builder.dealStatus
    val statusLocked = mode.dealId != null && !status.isNullOrEmpty() && status != "draft"
    val crewLocked = if (!status.isNullOrEmpty()) {
        statusLocked
    } else {
        mode.dealId != null && form.text("userId").isNotEmpty()
    }
    val departments = remember(covered, catalogue) { CrewRoles.departmentOptions(covered, catalogue) }
    val designations = remember(form.text("department"), covered, catalogue) {
        CrewRoles.designationOptions(form.text("department"), covered, catalogue)
    }
    val crew = state.crewDirectory.orEmpty()
    val crewOptions = remember(crew, builder.takenUserIds, form.text("userId"), form.text("fullLegalName")) {
        CrewRoles.crewOptions(crew, builder.takenUserIds, form.text("userId"), form.text("fullLegalName"))
    }
    val agreementTag = when {
        isNonUnionId(form.text("union")) -> str(S.dm_create_non_union)
        else -> builder.reference.selectedUnion?.let { DocRead.text(it, "short_label") } ?: form.text("union")
    }
    CardBlock(title = str(S.dm_step2_card_role), tag = agreementTag.ifEmpty { null }, tone = BuilderTone.Gold) {
        ExternalRow(form, locked = statusLocked, ops)
        BuilderGrid(columns = 3) {
            cell {
                if (form.flag("isExternal")) {
                    Field(str(S.dm_step2_crew_name), required = true) {
                        BuilderInput(
                            value = form.text("crewName"),
                            onValueChange = { name -> ops.edit { it.withCrewName(name) } },
                            placeholder = str(S.dm_step2_crew_name_hint),
                        )
                    }
                } else {
                    Field(str(S.crew_member), required = true) {
                        val selected = crewOptions.firstOrNull { it.userId == form.text("userId") }
                        RichSelect(
                            options = crewOptions.map { option ->
                                PickOption(
                                    key = option.userId,
                                    label = option.name,
                                    sub = option.subline,
                                    search = option.search,
                                    badge = str(S.dm_checklist_pending).takeIf { option.pending },
                                )
                            },
                            selectedKey = form.text("userId").ifEmpty { null },
                            onPick = { id ->
                                ops.edit {
                                    CrewRoles.withCrewMember(it, id, crew, state.production.units, covered, catalogue)
                                }
                            },
                            placeholder = str(S.desktop_dm_select_crew_member_placeholder),
                            enabled = !crewLocked,
                            dropdownWidth = 340.dp,
                            triggerText = selected?.triggerLabel,
                            leading = { selected?.let { DmPersonAvatar(it.name, it.userId, 22.dp) } },
                            rowLeading = { option -> DmPersonAvatar(option.label, option.key, 32.dp) },
                        )
                    }
                }
            }
            cell {
                Field(str(S.dm_nom_dept), required = true) {
                    RichSelect(
                        options = departments.map {
                            PickOption(
                                it.value,
                                it.label,
                                badge = if (it.system) str(S.desktop_language_system_short) else null,
                            )
                        },
                        selectedKey = form.text("department").ifEmpty { null },
                        onPick = { id ->
                            ops.patch(
                                "department" to JsonPrimitive(id.orEmpty()),
                                "designation" to JsonPrimitive(""),
                                "jobTitle" to JsonPrimitive(""),
                                "customJobTitle" to JsonPrimitive(""),
                            )
                        },
                        placeholder = str(S.desktop_dm_select_department),
                    )
                }
            }
            cell {
                Field(str(S.dm_label_designation), required = true) {
                    RichSelect(
                        options = designations.map {
                            PickOption(
                                it.value,
                                it.label,
                                badge = if (it.system) str(S.desktop_language_system_short) else null,
                            )
                        },
                        selectedKey = (form.text("designation").ifEmpty { form.text("jobTitle") }).ifEmpty { null },
                        onPick = { id ->
                            if (id == DealForm.CUSTOM_JOB_TITLE) {
                                ops.patch(
                                    "jobTitle" to JsonPrimitive(DealForm.CUSTOM_JOB_TITLE),
                                    "designation" to JsonPrimitive(""),
                                )
                            } else {
                                ops.patch(
                                    "designation" to JsonPrimitive(id.orEmpty()),
                                    "jobTitle" to JsonPrimitive(
                                        id?.let { CrewRoles.designationLabel(it, catalogue) }.orEmpty(),
                                    ),
                                    "customJobTitle" to JsonPrimitive(""),
                                )
                            }
                        },
                        placeholder = if (form.text("department").isEmpty()) {
                            str(S.desktop_dm_select_department_first)
                        } else {
                            str(S.desktop_dm_select_designation)
                        },
                        enabled = form.text("department").isNotEmpty(),
                    )
                }
            }
            cell {
                Field(str(S.dm_step2_crew_type), required = true) {
                    NativeSelect(
                        value = form.text("crewType"),
                        options = listOf(
                            PickOption("shoot_crew", str(S.dm_step2_crew_type_shoot)),
                            PickOption("non_shoot_crew", str(S.dm_step2_crew_type_non_shoot)),
                        ),
                        onPick = { ops.set("crewType", it) },
                        placeholder = str(S.desktop_dm_select_crew_type_placeholder),
                    )
                }
            }
            cell {
                Field(str(S.dm_step2_call_sheet_tier)) {
                    NativeSelect(
                        value = form.text("callSheetTier"),
                        options = listOf(
                            "HOD" to "HOD",
                            "Crew" to str(S.dm_label_crew),
                            "Daily" to str(S.dm_rates_buyout_mode_daily),
                        )
                            .map { (value, label) -> PickOption(value, label) },
                        onPick = { ops.set("callSheetTier", it) },
                    )
                }
            }
            cell {
                Field(str(S.dm_step2_unit), required = true) {
                    NativeSelect(
                        value = form.text("unit"),
                        options = state.production.units.distinctBy { it.name }.map { unit ->
                            PickOption(
                                unit.id,
                                DealLabels.translation(unit.name)?.takeIf { it.isNotEmpty() } ?: unit.name.ifEmpty {
                                    unit.id
                                },
                            )
                        },
                        onPick = { ops.set("unit", it) },
                        placeholder = str(S.desktop_dm_select_unit_placeholder),
                    )
                }
            }
            if (form.text("jobTitle") == DealForm.CUSTOM_JOB_TITLE) {
                cell(span = 3) {
                    Field(str(S.dm_step2_custom_job_title)) {
                        BuilderInput(
                            value = form.text("customJobTitle"),
                            onValueChange = { ops.set("customJobTitle", it) },
                            placeholder = str(S.dm_step2_custom_job_title_hint),
                        )
                    }
                }
            }
        }
    }
}

/** The external toggle: a person outside the project gets a portal link instead of a Zillit account. */
@Composable
private fun ExternalRow(form: DealForm, locked: Boolean, ops: FormOps) {
    val p = bp
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.infoBox)
            .border(1.dp, p.hairline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = str(S.dm_step2_external_label),
                style = DmType.sans(13.sp, FontWeight.Bold),
                color = p.ink,
            )
            ZillitText(
                text = str(S.dm_step2_external_sub),
                style = DmType.sans(11.sp),
                color = p.muted,
            )
            if (locked) {
                ZillitText(
                    text = str(S.desktop_dm_locked_the_project_external_type_can_only),
                    style = DmType.sans(11.sp, FontWeight.SemiBold),
                    color = p.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        ZillitText(
            text = if (form.flag("isExternal")) str(S.dm_nda_chip_external) else str(S.dm_step2_external_off),
            style = DmType.sans(11.sp),
            color = p.ink2,
        )
        BuilderSwitch(
            checked = form.flag("isExternal"),
            enabled = !locked,
            onChange = { external ->
                ops.edit { current ->
                    current.with("isExternal", external).let { if (external) it.with("userId", "") else it }
                }
            },
        )
    }
}

/** External crew: the typed name, mirrored into the legal name while the two still agree. */
private fun DealForm.withCrewName(name: String): DealForm {
    val legal = text("fullLegalName")
    val next = with("crewName", name)
    return if (legal.isEmpty() || legal == text("crewName")) next.with("fullLegalName", name) else next
}
