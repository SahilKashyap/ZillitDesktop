// One composable per dialog, and the request form is a long list of fields.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.data.hubDateTime
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvApprovalRound
import com.zillit.desktop.feature.transporthub.domain.AvApprover
import com.zillit.desktop.feature.transporthub.domain.AvInsuranceStatus
import com.zillit.desktop.feature.transporthub.domain.AvRatePeriod
import com.zillit.desktop.feature.transporthub.domain.AvSourceType
import com.zillit.desktop.feature.transporthub.domain.AvUseType
import com.zillit.desktop.feature.transporthub.domain.AvVariant
import com.zillit.desktop.feature.transporthub.domain.avCategories
import com.zillit.desktop.feature.transporthub.domain.avConditions
import com.zillit.desktop.feature.transporthub.domain.avModifications
import com.zillit.desktop.feature.transporthub.domain.avPermittedUses
import com.zillit.desktop.feature.transporthub.domain.builtInVehicleTypes
import com.zillit.desktop.feature.transporthub.ui.AvEditor
import com.zillit.desktop.feature.transporthub.ui.AvFormMode
import com.zillit.desktop.feature.transporthub.ui.AvFlow
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/** Everything Action Vehicles can have open. */
@Composable
internal fun ActionVehicleDialogs(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    AvFormDialog(state, onEvent)
    AvDetailDialog(state, onEvent)
    AvApproverDialog(state, onEvent)
    AvDecisionDialog(state, onEvent)
    AvThreadDialog(state, onEvent)
    AvCommentPickerDialog(state, onEvent)
}

/**
 * The request / source / edit form.
 *
 * One form, three jobs, and the differences are the point: a request collects
 * the **options** the AD would accept and none of the money; sourcing records
 * the one car found and the terms it comes on; and a finalized record is
 * frozen apart from its two shoot dates, so everything else is read-only
 * rather than quietly ignored by the server.
 */
@Composable
private fun AvFormDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.avEditor ?: return
    val locked = editor.datesOnly
    ZillitDialogShell(
        title = when (editor.mode) {
            AvFormMode.Request -> str(S.av_title_request)
            AvFormMode.Source -> str(S.av_title_source, editor.displayName)
            AvFormMode.Edit -> str(S.av_title_edit, editor.displayName)
        },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseAvEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseAvEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = when (editor.mode) {
                    AvFormMode.Request -> str(S.av_send_request)
                    AvFormMode.Source -> str(S.av_save_mark_sourced)
                    AvFormMode.Edit -> str(S.av_save_changes)
                },
                onClick = { onEvent(HubEvent.SaveActionVehicle) },
                loading = editor.saving,
            )
        },
    ) {
        if (locked) {
            ZillitText(
                text = str(S.av_finalized_banner),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitSectionLabel(text = str(S.av_section_identity))
        ZillitTextField(
            value = editor.displayName,
            onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(displayName = it))) },
            label = str(S.av_display_name),
            helperText = str(S.av_display_name_helper),
            enabled = !locked,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            // Several may fit, and a production may word its own — a custom
            // entry stores its label as the value.
            AvTokenPicker(
                label = str(S.av_category),
                builtIns = avCategories,
                chosen = editor.categories,
                enabled = !locked,
                onChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(categories = it))) },
                modifier = Modifier.weight(1f),
            )
            AvTokenPicker(
                label = str(S.av_vehicle_type),
                builtIns = builtInVehicleTypes.map { it to it },
                chosen = editor.vehicleTypes,
                enabled = !locked,
                onChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(vehicleTypes = it))) },
                modifier = Modifier.weight(1f),
            )
        }
        if (editor.mode == AvFormMode.Request) {
            AvVariantFields(editor, onEvent)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.make,
                    onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(make = it))) },
                    label = str(S.av_make),
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.model,
                    onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(model = it))) },
                    label = str(S.av_model),
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.year,
                    onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(year = it))) },
                    label = str(S.av_year),
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.color,
                    onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(color = it))) },
                    label = str(S.av_color),
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ZillitTextField(
            value = editor.description,
            onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(description = it))) },
            label = str(S.av_description),
            enabled = !locked,
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            // How the car was prepped is the sourcer's detail, never part of
            // what the AD asked for.
            if (editor.showsSourcing) {
                AvTokenPicker(
                    label = str(S.av_modifications),
                    builtIns = avModifications,
                    chosen = editor.modifications,
                    enabled = !locked,
                    onChange = {
                        onEvent(HubEvent.AvEditorChanged(editor.copy(modifications = it)))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            AvTokenPicker(
                label = str(S.av_condition),
                builtIns = avConditions,
                chosen = listOfNotNull(editor.condition.takeIf { it.isNotBlank() }),
                enabled = !locked,
                single = true,
                onChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(condition = it.firstOrNull().orEmpty())))
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (editor.showsSourcing) {
            AvPhotoRow(editor, onEvent)
        }

        ZillitSectionLabel(text = str(S.av_section_production))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            HubTagField(
                values = editor.scenes,
                onValuesChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(scenes = it))) },
                label = str(S.av_scenes),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = editor.useType,
                options = AvUseType.entries,
                onSelect = { onEvent(HubEvent.AvEditorChanged(editor.copy(useType = it))) },
                label = { it.label() },
                showInitials = false,
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
        }
        HubLocationField(
            label = str(S.av_location),
            value = editor.location,
            onChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(location = it))) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            // The two dates stay editable even on a frozen record: a shoot
            // day moves, and that is the one change still worth making.
            ZillitDateField(
                value = editor.needRehearsalDate,
                onValueChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(needRehearsalDate = it)))
                },
                label = str(S.av_need_rehearsal),
                modifier = Modifier.weight(1f),
            )
            ZillitDateField(
                value = editor.needShootingDate,
                onValueChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(needShootingDate = it)))
                },
                label = str(S.av_need_shooting),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            HubTagField(
                values = editor.approvedDrivers,
                onValuesChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(approvedDrivers = it)))
                },
                label = str(S.av_approved_drivers),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.reminderDays,
                onValueChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(reminderDays = it)))
                },
                label = str(S.av_reminder),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = editor.notes,
            onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(notes = it))) },
            label = str(S.av_notes),
            enabled = !locked,
            singleLine = false,
        )
        if (editor.showsSourcing) {
            AvSourceFields(editor, locked, onEvent)
        }
    }
}

/**
 * The AD's vehicle options.
 *
 * One request can cover several cars, so each acceptable one is its own row
 * rather than a separate request — and the server derives its own summary
 * from them.
 */
@Composable
private fun AvVariantFields(editor: AvEditor, onEvent: (HubEvent) -> Unit) {
    ZillitSectionLabel(text = str(S.av_options_title))
    ZillitText(
        text = str(S.av_options_hint),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
    editor.variants.forEachIndexed { index, variant ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = variant.make,
                onValueChange = { typed ->
                    onEvent(HubEvent.AvEditorChanged(editor.withVariant(index, variant.copy(make = typed))))
                },
                label = str(S.av_make),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = variant.model,
                onValueChange = { typed ->
                    onEvent(HubEvent.AvEditorChanged(editor.withVariant(index, variant.copy(model = typed))))
                },
                label = str(S.av_model),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = variant.year?.toString().orEmpty(),
                onValueChange = { typed ->
                    onEvent(
                        HubEvent.AvEditorChanged(
                            editor.withVariant(index, variant.copy(year = typed.trim().toIntOrNull())),
                        ),
                    )
                },
                label = str(S.av_year),
                modifier = Modifier.weight(1f),
            )
            HubTagField(
                values = variant.colors,
                onValuesChange = { colors ->
                    onEvent(
                        HubEvent.AvEditorChanged(editor.withVariant(index, variant.copy(colors = colors))),
                    )
                },
                label = str(S.av_option_colours),
                modifier = Modifier.weight(1f),
            )
            if (editor.variants.size > 1) {
                ZillitButton(
                    text = str(S.remove),
                    onClick = {
                        onEvent(
                            HubEvent.AvEditorChanged(
                                editor.copy(
                                    variants = editor.variants.filterIndexed { i, _ -> i != index },
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
    ZillitButton(
        text = str(S.av_options_add),
        onClick = {
            onEvent(HubEvent.AvEditorChanged(editor.copy(variants = editor.variants + AvVariant())))
        },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
}

@Composable
private fun AvPhotoRow(editor: AvEditor, onEvent: (HubEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSectionLabel(text = str(S.desktop_hub_sourced_photos))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            editor.images.forEach { slot ->
                ZillitStatusPill(
                    label = slot.attachment.name.ifBlank { str(S.photo) },
                    tone = StatusTone.Neutral,
                )
                ZillitButton(
                    text = str(S.remove),
                    onClick = { onEvent(HubEvent.RemoveAvPhoto(slot)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            ZillitButton(
                text = str(S.desktop_hub_add_photos),
                onClick = { onEvent(HubEvent.PickAvPhotos) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !editor.datesOnly,
            )
        }
    }
}

/**
 * Where the car came from, and on what terms.
 *
 * Production-owned hides the owner, the rate and the agreement: there is no
 * third party to agree anything with, and the save clears whatever was there.
 */
@Composable
private fun AvSourceFields(editor: AvEditor, locked: Boolean, onEvent: (HubEvent) -> Unit) {
    val owned = editor.sourceType.isOwned
    ZillitSectionLabel(text = str(S.av_section_source))
    ZillitText(
        text = str(S.av_section_source_hint),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitSelect(
            value = editor.sourceType,
            options = AvSourceType.entries,
            onSelect = { onEvent(HubEvent.AvEditorChanged(editor.copy(sourceType = it))) },
            label = { it.label() },
            showInitials = false,
            enabled = !locked,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = editor.replacementValue,
            onValueChange = {
                onEvent(HubEvent.AvEditorChanged(editor.copy(replacementValue = it)))
            },
            label = str(S.av_valuation),
            enabled = !locked,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = editor.licensePlate,
            onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(licensePlate = it))) },
            label = str(S.av_reg_plate),
            enabled = !locked,
            modifier = Modifier.weight(1f),
        )
    }
    if (!owned) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.ownerName,
                onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(ownerName = it))) },
                label = str(S.av_owner_required),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.ownerContactNumber,
                onValueChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(ownerContactNumber = it)))
                },
                label = str(S.av_contact_required),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.rate,
                onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(rate = it))) },
                label = str(S.av_rate),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = editor.ratePeriod,
                options = AvRatePeriod.entries,
                onSelect = { onEvent(HubEvent.AvEditorChanged(editor.copy(ratePeriod = it))) },
                label = { it.label() },
                showInitials = false,
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitDateField(
                value = editor.agreementStartDate,
                onValueChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(agreementStartDate = it)))
                },
                label = str(S.av_agreement_start),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitDateField(
                value = editor.agreementEndDate,
                onValueChange = {
                    onEvent(HubEvent.AvEditorChanged(editor.copy(agreementEndDate = it)))
                },
                label = str(S.av_agreement_end),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = editor.insuranceStatus,
                options = AvInsuranceStatus.entries,
                onSelect = { onEvent(HubEvent.AvEditorChanged(editor.copy(insuranceStatus = it))) },
                label = { it.label() },
                showInitials = false,
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.policyRef,
                onValueChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(policyRef = it))) },
                label = str(S.av_policy_ref),
                enabled = !locked,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        AvTokenPicker(
            label = str(S.av_permitted_uses),
            builtIns = avPermittedUses + (AvEditor.OTHER_USE to str(S.other)),
            chosen = editor.permittedUses,
            enabled = !locked,
            onChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(permittedUses = it))) },
            modifier = Modifier.weight(1f),
        )
        HubTagField(
            values = editor.propPlates,
            onValuesChange = { onEvent(HubEvent.AvEditorChanged(editor.copy(propPlates = it))) },
            label = str(S.av_prop_plates),
            enabled = !locked,
            modifier = Modifier.weight(1f),
        )
    }
    // The typed detail rides the permitted-uses array on save, so no new
    // server field is needed for a use the production words itself.
    if (AvEditor.OTHER_USE in editor.permittedUses) {
        ZillitTextField(
            value = editor.permittedUsesOther,
            onValueChange = {
                onEvent(HubEvent.AvEditorChanged(editor.copy(permittedUsesOther = it)))
            },
            label = str(S.av_permitted_other),
            enabled = !locked,
        )
    }
}

/**
 * A picker over the built-in options that also takes a production's own.
 *
 * The web calls this a CustomSelect: the built-ins are offered by their
 * labels, and anything typed is stored as its own value — which is why the
 * record holds strings and not an enum.
 */
@Composable
private fun AvTokenPicker(
    label: String,
    builtIns: List<Pair<String, String>>,
    chosen: List<String>,
    enabled: Boolean,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    single: Boolean = false,
) {
    val options = (builtIns.map { it.first } + chosen).distinct()
    if (single) {
        ZillitSelect(
            value = chosen.firstOrNull(),
            options = listOf(null) + options,
            onSelect = { onChange(listOfNotNull(it)) },
            label = { it?.optionLabel(builtIns) ?: str(S.select) },
            showInitials = false,
            enabled = enabled,
            modifier = modifier,
        )
        return
    }
    ZillitMultiSelect(
        selected = chosen,
        options = options,
        label = { it.optionLabel(builtIns) },
        onChange = onChange,
        enabled = enabled,
        placeholder = label,
        modifier = modifier,
    )
}

private fun String.optionLabel(builtIns: List<Pair<String, String>>): String =
    builtIns.firstOrNull { it.first == this }?.second ?: this

/**
 * One record, whole: the car, the terms, the two approval rounds and the log.
 *
 * The approver tracker is here rather than on the row because it is where the
 * acts on a round live — nudging somebody who has not answered, taking them
 * off, or adding more people to a round already in flight.
 */
@Composable
private fun AvDetailDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val vehicle = state.actionVehicle(state.openActionVehicle) ?: return
    ZillitDialogShell(
        title = vehicle.title,
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseActionVehicle) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(HubEvent.CloseActionVehicle) },
                variant = ButtonVariant.Secondary,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitStatusPill(
                label = vehicle.status.label(),
                tone = vehicle.status.tone(),
                dot = true,
            )
            vehicle.categories.forEach {
                ZillitStatusPill(label = it.categoryLabel(), tone = StatusTone.Neutral)
            }
        }
        AvDetailFacts(vehicle, state)
        if (vehicle.variants.isNotEmpty()) {
            ZillitSectionLabel(text = str(S.av_options_title))
            vehicle.variants.forEach { variant ->
                ZillitText(text = variant.summary, style = ZillitTheme.typography.bodyMedium)
            }
        }
        if (vehicle.approvers.isNotEmpty()) {
            AvApproverTracker(
                vehicle = vehicle,
                approvers = vehicle.approvers,
                flow = AvFlow.Approval,
                title = str(S.av_track_approval),
                onEvent = onEvent,
            )
        }
        if (vehicle.returnApprovers.isNotEmpty() || vehicle.returnRequestedMs > 0) {
            AvApproverTracker(
                vehicle = vehicle,
                approvers = vehicle.returnApprovers,
                flow = AvFlow.Return,
                title = str(S.av_track_return),
                onEvent = onEvent,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (!vehicle.status.isTerminal) {
                ZillitButton(
                    text = str(S.av_private_comments),
                    onClick = { onEvent(HubEvent.OpenAvThread(vehicle.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.av_send_for_comments),
                    onClick = { onEvent(HubEvent.PickAvCommenters(vehicle)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun AvDetailFacts(vehicle: ActionVehicle, state: HubUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        HubFact(
            label = str(S.av_vehicle_type),
            value = vehicle.vehicleTypes.joinToString(", "),
            modifier = Modifier.weight(1f),
        )
        HubFact(
            label = str(S.av_condition),
            value = vehicle.condition,
            modifier = Modifier.weight(1f),
        )
        HubFact(
            label = str(S.av_scenes),
            value = vehicle.scenes.joinToString(", "),
            modifier = Modifier.weight(1f),
        )
        HubFact(
            label = str(S.av_location),
            value = vehicle.location.display(),
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        HubFact(
            label = str(S.av_need_rehearsal),
            value = vehicle.needRehearsalMs.hubDate(),
            modifier = Modifier.weight(1f),
        )
        HubFact(
            label = str(S.av_need_shooting),
            value = vehicle.needShootingMs.hubDate(),
            modifier = Modifier.weight(1f),
        )
        HubFact(
            label = str(S.av_approved_drivers),
            value = vehicle.approvedDrivers.joinToString(", "),
            modifier = Modifier.weight(1f),
        )
        HubFact(
            label = str(S.desktop_ce_requested_by),
            value = vehicle.requesterName,
            modifier = Modifier.weight(1f),
        )
    }
    if (vehicle.sourceType != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            HubFact(
                label = str(S.av_source),
                value = vehicle.sourceType.label(),
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.av_owner_required),
                value = vehicle.ownerName,
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.av_rate),
                value = vehicle.rate?.let { "$it / ${vehicle.ratePeriod.label()}" }.orEmpty(),
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.av_insurance),
                value = vehicle.insuranceStatus?.label().orEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            HubFact(
                label = str(S.av_agreement_start),
                value = vehicle.agreementStartMs.hubDate(),
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.av_agreement_end),
                value = vehicle.agreementEndMs.hubDate(),
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.av_permitted_uses),
                value = vehicle.permittedUses.joinToString(", "),
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.av_prop_plates),
                value = vehicle.propPlates.joinToString(", "),
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (vehicle.notes.isNotBlank()) {
        HubFact(label = str(S.av_notes), value = vehicle.notes)
    }
    if (vehicle.modifications.isNotEmpty()) {
        HubFact(
            label = str(S.av_modifications),
            value = vehicle.modifications.joinToString(", ") { it.modificationLabel() },
        )
    }
    // Suppresses the unused-parameter warning while keeping the signature the
    // other facts rows use; the state is what resolves names here later.
    check(state.viewer.userId.length >= 0)
}

/**
 * One round's reviewers.
 *
 * First write wins, so once anybody has answered the others' "pending" tags
 * and the running count are dropped rather than left stuck: nothing more will
 * be asked of them.
 */
@Composable
private fun AvApproverTracker(
    vehicle: ActionVehicle,
    approvers: List<AvApprover>,
    flow: AvFlow,
    title: String,
    onEvent: (HubEvent) -> Unit,
) {
    val round = AvApprovalRound.of(approvers)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSectionLabel(text = title)
        ZillitText(
            text = when {
                round.declined -> str(S.desktop_hub_a_reviewer_declined)
                round.approved > 0 && round.approvedByName.isNotBlank() ->
                    str(S.ah_tier_approved_by, round.approvedByName)

                round.approved > 0 -> str(S.approved)
                else -> str(S.av_approval_progress, round.approved, round.total)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        approvers.forEach { approver ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = approver.name.ifBlank { approver.userId },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    approver.hasApproved ->
                        ZillitStatusPill(label = str(S.approved), tone = StatusTone.Done)

                    approver.hasRejected ->
                        ZillitStatusPill(label = str(S.av_chip_rejected), tone = StatusTone.Rejected)

                    // Moot once somebody else has settled it.
                    !round.resolved -> {
                        ZillitStatusPill(label = str(S.pending), tone = StatusTone.Pending)
                        ZillitButton(
                            text = str(S.av_nudge),
                            onClick = {
                                onEvent(HubEvent.RemindAvApprover(vehicle.id, approver.userId))
                            },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                        ZillitButton(
                            text = str(S.remove),
                            onClick = {
                                onEvent(
                                    HubEvent.RemoveAvApprover(vehicle.id, approver.userId, flow),
                                )
                            },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }

                    else -> Unit
                }
            }
        }
        if (!round.resolved) {
            ZillitButton(
                text = str(S.av_edit_approvers),
                onClick = {
                    onEvent(HubEvent.PickAvApprovers(vehicle, flow, addingToExisting = true))
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** Picking who decides — on the request, or on its return. */
@Composable
private fun AvApproverDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val picker = state.avApproverPicker ?: return
    val vehicle = state.actionVehicle(picker.vehicleId)
    ZillitDialogShell(
        title = when {
            picker.addingToExisting -> str(S.av_edit_approvers)
            picker.flow == AvFlow.Return ->
                str(S.av_pick_return_approvers, vehicle?.title.orEmpty())

            else -> str(S.av_pick_approvers, vehicle?.title.orEmpty())
        },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseAvApproverPicker) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseAvApproverPicker) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = when {
                    picker.addingToExisting -> str(S.av_edit_approvers)
                    picker.flow == AvFlow.Return -> str(S.av_request_return)
                    else -> str(S.av_send_for_approval)
                },
                onClick = { onEvent(HubEvent.SubmitAvApprovers) },
                loading = picker.saving,
                enabled = picker.picked.isNotEmpty(),
            )
        },
    ) {
        // Any single approval finalizes the record, which changes how many
        // people a coordinator adds — so the picker says it.
        ZillitText(
            text = if (picker.flow == AvFlow.Return) {
                str(S.av_pick_return_approvers_hint)
            } else {
                str(S.av_pick_approvers_hint)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitMultiSelect(
            selected = state.people.filter { it.userId in picker.picked },
            options = state.people,
            label = { it.name },
            onChange = { next ->
                onEvent(HubEvent.AvApproverPickerChanged(picker.copy(picked = next.map { it.userId })))
            },
            searchText = { "${it.name} ${it.designation} ${it.email}" },
            subtitle = { it.designation.takeIf { d -> d.isNotBlank() } },
            placeholder = str(S.av_search_project_users),
            emptyText = str(S.av_no_project_users),
        )
        if (picker.flow == AvFlow.Return && !picker.addingToExisting) {
            ZillitTextField(
                value = picker.note,
                onValueChange = { onEvent(HubEvent.AvApproverPickerChanged(picker.copy(note = it))) },
                label = str(S.av_return_note),
                singleLine = false,
            )
        }
        ZillitText(
            text = str(S.av_selected_count, picker.picked.size),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The approve or decline an approver answers. A reason is optional. */
@Composable
private fun AvDecisionDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val decision = state.avDecision ?: return
    ZillitDialogShell(
        title = when {
            decision.flow == AvFlow.Return && decision.isReject -> str(S.av_reject_return_title)
            decision.flow == AvFlow.Return -> str(S.av_review_return)
            decision.isReject -> str(S.av_reject_reason_title)
            else -> str(S.av_review)
        },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseAvDecision) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseAvDecision) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = if (decision.isReject) str(S.av_reject) else str(S.av_approve),
                onClick = { onEvent(HubEvent.SubmitAvDecision) },
                variant = if (decision.isReject) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = decision.saving,
            )
        },
    ) {
        if (decision.isReject) {
            ZillitTextField(
                value = decision.reason,
                onValueChange = { onEvent(HubEvent.AvDecisionChanged(decision.copy(reason = it))) },
                label = str(S.av_reason_optional),
                singleLine = false,
            )
        } else {
            ZillitText(
                text = if (decision.flow == AvFlow.Return) {
                    str(S.av_pick_return_approvers_hint)
                } else {
                    str(S.av_pick_approvers_hint)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** A record's private thread. Only its audience can read it. */
@Composable
private fun AvThreadDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val thread = state.avThread ?: return
    val vehicle = state.actionVehicle(thread.vehicleId) ?: return
    ZillitDialogShell(
        title = str(S.av_private_comments),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseAvThread) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(HubEvent.CloseAvThread) },
                variant = ButtonVariant.Secondary,
            )
            if (!vehicle.status.isTerminal) {
                ZillitButton(
                    text = str(S.av_post_comment),
                    onClick = { onEvent(HubEvent.SendAvComment) },
                    loading = thread.sending,
                    enabled = thread.draft.isNotBlank(),
                )
            }
        },
    ) {
        ZillitText(
            text = str(S.av_private_comments_hint),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        if (vehicle.comments.isEmpty()) {
            ZillitText(
                text = str(S.av_no_comments_yet),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        vehicle.comments.forEach { comment ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitText(
                        text = comment.name.ifBlank { "—" },
                        style = ZillitTheme.typography.labelSmall,
                    )
                    ZillitText(
                        text = comment.createdMs.hubDateTime(),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    // Inert on today's payload: no deployment checked sends an
                    // edit stamp yet, so the tag simply does not render.
                    if (comment.editedMs > 0) {
                        ZillitStatusPill(label = str(S.edited), tone = StatusTone.Neutral)
                    }
                }
                ZillitText(text = comment.text, style = ZillitTheme.typography.bodyMedium)
            }
        }
        if (vehicle.status.isTerminal) {
            ZillitText(
                text = str(S.av_thread_closed),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        } else {
            ZillitTextField(
                value = thread.draft,
                onValueChange = { onEvent(HubEvent.AvThreadChanged(thread.copy(draft = it))) },
                label = str(S.av_write_a_comment),
                singleLine = false,
            )
        }
    }
}

/** Opening a thread to more people. Everyone else stays excluded. */
@Composable
private fun AvCommentPickerDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val picker = state.avCommentPicker ?: return
    val vehicle = state.actionVehicle(picker.vehicleId)
    ZillitDialogShell(
        title = str(S.av_pick_comment_recipients, vehicle?.title.orEmpty()),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseAvCommentPicker) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseAvCommentPicker) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.av_send_for_comments),
                onClick = { onEvent(HubEvent.SubmitAvCommenters) },
                loading = picker.saving,
                enabled = picker.picked.isNotEmpty(),
            )
        },
    ) {
        ZillitText(
            text = str(S.av_pick_comment_recipients_hint),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitMultiSelect(
            selected = state.people.filter { it.userId in picker.picked },
            options = state.people,
            label = { it.name },
            onChange = { next ->
                onEvent(HubEvent.AvCommentPickerChanged(picker.copy(picked = next.map { it.userId })))
            },
            searchText = { "${it.name} ${it.designation} ${it.email}" },
            subtitle = { it.designation.takeIf { d -> d.isNotBlank() } },
            placeholder = str(S.av_search_project_users),
            emptyText = str(S.av_no_project_users),
        )
        ZillitTextField(
            value = picker.message,
            onValueChange = { onEvent(HubEvent.AvCommentPickerChanged(picker.copy(message = it))) },
            label = str(S.av_first_message),
            singleLine = false,
        )
    }
}

private fun AvEditor.withVariant(index: Int, variant: AvVariant): AvEditor =
    copy(variants = variants.toMutableList().also { it[index] = variant })

internal fun AvUseType.label(): String = when (this) {
    AvUseType.Interior -> str(S.desktop_hub_interior)
    AvUseType.Exterior -> str(S.desktop_hub_exterior)
    AvUseType.Both -> str(S.desktop_hub_interior_exterior)
}

internal fun AvSourceType.label(): String = when (this) {
    AvSourceType.Rented -> str(S.desktop_hub_rented)
    AvSourceType.ProductionOwned -> str(S.desktop_hub_production_owned)
    AvSourceType.Vendor -> str(S.ah_lbl_vendor)
    AvSourceType.PrivateOwner -> str(S.desktop_hub_private_owner)
}

internal fun AvRatePeriod.label(): String = when (this) {
    AvRatePeriod.Daily -> str(S.txt_day)
    AvRatePeriod.Weekly -> str(S.week_label)
    AvRatePeriod.Flat -> str(S.desktop_flat)
}

internal fun AvInsuranceStatus.label(): String = when (this) {
    AvInsuranceStatus.Covered -> str(S.dm_gpr_covered)
    AvInsuranceStatus.Pending -> str(S.pending)
    AvInsuranceStatus.NotCovered -> str(S.desktop_hub_not_covered)
}

private fun String.modificationLabel(): String =
    avModifications.firstOrNull { it.first == this }?.second ?: this
