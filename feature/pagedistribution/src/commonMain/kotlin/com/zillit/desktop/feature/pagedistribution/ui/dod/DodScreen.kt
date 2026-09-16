// One composable per piece of the page; the header alone is a long method.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.pagedistribution.ui.dod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.pdfFileDrop

/**
 * Schedule D.O.D — the web's `DoD.jsx` page: a header with the accent bar,
 * the history notice, a grid of named folder cards wearing their unread
 * count, the paperclip that uploads, and a drop zone over the whole page.
 * Every dialog it opens is in [DodDialogs].
 *
 * Same state and events as the other two distribution tools — only the
 * face differs, which is why this is a screen over the shared engine and
 * not a module of its own.
 */
@Composable
fun DodScreen(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    /** A user id shown as "Name (Designation)"; null falls back to the id. */
    resolveUser: (String) -> String?,
) {
    val live = state.mode == ListMode.Live
    var dropHover by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .pdfFileDrop(
                enabled = live && state.upload == null,
                onHover = { dropHover = it },
                onFiles = { onEvent(DistributionEvent.FilesDropped(it)) },
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            DodHeader(state, onEvent)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to ${state.tool.title}.")
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
                // ZL-17014 — the web's history alert.
                if (!live) {
                    ZillitNotice(
                        text = "Records of deleted documents. They can be viewed and downloaded, not changed.",
                        tone = StatusTone.Progress,
                        icon = ZillitIcons.Info,
                    )
                }
                DodFolderGrid(state, onEvent)
            }
        }
        DropOverlay(visible = dropHover)
        DodDialogs(state, onEvent, resolveUser)
    }
}

/** The web's `.dod-page__header`: accent bar, title, and the actions at the right. */
@Composable
private fun DodHeader(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val live = state.mode == ListMode.Live
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                Modifier
                    .width(ACCENT_BAR_WIDTH)
                    .height(ACCENT_BAR_HEIGHT)
                    .clip(ZillitTheme.shapes.small)
                    .background(colors.accent),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = state.tool.title,
                        style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    if (!live) ZillitStatusPill(label = "History", tone = StatusTone.Neutral)
                    val total = state.folderUnread.values.sum()
                    if (live && total > 0) {
                        ZillitStatusPill(label = "$total unread", tone = StatusTone.Rejected, dot = true)
                    }
                }
                ZillitText(
                    text = if (live) {
                        "Day-out-of-days PDFs filed into named folders. " +
                            "Open a folder to view, download, move or publish."
                    } else {
                        "Documents deleted from the folders, kept for the record."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(
                text = if (live) "History" else "Back to live",
                onClick = { onEvent(DistributionEvent.ToggleHistory) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = if (live) ZillitIcons.Clock else ZillitIcons.ArrowLeft,
            )
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(DistributionEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Reload,
                loading = state.loading && state.openFolder == null,
            )
            if (live) {
                ZillitButton(
                    text = "Upload PDF",
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    leadingIcon = ZillitIcons.Paperclip,
                    loading = state.busy && state.upload == null,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** The folder cards — the web's `renderPageList` grid, three across at its width. */
@Composable
private fun DodFolderGrid(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val live = state.mode == ListMode.Live
    when {
        state.loading && state.folders.isEmpty() -> SkeletonGrid()
        state.folders.isEmpty() -> ZillitEmptyState(
            title = if (live) "No folders yet" else "Nothing in the history",
            message = if (live) {
                "Upload the first D.O.D PDF — it is filed under the folder name you give it."
            } else {
                "Deleted documents will be listed here."
            },
            icon = ZillitIcons.Folder,
            action = if (live) {
                {
                    ZillitButton(
                        text = "Upload PDF",
                        onClick = { onEvent(DistributionEvent.PickPdf()) },
                        leadingIcon = ZillitIcons.Paperclip,
                    )
                }
            } else {
                null
            },
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xxl),
        ) {
            // The index rides in the key: the service can list one scene twice
            // (seen live 2026-09-15 — two rows, no `_id`, no `revision_date`),
            // and a LazyGrid throws on a repeated key, freezing the window.
            itemsIndexed(
                state.folders,
                key = { index, folder -> "${folder.id}|${folder.key}|${folder.revisionDateMs}|$index" },
            ) { _, folder ->
                DodFolderCard(
                    folder = folder,
                    unread = if (live) state.folderUnread[folder.key] ?: 0 else 0,
                    onClick = { onEvent(DistributionEvent.OpenFolder(folder.key)) },
                )
            }
        }
    }
}

/**
 * One folder: the glyph, the name, when it was started, and its unread
 * count — the web's antd `Card` with the folder image and `Badge`. Lifts
 * and warms its border on hover, as the web's `.ant-card:hover` does.
 */
@Composable
internal fun DodFolderCard(folder: DistFolder, unread: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) HOVER_LIFT else 0.dp, tween(HOVER_MILLIS), label = "lift")
    val elevation by animateDpAsState(if (hovered) HOVER_SHADOW else REST_SHADOW, tween(HOVER_MILLIS), label = "shadow")
    val outline by animateColorAsState(
        targetValue = if (hovered) colors.accent else colors.border,
        animationSpec = tween(HOVER_MILLIS),
        label = "outline",
    )
    Box(
        modifier = Modifier
            .offset(y = -lift)
            .shadow(elevation, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, outline, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .defaultMinSize(minHeight = FOLDER_MIN_HEIGHT),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(FOLDER_GLYPH_TILE)
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Folder, tint = colors.accent, size = FOLDER_GLYPH)
            }
            ZillitText(
                text = folder.key.ifBlank { "Untitled" },
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            ZillitText(
                text = "Uploaded on\n${DistributionDates.dateTime(folder.createdMs)}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (folder.deleted) ZillitStatusPill(label = "Deleted", tone = StatusTone.Rejected)
        }
        if (unread > 0) {
            UnreadBadge(
                count = unread,
                modifier = Modifier.align(Alignment.TopEnd).padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

/** The red count in the card's corner — antd's `Badge`, capped at 99+. */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE)
            .clip(CircleShape)
            .background(ZillitTheme.colors.danger)
            .padding(horizontal = ZillitTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textOnAccent,
            maxLines = 1,
        )
    }
}

/** Six grey cards while the first list is in flight — the shape of what is coming. */
@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(SKELETON_CARDS) {
            Column(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                    .padding(ZillitTheme.spacing.lg)
                    .defaultMinSize(minHeight = FOLDER_MIN_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSkeletonBar(Modifier.size(FOLDER_GLYPH_TILE), height = FOLDER_GLYPH_TILE)
                ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION))
                ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_META_FRACTION), height = SKELETON_META_HEIGHT)
            }
        }
    }
}

/** The page under a dragged file — says what letting go does. */
@Composable
private fun DropOverlay(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val colors = ZillitTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .padding(ZillitTheme.spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, colors.accent, ZillitTheme.shapes.large)
                    .background(colors.accentSoft, ZillitTheme.shapes.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Upload, tint = colors.accent, size = FOLDER_GLYPH)
                Spacer(Modifier.height(ZillitTheme.spacing.sm))
                ZillitText(
                    text = "Drop a PDF to upload it",
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = "You will name its folder next.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

private val ACCENT_BAR_WIDTH = 4.dp
private val ACCENT_BAR_HEIGHT = 24.dp
private val FOLDER_MIN_WIDTH = 220.dp
private val FOLDER_MIN_HEIGHT = 160.dp
private val FOLDER_GLYPH_TILE = 64.dp
private val FOLDER_GLYPH = 32.dp
private val BADGE_SIZE = 20.dp
private const val BADGE_CAP = 99
private val HOVER_LIFT = 2.dp
private val HOVER_SHADOW = 8.dp
private val REST_SHADOW = 1.dp
private const val HOVER_MILLIS = 180
private const val SKELETON_CARDS = 6
private const val SKELETON_TITLE_FRACTION = 0.6f
private const val SKELETON_META_FRACTION = 0.8f
private val SKELETON_META_HEIGHT = 10.dp
