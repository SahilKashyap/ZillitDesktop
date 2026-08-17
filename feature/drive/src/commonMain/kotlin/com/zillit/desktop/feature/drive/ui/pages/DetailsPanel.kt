package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DetailsState
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState

/**
 * The docked details panel.
 *
 * Metadata, who can reach it, its version history, its comments and its
 * activity — the five things people open a file's details to answer, in the
 * order they ask them.
 *
 * Docked rather than modal on purpose: inspecting a file is something people do
 * *while* browsing, and a modal makes them close it to click the next row.
 */
@Composable
fun DetailsPanel(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val item = state.details.item ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.surface)
            .zillitVerticalScroll()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        DetailsHeader(item, onEvent)
        if (state.details.loading) ZillitSpinner()

        DetailsActions(item, state.viewer, onEvent)
        ZillitDivider()

        DetailsMetadata(item)
        ZillitDivider()

        DetailsAccess(state.details.access)

        DetailsVersions(item, state.details.versions, state.viewer, onEvent)
        DetailsComments(item, state.details, onEvent)
        DetailsActivity(state.details.activity)
    }
}

@Composable
private fun DetailsHeader(item: DriveItem, onEvent: (DriveEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!item.isFolder) ZillitFileBadge(fileName = item.name)
        ZillitText(
            text = item.name,
            style = ZillitTheme.typography.titleSmall,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close details",
            onClick = { onEvent(DriveEvent.ShowDetails(null)) },
        )
    }
}

@Composable
private fun DetailsActions(
    item: DriveItem,
    viewer: DriveViewer,
    onEvent: (DriveEvent) -> Unit,
) {
    val editable = viewer.may(DriveAction.Edit, item)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (viewer.may(DriveAction.Download, item) && !item.isFolder) {
            ZillitButton(
                text = "Download",
                onClick = { onEvent(DriveEvent.Download(item)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
            )
        }
        if (viewer.may(DriveAction.Share, item) && !item.isFolder) {
            ZillitButton(
                text = "Share link",
                onClick = { onEvent(DriveEvent.ShareLink(item)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        if (item.isEditableDocument) {
            ZillitButton(
                // Named for what it will actually do: opening an editor that
                // turns out to be read-only reads as a broken save.
                text = if (editable) "Edit" else "View",
                onClick = { onEvent(DriveEvent.OpenInEditor(item, editable)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
    }
}

@Composable
private fun ColumnScope.DetailsMetadata(item: DriveItem) {
    ZillitSectionLabel("Details")
    DetailRow(
        label = "Type",
        value = if (item.isFolder) "Folder" else item.extension.uppercase().ifBlank { "File" },
    )
    if (!item.isFolder) DetailRow("Size", formatBytes(item.sizeBytes))
    DetailRow("Uploaded by", item.uploadedByName.ifBlank { "—" })
    DetailRow("Created", EpochDate.dateTime(item.createdAt).ifBlank { "—" })
    DetailRow("Modified", EpochDate.dateTime(item.updatedAt).ifBlank { "—" })
    if (item.description.isNotBlank()) DetailRow("Description", item.description)
}

@Composable
private fun ColumnScope.DetailsAccess(access: List<DriveAccessEntry>) {
    ZillitSectionLabel("Who can reach this")
    if (access.isEmpty()) {
        ZillitText(
            // Not "nobody" — an item with no explicit grants is reachable by
            // admins and by whoever inherits from a parent folder, and saying
            // "nobody" would be plainly wrong.
            text = "No explicit access has been granted. Administrators, and anyone with " +
                "access to a parent folder, can still reach it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    access.forEach { entry ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = entry.userName.ifBlank { entry.userId })
            ZillitText(
                text = entry.userName.ifBlank { entry.userId },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = entry.role.label, tone = StatusTone.Neutral)
        }
    }
}

@Composable
private fun ColumnScope.DetailsVersions(
    item: DriveItem,
    versions: List<DriveVersion>,
    viewer: DriveViewer,
    onEvent: (DriveEvent) -> Unit,
) {
    if (versions.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel("Versions")
    versions.forEach { version ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = "v${version.versionNumber}", maxLines = 1)
                ZillitText(
                    text = "${EpochDate.dateTime(version.createdAt)} · " +
                        formatBytes(version.sizeBytes),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
            if (viewer.may(DriveAction.Edit, item)) {
                ZillitButton(
                    text = "Restore",
                    onClick = { onEvent(DriveEvent.RestoreVersion(item.id, version.id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DetailsComments(
    item: DriveItem,
    details: DetailsState,
    onEvent: (DriveEvent) -> Unit,
) {
    // Folders carry no comments — the server has no route for them, and an
    // empty comment box on a folder invites a comment that cannot be saved.
    if (item.isFolder) return
    ZillitDivider()
    ZillitSectionLabel("Comments")
    details.comments.forEach { comment -> CommentRow(comment) }

    ZillitTextField(
        value = details.commentDraft,
        onValueChange = { onEvent(DriveEvent.CommentDraft(it)) },
        placeholder = "Add a comment",
        onImeAction = { onEvent(DriveEvent.PostComment) },
        imeAction = ImeAction.Send,
    )
    ZillitButton(
        text = "Comment",
        onClick = { onEvent(DriveEvent.PostComment) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = details.commentDraft.isNotBlank(),
    )
}

@Composable
private fun CommentRow(comment: DriveComment) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = comment.authorName)
            ZillitText(
                text = comment.authorName,
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = EpochDate.dateTime(comment.createdAt),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(text = comment.text, style = ZillitTheme.typography.bodySmall)
    }
}

@Composable
private fun ColumnScope.DetailsActivity(
    activity: List<com.zillit.desktop.feature.drive.domain.DriveActivity>,
) {
    if (activity.isEmpty()) return
    ZillitDivider()
    ZillitSectionLabel("Activity")
    // Enough to see what just happened; the Activity page has the rest.
    activity.take(ACTIVITY_PREVIEW).forEach { entry ->
        ZillitText(
            text = "${entry.displayName} ${entry.label.lowercase()} · ${EpochDate.dateTime(entry.at)}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall, maxLines = 2)
    }
}

private const val ACTIVITY_PREVIEW = 8
