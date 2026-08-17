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
            text = "Zillit signs in a device, not a password. If you lose every device you " +
                "are signed in on, a code sent to this address is how you get back in — so " +
                "use one you can reach without this app.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )

        ZillitSectionCard(title = "Recovery email", icon = ZillitIcons.Mail) {
            ZillitTextField(
                value = state.email,
                onValueChange = { onEvent(AccountEvent.RecoveryEmailChanged(it)) },
                label = "Email address",
                placeholder = "you@example.com",
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving,
                // Only once there is something to be wrong about. Marking an
                // untouched field invalid is the form scolding someone for not
                // having typed yet.
                errorText = "That does not look like an email address."
                    .takeIf { state.email.isNotBlank() && !state.isValid },
                helperText = "The production never sees this address; it is not your Zillit mailbox.",
                onImeAction = { onEvent(AccountEvent.SaveRecoveryEmail) },
            )

            if (state.error != null) {
                ZillitNotice(text = state.error, tone = StatusTone.Rejected, icon = ZillitIcons.Info)
            }
            if (state.isSaved) {
                ZillitNotice(
                    text = "Saved. Keep the address reachable — a code sent there is the only " +
                        "way back if you lose your devices.",
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Check,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Absolute.Right,
            ) {
                ZillitButton(
                    text = "Save address",
                    enabled = state.canSave,
                    loading = state.isSaving,
                    onClick = { onEvent(AccountEvent.SaveRecoveryEmail) },
                )
            }
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
                title = "Could not read your devices",
            )

            state.devices.isEmpty() -> ZillitEmptyState(
                title = "Only this computer",
                message = "Nothing else is signed in to Zillit as you. Devices appear here " +
                    "once you link them from the phone app.",
                icon = ZillitIcons.Monitor,
            )

            else -> ZillitSectionCard(
                title = "Signed in",
                icon = ZillitIcons.Monitor,
                meta = "${state.devices.size} ${if (state.devices.size == 1) "device" else "devices"}",
                padded = false,
                action = {
                    ZillitButton(
                        text = "Refresh",
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
                if (device.isThisDevice) ZillitTag("This computer", tone = TagTone.Accent)
                if (device.isPrimary) ZillitTag("Main device", tone = TagTone.Neutral)
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
                text = "Sign out",
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = isUnlinking,
                onClick = { onEvent(AccountEvent.AskUnlink(device)) },
            )
        } else {
            // Says why, rather than showing a button the server would refuse.
            ZillitText(
                text = "Cannot be signed out",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun LoadingDevices() {
    ZillitSectionCard(title = "Signed in", icon = ZillitIcons.Monitor) {
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
        title = if (here) "Sign this computer out?" else "Sign out ${device?.displayName.orEmpty()}?",
        subtitle = if (here) "You will have to sign in again here." else null,
        icon = ZillitIcons.Monitor,
        visible = device != null,
        onDismiss = { onEvent(AccountEvent.DismissUnlink) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = if (here) {
                "This is the computer you are using. Signing it out closes Zillit here and " +
                    "removes the mail and production data stored on it. Your other devices " +
                    "stay signed in."
            } else {
                "That device is signed out the next time it contacts Zillit. Nothing it has " +
                    "already sent is affected, and it can be linked again later."
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(AccountEvent.DismissUnlink) },
            )
            ZillitButton(
                text = if (here) "Sign out here" else "Sign out",
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
    val production = seed.productionName.ifBlank { "this production" }
    val invite = inviteText(production, code)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        if (code.isBlank()) {
            // Nothing to share, said plainly. A copy button that copies "Code: "
            // is worse than an absent one.
            ZillitEmptyState(
                title = "No production code",
                message = "This production has no join code, so nobody can be invited with " +
                    "one. An administrator can check the production's setup.",
                icon = ZillitIcons.Info,
            )
            return@Column
        }

        ZillitSectionCard(title = "Production code", icon = ZillitIcons.Tools) {
            // Large and spaced, because this gets read aloud across a set as
            // often as it gets pasted.
            ZillitText(
                text = code,
                style = ZillitTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
            )
            ZillitText(
                text = "Anyone with this code can ask to join $production. They still have to " +
                    "be approved — nobody gets in on the code alone.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(title = "The message", icon = ZillitIcons.Mail) {
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
                    text = "Copy the code",
                    variant = ButtonVariant.Tertiary,
                    onClick = { onEvent(AccountEvent.CopyInvite(code)) },
                )
                ZillitButton(
                    text = "Copy the invite",
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
    "Join $production on Zillit. Install the app, choose “Join a production”, " +
        "and enter the code $code. An administrator will approve you."

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
        title = "Leave ${productionName.ifBlank { "this production" }}?",
        subtitle = "You come off the crew list.",
        icon = ZillitIcons.Detach,
        visible = state.isConfirming,
        onDismiss = { onEvent(AccountEvent.DismissLeave) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = "You stop receiving this production's notices, call sheets and messages, " +
                "and it disappears from your list. Coming back means using the production " +
                "code again and waiting for an administrator to approve you.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        if (isAdmin) {
            // The one case the other clients warn about, because a production
            // whose last admin leaves cannot approve anyone back in.
            ZillitNotice(
                text = "You administer this production. Make sure someone else is an " +
                    "administrator before you leave, or nobody can approve new crew.",
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
                text = "Stay",
                variant = ButtonVariant.Tertiary,
                enabled = !state.isLeaving,
                onClick = { onEvent(AccountEvent.DismissLeave) },
            )
            ZillitButton(
                text = "Leave production",
                variant = ButtonVariant.Danger,
                loading = state.isLeaving,
                onClick = { onEvent(AccountEvent.ConfirmLeave) },
            )
        }
    }
}

private const val SKELETON_ROWS = 3
private val DIALOG_WIDTH = 440.dp
