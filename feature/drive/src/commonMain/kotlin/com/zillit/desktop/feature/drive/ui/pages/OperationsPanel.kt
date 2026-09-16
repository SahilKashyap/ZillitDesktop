package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.UploadState
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState

/**
 * The floating bottom-right panel for uploads in flight — the web's
 * `OperationsPanel`: a header with the active count that folds the list
 * away, then one row per upload with its progress, status and a cancel or
 * remove control, and "Clear completed" once anything has finished.
 */
@Composable
@Suppress("LongMethod") // The panel's header and list, together.
internal fun OperationsPanel(state: DriveUiState, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val uploads = state.uploads
    AnimatedVisibility(
        visible = uploads.isNotEmpty(),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .width(PANEL_WIDTH)
                .shadow(PANEL_SHADOW, ZillitTheme.shapes.large)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, ZillitTheme.shapes.large),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(icon = ZillitIcons.Upload, tint = colors.accent, size = ZillitTheme.spacing.lg)
                ZillitText(
                    text = if (state.activeUploads.isEmpty()) {
                        "Uploads complete"
                    } else {
                        "Uploading ${state.activeUploads.size} of ${uploads.size}"
                    },
                    style = ZillitTheme.typography.label,
                    modifier = Modifier.weight(1f),
                )
                if (uploads.any { it.isSettled }) {
                    ZillitButton(
                        text = "Clear completed",
                        onClick = { onEvent(DriveEvent.ClearFinishedUploads) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                ZillitIconButton(
                    icon = if (state.operationsExpanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronUp,
                    contentDescription = if (state.operationsExpanded) "Collapse" else "Expand",
                    onClick = { onEvent(DriveEvent.ToggleOperations) },
                )
            }
            if (state.operationsExpanded) {
                ZillitDivider()
                Column(
                    modifier = Modifier.heightIn(max = LIST_MAX).verticalScroll(rememberScrollState()),
                ) {
                    uploads.forEach { upload ->
                        UploadRow(upload, onEvent)
                        ZillitDivider()
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // One row per upload state.
private fun UploadRow(upload: QueuedUpload, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val (label, tone) = when (val s = upload.state) {
        UploadState.Queued -> "Queued" to StatusTone.Neutral
        is UploadState.InProgress -> "Uploading" to StatusTone.Progress
        UploadState.Completing -> "Finishing" to StatusTone.Progress
        is UploadState.Done -> "Completed" to StatusTone.Done
        is UploadState.Failed -> (if (s.reason == "Cancelled") "Cancelled" else "Failed") to StatusTone.Rejected
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtBadge(upload.fileName.substringAfterLast('.', ""), size = ROW_BADGE)
            ZillitText(
                text = upload.fileName,
                style = ZillitTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = label, tone = tone)
            if (upload.isSettled) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = "Remove ${upload.fileName}",
                    onClick = { onEvent(DriveEvent.RemoveUpload(upload.id)) },
                )
            } else {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = "Cancel upload of ${upload.fileName}",
                    onClick = { onEvent(DriveEvent.CancelUpload(upload.id)) },
                    tint = colors.danger,
                )
            }
        }
        val failed = upload.state is UploadState.Failed
        ZillitProgressBar(
            fraction = if (failed) 1f else upload.fraction,
            fillColor = when {
                failed -> colors.danger
                upload.state is UploadState.Done -> colors.success
                else -> colors.accent
            },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            ZillitText(
                text = when (val s = upload.state) {
                    is UploadState.InProgress ->
                        "Part ${s.uploadedParts} of ${s.totalParts} · ${formatBytes(upload.sizeBytes)}"
                    is UploadState.Failed -> s.reason
                    else -> formatBytes(upload.sizeBytes)
                },
                style = ZillitTheme.typography.labelSmall,
                color = if (failed) colors.danger else colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "${(upload.fraction * PERCENT).toInt()}%",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

private val PANEL_WIDTH = 360.dp
private val PANEL_SHADOW = 12.dp
private val LIST_MAX = 320.dp
private val ROW_BADGE = 28.dp
private const val PERCENT = 100
