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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
                text = str(S.desktop_profile_not_admin_notice),
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
                text = str(S.desktop_undo_changes),
                variant = ButtonVariant.Tertiary,
                enabled = !form.isSaving,
                onClick = { onEvent(AccountEvent.ResetProfile) },
            )
            ZillitButton(
                // Names what the button will actually do, which differs by who
                // is pressing it.
                text = if (seed.isAdmin) str(S.desktop_save_profile) else str(S.av_send_for_approval),
                enabled = form.canSave,
                loading = form.isSaving,
                onClick = { onEvent(AccountEvent.SaveProfile) },
            )
        }
    }
}

@Composable
private fun NameCard(form: ProfileFormState, seed: ProfileSeed, onEvent: (AccountEvent) -> Unit) {
    ZillitSectionCard(title = str(S.docusign_type_hint), icon = ZillitIcons.User) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // Redrawn from the typed name rather than the stored one, so the
            // initials (for a profile without a picture) change as the field
            // does — the cheapest possible preview of what the crew list is
            // about to show.
            ZillitAvatar(
                name = "${form.firstName} ${form.lastName}".trim().ifBlank { seed.email },
                userId = seed.userId,
                size = AVATAR,
            )
            ZillitTextField(
                value = form.firstName,
                onValueChange = { onEvent(AccountEvent.FirstNameChanged(it)) },
                label = str(S.first_name_label),
                modifier = Modifier.weight(1f),
                enabled = !form.isSaving,
                // Said on the field rather than as a toast on submit: the user
                // is looking here, and both phone clients answer an empty name
                // with an alert that hides which one it meant.
                errorText = str(S.docusign_prop_required).takeIf { form.firstName.isBlank() },
            )
            ZillitTextField(
                value = form.lastName,
                onValueChange = { onEvent(AccountEvent.LastNameChanged(it)) },
                label = str(S.last_name_label),
                modifier = Modifier.weight(1f),
                enabled = !form.isSaving,
                errorText = str(S.docusign_prop_required).takeIf { form.lastName.isBlank() },
            )
        }

        if (seed.email.isNotBlank()) {
            ZillitText(
                text = str(S.desktop_signed_in_as_email_fixed, seed.email),
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
        title = str(S.desktop_department_and_role),
        icon = ZillitIcons.Tools,
        meta = str(S.desktop_department_and_role_meta).takeIf { !form.isLoadingDepartments },
    ) {
        ZillitText(
            text = str(S.desktop_department_and_role_hint),
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
                str(S.desktop_profile_saved_notice)

            ProfileSaveOutcome.SentForApproval ->
                str(S.desktop_profile_sent_notice)
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
            label = str(S.desktop_keep_name_off_crew_list),
            enabled = !form.isSaving,
        )
        ZillitText(
            text = str(S.desktop_keep_name_off_crew_list_hint),
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
    form.isLoadingDepartments -> str(S.desktop_loading_departments)
    form.departments.isEmpty() -> str(S.desktop_no_departments_on_project)
    else -> str(S.desktop_choose_a_department)
}

private fun rolePlaceholder(form: ProfileFormState): String = when {
    form.departmentId == null -> str(S.desktop_choose_a_department_first)
    form.roles.isEmpty() -> str(S.desktop_department_has_no_roles)
    else -> str(S.desktop_choose_a_role)
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
            label = str(S.desktop_show_mailbox_in_crew_list),
            enabled = !form.isSaving,
        )
        ZillitText(
            text = str(S.desktop_mailbox_appears_next_to_name, form.mailboxAddress.orEmpty()),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}
