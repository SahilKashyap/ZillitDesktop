package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Ask someone outside the production to send files into a folder.
 *
 * The link is the product here: it goes to a supplier or a location owner who
 * has no Zillit account, and whatever they send lands in the folder. So the
 * link is copied the moment it exists and stays on screen to be copied again,
 * rather than living only in a toast that has already gone.
 */
@Composable
internal fun FileRequestDialog(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val panel = state.fileRequests
    if (!panel.open) return

    ZillitDialogShell(
        title = str(S.drive_request_files_title),
        subtitle = panel.folderName.takeIf { it.isNotBlank() }?.let { str(S.desktop_drive_request_into, it) },
        icon = ZillitIcons.Upload,
        visible = true,
        onDismiss = { onEvent(DriveEvent.CloseFileRequests) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DriveEvent.CloseFileRequests) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_drive_create_link),
                onClick = { onEvent(DriveEvent.SubmitFileRequest) },
                enabled = panel.canSubmit,
                loading = panel.submitting,
            )
        },
    ) {
        NewRequestForm(state, onEvent)
        panel.created?.let { made ->
            ZillitDivider()
            CreatedLink(made, onEvent)
        }
        ZillitDivider()
        OpenRequests(state, onEvent)
    }
}

@Composable
private fun NewRequestForm(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val panel = state.fileRequests
    ZillitTextField(
        value = panel.title,
        onValueChange = { onEvent(DriveEvent.FileRequestTitle(it)) },
        placeholder = str(S.desktop_drive_request_title_hint),
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitTextField(
        value = panel.description,
        onValueChange = { onEvent(DriveEvent.FileRequestDescription(it)) },
        placeholder = str(S.desktop_drive_request_note_hint),
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_drive_expires_in),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        // A short list rather than a date picker: nobody wants to think about
        // the exact hour a supplier's upload window shuts.
        listOf(7, 14, 30).forEach { days ->
            ZillitButton(
                text = str(S.ah_days_format, days),
                onClick = { onEvent(DriveEvent.FileRequestExpiry(days)) },
                variant = if (panel.expiryDays == days) ButtonVariant.Secondary else ButtonVariant.Tertiary,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitCheckbox(
            checked = panel.requireName,
            onCheckedChange = { onEvent(DriveEvent.FileRequestRequireName(it)) },
            label = str(S.desktop_drive_ask_for_name),
        )
        ZillitCheckbox(
            checked = panel.requireEmail,
            onCheckedChange = { onEvent(DriveEvent.FileRequestRequireEmail(it)) },
            label = str(S.desktop_drive_ask_for_email),
        )
    }
}

/** The link just made — already on the clipboard, and still here to copy again. */
@Composable
private fun CreatedLink(request: DriveFileRequest, onEvent: (DriveEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = str(S.desktop_drive_link_ready_copied),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
            )
            ZillitText(
                text = request.link.ifBlank { str(S.desktop_drive_no_address_returned) },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitButton(
            text = str(S.desktop_drive_copy_again),
            onClick = { onEvent(DriveEvent.CopyFileRequest(request)) },
            variant = ButtonVariant.Secondary,
            enabled = request.link.isNotBlank(),
        )
    }
}

@Composable
private fun OpenRequests(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val panel = state.fileRequests
    ZillitSectionLabel(text = str(S.desktop_drive_open_requests), modifier = Modifier.fillMaxWidth())
    when {
        panel.loading -> ZillitText(
            text = str(S.ah_loading),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )

        panel.requests.isEmpty() -> ZillitText(
            text = str(S.desktop_drive_no_open_requests),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )

        else -> panel.requests.forEach { request ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = request.title,
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    ZillitText(
                        text = if (request.uploadCount == 1) {
                            "1 file received"
                        } else {
                            "${request.uploadCount} files received"
                        },
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                // A revoked request keeps its row: it explains where a link
                // that someone still holds has gone.
                if (request.revoked) {
                    ZillitStatusPill(label = str(S.desktop_drive_revoked), tone = StatusTone.Rejected)
                } else {
                    ZillitButton(
                        text = str(S.copy),
                        onClick = { onEvent(DriveEvent.CopyFileRequest(request)) },
                        variant = ButtonVariant.Tertiary,
                        enabled = request.link.isNotBlank(),
                    )
                    ZillitButton(
                        text = str(S.drive_link_revoke),
                        onClick = { onEvent(DriveEvent.RevokeFileRequest(request)) },
                        variant = ButtonVariant.Danger,
                    )
                }
            }
        }
    }
}
