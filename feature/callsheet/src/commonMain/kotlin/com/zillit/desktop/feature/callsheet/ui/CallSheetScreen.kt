package com.zillit.desktop.feature.callsheet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.ui.pages.PublishSheetDialog
import com.zillit.desktop.feature.callsheet.ui.pages.SendSheetDialog
import com.zillit.desktop.feature.callsheet.ui.pages.SheetEditorPage
import com.zillit.desktop.feature.callsheet.ui.pages.SheetPdfOverlay

/**
 * The call sheet tool.
 *
 * One hub with three tabs; the editor and the PDF viewer open over it as
 * full-page states rather than routes, because a sheet mid-edit and its list
 * are never on screen together on the web either.
 */
@Composable
fun CallSheetScreen(
    state: CallSheetUiState,
    onEvent: (CallSheetEvent) -> Unit,
) {
    val editor = state.editor
    if (editor != null) {
        SheetEditorPage(state = state, editor = editor, onEvent = onEvent)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            HubChrome(state = state, onEvent = onEvent)

            when {
                state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ZillitSpinner()
                }
                state.listFor.isEmpty() -> ZillitText(
                    text = "Nothing here yet.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textMuted,
                )
                else -> SheetList(state = state, onEvent = onEvent)
            }
        }

        state.pdf?.let { SheetPdfOverlay(view = it, onEvent = onEvent) }
        state.send?.let { SendSheetDialog(state = state, dialog = it, onEvent = onEvent) }
        state.publish?.let { PublishSheetDialog(dialog = it, busy = state.busy, onEvent = onEvent) }
    }
}

@Composable
@Suppress("LongMethod") // Chrome: header, notices and two tab strips in order.
private fun HubChrome(state: CallSheetUiState, onEvent: (CallSheetEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitPageHeader(
                title = "Call Sheet",
                description = "Compose, review and publish the day's call sheet.",
                actions = {
                    if (state.viewer.canAuthor) {
                        ZillitButton(
                            text = "Create call sheet",
                            onClick = { onEvent(CallSheetEvent.NewSheet) },
                            loading = state.busy,
                        )
                    }
                },
            )

            if (state.viewer.isBlocked) {
                ZillitNotice(text = "You do not have access to the call sheet tool.")
                return@Column
            }

            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = "Dismiss",
                            onClick = { onEvent(CallSheetEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }

            ZillitTabStrip(
                tabs = CallSheetDestination.entries
                    .filter { it.visibleTo(state.viewer) }
                    .map { ZillitTab(id = it.name, label = it.label) },
                activeId = state.destination.name,
                onSelect = { id ->
                    onEvent(CallSheetEvent.Open(CallSheetDestination.valueOf(id)))
                },
            )

            if (state.destination == CallSheetDestination.Approvals) {
                ZillitTabStrip(
                    tabs = ApprovalBucket.entries.map { ZillitTab(id = it.name, label = it.label) },
                    activeId = state.bucket.name,
                    onSelect = { id ->
                        onEvent(CallSheetEvent.OpenBucket(ApprovalBucket.valueOf(id)))
                    },
                )
            }
    }
}

@Composable
private fun SheetList(state: CallSheetUiState, onEvent: (CallSheetEvent) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.listFor, key = { it.id }) { sheet ->
            SheetRow(state = state, sheet = sheet, onEvent = onEvent)
        }
    }
}

@Composable
private fun SheetRow(
    state: CallSheetUiState,
    sheet: CallSheetSummary,
    onEvent: (CallSheetEvent) -> Unit,
) {
    ZillitSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = sheet.name.ifBlank { "Call sheet ${sheet.serialNo}" },
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitText(
                    text = listOf(sheet.serialNo, sheet.createdBy)
                        .filter { it.isNotBlank() }
                        .joinToString("  ·  "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitStatusPill(label = sheet.status.label, tone = sheet.status.tone)
            RowActions(state = state, sheet = sheet, onEvent = onEvent)
        }
    }
}

@Composable
private fun RowActions(
    state: CallSheetUiState,
    sheet: CallSheetSummary,
    onEvent: (CallSheetEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = "View",
            onClick = { onEvent(CallSheetEvent.ViewPdf(sheet.id, sheet.name)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        if (state.viewer.canAuthor && !sheet.status.locked) {
            ZillitButton(
                text = "Edit",
                onClick = { onEvent(CallSheetEvent.EditSheet(sheet.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        if (state.viewer.canAuthor && sheet.status == CallSheetStatus.Draft) {
            ZillitButton(
                text = "Send",
                onClick = { onEvent(CallSheetEvent.OpenSend(sheet.id, sheet.name)) },
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(CallSheetEvent.DeleteSheet(sheet.id)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
        if (state.bucket == ApprovalBucket.Received &&
            state.destination == CallSheetDestination.Approvals &&
            sheet.status.reviewInFlight
        ) {
            ZillitButton(
                text = "Approve",
                onClick = { onEvent(CallSheetEvent.Approve(sheet.id)) },
                size = ButtonSize.Small,
                loading = state.busy,
            )
            ZillitButton(
                text = "Reject",
                onClick = { onEvent(CallSheetEvent.Reject(sheet.id, "")) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
        if (state.viewer.canAuthor && sheet.status == CallSheetStatus.ApprovedForPublish) {
            ZillitButton(
                text = "Publish",
                onClick = { onEvent(CallSheetEvent.OpenPublish(sheet.id, sheet.name)) },
                size = ButtonSize.Small,
            )
        }
        Spacer(Modifier)
    }
}

internal val CallSheetStatus.tone: StatusTone
    get() = when (this) {
        CallSheetStatus.Draft -> StatusTone.Neutral
        CallSheetStatus.PendingInternalApproval, CallSheetStatus.PendingApproval -> StatusTone.Pending
        CallSheetStatus.InternalApproved -> StatusTone.Progress
        CallSheetStatus.ApprovedForPublish -> StatusTone.Ready
        CallSheetStatus.Published -> StatusTone.Done
        CallSheetStatus.ApprovalRejected -> StatusTone.Rejected
        CallSheetStatus.Deleted, CallSheetStatus.Unknown -> StatusTone.Neutral
    }
