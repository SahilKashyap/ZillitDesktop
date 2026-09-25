package com.zillit.desktop.feature.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where a recovery code goes when every device is gone.
 *
 * ## Why this page states the stakes
 *
 * Zillit signs a *device* in, not a password. Lose every device you are signed
 * in on and this address is the only way back to the account — there is no
 * "forgot password" behind it. Both phone clients present it as one more field
 * in a settings list; on the day it is needed, that framing is why it was never
 * filled in.
 */
@Composable
fun RecoveryEmailPage(state: RecoveryEmailState, onEvent: (AccountEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ZillitNotice(
            text = str(S.desktop_recovery_email_notice),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )

        RecoveryKeyCard(state, onEvent)

        ZillitSectionCard(title = str(S.recovery_email), icon = ZillitIcons.Mail) {
            ZillitTextField(
                value = state.email,
                onValueChange = { onEvent(AccountEvent.RecoveryEmailChanged(it)) },
                label = str(S.hint_email),
                placeholder = "you@example.com",
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving,
                // Only once there is something to be wrong about. Marking an
                // untouched field invalid is the form scolding someone for not
                // having typed yet.
                errorText = str(S.desktop_not_an_email_address)
                    .takeIf { state.email.isNotBlank() && !state.isValid },
                helperText = str(S.desktop_recovery_email_helper),
                onImeAction = { onEvent(AccountEvent.SaveRecoveryEmail) },
            )

            if (state.error != null) {
                ZillitNotice(text = state.error, tone = StatusTone.Rejected, icon = ZillitIcons.Info)
            }
            if (state.isSaved) {
                ZillitNotice(
                    text = str(S.desktop_recovery_email_saved_notice),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Check,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Absolute.Right,
            ) {
                ZillitButton(
                    text = str(S.desktop_save_address),
                    enabled = state.canSave,
                    loading = state.isSaving,
                    onClick = { onEvent(AccountEvent.SaveRecoveryEmail) },
                )
            }
        }
    }
}

/**
 * The recovery key, read-only, with a way to keep it.
 *
 * The web's Recovery dialog opens with it above the address; typed on a new
 * device, it brings every production back without an email round trip.
 */
@Composable
private fun RecoveryKeyCard(state: RecoveryEmailState, onEvent: (AccountEvent) -> Unit) {
    ZillitSectionCard(title = str(S.desktop_recovery_key), icon = ZillitIcons.Lock) {
        when {
            state.isLoadingKey && state.key.isBlank() -> ZillitSkeletonBar(Modifier.fillMaxWidth())

            state.keyError != null && state.key.isBlank() -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitNotice(
                    text = state.keyError,
                    tone = StatusTone.Rejected,
                    icon = ZillitIcons.Info,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = str(S.retry),
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    onClick = { onEvent(AccountEvent.ReloadRecoveryKey) },
                )
            }

            else -> ZillitTextField(
                value = state.key.ifBlank { "—" },
                onValueChange = {},
                readOnly = true,
                helperText = str(S.recovery_code_alert),
                modifier = Modifier.fillMaxWidth(),
                trailingContent = {
                    ZillitButton(
                        text = str(S.copy),
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Copy,
                        enabled = state.key.isNotBlank(),
                        onClick = { onEvent(AccountEvent.CopyRecoveryKey) },
                    )
                },
            )
        }
    }
}

/**
 * Every computer and phone signed in as this person.
 *
 * ## It is a security page, not an inventory
 *
 * The reason anyone opens it is a device they no longer have. So the row's
 * verb is "Sign out", the destructive tone is on the button rather than the
 * row, and the device you are reading this on is labelled as such — Android's
 * list gives no such warning, and signing yourself out by accident from a list
 * of near-identical device names is a real way to lose an evening.
 */
@Composable
fun LinkedDevicesPage(state: DevicesState, onEvent: (AccountEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        when {
            state.isLoading && state.devices.isEmpty() -> LoadingDevices()

            state.error != null && state.devices.isEmpty() -> ZillitErrorState(
                message = state.error,
                onRetry = { onEvent(AccountEvent.ReloadDevices) },
                title = str(S.desktop_could_not_read_devices),
            )

            state.devices.isEmpty() -> ZillitEmptyState(
                title = str(S.desktop_only_this_computer),
                message = str(S.desktop_only_this_computer_message),
                icon = ZillitIcons.Monitor,
            )

            else -> ZillitSectionCard(
                title = str(S.desktop_signed_in),
                icon = ZillitIcons.Monitor,
                meta = if (state.devices.size == 1) {
                    str(S.desktop_device_count_one, state.devices.size)
                } else {
                    str(S.desktop_device_count_other, state.devices.size)
                },
                padded = false,
                action = {
                    ZillitButton(
                        text = str(S.refresh_text),
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Reload,
                        loading = state.isLoading,
                        onClick = { onEvent(AccountEvent.ReloadDevices) },
                    )
                },
            ) {
                state.devices.forEachIndexed { index, device ->
                    if (index > 0) ZillitDivider()
                    DeviceRow(device, state.unlinkingId == device.id, onEvent)
                }
            }
        }

        // Shown under the list rather than replacing it: a failed unlink must
        // not take away the list the user is working through.
        if (state.error != null && state.devices.isNotEmpty()) {
            ZillitNotice(text = state.error, tone = StatusTone.Rejected, icon = ZillitIcons.Info)
        }
    }
}

@Composable
private fun DeviceRow(device: LinkedDevice, isUnlinking: Boolean, onEvent: (AccountEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Monitor,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = device.displayName, style = ZillitTheme.typography.titleSmall)
                if (device.isThisDevice) ZillitTag(str(S.desktop_this_computer), tone = TagTone.Accent)
                if (device.isPrimary) ZillitTag(str(S.desktop_main_device), tone = TagTone.Neutral)
            }
            if (device.detail.isNotBlank()) {
                ZillitText(
                    text = device.detail,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }

        if (device.canUnlink) {
            ZillitButton(
                text = str(S.desktop_sign_out),
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = isUnlinking,
                onClick = { onEvent(AccountEvent.AskUnlink(device)) },
            )
        } else {
            // Says why, rather than showing a button the server would refuse.
            ZillitText(
                text = str(S.desktop_cannot_be_signed_out),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun LoadingDevices() {
    ZillitSectionCard(title = str(S.desktop_signed_in), icon = ZillitIcons.Monitor) {
        repeat(SKELETON_ROWS) { ZillitSkeletonBar(modifier = Modifier.fillMaxWidth()) }
    }
}

/**
 * The confirmation, which changes shape for the device you are on.
 *
 * Signing out another device is a small thing; signing out this one ends the
 * session that is showing the dialog. The two deserve different words, and the
 * second deserves the reader's full attention before they press it.
 */
@Composable
fun UnlinkDialog(state: DevicesState, onEvent: (AccountEvent) -> Unit) {
    val device = state.confirming
    val here = device?.isThisDevice == true

    ZillitDialogShell(
        title = if (here) {
            str(S.desktop_sign_this_computer_out)
        } else {
            str(S.desktop_sign_out_device, device?.displayName.orEmpty())
        },
        subtitle = if (here) str(S.desktop_sign_in_again_here) else null,
        icon = ZillitIcons.Monitor,
        visible = device != null,
        onDismiss = { onEvent(AccountEvent.DismissUnlink) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = if (here) {
                str(S.desktop_sign_out_here_body)
            } else {
                str(S.desktop_sign_out_other_device_body)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(AccountEvent.DismissUnlink) },
            )
            ZillitButton(
                text = if (here) str(S.desktop_sign_out_here) else str(S.desktop_sign_out),
                variant = ButtonVariant.Danger,
                onClick = { onEvent(AccountEvent.ConfirmUnlink) },
            )
        }
    }
}

/**
 * The production's code, and something to paste.
 *
 * ## No API call
 *
 * Joining is pull, not push: the crew member enters the code and lands in the
 * "Approve new crew" queue an admin already has a page for. Nothing is sent
 * from here, which is why this page has no confirmation and no failure state —
 * it is a code and a copy button, matching what the web's invite modal does
 * once the sharing buttons are stripped of platforms a desktop app has no
 * business opening.
 */
@Composable
fun InviteCrewPage(seed: ProfileSeed, onEvent: (AccountEvent) -> Unit) {
    val code = seed.productionCode
    val production = seed.productionName.ifBlank { str(S.desktop_this_project) }
    val invite = inviteText(production, code)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        if (code.isBlank()) {
            // Nothing to share, said plainly. A copy button that copies "Code: "
            // is worse than an absent one.
            ZillitEmptyState(
                title = str(S.desktop_no_project_code),
                message = str(S.desktop_no_project_code_message),
                icon = ZillitIcons.Info,
            )
            return@Column
        }

        ZillitSectionCard(title = str(S.project_code), icon = ZillitIcons.Tools) {
            // Large and spaced, because this gets read aloud across a set as
            // often as it gets pasted.
            ZillitText(
                text = code,
                style = ZillitTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
            )
            ZillitText(
                text = str(S.desktop_invite_code_hint, production),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(title = str(S.desktop_the_message), icon = ZillitIcons.Mail) {
            ZillitText(
                text = invite,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
            ) {
                ZillitButton(
                    text = str(S.desktop_copy_the_code),
                    variant = ButtonVariant.Tertiary,
                    onClick = { onEvent(AccountEvent.CopyInvite(code)) },
                )
                ZillitButton(
                    text = str(S.desktop_copy_the_invite),
                    onClick = { onEvent(AccountEvent.CopyInvite(invite)) },
                )
            }
        }
    }
}

/**
 * What gets pasted into a message.
 *
 * Kept out of the composable so the wording can be asserted on: the code has to
 * survive being copied, and a template that drops it produces an invite nobody
 * can act on and nobody notices sending.
 */
internal fun inviteText(production: String, code: String): String =
    str(S.desktop_invite_text, production, code)

/**
 * Leaving the production, as a dialog on the Settings page.
 *
 * A dialog rather than a page, matching every other client: there is nothing to
 * read and one decision to make. What it does say is the part all three clients
 * leave out — coming back needs an admin, so this is not a toggle.
 */
@Composable
fun LeaveProductionDialog(
    state: LeaveState,
    productionName: String,
    isAdmin: Boolean,
    onEvent: (AccountEvent) -> Unit,
) {
    ZillitDialogShell(
        title = str(S.desktop_leave_production_title, productionName.ifBlank { str(S.desktop_this_production_lower) }),
        subtitle = str(S.desktop_leave_production_subtitle),
        icon = ZillitIcons.Detach,
        visible = state.isConfirming,
        onDismiss = { onEvent(AccountEvent.DismissLeave) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = str(S.desktop_leave_production_body),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        if (isAdmin) {
            // The one case the other clients warn about, because a production
            // whose last admin leaves cannot approve anyone back in.
            ZillitNotice(
                text = str(S.desktop_leave_production_admin_warning),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }

        if (state.error != null) {
            ZillitNotice(text = state.error, tone = StatusTone.Rejected, icon = ZillitIcons.Info)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.docusign_leave_stay),
                variant = ButtonVariant.Tertiary,
                enabled = !state.isLeaving,
                onClick = { onEvent(AccountEvent.DismissLeave) },
            )
            ZillitButton(
                text = str(S.desktop_leave_project),
                variant = ButtonVariant.Danger,
                loading = state.isLeaving,
                onClick = { onEvent(AccountEvent.ConfirmLeave) },
            )
        }
    }
}

private const val SKELETON_ROWS = 3
private val DIALOG_WIDTH = 440.dp
