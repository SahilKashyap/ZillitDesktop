package com.zillit.desktop.feature.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.Designation
import com.zillit.desktop.feature.auth.domain.JoinFieldError
import com.zillit.desktop.feature.auth.domain.message

/**
 * Asking to join a production.
 *
 * Two steps: the code, then who you are on this production. They are separate
 * because a form presented before the code is known to be valid asks someone to
 * fill in six fields on the strength of a guess.
 */
@Composable
internal fun JoinProductionDialog(
    state: JoinFlowState,
    onEvent: (JoinEvent) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    ZillitDialogShell(
        title = "Join a production",
        visible = visible,
        subtitle = when (state.step) {
            JoinStep.Code -> "Step 1 of 2 — the production code"
            JoinStep.Details -> "Step 2 of 2 — who you are on this production"
            JoinStep.Submitted -> "Request sent"
        },
        icon = ZillitIcons.User,
        onDismiss = { onEvent(JoinEvent.Dismiss) },
        width = DIALOG_WIDTH,
        modifier = modifier,
    ) {
        StepBar(state.step)

        when (state.step) {
            JoinStep.Code -> CodeStep(state, onEvent)
            JoinStep.Details -> DetailsStep(state, onEvent)
            JoinStep.Submitted -> SubmittedStep(state, onEvent)
        }

        if (state.error != null) {
            ZillitText(
                text = state.error,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.small)
                    .background(ZillitTheme.colors.dangerSoft)
                    .padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/**
 * Where the flow stands, as filled segments — code, details, sent. Cheaper to
 * read than the subtitle's words, and it promises how much is left.
 */
@Composable
private fun StepBar(step: JoinStep) {
    val reached = when (step) {
        JoinStep.Code -> 1
        JoinStep.Details -> 2
        JoinStep.Submitted -> STEP_COUNT
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        repeat(STEP_COUNT) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(STEP_BAR_HEIGHT)
                    .clip(ZillitTheme.shapes.pill)
                    .background(
                        if (index < reached) {
                            ZillitTheme.colors.accent
                        } else {
                            ZillitTheme.colors.border
                        },
                    ),
            )
        }
    }
}

@Composable
private fun CodeStep(state: JoinFlowState, onEvent: (JoinEvent) -> Unit) {
    ZillitText(
        text = "Enter the code the production gave you.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )

    ZillitTextField(
        value = state.codeText,
        onValueChange = { onEvent(JoinEvent.CodeChanged(it)) },
        placeholder = "Production code",
        modifier = Modifier.fillMaxWidth(),
    )

    DialogActions(
        confirmText = "Find production",
        confirmEnabled = state.canFindProject,
        isBusy = state.isBusy,
        onConfirm = { onEvent(JoinEvent.FindProject) },
        onDismiss = { onEvent(JoinEvent.Dismiss) },
    )
}

@Composable
private fun DetailsStep(state: JoinFlowState, onEvent: (JoinEvent) -> Unit) {
    val draft = state.draft

    // Names the production before asking for anything: the code alone is not
    // something anyone can check they typed correctly. The avatar carries the
    // same identity hue the production will have on the list.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = state.project?.name.orEmpty())
        Column {
            ZillitText(
                text = state.project?.name.orEmpty(),
                style = ZillitTheme.typography.titleSmall,
            )
            ZillitText(
                text = "#" + state.codeText.trim(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }

    PhotoRow(state, onEvent)

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = draft.firstName,
            onValueChange = { onEvent(JoinEvent.DraftChanged(draft.copy(firstName = it))) },
            placeholder = "First name",
            errorText = JoinFieldError.FirstNameTooShort.takeIf { it in state }?.message,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = draft.lastName,
            onValueChange = { onEvent(JoinEvent.DraftChanged(draft.copy(lastName = it))) },
            placeholder = "Last name",
            errorText = JoinFieldError.LastNameTooShort.takeIf { it in state }?.message,
            modifier = Modifier.weight(1f),
        )
    }

    // A personal production has no crew, so it has no department, role or unit
    // to ask about. Both other clients skip these for it.
    if (!state.isPersonal) {
        CrewFields(state, onEvent)
    }

    ZillitCheckbox(
        checked = draft.keepNamePrivate,
        onCheckedChange = { onEvent(JoinEvent.DraftChanged(draft.copy(keepNamePrivate = it))) },
        label = "Keep my name off crew lists",
    )

    DialogActions(
        confirmText = "Send request",
        confirmEnabled = !state.isBusy,
        isBusy = state.isBusy,
        onConfirm = { onEvent(JoinEvent.Submit) },
        onDismiss = { onEvent(JoinEvent.Dismiss) },
    )
}

/**
 * The joiner's own picture.
 *
 * The phones take a selfie here — Android opens its camera activity, iOS asks
 * for a photo — and a workstation has no camera worth assuming, so the picture
 * is chosen from disk. It is stored the moment it is picked, which is why this
 * has a busy state of its own: the upload belongs to the form, not to the
 * button that submits it.
 *
 * Optional throughout. A production would rather have a crew member with no
 * photograph than not have them.
 */
@Composable
private fun PhotoRow(state: JoinFlowState, onEvent: (JoinEvent) -> Unit) {
    if (!state.canChoosePhoto) return
    val stored = state.photo

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitAvatar(name = state.draft.firstName.ifBlank { "?" })

            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = when {
                        state.isStoringPhoto -> "Saving your photo…"
                        stored != null -> "Photo added"
                        else -> "Add a photo — optional"
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitButton(
                        text = if (stored != null) "Change" else "Choose photo",
                        variant = ButtonVariant.Tertiary,
                        enabled = !state.isStoringPhoto && !state.isBusy,
                        onClick = { onEvent(JoinEvent.ChoosePhoto) },
                    )
                    if (stored != null) {
                        ZillitButton(
                            text = "Remove",
                            variant = ButtonVariant.Tertiary,
                            enabled = !state.isStoringPhoto,
                            onClick = { onEvent(JoinEvent.RemovePhoto) },
                        )
                    }
                }
            }
        }

        // A photo that would not save must not read as a form error: the
        // request goes without it.
        state.photoError?.let { problem ->
            ZillitText(
                text = problem,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun CrewFields(state: JoinFlowState, onEvent: (JoinEvent) -> Unit) {
    val draft = state.draft

    LabelledField("Department", JoinFieldError.DepartmentMissing.takeIf { it in state }?.message) {
        ZillitSelect(
            value = state.departments.firstOrNull { it.id == draft.departmentId },
            options = state.departments,
            onSelect = { chosen ->
                onEvent(JoinEvent.DraftChanged(draft.copy(departmentId = chosen?.id)))
            },
            // Department, role and unit names are translation keys —
            // `transportation_department_label`, `main_unit_label`.
            label = { it?.name?.localised() ?: "Select a department" },
        )
    }

    LabelledField("Role", JoinFieldError.DesignationMissing.takeIf { it in state }?.message) {
        ZillitSelect(
            value = state.designations.firstOrNull { it.id == draft.designationId },
            options = state.designations,
            onSelect = { chosen ->
                onEvent(JoinEvent.DraftChanged(draft.copy(designationId = chosen?.id)))
            },
            label = { it?.name?.localised() ?: "Select a role" },
            // Roles belong to a department; offering them first would be a list
            // of every job on the production.
            enabled = draft.departmentId != null,
        )
    }

    LabelledField("Unit", JoinFieldError.UnitMissing.takeIf { it in state }?.message) {
        ZillitSelect(
            value = state.units.firstOrNull { it.id == draft.unitId },
            options = state.units,
            onSelect = { chosen ->
                onEvent(JoinEvent.DraftChanged(draft.copy(unitId = chosen?.id)))
            },
            label = { it?.name?.localised() ?: "Select a unit" },
        )
    }
}

/**
 * The confirmation.
 *
 * Says what happens next rather than just "done" — a request that is waiting on
 * a coordinator looks identical to one that failed unless the screen says so.
 */
@Composable
private fun SubmittedStep(state: JoinFlowState, onEvent: (JoinEvent) -> Unit) {
    ZillitText(
        text = state.project?.name.orEmpty(),
        style = ZillitTheme.typography.bodyMedium,
    )
    ZillitText(
        text = state.outcome?.joinMessage.orEmpty(),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        ZillitButton(
            text = "Done",
            variant = ButtonVariant.Primary,
            onClick = { onEvent(JoinEvent.Dismiss) },
        )
    }
}

@Composable
private fun LabelledField(label: String, errorText: String?, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        field()
        if (errorText != null) {
            ZillitText(
                text = errorText,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun DialogActions(
    confirmText: String,
    confirmEnabled: Boolean,
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
    ) {
        ZillitButton(text = "Cancel", variant = ButtonVariant.Tertiary, onClick = onDismiss)
        ZillitButton(
            text = if (isBusy) "Working..." else confirmText,
            variant = ButtonVariant.Primary,
            enabled = confirmEnabled,
            onClick = onConfirm,
        )
    }
}

private val DIALOG_WIDTH = 460.dp
private val STEP_BAR_HEIGHT = 4.dp
private const val STEP_COUNT = 3
