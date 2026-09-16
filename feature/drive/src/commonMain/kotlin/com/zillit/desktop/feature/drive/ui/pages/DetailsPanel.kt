package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.domain.relativeTime
import com.zillit.desktop.feature.drive.ui.DetailsState
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.LocalDriveNow

/**
 * The details panel — `FileDetailsPanel.jsx`: quick actions, metadata,
 * access (with Manage), tags, comments, versions and the activity timeline,
 * in the order the web lists them.
 *
 * Docked beside the listing rather than a drawer over it: inspecting a file
 * is something people do *while* browsing, and a drawer makes them close it
 * to click the next row.
 */
@Composable
internal fun DetailsPanel(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val item = state.details.item ?: return
    val colors = ZillitTheme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        DetailsHeader(item, state, onEvent)
        ZillitDivider()
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            QuickActions(item, state, onEvent)
            Metadata(item, state)
            AccessSection(item, state, onEvent)
            TagsSection(item, state.details, state.tags, state.viewer, onEvent)
            if (!item.isFolder) CommentsSection(state, onEvent)
            if (item.isEditableDocument) VersionsSection(item, state.details.versions, state.viewer, onEvent)
            ActivitySection(state.details)
        }
    }
}

@Composable
private fun DetailsHeader(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemGlyph(item, state, onEvent, size = HEADER_GLYPH)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = item.name, style = ZillitTheme.typography.titleSmall, maxLines = 2)
            ZillitText(
                text = if (item.isFolder) "Folder" else item.extension.uppercase().ifBlank { "File" } +
                    if (item.sizeBytes > 0) " · ${formatBytes(item.sizeBytes)}" else "",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        if (state.details.loading) ZillitSpinner(size = ZillitTheme.spacing.lg)
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close details",
            onClick = { onEvent(DriveEvent.ShowDetails(null)) },
        )
    }
}

/** Favourite, Copy link, Download, Edit/View, Request files — the web's quick actions plus the row's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val viewer = state.viewer
    val starred = state.isFavourite(item)
    val editable = viewer.may(DriveAction.Edit, item)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitButton(
            text = if (starred) "Favourited" else "Favourite",
            onClick = { onEvent(DriveEvent.ToggleFavourite(item.ref)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = if (starred) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
        )
        if (!item.isFolder && editable) {
            ZillitButton(
                text = "Copy link",
                onClick = { onEvent(DriveEvent.CopyLink(item)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Link,
            )
        }
        if (!item.isFolder && viewer.may(DriveAction.Download, item)) {
            ZillitButton(
                text = "Download",
                onClick = { onEvent(DriveEvent.Download(item)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
            )
        }
        if (item.isEditableDocument && !viewer.isViewOnly(item)) {
            ZillitButton(
                text = if (editable) "Edit" else "View",
                onClick = { onEvent(DriveEvent.OpenInEditor(item, editable)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
        if (item.isFolder && state.canCreateHere) {
            ZillitButton(
                text = "Request files",
                onClick = { onEvent(DriveEvent.OpenFileRequests(item)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Inbox,
            )
        }
    }
}

@Composable
private fun ColumnScope.Metadata(item: DriveItem, state: DriveUiState) {
    val now = LocalDriveNow.current()
    SheetSection {
        DetailRow("Type") {
            ZillitStatusPill(
                label = if (item.isFolder) "Folder" else item.extension.uppercase().ifBlank { "File" },
                tone = if (item.isFolder) StatusTone.Progress else StatusTone.Neutral,
            )
        }
        if (!item.isFolder && item.sizeBytes > 0) DetailRow("Size") { Value(formatBytes(item.sizeBytes)) }
        if (item.isFolder && item.itemCount != null) {
            DetailRow("Contains") { Value("${item.itemCount} file${if (item.itemCount == 1) "" else "s"}") }
        }
        item.createdAt?.let { stamp ->
            DetailRow("Created") {
                ZillitTooltip(text = EpochDate.dateTime(stamp)) { Value(stampLabel(stamp, now)) }
            }
        }
        item.updatedAt?.takeIf { it != item.createdAt }?.let { stamp ->
            DetailRow("Modified") {
                ZillitTooltip(text = EpochDate.dateTime(stamp)) { Value(stampLabel(stamp, now)) }
            }
        }
        DetailRow("Owner") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(icon = ZillitIcons.User, tint = ZillitTheme.colors.textMuted, size = ZillitTheme.spacing.md)
                Value(if (state.viewer.owns(item)) "You" else item.uploadedByName.ifBlank { "Unknown" })
            }
        }
        if (item.description.isNotBlank()) DetailRow("Description") { Value(item.description, lines = 4) }
    }
}

@Composable
private fun Value(text: String, lines: Int = 2) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, maxLines = lines)
}

@Composable
private fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Box(Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun SectionTitle(text: String, count: Int = 0, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = if (count > 0) "$text ($count)" else text,
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Who can reach this, and Manage when the viewer may change it. */
@Composable
private fun ColumnScope.AccessSection(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val access = state.details.access
    ZillitDivider()
    SectionTitle("Access", access.size) {
        if (state.viewer.may(DriveAction.Share, item) && state.canCreateHere) {
            ZillitButton(
                text = "Manage",
                onClick = { onEvent(DriveEvent.OpenShare(item)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
    }
    if (access.isEmpty()) {
        ZillitText(
            // Not "nobody" — an item with no explicit grants is reachable by
            // admins and by whoever inherits from a parent folder.
            text = "No access records. Administrators, and anyone with access to a parent folder, can still reach it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    access.forEach { entry -> AccessRow(entry, item.isFolder, state) }
}

@Composable
private fun AccessRow(entry: DriveAccessEntry, forFolder: Boolean, state: DriveUiState) {
    val name = entry.userName.ifBlank { state.crew.firstOrNull { it.id == entry.userId }?.name ?: "Unknown" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitAvatar(name = name, size = ACCESS_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = name, style = ZillitTheme.typography.label, maxLines = 1)
            if (entry.designation.isNotBlank()) {
                ZillitText(
                    text = entry.designation,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (forFolder) {
            ZillitStatusPill(
                label = entry.role.label,
                tone = when (entry.role) {
                    com.zillit.desktop.feature.drive.domain.DriveRole.Owner -> StatusTone.Rejected
                    com.zillit.desktop.feature.drive.domain.DriveRole.Editor -> StatusTone.Progress
                    com.zillit.desktop.feature.drive.domain.DriveRole.Viewer -> StatusTone.Ready
                },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                if (entry.permissions.canView) ZillitStatusPill(label = "View", tone = StatusTone.Ready)
                if (entry.permissions.canEdit) ZillitStatusPill(label = "Edit", tone = StatusTone.Progress)
                if (entry.permissions.canDownload) ZillitStatusPill(label = "Download", tone = StatusTone.Pending)
            }
        }
    }
}

/**
 * The item's tags, and the two ways to add one — a select of the project's
 * unused tags, and a new name. Controls appear only for someone who may edit
 * the item, as the web gates them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod") // The chips, then the two ways to add one.
private fun ColumnScope.TagsSection(
    item: DriveItem,
    details: DetailsState,
    all: List<DriveTag>,
    viewer: DriveViewer,
    onEvent: (DriveEvent) -> Unit,
) {
    ZillitDivider()
    SectionTitle("Tags", details.tags.size)
    val mayTag = viewer.may(DriveAction.Edit, item)
    if (details.tags.isEmpty()) {
        ZillitText(
            text = "No tags assigned",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            details.tags.forEach { tag ->
                TagChip(
                    tag,
                    onRemove = { onEvent(DriveEvent.RemoveTag(tag.id)) }.takeIf { mayTag && !details.tagsBusy },
                )
            }
        }
    }
    if (!mayTag) return
    val available = details.unapplied(all)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val placeholder = DriveTag(id = "", name = if (available.isEmpty()) "No tags available" else "Add a tag…")
        ZillitSelect(
            value = placeholder,
            options = listOf(placeholder) + available,
            onSelect = { if (it.id.isNotBlank()) onEvent(DriveEvent.AssignTag(it.id)) },
            label = { it.name },
            enabled = available.isNotEmpty() && !details.tagsBusy,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = details.tagDraft,
            onValueChange = { onEvent(DriveEvent.TagDraft(it)) },
            placeholder = "New tag name",
            onImeAction = { onEvent(DriveEvent.CreateAndAssignTag) },
            imeAction = ImeAction.Done,
            maxLength = TAG_MAX,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Create",
            onClick = { onEvent(DriveEvent.CreateAndAssignTag) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = details.tagDraft.isNotBlank() && !details.tagsBusy,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

/** One applied tag, in its colour. The remove control is absent, not disabled, for a reader. */
@Composable
private fun TagChip(tag: DriveTag, onRemove: (() -> Unit)?) {
    val tint = parseHex(tag.color) ?: ZillitTheme.colors.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(tint.copy(alpha = TAG_WASH))
            .padding(
                start = ZillitTheme.spacing.sm,
                end = if (onRemove == null) ZillitTheme.spacing.sm else ZillitTheme.spacing.xxs,
                top = ZillitTheme.spacing.xxs,
                bottom = ZillitTheme.spacing.xxs,
            ),
    ) {
        Box(Modifier.size(TAG_DOT).clip(ZillitTheme.shapes.pill).background(tint))
        ZillitText(text = tag.name, style = ZillitTheme.typography.labelSmall)
        if (onRemove != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Remove tag ${tag.name}",
                onClick = onRemove,
                size = TAG_REMOVE,
            )
        }
    }
}

/** The thread — add, and edit or delete your own (`handleUpdateComment`, `handleDeleteComment`). */
@Composable
private fun ColumnScope.CommentsSection(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val details = state.details
    ZillitDivider()
    SectionTitle("Comments", details.comments.size)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitTextField(
            value = details.commentDraft,
            onValueChange = { onEvent(DriveEvent.CommentDraft(it)) },
            placeholder = "Add a comment…",
            onImeAction = { onEvent(DriveEvent.PostComment) },
            imeAction = ImeAction.Send,
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.Send,
            contentDescription = "Post comment",
            onClick = { onEvent(DriveEvent.PostComment) },
            enabled = details.commentDraft.isNotBlank(),
            tint = ZillitTheme.colors.accent,
        )
    }
    if (details.comments.isEmpty()) {
        ZillitText(
            text = "No comments yet",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
    details.comments.forEach { comment -> CommentRow(comment, state, onEvent) }
}

@Composable
@Suppress("LongMethod") // One comment: its header, and the text or its editor.
private fun CommentRow(comment: DriveComment, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val details = state.details
    val mine = state.viewer.userId.isNotBlank() && comment.authorId == state.viewer.userId
    val now = LocalDriveNow.current()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = comment.authorName, size = ACCESS_AVATAR)
            ZillitText(
                text = comment.authorName,
                style = ZillitTheme.typography.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(
                text = comment.createdAt?.let { stampLabel(it, now) }.orEmpty() +
                    if (comment.edited) " (edited)" else "",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            if (mine && details.editingCommentId != comment.id) {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Edit comment",
                    onClick = { onEvent(DriveEvent.StartEditComment(comment.id, comment.text)) },
                    size = COMMENT_ACTION,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete comment",
                    onClick = { onEvent(DriveEvent.DeleteComment(comment.id)) },
                    tint = colors.danger,
                    size = COMMENT_ACTION,
                )
            }
        }
        if (details.editingCommentId == comment.id) {
            ZillitTextField(
                value = details.editingText,
                onValueChange = { onEvent(DriveEvent.EditCommentText(it)) },
                singleLine = false,
                onImeAction = { onEvent(DriveEvent.SaveComment) },
                imeAction = ImeAction.Done,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitButton(
                    text = "Save",
                    onClick = { onEvent(DriveEvent.SaveComment) },
                    size = ButtonSize.Small,
                    enabled = details.editingText.isNotBlank(),
                )
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(DriveEvent.CancelEditComment) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        } else {
            ZillitText(
                text = comment.text,
                style = ZillitTheme.typography.bodySmall,
                modifier = Modifier.padding(start = ACCESS_AVATAR + ZillitTheme.spacing.sm),
            )
        }
    }
}

/** Only for documents the editor can rewrite (ZL-18509). */
@Composable
private fun ColumnScope.VersionsSection(
    item: DriveItem,
    versions: List<DriveVersion>,
    viewer: DriveViewer,
    onEvent: (DriveEvent) -> Unit,
) {
    ZillitDivider()
    SectionTitle("Versions", versions.size)
    if (versions.isEmpty()) {
        ZillitText(
            text = "No previous versions",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    val now = LocalDriveNow.current()
    versions.forEach { version ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(text = "v${version.versionNumber}", style = ZillitTheme.typography.label)
                    ZillitText(
                        text = version.createdAt?.let { stampLabel(it, now) }.orEmpty(),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                ZillitText(
                    text = "${version.fileName.ifBlank { item.name }} · ${formatBytes(version.sizeBytes)}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
            if (viewer.may(DriveAction.Download, item)) {
                ZillitTooltip(text = "Download this version") {
                    ZillitIconButton(
                        icon = ZillitIcons.Download,
                        contentDescription = "Download version ${version.versionNumber}",
                        onClick = { onEvent(DriveEvent.DownloadVersion(item, version.id)) },
                    )
                }
            }
            if (viewer.may(DriveAction.Edit, item)) {
                ZillitTooltip(text = "Restore this version") {
                    ZillitIconButton(
                        icon = ZillitIcons.Reload,
                        contentDescription = "Restore version ${version.versionNumber}",
                        onClick = { onEvent(DriveEvent.RequestRestoreVersion(item.id, version.id)) },
                    )
                }
            }
        }
    }
}

/** The item's own trail, as a coloured timeline — green for created, red for deleted, blue otherwise. */
@Composable
private fun ColumnScope.ActivitySection(details: DetailsState) {
    ZillitDivider()
    SectionTitle("Activity")
    if (details.activity.isEmpty()) {
        ZillitText(
            text = "No activity yet",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    val now = LocalDriveNow.current()
    details.activity.forEach { entry -> TimelineRow(entry, now) }
}

@Composable
private fun TimelineRow(entry: DriveActivity, now: Long) {
    val colors = ZillitTheme.colors
    val tint = when {
        entry.action.contains("deleted") -> colors.danger
        entry.action.contains("created") -> colors.success
        else -> colors.info
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Top) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        ) {
            Box(Modifier.size(TIMELINE_DOT).clip(ZillitTheme.shapes.pill).background(tint))
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = entry.label, style = ZillitTheme.typography.label)
            ZillitText(
                text = entry.displayName + " · " +
                    (entry.at?.let { if (now > 0) relativeTime(it, now) else EpochDate.dateTime(it) } ?: "—"),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

private val HEADER_GLYPH = 40.dp
private val LABEL_WIDTH = 92.dp
private val ACCESS_AVATAR = 26.dp
private val TAG_DOT = 8.dp
private val TAG_REMOVE = 18.dp
private val COMMENT_ACTION = 22.dp
private val TIMELINE_DOT = 8.dp
private const val TAG_WASH = 0.18f
private const val TAG_MAX = 50
