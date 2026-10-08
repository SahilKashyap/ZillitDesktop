package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.UploadPlan
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.domain.treeRows
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.FolderUploadState

/**
 * Confirmation for a folder upload (the web's `FolderUploadModal`): the tree
 * that will be created, the totals, one date for every new folder, and the
 * files that will be skipped.
 */
@Suppress("LongMethod") // One dialog; splitting it separates each part from the plan it reads.
@Composable
internal fun FolderUploadDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val job = state.folderUpload
    ZillitDialogShell(
        title = str(S.drive_upload_folder),
        subtitle = str(S.desktop_docdist_folder_upload_subtitle),
        visible = job != null,
        onDismiss = { onEvent(DocDistEvent.CancelFolderUpload) },
        icon = ZillitIcons.Upload,
        width = DIALOG_WIDTH.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CancelFolderUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.upload),
                onClick = { onEvent(DocDistEvent.ConfirmFolderUpload) },
                enabled = job != null && job.plan.requestCount > 0,
                leadingIcon = ZillitIcons.Upload,
            )
        },
    ) {
        if (job == null) return@ZillitDialogShell
        ZillitText(
            text = summaryOf(job.plan) + "  ·  " + str(S.desktop_docdist_upload_into, job.parentLabel),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
        )
        // Mandatory: the library groups by folder date, so a folder created without one
        // would not sort into the listing.
        FieldLabel(str(S.desktop_docdist_date_new_folders))
        ZillitDateField(
            value = job.date,
            onValueChange = { onEvent(DocDistEvent.EditFolderUploadDate(it)) },
            today = state.today,
        )
        PlanTree(job.plan)
        if (job.rejected.isNotEmpty()) SkippedNotice(job)
    }
}

@Composable
private fun PlanTree(plan: UploadPlan) {
    val c = ZillitTheme.colors
    val rows = remember(plan) { plan.treeRows() }
    ZillitScrollColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = TREE_MAX_HEIGHT.dp)
            .clip(ZillitTheme.shapes.medium)
            .border(0.5.dp, c.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.xs),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Box(Modifier.width((row.depth * INDENT).dp))
                if (row.isFolder) FolderGlyph(size = ROW_GLYPH.dp) else FileGlyph(row.name, null, size = ROW_GLYPH.dp)
                ZillitText(
                    text = row.name,
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (!row.isFolder) {
                    ZillitText(
                        text = formatBytes(row.sizeBytes),
                        style = ZillitTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkippedNotice(job: FolderUploadState) {
    val c = ZillitTheme.colors
    val names = job.rejected.map { it.relativePath }
    Column(
        Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(c.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(
                if (names.size == 1) S.desktop_docdist_skipped_one else S.desktop_docdist_skipped_many,
                names.size,
            ),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.warning,
        )
        ZillitText(
            text = str(S.desktop_docdist_unsupported_note, SupportedUploads.LABEL),
            style = ZillitTheme.typography.bodySmall,
            color = c.warning,
        )
        names.take(SKIPPED_SHOWN).forEach {
            ZillitText(text = "• $it", style = ZillitTheme.typography.bodySmall, color = c.warning, maxLines = 1)
        }
        if (names.size > SKIPPED_SHOWN) {
            ZillitText(
                text = "• " + str(S.desktop_n_more, names.size - SKIPPED_SHOWN),
                style = ZillitTheme.typography.bodySmall,
                color = c.warning,
            )
        }
    }
}

/** "3 folders · 12 files · 4.2 MB". */
private fun summaryOf(plan: UploadPlan): String = listOfNotNull(
    str(
        if (plan.folderCount == 1) S.drive_count_folder_singular else S.drive_count_folder_plural,
        plan.folderCount,
    ).takeIf { plan.folderCount > 0 },
    str(if (plan.fileCount == 1) S.drive_count_file_singular else S.drive_count_file_plural, plan.fileCount),
    formatBytes(plan.bytes).takeIf { plan.bytes > 0 },
).joinToString(" · ")

private const val DIALOG_WIDTH = 620
private const val TREE_MAX_HEIGHT = 300
private const val INDENT = 18
private const val ROW_GLYPH = 22
private const val SKIPPED_SHOWN = 8
