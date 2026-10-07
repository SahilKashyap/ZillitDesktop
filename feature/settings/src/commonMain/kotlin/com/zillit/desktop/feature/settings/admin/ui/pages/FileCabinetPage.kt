package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.CabinetModule
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.FileCabinetState

/**
 * File Cabinet Documents — the web's `FileCabinateModal`: tick the modules to
 * archive, ask for a ZIP, and download it once the server has prepared it.
 *
 * One request at a time, as on the web. While it is being prepared the only
 * action is cancelling it, and once it is ready the only action is downloading.
 *
 * Not ported: the web also opens most rows in a read-only browser of that
 * module's records (each tool's own page, mounted inside the modal). The
 * archive is what this page is for, so rows here select rather than open.
 */
@Composable
fun FileCabinetPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    val cabinet = state.fileCabinet
    val uri = LocalUriHandler.current

    // The ZIP's address goes to the browser, which downloads it — the web's
    // `window.open(url, '_self')` — and the page forgets it.
    LaunchedEffect(cabinet.downloadUrl) {
        cabinet.downloadUrl?.let { url ->
            uri.openUri(url)
            onEvent(AdminEvent.CabinetUrlOpened)
        }
    }

    AdminPage(
        title = AdminDestination.FileCabinet.title,
        description = str(S.file_cabinet_info),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.search),
        action = { Actions(cabinet, onEvent) },
    ) {
        val rows = state.cabinetMatching
        RowCard {
            if (rows.isEmpty() && state.hasLoaded) EmptyRow(str(S.desktop_fc_no_documents))
            rows.forEachIndexed { index, module ->
                if (index > 0) RowRule()
                ModuleRow(module, cabinet, onEvent)
            }
        }
    }

    ZillitDialogShell(
        title = AdminDestination.FileCabinet.title,
        visible = cabinet.showNotice,
        onDismiss = { onEvent(AdminEvent.CabinetNoticeDismissed) },
        actions = {
            ZillitButton(text = str(S.ok), onClick = { onEvent(AdminEvent.CabinetNoticeDismissed) })
        },
    ) {
        ZillitText(
            text = str(S.desktop_fc_ready_message),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** The corner: what can be done about the request as it stands. */
@Composable
private fun RowScope.Actions(cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit) {
    val request = cabinet.request
    when {
        request == null -> {
            if (cabinet.selected.isNotEmpty()) {
                ZillitText(
                    text = "${cabinet.selected.size} ${str(S.selected)}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitButton(
                text = if (cabinet.allSelected) str(S.dd_deselect_all) else str(S.select_all),
                onClick = { onEvent(AdminEvent.CabinetSelectAllToggled) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = cabinet.modules.isNotEmpty() && !cabinet.isWorking,
            )
            ZillitButton(
                text = str(S.desktop_fc_request_download),
                onClick = { onEvent(AdminEvent.CabinetRequestDownload) },
                size = ButtonSize.Small,
                enabled = cabinet.selected.isNotEmpty() && !cabinet.isWorking,
                loading = cabinet.isWorking,
            )
        }

        request.isPreparing -> {
            ZillitButton(
                text = str(S.desktop_fc_cancel_request),
                onClick = { onEvent(AdminEvent.CabinetCancelRequest) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !cabinet.isWorking,
            )
            ZillitButton(
                text = "${str(S.desktop_fc_download_in_progress)}...",
                onClick = {},
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = false,
            )
        }

        else -> ZillitButton(
            text = str(S.download),
            onClick = { onEvent(AdminEvent.CabinetDownload) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            enabled = !cabinet.isWorking,
            loading = cabinet.isWorking,
        )
    }
}

@Composable
private fun ModuleRow(module: CabinetModule, cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit) {
    val locked = cabinet.request != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onEvent(AdminEvent.CabinetToggled(module.identifier)) }
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = module.name,
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitCheckbox(
            checked = module.identifier in cabinet.selected,
            onCheckedChange = { onEvent(AdminEvent.CabinetToggled(module.identifier)) },
            enabled = !locked,
        )
    }
}
