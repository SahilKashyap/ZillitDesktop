package com.zillit.desktop.feature.externalusers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
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
        title = if (editing.isNew) "Add User" else "Edit User",
        subtitle = if (editing.isNew) "A contact for this production's directory" else draft.fullName,
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
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(ExternalUsersEvent.CancelEdit) },
            )
            ZillitButton(
                text = "Submit",
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
                label = "Full name *",
                placeholder = "Enter full name",
                errorText = editing.errors["fullName"],
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.email,
                onValueChange = { value -> change { copy(draft = draft.copy(email = value)) } },
                label = "Email *",
                placeholder = "name@example.com",
                keyboardType = KeyboardType.Email,
                errorText = editing.errors["email"],
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Country code")
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
                label = "Phone",
                placeholder = "Digits only",
                keyboardType = KeyboardType.Phone,
                errorText = editing.errors["phone"],
                modifier = Modifier.weight(2f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Gender")
                ZillitSelect(
                    value = Gender.entries.firstOrNull { it.wire == draft.gender.lowercase() }
                        ?: Gender.NonBinary.takeIf { draft.gender.equals("other", ignoreCase = true) },
                    options = listOf(null) + Gender.entries,
                    onSelect = { value -> change { copy(draft = draft.copy(gender = value?.wire.orEmpty())) } },
                    label = { gender -> gender?.label ?: "Select gender" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Type *")
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
                label = "Type *",
                placeholder = "Enter the type",
                errorText = editing.errors["userType"],
            )
        }
        if (editing.bucket == ExternalUserBucket.Crew) {
            CrewFields(editing, state, ::change)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = "ADDITIONAL INFORMATION",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = "Add more information",
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
                        placeholder = "Label name",
                        errorText = editing.errors["otherInfo$index"],
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = row.value,
                        onValueChange = { value ->
                            change { copy(draft = draft.withInfo(index, row.copy(value = value))) }
                        },
                        placeholder = "Description",
                        modifier = Modifier.weight(1f),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Remove row ${index + 1}",
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
            FieldLabel("Department *")
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
                label = { option -> option?.name?.localised() ?: "Select department" },
                modifier = Modifier.fillMaxWidth(),
            )
            editing.errors["departmentId"]?.let { FieldError(it) }
        }
        // The web shows the designation column only once a department is chosen.
        if (department != null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Designation")
                ZillitSelect(
                    value = department.designations.firstOrNull { it.id == draft.designationId },
                    options = listOf(null) + department.designations,
                    onSelect = { chosen ->
                        change { copy(draft = draft.copy(designationId = chosen?.id.orEmpty())) }
                    },
                    label = { option -> option?.name?.localised() ?: "Select designation" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The dial-code selector beside the phone input — the web's searchable,
 * clearable antd `Select`: a narrow trigger showing `+44`, and a wider list
 * naming every country so the right one can be found.
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
    Box(modifier) {
        DialCodeTrigger(value, open, isError, onOpen = { open = true }, onClear = { onPick("") })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(8.dp)),
        ) {
            DialCodeList(
                codes = codes,
                selected = value,
                onPick = { code ->
                    open = false
                    onPick(code)
                },
            )
        }
    }
}

/** The closed picker: the code or its placeholder, a chevron — a clear button on hover once one is set. */
@Composable
private fun DialCodeTrigger(
    value: String,
    open: Boolean,
    isError: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = when {
        isError -> colors.danger
        open || hovered -> colors.accent
        else -> colors.border
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, border, ZillitTheme.shapes.medium)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = value.ifEmpty { "Select code" },
            style = ZillitTheme.typography.bodyMedium,
            color = if (value.isEmpty()) colors.textDisabled else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty() && hovered) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(2.dp)) {
                ZillitIcon(ZillitIcons.Close, contentDescription = "Clear code", tint = colors.textMuted, size = 12.dp)
            }
        } else {
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = 13.dp)
        }
    }
}

@Composable
private fun DialCodeList(codes: List<DialCode>, selected: String, onPick: (String) -> Unit) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shown = remember(codes, query) {
        val needle = query.trim()
        val matching = if (needle.isEmpty()) codes else codes.filter { it.label.contains(needle, ignoreCase = true) }
        // The web's `filterSort`: alphabetical by label.
        matching.sortedBy { it.label.lowercase() }
    }
    Column(Modifier.width(LIST_WIDTH).padding(horizontal = 8.dp, vertical = 4.dp)) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search country or code",
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        // A FIXED height: a lazy list inside a menu measures its intrinsics on
        // open and crashes under a mere `heightIn`.
        LazyColumn(Modifier.fillMaxWidth().height(LIST_HEIGHT).padding(top = 6.dp)) {
            if (shown.isEmpty()) {
                item {
                    ZillitText(
                        text = if (codes.isEmpty()) "Country codes are still loading" else "No matching country",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            items(shown, key = { "${it.isoCode}-${it.dialCode}-${it.name}" }) { code ->
                DialCodeRow(code, picked = code.dialCode == selected) { onPick(code.dialCode) }
            }
        }
    }
}

@Composable
private fun DialCodeRow(code: DialCode, picked: Boolean, onPick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitText(
        text = code.label,
        style = ZillitTheme.typography.bodyMedium,
        color = if (picked) colors.accentText else colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    picked -> colors.surfaceSelected
                    hovered -> colors.surfaceHover
                    else -> colors.surfaceRaised
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
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
private val LIST_HEIGHT = 280.dp
