package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.WATERMARK_SUPPORTED_LABEL
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.LibraryRow
import com.zillit.desktop.feature.documentdistribution.ui.LibraryRowGroup
import com.zillit.desktop.feature.documentdistribution.ui.LibraryView

/**
 * The library: the web's `Library.jsx` — toolbar, folder path, a
 * date-grouped listing of folders and files in list or grid form, a bulk
 * Actions menu over the selection, and drop-to-upload inside a folder.
 */
@Composable
fun LibraryPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        FixedPage(
            modifier = Modifier.externalFileDrop(
                enabled = state.currentFolder != null || state.composer.open,
                onHover = { onEvent(DocDistEvent.DragHover(it)) },
                onFiles = { onEvent(DocDistEvent.DropFiles(it)) },
            ),
        ) {
            if (!state.infoBannerDismissed) InfoBanner(onEvent)
            LibraryToolbar(state, onEvent)
            state.currentFolder?.let { folder ->
                FolderPath(state, onEvent)
                FolderHeader(folder, state, onEvent)
            }
            if (state.selectionCount > 0) SelectionBar(state, onEvent)
            ListingCard(state, onEvent, Modifier.weight(1f))
        }

        state.upload?.let { progress ->
            FloatingCard(Modifier.align(Alignment.BottomEnd).padding(ZillitTheme.spacing.xl)) {
                ZillitSpinner(size = 16.dp)
                Column {
                    ZillitText(text = progress.title, style = ZillitTheme.typography.label)
                    if (progress.name.isNotBlank()) {
                        ZillitText(
                            text = progress.name,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        state.busy?.let { message ->
            FloatingCard(Modifier.align(Alignment.BottomEnd).padding(ZillitTheme.spacing.xl)) {
                ZillitSpinner(size = 16.dp)
                ZillitText(text = message, style = ZillitTheme.typography.label)
            }
        }
    }

    FolderEditorDialog(state, onEvent)
    MoveItemsDialog(state, onEvent)
    PublishDialog(state, onEvent)
    FilePreviewDialog(state, onEvent)
    WatermarkDownloadDialog(state, onEvent)
    WatermarkBatchDialog(state, onEvent)
    DocumentPickerDialog(state, onEvent)
}

/** A small raised card pinned to a corner — upload progress, "preparing". */
@Composable
internal fun FloatingCard(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = ZillitTheme.colors
    Row(
        modifier = modifier
            .shadow(8.dp, ZillitTheme.shapes.large)
            .clip(ZillitTheme.shapes.large)
            .background(c.surfaceRaised)
            .border(0.5.dp, c.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * "How to use this module" — the web's banner as of ZL-21622: four points,
 * the watermark one included, on a quiet surface with the accent kept for the
 * title and icon. Drawn here rather than as a `ZillitNotice`, which caps its
 * text at three lines and cut the last point off.
 */
@Composable
private fun InfoBanner(onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(c.surface)
            .border(0.5.dp, c.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitIcon(icon = ZillitIcons.Upload, tint = c.accent, size = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(text = str(S.dd_help_title), style = ZillitTheme.typography.label, color = c.accent)
            ZillitText(
                text = str(S.desktop_docdist_info_banner_points),
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            ZillitText(
                text = str(S.desktop_docdist_supported_types, SupportedUploads.LABEL),
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.sync_action_dismiss),
            onClick = { onEvent(DocDistEvent.DismissInfoBanner) },
        )
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun LibraryToolbar(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val canPost = state.viewer.canPost
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(DocDistEvent.Search(it)) },
            placeholder = str(S.dd_search_files_folders),
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )
        ZillitDateField(
            value = state.dateFilter.orEmpty(),
            onValueChange = { onEvent(DocDistEvent.FilterByDate(it)) },
            placeholder = str(S.dd_filter_by_date),
            modifier = Modifier.width(DATE_WIDTH.dp),
        )
        ZillitSelect(
            value = state.sort,
            options = LibrarySort.entries,
            onSelect = { onEvent(DocDistEvent.SortBy(it)) },
            label = { it.label },
            modifier = Modifier.width(SORT_WIDTH.dp),
        )
        ZillitSegmented(
            options = listOf(
                ZillitTab("list", str(S.desktop_drive_view_list)),
                ZillitTab("grid", str(S.desktop_drive_view_grid)),
            ),
            activeId = if (state.view == LibraryView.List) "list" else "grid",
            onSelect = { onEvent(DocDistEvent.SetView(if (it == "grid") LibraryView.Grid else LibraryView.List)) },
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.currentFolder != null) {
                ZillitButton(
                    text = str(S.upload),
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PickAndUpload) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                    enabled = state.upload == null,
                )
            }
            ZillitButton(
                text = str(S.dd_empty_create_cta),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenNewFolder) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            ZillitButton(
                text = str(S.dd_compose_email),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.ComposeBlank) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
            )
        }
    }
}

@Composable
private fun FolderPath(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ArrowLeft,
            contentDescription = str(S.desktop_docdist_up_one_folder),
            onClick = { onEvent(DocDistEvent.GoUp) },
        )
        ZillitIconButton(
            icon = ZillitIcons.Home,
            contentDescription = str(S.desktop_docdist_back_to_root),
            onClick = { onEvent(DocDistEvent.OpenFolder(null)) },
        )
        state.breadcrumb.forEachIndexed { index, folder ->
            ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
            val last = index == state.breadcrumb.lastIndex
            ZillitButton(
                text = folder.name,
                onClick = { onEvent(DocDistEvent.OpenFolder(folder.id)) },
                variant = if (last) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The open folder's name, description and its two whole-folder actions. */
@Composable
private fun FolderHeader(folder: LibraryFolder, state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(c.accentSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FolderGlyph(size = 36.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(text = folder.name, style = ZillitTheme.typography.titleSmall, maxLines = 1)
            ZillitText(
                text = folder.description.ifBlank { prettyIsoDate(folder.folderDate) },
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
            )
        }
        if (state.documents.isNotEmpty()) {
            ZillitButton(
                text = str(S.publish),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PublishFolder(folder.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.dd_distribute_this_folder),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                    DocDistEvent.DistributeFolder(folder.id),
                ) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
            )
        } else {
            ZillitText(
                text = str(S.dd_upload_to_enable),
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
    }
}

/** "3 selected · Actions ▾ · Clear" — the web's selection bar. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun SelectionBar(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    // The entries stay live without the right and ask for it instead — the
    // flip QA asked for on the phones: a greyed item explains nothing.
    val post = { action: () -> Unit -> gatedClick(state.viewer.canPost, { onEvent(askPost) }, action) }
    val download = { action: () -> Unit -> gatedClick(state.viewer.canDownload, { onEvent(askDownload) }, action) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(c.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.dd_n_selected, state.selectionCount),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.warning,
            modifier = Modifier.weight(1f),
        )
        Box {
            ZillitButton(
                text = str(S.dd_actions),
                onClick = { menuOpen = true },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronDown,
            )
            MenuPopup(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                entries = listOf(
                    MenuEntry(str(S.dd_share_documents), post { onEvent(DocDistEvent.Compose) }, ZillitIcons.Send),
                    MenuEntry(str(S.dd_watermark_share), post { onEvent(DocDistEvent.Compose) }, ZillitIcons.Shield),
                    MenuEntry(
                        str(S.dd_watermark_download),
                        download { onEvent(DocDistEvent.OpenWatermarkBatch) },
                        ZillitIcons.Download,
                    ),
                    MenuEntry(str(S.publish), post { onEvent(DocDistEvent.OpenPublishSelection) }, ZillitIcons.Link),
                    MenuEntry(
                        str(S.dd_action_move),
                        post { onEvent(DocDistEvent.OpenMove) },
                        ZillitIcons.Grid,
                        dividerBefore = true,
                    ),
                    MenuEntry(
                        str(S.delete),
                        post { onEvent(DocDistEvent.ConfirmDeleteSelection) },
                        ZillitIcons.Trash,
                        danger = true,
                        dividerBefore = true,
                    ),
                ),
            )
        }
        ZillitButton(
            text = str(S.dd_action_clear),
            onClick = { onEvent(DocDistEvent.ClearSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

// -- the listing ---------------------------------------------------------------

@Suppress("CyclomaticComplexMethod") // One branch per state the screen can be in.
@Composable
private fun ListingCard(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit, modifier: Modifier) {
    val c = ZillitTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(c.surface)
            .border(0.5.dp, c.border, ZillitTheme.shapes.large)
            .testTag(LIBRARY_LISTING_TAG),
    ) {
        when {
            state.loading && state.documents.isEmpty() && state.folders.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
            state.isEmptyListing && state.hasActiveFilter -> ZillitEmptyState(
                title = str(S.dd_empty_no_results_title),
                message = when {
                    state.search.isNotBlank() && state.dateFilter != null ->
                        str(S.dd_empty_no_results_search_date, state.search.trim())
                    state.search.isNotBlank() -> str(S.dd_empty_no_results_search, state.search.trim())
                    else -> str(S.dd_empty_no_results_date)
                },
                icon = ZillitIcons.Search,
                action = {
                    ZillitButton(
                        text = str(S.dd_empty_clear_filters),
                        onClick = { onEvent(DocDistEvent.ClearFilters) },
                        variant = ButtonVariant.Secondary,
                    )
                },
            )
            state.isEmptyListing -> EmptyLibrary(state, onEvent)
            state.view == LibraryView.List -> ListView(state, onEvent)
            else -> GridView(state, onEvent)
        }

        if (state.dragHover && state.currentFolder != null) DropOverlay(state.currentFolder?.name.orEmpty())
    }
}

@Composable
private fun EmptyLibrary(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val folder = state.currentFolder
    val canPost = state.viewer.canPost
    ZillitEmptyState(
        title = str(if (folder != null) S.dd_empty_folder_title else S.desktop_docdist_library_empty_title),
        message = if (folder != null) {
            str(S.desktop_docdist_empty_folder_message, folder.name, SupportedUploads.LABEL)
        } else {
            str(S.desktop_docdist_empty_library_message)
        },
        icon = if (folder != null) ZillitIcons.Upload else ZillitIcons.Grid,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                if (folder != null) {
                    ZillitButton(
                        text = str(S.dd_empty_upload_cta),
                        onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PickAndUpload) },
                        leadingIcon = ZillitIcons.Upload,
                    )
                }
                ZillitButton(
                    text = str(S.dd_empty_create_cta),
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenNewFolder) },
                    variant = if (folder != null) ButtonVariant.Secondary else ButtonVariant.Primary,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    )
}

@Composable
private fun DropOverlay(folderName: String) {
    val c = ZillitTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.35f)).padding(ZillitTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(ZillitTheme.shapes.large)
                .background(c.surfaceRaised)
                .border(2.dp, c.accent, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(icon = ZillitIcons.Upload, tint = c.accent, size = 40.dp)
            ZillitText(text = str(S.dd_drop_title), style = ZillitTheme.typography.titleMedium)
            ZillitText(text = str(S.dd_drop_subtitle, folderName), color = c.textSecondary)
            ZillitText(
                text = str(S.dd_drop_supported),
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
    }
}

/** Fires [onReach] as the last rows scroll into view — the web's sentinel. */
@Composable
private fun LoadMoreOnEnd(listState: LazyListState, hasMore: Boolean, onReach: () -> Unit) {
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - LOAD_MORE_LEAD } ?: false
        }.collect { nearEnd -> if (nearEnd) onReach() }
    }
}

@Composable
private fun ListView(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val listState = rememberLazyListState()
    LoadMoreOnEnd(listState, state.hasMore) { onEvent(DocDistEvent.LoadMore) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitCheckbox(checked = state.allSelected, onCheckedChange = { onEvent(DocDistEvent.SelectAll(it)) })
            ColumnHeading(str(S.name), Modifier.weight(NAME_WEIGHT))
            ColumnHeading(str(S.date), Modifier.width(DATE_COLUMN.dp))
            ColumnHeading(str(S.description), Modifier.weight(DESCRIPTION_WEIGHT))
            ColumnHeading(str(S.dd_watermark_size), Modifier.width(SIZE_COLUMN.dp))
            Box(Modifier.width(ACTIONS_COLUMN.dp))
        }
        ZillitLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(ZillitTheme.spacing.sm),
        ) {
            state.rows.forEach { group ->
                group.heading?.let { heading ->
                    item(key = "h-${group.key}") { GroupHeading(heading, group) }
                }
                items(group.items, key = { it.id }) { row ->
                    when (row) {
                        is LibraryRow.Folder -> FolderListRow(row.folder, state, onEvent)
                        is LibraryRow.File -> FileListRow(row.document, state, onEvent)
                    }
                }
            }
            if (state.hasMore) item(key = "more") { LoadingMoreRow(state.loadingMore) }
        }
    }
}

@Composable
private fun ColumnHeading(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        modifier = modifier,
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
    )
}

@Composable
private fun GroupHeading(heading: String, group: LibraryRowGroup) {
    val c = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.sm,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Calendar, tint = c.accent, size = 14.dp)
        ZillitText(text = heading, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
        ZillitText(text = "· ${group.countLabel}", style = ZillitTheme.typography.label, color = c.textMuted)
    }
}

@Composable
private fun LoadingMoreRow(loading: Boolean) {
    Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
        if (loading) ZillitSpinner(size = 16.dp)
        else ZillitText(
            text = str(S.desktop_loading_more),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun FolderListRow(folder: LibraryFolder, state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val selected = folder.id in state.selectedFolderIds
    HoverRow(selected = selected, onClick = { onEvent(DocDistEvent.OpenFolder(folder.id)) }) { hovered ->
        ZillitCheckbox(checked = selected, onCheckedChange = { onEvent(DocDistEvent.ToggleFolder(folder.id)) })
        Row(
            modifier = Modifier.weight(NAME_WEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderGlyph(size = 28.dp)
            ZillitText(
                text = folder.name,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The folder's bubble: every unread row filed under it (`Library.jsx:854`).
            ZillitBadge(count = state.unread.folder(folder.id))
        }
        CellText(prettyIsoDate(folder.folderDate), Modifier.width(DATE_COLUMN.dp))
        CellText(folder.description.ifBlank { "—" }, Modifier.weight(DESCRIPTION_WEIGHT))
        CellText("—", Modifier.width(SIZE_COLUMN.dp))
        Box(Modifier.width(ACTIONS_COLUMN.dp)) {
            FolderActions(folder, state, onEvent, visible = hovered || selected)
        }
    }
}

@Composable
private fun FileListRow(document: LibraryDocument, state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val selected = document.id in state.selectedDocumentIds
    HoverRow(selected = selected, onClick = { onEvent(DocDistEvent.OpenDocument(document.id)) }) { hovered ->
        ZillitCheckbox(checked = selected, onCheckedChange = { onEvent(DocDistEvent.ToggleDocument(document.id)) })
        Row(
            modifier = Modifier.weight(NAME_WEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileGlyph(document, size = 28.dp)
            ZillitText(text = document.name, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            // The file's dot: its own unread events (`Library.jsx:929`).
            ZillitBadge(count = state.unread.file(document.id))
        }
        CellText(prettyIsoDate(document.documentDate), Modifier.width(DATE_COLUMN.dp))
        CellText("—", Modifier.weight(DESCRIPTION_WEIGHT))
        CellText(formatBytes(document.sizeBytes), Modifier.width(SIZE_COLUMN.dp))
        Box(Modifier.width(ACTIONS_COLUMN.dp)) {
            FileActions(document, state, onEvent, visible = hovered || selected)
        }
    }
}

@Composable
private fun CellText(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        modifier = modifier,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
    )
}

/**
 * The row's buttons, revealed by alpha rather than composed on hover — a
 * control that only exists while hovered never receives the press.
 */
@Composable
private fun FolderActions(
    folder: LibraryFolder,
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
    visible: Boolean,
) {
    val canPost = state.viewer.canPost
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.alpha(if (visible || menuOpen) 1f else 0.25f),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitTooltip(if (canPost) str(S.dd_distribute_this_folder) else str(S.desktop_no_posting_rights)) {
            ZillitIconButton(
                ZillitIcons.Send,
                str(S.desktop_docdist_distribute_named, folder.name),
                gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.DistributeFolder(folder.id)) },
            )
        }
        ZillitTooltip(if (canPost) str(S.desktop_docdist_publish_folder) else str(S.desktop_no_posting_rights)) {
            ZillitIconButton(
                ZillitIcons.Link,
                str(S.desktop_docdist_publish_named, folder.name),
                gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PublishFolder(folder.id)) },
            )
        }
        Box {
            ZillitIconButton(ZillitIcons.MoreHorizontal, str(S.more), { menuOpen = true })
            MenuPopup(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                // Live without the right, asking for it — the repo's rule.
                entries = listOf(
                    MenuEntry(
                        str(S.edit),
                        gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenEditFolder(folder.id)) },
                        ZillitIcons.Edit,
                    ),
                    MenuEntry(
                        str(S.delete),
                        gatedClick(canPost, { onEvent(askPost) }) {
                            onEvent(DocDistEvent.ConfirmDeleteFolder(folder.id))
                        },
                        ZillitIcons.Trash,
                        danger = true,
                    ),
                ),
                width = 160.dp,
            )
        }
    }
}

@Composable
private fun FileActions(
    document: LibraryDocument,
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
    visible: Boolean,
) {
    val canPost = state.viewer.canPost
    val canDownload = state.viewer.canDownload
    Row(
        modifier = Modifier.alpha(if (visible) 1f else 0.25f),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitTooltip(if (canPost) str(S.desktop_docdist_send_by_email) else str(S.desktop_no_posting_rights)) {
            ZillitIconButton(
                ZillitIcons.Send,
                str(S.desktop_docdist_send_named, document.name),
                gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.DistributeDocument(document.id)) },
            )
        }
        ZillitTooltip(if (canPost) str(S.publish) else str(S.desktop_no_posting_rights)) {
            ZillitIconButton(
                ZillitIcons.Link,
                str(S.desktop_docdist_publish_named, document.name),
                gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PublishDocument(document.id)) },
            )
        }
        ZillitTooltip(
            when {
                !canDownload -> str(S.dd_export_no_rights)
                document.isWatermarkable -> str(S.desktop_docdist_watermark_plus_download)
                else -> str(S.desktop_docdist_watermark_supports, WATERMARK_SUPPORTED_LABEL)
            },
        ) {
            ZillitIconButton(
                ZillitIcons.Shield,
                str(S.desktop_docdist_watermark_named, document.name),
                gatedClick(canDownload, { onEvent(askDownload) }) { onEvent(
                    DocDistEvent.OpenWatermarkDownload(document.id),
                ) },
                enabled = document.isWatermarkable,
            )
        }
        ZillitTooltip(if (canDownload) str(S.download) else str(S.dd_export_no_rights)) {
            ZillitIconButton(
                ZillitIcons.Download,
                str(S.desktop_docdist_download_named, document.name),
                gatedClick(canDownload, { onEvent(askDownload) }) { onEvent(
                    DocDistEvent.DownloadDocument(document.id),
                ) },
            )
        }
        ZillitTooltip(if (canPost) str(S.delete) else str(S.desktop_no_posting_rights)) {
            ZillitIconButton(
                ZillitIcons.Trash,
                str(S.desktop_delete_named, document.name),
                gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.ConfirmDeleteDocument(document.id)) },
                tint = ZillitTheme.colors.danger,
            )
        }
    }
}

// -- grid ---------------------------------------------------------------------

@Composable
private fun GridView(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state.hasMore) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - LOAD_MORE_LEAD } ?: false
        }.collect { nearEnd -> if (nearEnd) onEvent(DocDistEvent.LoadMore) }
    }
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_WIDTH.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        state.rows.forEach { group ->
            group.heading?.let { heading ->
                item(key = "h-${group.key}", span = { GridItemSpan(maxLineSpan) }) { GroupHeading(heading, group) }
            }
            items(group.items, key = { it.id }) { row ->
                when (row) {
                    is LibraryRow.Folder -> GridCard(
                        title = row.folder.name,
                        unread = state.unread.folder(row.id),
                        sub = listOf(row.folder.description, prettyIsoDate(row.folder.folderDate))
                            .filter { it.isNotBlank() && it != "—" }
                            .joinToString(" · "),
                        selected = row.id in state.selectedFolderIds,
                        onOpen = { onEvent(DocDistEvent.OpenFolder(row.id)) },
                        onToggle = { onEvent(DocDistEvent.ToggleFolder(row.id)) },
                        glyph = { FolderGlyph(size = 44.dp) },
                        actions = { visible -> FolderActions(row.folder, state, onEvent, visible) },
                    )
                    is LibraryRow.File -> GridCard(
                        title = row.document.name,
                        unread = state.unread.file(row.id),
                        sub = formatBytes(row.document.sizeBytes) +
                            prettyIsoDate(row.document.documentDate).takeIf { it != "—" }?.let { " · $it" }.orEmpty(),
                        selected = row.id in state.selectedDocumentIds,
                        onOpen = { onEvent(DocDistEvent.OpenDocument(row.id)) },
                        onToggle = { onEvent(DocDistEvent.ToggleDocument(row.id)) },
                        glyph = { FileGlyph(row.document, size = 44.dp) },
                        actions = { visible -> FileActions(row.document, state, onEvent, visible) },
                    )
                }
            }
        }
        if (state.hasMore) item(key = "more", span = { GridItemSpan(maxLineSpan) }) { LoadingMoreRow(
            state.loadingMore,
        ) }
    }
}

@Composable
private fun GridCard(
    title: String,
    sub: String,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    glyph: @Composable () -> Unit,
    actions: @Composable (visible: Boolean) -> Unit,
    unread: Int = 0,
) {
    val c = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) c.surfaceSelected else if (hovered) c.surfaceHover else c.surfaceSunken)
            .border(0.5.dp, if (selected) c.accent else c.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitCheckbox(checked = selected, onCheckedChange = { onToggle() })
            Box(Modifier.weight(1f))
            ZillitBadge(count = unread)
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            glyph()
            ZillitText(text = title, style = ZillitTheme.typography.label, maxLines = 2, textAlign = TextAlign.Center)
            ZillitText(
                text = sub.ifBlank { " " },
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { actions(hovered || selected) }
    }
}

/** Names the listing so a test can find it without guessing. */
const val LIBRARY_LISTING_TAG = "docdist-listing"

private const val NAME_WEIGHT = 3f
private const val DESCRIPTION_WEIGHT = 1.6f
private const val SEARCH_WIDTH = 260
private const val DATE_WIDTH = 170
private const val SORT_WIDTH = 190
private const val DATE_COLUMN = 120
private const val SIZE_COLUMN = 84
private const val ACTIONS_COLUMN = 168
private const val CARD_WIDTH = 188
private const val LOAD_MORE_LEAD = 4

/** What a press without the right turns into. */
internal val askPost = DocDistEvent.RequestRights(RightsKind.Post)
internal val askDownload = DocDistEvent.RequestRights(RightsKind.Download)
