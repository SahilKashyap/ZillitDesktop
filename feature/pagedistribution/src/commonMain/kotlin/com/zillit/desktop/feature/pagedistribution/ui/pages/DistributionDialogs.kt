// Dialogs branch on the tab kind; one composable per dialog.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.pagedistribution.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.pagedistribution.domain.FolderKey
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.DocumentCard
import com.zillit.desktop.feature.pagedistribution.ui.decodeImageBitmap

/** Every overlay the tool opens: folder, upload, viewer, tallies, move, and the two confirms. */
@Composable
internal fun DistributionDialogs(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    state.openFolder?.let { FolderDialog(state, onEvent, resolveUser) }
    state.upload?.let { UploadDialog(state, onEvent) }
    state.pdf?.let { PdfDialog(state, onEvent) }
    state.counts?.let { CountsDialog(state, onEvent, resolveUser) }
    state.move?.let { MoveDialog(state, onEvent) }
    state.confirmDelete?.let { document ->
        ConfirmDialog(
            title = "Delete document",
            body = "Delete \"${document.displayName()}\"? It moves to the history.",
            confirm = "Delete",
            danger = true,
            busy = state.busy,
            onConfirm = { onEvent(DistributionEvent.ConfirmDelete) },
            onDismiss = { onEvent(DistributionEvent.CancelDelete) },
        )
    }
    state.confirmPublish?.let { document ->
        val tab = state.activeTab
        val path = listOfNotNull(
            state.tool.publishRoot,
            tab.publishSubFolder,
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

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ZillitDialogShell(
        title = title,
        onDismiss = onDismiss,
        visible = true,
        actions = {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = confirm,
                onClick = onConfirm,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = busy,
            )
        },
    ) {
        ZillitText(text = body, style = ZillitTheme.typography.bodyMedium)
    }
}

@Composable
private fun FolderDialog(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val open = state.openFolder ?: return
    val tab = state.activeTab
    ZillitDialogShell(
        title = if (state.isDod) open.folder.key else "Scene ${open.folder.key}",
        subtitle = open.folder.scheduleType?.label,
        onDismiss = { onEvent(DistributionEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = FOLDER_DIALOG_WIDTH,
        actions = {
            if (state.mode == ListMode.Live) {
                ZillitButton(
                    text = "Upload here",
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    variant = ButtonVariant.Secondary,
                )
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(DistributionEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (state.loading && open.documents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
            return@ZillitDialogShell
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (open.documents.isEmpty()) {
                item {
                    ZillitText(
                        text = "No documents in this folder.",
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            items(open.documents, key = { it.id }) { document ->
                DocumentCard(state, tab, document, onEvent, resolveUser)
            }
            if (!open.exhausted && open.documents.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ZillitButton(
                            text = "Load older",
                            onClick = { onEvent(DistributionEvent.LoadMore) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            loading = open.loadingMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val editor = state.upload ?: return
    val tab = state.activeTab
    val kind = tab.kind
    val folders = kind as? TabKind.Folders
    val replacing = editor.replaces != null
    ZillitDialogShell(
        title = if (replacing) "Replace document" else "Upload PDF",
        subtitle = editor.fileName,
        onDismiss = { onEvent(DistributionEvent.CancelUpload) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DistributionEvent.CancelUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (replacing) "Replace" else "Upload",
                onClick = { onEvent(DistributionEvent.SubmitUpload) },
                loading = editor.saving,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (replacing) {
                ZillitStatusPill(label = "Replaces the current document", tone = StatusTone.Pending)
            }
            if (folders?.folderKey == FolderKey.Name) {
                ZillitTextField(
                    value = editor.name,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(name = it, nameFromPick = false)) },
                    label = "Folder name",
                    placeholder = "Week One",
                    helperText = "Pick an existing folder below, or type a new one.",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.folderNames.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        state.folderNames.take(MAX_FOLDER_CHIPS).forEach { name ->
                            ZillitChoiceChip(
                                label = name,
                                selected = editor.nameFromPick && editor.name == name,
                                onClick = {
                                    onEvent(DistributionEvent.UploadChanged(name = name, nameFromPick = true))
                                },
                            )
                        }
                    }
                }
            }
            if (kind is TabKind.Single) {
                ZillitTextField(
                    value = editor.name,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(name = it)) },
                    label = "Name (optional)",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !replacing,
                )
            }
            if (state.viewer.isTelevision) {
                ZillitTextField(
                    value = editor.episode,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(episode = it)) },
                    label = if (kind is TabKind.Single) "Episode number" else "Episode number (optional)",
                    placeholder = "1 or 1,2",
                    modifier = Modifier.width(FIELD_WIDTH),
                )
            }
            if (folders != null && folders.folderKey == FolderKey.SceneNumber) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitTextField(
                        value = editor.sceneNumber,
                        onValueChange = { onEvent(DistributionEvent.UploadChanged(sceneNumber = it)) },
                        label = "Scene number",
                        modifier = Modifier.width(FIELD_WIDTH),
                        enabled = !replacing,
                    )
                    ZillitTextField(
                        value = editor.pageNumber,
                        onValueChange = { onEvent(DistributionEvent.UploadChanged(pageNumber = it)) },
                        label = "Page number (optional)",
                        modifier = Modifier.width(FIELD_WIDTH),
                    )
                }
            }
            if (folders != null) {
                Column {
                    ZillitText(
                        text = "Revision colour",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitSelect(
                        value = editor.colour,
                        options = PageColour.entries,
                        onSelect = { onEvent(DistributionEvent.UploadChanged(colour = it)) },
                        label = { it.label },
                        modifier = Modifier.width(FIELD_WIDTH),
                    )
                }
            }
            if (folders?.scheduleTypeChoice == true && !replacing) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitText(
                        text = "Which pages are these?",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ScheduleType.entries.forEach { type ->
                        ZillitCheckbox(
                            checked = editor.scheduleType == type,
                            onCheckedChange = { onEvent(DistributionEvent.UploadChanged(scheduleType = type)) },
                            label = type.label,
                        )
                    }
                }
            }
            ZillitTextField(
                value = editor.dateYmd,
                onValueChange = { onEvent(DistributionEvent.UploadChanged(dateYmd = it)) },
                label = if (folders != null) "Page date (optional)" else "Date (optional)",
                placeholder = "YYYY-MM-DD",
                modifier = Modifier.width(FIELD_WIDTH),
            )
        }
    }
}

@Composable
internal fun PdfDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val view = state.pdf ?: return
    ZillitDialogShell(
        title = view.document.attachment?.name?.ifBlank { null }
            ?: view.document.originalName.ifBlank { null }
            ?: view.document.name.ifBlank { null }
            ?: "Document",
        onDismiss = { onEvent(DistributionEvent.CloseViewer) },
        visible = true,
        scrollable = false,
        width = PDF_DIALOG_WIDTH,
        actions = {
            if (state.viewer.mayDownload) {
                ZillitButton(
                    text = "Download",
                    onClick = { onEvent(DistributionEvent.Download(view.document)) },
                    variant = ButtonVariant.Secondary,
                    loading = state.busy,
                )
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(DistributionEvent.CloseViewer) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (view.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                view.pages.forEach { page ->
                    val bitmap = remember(page.page) { decodeImageBitmap(page.imageBytes) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Page ${page.page + 1}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountsDialog(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val counts = state.counts ?: return
    val rows = counts.rows.filter { if (counts.downloads) it.downloadCount > 0 else it.viewCount >= 1 }
    ZillitDialogShell(
        title = if (counts.downloads) "Download count" else "View count",
        subtitle = counts.document.attachment?.name,
        onDismiss = { onEvent(DistributionEvent.CloseCounts) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(DistributionEvent.CloseCounts) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        when {
            counts.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
            rows.isEmpty() -> ZillitText(
                text = if (counts.downloads) "Nobody has downloaded this yet." else "Nobody has viewed this yet.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ZillitText(
                            text = resolveUser(row.userId) ?: row.userId,
                            style = ZillitTheme.typography.bodyMedium,
                        )
                        ZillitText(
                            text = (if (counts.downloads) row.downloadCount else row.viewCount).toString(),
                            style = ZillitTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val move = state.move ?: return
    val candidates = state.folders.filter { it.key != move.document.name && it.key.isNotBlank() }
    ZillitDialogShell(
        title = "Move to folder",
        subtitle = move.document.attachment?.name,
        onDismiss = { onEvent(DistributionEvent.CancelMove) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DistributionEvent.CancelMove) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Move",
                onClick = { onEvent(DistributionEvent.ConfirmMove) },
                enabled = move.target != null,
                loading = move.saving,
            )
        },
    ) {
        if (candidates.isEmpty()) {
            ZillitText(
                text = "No other folders to move to.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            candidates.forEach { folder ->
                val selected = move.target?.key == folder.key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) ZillitTheme.colors.surfaceSelected else ZillitTheme.colors.surface,
                            ZillitTheme.shapes.medium,
                        )
                        .clickable { onEvent(DistributionEvent.MoveTarget(folder)) }
                        .padding(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitCheckbox(
                        checked = selected,
                        onCheckedChange = { onEvent(DistributionEvent.MoveTarget(folder)) },
                        label = folder.key,
                    )
                }
            }
        }
    }
}

/** The name the confirms quote — the file's, else a stand-in. */
private fun com.zillit.desktop.feature.pagedistribution.domain.DistDocument.displayName(): String =
    attachment?.name?.ifBlank { null } ?: "this document"

private val FIELD_WIDTH = 220.dp
private val FOLDER_DIALOG_WIDTH = 820.dp
private val PDF_DIALOG_WIDTH = 1180.dp
private const val MAX_FOLDER_CHIPS = 12
