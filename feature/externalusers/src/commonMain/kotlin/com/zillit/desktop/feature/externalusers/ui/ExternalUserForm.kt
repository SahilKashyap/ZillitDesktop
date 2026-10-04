package com.zillit.desktop.feature.externalusers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelectTrigger
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.externalusers.domain.DialCode
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.Gender
import com.zillit.desktop.feature.externalusers.domain.LabeledValue

/**
 * The Add / Edit User dialog — the web's `ExternalUserModal.jsx`, field for
 * field: name and email; dial code and phone; gender and type; the crew
 * pickers or the free-typed type; then any number of label/value rows.
 */
@Composable
@Suppress("LongMethod") // One form, one function — the web's modal, field for field.
internal fun ExternalUserForm(
    editing: EditingUser,
    state: ExternalUsersUiState,
    onEvent: (ExternalUsersEvent) -> Unit,
) {
    val draft = editing.draft
    fun change(mutation: EditingUser.() -> EditingUser) =
        onEvent(ExternalUsersEvent.DraftChanged(editing.mutation()))

    ZillitDialogShell(
        title = if (editing.isNew) str(S.desktop_eu_add_user) else str(S.desktop_eu_edit_user),
        subtitle = if (editing.isNew) str(S.desktop_eu_form_subtitle) else draft.fullName,
        icon = ZillitIcons.User,
        visible = true,
        onDismiss = { onEvent(ExternalUsersEvent.CancelEdit) },
        width = FORM_WIDTH,
        actions = {
            editing.errors["submit"]?.let { message ->
                ZillitText(
                    text = message,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                    modifier = Modifier.weight(1f),
                )
            }
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(ExternalUsersEvent.CancelEdit) },
            )
            ZillitButton(
                text = str(S.submit),
                loading = state.isSaving,
                enabled = !state.isSaving,
                onClick = { onEvent(ExternalUsersEvent.Submit) },
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = draft.fullName,
                onValueChange = { value -> change { copy(draft = draft.copy(fullName = value)) } },
                label = str(S.desktop_full_name_required_label),
                placeholder = str(S.desktop_enter_full_name),
                errorText = editing.errors["fullName"],
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.email,
                onValueChange = { value -> change { copy(draft = draft.copy(email = value)) } },
                label = str(S.docusign_add_contact_email_label),
                placeholder = str(S.dm_email_hint),
                keyboardType = KeyboardType.Email,
                errorText = editing.errors["email"],
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel(str(S.dm_loanout_country_code))
                DialCodePicker(
                    value = draft.countryCode,
                    codes = state.dialCodes,
                    isError = editing.errors["countryCode"] != null,
                    onPick = { code -> change { copy(draft = draft.copy(countryCode = code)) } },
                )
                editing.errors["countryCode"]?.let { FieldError(it) }
            }
            ZillitTextField(
                value = draft.phone,
                onValueChange = { value ->
                    // The web swallows every key but a digit or Backspace.
                    change { copy(draft = draft.copy(phone = value.filter(Char::isDigit))) }
                },
                label = str(S.phone),
                placeholder = str(S.desktop_sos_digits_only),
                keyboardType = KeyboardType.Phone,
                errorText = editing.errors["phone"],
                modifier = Modifier.weight(2f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel(str(S.gender))
                ZillitSelect(
                    value = Gender.entries.firstOrNull { it.wire == draft.gender.lowercase() }
                        ?: Gender.NonBinary.takeIf { draft.gender.equals("other", ignoreCase = true) },
                    options = listOf(null) + Gender.entries,
                    onSelect = { value -> change { copy(draft = draft.copy(gender = value?.wire.orEmpty())) } },
                    label = { gender -> gender?.label ?: str(S.desktop_select_gender) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel(str(S.ce_note_type_label))
                ZillitSelect(
                    value = editing.bucket,
                    options = listOf(
                        ExternalUserBucket.Crew,
                        ExternalUserBucket.Vendor,
                        ExternalUserBucket.Others,
                    ),
                    onSelect = { bucket -> change { copy(bucket = bucket, errors = errors - "userType") } },
                    label = ExternalUserBucket::label,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (editing.bucket == ExternalUserBucket.Others) {
            ZillitTextField(
                value = editing.otherType,
                onValueChange = { value -> change { copy(otherType = value) } },
                label = str(S.ce_note_type_label),
                placeholder = str(S.desktop_enter_the_type),
                errorText = editing.errors["userType"],
            )
        }
        if (editing.bucket == ExternalUserBucket.Crew) {
            CrewFields(editing, state, ::change)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = str(S.desktop_additional_information_upper),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = str(S.add_more_information),
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                    onClick = { onEvent(ExternalUsersEvent.AddInfoRow) },
                )
            }
            draft.otherInfo.forEachIndexed { index, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitTextField(
                        value = row.label,
                        onValueChange = { value ->
                            change { copy(draft = draft.withInfo(index, row.copy(label = value))) }
                        },
                        placeholder = str(S.desktop_label_name),
                        errorText = editing.errors["otherInfo$index"],
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = row.value,
                        onValueChange = { value ->
                            change { copy(draft = draft.withInfo(index, row.copy(value = value))) }
                        },
                        placeholder = str(S.description),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.desktop_remove_row_n, index + 1),
                        tint = ZillitTheme.colors.danger,
                        onClick = { onEvent(ExternalUsersEvent.RemoveInfoRow(index)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CrewFields(
    editing: EditingUser,
    state: ExternalUsersUiState,
    change: (EditingUser.() -> EditingUser) -> Unit,
) {
    val draft = editing.draft
    val department = state.departments.firstOrNull { it.id == draft.departmentId }

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel(str(S.dm_step2_department))
            ZillitSelect(
                value = department,
                options = listOf(null) + state.departments,
                onSelect = { chosen ->
                    // Web parity: a department change clears the designation.
                    change {
                        copy(
                            draft = draft.copy(departmentId = chosen?.id.orEmpty(), designationId = ""),
                            errors = errors - "departmentId",
                        )
                    }
                },
                label = { option -> option?.name?.localised() ?: str(S.ah_select_department) },
                modifier = Modifier.fillMaxWidth(),
            )
            editing.errors["departmentId"]?.let { FieldError(it) }
        }
        // The web shows the designation column only once a department is chosen.
        if (department != null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel(str(S.designation))
                ZillitSelect(
                    value = department.designations.firstOrNull { it.id == draft.designationId },
                    options = listOf(null) + department.designations,
                    onSelect = { chosen ->
                        change { copy(draft = draft.copy(designationId = chosen?.id.orEmpty())) }
                    },
                    label = { option -> option?.name?.localised() ?: str(S.select_designation) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The dial-code selector beside the phone input — the web's searchable,
 * clearable antd `Select`: a narrow field showing `+44`, and a wider list
 * naming every country so the right one can be found, alphabetical as the
 * web's `filterSort` leaves it. The app's one select field and list, built
 * from the parts so a code shows even before the codes have loaded, and
 * every country sharing it (+1) is ticked.
 */
@Composable
private fun DialCodePicker(
    value: String,
    codes: List<DialCode>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val sorted = remember(codes) { codes.sortedBy { it.label.lowercase() } }
    Box(modifier) {
        ZillitSelectTrigger(
            open = open,
            enabled = true,
            isError = isError,
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            minHeight = FIELD_HEIGHT,
            onClear = if (value.isNotEmpty()) ({ onPick("") }) else null,
        ) {
            ZillitText(
                text = value.ifEmpty { str(S.desktop_select_code) },
                style = ZillitTheme.typography.bodyMedium,
                color = if (value.isEmpty()) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = LIST_WIDTH,
                options = sorted,
                isSelected = { it.dialCode == value },
                onPick = { code ->
                    open = false
                    onPick(code.dialCode)
                },
                label = { it.dialCode },
                subtitle = { it.name },
                searchText = { it.label },
                searchable = true,
                searchPlaceholder = str(S.desktop_search_country_or_code),
                showInitials = false,
                emptyText = str(S.desktop_country_codes_still_loading),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.label,
        color = ZillitTheme.colors.textSecondary,
    )
}

@Composable
private fun FieldError(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.danger,
    )
}

private fun ExternalUser.withInfo(index: Int, row: LabeledValue): ExternalUser =
    copy(otherInfo = otherInfo.mapIndexed { i, existing -> if (i == index) row else existing })

private val FORM_WIDTH = 650.dp
private val FIELD_HEIGHT = 32.dp
private val LIST_WIDTH = 280.dp
