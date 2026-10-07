package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.GuideViewer
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolGuide
import com.zillit.desktop.feature.home.domain.ToolInfoViewer
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.toolDescription
import com.zillit.desktop.feature.home.domain.toolGuide

/**
 * The production dashboard — every tool this person may open.
 *
 * The grid is built from `GET project/tools`, so it shows exactly what the
 * backend says this user has and nothing else. There is no client-side list of
 * tools to fall out of step with the server, and no "coming soon" tiles for
 * things a user has no rights to — an inaccessible tile is an invitation to ask
 * why it does not work.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Unread counts by backend identifier. A map rather than a lookup lambda
     * because the grid's *order* depends on them — see [sortedForDisplay] —
     * and a lambda gives the memo below nothing it can compare.
     */
    toolBadges: Map<String, Int> = emptyMap(),
    /**
     * The admin notice's first "Click here": which tools the production has
     * at all (the web's `ProjectPermissionModule`). Null leaves the line out.
     */
    onCustomiseTools: (() -> Unit)? = null,
    /** The notice's second "Click here": the Viewing & Posting Rights Grid. */
    onOpenPermissionGrid: (() -> Unit)? = null,
    /** Opens a web address — the ⓘ's documentation page, or a video outside the app. */
    onOpenUrl: (String) -> Unit = {},
    /** The viewer's department identifier — the Deal Memo's ⓘ speaks to the accounts team as dealers. */
    viewerDepartment: String? = null,
    /** A non-film production: the ⓘ texts say "staff" where a film's say "crew". */
    isOtherProject: Boolean = false,
    /** A personal production: no ⓘ at all, as on the web. */
    isPersonalProject: Boolean = false,
    /** The production's `project_type`, sent to the documentation site. */
    projectType: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(state.sections, query, toolBadges) {
        state.sections.matching(query).sortedForDisplay(toolBadges)
    }
    var aboutTool by remember { mutableStateOf<ToolPresentation?>(null) }
    val describe = toolDescriber(state, viewerDepartment, isOtherProject)
    val open: (ToolPresentation) -> Unit = { tool -> onEvent(HomeEvent.OpenTool(tool.route)) }
    val drag = remember { DragState() }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .onGloballyPositioned { root = it },
    ) {
        Column(Modifier.fillMaxSize()) {
            GridHeader(
                state = state,
                query = query,
                onQueryChange = { query = it },
                onEvent = onEvent,
                onCustomiseTools = onCustomiseTools,
                onOpenPermissionGrid = onOpenPermissionGrid,
            )
            state.staleSince?.let { OfflineNotice(it) }
            GridBody(
                state = state,
                shown = shown,
                query = query,
                onClear = { query = "" },
                badges = toolBadges,
                actions = GridActions(open, { aboutTool = it }, describe.takeUnless { isPersonalProject }, onEvent),
                root = root,
                drag = drag,
                onEvent = onEvent,
            )
        }
        DragOverlay(drag)
        ZillitToast(
            message = state.toast?.text,
            onDismiss = { onEvent(HomeEvent.DismissToast) },
            tone = if (state.toast?.success == true) ZillitToastTone.Success else ZillitToastTone.Danger,
        )
    }

    GridDialogs(
        state = state,
        aboutTool = aboutTool,
        describe = describe,
        guide = toolGuider(state, viewerDepartment, isOtherProject, projectType),
        onOpen = open,
        onOpenUrl = onOpenUrl,
        onCloseAbout = { aboutTool = null },
        onEvent = onEvent,
    )
}

/** The page's dialogs: the ⓘ's, the section order, and the custom group's delete. */
@Suppress("LongParameterList")
@Composable
private fun GridDialogs(
    state: HomeUiState,
    aboutTool: ToolPresentation?,
    describe: (ToolPresentation) -> String,
    guide: (ToolPresentation) -> ToolGuide,
    onOpen: (ToolPresentation) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCloseAbout: () -> Unit,
    onEvent: (HomeEvent) -> Unit,
) {
    ToolInfoDialog(
        tool = aboutTool,
        describe = describe,
        guide = guide,
        onOpen = onOpen,
        onOpenUrl = onOpenUrl,
        onDismiss = onCloseAbout,
    )

    ReorderGroupsDialog(
        visible = state.isReordering,
        // The production's groups in this user's current order — every one of
        // them, including groups holding nothing they can see.
        groups = state.groups.orderedBy(state.groupOrder),
        onSave = { onEvent(HomeEvent.SaveGroupOrder(it)) },
        onDismiss = { onEvent(HomeEvent.CancelReorder) },
    )

    DeleteGroupDialog(
        group = state.confirmingDelete,
        onConfirm = { onEvent(HomeEvent.ConfirmDeleteGroup) },
        onDismiss = { onEvent(HomeEvent.CancelDeleteGroup) },
    )
}

/** The page below the header: loading, failure, nothing at all, no match — or the sections. */
@Suppress("LongParameterList")
@Composable
private fun GridBody(
    state: HomeUiState,
    shown: List<ToolSection>,
    query: String,
    onClear: () -> Unit,
    badges: Map<String, Int>,
    actions: GridActions,
    root: LayoutCoordinates?,
    drag: DragState,
    onEvent: (HomeEvent) -> Unit,
) {
    val needle = query.trim()
    when {
        state.isBusy && state.gridTools.isEmpty() -> Centred(str(S.desktop_tools_loading))

        state.error != null && state.gridTools.isEmpty() -> ErrorState(state.error, onEvent)

        state.gridTools.isEmpty() && state.localSections.isEmpty() -> Centred(str(S.desktop_ft_no_tools))

        shown.isEmpty() && needle.isNotEmpty() -> NoMatch(needle, onClear = onClear)

        else -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PAGE_PADDING,
                end = PAGE_PADDING,
                top = 20.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            if (needle.isNotEmpty()) {
                val count = shown.sumOf { it.tools.size }
                ZillitText(
                    text = if (count == 1) {
                        str(S.desktop_ft_match_one, count, needle)
                    } else {
                        str(S.desktop_ft_match_many, count, needle)
                    },
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = ZillitTheme.colors.textMuted,
                )
            }
            if (state.organizing) {
                OrganizeBar(
                    busy = state.busyGroupKey == NEW_GROUP_KEY,
                    createdGroupKey = state.createdGroupKey,
                    onCreate = { onEvent(HomeEvent.CreateGroup(it)) },
                )
            }
            ToolSections(
                sections = shown,
                state = state,
                badges = badges,
                query = query,
                actions = actions,
                root = root,
                drag = drag,
            )
        }
    }
}

/** Each tool's More and Watch video links for this viewer — see [toolGuide]. */
private fun toolGuider(
    state: HomeUiState,
    department: String?,
    isOtherProject: Boolean,
    projectType: String?,
): (ToolPresentation) -> ToolGuide {
    val viewer = GuideViewer(
        info = ToolInfoViewer(
            isAdmin = state.isAdmin,
            isOtherProject = isOtherProject,
            departmentIdentifier = department,
            canPost = { id -> state.permissions.tools.any { it.identifier == id && it.canPost } },
        ),
        projectType = projectType,
    )
    return { tool -> toolGuide(tool.identifier, viewer) }
}

/** "No tools match “q”", and the way back to everything. */
@Composable
private fun NoMatch(needle: String, onClear: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Search,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = 32.dp,
            )
            ZillitText(
                text = str(S.desktop_ft_no_match, needle),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitButton(text = str(S.ah_cd_clear_search), onClick = onClear, variant = ButtonVariant.Secondary)
        }
    }
}

/** "Delete this group?" — the web's confirm, the group named before the warning. */
@Composable
private fun DeleteGroupDialog(group: ToolGroup?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_delete_this_group),
        icon = ZillitIcons.Trash,
        visible = group != null,
        onDismiss = onDismiss,
        width = REORDER_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Secondary, onClick = onDismiss)
            ZillitButton(text = str(S.delete), variant = ButtonVariant.Danger, onClick = onConfirm)
        },
    ) {
        ZillitText(
            text = "“${group?.name.orEmpty()}” — ${str(S.desktop_ft_delete_group_body)}",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** Each tool's ⓘ text for this viewer — see [toolDescription]. */
private fun toolDescriber(
    state: HomeUiState,
    department: String?,
    isOtherProject: Boolean,
): (ToolPresentation) -> String {
    val viewer = ToolInfoViewer(
        isAdmin = state.isAdmin,
        isOtherProject = isOtherProject,
        departmentIdentifier = department,
        // The right as the server sent it, as the web reads `posting_access`.
        canPost = { id -> state.permissions.tools.any { it.identifier == id && it.canPost } },
    )
    return { tool ->
        val described = toolDescription(tool.identifier, tool.label, viewer)
        described.toolName?.let { str(described.key, it) } ?: str(described.key)
    }
}

/**
 * The phones' "Reorder groups" sheet: the sections in a list, dragged into
 * place by a grip (Android `ReorderToolGroupsBottomSheet.kt:40-146`, an
 * `ItemTouchHelper` that moves up and down only), with up/down arrows kept
 * beside the grip for a keyboard-reachable route. Save and Cancel; saved for
 * this user only — the subtitle says so, as Android's does.
 */
@Composable
private fun ReorderGroupsDialog(
    visible: Boolean,
    groups: List<ToolGroup>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // The working order lives here and resets each time the dialog opens.
    var order by remember(visible) { mutableStateOf(groups.map { it.identifier }) }
    val titles = remember(groups) { groups.associate { it.identifier to it.name } }
    ZillitDialogShell(
        title = str(S.reorder_groups),
        subtitle = str(S.desktop_tools_reorder_subtitle),
        icon = ZillitIcons.Grid,
        visible = visible,
        onDismiss = onDismiss,
        width = REORDER_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Secondary, onClick = onDismiss)
            ZillitButton(text = str(S.save), onClick = { onSave(order) })
        },
    ) {
        ReorderableGroupList(order, titles, onOrderChange = { order = it })
    }
}

/**
 * The rows, with drag-to-reorder. Drag distance accumulates and converts to
 * whole rows crossed — the same scheme as the workspace tab strip — so a
 * slow drag moves one row at a time and a fast one several, and the list
 * never reorders per pointer frame.
 */
@Composable
private fun ReorderableGroupList(
    order: List<String>,
    titles: Map<String, String>,
    onOrderChange: (List<String>) -> Unit,
) {
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragAccumulator by remember { mutableStateOf(0f) }
    val rowStep = with(LocalDensity.current) { REORDER_ROW_HEIGHT.toPx() }

    order.forEachIndexed { index, id ->
        val isDragging = draggingIndex == index
        ReorderableGroupRow(
            title = titles[id] ?: id,
            isDragging = isDragging,
            canMoveUp = index > 0,
            canMoveDown = index < order.lastIndex,
            onMove = { by -> onOrderChange(order.moved(index, index + by)) },
            onDragStart = { draggingIndex = index; dragAccumulator = 0f },
            onDrag = { delta ->
                dragAccumulator += delta
                val steps = (dragAccumulator / rowStep).toInt()
                if (steps != 0) {
                    val from = draggingIndex
                    val to = (from + steps).coerceIn(0, order.lastIndex)
                    if (to != from) {
                        onOrderChange(order.moved(from, to))
                        draggingIndex = to
                        dragAccumulator -= steps * rowStep
                    }
                }
            },
            onDragEnd = { draggingIndex = -1; dragAccumulator = 0f },
        )
    }
}

@Composable
private fun ReorderableGroupRow(
    title: String,
    isDragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(REORDER_ROW_HEIGHT)
            .clip(ZillitTheme.shapes.small)
            // The lifted row wears the sunken surface so the eye can follow it.
            .background(if (isDragging) ZillitTheme.colors.surfaceSunken else Color.Transparent)
            .padding(horizontal = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // The grip: the only place the drag starts, so the arrows stay clickable.
        Box(
            modifier = Modifier
                .size(GRIP_SIZE)
                .semantics { contentDescription = str(S.desktop_tools_drag_to_reorder, title) }
                .pointerInput(title) {
                    detectVerticalDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, delta ->
                        change.consume()
                        onDrag(delta)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.MoreHorizontal,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
            )
        }
        Box(Modifier.size(SECTION_DOT).clip(CircleShape).background(avatarHue(title)))
        ZillitText(
            text = title,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronDown,
            contentDescription = str(S.dd_cd_move_down),
            enabled = canMoveDown,
            onClick = { onMove(1) },
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronDown,
            contentDescription = str(S.dd_cd_move_up),
            enabled = canMoveUp,
            onClick = { onMove(-1) },
            modifier = Modifier.rotate(HALF_TURN),
        )
    }
}

/** The list with the item at [from] moved to [to], everything between shifted. */
internal fun List<String>.moved(from: Int, to: Int): List<String> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/**
 * The sections with only the tools whose name contains [query]; every section
 * untouched when the query is blank, and a section left with nothing dropped.
 * Case folded and trimmed.
 *
 * **Tool names only.** Both phones match the tile's own name and nothing else
 * (Android `fullList.filter { title.lowercase().contains(q) }`, iOS
 * `applyToolSearch`). Matching the section name too meant "accounts" returned
 * every tool in Accounts / Payroll on this client and only the tools actually
 * called that on a phone — the same word answering two different questions
 * depending on which screen you typed it into.
 */
internal fun List<ToolSection>.matching(query: String): List<ToolSection> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return mapNotNull { section ->
        section.tools
            .filter { it.label.contains(needle, ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?.let { section.copy(tools = it) }
    }
}

/**
 * Each section's tiles in the order both phones put them in: whatever carries
 * unread work first, then alphabetically.
 *
 * The server's own order is not an order — it is the sequence the backend
 * happened to serialise, and on a production that leaves most tools ungrouped
 * it is thirty-odd tiles arranged by nothing the reader can predict. Android
 * sorts `compareByDescending { badgeCount }.thenBy { title }` per group and
 * iOS `sortToolsByBadgeThenAlphabet`, whose own comment (ZL-20123) records
 * that every rebuild has to funnel through the sort or an async group fetch
 * puts it back to A–Z. The identifier is the final tiebreak, as iOS does it,
 * so the order is total and a recomposition cannot reshuffle equal tiles.
 */
internal fun List<ToolSection>.sortedForDisplay(badges: Map<String, Int>): List<ToolSection> =
    map { section ->
        section.copy(
            tools = section.tools.sortedWith(
                compareByDescending<ToolPresentation> { badges[it.identifier] ?: 0 }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.identifier },
            ),
        )
    }

/**
 * The tile's name with the letters that answered the query lit — the mark the
 * board already puts on a search hit, and the one Android puts on this very
 * grid (`ToolsGroupedAdapter.highlight`). Every occurrence rather than only
 * the first, matching the board: a two-word tool name can carry the needle
 * twice, and lighting one of them reads as a miss.
 *
 * The web's `filmtools-search-mark`: yellow with a dark brown foreground, so it holds in both themes.
 */
internal fun highlightedLabel(label: String, query: String): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(label)
    return buildAnnotatedString {
        append(label)
        var from = 0
        while (from < label.length) {
            val hit = label.indexOf(needle, startIndex = from, ignoreCase = true)
            if (hit < 0) break
            addStyle(
                SpanStyle(background = MARK_BACKGROUND, color = MARK_TEXT, fontWeight = FontWeight.Bold),
                hit,
                hit + needle.length,
            )
            from = hit + needle.length
        }
    }
}

/**
 * The page head, as the web lays it out: the accent bar and "Film Tools",
 * then the find box, Manage Tool Groups (admin, behind its switch) and
 * Customize order; under them, for an admin, the notice that tools are
 * chosen and then granted — each half with its own "Click here".
 */
@Composable
private fun GridHeader(
    state: HomeUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onEvent: (HomeEvent) -> Unit,
    onCustomiseTools: (() -> Unit)?,
    onOpenPermissionGrid: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val rule = colors.border
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(start = PAGE_PADDING, end = PAGE_PADDING, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            PageTitle()
            Spacer(Modifier.weight(1f))
            SearchBox(query, onQueryChange)
            if (state.canOrganize) OrganizeToggle(state.organizing) { onEvent(HomeEvent.ToggleOrganize) }
            ZillitTooltip(str(S.desktop_ft_customize_order_tooltip)) {
                ZillitButton(
                    text = str(S.desktop_ft_customize_order),
                    onClick = { onEvent(HomeEvent.StartReorder) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Hierarchy,
                )
            }
        }
        if (state.isAdmin) AdminNotice(onCustomiseTools, onOpenPermissionGrid)
    }
}

/** The grid as last fetched, shown because the network is gone — and saying so. */
@Composable
private fun OfflineNotice(since: Long) {
    ZillitNotice(
        text = str(S.desktop_tools_offline_notice, EpochDate.dateTime(since)),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        modifier = Modifier.padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
    )
}

/** The accent bar and "Film Tools". */
@Composable
private fun PageTitle() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .width(TITLE_ACCENT_WIDTH)
                .height(TITLE_ACCENT_HEIGHT)
                .clip(ZillitTheme.shapes.small)
                .background(ZillitTheme.colors.accent),
        )
        ZillitText(
            text = str(S.desktop_film_tools),
            style = ZillitTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/** "Search tools…", with a clear button once there is something to clear (`allowClear`). */
@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit) {
    ZillitTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = str(S.desktop_search_tools_ellipsis),
        leadingIcon = ZillitIcons.Search,
        trailingContent = if (query.isNotEmpty()) {
            {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.ah_cd_clear_search),
                    onClick = { onQueryChange("") },
                    size = CLEAR_SIZE,
                )
            }
        } else {
            null
        },
        modifier = Modifier.width(SEARCH_WIDTH),
    )
}

/** Off: an outlined button. On: filled amber with a tick — the page behaves differently while it is. */
@Composable
private fun OrganizeToggle(on: Boolean, onToggle: () -> Unit) {
    ZillitButton(
        text = if (on) str(S.done_text) else str(S.manage_tool_groups),
        onClick = onToggle,
        variant = if (on) ButtonVariant.Primary else ButtonVariant.Secondary,
        leadingIcon = if (on) ZillitIcons.Check else ZillitIcons.Grid,
    )
}

/**
 * One strip for both admin jobs: a tool switched on here is still invisible
 * until the people who need it are given the right to see it.
 */
@Composable
private fun AdminNotice(onCustomiseTools: (() -> Unit)?, onOpenPermissionGrid: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NOTICE_SHAPE)
            .background(colors.infoSoft)
            .border(1.dp, colors.info.copy(alpha = NOTICE_BORDER_ALPHA), NOTICE_SHAPE)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Info, contentDescription = null, tint = colors.info, size = 18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = str(S.tools_description_msg),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            onCustomiseTools?.let { NoticeAction(str(S.desktop_ft_notice_select), it) }
            onOpenPermissionGrid?.let { NoticeAction(str(S.desktop_ft_notice_permissions), it) }
        }
    }
}

/** "Click Here" — the only control on the line — and the rest of the sentence. */
@Composable
private fun NoticeAction(tail: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(
            text = str(S.desktop_pg_click_here),
            style = ZillitTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
            ),
            color = ZillitTheme.colors.info,
            modifier = Modifier.clip(ZillitTheme.shapes.small).clickable(onClick = onClick),
        )
        ZillitText(text = tail, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
    }
}

@Composable
private fun ErrorState(message: String, onEvent: (HomeEvent) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
            )
            // Retryable, because a failed rights call leaves the user with an
            // empty app and no way forward.
            ZillitButton(text = str(S.try_again), onClick = { onEvent(HomeEvent.Reload) })
        }
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(MESSAGE_WIDTH_FRACTION).padding(PAGE_PADDING),
        )
    }
}

private val SEARCH_WIDTH = 320.dp
private val CLEAR_SIZE = 24.dp
private val NOTICE_SHAPE = RoundedCornerShape(10.dp)
private const val NOTICE_BORDER_ALPHA = 0.3f
private val MARK_BACKGROUND = Color(0xFFFDE047)
private val MARK_TEXT = Color(0xFF713F12)
private val REORDER_WIDTH = 420.dp
private val REORDER_ROW_HEIGHT = 40.dp
private val GRIP_SIZE = 28.dp
private const val HALF_TURN = 180f
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 22.dp
private val PAGE_PADDING = 24.dp
private const val MESSAGE_WIDTH_FRACTION = 0.6f
private val SECTION_DOT = 8.dp
