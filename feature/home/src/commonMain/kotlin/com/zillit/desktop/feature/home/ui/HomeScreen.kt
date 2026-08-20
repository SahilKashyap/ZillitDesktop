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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolPresentation

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
) {
    // The find box is the screen's own: forty tiles is a wall, and the phones
    // put a search over theirs. Local state — a query is not a fact about the
    // production and has no business surviving a tool switch.
    var query by remember { mutableStateOf("") }
    val shown = remember(state.sections, query, toolBadges) {
        state.sections.matching(query).sortedForDisplay(toolBadges)
    }

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
        )

        state.staleSince?.let { since ->
            ZillitNotice(
                text = "You're offline — showing the tools saved ${EpochDate.dateTime(since)}. " +
                    "They'll refresh when the connection is back.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
            )
        }

        when {
            state.isBusy && state.gridTools.isEmpty() -> Centred("Loading your tools…")

            state.error != null -> ErrorState(state.error, onEvent)

            state.gridTools.isEmpty() -> Centred(
                "No tools are switched on for you in this production yet. " +
                    "A coordinator can grant access.",
            )

            shown.isEmpty() -> Centred("No tool matches \"${query.trim()}\".")

            else -> ToolGrid(shown, onEvent, toolBadges, query)
        }
    }

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
        title = "Reorder groups",
        subtitle = "Drag the groups into the order you want on your Tools page. This is saved only for you.",
        icon = ZillitIcons.Grid,
        visible = visible,
        onDismiss = onDismiss,
        width = REORDER_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = "Cancel", variant = ButtonVariant.Secondary, onClick = onDismiss)
            ZillitButton(text = "Save", onClick = { onSave(order) })
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
                .semantics { contentDescription = "Drag to reorder $title" }
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
            contentDescription = "Move down",
            enabled = canMoveDown,
            onClick = { onMove(1) },
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronDown,
            contentDescription = "Move up",
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
            ZillitText(text = "Film Tools", style = ZillitTheme.typography.displayLarge)
            ZillitText(
                text = when (toolCount) {
                    0 -> "The production's departments, in one grid."
                    1 -> "1 tool switched on for you."
                    else -> "$toolCount tools switched on for you."
                },
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search tools…",
            leadingIcon = ZillitIcons.Search,
            shape = ZillitTheme.shapes.pill,
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        // The phones' sort icon beside the search: your own section order.
        if (canReorder) {
            ZillitIconButton(
                icon = ZillitIcons.Filter,
                contentDescription = "Reorder groups",
                onClick = onReorder,
            )
        }
    }
}

/**
 * The grid, in the production's own sections.
 *
 * Headers span the full width so the run beneath them reads as one group —
 * thirty-odd unlabelled tiles is a wall, and the crew already know these tools
 * by their department.
 */
@Composable
private fun ToolGrid(
    sections: List<ToolSection>,
    onEvent: (HomeEvent) -> Unit,
    toolBadges: Map<String, Int> = emptyMap(),
    /** Lights the matched letters in each tile's name. */
    query: String = "",
) {
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        // Adaptive rather than a fixed column count: this is a desktop window
        // that can be a third of a screen or all of it, and a fixed grid would
        // be either cramped or a row of stamps.
        columns = GridCells.Adaptive(minSize = TILE_MIN),
        state = gridState,
        // A production can switch on forty tools, and at the platform's default
        // wheel distance the bottom of that grid is eighty notches away.
        modifier = Modifier.fillMaxSize().then(rememberWheelScroll(gridState)),
        contentPadding = PaddingValues(PAGE_PADDING),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        sections.forEach { section ->
            // Every non-empty section is headed, always — one header per group
            // is what both phones emit, with no single-group special case.
            // Suppressing it left a production that keeps its tools in one
            // group showing an unlabelled grid here and a labelled one on the
            // same user's phone.
            item(key = "section-${section.title}", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(section.title, section.tools.size)
            }
            items(section.tools, key = ToolPresentation::identifier) { tool ->
                ToolTile(
                    tool = tool,
                    badge = toolBadges[tool.identifier] ?: 0,
                    query = query,
                    onClick = { onEvent(HomeEvent.OpenTool(tool.route)) },
                )
            }
        }
    }
}

/**
 * A section's name, wearing the section's own colour.
 *
 * The bar takes its hue from the group name by the same rule the tiles take
 * theirs — so a department reads as one block of colour down the page rather
 * than a heading that happens to sit above some tiles. The count rides in a
 * soft pill because a bare number beside a title reads as part of it.
 */
@Composable
private fun SectionHeader(title: String, count: Int) {
    val hue = avatarHue(title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.lg, bottom = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // The name rides its own tinted pill rather than sitting bare on the
        // page: a heading over a wall of white tiles needs a shape of its own
        // to be found while scrolling, and the tint ties it to the department's
        // colour without ever printing text in a hue that dark mode would bury.
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(hue.copy(alpha = CHIP_TINT))
                .padding(
                    start = ZillitTheme.spacing.sm,
                    end = ZillitTheme.spacing.md,
                    top = ZillitTheme.spacing.xs,
                    bottom = ZillitTheme.spacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Box(
                Modifier
                    .size(SECTION_DOT)
                    .clip(CircleShape)
                    .background(hue),
            )
            ZillitText(
                text = title,
                style = ZillitTheme.typography.titleSmall,
                color = ZillitTheme.colors.textPrimary,
            )
            ZillitText(
                text = count.toString(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        // The rule finishes the line rather than dividing it — it fades to
        // nothing so the eye stays on the name.
        Box(
            Modifier
                .weight(1f)
                .height(HAIRLINE)
                .background(
                    Brush.horizontalGradient(
                        listOf(hue.copy(alpha = RULE_TINT), Color.Transparent),
                    ),
                ),
        )
    }
}

@Composable
private fun ToolTile(tool: ToolPresentation, onClick: () -> Unit, badge: Int = 0, query: String = "") {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box {
    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .shadow(if (hovered) TILE_LIFT else 0.dp, ZillitTheme.shapes.medium)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(
                width = HAIRLINE,
                color = if (hovered) colors.borderStrong else colors.border,
                shape = ZillitTheme.shapes.medium,
            )
            .hoverable(interaction)
            .clickable(onClickLabel = tool.label, onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The phone's artwork on a disc in the tool's own hue — the same
        // one-name, one-colour rule the avatars keep, so Accounting is
        // findable by colour after the first visit. The disc is tinted softly
        // and the glyph carries the full hue: these icons are filled, and a
        // white silhouette on saturated colour loses their detail.
        val hue = avatarHue(tool.label)
        Box(
            modifier = Modifier
                .size(TILE_DISC)
                .clip(CircleShape)
                .background(hue.copy(alpha = DISC_TINT)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = tool.icon,
                contentDescription = null,
                tint = hue,
                size = TILE_ICON,
            )
        }
        Box(Modifier.padding(top = ZillitTheme.spacing.sm)) {
            ZillitText(
                text = highlightedLabel(tool.label, query),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
    // Over the tile's corner, like the phone's launcher — the count must
    // survive any tile art behind it, hence on top rather than beside.
    if (badge > 0) {
        com.zillit.desktop.core.designsystem.component.ZillitBadge(
            count = badge,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ZillitTheme.spacing.xs),
            // Uncapped here, capped everywhere else: a tile has room for the
            // real number and both phones print it in full, while the rail and
            // tab strip — where "99+" was introduced — still cannot.
            cap = null,
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
            ZillitButton(text = "Try again", onClick = { onEvent(HomeEvent.Reload) })
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

private val TILE_MIN = 132.dp
private val SEARCH_WIDTH = 260.dp
private val REORDER_WIDTH = 420.dp
private val REORDER_ROW_HEIGHT = 40.dp
private val GRIP_SIZE = 28.dp
private const val HALF_TURN = 180f
private val TILE_ICON = 24.dp
private const val DISC_TINT = 0.16f
private val SECTION_DOT = 8.dp
private const val CHIP_TINT = 0.14f
private const val RULE_TINT = 0.35f
private val TILE_DISC = 44.dp
private val TILE_LIFT = 6.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val PAGE_PADDING = 24.dp
private val HAIRLINE = 1.dp
private const val MESSAGE_WIDTH_FRACTION = 0.6f
