package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.settings.admin.domain.CompanyField
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminField
import com.zillit.desktop.feature.settings.admin.ui.AdminForm
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState

/**
 * Every dialog these pages open.
 *
 * One entry point, because at most one is ever open — [AdminUiState.form] and
 * [AdminUiState.confirming] are single-valued, which is what makes "close" one
 * assignment rather than a flag per dialog.
 */
@Composable
fun AdminDialogs(state: AdminUiState, onEvent: (AdminEvent) -> Unit) {
    ConfirmationDialog(state, onEvent)

    when (val form = state.form) {
        is AdminForm.Name -> NameDialog(form, state, onEvent)
        is AdminForm.PreApproval -> PreApprovalDialog(form, state, onEvent)
        is AdminForm.Sos -> SosDialog(form, state, onEvent)
        is AdminForm.Company -> CompanyDialog(form, state, onEvent)
        is AdminForm.ProductionName -> ProductionNameDialog(form, state, onEvent)
        null -> Unit
    }
}

/**
 * The one-field form: naming a department, a job title, a group, a unit.
 *
 * Four pages share it because the shape is identical and the difference is a
 * noun. Four dialogs would be four places to get the three-character rule
 * wrong.
 */
@Composable
private fun NameDialog(form: AdminForm.Name, state: AdminUiState, onEvent: (AdminEvent) -> Unit) {
    ZillitDialogShell(
        title = form.title,
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        actions = {
            // The button says what it does. Renaming through a button labelled
            // "Add" reads as creating a second thing, which on a page where
            // both are offered is a real hesitation.
            FormActions(
                state = state,
                submittable = form.isValid,
                onEvent = onEvent,
                submitLabel = if (form.isRename) "Rename" else "Add",
            )
        },
    ) {
        ZillitTextField(
            value = form.value,
            onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.Name, it)) },
            label = "Name",
            placeholder = "At least three characters",
            errorText = form.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The production's name, with its own tighter length rule. */
@Composable
private fun ProductionNameDialog(
    form: AdminForm.ProductionName,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
) {
    ZillitDialogShell(
        title = "Rename this production",
        subtitle = "Everyone on it sees the new name.",
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        actions = { FormActions(state, form.isValid, onEvent, submitLabel = "Rename") },
    ) {
        ZillitTextField(
            value = form.value,
            onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.Name, it)) },
            label = "Production name",
            helperText = "Three to twenty-five characters.",
            errorText = form.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Pre-approving someone.
 *
 * The department and job title are the point of the form: they are what the
 * person is let in *as*, and choosing a department clears the job title,
 * because the titles belong to the department.
 */
// A form. Splitting it into per-field composables would hide what the form
// asks for, which is the only thing worth reading here.
@Suppress("LongMethod")
@Composable
private fun PreApprovalDialog(
    form: AdminForm.PreApproval,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
) {
    val department = state.departments.firstOrNull { it.id == form.departmentId }

    ZillitDialogShell(
        title = "Pre-approve someone",
        subtitle = "They join with the production code and skip the approval queue.",
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        actions = { FormActions(state, form.isValid, onEvent, submitLabel = "Pre-approve") },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = form.firstName,
                onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.FirstName, it)) },
                label = "First name",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = form.lastName,
                onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.LastName, it)) },
                label = "Last name",
                modifier = Modifier.weight(1f),
            )
        }

        ZillitSelect(
            value = department ?: NO_DEPARTMENT,
            options = listOf(NO_DEPARTMENT) + state.departments,
            onSelect = { onEvent(AdminEvent.FieldChanged(AdminField.Department, it.id)) },
            label = { it.name.localised() },
            modifier = Modifier.fillMaxWidth(),
        )

        val titles = department?.jobTitles.orEmpty()
        ZillitSelect(
            value = titles.firstOrNull { it.id == form.jobTitleId } ?: NO_JOB_TITLE,
            options = listOf(NO_JOB_TITLE) + titles,
            onSelect = { onEvent(AdminEvent.FieldChanged(AdminField.JobTitle, it.id)) },
            label = { it.name.localised() },
            // Nothing to choose from until a department is picked, and offering
            // an empty list reads as a list that failed to load.
            enabled = titles.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitTextField(
            value = form.email,
            onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.Email, it)) },
            label = "Email",
            helperText = "Optional.",
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = form.countryCode,
                onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.CountryCode, it)) },
                label = "Code",
                placeholder = "+44",
                modifier = Modifier.width(CODE_WIDTH),
            )
            ZillitTextField(
                value = form.phone,
                onValueChange = { onEvent(AdminEvent.FieldChanged(AdminField.Phone, it)) },
                label = "Phone",
                helperText = "Optional, but a number needs its country code.",
                modifier = Modifier.weight(1f),
            )
        }

        form.error?.let { ErrorLine(it) }
    }
}

/** Adding an SOS recipient — a crew member, or an outside number. */
@Suppress("LongMethod") // A form; see PreApprovalDialog.
@Composable
private fun SosDialog(form: AdminForm.Sos, state: AdminUiState, onEvent: (AdminEvent) -> Unit) {
    val draft = form.draft

    ZillitDialogShell(
        title = when (draft.entryType) {
            SosEntryType.Crew -> "Alert someone on the crew"
            SosEntryType.Outsider -> "Alert someone outside the production"
        },
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        actions = { FormActions(state, draft.isComplete, onEvent, submitLabel = "Add recipient") },
    ) {
        when (draft.entryType) {
            SosEntryType.Crew -> {
                val chosen = state.crew.firstOrNull { it.userId == draft.userId }
                ZillitSelect(
                    value = chosen ?: NO_CREW,
                    // Someone off the production cannot be alerted, so they are
                    // not offered.
                    options = listOf(NO_CREW) + state.crew.filter { it.isActive },
                    onSelect = { onEvent(AdminEvent.SosDraftChanged(draft.copy(userId = it.userId))) },
                    label = { it.fullName },
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitText(
                    text = "Their number comes from their profile, so it stays right when they change it.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            SosEntryType.Outsider -> {
                ZillitTextField(
                    value = draft.name,
                    onValueChange = { onEvent(AdminEvent.SosDraftChanged(draft.copy(name = it))) },
                    label = "Name",
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = draft.relationship,
                    onValueChange = { onEvent(AdminEvent.SosDraftChanged(draft.copy(relationship = it))) },
                    label = "Relationship to the production",
                    placeholder = "Unit nurse, local fixer, hospital",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitTextField(
                        value = draft.countryCode,
                        onValueChange = {
                            onEvent(AdminEvent.SosDraftChanged(draft.copy(countryCode = it)))
                        },
                        label = "Code",
                        placeholder = "+44",
                        modifier = Modifier.width(CODE_WIDTH),
                    )
                    ZillitTextField(
                        value = draft.phone,
                        onValueChange = { onEvent(AdminEvent.SosDraftChanged(draft.copy(phone = it))) },
                        label = "Phone",
                        helperText = "Five to twenty digits.",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        form.error?.let { ErrorLine(it) }
    }
}

/**
 * The company block.
 *
 * Every field is sent, including the blank ones — an omitted field is left
 * alone by the server, so a form that skipped its empties could never clear
 * one.
 */
@Suppress("LongMethod") // A form; see PreApprovalDialog.
@Composable
private fun CompanyDialog(
    form: AdminForm.Company,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
) {
    val draft = form.draft

    ZillitDialogShell(
        title = "Company details",
        subtitle = "Printed at the head of the crew list.",
        visible = true,
        onDismiss = { onEvent(AdminEvent.CloseForm) },
        width = WIDE_DIALOG,
        actions = { FormActions(state, submittable = true, onEvent, submitLabel = "Save details") },
    ) {
        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(AdminEvent.CompanyDraftChanged(draft.copy(name = it))) },
            label = "Company name",
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.address,
            onValueChange = { onEvent(AdminEvent.CompanyDraftChanged(draft.copy(address = it))) },
            label = "Address",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.registeredAddress,
            onValueChange = {
                onEvent(AdminEvent.CompanyDraftChanged(draft.copy(registeredAddress = it)))
            },
            label = "Registered address",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.countryCode,
                onValueChange = {
                    onEvent(AdminEvent.CompanyDraftChanged(draft.copy(countryCode = it)))
                },
                label = "Code",
                placeholder = "+44",
                modifier = Modifier.width(CODE_WIDTH),
            )
            ZillitTextField(
                value = draft.phone,
                onValueChange = { onEvent(AdminEvent.CompanyDraftChanged(draft.copy(phone = it))) },
                label = "Phone",
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = draft.email,
            onValueChange = { onEvent(AdminEvent.CompanyDraftChanged(draft.copy(email = it))) },
            label = "Email",
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.companyNumber,
            onValueChange = {
                onEvent(AdminEvent.CompanyDraftChanged(draft.copy(companyNumber = it)))
            },
            label = "Company number",
            modifier = Modifier.fillMaxWidth(),
        )

        CustomFields(draft.customFields, onEvent)

        form.error?.let { ErrorLine(it) }
    }
}

/**
 * The repeatable extra lines on the crew list header.
 *
 * A row with no label is dropped on save rather than refused: an admin who adds
 * a row and changes their mind should be able to save the rest without hunting
 * for the empty one.
 */
@Composable
private fun CustomFields(fields: List<CompanyField>, onEvent: (AdminEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = "Extra lines",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textMuted,
        )

        fields.forEachIndexed { index, field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = field.label,
                    onValueChange = { label ->
                        onEvent(
                            AdminEvent.CustomFieldsChanged(
                                fields.toMutableList().also { it[index] = field.copy(label = label) },
                            ),
                        )
                    },
                    placeholder = "Label",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = field.value,
                    onValueChange = { value ->
                        onEvent(
                            AdminEvent.CustomFieldsChanged(
                                fields.toMutableList().also { it[index] = field.copy(value = value) },
                            ),
                        )
                    },
                    placeholder = "Value",
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove this line",
                    onClick = {
                        onEvent(
                            AdminEvent.CustomFieldsChanged(
                                fields.toMutableList().also { it.removeAt(index) },
                            ),
                        )
                    },
                )
            }
        }

        ZillitButton(
            text = "Add a line",
            onClick = { onEvent(AdminEvent.CustomFieldsChanged(fields + CompanyField("", ""))) },
            variant = ButtonVariant.Tertiary,
            size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
        )
    }
}

/**
 * The yes/no for something that cannot be undone from here.
 *
 * The confirmation carries its own words — see `AdminConfirmation` — so this is
 * one dialog rather than one per action, and every message names the thing
 * being removed rather than saying "this item".
 */
@Composable
private fun ConfirmationDialog(state: AdminUiState, onEvent: (AdminEvent) -> Unit) {
    val confirmation = state.confirming ?: return

    ZillitDialogShell(
        title = confirmation.title,
        visible = true,
        onDismiss = { onEvent(AdminEvent.DismissConfirmation) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AdminEvent.DismissConfirmation) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = confirmation.confirmLabel,
                onClick = { onEvent(AdminEvent.ConfirmAction) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = confirmation.message,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/**
 * Cancel and submit, the same way on every form.
 *
 * Submit stays enabled while saving so the loading state has somewhere to show;
 * the view model refuses a second call, so a double click costs nothing.
 */
@Composable
private fun FormActions(
    state: AdminUiState,
    submittable: Boolean,
    onEvent: (AdminEvent) -> Unit,
    submitLabel: String = "Add",
) {
    ZillitButton(
        text = "Cancel",
        onClick = { onEvent(AdminEvent.CloseForm) },
        variant = ButtonVariant.Tertiary,
        enabled = !state.isSaving,
    )
    ZillitButton(
        text = submitLabel,
        onClick = { onEvent(AdminEvent.SubmitForm) },
        enabled = submittable && !state.isSaving,
        loading = state.isSaving,
    )
}

@Composable
private fun ErrorLine(message: String) {
    ZillitText(
        text = message,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.danger,
    )
}

/** The "nothing chosen" options, so a picker never opens on a real value. */
private val NO_DEPARTMENT = Department(id = "", name = "Choose a department")
private val NO_JOB_TITLE = JobTitle(id = "", name = "Choose a job title")
private val NO_CREW = CrewMember(userId = "", fullName = "Choose someone")

private val CODE_WIDTH = 96.dp
private val WIDE_DIALOG = 560.dp
