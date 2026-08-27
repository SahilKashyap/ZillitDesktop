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
import com.zillit.desktop.feature.location.domain.MediaAttachment

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
                title = "Location",
                description = "Scouted places — photos, videos and links, filed by location, scene and episode.",
                actions = {
                    ZillitButton(text = "Refresh", onClick = { onEvent(LocationEvent.Refresh) },
                        variant = ButtonVariant.Tertiary, loading = state.loading)
                    ZillitButton(text = "Add link", onClick = { onEvent(LocationEvent.NewLink) },
                        variant = ButtonVariant.Secondary)
                    ZillitButton(text = "Upload photo or video", onClick = { onEvent(LocationEvent.PickFile) },
                        leadingIcon = ZillitIcons.Upload)
                },
            )
            if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Location tool.")
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(text = "Dismiss", onClick = { onEvent(LocationEvent.DismissError) },
                            variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                    },
                )
            }
            ZillitTabStrip(
                tabs = LocationStatus.entries.map { ZillitTab(it.wire, it.label) },
                activeId = state.status.wire,
                onSelect = { id -> onEvent(LocationEvent.SelectStatus(LocationStatus.fromWire(id))) },
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(text = "Group by", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                GroupBy.entries.forEach { by ->
                    if (by != GroupBy.EpisodeNo || state.viewer.isTelevision || state.groupBy == by) {
                        ZillitChoiceChip(label = by.label, selected = state.groupBy == by,
                            onClick = { onEvent(LocationEvent.SelectGroupBy(by)) })
                    }
                }
                ZillitSearchField(
                    value = state.query,
                    onValueChange = { onEvent(LocationEvent.Search(it)) },
                    placeholder = "Search location, scene, episode",
                    modifier = Modifier.width(SEARCH_WIDTH),
                )
            }
            when {
                state.loading && state.info.isEmpty() -> Box(Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.folders.isEmpty() -> ZillitText(
                    text = if (state.query.isBlank()) "Nothing in ${state.status.label} yet." else "No folder matches.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                else -> FolderGrid(state, onEvent)
            }
        }
        when {
            state.gallery != null -> GalleryDialog(state, onEvent, loadImage, resolveUser)
            state.browsing != null -> PicksDialog(state.browsing, onEvent)
        }
        state.editor?.let { EditorDialog(state, onEvent) }
        state.viewing?.let { ViewDialog(state, it, onEvent, loadImage, resolveUser) }
        state.confirmDelete?.let { ids ->
            ZillitDialogShell(
                title = "Delete records",
                onDismiss = { onEvent(LocationEvent.CancelDelete) },
                visible = true,
                actions = {
                    ZillitButton(text = "Cancel", onClick = { onEvent(LocationEvent.CancelDelete) },
                        variant = ButtonVariant.Tertiary)
                    ZillitButton(text = "Delete", onClick = { onEvent(LocationEvent.ConfirmDelete) },
                        variant = ButtonVariant.Danger, loading = state.busy)
                },
            ) {
                ZillitText(text = "Delete ${ids.size} record(s) from ${state.status.label}?",
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
        items(state.folders, key = { it.key }) { folder -> FolderTile(state.groupBy, folder,
            colors) { onEvent(LocationEvent.OpenFolder(folder)) } }
    }
}

@Composable
private fun FolderTile(
    by: GroupBy,
    folder: LocationFolder,
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
        ZillitText(
            text = when (by) {
                GroupBy.LocationName -> folder.title
                GroupBy.SceneNo -> "Scene ${folder.title}"
                GroupBy.EpisodeNo -> "Episode ${folder.title}"
            },
            style = ZillitTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        val facts = buildList {
            if (by != GroupBy.LocationName && folder.locations.isNotEmpty()) add(folder.locations.joinToString())
            if (by != GroupBy.SceneNo && folder.sceneNumbers.any { it.isNotBlank() }) add("Scenes " + folder
                .sceneNumbers.filter { it.isNotBlank() }.joinToString())
            if (by != GroupBy.EpisodeNo && folder.episodes.isNotEmpty()) add("Ep " + folder.episodes.joinToString())
            if (folder.cities.isNotEmpty()) add(folder.cities.joinToString())
        }
        if (facts.isNotEmpty()) {
            ZillitText(text = facts.joinToString(" · "), style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary)
        }
        ZillitText(text = "Updated ${LocationDates.date(folder.lastUpdateMs)}",
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
        subtitle = "${open.records.size} record(s) · ${state.status.label}",
        onDismiss = { onEvent(LocationEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = GALLERY_WIDTH,
        actions = {
            if (open.selecting) {
                val n = open.selected.size
                moves.forEach { to ->
                    ZillitButton(text = "Move to ${to.label}", onClick = { onEvent(LocationEvent.MoveSelected(to)) },
                        variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = n > 0, loading = state
                            .busy)
                }
                ZillitButton(text = "PDF", onClick = { onEvent(LocationEvent.PdfSelected) },
                    variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = n > 0)
                ZillitButton(text = "Delete", onClick = { onEvent(LocationEvent.DeleteSelected) },
                    variant = ButtonVariant.Danger, size = ButtonSize.Small, enabled = n > 0)
            }
            ZillitButton(text = if (open.selecting) "Done" else "Select",
                onClick = { onEvent(LocationEvent.ToggleSelecting) }, variant = ButtonVariant
                    .Tertiary, size = ButtonSize.Small)
            ZillitButton(text = "Upload here", onClick = { onEvent(LocationEvent.PickFile) },
                variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            if (state.browsing != null) {
                ZillitButton(text = "Back", onClick = { onEvent(LocationEvent.Back) }, variant = ButtonVariant.Tertiary)
            }
            ZillitButton(text = "Close", onClick = { onEvent(LocationEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        if (state.loading && open.records.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center) { ZillitSpinner() }
            return@ZillitDialogShell
        }
        if (open.records.isEmpty()) {
            ZillitText(text = "No records in this folder.", style = ZillitTheme.typography.bodyMedium,
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
                MediaTile(record, open.selecting, record.id in open.selected, loadImage, resolveUser, onEvent)
            }
            if (!open.exhausted) {
                item {
                    Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md), contentAlignment = Alignment.Center) {
                        ZillitButton(text = "Load older", onClick = { onEvent(LocationEvent.LoadMore) },
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
private fun PicksDialog(open: OpenFolder, onEvent: (LocationEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = open.title,
        subtitle = when (open.by) {
            GroupBy.LocationName -> "Pick a scene"
            GroupBy.SceneNo -> "Pick a location"
            GroupBy.EpisodeNo -> "Pick a location and scene"
        },
        onDismiss = { onEvent(LocationEvent.CloseFolder) },
        visible = true,
        width = GALLERY_WIDTH,
        actions = {
            ZillitButton(text = "Upload here", onClick = { onEvent(LocationEvent.PickFile) },
                variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            ZillitButton(text = "Close", onClick = { onEvent(LocationEvent.CloseFolder) },
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
                            GroupBy.LocationName -> "Scene " + pick.scene.ifBlank { "Not Assigned" }
                            GroupBy.SceneNo -> pick.location.ifBlank { "Not Assigned" }
                            GroupBy.EpisodeNo -> pick.location.ifBlank { "Not Assigned" } +
                                " · Sc " + pick.scene.ifBlank { "Not Assigned" }
                        },
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                    )
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
                    text = record.location.ifBlank { "Not Assigned" },
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (record.isLink) ZillitStatusPill(label = "Link", tone = StatusTone.Neutral)
                if (record.attachment?.isVideo == true) ZillitStatusPill(label = "Video", tone = StatusTone.Neutral)
            }
            ZillitText(
                text = listOf(
                    record.sceneNumber.takeIf { it.isNotBlank() }?.let { "Sc $it" },
                    record.episodes.takeIf { it.isNotEmpty() }?.let { "Ep ${it.joinToString()}" },
                    record.city.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" · ").ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            ZillitText(
                text = "${resolveUser(record.uploadedBy) ?: "Unknown"} · ${LocationDates.date(record.createdMs)}" +
                    if (record.edited) " · edited" else "",
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
                    record.isLink -> "Link"
                    visual?.isVideo == true -> "Video"
                    visual == null -> "No media"
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
        title = record.location.ifBlank { "Not Assigned" },
        subtitle = listOf(record.sceneNumber.takeIf { it.isNotBlank() }?.let { "Scene $it" },
            record.city.takeIf { it.isNotBlank() })
            .filterNotNull().joinToString(" · ").ifBlank { null },
        onDismiss = { onEvent(LocationEvent.CloseView) },
        visible = true,
        width = GALLERY_WIDTH,
        actions = {
            ZillitButton(text = "Edit", onClick = { onEvent(LocationEvent.Edit(record)) },
                variant = ButtonVariant.Secondary)
            if (record.attachment != null) {
                ZillitButton(text = "Download", onClick = { onEvent(LocationEvent.Download(record)) },
                    variant = ButtonVariant.Secondary)
            }
            ZillitButton(text = "Close", onClick = { onEvent(LocationEvent.CloseView) },
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
                "Address" to record.address,
                "Contact" to listOf(record.contactName, record.countryCode,
                    record.phone).filter { it.isNotBlank() }.joinToString(" "),
                "Email" to record.email,
                "Episodes" to record.episodes.joinToString(),
                "Uploaded by" to (resolveUser(record.uploadedBy) ?: record.uploadedBy),
                "Uploaded" to LocationDates.date(record.createdMs),
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
    ZillitSectionLabel(text = "Discussion", modifier = Modifier.fillMaxWidth())
    when {
        state.discussionLoading && state.discussion.isEmpty() -> ZillitText(
            text = "Loading…",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        state.discussion.isEmpty() -> ZillitText(
            text = "Nothing said about this one yet.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        else -> state.discussion.forEach { message ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = if (message.isMine) "You" else resolveUser(message.senderId) ?: "Someone",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                ZillitText(
                    // A line whose body would not decrypt keeps its place;
                    // saying so is better than an empty bubble.
                    text = message.body.ifBlank { "(could not be read)" },
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
            placeholder = "Say something about this location…",
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Send",
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
            editor.id != null -> "Edit location"
            editor.file != null -> "Add ${if (editor.file.isVideo) "video" else "photo"}"
            else -> "Add link"
        },
        subtitle = editor.file?.name,
        onDismiss = { onEvent(LocationEvent.CancelEdit) },
        visible = true,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(LocationEvent.CancelEdit) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Save", onClick = { onEvent(LocationEvent.Save) }, loading = editor.saving)
        },
    ) {
        // The shell scrolls the body itself; a second scroller here is a crash.
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(value = editor.location, onValueChange = { change(editor.copy(location = it)) },
                label = "Location name", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.sceneNumber, onValueChange = { change(editor.copy(sceneNumber = it)) },
                    label = "Scene number", modifier = Modifier.weight(1f))
                if (state.viewer.isTelevision) {
                    ZillitTextField(value = editor.episodes, onValueChange = { change(editor.copy(episodes = it)) },
                        label = "Episodes", placeholder = "1,2", modifier = Modifier.weight(1f))
                }
                ZillitTextField(value = editor.city, onValueChange = { change(editor.copy(city = it)) },
                    label = "City", modifier = Modifier.weight(1f))
            }
            if (editor.id == null && editor.file == null) {
                ZillitTextField(value = editor.link, onValueChange = { change(editor.copy(link = it)) },
                    label = "Link", placeholder = "https://…", modifier = Modifier.fillMaxWidth())
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
                label = "Address",
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitTextField(value = editor.description, onValueChange = { change(editor.copy(description = it)) },
                label = "Description", singleLine = false, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.contactName, onValueChange = { change(editor.copy(contactName = it)) },
                    label = "Contact name", modifier = Modifier.weight(1f))
                ZillitTextField(value = editor.countryCode, onValueChange = { change(editor.copy(countryCode = it)) },
                    label = "Code", placeholder = "+44", modifier = Modifier.width(CODE_WIDTH))
                ZillitTextField(value = editor.phone, onValueChange = { change(editor.copy(phone = it)) },
                    label = "Phone", modifier = Modifier.weight(1f))
            }
            ZillitTextField(value = editor.email, onValueChange = { change(editor.copy(email = it)) }, label = "Email",
                modifier = Modifier.fillMaxWidth())
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
