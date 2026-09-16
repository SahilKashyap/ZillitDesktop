// The board page; every dialog it opens is in ContinuityDialogs.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.continuity.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityTab

/**
 * The continuity board — the web's `Continuity.jsx` page: the two tabs
 * wearing their unread, the "Continuity scene(s) from …" card with its
 * search and note line, the scene folders, the paperclip that uploads, and
 * a drop zone over the whole page on My Department.
 */
@Composable
fun ContinuityScreen(
    state: ContinuityUiState,
    onEvent: (ContinuityEvent) -> Unit,
    /** A stored file decoded — the thumbnail for cards, the full image for the viewer. */
    loadImage: LoadImage,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
    /** A PDF's pages rendered for the in-app viewer; null or empty falls back to "open outside". */
    loadPdfPages: suspend (ContinuityAttachment) -> List<ImageBitmap>? = { null },
) {
    val colors = ZillitTheme.colors
    val mine = state.tab == ContinuityTab.MyDepartment
    var dropHover by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .continuityFileDrop(
                enabled = mine && state.editor == null && state.open == null,
                onHover = { dropHover = it },
                onFiles = { onEvent(ContinuityEvent.FilesPicked(it)) },
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(state, onEvent)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Continuity tool.")
                state.error?.let { message ->
                    ZillitNotice(
                        text = message,
                        tone = StatusTone.Rejected,
                        icon = ZillitIcons.Warning,
                        action = {
                            ZillitButton(
                                text = "Dismiss",
                                onClick = { onEvent(ContinuityEvent.DismissError) },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                        },
                    )
                }
                ZillitTabStrip(
                    tabs = ContinuityTab.entries.map { tab ->
                        ZillitTab(tab.wireLabel, tab.label, count = state.unread.tab(tab))
                    },
                    activeId = state.tab.wireLabel,
                    onSelect = { id ->
                        onEvent(ContinuityEvent.SelectTab(ContinuityTab.entries.first { it.wireLabel == id }))
                    },
                )
                BoardCard(state, onEvent)
            }
        }
        DropOverlay(visible = dropHover)
        ContinuityDialogs(state, onEvent, loadImage, resolveUser, formatDate, loadPdfPages)
    }
}

/** The accent bar, the title with the unread total, and the actions at the right. */
@Composable
private fun Header(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val mine = state.tab == ContinuityTab.MyDepartment
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                Modifier
                    .width(ACCENT_BAR_WIDTH)
                    .height(ACCENT_BAR_HEIGHT)
                    .clip(ZillitTheme.shapes.small)
                    .background(colors.accent),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "Continuity",
                        style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    val total = state.unread.tabs.values.sum()
                    if (total > 0) ZillitStatusPill(label = "$total unread", tone = StatusTone.Rejected, dot = true)
                }
                ZillitText(
                    text = "Photos, videos and documents by scene — yours in My Department, " +
                        "forwarded ones in All Departments.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(ContinuityEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Reload,
                loading = state.loading && state.open == null && state.pick == null,
            )
            // The web's paperclip lives on My Department only — All is what was forwarded.
            if (mine) UploadMenu(onEvent, loading = state.busy && state.editor == null)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** The paperclip's three choices — photos, videos, documents — as a menu under one button. */
@Composable
internal fun UploadMenu(
    onEvent: (ContinuityEvent) -> Unit,
    loading: Boolean = false,
    text: String = "Upload",
    variant: ButtonVariant = ButtonVariant.Primary,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = text,
            onClick = { open = true },
            variant = variant,
            leadingIcon = ZillitIcons.Paperclip,
            loading = loading,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = listOf(
                ZillitMenuEntry.Action("Photos", ZillitIcons.Photo, ZillitMenuTone.Primary) {
                    open = false
                    onEvent(ContinuityEvent.PickFiles(PickKind.Photos))
                },
                ZillitMenuEntry.Action("Videos", ZillitIcons.Play, ZillitMenuTone.Neutral) {
                    open = false
                    onEvent(ContinuityEvent.PickFiles(PickKind.Videos))
                },
                ZillitMenuEntry.Action("Documents", ZillitIcons.File, ZillitMenuTone.Neutral) {
                    open = false
                    onEvent(ContinuityEvent.PickFiles(PickKind.Documents))
                },
            ),
        )
    }
}

/**
 * The web's antd `Card`: "Continuity scene(s) from My Department" with the
 * scene search at the right, the note line, then the folder grid.
 */
@Composable
private fun BoardCard(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitText(
                    text = "Continuity scene(s) from ${state.tab.label}",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                ZillitSearchField(
                    value = state.folderQuery,
                    onValueChange = { onEvent(ContinuityEvent.SearchFolders(it)) },
                    placeholder = "Search By Scene No",
                    modifier = Modifier.width(SEARCH_WIDTH),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = "Note :",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
                ZillitText(text = state.tab.note, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg)) { FolderGrid(state, onEvent) }
    }
}

/** The scene folders — the web's `Card.Grid` tiles, "Scene No - N" with the unread count and a chevron. */
@Composable
private fun FolderGrid(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    when {
        state.loading && state.folders.isEmpty() -> SkeletonFolders()
        state.shownFolders.isEmpty() -> ZillitEmptyState(
            title = if (state.folderQuery.isNotBlank()) "No scene matches" else "No scenes available.",
            message = when {
                state.folderQuery.isNotBlank() -> "Try another scene number."
                state.tab == ContinuityTab.MyDepartment -> "Upload a photo, video or document to start a scene folder."
                else -> "Scenes forwarded from ‘My Department’ folders will appear here."
            },
            icon = ZillitIcons.Folder,
            action = if (state.tab == ContinuityTab.MyDepartment && state.folderQuery.isBlank()) {
                {
                    ZillitButton(
                        text = "Upload",
                        onClick = { onEvent(ContinuityEvent.PickFiles(PickKind.Photos)) },
                        leadingIcon = ZillitIcons.Paperclip,
                    )
                }
            } else {
                null
            },
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xxl),
        ) {
            items(state.shownFolders, key = { it }) { folder ->
                FolderCard(
                    sceneFolder = folder,
                    unread = state.unread.folder(state.tab, folder),
                    onClick = { onEvent(ContinuityEvent.OpenFolder(folder)) },
                )
            }
        }
    }
}

@Composable
internal fun FolderCard(sceneFolder: String, unread: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    HoverCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .width(FOLDER_GLYPH_TILE)
                    .height(FOLDER_GLYPH_TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = FOLDER_GLYPH)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = "Scene No - $sceneFolder",
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                ZillitText(
                    text = if (unread > 0) "$unread new" else "Open",
                    style = ZillitTheme.typography.bodySmall,
                    color = if (unread > 0) colors.danger else colors.textMuted,
                )
            }
            UnreadBadge(count = unread)
            ZillitIcon(icon = ZillitIcons.ChevronRight, tint = colors.textMuted, size = CHEVRON)
        }
    }
}

@Composable
private fun SkeletonFolders() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(SKELETON_CARDS) {
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                    .padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitSkeletonBar(Modifier.width(FOLDER_GLYPH_TILE), height = FOLDER_GLYPH_TILE)
                ZillitSkeletonBar(Modifier.weight(1f))
            }
        }
    }
}

/** The page under a dragged file — says what letting go does. */
@Composable
private fun DropOverlay(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val colors = ZillitTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .padding(ZillitTheme.spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, colors.accent, ZillitTheme.shapes.large)
                    .background(colors.accentSoft, ZillitTheme.shapes.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Upload, tint = colors.accent, size = FOLDER_GLYPH)
                Spacer(Modifier.height(ZillitTheme.spacing.sm))
                ZillitText(
                    text = "Drop photos, videos or documents to upload",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = "You will add the scene details next.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

private val ACCENT_BAR_WIDTH = 4.dp
private val ACCENT_BAR_HEIGHT = 24.dp
private val SEARCH_WIDTH = 260.dp
private val FOLDER_MIN_WIDTH = 240.dp
private val FOLDER_GLYPH_TILE = 40.dp
private val FOLDER_GLYPH = 20.dp
private val CHEVRON = 16.dp
private const val SKELETON_CARDS = 8
