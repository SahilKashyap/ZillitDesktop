package com.zillit.desktop.feature.budget.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRules

/**
 * The Budget tool: a rail of the budget's versions and the conversations
 * about it, beside the conversation that is open.
 *
 * The web's `CommonBudget.jsx` layout — a 30 % sider and the chat beside it —
 * with the rail's three faces (episodes, department directory, versions)
 * cross-fading in place rather than replacing each other outright.
 */
@Composable
fun BudgetScreen(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    modifier: Modifier = Modifier,
    seams: BudgetScreenSeams = BudgetScreenSeams(),
) {
    val colors = ZillitTheme.colors
    Box(modifier.fillMaxSize().background(colors.canvas)) {
        when {
            state.viewer.resolved && !state.viewer.canView(state.mode) -> ZillitEmptyState(
                title = "No access to ${state.mode.title}",
                message = "This tool is not shared with you. An administrator can grant viewing rights.",
                icon = ZillitIcons.Shield,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Row(Modifier.fillMaxSize()) {
                Rail(state, onEvent, seams, Modifier.width(RAIL_WIDTH).fillMaxHeight())
                ZillitVerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val conversation = seams.conversation
                    if (state.selectedChat != null && conversation != null) {
                        conversation(state)
                    } else {
                        ConversationPlaceholder(state, Modifier.align(Alignment.Center))
                    }
                }
            }
        }

        BudgetDialogs(state, onEvent, seams)

        ZillitErrorToast(message = state.error, onDismiss = { onEvent(BudgetEvent.DismissMessage) })
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(BudgetEvent.DismissMessage) },
            tone = ZillitToastTone.Success,
        )
    }
}

/** The left third: title, breadcrumb, and whichever face the stage calls for. */
@Composable
private fun Rail(
    state: BudgetUiState,
    onEvent: (BudgetEvent) -> Unit,
    seams: BudgetScreenSeams,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Box(modifier.background(colors.surface)) {
        Column(Modifier.fillMaxSize()) {
            RailHeader(state, onEvent)
            AnimatedContent(
                targetState = state.stage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "budget-stage",
            ) { stage ->
                when (stage) {
                    BudgetStage.Episodes -> EpisodeList(state, onEvent)
                    BudgetStage.Directory -> DepartmentDirectory(state, onEvent)
                    BudgetStage.Versions -> VersionsPane(state, onEvent, seams)
                }
            }
        }
        RailFloatingActions(state, onEvent, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun RailHeader(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(
            start = ZillitTheme.spacing.lg,
            end = ZillitTheme.spacing.lg,
            top = ZillitTheme.spacing.lg,
            bottom = ZillitTheme.spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                Modifier.size(HEADER_ICON).clip(ZillitTheme.shapes.medium).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitToolIcons.Budget, tint = colors.accentText, size = 18.dp)
            }
            Column {
                ZillitText(
                    text = state.mode.title,
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                ZillitText(
                    text = when (state.mode) {
                        BudgetMode.Main -> "The production's budget and the conversations about it"
                        BudgetMode.Department -> "One budget per department, discussed with its crew"
                    },
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        Breadcrumb(state, onEvent)
    }
}

/**
 * `CustomBreadcrumb.jsx`: Episode List / Episode - N / Department List /
 * Department — only the crumbs that apply, the movable ones underlined.
 */
@Composable
private fun Breadcrumb(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    val crumbs = buildList {
        if (state.context.isTelevision && state.selectedEpisode.isNotBlank()) {
            add("Episode List" to { onEvent(BudgetEvent.BackToEpisodes) })
            add("Episode - ${state.selectedEpisode}" to null)
        }
        if (state.mode == BudgetMode.Department && state.openDepartment != null) {
            add("Department List" to { onEvent(BudgetEvent.BackToDirectory) })
            add(state.openDepartment.name to null)
        }
    }
    if (crumbs.isEmpty()) return
    val colors = ZillitTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
    ) {
        crumbs.forEachIndexed { index, (label, onClick) ->
            if (index > 0) {
                ZillitText(text = "/", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
            ZillitText(
                text = label,
                style = if (onClick != null) {
                    ZillitTheme.typography.label.copy(textDecoration = TextDecoration.Underline)
                } else {
                    ZillitTheme.typography.label
                },
                color = if (onClick != null) colors.accentText else colors.textSecondary,
                maxLines = 1,
                modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
        }
    }
}

// -- episodes ----------------------------------------------------------------

/** `EpisodeList.jsx`: one card per episode, with everything unread beneath it. */
@Composable
private fun EpisodeList(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ZillitSearchField(
            value = state.episodeSearch,
            onValueChange = { onEvent(BudgetEvent.EpisodeSearch(it)) },
            placeholder = "Search episode",
            modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg),
        )
        val episodes = state.episodesVisible
        if (episodes.isEmpty()) {
            RailEmpty(
                title = if (state.loading) "Loading…" else "No episodes yet",
                message = if (state.loading) null else "Upload a budget and its episode appears here.",
            )
            return
        }
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            episodes.forEach { episode ->
                RailRowCard(
                    title = "Episode $episode",
                    subtitle = state.episodes[episode].orEmpty().size.let { "$it budget".plural(it) },
                    leading = { RailInitial(episode.take(2)) },
                    badge = state.episodeUnread(episode),
                    onClick = { onEvent(BudgetEvent.OpenEpisode(episode)) },
                )
            }
        }
    }
}

// -- the department directory ----------------------------------------------------

/** `AddAndShowDepartmentList.jsx`: search, then a card per department with a budget. */
@Composable
private fun DepartmentDirectory(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (BudgetRules.showsDepartmentSearch(state.viewer)) {
            ZillitSearchField(
                value = state.directorySearch,
                onValueChange = { onEvent(BudgetEvent.DirectorySearch(it)) },
                placeholder = "Search department",
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg),
            )
        }
        val rows = state.directoryVisible
        when {
            state.loading && rows.isEmpty() -> LoadingRows()
            rows.isEmpty() -> RailEmpty(
                title = if (state.directorySearch.isBlank()) {
                    "No department has a budget yet"
                } else {
                    "No department matches"
                },
                message = if (state.directorySearch.isBlank() && state.canPost) {
                    "Use + to upload the first one."
                } else {
                    null
                },
            )

            else -> ZillitScrollColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    top = ZillitTheme.spacing.md,
                    bottom = FAB_CLEARANCE,
                ),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                rows.forEach { row ->
                    RailRowCard(
                        title = row.department.name,
                        subtitle = null,
                        leading = { RailInitial(row.department.name.take(1)) },
                        badge = row.unread,
                        onClick = { onEvent(BudgetEvent.OpenDepartment(row.department.id)) },
                    )
                }
            }
        }
    }
}

// -- shared rail furniture --------------------------------------------------------

/**
 * One tappable card of the rail — a department, an episode, a person or a
 * room: a leading face, a title with its badge, a chevron.
 */
@Composable
internal fun RailRowCard(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailingChevron: Boolean = true,
    titleSuffix: String? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> colors.surfaceSelected
        hovered -> colors.surfaceHover
        else -> colors.surfaceSunken
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(background)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = title,
                    style = if (badge > 0) ZillitTheme.typography.titleSmall else ZillitTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleSuffix?.let {
                    ZillitText(
                        text = it,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
            }
        }
        if (badge > 0) ZillitBadge(count = badge)
        if (trailingChevron) ZillitIcon(ZillitIcons.ChevronRight, tint = colors.textMuted, size = CHEVRON_SIZE)
    }
}

/** A lettered disc — the web's antd `Avatar` with the first letter. */
@Composable
internal fun RailInitial(text: String, size: androidx.compose.ui.unit.Dp = INITIAL_SIZE) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.titleSmall,
            color = colors.accentText,
            maxLines = 1,
        )
    }
}

/** A person's face, or their initials while the picture loads or when there is none. */
@Composable
internal fun RailFace(name: String, userId: String, loadAvatar: suspend (String) -> ImageBitmap?) {
    val image by androidx.compose.runtime.produceState<ImageBitmap?>(null, userId) {
        value = runCatching { loadAvatar(userId) }.getOrNull()
    }
    ZillitAvatar(name = name, size = INITIAL_SIZE, image = image, userId = userId)
}

@Composable
internal fun RailEmpty(title: String, message: String?, icon: ImageVector? = null) {
    Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
        ZillitEmptyState(title = title, message = message, icon = icon)
    }
}

@Composable
internal fun LoadingRows() {
    Column(
        Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = SKELETON_HEIGHT) }
    }
}

/**
 * The rail's floating buttons (`CommonBudget.jsx:2117-2260`): the chat
 * bubble that offers "Add member" / "Create group", and the "+" that uploads
 * — or, on the directory, opens the drawer of departments.
 */
@Composable
private fun RailFloatingActions(state: BudgetUiState, onEvent: (BudgetEvent) -> Unit, modifier: Modifier = Modifier) {
    if (state.stage == BudgetStage.Episodes) return
    Row(
        modifier = modifier.padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.stage == BudgetStage.Versions) {
            Box {
                FloatingButton(
                    icon = ZillitIcons.Chat,
                    tooltip = "Discuss this budget",
                    primary = false,
                    onClick = { onEvent(BudgetEvent.ChatMenu(!state.chatMenuOpen)) },
                )
                ChatActionsMenu(state, onEvent)
            }
        }
        FloatingButton(
            icon = ZillitIcons.Add,
            tooltip = if (state.stage == BudgetStage.Directory) "Add a department's budget" else "Upload budget",
            primary = true,
            onClick = {
                if (state.stage == BudgetStage.Directory) {
                    onEvent(BudgetEvent.OpenDrawer)
                } else {
                    onEvent(BudgetEvent.UploadRequested())
                }
            },
        )
    }
}

@Composable
internal fun FloatingButton(icon: ImageVector, tooltip: String, primary: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        primary && hovered -> colors.accentHover
        primary -> colors.accent
        hovered -> colors.surfaceHover
        else -> colors.surfaceRaised
    }
    ZillitTooltip(tooltip) {
        Box(
            Modifier
                .size(FAB_SIZE)
                .shadow(FAB_SHADOW, CircleShape)
                .clip(CircleShape)
                .background(background)
                .hoverable(interaction)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon, tint = if (primary) colors.textOnAccent else colors.accentText, size = 20.dp)
        }
    }
}

/** The right two thirds before a thread is picked — the web's `Empty` with its two sentences. */
@Composable
private fun ConversationPlaceholder(state: BudgetUiState, modifier: Modifier = Modifier) {
    val (title, message) = when {
        state.mode == BudgetMode.Department && state.stage == BudgetStage.Directory ->
            "Pick a department" to "Click on any department to start a conversation about its budget."
        state.stage == BudgetStage.Episodes ->
            "Pick an episode" to "Each episode keeps its own budgets and conversations."
        !state.hasDocuments ->
            "No budget yet" to "Upload a budget, then discuss it with the crew who can see it."
        else ->
            "Pick a conversation" to "Click on any user or group to start a conversation."
    }
    ZillitEmptyState(title = title, message = message, icon = ZillitIcons.Chat, modifier = modifier)
}

private fun String.plural(count: Int): String = if (count == 1) this else this + "s"

private val RAIL_WIDTH = 380.dp
private val HEADER_ICON = 36.dp
private val INITIAL_SIZE = 40.dp
private val FAB_SIZE = 48.dp
private val FAB_SHADOW = 6.dp
private val FAB_CLEARANCE = 88.dp
private val SKELETON_HEIGHT = 56.dp
private const val SKELETON_ROWS = 4
private val CHEVRON_SIZE = 16.dp
