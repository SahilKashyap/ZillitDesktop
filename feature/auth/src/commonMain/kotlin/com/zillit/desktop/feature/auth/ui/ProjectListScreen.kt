package com.zillit.desktop.feature.auth.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectFilter
import com.zillit.desktop.feature.auth.domain.highlightRanges
import kotlinx.coroutines.delay

/**
 * Choosing a production — a port of the web client's `/projects`.
 *
 * ## Why this is full-bleed rather than the shared auth card
 *
 * The other sign-in steps are short forms and sit in a 420pt card. This one is a
 * list that can run to dozens of rows with a search box and a filter above it;
 * inside that card it would be a scrolling column two thirds empty on a
 * 1440pt-wide window. The web makes the same split — `/device/login` is a
 * centred card, `/projects` is a page.
 *
 * Light and dark both come from [ZillitTheme]; unlike the QR page there is no
 * brand-splash argument for pinning one, and by this point the user's stored
 * preference has loaded.
 */
@Composable
internal fun ProjectListScreen(
    state: AuthUiState,
    onEvent: (AuthEvent) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    createViewModel: CreateProductionViewModel? = null,
    joinViewModel: JoinProductionViewModel? = null,
) {
    Box(modifier) {
        ProjectListBody(state, onEvent, themeMode, onThemeModeChange)

        // Always composed, visibility-driven: an `if` on the flag would
        // unmount the dialog on dismiss and skip its exit animation. Null
        // view models (the graph could not supply one) still mean no dialog.
        if (joinViewModel != null) {
            val joinState by joinViewModel.state.collectAsState()
            JoinProductionDialog(
                state = joinState,
                onEvent = joinViewModel::onEvent,
                visible = state.isJoining,
            )
        }

        if (createViewModel != null) {
            val createState by createViewModel.state.collectAsState()
            // Opened fires when the dialog opens, not when the screen mounts.
            LaunchedEffect(state.isCreatingProduction) {
                if (state.isCreatingProduction) createViewModel.onEvent(CreateProductionEvent.Opened)
            }
            CreateProductionDialog(
                state = createState,
                onEvent = createViewModel::onEvent,
                onDismiss = { onEvent(AuthEvent.DismissCreateProduction) },
                visible = state.isCreatingProduction,
            )
        }
    }
}

@Composable
private fun ProjectListBody(
    state: AuthUiState,
    onEvent: (AuthEvent) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas),
    ) {
        ProjectListHeader(
            state = state,
            onEvent = onEvent,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )

        if (state.isShowingSavedProjects) OfflineNotice()

        // Without this a failed selection is completely silent — the row stops
        // responding and nothing says why.
        state.error?.let { message ->
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.dangerSoft)
                    .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
            )
        }

        val content = when {
            state.isBusy && state.projects.isEmpty() -> ListContent.Loading
            state.projects.isEmpty() -> ListContent.Empty
            state.visibleProjects.isEmpty() -> ListContent.NoMatches
            else -> ListContent.Grid
        }

        // Crossfade rather than a cut: the last filtered card fading out and
        // the "no matches" message fading in reads as one motion.
        Crossfade(
            targetState = content,
            animationSpec = tween(CONTENT_SWAP_MS),
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            when (view) {
                ListContent.Loading -> CentredMessage(
                    text = "Loading your projects...",
                    icon = ZillitIcons.Reload,
                )

                ListContent.Empty -> CentredMessage(
                    text = "This device isn't on any project yet. " +
                        "Ask a coordinator to add you, then sign in again.",
                    icon = ZillitIcons.Info,
                )

                ListContent.NoMatches -> CentredMessage(
                    text = "No project matches \"${state.projectFilter.trim()}\".",
                    icon = ZillitIcons.Search,
                )

                ListContent.Grid -> ProjectGrid(state, onEvent)
            }
        }
    }
}

/**
 * Offline: the cards are the last list this device was given. Said plainly,
 * in a calm colour — the productions are real, only the refresh is missing —
 * so nobody reads a saved list as a stale one.
 */
@Composable
private fun OfflineNotice() {
    ZillitText(
        text = "You're offline — showing the projects saved on this device. " +
            "They'll refresh when the connection is back.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.infoSoft)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
    )
}

@Composable
private fun ProjectListHeader(
    state: AuthUiState,
    onEvent: (AuthEvent) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(TITLE_ACCENT_WIDTH)
                    .height(TITLE_ACCENT_HEIGHT)
                    .clip(ZillitTheme.shapes.pill)
                    .background(ZillitTheme.colors.accent),
            )
            Spacer(Modifier.width(ZillitTheme.spacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = "Projects",
                    style = ZillitTheme.typography.displayLarge,
                )
                ZillitText(
                    text = countSentence(state.visibleProjects.size, state.projects.size),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            Spacer(Modifier.weight(1f))

            ZillitIconButton(
                icon = themeMode.nextIcon(),
                contentDescription = "Switch theme",
                onClick = { onThemeModeChange(themeMode.next()) },
            )
        }

        FilterBar(state, onEvent)
    }
}

/**
 * Search and the two entry actions on one line; the category filter as a chip
 * row beneath. Chips rather than the old dropdown: four choices earn showing,
 * not hiding, and the selected one stays visible while browsing.
 */
@Composable
private fun FilterBar(state: AuthUiState, onEvent: (AuthEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.projectFilter,
            onValueChange = { onEvent(AuthEvent.ProjectFilterChanged(it)) },
            placeholder = "Search by name, code, or parent...",
            modifier = Modifier.widthIn(max = SEARCH_MAX_WIDTH).weight(1f),
        )
        ZillitButton(
            text = "Join a project",
            onClick = { onEvent(AuthEvent.StartJoin) },
            variant = ButtonVariant.Secondary,
        )
        ZillitButton(
            text = "Start a project",
            onClick = { onEvent(AuthEvent.StartNewProject) },
            leadingIcon = ZillitIcons.Add,
            variant = ButtonVariant.Primary,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ProjectFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.projectCategory == filter,
                onClick = { onEvent(AuthEvent.ProjectCategoryChanged(filter)) },
            )
        }
    }
}

@Composable
private fun ProjectGrid(state: AuthUiState, onEvent: (AuthEvent) -> Unit) {
    val gridState = rememberLazyGridState()
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CARD_MIN_WIDTH),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PAGE_PADDING,
            end = PAGE_PADDING,
            top = ZillitTheme.spacing.md,
            bottom = ZillitTheme.spacing.md,
        ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        itemsIndexed(state.visibleProjects, key = { _, project -> project.id }) { index, project ->
            ProjectCard(
                project = project,
                query = state.projectFilter,
                unread = state.projectUnread[project.id] ?: 0,
                onOpen = { onEvent(AuthEvent.SelectProject(project)) },
                onToggleFavourite = { onEvent(AuthEvent.ToggleFavourite(project)) },
                // animateItem moves survivors smoothly when a star or filter
                // reorders the grid; the entrance staggers newcomers in. A card
                // that merely moved keeps its state, so it never re-enters.
                modifier = Modifier
                    .animateItem(fadeInSpec = null, fadeOutSpec = tween(CARD_EXIT_MS))
                    .cardEntrance(index),
            )
        }
    }
}

/**
 * One production.
 *
 * A pending row is deliberately not clickable and says why. The web renders it
 * with a greyed style but still routes the click, which fails somewhere further
 * in with no explanation.
 */
@Composable
@Suppress("LongParameterList")
private fun ProjectCard(
    project: Project,
    query: String,
    onOpen: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
    unread: Int = 0,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val lifted = hovered && project.isOpenable

    Box(modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .shadow(if (lifted) CARD_LIFT else 0.dp, ZillitTheme.shapes.medium)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(
                width = HAIRLINE,
                color = if (lifted) colors.borderStrong else colors.border,
                shape = ZillitTheme.shapes.medium,
            )
            .hoverable(interaction)
            .then(if (project.isOpenable) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        // The identity strip: the same hue the avatar derives from the name,
        // so a production can be found again by colour alone. Pending cards
        // stay grey — there is no inside to go to yet.
        Box(
            Modifier
                .fillMaxWidth()
                .height(IDENTITY_STRIP)
                .background(
                    if (project.isOpenable) avatarHue(project.name) else colors.surfaceSunken,
                ),
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ProjectCardHeader(project, query, onToggleFavourite)

            // Collected, not sampled: the picker is on screen before the label
            // fetch returns, and the card has to repaint when it does.
            val labels by Labels.dictionary.collectAsState()

            ZillitText(
                text = project.subtitle(labels),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                modifier = Modifier.height(SUBTITLE_HEIGHT), // Fixed, so cards align in the grid
            )

            ProjectCardFooter(project)
        }
    }
    // Over the card's corner, like the launcher tiles inside — which
    // production has news, visible before choosing one.
    if (unread > 0) {
        com.zillit.desktop.core.designsystem.component.ZillitBadge(
            count = unread,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ZillitTheme.spacing.xs),
        )
    }
    }
}

/** Avatar, name and code, and the favourite star. */
@Composable
private fun ProjectCardHeader(project: Project, query: String, onToggleFavourite: () -> Unit) {
    val colors = ZillitTheme.colors

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = project.name, size = CARD_AVATAR)

        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = highlighted(project.name, query, colors.accent),
                style = ZillitTheme.typography.titleSmall,
                color = if (project.isOpenable) colors.textPrimary else colors.textMuted,
                maxLines = 1,
            )
            ZillitText(
                text = if (project.code.isBlank()) "No code" else "#${project.code}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }

        // A production awaiting approval cannot be favourited: there is nothing
        // yet to come back to.
        if (!project.isPending) {
            ZillitIconButton(
                icon = if (project.isFavourite) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
                contentDescription = if (project.isFavourite) {
                    "Remove from favourites"
                } else {
                    "Add to favourites"
                },
                onClick = onToggleFavourite,
                tint = if (project.isFavourite) colors.warning else colors.textMuted,
                modifier = Modifier.size(STAR_SIZE),
            )
        }
    }
}

/** What this production is, and what is waiting in it. */
@Composable
private fun ProjectCardFooter(project: Project) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            if (project.isAdmin) ZillitTag("Admin", tone = TagTone.Accent)
            if (project.isPersonal) ZillitTag("Personal", tone = TagTone.Info)
            if (project.isPending) ZillitTag("Awaiting approval", tone = TagTone.Warning)
        }
        // The count lives on the card's corner (from the live device-scope
        // fetch); the list DTO's own number is a snapshot the server took
        // when the list was built, and two numbers on one card disagreed.
    }
}

/**
 * The secondary line: parent production, type.
 *
 * One line rather than the web's hover popover. A popover hides the project code
 * behind a hover on a list whose main use is finding a production by its code,
 * and it is unreachable by keyboard.
 */
/**
 * `in Parent · Feature` — what kind of production this is.
 *
 * The type and sub-type arrive as translation keys, not display names:
 * `GET project` returns `project_sub_type: "feature_label"` and
 * `project_type: "entertainment_industry_label"`. Printed as they came, every
 * card on the picker read `feature_label`.
 *
 * [labels] is passed in rather than read from [Labels] directly so the caller
 * observes it — this screen is on-screen before the dictionaries finish
 * loading, and a sampled read would leave the fallback showing until the window
 * was reopened.
 *
 * The domain [Project.type] is `project_type_id` — the filterable id, chosen
 * over the label precisely so filtering survives a language change. It is
 * key-shaped too (`entertainment`), so it goes through the same lookup, and
 * falls back to a humanised form when the dictionary has no entry for it.
 */
internal fun Project.subtitle(labels: LabelDictionary): String = listOfNotNull(
    parentName?.let { "in $it" },
    (subType?.takeIf { it.isNotBlank() } ?: type?.takeIf { it.isNotBlank() })
        ?.let { labels.translate(it) },
).joinToString(" · ").ifEmpty { "Project" }

/** Bolds the parts of [text] matching [query]. */
private fun highlighted(
    text: String,
    query: String,
    accent: androidx.compose.ui.graphics.Color,
): AnnotatedString = AnnotatedString.Builder(text).apply {
    highlightRanges(text, query).forEach { range ->
        addStyle(
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
            range.first,
            range.last + 1,
        )
    }
}.toAnnotatedString()

/** Which of the board's four faces is showing — the crossfade's key. */
private enum class ListContent { Loading, Empty, NoMatches, Grid }

/**
 * The entrance: rise and fade, offset by grid position so the board deals the
 * cards rather than dropping them at once. Runs once per card composition —
 * a card that survives a filter keystroke keeps its state and stays put; only
 * genuinely new cards enter. The stagger caps so a long grid's tail does not
 * arrive noticeably late.
 */
@Composable
private fun Modifier.cardEntrance(index: Int): Modifier {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, MAX_STAGGER_STEPS) * STAGGER_STEP_MS)
        entrance.animateTo(1f, tween(ENTRANCE_MS))
    }
    return graphicsLayer {
        alpha = entrance.value
        translationY = (1f - entrance.value) * ENTRANCE_RISE.toPx()
    }
}

/** The header's one line of orientation — counts, in words that scan. */
private fun countSentence(visible: Int, total: Int): String = when {
    total == 0 -> "No projects yet."
    visible != total -> "Showing $visible of $total projects."
    total == 1 -> "You have access to 1 project."
    else -> "You have access to $total projects."
}

private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
    ThemeMode.Dark -> ThemeMode.System
}

private fun ThemeMode.nextIcon() = when (this) {
    ThemeMode.System -> ZillitIcons.Monitor
    ThemeMode.Light -> ZillitIcons.Sun
    ThemeMode.Dark -> ZillitIcons.Moon
}

@Composable
private fun CentredMessage(text: String, icon: ImageVector? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            modifier = Modifier.widthIn(max = MESSAGE_MAX_WIDTH).padding(PAGE_PADDING)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(ICON_DISC)
                        .clip(CircleShape)
                        .background(ZillitTheme.colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(
                        icon = icon,
                        contentDescription = null,
                        tint = ZillitTheme.colors.accentText,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }
            ZillitText(
                text = text,
                style = ZillitTheme.typography.bodyLarge,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

private val PAGE_PADDING = 16.dp
private val SEARCH_MAX_WIDTH = 420.dp
private val MESSAGE_MAX_WIDTH = 460.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val HAIRLINE = 1.dp
private val CARD_MIN_WIDTH = 300.dp
private val CARD_HEIGHT = 168.dp

/** The hover lift — enough shadow to say "clickable", not enough to float. */
private val CARD_LIFT = 6.dp

/** The card's colour-coded top edge; see the comment at its use. */
private val IDENTITY_STRIP = 4.dp
private val CARD_AVATAR = 40.dp

/** Fixed so every card's tags and badge sit on the same line across the grid. */
private val SUBTITLE_HEIGHT = 32.dp
private val STAR_SIZE = 24.dp

/** The empty states' icon, on its soft disc. */
private val ICON_DISC = 64.dp
private val ICON_SIZE = 28.dp

// The deal-in: each card rises this far while fading, offset per position.
// The built-in appearance fade is off (the stagger owns arrival); removal
// fades out through animateItem, since a filtered-out card has no composable
// left to animate itself.
private val ENTRANCE_RISE = 16.dp
private const val CARD_EXIT_MS = 140
private const val CONTENT_SWAP_MS = 180
private const val ENTRANCE_MS = 220
private const val STAGGER_STEP_MS = 40L
private const val MAX_STAGGER_STEPS = 12
