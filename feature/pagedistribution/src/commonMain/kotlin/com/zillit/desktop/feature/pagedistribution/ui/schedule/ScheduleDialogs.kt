// One composable per dialog; the page card and the upload form are long by nature.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.pagedistribution.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.UploadEditor
import com.zillit.desktop.feature.pagedistribution.ui.dod.ColourSwatches
import com.zillit.desktop.feature.pagedistribution.ui.dod.DodCountsDialog
import com.zillit.desktop.feature.pagedistribution.ui.dod.Fact
import com.zillit.desktop.feature.pagedistribution.ui.dod.Ribbon
import com.zillit.desktop.feature.pagedistribution.ui.dod.displayName
import com.zillit.desktop.feature.pagedistribution.ui.dod.formatBytes
import com.zillit.desktop.feature.pagedistribution.ui.dod.rememberDodFace
import com.zillit.desktop.feature.pagedistribution.ui.folderKey
import com.zillit.desktop.feature.pagedistribution.ui.pages.ConfirmDialog
import com.zillit.desktop.feature.pagedistribution.ui.pages.PdfDialog
import com.zillit.desktop.feature.pagedistribution.ui.tint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.LocalDate

/**
 * Every overlay the Schedule Full & One Line page opens: a scene folder's
 * pages, the search drawer, the upload form with the web's two follow-up
 * questions, the PDF viewer, the tallies, and the two confirms. The viewer,
 * the tallies and the confirms are shared with D.O.D; the rest wear the
 * web's schedule face.
 */
@Composable
internal fun ScheduleDialogs(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    // The drawer sits under the folder it opens, as the web's does.
    SearchDrawer(state, onEvent)
    state.openFolder?.let { PagesFolderDialog(state, onEvent, resolveUser) }
    state.upload?.let { ScheduleUploadDialog(state, onEvent) }
    state.pdf?.let { PdfDialog(state, onEvent) }
    state.counts?.let { DodCountsDialog(state, onEvent, resolveUser) }
    state.confirmDelete?.let { document ->
        ConfirmDialog(
            title = "Delete page",
            body = "Are you sure you want to delete \"${document.displayName()}\"? It moves to the history.",
            confirm = "Yes, delete",
            danger = true,
            busy = state.busy,
            onConfirm = { onEvent(DistributionEvent.ConfirmDelete) },
            onDismiss = { onEvent(DistributionEvent.CancelDelete) },
        )
    }
    state.confirmPublish?.let { document ->
        // The web's `handleDistributeToDocDist`: [tool, tab, episode (TV) else scene].
        val path = listOfNotNull(
            state.tool.publishRoot,
            state.activeTab.publishSubFolder,
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
 * A scene folder opened — the web's 800-wide "Pages" modal listing the
 * scene's pages, paged as the reader scrolls (`handleScroll` →
 * `getNextScriptPagesData`). Esc does not close it on the web (ZL-8430);
 * here the scrim and Close do.
 */
@Composable
private fun PagesFolderDialog(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val open = state.openFolder ?: return
    val live = state.mode == ListMode.Live
    val listState = rememberLazyListState()
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
    val count = open.documents.size
    ZillitDialogShell(
        title = "Pages · Scene ${open.folder.key.ifBlank { "—" }}",
        subtitle = listOfNotNull(
            open.folder.scheduleType?.scheduleLabel(),
            when {
                !live -> "Deleted pages"
                count == 0 -> null
                else -> "$count page${if (count == 1) "" else "s"}"
            },
        ).joinToString("  ·  ").ifBlank { null },
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
                    title = "No data found",
                    message = if (live) "Upload a page here to start this scene." else null,
                    icon = ZillitIcons.File,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(open.documents, key = { it.id }) { document ->
                        PageCard(state, document, onEvent, resolveUser)
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
 * One page — the web's `Badge.Ribbon` over an antd `Card` painted the
 * page's revision colour: the "Uploaded on" ribbon, the uploader's face and
 * name, the More pill, then the info rows — episode on television, scene
 * number, page number, page date, schedule type.
 *
 * The colour is the page's own data (white, pink, blue…), so it stays even
 * in the dark theme, as the web's CSS insists; the text on it is the
 * theme's, which the tint keeps legible.
 */
@Composable
internal fun PageCard(
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
                size = CARD_AVATAR,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Fact(label = "Uploaded by", value = uploader)
                if (state.viewer.isTelevision) Fact(label = "Episode", value = document.episode.ifBlank { "—" })
                Fact(label = "Scene number", value = document.sceneNumber.ifBlank { "—" })
                Fact(label = "Page number", value = document.pageNumber.ifBlank { "—" })
                Fact(label = "Page date", value = DistributionDates.date(document.userSelectedDateMs).ifBlank { "—" })
                Fact(label = "Schedule type", value = document.scheduleType?.scheduleLabel() ?: "—")
                FileLine(document)
            }
        }
    }
}

// Search drawer --------------------------------------------------------------

/**
 * The web's `SearchAndDisplayDrawer`: a 690-wide panel from the right with
 * the same three filters and the matching pages grouped into scene
 * folders; a folder opens over it. Closing clears the search, as the
 * web's `handleCloseDrawer` empties the results.
 */
@Composable
private fun SearchDrawer(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val results = state.searchResults
    val colors = ZillitTheme.colors
    val onClose = { onEvent(DistributionEvent.ClearSearch) }
    AnimatedVisibility(
        visible = results != null && state.isFolderTab,
        enter = fadeIn(tween(DRAWER_ENTER_MS)),
        exit = fadeOut(tween(DRAWER_EXIT_MS)),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = DRAWER_SCRIM))
                .onBackdropTap(onClose),
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
                        ZillitIcon(icon = ZillitIcons.Search, tint = colors.accent, size = DRAWER_ICON)
                        ZillitText(
                            text = "Search",
                            style = ZillitTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = "Close search",
                            onClick = onClose,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceSunken)
                            .padding(ZillitTheme.spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        ZillitTextField(
                            value = state.searchScene,
                            onValueChange = { onEvent(DistributionEvent.SearchChanged(scene = it)) },
                            placeholder = "Search by scene number",
                            leadingIcon = ZillitIcons.Search,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SearchResultList(state, onEvent)
                }
            }
        }
    }
}

@Composable
private fun SearchResultList(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val tab = state.activeTab
    val groups = remember(state.searchResults, tab) {
        state.searchResults.orEmpty().groupBy { it.folderKey(tab) }.entries.toList()
    }
    val live = state.mode == ListMode.Live
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        groups.isEmpty() -> ZillitEmptyState(
            title = "No data found",
            message = "No pages match this search.",
            icon = ZillitIcons.Search,
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            items(groups, key = { it.key }) { (scene, pages) ->
                SearchFolderCard(
                    scene = scene,
                    first = pages.first(),
                    pageCount = pages.size,
                    unread = if (live) state.folderUnread[scene] ?: 0 else 0,
                    television = state.viewer.isTelevision,
                    onClick = { onEvent(DistributionEvent.OpenFolder(scene)) },
                )
            }
        }
    }
}

/**
 * One scene in the search results — the web's ribbon card with the folder
 * glyph, scene number, page date, schedule type and page number of the
 * scene's first match, "View Details" in the corner, tinted the page's
 * colour and wearing the folder's unread.
 */
@Composable
internal fun SearchFolderCard(
    scene: String,
    first: DistDocument,
    pageCount: Int,
    unread: Int,
    television: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(tint(first.colour, colors.surface))
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
        Column {
            Ribbon(text = "Uploaded on: ${DistributionDates.dateTime(first.createdMs)}")
            Row(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(SEARCH_GLYPH_TILE)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = SEARCH_GLYPH)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitText(
                        text = "Scene number: ${scene.ifBlank { "—" }}",
                        style = ZillitTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    if (television) Fact(label = "Episode", value = first.episode.ifBlank { "—" })
                    val pageDate = DistributionDates.date(first.userSelectedDateMs).ifBlank { "—" }
                    Fact(label = "Page date", value = pageDate)
                    first.scheduleType?.let { Fact(label = "Schedule type", value = it.scheduleLabel()) }
                    if (first.pageNumber.isNotBlank()) Fact(label = "Page number", value = first.pageNumber)
                    ZillitText(
                        text = "$pageCount page${if (pageCount == 1) "" else "s"} match",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    end = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.sm,
                ),
                horizontalArrangement = Arrangement.End,
            ) {
                ZillitText(
                    text = "View details",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accentText,
                )
            }
        }
        if (unread > 0) {
            UnreadBadge(
                count = unread,
                modifier = Modifier.align(Alignment.TopEnd).padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

// Upload ---------------------------------------------------------------------

/** The upload dialog's three moments — the form, then one of the web's two follow-up questions. */
private enum class UploadStep { Form, ChooseType, ConfirmColour }

/**
 * The web's `DocumentModal` in its schedule shapes:
 *
 *  - Schedule Full / One Line: the file, the date (optional), the episode on
 *    television (required). A second upload replaces the current schedule.
 *  - Pages, new: episode (TV, required), page number (optional), scene
 *    number (required — starts with a digit, at most 15 characters), the
 *    page date (optional) and the colour; Upload then asks "Please select an
 *    option" — full schedule pages or one-line schedule pages.
 *  - Pages, replace: the scene stays, the page number, date and colour can
 *    change; Upload confirms the colour first ("Are you sure? Page colour is
 *    Blue…").
 */
@Composable
private fun ScheduleUploadDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val editor = state.upload ?: return
    val tab = state.activeTab
    val pages = tab.kind is TabKind.Folders
    val replacing = editor.replaces != null
    val oneLine = tab.key == ONE_LINE_TAB
    val colors = ZillitTheme.colors
    var step by remember(editor.fileName, editor.replaces) { mutableStateOf(UploadStep.Form) }
    var attempted by remember(editor.fileName, editor.replaces) { mutableStateOf(false) }
    // A refused send lands its message on the form, so the question closes.
    LaunchedEffect(state.error) { if (state.error != null) step = UploadStep.Form }
    val problems = remember(editor, pages, replacing, state.viewer.isTelevision) {
        uploadProblems(editor, pages = pages, replacing = replacing, television = state.viewer.isTelevision)
    }
    val onUpload = {
        when {
            problems.isNotEmpty() -> attempted = true
            pages && !replacing -> step = UploadStep.ChooseType
            pages && replacing -> step = UploadStep.ConfirmColour
            else -> onEvent(DistributionEvent.SubmitUpload)
        }
    }
    ZillitDialogShell(
        title = when {
            pages && replacing -> "Replace page"
            pages -> "Upload pages"
            replacing && oneLine -> "Replace one-line schedule"
            replacing -> "Replace full schedule"
            oneLine -> "Upload one-line schedule"
            else -> "Upload full schedule"
        },
        subtitle = when {
            pages -> "Pages are filed under their scene number"
            replacing -> "The current schedule moves to the history"
            else -> "Later uploads replace this one"
        },
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
                text = if (replacing) "Replace" else "Upload",
                onClick = onUpload,
                leadingIcon = ZillitIcons.Upload,
                loading = editor.saving,
                enabled = !editor.saving,
            )
        },
    ) {
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
        FileStrip(editor)
        if (replacing && !pages) {
            ZillitStatusPill(label = "Replaces the current schedule", tone = StatusTone.Pending)
        }
        if (pages) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.sceneNumber,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(sceneNumber = it)) },
                    label = "Scene number",
                    placeholder = "12 or 12A",
                    enabled = !replacing,
                    errorText = problems[Field.Scene].takeIf { attempted },
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.pageNumber,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(pageNumber = it)) },
                    label = "Page number (optional)",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitDateField(
                value = editor.dateYmd,
                onValueChange = { onEvent(DistributionEvent.UploadChanged(dateYmd = it)) },
                label = when {
                    pages -> "Page date (optional)"
                    oneLine -> "One-line date (optional)"
                    else -> "Schedule date (optional)"
                },
                errorText = problems[Field.Date].takeIf { attempted },
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isTelevision && !(pages && replacing)) {
                ZillitTextField(
                    value = editor.episode,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(episode = it)) },
                    label = "Episode number",
                    placeholder = "1 or 1,2",
                    helperText = "Required on a television production.",
                    errorText = problems[Field.Episode].takeIf { attempted },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (pages) {
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
    // The follow-up question sits over the form, as the web's small modal
    // does over its big one; Back returns to the form untouched.
    when (step) {
        UploadStep.ChooseType -> ChooseTypeDialog(
            editor = editor,
            busy = editor.saving,
            onPick = { onEvent(DistributionEvent.UploadChanged(scheduleType = it)) },
            onConfirm = { onEvent(DistributionEvent.SubmitUpload) },
            onBack = { step = UploadStep.Form },
        )
        UploadStep.ConfirmColour -> ConfirmDialog(
            title = "Are you sure?",
            body = "Page colour is ${editor.colour.label}. Do you want to continue with ${editor.colour.label}?",
            confirm = "Yes",
            danger = false,
            busy = editor.saving,
            onConfirm = { onEvent(DistributionEvent.SubmitUpload) },
            onDismiss = { step = UploadStep.Form },
        )
        UploadStep.Form -> Unit
    }
}

/** The picked file, as the web's preview strip names it. */
@Composable
private fun FileStrip(editor: UploadEditor) {
    val colors = ZillitTheme.colors
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
                text = "File size: ${formatBytes(editor.bytes.size.toString()) ?: "—"}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * The web's "Please select an option" modal: two radio rows, and OK refuses
 * until one is picked (`selectpagegValidation`).
 */
@Composable
private fun ChooseTypeDialog(
    editor: UploadEditor,
    busy: Boolean,
    onPick: (ScheduleType) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = ZillitTheme.colors
    var asked by remember { mutableStateOf(false) }
    ZillitDialogShell(
        title = "Please select an option",
        subtitle = "Which pages are these?",
        icon = ZillitIcons.File,
        onDismiss = onBack,
        visible = true,
        width = CHOOSE_DIALOG_WIDTH,
        actions = {
            ZillitButton(text = "Back", onClick = onBack, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "OK",
                onClick = { if (editor.scheduleType == null) asked = true else onConfirm() },
                loading = busy,
                enabled = !busy,
            )
        },
    ) {
        if (asked && editor.scheduleType == null) {
            ZillitNotice(text = "Please select an option before uploading.", tone = StatusTone.Rejected)
        }
        ScheduleType.entries.forEach { type ->
            val selected = editor.scheduleType == type
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.large)
                    .background(if (selected) colors.accentSoft else colors.infoSoft)
                    .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
                    .clickable { onPick(type) }
                    .padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = type.scheduleLabel(),
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary,
                )
                RadioDot(selected)
            }
        }
    }
}

/** A radio button's face — the ring, filled when picked. */
@Composable
private fun RadioDot(selected: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(RADIO)
            .clip(CircleShape)
            .border(2.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(RADIO_DOT).clip(CircleShape).background(colors.accent))
        }
    }
}

/** The fields the form can refuse on, so each refusal lands under its own field. */
private enum class Field { Scene, Episode, Date }

/**
 * The web's form rules, in its words: scene required, starting with a
 * digit, at most 15 characters (`validatSceneNo`); episode required on
 * television; a date, when typed, must parse.
 */
private fun uploadProblems(
    editor: UploadEditor,
    pages: Boolean,
    replacing: Boolean,
    television: Boolean,
): Map<Field, String> = buildMap {
    if (pages && !replacing) {
        val scene = editor.sceneNumber.trim()
        when {
            scene.isEmpty() -> put(Field.Scene, "Scene number is required")
            !scene.first().isDigit() -> put(Field.Scene, "Scene number should start with a number")
            scene.length > MAX_SCENE -> put(Field.Scene, "Scene number not greater than $MAX_SCENE characters")
        }
    }
    val episodeAsked = television && !(pages && replacing)
    if (episodeAsked && editor.episode.isBlank()) put(Field.Episode, "Episode number is required")
    val date = editor.dateYmd.trim()
    if (date.isNotEmpty() && runCatching { LocalDate.parse(date) }.isFailure) {
        put(Field.Date, "The date must be YYYY-MM-DD")
    }
}

// Helpers --------------------------------------------------------------------

/** Presses on the panel never reach the scrim — without `clickable`'s semantics merge. */
private fun Modifier.swallowPresses(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
        }
    }
}

/** A tap on a scrim — without `clickable`'s semantics merge, for the same reason. */
private fun Modifier.onBackdropTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    detectTapGestures { onTap() }
}

private val FOLDER_DIALOG_WIDTH = 820.dp
private val FOLDER_LIST_MIN_HEIGHT = 320.dp
private val FOLDER_LIST_MAX_HEIGHT = 640.dp
private val UPLOAD_DIALOG_WIDTH = 600.dp
private val CHOOSE_DIALOG_WIDTH = 460.dp
private val DRAWER_WIDTH = 690.dp
private val DRAWER_GUTTER = 48.dp
private val DRAWER_SHADOW = 24.dp
private val DRAWER_ICON = 18.dp
private const val DRAWER_SCRIM = 0.4f
private const val DRAWER_ENTER_MS = 240
private const val DRAWER_EXIT_MS = 180
private val FILE_TILE = 40.dp
private val CARD_AVATAR = 48.dp
private val SEARCH_GLYPH_TILE = 48.dp
private val SEARCH_GLYPH = 24.dp
private val RADIO = 20.dp
private val RADIO_DOT = 10.dp
private const val MAX_SCENE = 15
