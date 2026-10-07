package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.ZillitVideoView
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.ToolGuide
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.groupVisual
import kotlinx.coroutines.delay

/**
 * What the grid needs to draw one tile's ⓘ and the organize controls —
 * grouped because every section passes the same set down.
 */
internal class GridActions(
    val onOpen: (ToolPresentation) -> Unit,
    val onInfo: (ToolPresentation) -> Unit,
    /** The ⓘ's hover text; null hides the glyph (a personal production has none, as on the web). */
    val describe: ((ToolPresentation) -> String)?,
    val onEvent: (HomeEvent) -> Unit,
)

/**
 * The web's Film Tools sections (`FilmTools.jsx`): each group a heading — a
 * coloured glyph, the name, a one-line subheading, a rule under it — over an
 * adaptive grid of tile cards, the sections stacked down the page.
 *
 * While organizing (admin, Manage Tool Groups) a tile drags onto any
 * section, a heading becomes a rename field on click, a custom group shows
 * its delete, and an empty group stays on screen as a "Drag a tool here"
 * target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolSections(
    sections: List<ToolSection>,
    state: HomeUiState,
    badges: Map<String, Int>,
    query: String,
    actions: GridActions,
    root: LayoutCoordinates?,
    drag: DragState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        sections.forEach { section ->
            val requester = remember { BringIntoViewRequester() }
            val isNew = section.identifier != null && section.identifier == state.createdGroupKey
            // The one action on the page with no visible result is a create:
            // the group lands wherever the saved order puts it, usually below
            // the fold. Bring it into view, light it for a moment, then let go.
            LaunchedEffect(isNew) {
                if (isNew) {
                    requester.bringIntoView()
                    delay(NEW_GROUP_HIGHLIGHT_MS)
                    actions.onEvent(HomeEvent.CreatedGroupShown)
                }
            }
            val target = section.dropKey
            val hovered = state.organizing && target != null && drag.overGroup == target
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(requester)
                    .then(if (target != null) Modifier.onGloballyPositioned { drag.targets[target] = it } else Modifier)
                    .clip(ZillitTheme.shapes.medium)
                    .background(
                        when {
                            hovered -> ZillitTheme.colors.accentSoft
                            isNew -> ZillitTheme.colors.accentSoft.copy(alpha = NEW_GROUP_TINT)
                            else -> Color.Transparent
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(GROUP_INNER_GAP),
            ) {
                GroupHead(section, state, actions)
                TileGrid(section, state, badges, query, actions, root, drag)
            }
        }
    }
}

/** The heading: glyph, name (or its rename field), subheading, and a custom group's delete. */
@Composable
private fun GroupHead(section: ToolSection, state: HomeUiState, actions: GridActions) {
    val visual = groupVisual(section.identifier)
    val key = section.identifier
    val rule = ZillitTheme.colors.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(bottom = GROUP_HEAD_RULE_GAP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GROUP_HEAD_GAP),
    ) {
        Box(Modifier.size(GLYPH_BOX), contentAlignment = Alignment.Center) {
            ZillitIcon(icon = visual.icon, contentDescription = null, tint = visual.color, size = GLYPH)
        }
        Column(Modifier.weight(1f)) {
            when {
                key != null && state.renamingGroup == key ->
                    GroupNameEditor(
                        name = section.title,
                        busy = state.busyGroupKey == key,
                        onRename = { actions.onEvent(HomeEvent.RenameGroup(key, it)) },
                        onCancel = { actions.onEvent(HomeEvent.CancelRename) },
                    )

                state.organizing && state.isRenameable(key) ->
                    EditableGroupTitle(section.title) { key?.let { actions.onEvent(HomeEvent.StartRename(it)) } }

                else -> GroupTitle(section.title)
            }
            visual.sub?.takeIf { section.identifier != null }?.let { sub ->
                ZillitText(
                    text = sub,
                    style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (state.organizing && state.isDeletable(key)) {
            GroupDelete(busy = state.busyGroupKey == key) { key?.let { actions.onEvent(HomeEvent.AskDeleteGroup(it)) } }
        }
    }
}

/** A heading that renames on click — "Edit Group name" spelled out beside it, not left to a pencil. */
@Composable
private fun EditableGroupTitle(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(end = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        GroupTitle(title)
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .background(ZillitTheme.colors.accentSoft)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Edit,
                contentDescription = null,
                tint = ZillitTheme.colors.accent,
                size = 12.dp,
            )
            ZillitText(
                text = str(S.desktop_ft_edit_group_name),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accent,
            )
        }
    }
}

/** A custom group's delete; a spinner while the call is out. */
@Composable
private fun GroupDelete(busy: Boolean, onClick: () -> Unit) {
    if (busy) {
        ZillitSpinner()
        return
    }
    ZillitIconButton(
        icon = ZillitIcons.Trash,
        contentDescription = str(S.delete),
        onClick = onClick,
        tint = ZillitTheme.colors.danger,
    )
}

@Composable
private fun GroupTitle(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
        color = ZillitTheme.colors.textPrimary,
    )
}

/** Tiles in as many columns as leave each at least [TILE_MIN] wide — `repeat(auto-fill, minmax(260px, 1fr))`. */
@Composable
private fun TileGrid(
    section: ToolSection,
    state: HomeUiState,
    badges: Map<String, Int>,
    query: String,
    actions: GridActions,
    root: LayoutCoordinates?,
    drag: DragState,
) {
    if (section.tools.isEmpty()) {
        EmptyGroupTarget()
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = tileColumns(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            section.tools.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                ) {
                    row.forEach { tool ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ToolTile(
                                tool = tool,
                                badge = badges[tool.identifier] ?: 0,
                                query = query,
                                organizing = state.organizing && section.dropKey != null,
                                dragging = drag.tool?.identifier == tool.identifier,
                                actions = actions,
                                root = root,
                                drag = drag,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** As many columns as leave each tile at least [TILE_MIN] wide; never fewer than one. */
internal fun tileColumns(width: Dp): Int =
    ((width + TILE_GAP) / (TILE_MIN + TILE_GAP)).toInt().coerceAtLeast(1)

@Composable
private fun EmptyGroupTarget() {
    val border = ZillitTheme.colors.border
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = border,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                )
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = str(S.desktop_ft_group_empty),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One tile — the web's `filmtools-card`: the name, bold, the ⓘ at the end,
 * the unread count pinned to the top-right corner. Hover lifts it, warms the
 * border, lights a 3px accent stripe down the left edge and turns the name
 * amber. While organizing it carries a grip and drags instead of opening.
 */
@Suppress("LongParameterList")
@Composable
private fun ToolTile(
    tool: ToolPresentation,
    badge: Int,
    query: String,
    organizing: Boolean,
    dragging: Boolean,
    actions: GridActions,
    root: LayoutCoordinates?,
    drag: DragState,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var self by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lifted = hovered && !organizing

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .offset(y = if (lifted) (-1).dp else 0.dp)
            .onGloballyPositioned { self = it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .defaultMinSize(minHeight = TILE_MIN_HEIGHT)
                .tileSurface(lifted)
                .hoverable(interaction)
                .then(
                    if (organizing) {
                        Modifier.dragToGroup(tool, { self }, root, drag, actions)
                    } else {
                        Modifier.clickable(onClickLabel = tool.label) { actions.onOpen(tool) }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (organizing) {
                ZillitIcon(
                    icon = ZillitIcons.MoreVertical,
                    contentDescription = null,
                    tint = colors.textMuted,
                    size = 16.dp,
                )
            }
            ZillitText(
                text = highlightedLabel(tool.label, query),
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = if (lifted) colors.accent else colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            actions.describe?.let { describe -> InfoGlyph(tool, describe(tool)) { actions.onInfo(tool) } }
        }
        if (badge > 0) CornerBadge(badge, Modifier.align(Alignment.TopEnd))
        if (dragging) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(TILE_SHAPE)
                    .background(colors.surface.copy(alpha = DRAG_GHOST_ALPHA)),
            )
        }
    }
}

/** The card: surface and hairline at rest; lifted, the warm border and the 3dp accent stripe down the left. */
@Composable
private fun Modifier.tileSurface(lifted: Boolean): Modifier {
    val colors = ZillitTheme.colors
    return clip(TILE_SHAPE)
        .background(if (lifted) colors.surfaceHover else colors.surface)
        .border(1.dp, if (lifted) colors.accent.copy(alpha = HOVER_BORDER_ALPHA) else colors.border, TILE_SHAPE)
        .drawBehind { if (lifted) drawRect(colors.accent, size = Size(STRIPE.toPx(), size.height)) }
}

/** The ⓘ: its text on hover, the full description, More and Watch video on a click. */
@Composable
private fun InfoGlyph(tool: ToolPresentation, text: String, onClick: () -> Unit) {
    ZillitTooltip(text) {
        val about = str(S.desktop_sa_about_code, tool.label)
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .semantics { contentDescription = about }
                .clickable(onClickLabel = about, onClick = onClick),
        ) {
            ZillitIcon(icon = ZillitIcons.Info, contentDescription = null, tint = INFO_BLUE, size = INFO_SIZE)
        }
    }
}

/**
 * A tile dragged onto a section while organizing. The grab point is turned
 * into the page's own coordinates so the floating copy starts under the
 * pointer, and the drop is resolved against the sections' registered bounds.
 */
private fun Modifier.dragToGroup(
    tool: ToolPresentation,
    self: () -> LayoutCoordinates?,
    root: LayoutCoordinates?,
    drag: DragState,
    actions: GridActions,
): Modifier = pointerInput(tool.identifier) {
    detectDragGestures(
        onDragStart = { at -> drag.start(tool, inPage(self(), root, at)) },
        onDragEnd = {
            drag.end(root)?.let { (moved, group) ->
                actions.onEvent(HomeEvent.MoveTool(moved.identifier, moved.label, group))
            }
        },
        onDragCancel = { drag.cancel() },
    ) { change, amount ->
        change.consume()
        drag.moveBy(amount, root)
    }
}

/** [at], in the tile's own space, moved into the page's; unchanged when either is gone. */
private fun inPage(origin: LayoutCoordinates?, root: LayoutCoordinates?, at: Offset): Offset {
    val tile = origin?.takeIf { it.isAttached } ?: return at
    val page = root?.takeIf { it.isAttached } ?: return at
    return page.localPositionOf(tile, at)
}

/** The red count on the corner: `99+` past ninety-nine, as the web caps it. */
@Composable
private fun CornerBadge(count: Int, modifier: Modifier) {
    Box(
        modifier = modifier
            .offset(x = 8.dp, y = (-8).dp)
            .defaultMinSize(minWidth = BADGE_SIZE)
            .height(BADGE_SIZE)
            .clip(RoundedCornerShape(50))
            .background(ZillitTheme.colors.surface)
            .padding(2.dp)
            .clip(RoundedCornerShape(50))
            .background(BADGE_RED)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

/** The tile following the pointer while it is being dragged. */
@Composable
internal fun DragOverlay(drag: DragState) {
    val tool = drag.tool ?: return
    val at = drag.pointer
    Row(
        modifier = Modifier
            .offset { IntOffset((at.x - DRAG_GRAB_X.toPx()).toInt(), (at.y - DRAG_GRAB_Y.toPx()).toInt()) }
            .width(TILE_MIN)
            .clip(TILE_SHAPE)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.accent, TILE_SHAPE)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitIcon(
            icon = ZillitIcons.MoreVertical,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
            size = 16.dp,
        )
        ZillitText(
            text = tool.label,
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/**
 * A drag in progress: which tile, where the pointer is in the page's own
 * coordinates, and which section it is over. Sections register themselves as
 * targets as they lay out.
 */
internal class DragState {
    var tool by mutableStateOf<ToolPresentation?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var overGroup by mutableStateOf<String?>(null)
        private set

    val targets = mutableMapOf<String, LayoutCoordinates>()

    fun start(tile: ToolPresentation, at: Offset) {
        tool = tile
        pointer = at
    }

    fun moveBy(amount: Offset, root: LayoutCoordinates?) {
        pointer += amount
        overGroup = targetAt(root)
    }

    /** The tile and the section it was let go over, if any; the drag is over either way. */
    fun end(root: LayoutCoordinates?): Pair<ToolPresentation, String>? {
        val moved = tool
        val group = targetAt(root)
        cancel()
        return if (moved != null && group != null) moved to group else null
    }

    fun cancel() {
        tool = null
        overGroup = null
    }

    private fun targetAt(root: LayoutCoordinates?): String? {
        val base = root?.takeIf { it.isAttached } ?: return null
        return targets.entries.firstOrNull { (_, coords) ->
            coords.isAttached && base.localBoundingBoxOf(coords, clipBounds = false).contains(pointer)
        }?.key
    }
}

/** The organize bar: what the mode does, and the control that adds a group. */
@Composable
internal fun OrganizeBar(busy: Boolean, createdGroupKey: String?, onCreate: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Info,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = 15.dp,
            )
            ZillitText(
                text = str(S.desktop_ft_organize_hint),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        NewGroupTile(busy, createdGroupKey, onCreate)
    }
}

/** "Add new group": a button that opens into a name field with Save and Cancel. */
@Composable
private fun NewGroupTile(busy: Boolean, createdGroupKey: String?, onCreate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    // A create that landed leaves the field open and empty for the next one.
    LaunchedEffect(createdGroupKey) { if (createdGroupKey != null) value = "" }

    if (!open) {
        ZillitTooltip(str(S.desktop_ft_add_group_hint)) {
            ZillitButton(
                text = str(S.desktop_ft_add_group),
                onClick = { open = true },
                leadingIcon = ZillitIcons.Add,
                size = ButtonSize.Small,
            )
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val close = { open = false; value = "" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = str(S.mtg_group_name_hint),
            enabled = !busy,
            onImeAction = { if (value.isNotBlank()) onCreate(value) else close() },
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            modifier = Modifier
                .width(NAME_FIELD_WIDTH)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                },
        )
        ZillitButton(
            text = str(S.save),
            onClick = { onCreate(value) },
            enabled = value.isNotBlank(),
            loading = busy,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = str(S.cancel),
            onClick = close,
            variant = ButtonVariant.Secondary,
            enabled = !busy,
            size = ButtonSize.Small,
        )
    }
}

/** A heading's rename field — Enter saves, Escape puts the old name back. */
@Composable
private fun GroupNameEditor(name: String, busy: Boolean, onRename: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember(name) { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val changed = value.trim().isNotEmpty() && value.trim() != name
    val commit = { if (changed) onRename(value) else onCancel() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = value,
            onValueChange = { value = it },
            enabled = !busy,
            onImeAction = commit,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            modifier = Modifier
                .width(NAME_FIELD_WIDTH)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                },
        )
        ZillitButton(text = str(S.save), onClick = commit, enabled = changed, loading = busy, size = ButtonSize.Small)
        ZillitButton(
            text = str(S.cancel),
            onClick = onCancel,
            variant = ButtonVariant.Secondary,
            enabled = !busy,
            size = ButtonSize.Small,
        )
    }
}

/**
 * The ⓘ's answer, opened by a click: what the tool is for, and the web's two
 * ways to learn more — **More** (its page on the documentation site) and
 * **Watch video** (its tutorial clip) — beside the way in.
 */
@Composable
internal fun ToolInfoDialog(
    tool: ToolPresentation?,
    describe: (ToolPresentation) -> String,
    guide: (ToolPresentation) -> ToolGuide,
    onOpen: (ToolPresentation) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var video by remember(tool) { mutableStateOf<String?>(null) }
    val links = remember(tool) { tool?.let(guide) }
    ZillitDialogShell(
        title = tool?.label.orEmpty(),
        icon = tool?.icon,
        visible = tool != null && video == null,
        onDismiss = onDismiss,
        width = INFO_WIDTH,
        actions = {
            links?.let { shown ->
                ZillitButton(
                    text = str(S.desktop_ft_watch_video),
                    onClick = { video = shown.videoUrl },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Play,
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ok), onClick = onDismiss, variant = ButtonVariant.Secondary)
            tool?.let { shown ->
                ZillitButton(text = str(S.drive_btn_open), onClick = { onDismiss(); onOpen(shown) })
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = tool?.let(describe).orEmpty(),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            links?.tutorialUrl?.let { url ->
                ZillitText(
                    text = str(S.more),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = INFO_BLUE,
                    modifier = Modifier.clip(ZillitTheme.shapes.small).clickable { onOpenUrl(url) },
                )
            }
        }
    }
    VideoDialog(video, onOpenUrl) { video = null }
}

/** The tool's tutorial clip, played in the app — the web's `ShowVideoForTools`. */
@Composable
private fun VideoDialog(url: String?, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_ft_watch_video),
        icon = ZillitIcons.Play,
        visible = url != null,
        onDismiss = onDismiss,
        width = VIDEO_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ok), onClick = onDismiss)
        },
    ) {
        ZillitVideoView(
            url = url,
            failed = false,
            onOpenOutside = url?.let { shown -> { onOpenUrl(shown) } },
            modifier = Modifier.fillMaxWidth().height(VIDEO_HEIGHT),
        )
    }
}

private val SECTION_GAP = 26.dp
private val GROUP_INNER_GAP = 16.dp
private val GROUP_HEAD_GAP = 14.dp
private val GROUP_HEAD_RULE_GAP = 14.dp
private val GLYPH_BOX = 34.dp
private val GLYPH = 27.dp
private val TILE_MIN = 260.dp
private val TILE_GAP = 14.dp
private val TILE_MIN_HEIGHT = 66.dp
private val TILE_SHAPE = RoundedCornerShape(10.dp)
private val STRIPE = 3.dp
private const val HOVER_BORDER_ALPHA = 0.35f
private const val DRAG_GHOST_ALPHA = 0.6f
private val DRAG_GRAB_X = 24.dp
private val DRAG_GRAB_Y = 28.dp
private val BADGE_SIZE = 22.dp
private const val BADGE_CAP = 99
private val BADGE_RED = Color(0xFFF5222D)
private val INFO_BLUE = Color(0xFF3B82F6)
private val INFO_SIZE = 26.dp
private const val NEW_GROUP_HIGHLIGHT_MS = 2000L
private const val NEW_GROUP_TINT = 0.6f
private val NAME_FIELD_WIDTH = 240.dp
private val INFO_WIDTH = 460.dp
private val VIDEO_WIDTH = 900.dp
private val VIDEO_HEIGHT = 500.dp
