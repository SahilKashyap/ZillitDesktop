package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistPrompt
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

/**
 * The library: a breadcrumb, the folders inside the open one, and the
 * documents beneath them.
 *
 * ## One table, not two
 *
 * Folders and documents are separate collections on the wire and separate
 * sections here, rather than one merged list. The web merges them and pays for
 * it in every sort — a name sort that interleaves folders and files puts the
 * folder someone is looking for between two call sheets.
 */
@Composable
fun LibraryPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    var newFolderOpen by remember { mutableStateOf(false) }

    FixedPage {
        LibraryToolbar(
            state = state,
            onEvent = onEvent,
            onNewFolder = { newFolderOpen = true },
        )

        Breadcrumb(state, onEvent)

        if (state.subfolders.isNotEmpty()) {
            ZillitSectionCard(title = "Folders", icon = ZillitIcons.Grid, padded = false) {
                ZillitDataTable(
                    rows = state.subfolders,
                    key = { it.id },
                    onRowClick = { onEvent(DocDistEvent.OpenFolder(it.id)) },
                    // Inside a card in a fixed page there is a bounded height,
                    // but the folder list is short by nature and virtualising a
                    // dozen rows costs more than it saves.
                    virtualised = false,
                    columns = folderColumns(state, onEvent),
                    emptyTitle = "No folders",
                )
            }
        }

        DocumentsCard(state, onEvent)
    }

    MoveItemsDialog(state, onEvent)

    PublishDialog(state, onEvent)

    NewFolderDialog(
        visible = newFolderOpen,
        parent = state.currentFolder,
        onDismiss = { newFolderOpen = false },
        onCreate = { name ->
            newFolderOpen = false
            onEvent(DocDistEvent.CreateFolder(name, state.currentFolderId))
        },
    )
}

@Composable
private fun DocumentsCard(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    ZillitSectionCard(
        title = documentsTitle(state),
        icon = ZillitIcons.File,
        padded = false,
        action = {
            if (state.hasMore) {
                ZillitButton(
                    text = if (state.loadingMore) "Loading…" else "Load more",
                    onClick = { onEvent(DocDistEvent.LoadMore) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    loading = state.loadingMore,
                )
            }
        },
    ) {
        ZillitDataTable(
            rows = state.documents,
            key = { it.id },
            loading = state.loading,
            columns = documentColumns(state, onEvent),
            onRowClick = { onEvent(DocDistEvent.OpenDocument(it.id)) },
            isSelected = { it.id in state.selectedDocumentIds },
            emptyTitle = if (state.search.isBlank()) {
                "Nothing in this folder yet"
            } else {
                "No documents match \"${state.search}\""
            },
            emptyMessage = if (state.search.isBlank() && state.viewer.canPost) {
                "Upload a document, or create a folder to organise what the production issues."
            } else {
                null
            },
        )
    }
}

@Composable
private fun LibraryToolbar(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
    onNewFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(DocDistEvent.Search(it)) },
            placeholder = "Search this folder",
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )

        ZillitSelect(
            value = state.sort,
            options = LibrarySort.entries,
            onSelect = { onEvent(DocDistEvent.SortBy(it)) },
            label = { it.label },
            modifier = Modifier.width(SORT_WIDTH.dp),
        )

        // A selection turns the toolbar into a bulk-action bar, in place. A
        // separate floating bar (the web's choice) covers the rows it acts on.
        SelectionActions(state, onEvent)

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
        ) {
            if (state.viewer.canPost) {
                ZillitButton(
                    text = "New folder",
                    onClick = onNewFolder,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
    }
}

@Composable
private fun Breadcrumb(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = "Library",
            onClick = { onEvent(DocDistEvent.OpenFolder(null)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Home,
        )
        state.breadcrumb.forEach { folder ->
            ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
            ZillitButton(
                text = folder.name,
                onClick = { onEvent(DocDistEvent.OpenFolder(folder.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

private fun documentsTitle(state: DocDistUiState): String {
    val loaded = state.documents.size
    // "of N" only once there is more than is on screen — "12 of 12" reads as a
    // limit that is not one.
    return if (state.totalDocuments > loaded) {
        "Documents · $loaded of ${state.totalDocuments}"
    } else {
        "Documents · $loaded"
    }
}

/** A selection turns the toolbar into a bulk-action bar, in place. */
@Composable
private fun SelectionActions(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val selected = state.selectionCount
    if (selected == 0) return

    ZillitText(
        text = "$selected selected",
        style = ZillitTheme.typography.label,
        color = ZillitTheme.colors.textSecondary,
    )
    ZillitButton(
        text = "Clear",
        onClick = { onEvent(DocDistEvent.ClearSelection) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
    if (!state.viewer.canPost) return
    ZillitButton(
        text = "Move",
        onClick = { onEvent(DocDistEvent.OpenMove) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
    )
    // Only documents can be distributed or published; a folder in the
    // selection is not a thing to send, so both buttons follow the documents.
    if (state.selectedDocumentIds.isNotEmpty()) {
        ZillitButton(
            text = "Publish",
            onClick = { onEvent(DocDistEvent.OpenPublish) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = "Distribute",
            onClick = { onEvent(DocDistEvent.Compose) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
        )
    }
}

private fun folderColumns(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
): List<TableColumn<LibraryFolder>> = buildList {
    if (state.viewer.canPost) {
        add(
            TableColumn(
                header = "",
                width = ColumnWidth.Fixed(CHECK_COLUMN.dp),
                cell = { folder ->
                    ZillitCheckbox(
                        checked = folder.id in state.selectedFolderIds,
                        onCheckedChange = { onEvent(DocDistEvent.ToggleFolder(folder.id)) },
                    )
                },
            ),
        )
    }
    add(
        TableColumn(
            header = "Name",
            width = ColumnWidth.Weight(NAME_WEIGHT),
            cell = { folder ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.zillit.desktop.core.designsystem.component.ZillitIcon(
                        icon = ZillitIcons.Grid,
                        tint = ZillitTheme.colors.accent,
                    )
                    ZillitText(text = folder.name, maxLines = 1)
                }
            },
        ),
    )
    add(textColumn("Date", ColumnWidth.Fixed(DATE_COLUMN.dp), muted = true) {
        // The folder's production date, not a created timestamp — see
        // `LibraryFolder.folderDate`. There is no created stamp on the wire.
        it.folderDate.ifBlank { "—" }
    })
    if (state.viewer.canPost) {
        add(
            TableColumn(
                header = "",
                width = ColumnWidth.Fixed(ACTIONS_COLUMN.dp),
                cell = { folder ->
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Delete ${folder.name}",
                        onClick = { onEvent(DocDistEvent.DeleteFolder(folder.id)) },
                        tint = ZillitTheme.colors.danger,
                    )
                },
            ),
        )
    }
}

@Suppress("LongMethod") // A table of columns; splitting it separates each from its width.
private fun documentColumns(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
): List<TableColumn<LibraryDocument>> = buildList {
    add(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(CHECK_COLUMN.dp),
            cell = { document ->
                ZillitCheckbox(
                    checked = document.id in state.selectedDocumentIds,
                    onCheckedChange = { onEvent(DocDistEvent.ToggleDocument(document.id)) },
                )
            },
        ),
    )
    add(
        TableColumn(
            header = "Name",
            width = ColumnWidth.Weight(NAME_WEIGHT),
            cell = { document ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The extension badge rather than a per-kind icon: it is
                    // the same component mail uses for its attachments, so a
                    // PDF looks like a PDF everywhere in the app.
                    ZillitFileBadge(fileName = document.name)
                    ZillitText(text = document.name, maxLines = 1)
                }
            },
        ),
    )
    add(textColumn("Date", ColumnWidth.Fixed(DATE_COLUMN.dp), muted = true) {
        it.documentDate.ifBlank { "—" }
    })
    add(textColumn("Size", ColumnWidth.Fixed(SIZE_COLUMN.dp), numeric = true) {
        formatBytes(it.sizeBytes)
    })
    // No "uploaded by" column: this service records no uploader on a document
    // — the web's library shows none for the same reason. A column that can
    // never fill is worse than one that is not there.
    add(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(ACTIONS_COLUMN.dp),
            cell = { document ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    if (state.viewer.canDownload) {
                        ZillitIconButton(
                            icon = ZillitIcons.Download,
                            contentDescription = "Download ${document.name}",
                            onClick = { onEvent(DocDistEvent.DownloadDocument(document.id)) },
                        )
                    }
                    if (state.viewer.canPost) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Delete ${document.name}",
                            onClick = { onEvent(DocDistEvent.DeleteDocument(document.id)) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            },
        ),
    )
}

/**
 * "Move items": pick a destination folder for whatever is ticked.
 *
 * The destination list is indented to show the tree, and leaves out the
 * folders being moved along with everything under them — dropping a folder
 * into its own subtree detaches that branch from the root, and the documents
 * inside it stop being reachable from anywhere.
 */
@Composable
private fun MoveItemsDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val move = state.moveTarget ?: return
    val destinations = state.moveDestinations()
    val folders = state.selectedFolderIds.size
    val documents = state.selectedDocumentIds.size

    ZillitDialogShell(
        title = "Move items",
        subtitle = listOfNotNull(
            "$folders folder".plural(folders),
            "$documents file".plural(documents),
        ).joinToString(" · ").ifBlank { "Nothing selected" },
        visible = true,
        onDismiss = { onEvent(DocDistEvent.CloseMove) },
        icon = ZillitIcons.Grid,
    ) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().height(MOVE_LIST_HEIGHT.dp),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            DestinationRow(
                label = "Library root",
                indent = 0,
                selected = move.destinationId == null,
                onClick = { onEvent(DocDistEvent.ChooseMoveDestination(null)) },
            )
            destinations.forEach { destination ->
                DestinationRow(
                    label = destination.name,
                    indent = destination.depth + 1,
                    selected = move.destinationId == destination.id,
                    onClick = { onEvent(DocDistEvent.ChooseMoveDestination(destination.id)) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseMove) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Move here",
                onClick = { onEvent(DocDistEvent.ConfirmMove) },
                loading = move.saving,
                enabled = !move.saving && state.selectionCount > 0,
            )
        }
    }
}

@Composable
private fun DestinationRow(
    label: String,
    indent: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ZillitButton(
        text = "${"    ".repeat(indent)}$label",
        onClick = onClick,
        variant = if (selected) ButtonVariant.Secondary else ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "1 folder", "3 folders", and nothing at all for none. */
private fun String.plural(count: Int): String? = when (count) {
    0 -> null
    1 -> this
    else -> this + "s"
}

@Composable
private fun NewFolderDialog(
    visible: Boolean,
    parent: LibraryFolder?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember(visible) { mutableStateOf("") }

    ZillitDialogShell(
        title = "New folder",
        subtitle = parent?.let { "Inside ${it.name}" } ?: "At the top of the library",
        visible = visible,
        onDismiss = onDismiss,
        icon = ZillitIcons.Add,
    ) {
        ZillitTextField(
            value = name,
            onValueChange = { name = it },
            label = "Folder name",
            placeholder = "Call sheets",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Create",
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            )
        }
    }
}

/**
 * The confirmation for anything that cannot be undone.
 *
 * One dialog driven by [DocDistPrompt] rather than one per action: the copy
 * differs, the shape never does, and a dialog per action is how two of them end
 * up with different button orders.
 */
@Composable
fun DocDistPromptDialog(prompt: DocDistPrompt?, onEvent: (DocDistEvent) -> Unit) {
    ZillitDialogShell(
        title = prompt?.title.orEmpty(),
        visible = prompt != null,
        onDismiss = { onEvent(DocDistEvent.DismissPrompt) },
        icon = ZillitIcons.Warning,
    ) {
        ZillitText(text = prompt?.message.orEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = prompt?.confirmLabel.orEmpty(),
                onClick = { onEvent(DocDistEvent.ConfirmPrompt) },
                variant = ButtonVariant.Danger,
            )
        }
    }
}

/** The name column takes three shares of what the fixed columns leave. */
private const val NAME_WEIGHT = 3f
private const val SEARCH_WIDTH = 280
private const val SORT_WIDTH = 180
private const val DATE_COLUMN = 130
private const val SIZE_COLUMN = 100
private const val ACTIONS_COLUMN = 96
private const val CHECK_COLUMN = 44

private const val MOVE_LIST_HEIGHT = 280
