package com.zillit.desktop.feature.productionreport.ui

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
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.ui.pages.PublishSheetDialog
import com.zillit.desktop.feature.productionreport.ui.pages.SendSheetDialog
import com.zillit.desktop.feature.productionreport.ui.pages.SheetEditorPage
import com.zillit.desktop.feature.productionreport.ui.pages.SheetPdfOverlay

/**
 * The production report tool. Same hub shape as the call sheet — the two
 * lifecycles are deliberate mirrors of each other.
 */
@Composable
fun ProductionReportScreen(
    state: ReportUiState,
    onEvent: (ReportEvent) -> Unit,
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
private fun HubChrome(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitPageHeader(
                title = state.kind.title,
                description = when (state.kind) {
                    ReportKind.Production -> "Compose, review and publish the daily production report."
                    ReportKind.Ad -> "The 1st AD's daily report — day progress, cast times and requirements."
                    ReportKind.Wrap -> "The end-of-day wrap: day info, scenes and locations."
                },
                actions = {
                    if (state.viewer.canAuthor) {
                        ZillitButton(
                            text = "Create report",
                            onClick = { onEvent(ReportEvent.NewSheet) },
                            loading = state.busy,
                        )
                    }
                },
            )

            if (state.viewer.isBlocked) {
                ZillitNotice(text = "You do not have access to the production report tool.")
                return@Column
            }

            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = "Dismiss",
                            onClick = { onEvent(ReportEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }

            ZillitTabStrip(
                tabs = ReportDestination.entries
                    .filter { it.visibleTo(state.viewer, state.kind) }
                    .map { ZillitTab(id = it.name, label = it.label) },
                activeId = state.destination.name,
                onSelect = { id ->
                    onEvent(ReportEvent.Open(ReportDestination.valueOf(id)))
                },
            )

            if (state.destination == ReportDestination.Approvals) {
                ZillitTabStrip(
                    tabs = ApprovalBucket.entries.map { ZillitTab(id = it.name, label = it.label) },
                    activeId = state.bucket.name,
                    onSelect = { id ->
                        onEvent(ReportEvent.OpenBucket(ApprovalBucket.valueOf(id)))
                    },
                )
            }
    }
}

@Composable
private fun SheetList(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(state.listFor, key = { it.id }) { sheet ->
            SheetRow(state = state, sheet = sheet, onEvent = onEvent)
        }
    }
}

@Composable
private fun SheetRow(
    state: ReportUiState,
    sheet: ReportSummary,
    onEvent: (ReportEvent) -> Unit,
) {
    ZillitSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = sheet.name.ifBlank { state.kind.nameStem },
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
    state: ReportUiState,
    sheet: ReportSummary,
    onEvent: (ReportEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = "View",
            onClick = { onEvent(ReportEvent.ViewPdf(sheet.id, sheet.name)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        if (state.viewer.canAuthor && !sheet.status.locked) {
            ZillitButton(
                text = "Edit",
                onClick = { onEvent(ReportEvent.EditSheet(sheet.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        if (state.viewer.canAuthor && sheet.status == ReportStatus.Draft) {
            ZillitButton(
                text = "Send",
                onClick = { onEvent(ReportEvent.OpenSend(sheet.id, sheet.name)) },
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(ReportEvent.DeleteSheet(sheet.id)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
        if (state.bucket == ApprovalBucket.Received &&
            state.destination == ReportDestination.Approvals &&
            sheet.status.reviewInFlight
        ) {
            ZillitButton(
                text = "Approve",
                onClick = { onEvent(ReportEvent.Approve(sheet.id)) },
                size = ButtonSize.Small,
                loading = state.busy,
            )
            ZillitButton(
                text = "Reject",
                onClick = { onEvent(ReportEvent.Reject(sheet.id, "")) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
        if (state.viewer.canAuthor && sheet.status == ReportStatus.ApprovedForPublish) {
            ZillitButton(
                text = "Publish",
                onClick = { onEvent(ReportEvent.OpenPublish(sheet.id, sheet.name)) },
                size = ButtonSize.Small,
            )
        }
        Spacer(Modifier)
    }
}

internal val ReportStatus.tone: StatusTone
    get() = when (this) {
        ReportStatus.Draft -> StatusTone.Neutral
        ReportStatus.PendingInternalApproval, ReportStatus.PendingApproval -> StatusTone.Pending
        ReportStatus.InternalApproved -> StatusTone.Progress
        ReportStatus.ApprovedForPublish -> StatusTone.Ready
        ReportStatus.Published -> StatusTone.Done
        ReportStatus.ApprovalRejected -> StatusTone.Rejected
        ReportStatus.Deleted, ReportStatus.Unknown -> StatusTone.Neutral
    }
