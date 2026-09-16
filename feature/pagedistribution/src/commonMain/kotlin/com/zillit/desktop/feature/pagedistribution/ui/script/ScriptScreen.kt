// One composable per piece of the page; the header and the search drawer are long by nature.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.pagedistribution.ui.script

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
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
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.pdfFileDrop
import com.zillit.desktop.feature.pagedistribution.ui.tint
import kotlinx.coroutines.delay

/**
 * Script & Pages Distribution — the web's `ScriptDistribution.jsx` +
 * `RenderScript.jsx`: a header with the accent bar, the two tabs wearing
 * their unread chips (Full Script / Pages), the ribbon cards of the current
 * script, the scene-folder grid with its search bar, the search drawer
 * sliding in from the right, the paperclip that uploads, and a drop zone
 * over the whole page. Every dialog it opens is in [ScriptDialogs].
 *
 * Same state and events as the other two distribution tools — only the face
 * differs, which is why this is a screen over the shared engine and not a
 * module of its own.
 */
@Composable
fun ScriptScreen(
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
            ScriptHeader(state, onEvent)
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
                        text = "Records of deleted and replaced documents. " +
                            "They can be viewed and downloaded, not changed.",
                        tone = StatusTone.Progress,
                        icon = ZillitIcons.Info,
                    )
                }
                // The tab chips are the web's `badges` prop — live only (`routePathname != history`).
                ZillitTabStrip(
                    tabs = state.tool.tabs.map { tab ->
                        ZillitTab(tab.key, tab.label, count = if (live) state.tabUnread[tab.key] ?: 0 else 0)
                    },
                    activeId = state.activeTabKey,
                    onSelect = { onEvent(DistributionEvent.SelectTab(it)) },
                )
                if (state.isFolderTab) {
                    // The web's search strip sits above the grid on the live Pages tab only.
                    if (live) ScriptSearchBar(state, onEvent, inDrawer = false)
                    ScriptFolderGrid(state, onEvent)
                } else {
                    ScriptList(state, onEvent, resolveUser)
                }
            }
        }
        DropOverlay(visible = dropHover)
        ScriptSearchDrawer(state, onEvent)
        ScriptDialogs(state, onEvent, resolveUser)
    }
}

/** The web's `FilmHeader`: accent bar, title (+ " History"), and the actions at the right. */
@Composable
private fun ScriptHeader(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val live = state.mode == ListMode.Live
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
                        text = state.tool.title,
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
                        "The current full script, and revised pages filed by scene. " +
                            "Open a scene to view, download or publish its pages."
                    } else {
                        "Replaced scripts and deleted pages, kept for the record."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
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
                loading = state.loading && state.openFolder == null,
            )
            if (live) {
                // The web's paperclip: on Full Script with a script up, this is a replace.
                val replacing = !state.isFolderTab && state.documents.isNotEmpty()
                ZillitButton(
                    text = when {
                        replacing -> "Replace script"
                        state.isFolderTab -> "Upload page"
                        else -> "Upload script"
                    },
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    leadingIcon = ZillitIcons.Paperclip,
                    loading = state.busy && state.upload == null,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

// Full Script ------------------------------------------------------------------

/** The web's `renderFullScriptList`: ribbon cards, oldest first. */
@Composable
private fun ScriptList(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val live = state.mode == ListMode.Live
    val rows = state.sortedDocuments
    when {
        state.loading && rows.isEmpty() -> SkeletonList()
        rows.isEmpty() -> ZillitEmptyState(
            title = "No data found",
            message = if (live) {
                "Upload the full script as a PDF — the crew sees one current copy, " +
                    "and every replacement keeps the old one in the history."
            } else {
                "Replaced scripts will be listed here."
            },
            icon = ZillitIcons.File,
            action = if (live) {
                {
                    ZillitButton(
                        text = "Upload script",
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
                ScriptDocumentCard(state, document, onEvent, resolveUser)
            }
        }
    }
}

// Pages ------------------------------------------------------------------------

/**
 * The web's search strip: scene number (debounced 500 ms as typed), the
 * episode on television, and a colour — a colour excludes the scene and
 * the scene excludes the colour, which the view model enforces. The same
 * strip sits inside the search drawer.
 */
@Composable
internal fun ScriptSearchBar(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit, inDrawer: Boolean) {
    val colors = ZillitTheme.colors
    // The web's `debouncedHandleSearch`: typing runs the search half a second
    // after the last key; emptying every field clears it. The page's strip
    // owns the timer — the drawer's copy mirrors the same state and would
    // otherwise fire the search twice.
    if (!inDrawer) {
        LaunchedEffect(state.searchScene, state.searchEpisode) {
            val typed = state.searchScene.isNotBlank() || state.searchEpisode.isNotBlank()
            when {
                typed -> {
                    delay(SEARCH_DEBOUNCE_MS)
                    onEvent(DistributionEvent.RunSearch)
                }
                !state.isSearching && state.searchResults != null -> onEvent(DistributionEvent.ClearSearch)
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (inDrawer) colors.surfaceSunken else colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = state.searchScene,
            onValueChange = { onEvent(DistributionEvent.SearchChanged(scene = it)) },
            placeholder = "Search by scene number",
            leadingIcon = ZillitIcons.Search,
            modifier = Modifier.weight(1f),
            onImeAction = { onEvent(DistributionEvent.RunSearch) },
        )
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = state.searchEpisode,
                onValueChange = { onEvent(DistributionEvent.SearchChanged(episode = it)) },
                placeholder = "Search by episode",
                modifier = Modifier.width(EPISODE_WIDTH),
                onImeAction = { onEvent(DistributionEvent.RunSearch) },
            )
        }
        Row(
            modifier = Modifier.width(COLOUR_WIDTH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            state.searchColour?.let { colour ->
                Box(
                    Modifier
                        .size(SWATCH_DOT)
                        .clip(CircleShape)
                        .background(swatchColour(colour))
                        .border(1.dp, colors.borderStrong, CircleShape),
                )
            }
            ZillitSelect(
                value = state.searchColour,
                options = listOf<PageColour?>(null) + PageColour.entries,
                onSelect = { onEvent(DistributionEvent.SearchColour(it)) },
                label = { it?.label ?: "Search by colour" },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.isSearching || state.searchResults != null) {
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(DistributionEvent.ClearSearch) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The scene folders — the web's `renderPageList` grid, three across at its width. */
@Composable
private fun ScriptFolderGrid(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val live = state.mode == ListMode.Live
    when {
        state.loading && state.folders.isEmpty() -> SkeletonGrid()
        state.folders.isEmpty() -> ZillitEmptyState(
            title = if (live) "No data found" else "Nothing in the history",
            message = if (live) {
                "Upload the first revised page — it is filed under the scene number you give it."
            } else {
                "Deleted pages will be listed here."
            },
            icon = ZillitIcons.Folder,
            action = if (live) {
                {
                    ZillitButton(
                        text = "Upload page",
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
            // (seen live 2026-09-15 on schedule pages — two rows, no `_id`,
            // no `revision_date`), and a LazyGrid throws on a repeated key.
            itemsIndexed(
                state.folders,
                key = { index, folder -> "${folder.id}|${folder.key}|${folder.revisionDateMs}|$index" },
            ) { _, folder ->
                SceneFolderCard(
                    scene = folder.key,
                    uploadedMs = folder.createdMs,
                    colour = "",
                    deleted = folder.deleted,
                    unread = if (live) state.folderUnread[folder.key] ?: 0 else 0,
                    onClick = { onEvent(DistributionEvent.OpenFolder(folder.key)) },
                )
            }
        }
    }
}

/**
 * One scene folder: the glyph, "Scene No", when it was started, and its
 * unread count — the web's antd `Card` with the folder image and `Badge`.
 * Lifts and warms its border on hover, as the web's `.ant-card:hover` does.
 */
@Composable
internal fun SceneFolderCard(
    scene: String,
    uploadedMs: Long,
    colour: String,
    deleted: Boolean,
    unread: Int,
    onClick: () -> Unit,
    pageDateMs: Long = 0L,
    pages: Int = 0,
) {
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
            .background(tint(colour, colors.surface))
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
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = "Scene No :",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
                ZillitText(
                    text = scene.ifBlank { "—" },
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            ZillitText(
                text = if (pages > 0) {
                    "$pages page${if (pages == 1) "" else "s"} match"
                } else {
                    "Uploaded On : ${DistributionDates.dateTime(uploadedMs)}"
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (pageDateMs > 0) {
                ZillitText(
                    text = "Page Date : ${DistributionDates.date(pageDateMs)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            if (deleted) ZillitStatusPill(label = "Deleted", tone = StatusTone.Rejected)
        }
        if (unread > 0) {
            UnreadBadge(
                count = unread,
                modifier = Modifier.align(Alignment.TopEnd).padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/** The red count in the card's corner — antd's `Badge`, capped at 99+. */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
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

// Search drawer ------------------------------------------------------------------

/**
 * The web's `SearchAndDisplayDrawer`: a 690-wide panel from the right that
 * opens on the first answer, repeats the search strip, and lists the matches
 * grouped by scene as folder cards — "View Details" opens the scene. Open
 * exactly while there are results to show; closing clears the search.
 */
@Composable
private fun ScriptSearchDrawer(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val results = state.searchResults
    val visible = results != null && state.isFolderTab && state.openFolder == null
    val colors = ZillitTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(DRAWER_ENTER_MS)),
        exit = fadeOut(tween(DRAWER_EXIT_MS)),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = SCRIM))
                .pointerInput(Unit) { detectTapGestures { onEvent(DistributionEvent.ClearSearch) } },
        ) {
            val panelWidth = if (maxWidth < DRAWER_WIDTH + DRAWER_GUTTER) maxWidth else DRAWER_WIDTH
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .animateEnterExit(
                            enter = slideInHorizontally(tween(DRAWER_ENTER_MS)) { it },
                            exit = slideOutHorizontally(tween(DRAWER_EXIT_MS)) { it },
                        )
                        .shadow(DRAWER_SHADOW)
                        .background(colors.surface)
                        .swallowPresses(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        ZillitIcon(icon = ZillitIcons.Search, tint = colors.accent, size = FACT_ICON)
                        ZillitText(
                            text = "Search",
                            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = "Close search",
                            onClick = { onEvent(DistributionEvent.ClearSearch) },
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    Column(
                        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        ScriptSearchBar(state, onEvent, inDrawer = true)
                        SearchResultGrid(state, results.orEmpty(), onEvent)
                    }
                }
            }
        }
    }
}

/** The matches grouped by scene — the web's `sceneFolders`, first page's colour and date on the card. */
@Composable
private fun SearchResultGrid(
    state: DistributionUiState,
    results: List<DistDocument>,
    onEvent: (DistributionEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val groups = remember(results) { results.groupBy { it.sceneNumber } }
    Box(
        Modifier
            .fillMaxSize()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitText(
                    text = "Fetching results…",
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }
            groups.isEmpty() -> ZillitEmptyState(
                title = "No data found",
                message = "No pages match this search.",
                icon = ZillitIcons.Search,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                items(groups.entries.toList(), key = { it.key }) { (scene, pages) ->
                    val first = pages.first()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SceneFolderCard(
                            scene = scene,
                            uploadedMs = first.createdMs,
                            colour = first.colour,
                            deleted = first.deleted,
                            unread = state.folderUnread[scene] ?: 0,
                            onClick = { onEvent(DistributionEvent.OpenFolder(scene)) },
                            pageDateMs = first.userSelectedDateMs,
                            pages = pages.size,
                        )
                        ZillitText(
                            text = "View Details",
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.accentText,
                            modifier = Modifier.padding(top = ZillitTheme.spacing.xxs),
                        )
                    }
                }
            }
        }
    }
}

// Skeletons and overlays -----------------------------------------------------------

/** Six grey cards while the first folder list is in flight. */
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

/** Two grey ribbon cards while the script list is in flight. */
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
                ZillitSkeletonBar(Modifier.size(SKELETON_AVATAR), height = SKELETON_AVATAR)
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
                    text = "Drop a PDF to upload it",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = "You will fill in its details next.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** Presses on the drawer panel never reach the scrim beneath it. */
private fun Modifier.swallowPresses(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
        }
    }
}

private val ACCENT_BAR_WIDTH = 4.dp
private val ACCENT_BAR_HEIGHT = 24.dp
private val FOLDER_MIN_WIDTH = 220.dp
private val FOLDER_MIN_HEIGHT = 160.dp
private val FOLDER_GLYPH_TILE = 64.dp
private val FOLDER_GLYPH = 32.dp
private val BADGE_SIZE = 20.dp
private const val BADGE_CAP = 99
private val HOVER_LIFT = 2.dp
private val HOVER_SHADOW = 8.dp
private val REST_SHADOW = 1.dp
private const val HOVER_MILLIS = 180
private const val SKELETON_CARDS = 6
private const val SKELETON_ROWS = 2
private const val SKELETON_TITLE_FRACTION = 0.6f
private const val SKELETON_META_FRACTION = 0.8f
private val SKELETON_META_HEIGHT = 10.dp
private val SKELETON_AVATAR = 48.dp
private val EPISODE_WIDTH = 170.dp
private val COLOUR_WIDTH = 210.dp
private val SWATCH_DOT = 14.dp
private val FACT_ICON = 16.dp
private const val SEARCH_DEBOUNCE_MS = 500L
private val DRAWER_WIDTH = 690.dp
private val DRAWER_GUTTER = 48.dp
private val DRAWER_SHADOW = 24.dp
private const val SCRIM = 0.4f
private const val DRAWER_ENTER_MS = 240
private const val DRAWER_EXIT_MS = 180
