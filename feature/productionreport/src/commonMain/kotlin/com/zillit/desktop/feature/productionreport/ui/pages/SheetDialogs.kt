package com.zillit.desktop.feature.productionreport.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.PublishDialog
import com.zillit.desktop.feature.productionreport.ui.SendDialog
import com.zillit.desktop.feature.productionreport.ui.SheetPdfView
import com.zillit.desktop.feature.productionreport.ui.decodeImageBitmap

/**
 * Send for review: signature (assignees come from project metadata) or
 * comments, with the reviewer picker the comments round needs.
 */
@Composable
internal fun SendSheetDialog(
    state: ReportUiState,
    dialog: SendDialog,
    onEvent: (ReportEvent) -> Unit,
) {
    ZillitDialogShell(
        title = "Send \"${dialog.sheetName.ifBlank { "call sheet" }}\" for review",
        onDismiss = { onEvent(ReportEvent.DismissSend) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ReportEvent.DismissSend) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (dialog.forComments) "Send for comments" else "Send for signature",
                onClick = { onEvent(ReportEvent.ConfirmSend) },
                loading = state.busy,
                enabled = !dialog.forComments || dialog.selectedIds.isNotEmpty(),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitCheckbox(
                checked = !dialog.forComments,
                onCheckedChange = { onEvent(ReportEvent.SendModeChanged(forComments = false)) },
                label = "For signature — the project's final approvers sign off",
            )
            ZillitCheckbox(
                checked = dialog.forComments,
                onCheckedChange = { onEvent(ReportEvent.SendModeChanged(forComments = true)) },
                label = "For comments — pick who reviews first",
            )
            if (dialog.forComments) {
                ZillitText(
                    text = "Reviewers",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                state.members.forEach { member ->
                    ZillitCheckbox(
                        checked = member.userId in dialog.selectedIds,
                        onCheckedChange = { onEvent(ReportEvent.ToggleReviewer(member.userId)) },
                        label = member.fullName +
                            member.designation.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                    )
                }
            }
        }
    }
}

/** Publish: new sheet (replaces the pinned PDF) or continuation (appends). */
@Composable
internal fun PublishSheetDialog(
    dialog: PublishDialog,
    busy: Boolean,
    onEvent: (ReportEvent) -> Unit,
) {
    ZillitDialogShell(
        title = "Publish \"${dialog.sheetName.ifBlank { "call sheet" }}\"",
        onDismiss = { onEvent(ReportEvent.DismissPublish) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ReportEvent.DismissPublish) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Publish",
                onClick = { onEvent(ReportEvent.ConfirmPublish) },
                loading = busy,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitCheckbox(
                checked = !dialog.continuation,
                onCheckedChange = {
                    onEvent(ReportEvent.PublishOptionsChanged(continuation = false))
                },
                label = "New call sheet — replaces the previous one in the Home feed",
            )
            ZillitCheckbox(
                checked = dialog.continuation,
                onCheckedChange = {
                    onEvent(ReportEvent.PublishOptionsChanged(continuation = true))
                },
                label = "Continuation — appears alongside the previous one",
            )
            ZillitTextField(
                value = dialog.notes,
                onValueChange = { onEvent(ReportEvent.PublishOptionsChanged(notes = it)) },
                label = "Publish notes (optional)",
                singleLine = false,
            )
        }
    }
}

/** The server-rendered PDF, page images stacked in a scrolling overlay. */
@Composable
internal fun SheetPdfOverlay(
    view: SheetPdfView,
    onEvent: (ReportEvent) -> Unit,
) {
    ZillitDialogShell(
        title = view.title.ifBlank { "Call sheet" },
        onDismiss = { onEvent(ReportEvent.ClosePdf) },
        visible = true,
        scrollable = false,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ReportEvent.ClosePdf) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (view.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                view.pages.forEach { page ->
                    val bitmap = remember(page.page) { decodeImageBitmap(page.imageBytes) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Page ${page.page + 1}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
