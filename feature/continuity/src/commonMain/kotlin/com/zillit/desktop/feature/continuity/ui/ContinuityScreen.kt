// One composable per board piece; the dialogs branch on the state.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.continuity.ui

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
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
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
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab

/** The continuity board: two tabs, scene folders, and the card dialogs. */
@Composable
fun ContinuityScreen(
    state: ContinuityUiState,
    onEvent: (ContinuityEvent) -> Unit,
    /** A stored file decoded — the thumbnail for cards, the full image for the viewer. */
    loadImage: suspend (ContinuityAttachment, preview: Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
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
                title = "Continuity",
                description = "Photos, videos and documents by scene — yours in My Department, " +
                    "forwarded ones in All.",
                actions = {
                    ZillitButton(text = "Refresh", onClick = { onEvent(ContinuityEvent.Refresh) },
                        variant = ButtonVariant.Tertiary, loading = state.loading)
                    ZillitButton(text = "Upload", onClick = { onEvent(ContinuityEvent.PickFiles) },
                        leadingIcon = ZillitIcons.Upload)
                },
            )
            if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Continuity tool.")
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(text = "Dismiss", onClick = { onEvent(ContinuityEvent.DismissError) },
                            variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                    },
                )
            }
            ZillitTabStrip(
                tabs = ContinuityTab.entries.map { ZillitTab(it.wireLabel, it.label) },
                activeId = state.tab.wireLabel,
                onSelect = { id ->
                    onEvent(ContinuityEvent.SelectTab(ContinuityTab.entries.first { it.wireLabel == id }))
                },
            )
            ZillitSearchField(
                value = state.folderQuery,
                onValueChange = { onEvent(ContinuityEvent.SearchFolders(it)) },
                placeholder = "Search scene number",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            when {
                state.loading && state.folders.isEmpty() -> Box(Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.shownFolders.isEmpty() -> ZillitText(
                    text = when {
                        state.folderQuery.isNotBlank() -> "No scene matches."
                        state.tab == ContinuityTab.MyDepartment -> "Nothing uploaded by your department yet."
                        else -> "Nothing has been forwarded to All Departments yet."
                    },
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                else -> FolderGrid(state, onEvent)
            }
        }
        state.open?.let { CardsDialog(state, it, onEvent, loadImage, resolveUser, formatDate) }
        state.pick?.let { PickDialog(state, onEvent) }
        state.viewing?.let { ViewDialog(state, it, onEvent, loadImage, resolveUser, formatDate) }
        state.details?.let { DetailsDialog(state, it, onEvent, resolveUser, formatDate) }
        state.editor?.let { EditorDialog(state, onEvent) }
        if (state.confirmForward) ForwardDialog(state, onEvent)
        state.confirmDelete?.let { DeleteDialog(state, it, onEvent) }
    }
}

@Composable
private fun FolderGrid(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val colors = ZillitTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.shownFolders, key = { it }) { folder ->
            Column(
                modifier = Modifier
                    .background(colors.surface, ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .clickable { onEvent(ContinuityEvent.OpenFolder(folder)) }
                    .padding(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = "Scene $folder",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = if (state.tab == ContinuityTab.AllDepartments) "Pick a department" else "Open cards",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

// Department pick (All board) ------------------------------------------------

@Composable
private fun PickDialog(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val pick = state.pick ?: return
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "Scene ${pick.sceneFolder}",
        subtitle = "Departments with media",
        onDismiss = { onEvent(ContinuityEvent.ClosePick) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.ClosePick) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        when {
            pick.loading -> Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center) { ZillitSpinner() }
            pick.departments.isEmpty() -> ZillitText(text = "No department has forwarded media for this scene.",
                style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                pick.departments.forEach { department ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface, ZillitTheme.shapes.medium)
                            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                            .clickable { onEvent(ContinuityEvent.OpenDepartment(department)) }
                            .padding(ZillitTheme.spacing.md),
                    ) {
                        ZillitText(
                            text = state.departmentNames[department.id] ?: department.name.ifBlank { department.id },
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

// Cards ------------------------------------------------------------------------

@Composable
private fun CardsDialog(
    state: ContinuityUiState,
    open: OpenFolder,
    onEvent: (ContinuityEvent) -> Unit,
    loadImage: suspend (ContinuityAttachment, Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    val colors = ZillitTheme.colors
    val mine = open.tab == ContinuityTab.MyDepartment
    val subtitle = buildList {
        open.department?.let { add(state.departmentNames[it.id] ?: it.name) }
        add(open.tab.label)
        add("${open.shown.size} card(s)")
    }.joinToString(" · ")
    ZillitDialogShell(
        title = "Scene ${open.sceneFolder}",
        subtitle = subtitle,
        onDismiss = { onEvent(ContinuityEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = GALLERY_WIDTH,
        actions = {
            ZillitSearchField(
                value = open.query,
                onValueChange = { onEvent(ContinuityEvent.SearchCards(it)) },
                placeholder = "Search cards",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            if (mine) {
                if (open.selecting) {
                    ZillitButton(text = "Forward to All (${open.selected.size})",
                        onClick = { onEvent(ContinuityEvent.ForwardSelected) }, variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small, enabled = open.selected.isNotEmpty(), loading = state.busy)
                    // Off the board, not deleted — the work stays in the file
                    // cabinet, which is where a wrapped scene belongs.
                    ZillitButton(text = "File cabinet (${open.selected.size})",
                        onClick = { onEvent(ContinuityEvent.ArchiveSelected) }, variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small, enabled = open.selected.isNotEmpty(), loading = state.busy)
                }
                ZillitButton(text = if (open.selecting) "Done" else "Select",
                    onClick = { onEvent(ContinuityEvent.ToggleSelecting) }, variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small)
                ZillitButton(text = "Upload here", onClick = { onEvent(ContinuityEvent.PickFiles) },
                    variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        when {
            open.loading && open.scenes.isEmpty() -> Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center) { ZillitSpinner() }
            open.shown.isEmpty() -> ZillitText(
                text = if (open.query.isBlank()) "No cards in this scene." else "No card matches.",
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(TILE_MIN_WIDTH),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(open.shown, key = { it.id }) { scene ->
                    SceneTile(state, open, scene, loadImage, resolveUser, formatDate, onEvent)
                }
                if (!open.exhausted) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitButton(text = "Load older", onClick = { onEvent(ContinuityEvent.LoadMore) },
                                variant = ButtonVariant.Tertiary, size = ButtonSize.Small, loading = open.loadingMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneTile(
    state: ContinuityUiState,
    open: OpenFolder,
    scene: ContinuityScene,
    loadImage: suspend (ContinuityAttachment, Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
    onEvent: (ContinuityEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val selected = scene.id in open.selected
    // Whose card this is. A card from another department is not this person's
    // to edit at any rights level, so those controls are genuinely absent —
    // unlike the posting right, which now leaves them on screen and answers a
    // press with the offer to ask for it (see ContinuityViewModel.guardPost).
    val owned = state.viewer.isAdmin ||
        (open.tab == ContinuityTab.MyDepartment && scene.departmentId == state.viewer.departmentId)
    Column(
        modifier = Modifier
            .background(colors.surface, ZillitTheme.shapes.medium)
            .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .clickable {
                onEvent(if (open.selecting) ContinuityEvent.ToggleSelect(scene.id) else ContinuityEvent.View(scene))
            },
    ) {
        Thumbnail(scene, loadImage, Modifier.fillMaxWidth().aspectRatio(THUMB_RATIO))
        Column(
            Modifier.padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                if (open.selecting) {
                    ZillitCheckbox(
                        checked = selected,
                        onCheckedChange = { onEvent(ContinuityEvent.ToggleSelect(scene.id)) },
                    )
                }
                ZillitText(
                    text = "Scene ${scene.sceneNumber}" +
                        if (scene.episode.isNotBlank()) " · Ep ${scene.episode}" else "",
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                scene.attachment?.let { a ->
                    when {
                        a.isVideo -> ZillitStatusPill(label = "Video", tone = StatusTone.Neutral)
                        a.isDocument -> ZillitStatusPill(label = a.contentSubtype.uppercase().ifBlank { "Doc" },
                            tone = StatusTone.Neutral)
                    }
                }
            }
            if (scene.notes.isNotBlank()) {
                ZillitText(text = scene.notes, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary,
                    maxLines = 2)
            }
            ZillitText(
                text = "${resolveUser(scene.uploadedBy) ?: "Unknown"} · ${formatDate(scene.createdMs)}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (!open.selecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitIconButton(icon = ZillitIcons.Info, contentDescription = "Details",
                        onClick = { onEvent(ContinuityEvent.ShowDetails(scene)) })
                    if (owned) {
                        ZillitIconButton(icon = ZillitIcons.Edit, contentDescription = "Edit",
                            onClick = { onEvent(ContinuityEvent.Edit(scene)) })
                    }
                    if (scene.attachment != null) {
                        ZillitIconButton(icon = ZillitIcons.Download, contentDescription = "Download",
                            onClick = { onEvent(ContinuityEvent.Download(scene)) })
                    }
                    if (owned) {
                        ZillitIconButton(icon = ZillitIcons.Trash, contentDescription = "Remove", tint = colors.danger,
                            onClick = { onEvent(ContinuityEvent.RequestDelete(scene)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(
    scene: ContinuityScene,
    loadImage: suspend (ContinuityAttachment, Boolean) -> ImageBitmap?,
    modifier: Modifier,
    preview: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val attachment = scene.attachment
    var bitmap by remember(scene.id, preview) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(scene.id, preview) {
        if (attachment != null && (attachment.isImage || attachment.isVideo)) bitmap = loadImage(attachment, preview)
    }
    Box(modifier.background(colors.surfaceSunken, ZillitTheme.shapes.medium), contentAlignment = Alignment.Center) {
        val ready = bitmap
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = scene.notes,
                contentScale = if (preview) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ZillitText(
                text = when {
                    attachment == null -> "No file"
                    attachment.isVideo -> "Video"
                    attachment.isDocument -> attachment.name.ifBlank { attachment.contentSubtype.uppercase() }
                    else -> "…"
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

// Viewer / details -----------------------------------------------------------

@Composable
private fun ViewDialog(
    state: ContinuityUiState,
    scene: ContinuityScene,
    onEvent: (ContinuityEvent) -> Unit,
    loadImage: suspend (ContinuityAttachment, Boolean) -> ImageBitmap?,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "Scene ${scene.sceneNumber}" + if (scene.episode.isNotBlank()) " · Episode ${scene.episode}" else "",
        subtitle = scene.attachment?.name?.ifBlank { null },
        onDismiss = { onEvent(ContinuityEvent.CloseView) },
        visible = true,
        width = GALLERY_WIDTH,
        actions = {
            ZillitButton(
                text = "Details",
                onClick = { onEvent(ContinuityEvent.ShowDetails(scene)) },
                variant = ButtonVariant.Tertiary,
            )
            if (scene.attachment != null) {
                ZillitButton(text = "Download", onClick = { onEvent(ContinuityEvent.Download(scene)) },
                    variant = ButtonVariant.Secondary, loading = state.busy)
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.CloseView) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Thumbnail(scene, loadImage, Modifier.fillMaxWidth().height(VIEW_HEIGHT), preview = false)
            scene.attachment?.let { a ->
                if (a.isVideo || a.isDocument) {
                    ZillitText(
                        text = if (a.isVideo) {
                            "Videos play from your Downloads folder — use Download."
                        } else {
                            "Download to open the document."
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            if (scene.notes.isNotBlank()) ZillitText(text = scene.notes, style = ZillitTheme.typography.bodyMedium)
            DetailRows(state, scene, resolveUser, formatDate)
        }
    }
}

@Composable
private fun DetailsDialog(
    state: ContinuityUiState,
    scene: ContinuityScene,
    onEvent: (ContinuityEvent) -> Unit,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    ZillitDialogShell(
        title = "Scene ${scene.sceneNumber}",
        subtitle = "More info",
        onDismiss = { onEvent(ContinuityEvent.CloseDetails) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.CloseDetails) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (scene.notes.isNotBlank()) ZillitText(text = scene.notes, style = ZillitTheme.typography.bodyMedium)
            DetailRows(state, scene, resolveUser, formatDate)
        }
    }
}

@Composable
private fun DetailRows(
    state: ContinuityUiState,
    scene: ContinuityScene,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    val colors = ZillitTheme.colors
    val rows = buildList {
        if (scene.episode.isNotBlank()) add("Episode" to scene.episode)
        if (scene.actorName.isNotBlank()) add("Actor" to scene.actorName)
        scene.talentInfo.forEach { add(it.label.ifBlank { "Detail" } to it.value) }
        state.departmentNames[scene.departmentId]?.let { add("Department" to it) }
        add("Uploaded by" to (resolveUser(scene.uploadedBy) ?: scene.uploadedBy))
        add("Uploaded" to formatDate(scene.createdMs))
        if (scene.updatedMs > scene.createdMs) add("Edited" to formatDate(scene.updatedMs))
        scene.attachment?.let { a ->
            add("File" to a.name)
            a.fileSize.toLongOrNull()?.takeIf { it > 0 }?.let { add("Size" to formatBytes(it)) }
        }
    }.filter { it.second.isNotBlank() }
    rows.forEach { (label, value) ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = colors.textMuted,
                modifier = Modifier.width(LABEL_WIDTH))
            ZillitText(text = value, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary)
        }
    }
}

// Editor ---------------------------------------------------------------------

@Composable
private fun EditorDialog(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val editor = state.editor ?: return
    val colors = ZillitTheme.colors
    val change = { updated: com.zillit.desktop.feature.continuity.domain.SceneDraft ->
        onEvent(ContinuityEvent.DraftChanged(updated))
    }
    ZillitDialogShell(
        title = if (editor.isNew) "Add to continuity" else "Edit scene",
        subtitle = when {
            !editor.isNew -> null
            editor.files.size == 1 -> editor.files.first().name
            else -> "${editor.files.size} files"
        },
        onDismiss = { onEvent(ContinuityEvent.CancelEdit) },
        visible = true,
        actions = {
            if (editor.saving && editor.files.size > 1) {
                ZillitText(text = "${editor.progress} of ${editor.files.size} uploaded",
                    style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelEdit) },
                variant = ButtonVariant.Tertiary,
                enabled = !editor.saving,
            )
            ZillitButton(text = if (editor.isNew) "Upload" else "Save", onClick = { onEvent(ContinuityEvent.Save) },
                loading = editor.saving)
        },
    ) {
        // The shell scrolls the body itself; a second scroller here is a crash.
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (editor.isNew && editor.files.size > 1) {
                ZillitText(text = editor.files.joinToString { it.name }, style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.draft.sceneNumber,
                    onValueChange = { change(editor.draft.copy(sceneNumber = it)) },
                    label = "Scene number", modifier = Modifier.weight(1f))
                if (state.viewer.isTelevision) {
                    ZillitTextField(
                        value = editor.draft.episode,
                        onValueChange = { change(editor.draft.copy(episode = it)) },
                        label = "Episode",
                        placeholder = "Digits",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ZillitTextField(value = editor.draft.notes, onValueChange = { change(editor.draft.copy(notes = it)) },
                label = "Notes", singleLine = false, modifier = Modifier.fillMaxWidth())
            ZillitText(text = "More info", style = ZillitTheme.typography.label, color = colors.textSecondary)
            editor.draft.talentInfo.forEachIndexed { index, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(text = row.label, style = ZillitTheme.typography.bodySmall, color = colors.textMuted,
                        modifier = Modifier.width(LABEL_WIDTH))
                    ZillitText(text = row.value, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary,
                        modifier = Modifier.weight(1f))
                    ZillitIconButton(icon = ZillitIcons.Close, contentDescription = "Remove",
                        onClick = { onEvent(ContinuityEvent.RemoveDetail(index)) })
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(value = editor.newLabel,
                    onValueChange = { onEvent(ContinuityEvent.NewDetailChanged(it, editor.newValue)) },
                    label = "Label", placeholder = "Costume", modifier = Modifier.weight(1f))
                ZillitTextField(value = editor.newValue,
                    onValueChange = { onEvent(ContinuityEvent.NewDetailChanged(editor.newLabel, it)) },
                    label = "Value", placeholder = "Blue jacket", modifier = Modifier.weight(1f))
                ZillitButton(
                    text = "Add",
                    onClick = { onEvent(ContinuityEvent.AddDetail) },
                    variant = ButtonVariant.Secondary,
                    enabled = editor.newLabel.isNotBlank() || editor.newValue.isNotBlank(),
                )
            }
        }
    }
}

// Confirmations --------------------------------------------------------------

@Composable
private fun ForwardDialog(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val n = state.open?.selected?.size ?: 0
    ZillitDialogShell(
        title = "Forward to All Departments",
        onDismiss = { onEvent(ContinuityEvent.CancelForward) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelForward) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = "Forward", onClick = { onEvent(ContinuityEvent.ConfirmForward) }, loading = state.busy)
        },
    ) {
        ZillitText(text = "Make $n card(s) visible to every department? This cannot be undone.",
            style = ZillitTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeleteDialog(state: ContinuityUiState, scene: ContinuityScene, onEvent: (ContinuityEvent) -> Unit) {
    val board = state.open?.tab?.label ?: state.tab.label
    ZillitDialogShell(
        title = "Remove from $board",
        onDismiss = { onEvent(ContinuityEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Remove",
                onClick = { onEvent(ContinuityEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.busy,
            )
        },
    ) {
        ZillitText(
            text = "Remove scene ${scene.sceneNumber}'s " +
                "${scene.attachment?.name?.ifBlank { null } ?: "card"} from $board?",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= MB -> "${bytes / MB} MB"
    bytes >= KB -> "${bytes / KB} KB"
    else -> "$bytes B"
}

private const val KB = 1024L
private const val MB = KB * KB
private const val THUMB_RATIO = 4f / 3f
private val SEARCH_WIDTH = 260.dp
private val FOLDER_MIN_WIDTH = 200.dp
private val TILE_MIN_WIDTH = 220.dp
private val GALLERY_WIDTH = 1100.dp
private val VIEW_HEIGHT = 480.dp
private val LABEL_WIDTH = 110.dp
