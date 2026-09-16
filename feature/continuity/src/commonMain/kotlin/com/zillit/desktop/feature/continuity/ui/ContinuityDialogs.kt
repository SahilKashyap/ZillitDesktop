// Every dialog the board opens; each branches on one slice of the state.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "LargeClass")

package com.zillit.desktop.feature.continuity.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The dialogs, stacked in the order the web opens them. */
@Composable
internal fun ContinuityDialogs(
    state: ContinuityUiState,
    onEvent: (ContinuityEvent) -> Unit,
    loadImage: LoadImage,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
    loadPdfPages: suspend (ContinuityAttachment) -> List<ImageBitmap>?,
) {
    state.pick?.let { PickDialog(state, onEvent) }
    state.open?.let { CardsDialog(state, it, onEvent, loadImage, resolveUser, formatDate) }
    if (state.forwardIntent) ForwardIntentDialog(onEvent)
    state.forward?.let { ForwardDialog(state, it, onEvent) }
    state.viewing?.let { ViewDialog(state, it, onEvent, loadImage, loadPdfPages) }
    state.details?.let { DetailsDialog(state, it, onEvent, resolveUser, formatDate) }
    state.editor?.let { EditorDialog(state, it, onEvent) }
    state.editor?.detail?.let { DetailDialog(it, onEvent) }
    state.confirmDelete?.let { DeleteDialog(state, onEvent) }
}

// Department pick (All board) ------------------------------------------------

/** The web's `DepartmentList` modal: the departments with media for one scene, searchable, each wearing its unread. */
@Composable
private fun PickDialog(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val pick = state.pick ?: return
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "Department list continuity",
        subtitle = "Scene No - ${pick.sceneFolder}",
        icon = ZillitIcons.Users,
        onDismiss = { onEvent(ContinuityEvent.ClosePick) },
        visible = true,
        width = PICK_WIDTH,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.ClosePick) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        ZillitSearchField(
            value = pick.query,
            onValueChange = { onEvent(ContinuityEvent.SearchDepartments(it)) },
            placeholder = "Search By Department Name",
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            pick.loading -> Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center) { ZillitSpinner() }
            state.shownDepartments.isEmpty() -> ZillitEmptyState(
                title = if (pick.query.isBlank()) {
                    "No department has forwarded media for this scene"
                } else {
                    "No department matches"
                },
                icon = ZillitIcons.Users,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                state.shownDepartments.forEach { department ->
                    val unread = state.unread.department(pick.sceneFolder, department.id)
                    HoverCard(onClick = { onEvent(ContinuityEvent.OpenDepartment(department)) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(
                                text = state.departmentNames[department.id]
                                    ?: department.name.ifBlank { department.id },
                                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            UnreadBadge(count = unread)
                            ZillitIcon(icon = ZillitIcons.ChevronRight, tint = colors.textMuted, size = CHEVRON)
                        }
                    }
                }
            }
        }
    }
}

// Cards ------------------------------------------------------------------------

/**
 * The web's `ContinuityModal`: "Scene No - N [/ Department Name : X]", the
 * search, four cards across, older pages fetched as the grid nears its end,
 * and the Forward / Cancel bar while ticking.
 */
@Composable
private fun CardsDialog(
    state: ContinuityUiState,
    open: OpenFolder,
    onEvent: (ContinuityEvent) -> Unit,
    loadImage: LoadImage,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    val colors = ZillitTheme.colors
    val department = open.department?.let { state.departmentNames[it.id] ?: it.name }
    ZillitDialogShell(
        title = "Scene No - ${open.sceneFolder}" + if (department != null) "  /  Department Name : $department" else "",
        subtitle = "${open.tab.label} · ${open.shown.size} card(s)",
        icon = ZillitIcons.Photo,
        onDismiss = { onEvent(ContinuityEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = GALLERY_WIDTH,
        maxHeight = GALLERY_MAX_HEIGHT,
        actions = {
            if (open.selecting) {
                ZillitText(
                    text = "${open.selected.size} selected",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                ZillitButton(
                    text = "Forward",
                    onClick = { onEvent(ContinuityEvent.ForwardSelected) },
                    leadingIcon = ZillitIcons.Forward,
                    enabled = open.selected.isNotEmpty(),
                    loading = state.busy,
                )
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(ContinuityEvent.CancelSelecting) },
                    variant = ButtonVariant.Tertiary,
                )
            } else {
                if (open.tab == ContinuityTab.MyDepartment) {
                    UploadMenu(onEvent, text = "Upload here", variant = ButtonVariant.Secondary)
                }
                ZillitButton(
                    text = "Close",
                    onClick = { onEvent(ContinuityEvent.CloseFolder) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        },
    ) {
        ZillitSearchField(
            value = open.query,
            onValueChange = { onEvent(ContinuityEvent.SearchCards(it)) },
            placeholder = "Search by Scene notes / Scene Number",
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.fillMaxWidth().height(GALLERY_HEIGHT)) {
            when {
                open.loading && open.scenes.isEmpty() -> SkeletonCards()
                open.shown.isEmpty() -> ZillitEmptyState(
                    title = if (open.query.isBlank()) "No data Found" else "No card matches",
                    message = if (open.query.isBlank() && open.tab == ContinuityTab.MyDepartment) {
                        "Upload a photo, video or document into this scene."
                    } else {
                        null
                    },
                    icon = ZillitIcons.Photo,
                )
                else -> CardGrid(state, open, onEvent, loadImage, resolveUser, formatDate)
            }
        }
    }
}

@Composable
private fun CardGrid(
    state: ContinuityUiState,
    open: OpenFolder,
    onEvent: (ContinuityEvent) -> Unit,
    loadImage: LoadImage,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    val grid = rememberLazyGridState()
    val shown = open.shown
    // The web's `handelInfiniteScroll`: the bottom of the grid asks for the page before it.
    val nearEnd by remember(shown.size) {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= shown.size - 1
        }
    }
    LaunchedEffect(nearEnd, open.exhausted, open.loadingMore, open.query) {
        val wantsMore = nearEnd && open.query.isBlank()
        if (wantsMore && !open.exhausted && !open.loadingMore) onEvent(ContinuityEvent.LoadMore)
    }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(TILE_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(shown, key = { it.id }) { scene ->
            SceneTile(state, open, scene, loadImage, resolveUser, formatDate, onEvent)
        }
        if (open.loadingMore) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md), contentAlignment = Alignment.Center) {
                    ZillitSpinner()
                }
            }
        }
    }
}

@Composable
private fun SkeletonCards() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(SKELETON_TILES) { SkeletonTile() }
    }
}

/**
 * One card — the web's tile: the poster, then the band with "Scene No - N",
 * the department on the All board, the View More button and the ⋮ menu; a
 * tick in the corner while forwarding.
 */
@Composable
private fun SceneTile(
    state: ContinuityUiState,
    open: OpenFolder,
    scene: ContinuityScene,
    loadImage: LoadImage,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
    onEvent: (ContinuityEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val selected = scene.id in open.selected
    HoverCard(
        selected = selected,
        onClick = {
            onEvent(if (open.selecting) ContinuityEvent.ToggleSelect(scene.id) else ContinuityEvent.View(scene))
        },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO)) {
                ScenePoster(scene, loadImage, Modifier.fillMaxSize())
                if (open.selecting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(ZillitTheme.spacing.xs)
                            .clip(ZillitTheme.shapes.small)
                            .background(colors.surface.copy(alpha = TICK_GROUND_ALPHA)),
                    ) {
                        ZillitCheckbox(
                            checked = selected,
                            onCheckedChange = { onEvent(ContinuityEvent.ToggleSelect(scene.id)) },
                        )
                    }
                }
            }
            // The web's orange band under the picture.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentSoft)
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        ZillitText(
                            text = sceneTitle(scene),
                            style = ZillitTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (open.tab == ContinuityTab.AllDepartments) {
                            ZillitText(
                                text = "Department - ${state.departmentLabel(scene.departmentId)}",
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!open.selecting) TileMenu(state, open, scene, onEvent)
                }
                if (scene.notes.isNotBlank()) {
                    ZillitText(
                        text = scene.notes,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "${resolveUser(scene.uploadedBy) ?: "Unknown"} · ${formatDate(scene.createdMs)}",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = "View More",
                        onClick = { onEvent(ContinuityEvent.ShowDetails(scene)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/**
 * The ⋮ — the web's `EditForwardDeleteModal`: Edit Details on My Department,
 * Forward, Download, Delete. A card from another department is not this
 * person's to edit or delete at any rights level (scope, not a right), so
 * those are absent; a missing posting or download right leaves the entry
 * on screen and answers the press with the offer to ask an admin.
 */
@Composable
private fun TileMenu(
    state: ContinuityUiState,
    open: OpenFolder,
    scene: ContinuityScene,
    onEvent: (ContinuityEvent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val viewer = state.viewer
    val owned = viewer.isAdmin || scene.uploadedBy == viewer.userId ||
        (open.tab == ContinuityTab.MyDepartment && scene.departmentId == viewer.departmentId)
    Box {
        ZillitIconButton(
            icon = ZillitIcons.MoreVertical,
            contentDescription = "Card actions",
            onClick = { expanded = true },
        )
        val entries = buildList {
            if (open.tab == ContinuityTab.MyDepartment && owned) {
                add(ZillitMenuEntry.Action("Edit Details", ZillitIcons.Edit, ZillitMenuTone.Primary) {
                    onEvent(ContinuityEvent.Edit(scene))
                })
            }
            add(ZillitMenuEntry.Action("Forward", ZillitIcons.Forward, ZillitMenuTone.Info) {
                onEvent(ContinuityEvent.RequestForward)
            })
            if (scene.attachment != null) {
                add(ZillitMenuEntry.Action("Download", ZillitIcons.Download, ZillitMenuTone.Neutral) {
                    onEvent(ContinuityEvent.Download(scene))
                })
            }
            if (owned) {
                add(ZillitMenuEntry.Divider)
                add(ZillitMenuEntry.Action("Delete", ZillitIcons.Trash, ZillitMenuTone.Danger) {
                    onEvent(ContinuityEvent.RequestDelete(scene))
                })
            }
        }
        ZillitActionMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            entries = entries.map { entry ->
                if (entry is ZillitMenuEntry.Action) {
                    entry.copy(
                        onClick = {
                            expanded = false
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

// Forward ----------------------------------------------------------------------

/** The web's popconfirm on Forward — shown before the ticks appear. */
@Composable
private fun ForwardIntentDialog(onEvent: (ContinuityEvent) -> Unit) {
    ZillitDialogShell(
        title = "Forward",
        icon = ZillitIcons.Forward,
        onDismiss = { onEvent(ContinuityEvent.CancelForwardIntent) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelForwardIntent) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = "Ok", onClick = { onEvent(ContinuityEvent.ConfirmForwardIntent) })
        },
    ) {
        ZillitText(
            text = "Please note, doing this will make your material visible to all. Do you still want to proceed?",
            style = ZillitTheme.typography.bodyMedium,
        )
        ZillitText(
            text = "Tick the cards to forward, then press Forward.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The web's `ContinuityDrawer`: All Departments (My Department only) or Select Users, then the crew list and SAVE. */
@Composable
private fun ForwardDialog(state: ContinuityUiState, sheet: ForwardSheet, onEvent: (ContinuityEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val open = state.open
    val count = open?.selected?.size ?: 0
    val users = sheet.step == ForwardSheet.Step.Users
    ZillitDialogShell(
        title = "Forward",
        subtitle = "$count card(s) from Scene No - ${open?.sceneFolder.orEmpty()}",
        icon = ZillitIcons.Forward,
        onDismiss = { if (!sheet.sending) onEvent(ContinuityEvent.CloseForward) },
        visible = true,
        width = FORWARD_WIDTH,
        actions = {
            if (users) {
                ZillitText(
                    text = "${sheet.selectedUsers.size} selected",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                ZillitButton(
                    text = "Save",
                    onClick = { onEvent(ContinuityEvent.SendForward) },
                    enabled = sheet.selectedUsers.isNotEmpty(),
                    loading = sheet.sending,
                )
            }
            ZillitButton(
                text = if (users) "Cancel" else "Close",
                onClick = { onEvent(ContinuityEvent.CloseForward) },
                variant = ButtonVariant.Tertiary,
                enabled = !sheet.sending,
            )
        },
    ) {
        sheet.error?.let { InlineError(it) }
        if (!users) {
            if (open?.tab == ContinuityTab.MyDepartment) {
                ForwardChoice(
                    title = "All Departments",
                    detail = "Everyone on the production sees these cards under All Departments.",
                    icon = ZillitIcons.Globe,
                    loading = sheet.sending,
                    onClick = { onEvent(ContinuityEvent.ForwardToAllDepartments) },
                )
            }
            ForwardChoice(
                title = "Select Users",
                detail = "Sends each card as a chat message to the crew you choose.",
                icon = ZillitIcons.Users,
                loading = false,
                onClick = { onEvent(ContinuityEvent.ForwardChooseUsers) },
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitIconButton(
                    icon = ZillitIcons.ArrowLeft,
                    contentDescription = "Back",
                    onClick = { onEvent(ContinuityEvent.ForwardBack) },
                    enabled = !sheet.sending,
                )
                ZillitText(
                    text = "Select Users",
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                val everyone = state.shownCrew.map { it.userId }.toSet()
                ZillitButton(
                    text = if (everyone.isNotEmpty() && sheet.selectedUsers == everyone) {
                        "Unselect All"
                    } else {
                        "Select All"
                    },
                    onClick = { onEvent(ContinuityEvent.ToggleAllCrew) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = everyone.isNotEmpty(),
                )
            }
            ZillitSearchField(
                value = sheet.userQuery,
                onValueChange = { onEvent(ContinuityEvent.SearchCrew(it)) },
                placeholder = "Search",
                modifier = Modifier.fillMaxWidth(),
            )
            val crew = state.shownCrew
            if (crew.isEmpty()) {
                ZillitEmptyState(title = "No one matches", icon = ZillitIcons.Users)
            } else {
                ZillitLazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = CREW_LIST_HEIGHT)) {
                    items(crew, key = { it.userId }) { member ->
                        val ticked = member.userId in sheet.selectedUsers
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .clickable(enabled = !sheet.sending) {
                                    onEvent(ContinuityEvent.ToggleCrew(member.userId))
                                }
                                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            Column(Modifier.weight(1f)) {
                                ZillitText(
                                    text = member.name,
                                    style = ZillitTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                )
                                if (member.designation.isNotBlank()) {
                                    ZillitText(
                                        text = member.designation,
                                        style = ZillitTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                    )
                                }
                            }
                            ZillitCheckbox(
                                checked = ticked,
                                onCheckedChange = { onEvent(ContinuityEvent.ToggleCrew(member.userId)) },
                                enabled = !sheet.sending,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardChoice(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    HoverCard(onClick = { if (!loading) onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(CHOICE_GLYPH_TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) ZillitSpinner() else ZillitIcon(icon = icon, tint = colors.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
                ZillitText(text = detail, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
            ZillitIcon(icon = ZillitIcons.ChevronRight, tint = colors.textMuted, size = CHEVRON)
        }
    }
}

// Viewer / details -----------------------------------------------------------

/**
 * The web's `RenderShowImageAndVideo` / `DocumentViewer`: the image at full
 * size, a video's poster with Play (the system player — a desktop has no
 * `<video>` of its own here), a PDF's pages, or a document handed to the OS.
 */
@Composable
private fun ViewDialog(
    state: ContinuityUiState,
    scene: ContinuityScene,
    onEvent: (ContinuityEvent) -> Unit,
    loadImage: LoadImage,
    loadPdfPages: suspend (ContinuityAttachment) -> List<ImageBitmap>?,
) {
    val colors = ZillitTheme.colors
    val attachment = scene.attachment
    ZillitDialogShell(
        title = sceneTitle(scene),
        subtitle = attachment?.name?.ifBlank { null },
        icon = ZillitIcons.Eye,
        onDismiss = { onEvent(ContinuityEvent.CloseView) },
        visible = true,
        width = GALLERY_WIDTH,
        maxHeight = GALLERY_MAX_HEIGHT,
        actions = {
            ZillitButton(
                text = "View More",
                onClick = { onEvent(ContinuityEvent.ShowDetails(scene)) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Info,
            )
            Spacer(Modifier.weight(1f))
            if (attachment != null && (attachment.isVideo || attachment.isDocument)) {
                ZillitButton(
                    text = if (attachment.isVideo) "Play" else "Open",
                    onClick = { onEvent(ContinuityEvent.Open(scene)) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = if (attachment.isVideo) ZillitIcons.Play else ZillitIcons.File,
                    loading = state.busy,
                )
            }
            if (attachment != null) {
                ZillitButton(
                    text = "Download",
                    onClick = { onEvent(ContinuityEvent.Download(scene)) },
                    leadingIcon = ZillitIcons.Download,
                    loading = state.busy,
                )
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.CloseView) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        when {
            attachment == null -> ZillitEmptyState(title = "This card has no file", icon = ZillitIcons.Photo)
            attachment.isPdf -> PdfPages(attachment, loadPdfPages)
            attachment.isDocument -> ZillitEmptyState(
                title = attachment.name.ifBlank { "Document" },
                message = "Open it in the app your system uses for ${attachment.contentSubtype.uppercase()} files.",
                icon = ZillitIcons.File,
            )
            else -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VIEW_HEIGHT)
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.surfaceSunken)
                    .then(
                        if (attachment.isVideo) {
                            Modifier.clickable { onEvent(ContinuityEvent.Open(scene)) }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // A video's full file is not a picture; its poster frame is what the viewer shows.
                ScenePoster(scene, loadImage, Modifier.fillMaxSize(), preview = attachment.isVideo, fit = true)
            }
        }
        if (scene.notes.isNotBlank()) {
            ZillitText(text = scene.notes, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

/** A PDF's pages, rendered once and stacked — the web's `DocumentViewer` for the one document kind it opens inline. */
@Composable
private fun PdfPages(
    attachment: ContinuityAttachment,
    loadPdfPages: suspend (ContinuityAttachment) -> List<ImageBitmap>?,
) {
    val colors = ZillitTheme.colors
    var pages by remember(attachment.media) { mutableStateOf<List<ImageBitmap>?>(null) }
    var failed by remember(attachment.media) { mutableStateOf(false) }
    LaunchedEffect(attachment.media) {
        pages = loadPdfPages(attachment)
        failed = pages.isNullOrEmpty()
    }
    val ready = pages
    when {
        failed -> ZillitEmptyState(
            title = attachment.name.ifBlank { "PDF" },
            message = "This PDF could not be shown here — use Open to read it in your PDF app.",
            icon = ZillitIcons.File,
        )
        ready == null -> Box(Modifier.fillMaxWidth().height(VIEW_HEIGHT), contentAlignment = Alignment.Center) {
            ZillitSpinner()
        }
        else -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ready.forEach { page ->
                Image(
                    bitmap = page,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth(PAGE_WIDTH_FRACTION)
                        .border(1.dp, colors.border),
                )
            }
        }
    }
}

/**
 * The web's `ViewTalenInfoDetails` ("View More"): scene number, department
 * on the All board, episode, then Description and More Info as two
 * sections that open one at a time.
 */
@Composable
private fun DetailsDialog(
    state: ContinuityUiState,
    scene: ContinuityScene,
    onEvent: (ContinuityEvent) -> Unit,
    resolveUser: (String) -> String?,
    formatDate: (Long) -> String,
) {
    val colors = ZillitTheme.colors
    val onAll = state.open?.tab == ContinuityTab.AllDepartments
    var openSection by remember(scene.id) { mutableStateOf(0) }
    ZillitDialogShell(
        title = "Details",
        subtitle = sceneTitle(scene),
        icon = ZillitIcons.Info,
        onDismiss = { onEvent(ContinuityEvent.CloseDetails) },
        visible = true,
        width = DETAILS_WIDTH,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ContinuityEvent.CloseDetails) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        FactCard("Scene No", scene.sceneNumber)
        if (onAll) FactCard("Department Name", state.departmentLabel(scene.departmentId))
        if (scene.episode.isNotBlank()) FactCard("Episode No", scene.episode)
        scene.attachment?.let { a ->
            val size = a.fileSize.toLongOrNull()?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}" }.orEmpty()
            FactCard("File", a.name.ifBlank { a.contentSubtype.uppercase() } + size)
        }
        FactCard("Uploaded", "${resolveUser(scene.uploadedBy) ?: scene.uploadedBy} · ${formatDate(scene.createdMs)}")
        Accordion(
            title = "Description",
            open = openSection == 0,
            onToggle = { openSection = if (openSection == 0) -1 else 0 },
        ) {
            if (scene.notes.isBlank()) {
                ZillitText(text = "No data Found", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            } else {
                ZillitText(text = scene.notes, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
            }
        }
        Accordion(
            title = "More Info",
            open = openSection == 1,
            onToggle = { openSection = if (openSection == 1) -1 else 1 },
        ) {
            val rows = buildList {
                if (scene.actorName.isNotBlank()) add("Actor" to scene.actorName)
                scene.talentInfo.forEach { add(it.label to it.value) }
            }
            if (rows.isEmpty()) {
                ZillitText(text = "No data Found", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            } else {
                rows.forEach { (label, value) -> FactCard(label.replaceFirstChar { it.uppercase() }, value) }
            }
        }
    }
}

/** The web's hoverable antd `Card` line: **Label -** value. */
@Composable
private fun FactCard(label: String, value: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = "$label -",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

@Composable
private fun Accordion(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .clickable(onClick = onToggle)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = if (open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                tint = colors.textMuted,
                size = CHEVRON,
            )
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }
        AnimatedVisibility(visible = open) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                content()
            }
        }
    }
}

// Editor ---------------------------------------------------------------------

/**
 * The web's `AddScene` / `EditScene`: the picked files, Scene No, Episode No
 * on television, Scene Notes, the detail rows with their pencil and bin,
 * "Add more details" at the left of the footer, Cancel and Submit / Update
 * at the right.
 */
@Composable
private fun EditorDialog(state: ContinuityUiState, editor: SceneEditor, onEvent: (ContinuityEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val change = { updated: SceneDraft -> onEvent(ContinuityEvent.DraftChanged(updated)) }
    ZillitDialogShell(
        title = if (editor.isNew) "Add Scene Details" else "Edit Details",
        subtitle = when {
            !editor.isNew -> "Scene No - ${editor.draft.sceneNumber}"
            editor.files.size == 1 -> editor.files.first().name
            else -> "${editor.files.size} files"
        },
        icon = if (editor.isNew) ZillitIcons.Upload else ZillitIcons.Edit,
        onDismiss = { onEvent(ContinuityEvent.CancelEdit) },
        visible = true,
        width = EDITOR_WIDTH,
        actions = {
            ZillitButton(
                text = "Add more details",
                onClick = { onEvent(ContinuityEvent.OpenDetail()) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
                enabled = !editor.saving,
            )
            Spacer(Modifier.weight(1f))
            if (editor.saving && editor.files.size > 1) {
                ZillitText(
                    text = "${editor.progress} of ${editor.files.size} uploaded",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelEdit) },
                variant = ButtonVariant.Tertiary,
                enabled = !editor.saving,
            )
            ZillitButton(
                text = if (editor.isNew) "Submit" else "Update",
                onClick = { onEvent(ContinuityEvent.Save) },
                loading = editor.saving,
            )
        },
    ) {
        if (editor.isNew) PickedFilesStrip(editor.files)
        editor.error?.let { InlineError(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.draft.sceneNumber,
                onValueChange = { change(editor.draft.copy(sceneNumber = it.take(FIELD_MAX))) },
                label = "Scene No",
                placeholder = "Enter Scene Number",
                maxLength = FIELD_MAX,
                enabled = !editor.saving,
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isTelevision) {
                ZillitTextField(
                    value = editor.draft.episode,
                    onValueChange = { change(editor.draft.copy(episode = it.take(FIELD_MAX))) },
                    label = "Episode No",
                    placeholder = "Enter Episode Number",
                    maxLength = FIELD_MAX,
                    enabled = !editor.saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ZillitTextField(
            value = editor.draft.notes,
            onValueChange = { change(editor.draft.copy(notes = it)) },
            label = "Scene Notes",
            placeholder = "Description",
            singleLine = false,
            enabled = !editor.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        if (editor.draft.talentInfo.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium),
            ) {
                editor.draft.talentInfo.forEachIndexed { index, row ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        ZillitText(
                            text = row.label.replaceFirstChar { it.uppercase() },
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                        )
                        ZillitText(
                            text = ": ${row.value}",
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Edit,
                            contentDescription = "Edit detail",
                            onClick = { onEvent(ContinuityEvent.OpenDetail(index)) },
                            enabled = !editor.saving,
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Remove detail",
                            tint = colors.danger,
                            onClick = { onEvent(ContinuityEvent.RemoveDetail(index)) },
                            enabled = !editor.saving,
                        )
                    }
                }
            }
        }
    }
}

/** The files about to be uploaded, as small posters — the web's media preview modal, folded into the form. */
@Composable
private fun PickedFilesStrip(files: List<PickedContinuityFile>) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        files.take(STRIP_MAX).forEach { file ->
            var bitmap by remember(file.name) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(file.name) {
                if (file.isImage) bitmap = withContext(Dispatchers.Default) { decodeImageBitmap(file.bytes) }
            }
            Box(
                modifier = Modifier
                    .size(STRIP_TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                val ready = bitmap
                if (ready != null) {
                    Image(
                        bitmap = ready,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                        modifier = Modifier.padding(ZillitTheme.spacing.xs),
                    ) {
                        ZillitIcon(
                            icon = when {
                                file.isVideo -> ZillitIcons.Play
                                file.isImage -> ZillitIcons.Photo
                                else -> ZillitIcons.File
                            },
                            tint = colors.accent,
                        )
                        ZillitText(
                            text = file.name,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (files.size > STRIP_MAX) {
            Box(
                modifier = Modifier
                    .size(STRIP_TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = "+${files.size - STRIP_MAX}",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.accent,
                )
            }
        }
    }
}

/** A refusal shown inside the dialog it belongs to — a page banner would sit behind the scrim. */
@Composable
private fun InlineError(text: String) {
    ZillitNotice(text = text, tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
}

/** The web's `AddNewDetails`: a Title and a Description, both required. */
@Composable
private fun DetailDialog(detail: DetailEditor, onEvent: (ContinuityEvent) -> Unit) {
    ZillitDialogShell(
        title = if (detail.isNew) "Add Scene Details" else "Edit detail",
        icon = ZillitIcons.Add,
        onDismiss = { onEvent(ContinuityEvent.CancelDetail) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelDetail) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = "Submit", onClick = { onEvent(ContinuityEvent.SaveDetail) }, enabled = detail.canSave)
        },
    ) {
        ZillitTextField(
            value = detail.label,
            onValueChange = { onEvent(ContinuityEvent.DetailChanged(it.take(LABEL_MAX), detail.value)) },
            label = "Title",
            placeholder = "Enter Title",
            maxLength = LABEL_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = detail.value,
            onValueChange = { onEvent(ContinuityEvent.DetailChanged(detail.label, it.take(VALUE_MAX))) },
            label = "Description",
            placeholder = "Enter Description",
            singleLine = false,
            maxLength = VALUE_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// Confirmations --------------------------------------------------------------

/** The web's popconfirm: "Are you sure you want to delete this Item ?" */
@Composable
private fun DeleteDialog(state: ContinuityUiState, onEvent: (ContinuityEvent) -> Unit) {
    val scene = state.confirmDelete ?: return
    val board = state.open?.tab?.label ?: state.tab.label
    ZillitDialogShell(
        title = "Delete",
        subtitle = "${sceneTitle(scene)} · $board",
        icon = ZillitIcons.Trash,
        onDismiss = { onEvent(ContinuityEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ContinuityEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Ok",
                onClick = { onEvent(ContinuityEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.busy,
            )
        },
    ) {
        ZillitText(text = "Are you sure you want to delete this Item ?", style = ZillitTheme.typography.bodyMedium)
        ZillitText(
            text = "It comes off the $board board only; the other board keeps its copy.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private const val FIELD_MAX = 15
private const val LABEL_MAX = 100
private const val VALUE_MAX = 180
private const val STRIP_MAX = 6
private const val SKELETON_TILES = 8
private const val TICK_GROUND_ALPHA = 0.85f
private const val PAGE_WIDTH_FRACTION = 0.9f
private val PICK_WIDTH = 520.dp
private val FORWARD_WIDTH = 520.dp
private val DETAILS_WIDTH = 600.dp
private val EDITOR_WIDTH = 560.dp
private val GALLERY_WIDTH = 1200.dp
private val GALLERY_MAX_HEIGHT = 800.dp
private val GALLERY_HEIGHT = 550.dp
private val VIEW_HEIGHT = 520.dp
private val TILE_MIN_WIDTH = 240.dp
private val CHEVRON = 16.dp
private val CHOICE_GLYPH_TILE = 40.dp
private val CREW_LIST_HEIGHT = 380.dp
private val STRIP_TILE = 72.dp
