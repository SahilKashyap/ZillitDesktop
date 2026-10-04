package com.zillit.desktop.feature.home.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import androidx.compose.ui.draw.rotate
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.ToolInfoViewer
import com.zillit.desktop.feature.home.domain.toolDescription

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
     * Opens the production's tool switches — the phones' customise button on
     * their Tools tab (`Tools.kt:199`, admin only). Null hides the control:
     * the host decides where the page lives, this screen only offers the way.
     */
    onCustomiseTools: (() -> Unit)? = null,
    /** The viewer's department identifier — the Deal Memo's ⓘ speaks to the accounts team as dealers. */
    viewerDepartment: String? = null,
    /** A non-film production: the ⓘ texts say "staff" where a film's say "crew". */
    isOtherProject: Boolean = false,
) {
    // The find box is the screen's own: forty tiles is a wall, and the phones
    // put a search over theirs. Local state — a query is not a fact about the
    // production and has no business surviving a tool switch.
    var query by remember { mutableStateOf("") }
    val shown = remember(state.sections, query, toolBadges) {
        state.sections.matching(query).sortedForDisplay(toolBadges)
    }
    // The tool whose ⓘ was clicked; null keeps its description shut.
    var aboutTool by remember { mutableStateOf<ToolPresentation?>(null) }
    val describe = toolDescriber(state, viewerDepartment, isOtherProject)
    val open: (ToolPresentation) -> Unit = { tool -> onEvent(HomeEvent.OpenTool(tool.route)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas),
    ) {
        GridHeader(
            toolCount = state.gridTools.size,
            query = query,
            onQueryChange = { query = it },
            // Whether the production *has* groups, not whether this user can
            // currently see more than one section of them. Both phones show
            // the control unconditionally, seeded from the project's own group
            // set; gating on rendered sections hid it from anyone whose rights
            // left them one section, and quietly shortened the list they were
            // reordering. The one case neither phone can reach — a production
            // with no groups at all — would open an empty sheet, so it stays out.
            canReorder = state.groups.isNotEmpty(),
            onReorder = { onEvent(HomeEvent.StartReorder) },
            // Admin only, as the phones gate their button (`Tools.kt:333`).
            onCustomiseTools = onCustomiseTools.takeIf { state.isAdmin },
        )

        state.staleSince?.let { since ->
            ZillitNotice(
                text = str(S.desktop_tools_offline_notice, EpochDate.dateTime(since)),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
            )
        }

        when {
            state.isBusy && state.gridTools.isEmpty() -> Centred(str(S.desktop_tools_loading))

            state.error != null -> ErrorState(state.error, onEvent)

            state.gridTools.isEmpty() -> Centred(str(S.desktop_tools_none_switched_on))

            shown.isEmpty() -> Centred(str(S.desktop_tools_no_match, query.trim()))

            else -> ToolSections(
                sections = shown,
                badges = toolBadges,
                query = query,
                onOpen = open,
                onInfo = { aboutTool = it },
            )
        }
    }

    ToolInfoDialog(tool = aboutTool, describe = describe, onOpen = open, onDismiss = { aboutTool = null })

    ReorderGroupsDialog(
        visible = state.isReordering,
        // The production's groups in this user's current order — every one of
        // them, including groups holding nothing they can see. Listing only
        // the rendered sections meant `saveGroupOrder` reconciled the rest
        // onto the end, rewriting an order for groups the user was never shown.
        groups = state.groups.orderedBy(state.groupOrder),
        onSave = { onEvent(HomeEvent.SaveGroupOrder(it)) },
        onDismiss = { onEvent(HomeEvent.CancelReorder) },
    )
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
 * [HIGHLIGHT] carries an explicit black foreground, so it holds in both themes.
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
            addStyle(SpanStyle(background = HIGHLIGHT, color = Color.Black), hit, hit + needle.length)
            from = hit + needle.length
        }
    }
}

/** The accent bar, the name, how much this person may open — and the find box. */
@Composable
private fun GridHeader(
    toolCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    canReorder: Boolean,
    onReorder: () -> Unit,
    onCustomiseTools: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            Modifier
                .width(TITLE_ACCENT_WIDTH)
                .height(TITLE_ACCENT_HEIGHT)
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.accent),
        )
        Column {
            ZillitText(text = str(S.desktop_film_tools), style = ZillitTheme.typography.displayLarge)
            ZillitText(
                text = when (toolCount) {
                    0 -> str(S.desktop_tools_departments_grid)
                    1 -> str(S.desktop_tools_one_switched_on)
                    else -> str(S.desktop_tools_count_switched_on, toolCount)
                },
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = str(S.desktop_search_tools_ellipsis),
            leadingIcon = ZillitIcons.Search,
            shape = ZillitTheme.shapes.pill,
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        // The phones' sort icon beside the search: your own section order.
        if (canReorder) {
            ZillitIconButton(
                icon = ZillitIcons.Filter,
                contentDescription = str(S.reorder_groups),
                onClick = onReorder,
            )
        }
        // Which tools the production has at all — the phones' customise
        // button, opening the same switches Admin Settings holds.
        onCustomiseTools?.let { open ->
            ZillitIconButton(
                icon = ZillitIcons.Settings,
                contentDescription = str(S.desktop_tools_customise),
                onClick = open,
            )
        }
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

private val SEARCH_WIDTH = 260.dp
private val REORDER_WIDTH = 420.dp
private val REORDER_ROW_HEIGHT = 40.dp
private val GRIP_SIZE = 28.dp
private const val HALF_TURN = 180f
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val PAGE_PADDING = 24.dp
private const val MESSAGE_WIDTH_FRACTION = 0.6f
private val SECTION_DOT = 8.dp
