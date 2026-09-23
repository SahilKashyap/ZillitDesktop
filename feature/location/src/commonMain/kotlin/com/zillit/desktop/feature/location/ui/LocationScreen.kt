// Screens branch on the shortlist and the grouping; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.location.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.LocationFolder
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationUnread
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The location library: shortlist tabs, grouping, the folder grid, and the dialogs. */
@Composable
fun LocationScreen(
    state: LocationUiState,
    onEvent: (LocationEvent) -> Unit,
    /** A record's thumbnail (or full image), decoded; null shows a placeholder. */
    loadImage: suspend (MediaAttachment, preview: Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitPageHeader(
                title = str(S.location),
                description = str(S.desktop_location_page_description),
                actions = {
                    ZillitButton(text = str(S.refresh_text), onClick = { onEvent(LocationEvent.Refresh) },
                        variant = ButtonVariant.Tertiary, loading = state.loading)
                    ZillitButton(text = str(S.add_more_link), onClick = { onEvent(LocationEvent.NewLink) },
                        variant = ButtonVariant.Secondary)
                    ZillitButton(text = str(S.desktop_location_upload_photo_or_video),
                        onClick = { onEvent(LocationEvent.PickFile) }, leadingIcon = ZillitIcons.Upload)
                },
            )
            if (state.viewer.isBlocked) ZillitNotice(text = str(S.desktop_location_no_access))
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(text = str(S.sync_action_dismiss),
                            onClick = { onEvent(LocationEvent.DismissError) },
                            variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                    },
                )
            }
            ZillitTabStrip(
                tabs = LocationStatus.entries.map { ZillitTab(it.wire, it.label, count = state.unread.status(it)) },
                activeId = state.status.wire,
                onSelect = { id -> onEvent(LocationEvent.SelectStatus(LocationStatus.fromWire(id))) },
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(text = str(S.group_by), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                GroupBy.entries.forEach { by ->
                    if (by != GroupBy.EpisodeNo || state.viewer.isTelevision || state.groupBy == by) {
                        ZillitChoiceChip(label = by.label, selected = state.groupBy == by,
                            onClick = { onEvent(LocationEvent.SelectGroupBy(by)) })
                    }
                }
                ZillitSearchField(
                    value = state.query,
                    onValueChange = { onEvent(LocationEvent.Search(it)) },
                    placeholder = str(S.desktop_location_search_placeholder),
                    modifier = Modifier.width(SEARCH_WIDTH),
                )
            }
            when {
                state.loading && state.info.isEmpty() -> Box(Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.folders.isEmpty() -> ZillitText(
                    text = if (state.query.isBlank()) {
                        str(S.desktop_location_nothing_in_status_yet, state.status.label)
                    } else {
                        str(S.desktop_location_no_folder_matches)
                    },
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                else -> FolderGrid(state, onEvent)
            }
        }
        when {
            state.gallery != null -> GalleryDialog(state, onEvent, loadImage, resolveUser)
            state.browsing != null -> PicksDialog(state.browsing, state.unread, state.status, onEvent)
        }
        state.editor?.let { EditorDialog(state, onEvent) }
        state.viewing?.let { ViewDialog(state, it, onEvent, loadImage, resolveUser) }
        state.confirmDelete?.let { ids ->
            ZillitDialogShell(
                title = str(S.desktop_location_delete_records),
                onDismiss = { onEvent(LocationEvent.CancelDelete) },
                visible = true,
                actions = {
                    ZillitButton(text = str(S.cancel), onClick = { onEvent(LocationEvent.CancelDelete) },
                        variant = ButtonVariant.Tertiary)
                    ZillitButton(text = str(S.delete), onClick = { onEvent(LocationEvent.ConfirmDelete) },
                        variant = ButtonVariant.Danger, loading = state.busy)
                },
            ) {
                ZillitText(text = str(S.desktop_location_delete_records_confirm, ids.size, state.status.label),
                    style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FolderGrid(state: LocationUiState, onEvent: (LocationEvent) -> Unit) {
    val colors = ZillitTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.folders, key = { it.key }) { folder ->
            FolderTile(state.groupBy, folder, state.unread.folder(state.status, state.groupBy, folder), colors) {
                onEvent(LocationEvent.OpenFolder(folder))
            }
        }
    }
}

@Composable
private fun FolderTile(
    by: GroupBy,
    folder: LocationFolder,
    unread: Int,
    colors: com.zillit.desktop.core.designsystem.ZillitColors,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .background(colors.surface, ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = when (by) {
                    GroupBy.LocationName -> folder.title
                    GroupBy.SceneNo -> str(S.desktop_scene_numbered, folder.title)
                    GroupBy.EpisodeNo -> str(S.desktop_episode_numbered, folder.title)
                },
                style = ZillitTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // The folder's unread, as the web's folder cards carry it.
            ZillitBadge(count = unread)
        }
        val facts = buildList {
            if (by != GroupBy.LocationName && folder.locations.isNotEmpty()) add(folder.locations.joinToString())
            if (by != GroupBy.SceneNo && folder.sceneNumbers.any { it.isNotBlank() }) {
                add(str(S.desktop_scenes_list, folder.sceneNumbers.filter { it.isNotBlank() }.joinToString()))
            }
            if (by != GroupBy.EpisodeNo && folder.episodes.isNotEmpty()) {
                add(str(S.desktop_episode_abbrev_list, folder.episodes.joinToString()))
            }
            if (folder.cities.isNotEmpty()) add(folder.cities.joinToString())
        }
        if (facts.isNotEmpty()) {
            ZillitText(text = facts.joinToString(" · "), style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary)
        }
        ZillitText(text = str(S.docusign_auto_refreshed_at, LocationDates.date(folder.lastUpdateMs)),
            style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
}

// Gallery --------------------------------------------------------------------

@Composable
private fun GalleryDialog(
    state: LocationUiState,
    onEvent: (LocationEvent) -> Unit,
    loadImage: suspend (MediaAttachment, Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
) {
    val open = state.gallery ?: return
    val colors = ZillitTheme.colors
    val moves = state.status.movesTo
    ZillitDialogShell(
        title = open.pick.title,
        subtitle = str(S.desktop_location_records_count_in_status, open.records.size, state.status.label),
        onDismiss = { onEvent(LocationEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = GALLERY_WIDTH,
        actions = {
            if (open.selecting) {
                val n = open.selected.size
                moves.forEach { to ->
                    ZillitButton(text = str(S.drive_move_to_destination, to.label),
                        onClick = { onEvent(LocationEvent.MoveSelected(to)) },
                        variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = n > 0, loading = state
                            .busy)
                }
                ZillitButton(text = str(S.pdf), onClick = { onEvent(LocationEvent.PdfSelected) },
                    variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = n > 0)
                ZillitButton(text = str(S.delete), onClick = { onEvent(LocationEvent.DeleteSelected) },
                    variant = ButtonVariant.Danger, size = ButtonSize.Small, enabled = n > 0)
            }
            ZillitButton(text = str(if (open.selecting) S.ah_done else S.select),
                onClick = { onEvent(LocationEvent.ToggleSelecting) }, variant = ButtonVariant
                    .Tertiary, size = ButtonSize.Small)
            ZillitButton(text = str(S.desktop_location_upload_here), onClick = { onEvent(LocationEvent.PickFile) },
                variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            if (state.browsing != null) {
                ZillitButton(text = str(S.back), onClick = { onEvent(LocationEvent.Back) },
                    variant = ButtonVariant.Tertiary)
            }
            ZillitButton(text = str(S.close), onClick = { onEvent(LocationEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        if (state.loading && open.records.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center) { ZillitSpinner() }
            return@ZillitDialogShell
        }
        if (open.records.isEmpty()) {
            ZillitText(text = str(S.desktop_location_no_records_in_folder), style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted)
            return@ZillitDialogShell
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TILE_MIN_WIDTH),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            items(open.records, key = { it.id }) { record ->
                MediaTile(record, open.selecting, record.id in open.selected, state.unread.record(record.id),
                    loadImage, resolveUser, onEvent)
            }
            if (!open.exhausted) {
                item {
                    Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md), contentAlignment = Alignment.Center) {
                        ZillitButton(text = str(S.desktop_load_older), onClick = { onEvent(LocationEvent.LoadMore) },
                            variant = ButtonVariant.Tertiary, size = ButtonSize.Small, loading = open.loadingMore)
                    }
                }
            }
        }
    }
}

/**
 * The level between a folder and its galleries — the web's scene / location
 * list modal — shown only when the folder holds more than one gallery.
 */
@Composable
private fun PicksDialog(open: OpenFolder, unread: LocationUnread, status: LocationStatus,
    onEvent: (LocationEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = open.title,
        subtitle = when (open.by) {
            GroupBy.LocationName -> str(S.desktop_location_pick_scene)
            GroupBy.SceneNo -> str(S.desktop_pick_location)
            GroupBy.EpisodeNo -> str(S.desktop_location_pick_location_and_scene)
        },
        onDismiss = { onEvent(LocationEvent.CloseFolder) },
        visible = true,
        width = GALLERY_WIDTH,
        actions = {
            ZillitButton(text = str(S.desktop_location_upload_here), onClick = { onEvent(LocationEvent.PickFile) },
                variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            ZillitButton(text = str(S.close), onClick = { onEvent(LocationEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            open.picks.forEach { pick ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, ZillitTheme.shapes.medium)
                        .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                        .clickable { onEvent(LocationEvent.OpenPick(pick)) }
                        .padding(ZillitTheme.spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = when (open.by) {
                            GroupBy.LocationName ->
                                str(S.desktop_scene_numbered, pick.scene.ifBlank { str(S.not_assigned) })
                            GroupBy.SceneNo -> pick.location.ifBlank { str(S.not_assigned) }
                            GroupBy.EpisodeNo -> pick.location.ifBlank { str(S.not_assigned) } +
                                " · " + str(S.desktop_scene_abbrev, pick.scene.ifBlank { str(S.not_assigned) })
                        },
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitBadge(count = unread.pick(status, pick))
                }
            }
        }
    }
}

@Composable
private fun MediaTile(
    record: LocationMedia,
    selecting: Boolean,
    selected: Boolean,
    unread: Int,
    loadImage: suspend (MediaAttachment, Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
    onEvent: (LocationEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .background(colors.surface, ZillitTheme.shapes.medium)
            .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .clickable {
                onEvent(if (selecting) LocationEvent.ToggleSelect(record.id) else LocationEvent.View(record))
            },
    ) {
        Thumbnail(record, loadImage, Modifier.fillMaxWidth().aspectRatio(THUMB_RATIO))
        Column(Modifier.padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (selecting) ZillitCheckbox(checked = selected,
                    onCheckedChange = { onEvent(LocationEvent.ToggleSelect(record.id)) })
                ZillitText(
                    text = record.location.ifBlank { str(S.not_assigned) },
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (record.isLink) ZillitStatusPill(label = str(S.link), tone = StatusTone.Neutral)
                if (record.attachment?.isVideo == true) {
                    ZillitStatusPill(label = str(S.video), tone = StatusTone.Neutral)
                }
                // Unread comments on this record — the web's image-list badge.
                ZillitBadge(count = unread)
            }
            ZillitText(
                text = listOf(
                    record.sceneNumber.takeIf { it.isNotBlank() }?.let { str(S.desktop_scene_abbrev, it) },
                    record.episodes.takeIf { it.isNotEmpty() }
                        ?.let { str(S.desktop_episode_abbrev_list, it.joinToString()) },
                    record.city.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" · ").ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            ZillitText(
                text = (resolveUser(record.uploadedBy) ?: str(S.desktop_unknown)) +
                    " · ${LocationDates.date(record.createdMs)}" +
                    if (record.edited) " · " + str(S.desktop_edited_marker) else "",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun Thumbnail(
    record: LocationMedia,
    loadImage: suspend (MediaAttachment, Boolean) -> ImageBitmap?,
    modifier: Modifier,
    preview: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val visual = record.visual
    var bitmap by remember(record.id, preview) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(record.id, preview) {
        val renderable = visual != null && (visual.isImage || visual.isVideo)
        if (visual != null && (renderable || record.isLink)) bitmap = loadImage(visual, preview)
    }
    Box(modifier.background(colors.surfaceSunken, ZillitTheme.shapes.medium), contentAlignment = Alignment.Center) {
        val ready = bitmap
        if (ready != null) {
            Image(bitmap = ready, contentDescription = record.location, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            ZillitText(
                text = when {
                    record.isLink -> str(S.link)
                    visual?.isVideo == true -> str(S.video)
                    visual == null -> str(S.desktop_no_media)
                    else -> "…"
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

// Viewer ---------------------------------------------------------------------

@Composable
private fun ViewDialog(
    state: LocationUiState,
    record: LocationMedia,
    onEvent: (LocationEvent) -> Unit,
    loadImage: suspend (MediaAttachment, Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = record.location.ifBlank { str(S.not_assigned) },
        subtitle = listOf(record.sceneNumber.takeIf { it.isNotBlank() }?.let { str(S.desktop_scene_numbered, it) },
            record.city.takeIf { it.isNotBlank() })
            .filterNotNull().joinToString(" · ").ifBlank { null },
        onDismiss = { onEvent(LocationEvent.CloseView) },
        visible = true,
        width = GALLERY_WIDTH,
        actions = {
            ZillitButton(text = str(S.edit), onClick = { onEvent(LocationEvent.Edit(record)) },
                variant = ButtonVariant.Secondary)
            if (record.attachment != null) {
                ZillitButton(text = str(S.download), onClick = { onEvent(LocationEvent.Download(record)) },
                    variant = ButtonVariant.Secondary)
            }
            ZillitButton(text = str(S.close), onClick = { onEvent(LocationEvent.CloseView) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Thumbnail(record, loadImage, Modifier.fillMaxWidth().height(VIEW_HEIGHT), preview = false)
            if (record.isLink) ZillitText(text = record.link, style = ZillitTheme.typography.bodyMedium,
                color = colors.accentText)
            if (record.description.isNotBlank()) ZillitText(text = record.description,
                style = ZillitTheme.typography.bodyMedium)
            val details = listOf(
                str(S.address) to record.address,
                str(S.contact) to listOf(record.contactName, record.countryCode,
                    record.phone).filter { it.isNotBlank() }.joinToString(" "),
                str(S.email) to record.email,
                str(S.desktop_episodes) to record.episodes.joinToString(),
                str(S.txt_uploaded_by) to (resolveUser(record.uploadedBy) ?: record.uploadedBy),
                str(S.sides_uploaded) to LocationDates.date(record.createdMs),
            ).filter { it.second.isNotBlank() }
            details.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = colors.textMuted,
                        modifier = Modifier.width(LABEL_WIDTH))
                    ZillitText(text = value, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary)
                }
            }
            ZillitDivider()
            Discussion(state, onEvent, resolveUser)
        }
    }
}

/**
 * The record's discussion.
 *
 * The phones and the web have carried this thread for years — it is where a
 * location manager answers "can we park a truck there?" against the picture
 * itself. This client had no thread at all, so the conversation happened
 * somewhere the record could not see.
 */
@Composable
private fun Discussion(
    state: LocationUiState,
    onEvent: (LocationEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    ZillitSectionLabel(text = str(S.discussion_text), modifier = Modifier.fillMaxWidth())
    when {
        state.discussionLoading && state.discussion.isEmpty() -> ZillitText(
            text = str(S.ah_loading),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        state.discussion.isEmpty() -> ZillitText(
            text = str(S.desktop_location_discussion_empty),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        else -> state.discussion.forEach { message ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = if (message.isMine) str(S.you) else resolveUser(message.senderId) ?: str(S.history_someone),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                ZillitText(
                    // A line whose body would not decrypt keeps its place;
                    // saying so is better than an empty bubble.
                    text = message.body.ifBlank { str(S.desktop_message_could_not_be_read) },
                    style = ZillitTheme.typography.bodySmall,
                    color = if (message.body.isBlank()) colors.textMuted else colors.textPrimary,
                )
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitTextField(
            value = state.discussionDraft,
            onValueChange = { onEvent(LocationEvent.DiscussionDraftChanged(it)) },
            placeholder = str(S.desktop_location_discussion_placeholder),
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.send),
            onClick = { onEvent(LocationEvent.SendDiscussion) },
            enabled = state.discussionDraft.isNotBlank() && !state.discussionSending,
            loading = state.discussionSending,
        )
    }
}

// Editor ---------------------------------------------------------------------

@Composable
private fun EditorDialog(state: LocationUiState, onEvent: (LocationEvent) -> Unit) {
    val editor = state.editor ?: return
    val change = { updated: LocationEditor -> onEvent(LocationEvent.EditorChanged(updated)) }
    ZillitDialogShell(
        title = when {
            editor.id != null -> str(S.desktop_location_edit_title)
            editor.file != null ->
                str(if (editor.file.isVideo) S.desktop_location_add_video else S.desktop_location_add_photo)
            else -> str(S.add_more_link)
        },
        subtitle = editor.file?.name,
        onDismiss = { onEvent(LocationEvent.CancelEdit) },
        visible = true,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = { onEvent(LocationEvent.CancelEdit) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = str(S.save), onClick = { onEvent(LocationEvent.Save) }, loading = editor.saving)
        },
    ) {
        // The shell scrolls the body itself; a second scroller here is a crash.
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(value = editor.location, onValueChange = { change(editor.copy(location = it)) },
                label = str(S.location_name), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.sceneNumber, onValueChange = { change(editor.copy(sceneNumber = it)) },
                    label = str(S.txt_scene_number), modifier = Modifier.weight(1f))
                if (state.viewer.isTelevision) {
                    ZillitTextField(value = editor.episodes, onValueChange = { change(editor.copy(episodes = it)) },
                        label = str(S.desktop_episodes), placeholder = "1,2", modifier = Modifier.weight(1f))
                }
                ZillitTextField(value = editor.city, onValueChange = { change(editor.copy(city = it)) },
                    label = str(S.city), modifier = Modifier.weight(1f))
            }
            if (editor.id == null && editor.file == null) {
                ZillitTextField(value = editor.link, onValueChange = { change(editor.copy(link = it)) },
                    label = str(S.link), placeholder = "https://…", modifier = Modifier.fillMaxWidth())
            }
            // Picked on a map, stored as text. This tool's body is address and
            // nothing else — `putBase` in LocationWire.kt:88, the web's
            // `createModal` (`commonFunctionForFilmTools.js:296-311`) the same
            // — so the pick's coordinates are dropped rather than sent under
            // invented keys. (The 406-on-null-lat/lng trap belongs to
            // Transportation, not here.)
            ZillitLocationField(
                text = editor.address,
                onTextChange = { change(editor.copy(address = it)) },
                onPicked = { change(editor.copy(address = it.oneLine())) },
                label = str(S.address),
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitTextField(value = editor.description, onValueChange = { change(editor.copy(description = it)) },
                label = str(S.description), singleLine = false, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.contactName, onValueChange = { change(editor.copy(contactName = it)) },
                    label = str(S.contact_name), modifier = Modifier.weight(1f))
                ZillitTextField(value = editor.countryCode, onValueChange = { change(editor.copy(countryCode = it)) },
                    label = str(S.code), placeholder = "+44", modifier = Modifier.width(CODE_WIDTH))
                ZillitTextField(value = editor.phone, onValueChange = { change(editor.copy(phone = it)) },
                    label = str(S.phone), modifier = Modifier.weight(1f))
            }
            ZillitTextField(value = editor.email, onValueChange = { change(editor.copy(email = it)) },
                label = str(S.email), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The one line a picked place reads as: its name, then its address. */

private val SEARCH_WIDTH = 280.dp
private val FOLDER_MIN_WIDTH = 240.dp
private val TILE_MIN_WIDTH = 220.dp
private val GALLERY_WIDTH = 1100.dp
private val VIEW_HEIGHT = 480.dp
private val LABEL_WIDTH = 100.dp
private val CODE_WIDTH = 90.dp
private const val THUMB_RATIO = 4f / 3f
