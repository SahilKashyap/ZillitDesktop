package com.zillit.desktop.feature.externalusers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.externalusers.domain.CREW_TYPE
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.LabeledValue

/**
 * External Users: the production's outside contacts — one roster, a form,
 * and a read-only card, the web's `Externaluser.jsx` as one desktop pane.
 */
@Composable
fun ExternalUsersScreen(
    state: ExternalUsersUiState,
    onEvent: (ExternalUsersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.viewer.isBlocked -> Centred("You don't have access to External Users.")
            else -> Roster(state, onEvent)
        }

        state.editing?.let { editing ->
            UserFormDialog(editing, state, onEvent)
        }
        state.details?.let { details ->
            DetailsDialog(details, state, onEvent)
        }
        state.confirmDelete?.let { doomed ->
            ZillitDialogShell(
                title = "Delete ${doomed.fullName}?",
                visible = true,
                onDismiss = { onEvent(ExternalUsersEvent.CancelDelete) },
                actions = {
                    ZillitButton(
                        text = "Cancel",
                        variant = ButtonVariant.Tertiary,
                        onClick = { onEvent(ExternalUsersEvent.CancelDelete) },
                    )
                    ZillitButton(
                        text = "Delete",
                        variant = ButtonVariant.Danger,
                        onClick = { onEvent(ExternalUsersEvent.ConfirmDelete) },
                    )
                },
            ) {
                ZillitText(
                    text = "The contact is removed from this production's directory.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Roster(state: ExternalUsersUiState, onEvent: (ExternalUsersEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = "External Users", style = ZillitTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (state.viewer.canPost || state.viewer.isAdmin) {
                ZillitButton(
                    text = "Add User",
                    leadingIcon = ZillitIcons.Add,
                    onClick = { onEvent(ExternalUsersEvent.New) },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ExternalUserBucket.entries.forEach { bucket ->
                ZillitChoiceChip(
                    label = bucket.label,
                    selected = state.bucket == bucket,
                    onClick = { onEvent(ExternalUsersEvent.Filter(bucket)) },
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitSearchField(
                value = state.query,
                onValueChange = { onEvent(ExternalUsersEvent.Search(it)) },
                placeholder = "Search by user name",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
        }

        when {
            state.isLoading && state.users.isEmpty() -> Centred("Loading users…")
            state.visible.isEmpty() -> Centred("No users found.")
            else -> UserRows(state, onEvent)
        }
    }
}

@Composable
private fun UserRows(state: ExternalUsersUiState, onEvent: (ExternalUsersEvent) -> Unit) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().then(rememberWheelScroll(listState)),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        items(state.visible, key = ExternalUser::id) { user ->
            UserRow(
                user = user,
                mayEdit = state.viewer.mayEdit(user),
                onOpen = { onEvent(ExternalUsersEvent.ShowDetails(user)) },
                onEdit = { onEvent(ExternalUsersEvent.Edit(user)) },
                onDelete = { onEvent(ExternalUsersEvent.Delete(user)) },
            )
        }
        if (state.hasMore && state.query.isBlank()) {
            item {
                LaunchedEffect(Unit) { onEvent(ExternalUsersEvent.LoadMore) }
                ZillitText(
                    text = if (state.isLoading) "Loading…" else "",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun UserRow(
    user: ExternalUser,
    mayEdit: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = user.fullName)
        UserIdentity(user, Modifier.weight(1f))
        if (hovered && mayEdit) {
            ZillitIconButton(
                icon = ZillitIcons.Edit,
                contentDescription = "Edit ${user.fullName}",
                onClick = onEdit,
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete ${user.fullName}",
                tint = colors.danger,
                onClick = onDelete,
            )
        }
    }
}

/** Name, gender and type over the one contact line the row can fit. */
@Composable
private fun UserIdentity(user: ExternalUser, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors

    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = user.fullName,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
            )
            if (user.gender.isNotBlank()) {
                ZillitText(
                    text = "(${user.gender.uppercase()})",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitTag(ExternalUserBucket.of(user.userType).typeLabel(user), tone = TagTone.Neutral)
        }
        ZillitText(
            text = listOfNotNull(
                user.email.takeIf { it.isNotBlank() },
                listOf(user.countryCode, user.phone)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "No contact details" },
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

private fun ExternalUserBucket.typeLabel(user: ExternalUser): String = when (this) {
    ExternalUserBucket.Others -> user.userType
    else -> label
}

@Composable
@Suppress("LongMethod") // One form, one function — the web's modal, field for field.
private fun UserFormDialog(
    editing: EditingUser,
    state: ExternalUsersUiState,
    onEvent: (ExternalUsersEvent) -> Unit,
) {
    val draft = editing.draft
    fun change(mutation: EditingUser.() -> EditingUser) =
        onEvent(ExternalUsersEvent.DraftChanged(editing.mutation()))

    ZillitDialogShell(
        title = if (editing.isNew) "Add User" else "Edit User",
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
                text = if (state.isSaving) "Saving…" else "Submit",
                enabled = !state.isSaving,
                onClick = { onEvent(ExternalUsersEvent.Submit) },
            )
        },
    ) {
        ZillitTextField(
            value = draft.fullName,
            onValueChange = { value -> change { copy(draft = draft.copy(fullName = value)) } },
            label = "Full name",
            errorText = editing.errors["fullName"],
        )
        ZillitTextField(
            value = draft.email,
            onValueChange = { value -> change { copy(draft = draft.copy(email = value)) } },
            label = "Email",
            errorText = editing.errors["email"],
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.countryCode,
                onValueChange = { value ->
                    // The dial code with its plus, digits only behind it.
                    val cleaned = value.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '+') }
                    change { copy(draft = draft.copy(countryCode = cleaned)) }
                },
                label = "Code",
                placeholder = "+44",
                errorText = editing.errors["countryCode"],
                modifier = Modifier.width(CODE_WIDTH),
            )
            ZillitTextField(
                value = draft.phone,
                onValueChange = { value ->
                    change { copy(draft = draft.copy(phone = value.filter(Char::isDigit))) }
                },
                label = "Phone",
                errorText = editing.errors["phone"],
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = "Gender",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitSelect(
                    value = draft.gender,
                    options = listOf("", "male", "female", "non-binary"),
                    onSelect = { value -> change { copy(draft = draft.copy(gender = value)) } },
                    label = { gender -> gender.ifBlank { "—" } },
                )
            }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = "Type",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitSelect(
                    value = editing.bucket,
                    options = listOf(
                        ExternalUserBucket.Crew,
                        ExternalUserBucket.Vendor,
                        ExternalUserBucket.Others,
                    ),
                    onSelect = { bucket -> change { copy(bucket = bucket) } },
                    label = ExternalUserBucket::label,
                )
            }
        }
        if (editing.bucket == ExternalUserBucket.Others) {
            ZillitTextField(
                value = editing.otherType,
                onValueChange = { value -> change { copy(otherType = value) } },
                label = "Which type?",
                errorText = editing.errors["userType"],
            )
        }
        if (editing.bucket == ExternalUserBucket.Crew) {
            CrewFields(editing, state, ::change)
        }

        ZillitText(
            text = "ADDITIONAL INFORMATION",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
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
                    placeholder = "Title",
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
                    icon = ZillitIcons.Close,
                    contentDescription = "Remove row",
                    onClick = { onEvent(ExternalUsersEvent.RemoveInfoRow(index)) },
                )
            }
        }
        ZillitButton(
            text = "Add more information",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
            onClick = { onEvent(ExternalUsersEvent.AddInfoRow) },
        )
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

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = "Department",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitSelect(
                value = department,
                options = state.departments,
                onSelect = { chosen ->
                    // Web parity: a department change clears the designation.
                    change {
                        copy(draft = draft.copy(departmentId = chosen?.id.orEmpty(), designationId = ""))
                    }
                },
                label = { option -> option?.name?.localised() ?: "—" },
            )
            editing.errors["departmentId"]?.let { FieldError(it) }
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = "Designation",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitSelect(
                value = department?.designations?.firstOrNull { it.id == draft.designationId },
                options = department?.designations.orEmpty(),
                onSelect = { chosen ->
                    change { copy(draft = draft.copy(designationId = chosen?.id.orEmpty())) }
                },
                label = { option -> option?.name?.localised() ?: "—" },
                enabled = department != null,
            )
        }
    }
}

private fun ExternalUser.withInfo(index: Int, row: LabeledValue): ExternalUser =
    copy(otherInfo = otherInfo.mapIndexed { i, existing -> if (i == index) row else existing })

@Composable
private fun DetailsDialog(
    user: ExternalUser,
    state: ExternalUsersUiState,
    onEvent: (ExternalUsersEvent) -> Unit,
) {
    val department = state.departments.firstOrNull { it.id == user.departmentId }

    ZillitDialogShell(
        title = "User Details",
        visible = true,
        onDismiss = { onEvent(ExternalUsersEvent.CloseDetails) },
        actions = {
            if (state.viewer.mayEdit(user)) {
                ZillitButton(
                    text = "Edit",
                    variant = ButtonVariant.Secondary,
                    onClick = {
                        onEvent(ExternalUsersEvent.CloseDetails)
                        onEvent(ExternalUsersEvent.Edit(user))
                    },
                )
            }
            ZillitButton(text = "Close", onClick = { onEvent(ExternalUsersEvent.CloseDetails) })
        },
    ) {
        DetailLine("Full name", user.fullName)
        DetailLine("Email", user.email)
        DetailLine("Phone", listOf(user.countryCode, user.phone).filter { it.isNotBlank() }.joinToString(" "))
        DetailLine("Gender", user.gender)
        DetailLine("Type", ExternalUserBucket.of(user.userType).typeLabel(user))
        if (user.userType == CREW_TYPE) {
            DetailLine("Department", department?.name?.localised().orEmpty())
            DetailLine(
                "Designation",
                department?.designations?.firstOrNull { it.id == user.designationId }
                    ?.name?.localised().orEmpty(),
            )
        }
        user.otherInfo.forEach { row -> DetailLine(row.label, row.value) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(DETAIL_LABEL_WIDTH),
        )
        ZillitText(
            text = value.ifBlank { "N/A" },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun FieldError(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.danger,
    )
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private val SEARCH_WIDTH = androidx.compose.ui.unit.Dp(260f)
private val FORM_WIDTH = androidx.compose.ui.unit.Dp(560f)
private val CODE_WIDTH = androidx.compose.ui.unit.Dp(110f)
private val DETAIL_LABEL_WIDTH = androidx.compose.ui.unit.Dp(120f)
