package com.zillit.desktop.feature.externalusers.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.Gender
import com.zillit.desktop.feature.externalusers.domain.phoneLine

/**
 * External Users: the production's outside contacts — the web's
 * `Externaluser.jsx` as one desktop pane: a header strip with the search,
 * the type filter and Add User; a card grid; and the two dialogs.
 */
@Composable
fun ExternalUsersScreen(
    state: ExternalUsersUiState,
    onEvent: (ExternalUsersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.viewer.isBlocked -> Centred("You don't have access to External Users.")
            else -> Page(state, onEvent)
        }

        state.editing?.let { editing -> ExternalUserForm(editing, state, onEvent) }
        state.details?.let { details -> ExternalUserDetails(details, state, onEvent) }
        state.confirmDelete?.let { doomed -> DeleteConfirm(doomed, onEvent) }
    }
}

/**
 * The Add / Edit User form on its own, for another tool that adds a contact
 * in place — the Crew List's Add External User, which the web mounts from the
 * same modal. Draws nothing until [ExternalUsersEvent.New] opens a draft.
 */
@Composable
fun ExternalUserFormDialog(state: ExternalUsersUiState, onEvent: (ExternalUsersEvent) -> Unit) {
    state.editing?.let { editing -> ExternalUserForm(editing, state, onEvent) }
}

@Composable
private fun Page(state: ExternalUsersUiState, onEvent: (ExternalUsersEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(state, onEvent)
        Box(Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = ZillitTheme.spacing.lg)) {
            when {
                state.isLoading && state.users.isEmpty() -> SkeletonGrid()
                state.visible.isEmpty() -> ZillitEmptyState(
                    title = if (state.query.isBlank()) "No data found" else "No users match “${state.query.trim()}”",
                    message = when {
                        state.query.isNotBlank() -> "Search reaches the name only — try another spelling."
                        state.bucket != ExternalUserBucket.All -> "No ${state.bucket.label.lowercase()} contacts yet."
                        else -> "Contacts added here are available to mail, distribution and e-signature."
                    },
                    icon = ZillitIcons.Users,
                )
                else -> CardGrid(state, onEvent)
            }
        }
    }
}

/** The web's header strip: accent bar and title, then search, the type filter and the CTA. */
@Composable
private fun Header(state: ExternalUsersUiState, onEvent: (ExternalUsersEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = PAGE_GUTTER, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Title(state)
            Spacer(Modifier.weight(1f))
            ZillitSearchField(
                value = state.query,
                onValueChange = { onEvent(ExternalUsersEvent.Search(it)) },
                placeholder = "Search by user name",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            // Shown to everyone: without posting rights the press is answered
            // by ExternalUsersViewModel.guardPost, which offers to ask an admin.
            ZillitButton(
                text = "Add User",
                leadingIcon = ZillitIcons.Add,
                onClick = { onEvent(ExternalUsersEvent.New) },
            )
        }
        TypeFilter(state.bucket) { onEvent(ExternalUsersEvent.Filter(it)) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The accent bar, the title, the count, and a spinner while a later page is in flight. */
@Composable
private fun Title(state: ExternalUsersUiState) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .width(ACCENT_BAR_WIDTH)
            .height(ACCENT_BAR_HEIGHT)
            .clip(ZillitTheme.shapes.small)
            .background(colors.accent),
    )
    ZillitText(text = "External Users", style = ZillitTheme.typography.titleLarge)
    if (state.users.isNotEmpty()) {
        val count = state.users.size
        ZillitText(
            text = if (state.query.isNotBlank()) {
                "${state.visible.size} of $count"
            } else {
                "$count contact${if (count == 1) "" else "s"}"
            },
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    if (state.isLoading && state.users.isNotEmpty()) {
        ZillitSpinner(size = ZillitTheme.spacing.md, modifier = Modifier.padding(start = 2.dp))
    }
}

/** The web's "Filter by Type" select, as a chip row so every bucket is one click away. */
@Composable
private fun TypeFilter(selected: ExternalUserBucket, onPick: (ExternalUserBucket) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "Type",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(end = ZillitTheme.spacing.xs),
        )
        ExternalUserBucket.entries.forEach { bucket ->
            ZillitChoiceChip(
                label = bucket.label,
                selected = selected == bucket,
                onClick = { onPick(bucket) },
            )
        }
    }
}

@Composable
private fun CardGrid(state: ExternalUsersUiState, onEvent: (ExternalUsersEvent) -> Unit) {
    val gridState = rememberLazyGridState()
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ZillitTheme.spacing.lg, end = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        items(state.visible, key = ExternalUser::id) { user ->
            UserCard(
                user = user,
                creator = state.creatorOf(user),
                mayEdit = state.viewer.mayEdit(user),
                onEvent = onEvent,
            )
        }
        // The web fetches the next page when the body scrolls to its bottom;
        // here the sentinel row composes as the grid reaches it.
        if (state.hasMore && state.query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(Unit) { onEvent(ExternalUsersEvent.LoadMore) }
                Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm), contentAlignment = Alignment.Center) {
                    if (state.isLoading) ZillitSpinner(size = ZillitTheme.spacing.lg)
                }
            }
        }
    }
}

/**
 * One contact, the web's `ExternalUserCard`: a tinted head with the name,
 * gender and type; three rows of contact facts; a foot that opens the details.
 */
@Composable
private fun UserCard(
    user: ExternalUser,
    creator: com.zillit.desktop.feature.externalusers.domain.Creator?,
    mayEdit: Boolean,
    onEvent: (ExternalUsersEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The web's hover: a 2px lift, a warmer border, a deeper shadow.
    val lift by animateDpAsState(if (hovered) CARD_LIFT else 0.dp, tween(HOVER_MS), label = "cardLift")
    val elevation by animateDpAsState(
        if (hovered) CARD_SHADOW_HOVER else CARD_SHADOW,
        tween(HOVER_MS),
        label = "cardShadow",
    )
    val outline by animateColorAsState(
        if (hovered) colors.accent.copy(alpha = HOVER_BORDER_ALPHA) else colors.border,
        tween(HOVER_MS),
        label = "cardBorder",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .offset(y = -lift)
            .hoverable(interaction)
            .shadow(elevation, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(colors.surface)
            .border(1.dp, outline, CARD_SHAPE),
    ) {
        CardHead(user, mayEdit, onEvent)
        Column(
            Modifier.padding(horizontal = CARD_PADDING, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            FactRow("Email") {
                EmailLink(user.email, onEvent)
            }
            FactRow("Contact Number") {
                PhoneValue(user.phoneLine)
            }
            FactRow("Created By") {
                ZillitText(
                    text = creator?.let { who ->
                        who.fullName + who.designation.takeIf { it.isNotBlank() }
                            ?.let { " (${it.localised()})" }.orEmpty()
                    } ?: "—",
                    style = ZillitTheme.typography.bodyMedium,
                    color = if (creator == null) colors.textMuted else colors.textPrimary,
                    // Name and designation together outrun a card; two lines, not an ellipsis.
                    maxLines = 2,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        CardFoot(user, onEvent)
    }
}

@Composable
private fun CardHead(user: ExternalUser, mayEdit: Boolean, onEvent: (ExternalUsersEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(horizontal = CARD_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = user.fullName, size = AVATAR_SIZE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = user.fullName,
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Gender.labelOf(user.gender).takeIf { it.isNotBlank() }?.let { gender ->
                    ZillitText(
                        text = "($gender)",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
            val bucket = ExternalUserBucket.of(user.userType)
            ZillitTag(
                label = bucket.typeLabel(user),
                tone = when (bucket) {
                    ExternalUserBucket.Vendor -> TagTone.Info
                    ExternalUserBucket.Others -> TagTone.Neutral
                    else -> TagTone.Accent
                },
            )
        }
        if (mayEdit) {
            // Always composed, as on the web: the edit and delete squares
            // live in the card head, never behind a hover.
            HeadAction(ZillitIcons.Edit, "Edit ${user.fullName}", danger = false) {
                onEvent(ExternalUsersEvent.Edit(user))
            }
            HeadAction(ZillitIcons.Trash, "Delete ${user.fullName}", danger = true) {
                onEvent(ExternalUsersEvent.Delete(user))
            }
        }
    }
}

/** The web's 34px bordered icon square, warming to the accent (or red) on hover. */
@Composable
private fun HeadAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hot = if (danger) colors.danger else colors.accent
    val tint by animateColorAsState(if (hovered) hot else colors.textSecondary, tween(HOVER_MS), label = "actionTint")
    val edge by animateColorAsState(
        if (hovered) hot.copy(alpha = HOVER_BORDER_ALPHA) else colors.border,
        tween(HOVER_MS),
        label = "actionEdge",
    )
    Box(
        Modifier
            .size(ACTION_SIZE)
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, edge, ZillitTheme.shapes.medium)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, contentDescription = description, tint = tint, size = ZillitTheme.spacing.lg)
    }
}

@Composable
private fun CardFoot(user: ExternalUser, onEvent: (ExternalUsersEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                onEvent(ExternalUsersEvent.ShowDetails(user))
            }
            .padding(horizontal = CARD_PADDING, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "View more details",
            style = ZillitTheme.typography.label,
            color = if (hovered) colors.accentHover else colors.accentText,
        )
        ZillitIcon(
            ZillitIcons.ChevronRight,
            tint = if (hovered) colors.accentHover else colors.accentText,
            size = ZillitTheme.spacing.md,
            modifier = Modifier.padding(start = ZillitTheme.spacing.xxs),
        )
    }
}

/** Label left, value right — the web's `external-user-card__row`. */
@Composable
private fun FactRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

/** An address that opens the composer — the web's `EmailOpener`. Blank reads N/A. */
@Composable
internal fun EmailLink(address: String, onEvent: (ExternalUsersEvent) -> Unit) {
    val colors = ZillitTheme.colors
    if (address.isBlank()) {
        ZillitText(text = "N/A", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitTooltip("Write an email") {
        ZillitText(
            text = address,
            style = ZillitTheme.typography.bodyMedium.copy(
                textDecoration = if (hovered) {
                    androidx.compose.ui.text.style.TextDecoration.Underline
                } else {
                    androidx.compose.ui.text.style.TextDecoration.None
                },
            ),
            color = if (hovered) colors.accentHover else colors.accentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) {
                    onEvent(ExternalUsersEvent.WriteTo(address))
                },
        )
    }
}

/** A number with the web's GSM note on hover: the desktop cannot ring it. Blank reads N/A. */
@Composable
internal fun PhoneValue(line: String) {
    val colors = ZillitTheme.colors
    if (line.isBlank()) {
        ZillitText(text = "N/A", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
        return
    }
    ZillitTooltip(GSM_NOTE) {
        ZillitText(text = line, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1)
    }
}

internal fun ExternalUserBucket.typeLabel(user: ExternalUser): String = when (this) {
    ExternalUserBucket.Others -> user.userType
    else -> label
}

/** Six placeholder cards while the first page is in flight — the web's `Spin` over an empty body. */
@Composable
private fun SkeletonGrid() {
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        items(SKELETON_CARDS) {
            Column(
                Modifier
                    .clip(CARD_SHAPE)
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, CARD_SHAPE)
                    .padding(CARD_PADDING),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSkeletonBar(Modifier.width(SKELETON_TITLE_WIDTH))
                ZillitSkeletonBar()
                ZillitSkeletonBar()
                ZillitSkeletonBar(Modifier.width(SKELETON_TITLE_WIDTH))
            }
        }
    }
}

@Composable
private fun DeleteConfirm(doomed: ExternalUser, onEvent: (ExternalUsersEvent) -> Unit) {
    ZillitDialogShell(
        title = "Are you sure to delete?",
        subtitle = doomed.fullName,
        icon = ZillitIcons.Trash,
        visible = true,
        width = CONFIRM_WIDTH,
        onDismiss = { onEvent(ExternalUsersEvent.CancelDelete) },
        actions = {
            ZillitButton(
                text = "No",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(ExternalUsersEvent.CancelDelete) },
            )
            ZillitButton(
                text = "Yes",
                variant = ButtonVariant.Danger,
                onClick = { onEvent(ExternalUsersEvent.ConfirmDelete) },
            )
        },
    ) {
        ZillitText(
            text = "The contact is removed from this production's directory.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

internal const val GSM_NOTE = "You can call GSM contacts through the mobile app"

private val PAGE_GUTTER = 24.dp
private val SEARCH_WIDTH = 260.dp
private val ACCENT_BAR_WIDTH = 4.dp
private val ACCENT_BAR_HEIGHT = 20.dp
private val CARD_MIN_WIDTH = 320.dp
private val GRID_GAP = 14.dp
private val CARD_PADDING = 14.dp
private val CARD_LIFT = 2.dp
private val CARD_SHADOW = 1.dp
private val CARD_SHADOW_HOVER = 6.dp
private val CARD_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
private val AVATAR_SIZE = 32.dp
private val ACTION_SIZE = 32.dp
private val CONFIRM_WIDTH = 420.dp
private val SKELETON_TITLE_WIDTH = 160.dp
private const val SKELETON_CARDS = 6
private const val HOVER_MS = 180
private const val HOVER_BORDER_ALPHA = 0.45f
