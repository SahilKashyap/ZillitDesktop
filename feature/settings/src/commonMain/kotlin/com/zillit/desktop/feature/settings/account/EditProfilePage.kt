package com.zillit.desktop.feature.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.localization.localised

/**
 * The crew member's own profile.
 *
 * ## Two ways this form can end
 *
 * An admin's edit is live the moment it saves. Everyone else's becomes a
 * request that an admin approves in the queue this module already has a page
 * for — and the difference is stated up front, not discovered afterwards from a
 * name that did not change. Both phone clients bury this in a toast that has
 * gone by the time the user looks back at the field.
 *
 * ## What is not editable here
 *
 * The email address and the production. Neither is a profile field: the address
 * belongs to the device registration, and the production is what the whole
 * window is scoped to. Showing them greyed out would invite an attempt.
 */
@Composable
fun EditProfilePage(state: AccountUiState, onEvent: (AccountEvent) -> Unit) {
    val form = state.profile
    val seed = state.seed

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        if (!seed.isAdmin) {
            ZillitNotice(
                text = "You are not an administrator on this project, so changes here are " +
                    "sent for approval rather than applied. Your profile updates once an " +
                    "admin accepts them.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }

        NameCard(form, seed, onEvent)
        PlacementCard(form, seed, onEvent)

        if (form.error != null) {
            ZillitNotice(text = form.error, tone = StatusTone.Rejected, icon = ZillitIcons.Info)
        }
        form.outcome?.let { SaveOutcomeNotice(it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Undo changes",
                variant = ButtonVariant.Tertiary,
                enabled = !form.isSaving,
                onClick = { onEvent(AccountEvent.ResetProfile) },
            )
            ZillitButton(
                // Names what the button will actually do, which differs by who
                // is pressing it.
                text = if (seed.isAdmin) "Save profile" else "Send for approval",
                enabled = form.canSave,
                loading = form.isSaving,
                onClick = { onEvent(AccountEvent.SaveProfile) },
            )
        }
    }
}

@Composable
private fun NameCard(form: ProfileFormState, seed: ProfileSeed, onEvent: (AccountEvent) -> Unit) {
    ZillitSectionCard(title = "Your name", icon = ZillitIcons.User) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // Redrawn from the typed name rather than the stored one, so the
            // initials change as the field does — the cheapest possible preview
            // of what the crew list is about to show.
            ZillitAvatar(
                name = "${form.firstName} ${form.lastName}".trim().ifBlank { seed.email },
                size = AVATAR,
            )
            ZillitTextField(
                value = form.firstName,
                onValueChange = { onEvent(AccountEvent.FirstNameChanged(it)) },
                label = "First name",
                modifier = Modifier.weight(1f),
                enabled = !form.isSaving,
                // Said on the field rather than as a toast on submit: the user
                // is looking here, and both phone clients answer an empty name
                // with an alert that hides which one it meant.
                errorText = "Required".takeIf { form.firstName.isBlank() },
            )
            ZillitTextField(
                value = form.lastName,
                onValueChange = { onEvent(AccountEvent.LastNameChanged(it)) },
                label = "Last name",
                modifier = Modifier.weight(1f),
                enabled = !form.isSaving,
                errorText = "Required".takeIf { form.lastName.isBlank() },
            )
        }

        if (seed.email.isNotBlank()) {
            ZillitText(
                text = "Signed in as ${seed.email}. Your address is set when the device is " +
                    "registered and cannot be changed here.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * Where this person sits on the production.
 *
 * The two pickers are dependent: roles belong to a department, so the second is
 * inert until the first has an answer, and says why rather than opening empty.
 */
@Composable
private fun PlacementCard(
    form: ProfileFormState,
    seed: ProfileSeed,
    onEvent: (AccountEvent) -> Unit,
) {
    ZillitSectionCard(
        title = "Department and role",
        icon = ZillitIcons.Tools,
        meta = "Decides which boards and tools you see".takeIf { !form.isLoadingDepartments },
    ) {
        ZillitText(
            text = "Changing your department moves which notices, call sheets and tools reach " +
                "you. Pick the department first — the roles below belong to it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSelect(
                value = form.department,
                options = form.departments,
                onSelect = { chosen -> chosen?.let { onEvent(AccountEvent.DepartmentChosen(it.id)) } },
                // Department names arrive as translation keys —
                // `transportation_department_label` — like everywhere else this
                // list is shown.
                label = { it?.name?.localised() ?: departmentPlaceholder(form) },
                enabled = !form.isSaving && form.departments.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = form.role,
                options = form.roles,
                onSelect = { chosen -> chosen?.let { onEvent(AccountEvent.RoleChosen(it.id)) } },
                label = { it?.name?.localised() ?: rolePlaceholder(form) },
                // A role list with no department chosen is a list of nothing,
                // and an enabled dropdown that opens empty reads as a page that
                // failed to load.
                enabled = !form.isSaving && form.roles.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }

        if (form.offersPrivateName(seed.designationName)) {
            PrivateNameRow(form, onEvent)
            if (form.mailboxAddress != null && !form.isPersonal) MailboxConsentRow(form, onEvent)
        }
    }
}

/** Which of the two things happened, in the words that describe each. */
@Composable
private fun SaveOutcomeNotice(outcome: ProfileSaveOutcome) {
    ZillitNotice(
        text = when (outcome) {
            ProfileSaveOutcome.Saved ->
                "Saved. The crew list and everything you have posted now carry the new details."

            ProfileSaveOutcome.SentForApproval ->
                "Sent. An administrator sees this in “Approve profile changes”; " +
                    "your profile is unchanged until one accepts it."
        },
        tone = StatusTone.Ready,
        icon = ZillitIcons.Check,
    )
}

/**
 * The producers-only switch.
 *
 * Offered to producers, main cast and studio executives and to nobody else —
 * see [allowsPrivateName]. The explanation is on the row because "keep my name
 * private" invites the reading that it hides the account, which it does not:
 * the person still appears, addressable, without their name attached.
 */
@Composable
private fun PrivateNameRow(form: ProfileFormState, onEvent: (AccountEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitCheckbox(
            checked = form.keepNamePrivate,
            onCheckedChange = { onEvent(AccountEvent.PrivateNameChanged(it)) },
            label = "Keep my name off the crew list",
            enabled = !form.isSaving,
        )
        ZillitText(
            text = "Your role still appears and people can still write to you — your name " +
                "is withheld from lists and generated documents.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * Says why the picker is empty when it is.
 *
 * "Not set" over a list that is still loading reads as a value; the load is
 * fast enough that the difference only shows on a bad connection, which is
 * exactly when it matters.
 */
private fun departmentPlaceholder(form: ProfileFormState): String = when {
    form.isLoadingDepartments -> "Loading departments…"
    form.departments.isEmpty() -> "No departments on this project"
    else -> "Choose a department"
}

private fun rolePlaceholder(form: ProfileFormState): String = when {
    form.departmentId == null -> "Choose a department first"
    form.roles.isEmpty() -> "This department has no roles yet"
    else -> "Choose a role"
}

private val AVATAR = 44.dp

/**
 * ZL-21078: whether the crew list shows this user's Zillit mailbox address.
 * Offered only to someone who has a mailbox, and never on a personal
 * production (no crew list there). The server's default is ON.
 */
@Composable
private fun MailboxConsentRow(form: ProfileFormState, onEvent: (AccountEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitCheckbox(
            checked = form.showMailboxInCrewList,
            onCheckedChange = { onEvent(AccountEvent.MailboxConsentChanged(it)) },
            label = "Show my Zillit mailbox address in the crew list",
            enabled = !form.isSaving,
        )
        ZillitText(
            text = "${form.mailboxAddress.orEmpty()} appears next to your name so the crew can write to it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}
