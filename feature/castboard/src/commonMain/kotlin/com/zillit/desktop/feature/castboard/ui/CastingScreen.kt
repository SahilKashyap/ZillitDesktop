package com.zillit.desktop.feature.castboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingBadges
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingMedia
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.CastingUnit

/**
 * The Casting tool.
 *
 * A board of characters and whoever is up for each, filtered by how far they
 * have got — selected, shortlisted, published — which is the shape both the
 * web's tabs and a casting director's day take.
 */
@Composable
fun CastingScreen(
    state: CastingUiState,
    onEvent: (CastingEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Which board is on screen — its title, and whether rows carry scenes. */
    board: BoardTool = BoardTool.Casting,
    /** Fetches a candidate's photo; null draws initials instead. */
    loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)? = null,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.hasNoAccess -> ZillitEmptyState(
                title = "No ${state.title(board.title).lowercase()} access",
                message = "Neither list is shared with you on this project.",
                icon = ZillitIcons.Shield,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(Modifier.fillMaxSize()) {
                Header(state, onEvent, board)
                ZillitDivider()
                Board(state, onEvent, loadPhoto, board)
            }
        }
        DiscussionDialog(state, onEvent)
    }
}

@Composable
private fun Header(state: CastingUiState, onEvent: (CastingEvent) -> Unit, board: BoardTool) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = state.title(board.title),
            eyebrow = "Film tools",
            description = state.unit?.label
                ?: if (board.showsScenes) {
                    "Characters, their costumes and the scenes they wear them in."
                } else {
                    "Characters and who is up for them."
                },
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(CastingEvent.Refresh) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
            },
        )
        // Only the lists this viewer has; one list needs no switch.
        if (state.viewer.units.size > 1) ListTabs(state, onEvent)
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitTabStrip(
                tabs = CastingViewModel.STATUSES.map {
                    ZillitTab(id = it.wire, label = it.label, count = state.stageUnread(it))
                },
                activeId = state.status.wire,
                onSelect = { wire ->
                    CastingViewModel.STATUSES.firstOrNull { it.wire == wire }
                        ?.let { onEvent(CastingEvent.StatusChanged(it)) }
                },
                modifier = Modifier.weight(1f),
            )
            ZillitSearchField(
                value = state.query,
                onValueChange = { onEvent(CastingEvent.QueryChanged(it)) },
                placeholder = "Character or name",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
        }
    }
}

@Composable
private fun Board(
    state: CastingUiState,
    onEvent: (CastingEvent) -> Unit,
    loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)?,
    board: BoardTool,
) {
    Column(Modifier.fillMaxSize()) {
        state.error?.let { message ->
            ZillitSectionCard(modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = message,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = "Dismiss",
                        onClick = { onEvent(CastingEvent.DismissError) },
                        variant = ButtonVariant.Tertiary,
                    )
                }
            }
        }
        when {
            state.loading && state.entries.isEmpty() -> Centre("Loading…")

            state.visible.isEmpty() && state.query.isNotBlank() -> ZillitEmptyState(
                title = "Nobody by that name",
                message = "No character or candidate here matches “${state.query}”.",
                icon = ZillitIcons.Search,
            )

            state.entries.isEmpty() -> ZillitEmptyState(
                title = "Nobody at this stage yet",
                message = "${state.status.label} is empty for ${state.unit?.label ?: "this list"}.",
                icon = ZillitIcons.Users,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_WIDTH),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(state.visible, key = { it.id }) { entry ->
                    EntryCard(state, entry, loadPhoto, board, onEvent)
                }
            }
        }
    }

    // Moving someone between stages already set a notice; nothing showed it,
    // so a move that worked looked identical to one that had not registered.
    ZillitToast(
        message = state.notice,
        onDismiss = { onEvent(CastingEvent.DismissError) },
        tone = ZillitToastTone.Success,
    )
}

@Composable
private fun Centre(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** One character: the picture, who it is, and where they are in the run. */
@Composable
private fun EntryCard(
    state: CastingUiState,
    entry: CastingEntry,
    loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)?,
    board: BoardTool,
    onEvent: (CastingEvent) -> Unit,
) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Photo(entry, loadPhoto)
        DiscussionButton(entry, state.entryUnread(entry), onEvent)
        ZillitText(
            text = entry.characterName.ifBlank { "Unnamed character" },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        // The candidates, in the order the service lists them: the first is
        // the one the board is really about.
        ZillitText(
            text = entry.talentNames.joinToString(", ").ifBlank { "No candidate yet" },
            style = ZillitTheme.typography.bodySmall,
            color = if (entry.talentNames.isEmpty()) {
                ZillitTheme.colors.textMuted
            } else {
                ZillitTheme.colors.textSecondary
            },
            maxLines = 2,
        )
        // Where this one can go next: the stages it is not already at. Shown
        // whatever the rights — CastingViewModel.move answers a press without
        // them by offering to ask an admin, which is more use than no button.
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            CastingViewModel.STATUSES
                .filter { it != state.status }
                .forEach { target ->
                    ZillitButton(
                        text = "→ ${target.label}",
                        onClick = { onEvent(CastingEvent.MoveTo(entry.id, target)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            entry.episode.takeIf { it.isNotBlank() }?.let {
                ZillitStatusPill(label = "Ep $it", tone = StatusTone.Neutral)
            }
            // A costume is worn in scenes; a part has a hierarchy. Each board
            // shows the one its rows actually carry.
            if (board.showsScenes) {
                entry.scenes.takeIf { it.isNotBlank() }?.let {
                    ZillitStatusPill(label = "Sc $it", tone = StatusTone.Progress)
                }
            } else {
                entry.hierarchy.takeIf { it.isNotBlank() }?.let {
                    ZillitStatusPill(label = it, tone = StatusTone.Progress)
                }
            }
            entry.gender.takeIf { it.isNotBlank() }?.let {
                ZillitStatusPill(label = it, tone = StatusTone.Neutral)
            }
        }
    }
}

@Composable
private fun Photo(entry: CastingEntry, loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)?) {
    val media = entry.media
    val picture by produceState<ImageBitmap?>(initialValue = null, media, loadPhoto) {
        value = if (media != null && loadPhoto != null) loadPhoto(media) else null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PHOTO_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = picture
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = entry.characterName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No picture is the common case on a fresh list; initials read
            // better than an empty grey rectangle.
            ZillitText(
                text = entry.initials(),
                style = ZillitTheme.typography.titleMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** "RM" — from the first candidate, or the character when nobody is cast. */
internal fun CastingEntry.initials(): String =
    (talentNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: characterName)
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }

private val CARD_WIDTH = 200.dp
private val PHOTO_HEIGHT = 150.dp
private val SEARCH_WIDTH = 260.dp

/**
 * One entry's discussion.
 *
 * Where a casting director answers "is she available that week?" against the
 * face itself. The phones and the web have carried this thread for years; on
 * this client the conversation had nowhere to live.
 */
@Composable
private fun DiscussionDialog(state: CastingUiState, onEvent: (CastingEvent) -> Unit) {
    val entry = state.openEntry ?: return
    ZillitDialogShell(
        title = entry.characterName.ifBlank { "Discussion" },
        subtitle = entry.talentNames.joinToString(", ").takeIf { it.isNotBlank() },
        icon = ZillitIcons.Chat,
        visible = true,
        onDismiss = { onEvent(CastingEvent.CloseDiscussion) },
    ) {
        when {
            state.discussionLoading && state.discussion.isEmpty() -> ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            state.discussion.isEmpty() -> ZillitText(
                text = "Nothing said about this one yet.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> state.discussion.forEach { message ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs)) {
                    ZillitText(
                        text = if (message.isMine) "You" else "Someone",
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(
                        // A line whose body would not decrypt keeps its place;
                        // saying so beats an empty bubble.
                        text = message.body.ifBlank { "(could not be read)" },
                        style = ZillitTheme.typography.bodySmall,
                        color = if (message.body.isBlank()) {
                            ZillitTheme.colors.textMuted
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitTextField(
                value = state.discussionDraft,
                onValueChange = { onEvent(CastingEvent.DiscussionDraftChanged(it)) },
                placeholder = "Say something about this one…",
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Send",
                onClick = { onEvent(CastingEvent.SendDiscussion) },
                enabled = state.discussionDraft.isNotBlank() && !state.discussionSending,
                loading = state.discussionSending,
            )
        }
    }
}

/** The two lists, each with its unread — the web's tab chips. */
@Composable
private fun ListTabs(state: CastingUiState, onEvent: (CastingEvent) -> Unit) {
    ZillitTabStrip(
        tabs = state.viewer.units.map { ZillitTab(id = it.unitId, label = it.label, count = state.listUnread(it)) },
        activeId = state.unit?.unitId,
        onSelect = { id ->
            state.viewer.units.firstOrNull { it.unitId == id }?.let { onEvent(CastingEvent.UnitChanged(it)) }
        },
    )
}

/** The thread button, with the character's unread — its folder rows and its thread, as the web's badges. */
@Composable
private fun DiscussionButton(entry: CastingEntry, unread: Int, onEvent: (CastingEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitButton(
            text = "Discussion",
            onClick = { onEvent(CastingEvent.OpenDiscussion(entry)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitBadge(count = unread)
    }
}

private fun CastingUiState.listUnread(unit: CastingUnit): Int = unread.tool(CastingBadges.toolOf(unit.kind))

private fun CastingUiState.stageUnread(status: CastingStatus): Int =
    unit?.let { unread.status(CastingBadges.toolOf(it.kind), status) } ?: 0

private fun CastingUiState.entryUnread(entry: CastingEntry): Int =
    unit?.let { unread.entry(CastingBadges.toolOf(it.kind), status, entry) } ?: 0
