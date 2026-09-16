// One composable per dialog; the document card and the upload form are long by nature.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.pagedistribution.ui.dod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.pages.ConfirmDialog
import com.zillit.desktop.feature.pagedistribution.ui.pages.PdfDialog
import com.zillit.desktop.feature.pagedistribution.ui.tint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Every overlay the D.O.D page opens: the folder's documents, the upload
 * form, the PDF viewer, the tallies, the move picker, and the two confirms.
 * The viewer and the confirms are the engine's own; the rest wear the web's
 * D.O.D face.
 */
@Composable
internal fun DodDialogs(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    state.openFolder?.let { DodFolderDialog(state, onEvent, resolveUser) }
    state.upload?.let { DodUploadDialog(state, onEvent) }
    state.pdf?.let { PdfDialog(state, onEvent) }
    state.counts?.let { DodCountsDialog(state, onEvent, resolveUser) }
    state.move?.let { DodMoveDialog(state, onEvent) }
    state.confirmDelete?.let { document ->
        ConfirmDialog(
            title = "Delete document",
            body = "Are you sure you want to delete \"${document.displayName()}\"? It moves to the history.",
            confirm = "Yes, delete",
            danger = true,
            busy = state.busy,
            onConfirm = { onEvent(DistributionEvent.ConfirmDelete) },
            onDismiss = { onEvent(DistributionEvent.CancelDelete) },
        )
    }
    state.confirmPublish?.let { document ->
        // The web's `handleDistributeToDocDist`: episode (TV) else scene as the leaf.
        val path = listOfNotNull(
            state.tool.publishRoot,
            document.episode.ifBlank { document.sceneNumber }.ifBlank { null },
        )
        ConfirmDialog(
            title = "Publish to Document Distribution",
            body = "File \"${document.displayName()}\" under ${path.joinToString(" / ")}?",
            confirm = "Publish",
            danger = false,
            busy = state.busy,
            onConfirm = { onEvent(DistributionEvent.ConfirmPublish) },
            onDismiss = { onEvent(DistributionEvent.CancelPublish) },
        )
    }
}

// Folder ---------------------------------------------------------------------

/**
 * A folder opened — the web's 800-wide modal listing its documents newest
 * first, paged as the reader scrolls (`handleScroll` → `getNextDodPagesData`).
 */
@Composable
private fun DodFolderDialog(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val open = state.openFolder ?: return
    val live = state.mode == ListMode.Live
    val listState = rememberLazyListState()
    // The web loads the next page when the panel is scrolled to its bottom.
    LaunchedEffect(listState, open.documents.size, open.exhausted) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 1 && info.totalItemsCount > 0
        }
            .distinctUntilChanged()
            .filter { it && !open.exhausted && !open.loadingMore && open.documents.isNotEmpty() }
            .collect { onEvent(DistributionEvent.LoadMore) }
    }
    ZillitDialogShell(
        title = open.folder.key.ifBlank { "Untitled folder" },
        subtitle = when {
            !live -> "Deleted documents"
            open.documents.isEmpty() -> "Schedule D.O.D"
            else -> "${open.documents.size} document${if (open.documents.size == 1) "" else "s"}"
        },
        icon = ZillitIcons.Folder,
        onDismiss = { onEvent(DistributionEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = FOLDER_DIALOG_WIDTH,
        actions = {
            if (live) {
                ZillitButton(
                    text = "Upload here",
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Paperclip,
                )
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(DistributionEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        val colors = ZillitTheme.colors
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = FOLDER_LIST_MIN_HEIGHT, max = FOLDER_LIST_MAX_HEIGHT)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.sm),
        ) {
            when {
                state.loading && open.documents.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                open.documents.isEmpty() -> ZillitEmptyState(
                    title = "No documents in this folder",
                    message = if (live) "Upload a PDF here to start it." else null,
                    icon = ZillitIcons.File,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(open.documents, key = { it.id }) { document ->
                        DodDocumentCard(state, document, onEvent, resolveUser)
                    }
                    if (open.loadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
                                contentAlignment = Alignment.Center,
                            ) { ZillitSpinner() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One document — the web's `Badge.Ribbon` over an antd `Card` painted the
 * page's revision colour: the "Uploaded on" ribbon, the uploader's face and
 * name, the episode on television, the folder name, and the orange More
 * pill whose popover holds every action.
 *
 * The colour is the page's own data (white, pink, blue…), so it stays even
 * in the dark theme, as the web's CSS insists; the text on it is the
 * theme's, which the tint keeps legible.
 */
@Composable
internal fun DodDocumentCard(
    state: DistributionUiState,
    document: DistDocument,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    val uploader = resolveUser(document.createdBy) ?: document.createdBy.ifBlank { "Unknown user" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(tint(document.colour, colors.surface))
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Ribbon(text = "Uploaded on: ${DistributionDates.dateTime(document.createdMs)}")
            Row(
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (document.deleted) ZillitStatusPill(label = "Deleted", tone = StatusTone.Rejected)
                DodMoreMenu(state, document, onEvent)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.md, bottom = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            ZillitAvatar(
                name = uploader.substringBefore(" ("),
                image = rememberDodFace(document.createdBy),
                userId = document.createdBy,
                size = CARD_AVATAR,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Fact(label = "Uploaded by", value = uploader)
                if (state.viewer.isTelevision) Fact(label = "Episode", value = document.episode.ifBlank { "—" })
                Fact(label = "Name", value = document.name.ifBlank { "—" })
                // The folder listing carries no attachment on the wire; only
                // a name or size that is actually known earns the line.
                val fileFacts = listOfNotNull(
                    document.fileName(),
                    formatBytes(document.attachment?.fileSize),
                )
                if (fileFacts.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitIcon(icon = ZillitIcons.File, tint = colors.textMuted, size = FACT_ICON)
                        ZillitText(
                            text = fileFacts.joinToString("  ·  "),
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The antd ribbon: a small accent tab pinned to the card's top-left corner. */
@Composable
internal fun Ribbon(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomEnd = RIBBON_CORNER))
            .background(ZillitTheme.colors.accent)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textOnAccent,
            maxLines = 1,
        )
    }
}

@Composable
internal fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs), verticalAlignment = Alignment.Top) {
        ZillitText(
            text = "$label:",
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 2,
        )
    }
}

/**
 * The web's `MorePopoverContent`, entry for entry and in its order: View,
 * Publish to Doc Distribution, Delete, Download, Download count, View
 * count, Move. History (the web's file cabinet) keeps View and Download
 * only — deleted documents are not publishable (ZL-20141).
 *
 * Delete and Move stay visible without the right, as on the web: the press
 * answers with "only an admin or the uploader can delete" or asks an admin
 * for posting rights, instead of a control that is simply not there.
 */
@Composable
private fun DodMoreMenu(state: DistributionUiState, document: DistDocument, onEvent: (DistributionEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val live = state.mode == ListMode.Live
    val colors = ZillitTheme.colors
    Box {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(colors.accent)
                .clickable { open = true }
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(icon = ZillitIcons.Paperclip, tint = colors.textOnAccent, size = FACT_ICON)
            ZillitText(
                text = "More",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textOnAccent,
            )
        }
        val entries = buildList {
            add(
                ZillitMenuEntry.Action("View", ZillitIcons.Eye, ZillitMenuTone.Primary) {
                    onEvent(DistributionEvent.View(document))
                },
            )
            if (live && state.viewer.mayPublish) {
                add(
                    ZillitMenuEntry.Action("Publish to Doc Distribution", ZillitIcons.Send, ZillitMenuTone.Info) {
                        onEvent(DistributionEvent.Publish(document))
                    },
                )
            }
            if (live) {
                add(
                    ZillitMenuEntry.Action("Delete", ZillitIcons.Trash, ZillitMenuTone.Danger) {
                        onEvent(DistributionEvent.Delete(document))
                    },
                )
            }
            add(
                ZillitMenuEntry.Action("Download", ZillitIcons.Download, ZillitMenuTone.Neutral) {
                    onEvent(DistributionEvent.Download(document))
                },
            )
            if (live && state.viewer.isAdmin) {
                add(ZillitMenuEntry.Divider)
                add(
                    ZillitMenuEntry.Action("Download count", ZillitIcons.BarChart, ZillitMenuTone.Neutral) {
                        onEvent(DistributionEvent.ShowCounts(document, downloads = true))
                    },
                )
                add(
                    ZillitMenuEntry.Action("View count", ZillitIcons.Users, ZillitMenuTone.Neutral) {
                        onEvent(DistributionEvent.ShowCounts(document, downloads = false))
                    },
                )
            }
            if (live) {
                add(ZillitMenuEntry.Divider)
                add(
                    ZillitMenuEntry.Action("Move to folder…", ZillitIcons.Folder, ZillitMenuTone.Approve) {
                        onEvent(DistributionEvent.Move(document))
                    },
                )
            }
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = entries.map { entry ->
                if (entry is ZillitMenuEntry.Action) {
                    entry.copy(
                        onClick = {
                            open = false
                            entry.onClick()
                        },
                    )
                } else {
                    entry
                }
            },
        )
    }
}

// Upload ---------------------------------------------------------------------

/**
 * The web's `DocumentModal` in its D.O.D shape: the file, a required folder
 * name that autocompletes from the existing folders, the episode on
 * television, and the optional page colour. No date — the web's D.O.D
 * picker is commented out and `user_selected_date` goes up as 0.
 */
@Composable
private fun DodUploadDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val editor = state.upload ?: return
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "Upload D.O.D",
        subtitle = "Choose the folder it files under",
        icon = ZillitIcons.Upload,
        onDismiss = { onEvent(DistributionEvent.CancelUpload) },
        visible = true,
        width = UPLOAD_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DistributionEvent.CancelUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Upload",
                onClick = { onEvent(DistributionEvent.SubmitUpload) },
                leadingIcon = ZillitIcons.Upload,
                loading = editor.saving,
                enabled = editor.name.isNotBlank() && !editor.saving,
            )
        },
    ) {
        // The file, as the web's preview strip names it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(FILE_TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.dangerSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = "PDF",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.danger,
                )
            }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = editor.fileName,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    text = "File size: ${formatBytes(editor.bytes.size.toString())}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        FolderNamePicker(state, editor.name, editor.nameFromPick, onEvent)

        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = editor.episode,
                onValueChange = { onEvent(DistributionEvent.UploadChanged(episode = it)) },
                label = "Episode number",
                placeholder = "1 or 1,2",
                helperText = "Required on a television production.",
                modifier = Modifier.width(FIELD_WIDTH),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = "Page colour (optional)",
                style = ZillitTheme.typography.label,
                color = colors.textSecondary,
            )
            ColourSwatches(
                selected = editor.colour,
                onSelect = { onEvent(DistributionEvent.UploadChanged(colour = it)) },
            )
            ZillitText(
                text = editor.colour.label,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * The web's antd `AutoComplete`: type a name, and the existing folders that
 * contain what was typed are offered underneath. A picked name is kept
 * verbatim; a typed one is title-cased on the way up (`capitalizeWordsForUnits`).
 */
@Composable
private fun FolderNamePicker(
    state: DistributionUiState,
    name: String,
    fromPick: Boolean,
    onEvent: (DistributionEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val matches = remember(state.folderNames, name) {
        state.folderNames.filter { name.isBlank() || it.contains(name.trim(), ignoreCase = true) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitTextField(
            value = name,
            onValueChange = { onEvent(DistributionEvent.UploadChanged(name = it, nameFromPick = false)) },
            label = "Folder name",
            placeholder = "Type a name",
            leadingIcon = ZillitIcons.Folder,
            helperText = if (fromPick) {
                "Existing folder."
            } else {
                "Pick an existing folder below, or type a new one — new names are title-cased."
            },
            errorText = if (name.isBlank()) "Name is required" else null,
            modifier = Modifier.fillMaxWidth(),
        )
        // Nothing to offer once the only match is the picked folder itself.
        val onlyThePick = fromPick && matches.singleOrNull() == name
        if (matches.isNotEmpty() && !onlyThePick) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SUGGESTIONS_MAX_HEIGHT)
                    .clip(ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .background(colors.surface)
                    .verticalScroll(rememberScrollState()),
            ) {
                matches.forEach { folder ->
                    val selected = fromPick && folder == name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) colors.surfaceSelected else Color.Transparent)
                            .clickable { onEvent(DistributionEvent.UploadChanged(name = folder, nameFromPick = true)) }
                            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = FACT_ICON)
                        ZillitText(
                            text = folder,
                            style = ZillitTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accent, size = FACT_ICON)
                    }
                }
            }
        }
    }
}

/** The eleven revision colours as swatches — the web's coloured `Select` options, seen at once. */
@Composable
internal fun ColourSwatches(selected: PageColour, onSelect: (PageColour) -> Unit) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        PageColour.entries.forEach { colour ->
            val isSelected = colour == selected
            Box(
                modifier = Modifier
                    .size(SWATCH)
                    .clip(CircleShape)
                    .background(swatchColour(colour))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) colors.accent else colors.borderStrong,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(colour) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    ZillitIcon(
                        icon = ZillitIcons.Check,
                        // The swatch is the page's own colour, not the theme's, so the
                        // tick's contrast is decided by the swatch alone.
                        tint = if (colour.isPale) DARK_TICK else Color.White,
                        size = FACT_ICON,
                    )
                }
            }
        }
    }
}

// Counts ---------------------------------------------------------------------

/**
 * The web's `ScriptDrawer`: who viewed (or downloaded) a document, with
 * their face and a name filter, admins only.
 */
@Composable
internal fun DodCountsDialog(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val counts = state.counts ?: return
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    val rows = counts.rows
        .filter { if (counts.downloads) it.downloadCount > 0 else it.viewCount >= 1 }
        .map { row -> row to (resolveUser(row.userId) ?: row.userId) }
        .filter { (_, name) -> query.isBlank() || name.contains(query.trim(), ignoreCase = true) }
    ZillitDialogShell(
        title = if (counts.downloads) "Download count" else "View count",
        subtitle = counts.document.displayName(),
        icon = if (counts.downloads) ZillitIcons.Download else ZillitIcons.Eye,
        onDismiss = { onEvent(DistributionEvent.CloseCounts) },
        visible = true,
        width = COUNTS_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(DistributionEvent.CloseCounts) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search by name",
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            counts.loading -> Box(
                Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
            rows.isEmpty() -> ZillitEmptyState(
                title = when {
                    query.isNotBlank() -> "Nobody matches"
                    counts.downloads -> "Nobody has downloaded this yet"
                    else -> "Nobody has viewed this yet"
                },
                icon = ZillitIcons.Users,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                rows.forEach { (row, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ZillitTheme.shapes.medium)
                            .background(colors.infoSoft)
                            .padding(ZillitTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitAvatar(
                            name = name.substringBefore(" ("),
                            image = rememberDodFace(row.userId),
                            userId = row.userId,
                            size = COUNT_AVATAR,
                        )
                        Column(Modifier.weight(1f)) {
                            ZillitText(
                                text = name,
                                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.info,
                                maxLines = 1,
                            )
                            ZillitText(
                                text = if (counts.downloads) {
                                    "Download count: ${row.downloadCount}"
                                } else {
                                    "View count: ${row.viewCount}"
                                },
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Move -----------------------------------------------------------------------

/**
 * The web's `MoveDodModal`: every other live folder with a name, one
 * ticked, and Move disabled until one is.
 */
@Composable
private fun DodMoveDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val move = state.move ?: return
    val colors = ZillitTheme.colors
    val candidates = state.folders.filter { !it.deleted && it.key.isNotBlank() && it.key != move.document.name }
    ZillitDialogShell(
        title = "Select folder to move",
        subtitle = move.document.displayName(),
        icon = ZillitIcons.Folder,
        onDismiss = { onEvent(DistributionEvent.CancelMove) },
        visible = true,
        width = MOVE_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DistributionEvent.CancelMove) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Move",
                onClick = { onEvent(DistributionEvent.ConfirmMove) },
                enabled = move.target != null && !move.saving,
                loading = move.saving,
            )
        },
    ) {
        if (candidates.isEmpty()) {
            ZillitEmptyState(title = "No other folders to move to", icon = ZillitIcons.Folder)
            return@ZillitDialogShell
        }
        Column {
            candidates.forEachIndexed { index, folder ->
                val selected = move.target?.key == folder.key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ZillitTheme.shapes.medium)
                        .background(if (selected) colors.surfaceSelected else Color.Transparent)
                        .clickable { onEvent(DistributionEvent.MoveTarget(folder)) }
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = FACT_ICON)
                    ZillitText(
                        text = folder.key,
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ZillitCheckbox(
                        checked = selected,
                        onCheckedChange = { onEvent(DistributionEvent.MoveTarget(folder)) },
                    )
                }
                if (index < candidates.lastIndex) {
                    Spacer(Modifier.height(1.dp).fillMaxWidth().background(colors.divider))
                }
            }
        }
    }
}

// Helpers --------------------------------------------------------------------

/** The file's own name when the wire carried one; null otherwise. */
internal fun DistDocument.fileName(): String? =
    attachment?.name?.ifBlank { null } ?: originalName.ifBlank { null }

/** The name the confirms and titles show — the file's, else a stand-in. */
internal fun DistDocument.displayName(): String = fileName() ?: "Document"

/** A stringified byte count as "1.2 MB"; null when unknown. */
internal fun formatBytes(size: String?): String? {
    val bytes = size?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return when {
        bytes >= MEGABYTE -> "${tenths(bytes, MEGABYTE)} MB"
        bytes >= KILOBYTE -> "${tenths(bytes, KILOBYTE)} KB"
        else -> "$bytes B"
    }
}

/** [bytes] over [unit] to one decimal, rounded half up. */
private fun tenths(bytes: Long, unit: Long): Float = ((bytes * TENTHS + unit / 2) / unit) / TENTHS_F

/** Whether a tick on this colour needs to be dark to be seen. */
private val PageColour.isPale: Boolean
    get() = this == PageColour.White || this == PageColour.Ivory || this == PageColour.Yellow ||
        this == PageColour.Cherry || this == PageColour.Buff

/** The colour's own hex, at full strength, for its swatch. */
private fun swatchColour(colour: PageColour): Color {
    val v = colour.hex.removePrefix("#").toLong(HEX_RADIX)
    return Color(
        red = ((v shr RED_SHIFT) and CHANNEL) / CHANNEL_MAX,
        green = ((v shr GREEN_SHIFT) and CHANNEL) / CHANNEL_MAX,
        blue = (v and CHANNEL) / CHANNEL_MAX,
    )
}

private val FOLDER_DIALOG_WIDTH = 820.dp
private val FOLDER_LIST_MIN_HEIGHT = 320.dp
private val FOLDER_LIST_MAX_HEIGHT = 640.dp
private val UPLOAD_DIALOG_WIDTH = 560.dp
private val COUNTS_DIALOG_WIDTH = 440.dp
private val MOVE_DIALOG_WIDTH = 460.dp
private val FIELD_WIDTH = 220.dp
private val FILE_TILE = 40.dp
private val CARD_AVATAR = 48.dp
private val COUNT_AVATAR = 40.dp
private val FACT_ICON = 14.dp
private val SWATCH = 28.dp
private val RIBBON_CORNER = 10.dp
private val DARK_TICK = Color(0xFF1F2937)
private val SUGGESTIONS_MAX_HEIGHT = 168.dp
private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L
private const val TENTHS = 10L
private const val TENTHS_F = 10f
private const val HEX_RADIX = 16
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFFL
private const val CHANNEL_MAX = 255f
