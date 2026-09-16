// One composable per piece of the page; the header and the cards are long by nature.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.pagedistribution.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.dod.Fact
import com.zillit.desktop.feature.pagedistribution.ui.dod.Ribbon
import com.zillit.desktop.feature.pagedistribution.ui.dod.fileName
import com.zillit.desktop.feature.pagedistribution.ui.dod.formatBytes
import com.zillit.desktop.feature.pagedistribution.ui.dod.rememberDodFace
import com.zillit.desktop.feature.pagedistribution.ui.pdfFileDrop
import kotlinx.coroutines.delay

/**
 * Schedule Full & One Line — the web's `scheduleTabs.jsx` +
 * `ScheduleDistributionMain.jsx`: a header, the three tabs wearing their
 * unread chips, the history notice, and per tab either the ribbon cards of
 * the one current schedule (Schedule Full / Schedule One Line) or the
 * scene folders of the Pages tab with their search bar. Every dialog it
 * opens is in [ScheduleDialogs].
 *
 * Same state and events as the other two distribution tools — only the
 * face differs, which is why this is a screen over the shared engine and
 * not a module of its own.
 */
@Composable
fun ScheduleScreen(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    /** A user id shown as "Name (Designation)"; null falls back to the id. */
    resolveUser: (String) -> String?,
) {
    val live = state.mode == ListMode.Live
    var dropHover by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .pdfFileDrop(
                enabled = live && state.upload == null,
                onHover = { dropHover = it },
                onFiles = { onEvent(DistributionEvent.FilesDropped(it)) },
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScheduleHeader(state, onEvent)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to ${state.tool.title}.")
                state.error?.let { message ->
                    ZillitNotice(
                        text = message,
                        tone = StatusTone.Rejected,
                        icon = ZillitIcons.Warning,
                        action = {
                            ZillitButton(
                                text = "Dismiss",
                                onClick = { onEvent(DistributionEvent.DismissError) },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                        },
                    )
                }
                // ZL-17014 — the web's history alert.
                if (!live) {
                    ZillitNotice(
                        text = "Records of deleted messages. Replaced schedules and deleted pages can be " +
                            "viewed and downloaded, not changed.",
                        tone = StatusTone.Progress,
                        icon = ZillitIcons.Info,
                    )
                }
                if (state.isFolderTab) {
                    PagesSearchBar(state, onEvent)
                    PagesFolderGrid(state, onEvent)
                } else {
                    ScheduleDocumentList(state, onEvent, resolveUser)
                }
            }
        }
        DropOverlay(visible = dropHover, folderTab = state.isFolderTab)
        ScheduleDialogs(state, onEvent, resolveUser)
    }
}

/**
 * The web's `FilmHeader` + `TabsComponents`: accent bar, the title (with
 * "History" appended on the history route), the actions at the right, and
 * the tab strip with one unread chip per tab.
 */
@Composable
private fun ScheduleHeader(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val live = state.mode == ListMode.Live
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    top = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.sm,
                ),
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
            HeaderTitle(state, Modifier.weight(1f))
            ZillitButton(
                text = if (live) "History" else "Back to live",
                onClick = { onEvent(DistributionEvent.ToggleHistory) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = if (live) ZillitIcons.Clock else ZillitIcons.ArrowLeft,
            )
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(DistributionEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Reload,
                loading = state.loading && state.openFolder == null && state.searchResults == null,
            )
            if (live) {
                // The web's paperclip: on a single-list tab with a schedule
                // already, the pick is a replace — the engine decides.
                val replaces = !state.isFolderTab && state.documents.isNotEmpty()
                ZillitButton(
                    text = if (replaces) "Replace PDF" else "Upload PDF",
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    leadingIcon = ZillitIcons.Paperclip,
                    loading = state.busy && state.upload == null,
                )
            }
        }
        ZillitTabStrip(
            tabs = state.tool.tabs.map { tab ->
                ZillitTab(id = tab.key, label = tab.label, count = if (live) state.tabUnread[tab.key] ?: 0 else 0)
            },
            activeId = state.activeTabKey,
            onSelect = { onEvent(DistributionEvent.SelectTab(it)) },
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** The title, its History pill and the unread total, over the one-line description. */
@Composable
private fun HeaderTitle(state: DistributionUiState, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val live = state.mode == ListMode.Live
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = if (live) state.tool.title else "${state.tool.title} History",
                style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            if (!live) ZillitStatusPill(label = "History", tone = StatusTone.Neutral)
            val total = state.tabUnread.values.sum()
            if (live && total > 0) {
                ZillitStatusPill(label = "$total unread", tone = StatusTone.Rejected, dot = true)
            }
        }
        ZillitText(
            text = if (live) {
                "The current shooting schedule, its pages by scene, and the one-line schedule — " +
                    "one live copy each, replaced rather than added to."
            } else {
                "Schedules that were replaced and pages that were deleted, kept for the record."
            },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

// Single lists ---------------------------------------------------------------

/** Schedule Full / Schedule One Line — the web's `renderFullScriptList` / `renderOneLineList`. */
@Composable
private fun ScheduleDocumentList(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val live = state.mode == ListMode.Live
    val rows = state.sortedDocuments
    val oneLine = state.activeTabKey == ONE_LINE_TAB
    when {
        state.loading && rows.isEmpty() -> SkeletonList()
        rows.isEmpty() -> ZillitEmptyState(
            title = if (live) "No data found" else "Nothing in the history",
            message = when {
                !live -> "Replaced schedules will be listed here."
                oneLine -> "Upload the one-line schedule PDF — later uploads replace it."
                else -> "Upload the full schedule PDF — later uploads replace it."
            },
            icon = ZillitIcons.File,
            action = if (live) {
                {
                    ZillitButton(
                        text = "Upload PDF",
                        onClick = { onEvent(DistributionEvent.PickPdf()) },
                        leadingIcon = ZillitIcons.Paperclip,
                    )
                }
            } else {
                null
            },
        )
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xxl),
        ) {
            items(rows, key = { it.id }) { document ->
                ScheduleDocumentCard(state, document, onEvent, resolveUser)
            }
        }
    }
}

/**
 * One schedule — the web's `Badge.Ribbon` over an antd `Card`: the
 * "Uploaded on" ribbon, the uploader's face, the episode on television,
 * the schedule (or one-line) date, who uploaded it, and the More button
 * whose popover holds every action.
 */
@Composable
internal fun ScheduleDocumentCard(
    state: DistributionUiState,
    document: DistDocument,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    val uploader = resolveUser(document.createdBy) ?: document.createdBy.ifBlank { "Unknown user" }
    val oneLine = state.activeTabKey == ONE_LINE_TAB
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(REST_SHADOW, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
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
                if (document.replaced) ZillitStatusPill(label = "Replaced", tone = StatusTone.Neutral)
                if (document.deleted) ZillitStatusPill(label = "Deleted", tone = StatusTone.Rejected)
                ScheduleMoreMenu(state, document, onEvent)
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
                if (state.viewer.isTelevision) {
                    ZillitText(
                        text = "Episode ${document.episode.ifBlank { "—" }}",
                        style = ZillitTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                }
                Fact(
                    label = if (oneLine) "One-line date" else "Schedule date",
                    value = DistributionDates.date(document.dateMs).ifBlank { "—" },
                )
                Fact(label = "Uploaded by", value = uploader)
                FileLine(document)
            }
        }
    }
}

/** The file's name and size when the wire carried them — a folder listing carries neither. */
@Composable
internal fun FileLine(document: DistDocument) {
    val name = document.fileName()
    val size = formatBytes(document.attachment?.fileSize)
    if (name == null && size == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.File, tint = ZillitTheme.colors.textMuted, size = FACT_ICON)
        if (name != null) {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (size != null) {
            ZillitText(
                text = if (name != null) "·  $size" else size,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The web's `MorePopoverContent`, entry for entry and in its order: View,
 * Publish to Doc Distribution, Replace (single lists), Delete (pages),
 * Download, Download count, View count. History keeps View and Download
 * only — replaced and deleted documents are not publishable (ZL-20141).
 *
 * Replace and Delete stay visible without the right, as on the web: the
 * press answers by asking an admin for posting rights, or with "only an
 * admin or the uploader can delete", instead of a control that is simply
 * not there.
 */
@Composable
internal fun ScheduleMoreMenu(
    state: DistributionUiState,
    document: DistDocument,
    onEvent: (DistributionEvent) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val live = state.mode == ListMode.Live
    val single = state.activeTab.kind is TabKind.Single
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
            if (live && single) {
                add(
                    ZillitMenuEntry.Action("Replace", ZillitIcons.Upload, ZillitMenuTone.Approve) {
                        onEvent(DistributionEvent.PickPdf(replaces = document))
                    },
                )
            }
            if (live && !single) {
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

// Pages ----------------------------------------------------------------------

/**
 * The Pages tab's search bar — the web's scene search, the episode search
 * on television (ZL-16321) and the colour select, shown once there are
 * folders to search (search stays on in history, ZL-15658). Typing runs
 * the search after the web's 500 ms debounce; clearing every field
 * restores the folder list.
 */
@Composable
private fun PagesSearchBar(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    if (state.folders.isEmpty() && state.searchResults == null) return
    val colors = ZillitTheme.colors
    val typed = state.searchScene to state.searchEpisode
    LaunchedEffect(typed) {
        val (scene, episode) = typed
        if (scene.isBlank() && episode.isBlank()) {
            if (state.searchResults != null && state.searchColour == null) onEvent(DistributionEvent.ClearSearch)
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        onEvent(DistributionEvent.RunSearch)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = state.searchScene,
            onValueChange = { onEvent(DistributionEvent.SearchChanged(scene = it)) },
            placeholder = "Search by scene number",
            leadingIcon = ZillitIcons.Search,
            modifier = Modifier.weight(1f),
        )
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = state.searchEpisode,
                onValueChange = { onEvent(DistributionEvent.SearchChanged(episode = it)) },
                placeholder = "Search by episode",
                modifier = Modifier.weight(1f),
            )
        }
        ColourSelect(
            value = state.searchColour,
            onSelect = { onEvent(DistributionEvent.SearchColour(it)) },
            modifier = Modifier.weight(1f),
        )
        if (state.isSearching || state.searchResults != null) {
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(DistributionEvent.ClearSearch) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Close,
            )
        }
    }
}

/** The web's "Search by Color" select — any colour, or one of the eleven, drawn in its own hue. */
@Composable
internal fun ColourSelect(value: PageColour?, onSelect: (PageColour?) -> Unit, modifier: Modifier = Modifier) {
    ZillitSelect(
        value = value,
        options = listOf<PageColour?>(null) + PageColour.entries,
        onSelect = onSelect,
        label = { it?.label ?: "Search by colour" },
        modifier = modifier,
    )
}

/** The folder cards — the web's `renderPageList` grid, three across at its width. */
@Composable
private fun PagesFolderGrid(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val live = state.mode == ListMode.Live
    when {
        state.loading && state.folders.isEmpty() && state.searchResults == null -> SkeletonGrid()
        state.folders.isEmpty() -> ZillitEmptyState(
            title = if (live) "No data found" else "Nothing in the history",
            message = if (live) {
                "Upload the first schedule page — it is filed under its scene number."
            } else {
                "Deleted pages will be listed here."
            },
            icon = ZillitIcons.Folder,
            action = if (live) {
                {
                    ZillitButton(
                        text = "Upload PDF",
                        onClick = { onEvent(DistributionEvent.PickPdf()) },
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
            // The index rides in the key: the service can list one scene twice
            // (seen live 2026-09-15 — two rows, no `_id`, no `revision_date`),
            // and a LazyGrid throws on a repeated key, freezing the window.
            itemsIndexed(
                state.folders,
                key = { index, folder -> "${folder.id}|${folder.key}|${folder.revisionDateMs}|$index" },
            ) { _, folder ->
                SceneFolderCard(
                    folder = folder,
                    unread = if (live) state.folderUnread[folder.key] ?: 0 else 0,
                    onClick = { onEvent(DistributionEvent.OpenFolder(folder.key)) },
                )
            }
        }
    }
}

/**
 * One scene's folder: the glyph, "Scene No", the schedule type, when it
 * was started, and its unread count — the web's antd `Card` with the
 * folder image and `Badge`. Lifts and warms its border on hover.
 */
@Composable
internal fun SceneFolderCard(folder: DistFolder, unread: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) HOVER_LIFT else 0.dp, tween(HOVER_MILLIS), label = "lift")
    val elevation by animateDpAsState(if (hovered) HOVER_SHADOW else REST_SHADOW, tween(HOVER_MILLIS), label = "shadow")
    val outline by animateColorAsState(
        targetValue = if (hovered) colors.accent else colors.border,
        animationSpec = tween(HOVER_MILLIS),
        label = "outline",
    )
    Box(
        modifier = Modifier
            .offset(y = -lift)
            .shadow(elevation, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, outline, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .defaultMinSize(minHeight = FOLDER_MIN_HEIGHT),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(FOLDER_GLYPH_TILE)
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = FOLDER_GLYPH)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = "Scene No:",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                ZillitText(
                    text = folder.key.ifBlank { "—" },
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            folder.scheduleType?.let { type ->
                ZillitStatusPill(label = type.scheduleLabel(), tone = type.tone())
            }
            ZillitText(
                text = "Uploaded on\n${DistributionDates.dateTime(folder.createdMs)}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (folder.deleted) ZillitStatusPill(label = "Deleted", tone = StatusTone.Rejected)
        }
        if (unread > 0) {
            UnreadBadge(
                count = unread,
                modifier = Modifier.align(Alignment.TopEnd).padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/** The red count in a card's corner — antd's `Badge`, capped at 99+. */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE)
            .clip(CircleShape)
            .background(ZillitTheme.colors.danger)
            .padding(horizontal = ZillitTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textOnAccent,
            maxLines = 1,
        )
    }
}

/** The web's folder-card and page-card wording for the two page kinds. */
internal fun ScheduleType.scheduleLabel(): String = when (this) {
    ScheduleType.FullSchedulePages -> "Full Schedule Pages"
    ScheduleType.OneLinePages -> "One Line Schedule Pages"
}

private fun ScheduleType.tone(): StatusTone = when (this) {
    ScheduleType.FullSchedulePages -> StatusTone.Progress
    ScheduleType.OneLinePages -> StatusTone.InTransit
}

// Loading and drop -----------------------------------------------------------

/** Six grey cards while the first folder list is in flight — the shape of what is coming. */
@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(SKELETON_CARDS) {
            Column(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                    .padding(ZillitTheme.spacing.lg)
                    .defaultMinSize(minHeight = FOLDER_MIN_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSkeletonBar(Modifier.size(FOLDER_GLYPH_TILE), height = FOLDER_GLYPH_TILE)
                ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION))
                ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_META_FRACTION), height = SKELETON_META_HEIGHT)
            }
        }
    }
}

/** Two grey ribbon cards while the first single list is in flight. */
@Composable
private fun SkeletonList() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        repeat(SKELETON_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                    .padding(ZillitTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSkeletonBar(Modifier.size(CARD_AVATAR), height = CARD_AVATAR)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION))
                    ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_META_FRACTION), height = SKELETON_META_HEIGHT)
                }
            }
        }
    }
}

/** The page under a dragged file — says what letting go does. */
@Composable
private fun DropOverlay(visible: Boolean, folderTab: Boolean) {
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
                    text = "Drop a PDF to upload it",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = if (folderTab) "You will give its scene number next." else "You will date it next.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

internal const val ONE_LINE_TAB = "oneline"
private const val SEARCH_DEBOUNCE_MS = 500L
private val ACCENT_BAR_WIDTH = 4.dp
private val ACCENT_BAR_HEIGHT = 24.dp
private val FOLDER_MIN_WIDTH = 220.dp
private val FOLDER_MIN_HEIGHT = 180.dp
private val FOLDER_GLYPH_TILE = 64.dp
private val FOLDER_GLYPH = 32.dp
private val BADGE_SIZE = 20.dp
private const val BADGE_CAP = 99
private val CARD_AVATAR = 48.dp
private val FACT_ICON = 14.dp
private val HOVER_LIFT = 2.dp
private val HOVER_SHADOW = 8.dp
private val REST_SHADOW = 1.dp
private const val HOVER_MILLIS = 180
private const val SKELETON_CARDS = 6
private const val SKELETON_ROWS = 2
private const val SKELETON_TITLE_FRACTION = 0.6f
private const val SKELETON_META_FRACTION = 0.8f
private val SKELETON_META_HEIGHT = 10.dp
