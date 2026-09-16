package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.MyDriveFilter
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewMode
import com.zillit.desktop.feature.drive.ui.refs

/**
 * The header panel — `DriveHeader.jsx` plus `DriveToolbar.jsx`: the two
 * section tabs, then breadcrumb on the left and the actions on the right,
 * then the bulk bar while anything is selected.
 */
@Composable
internal fun DriveHeaderPanel(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    onOpenWidget: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        SectionTabs(state, onEvent, onOpenWidget)
        HeaderRow(state, onEvent)
        AnimatedVisibility(
            visible = state.selected.isNotEmpty() && !state.showTrash,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            BulkBar(state, onEvent)
        }
        if (state.viewer.ready && !state.viewer.canCreate && state.section == DriveSection.MyDrive) {
            ZillitNotice(
                text = "You can browse this drive but cannot upload to it or create folders. " +
                    "Ask an administrator for posting rights on the Drive.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }
    }
}

/** My Drive / Shared with me — `drive-section-tab`, an underlined pair. */
@Composable
private fun SectionTabs(state: DriveUiState, onEvent: (DriveEvent) -> Unit, onOpenWidget: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.accentSoft)
                .padding(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(icon = ZillitIcons.Drive, tint = ZillitTheme.colors.accent, size = ZillitTheme.spacing.lg)
        }
        ZillitText(
            text = "Drive",
            style = ZillitTheme.typography.titleMedium,
            modifier = Modifier.padding(end = ZillitTheme.spacing.md),
        )
        DriveSection.entries.forEach { section ->
            SectionTab(
                label = section.label,
                icon = if (section == DriveSection.SharedWithMe) ZillitIcons.Users else null,
                active = state.section == section,
                onClick = { onEvent(DriveEvent.OpenSection(section)) },
            )
        }
        Box(Modifier.weight(1f))
        if (onOpenWidget != null) {
            ZillitButton(
                text = "Widget",
                onClick = onOpenWidget,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Detach,
            )
        }
        ZillitButton(
            text = "Refresh",
            onClick = { onEvent(DriveEvent.Refresh) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Reload,
            loading = state.loading,
        )
    }
}

@Composable
private fun SectionTab(label: String, icon: ImageVector?, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    active -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                ZillitIcon(
                    icon = icon,
                    tint = if (active) colors.accent else colors.textSecondary,
                    size = ZillitTheme.spacing.lg,
                )
            }
            ZillitText(
                text = label,
                style = ZillitTheme.typography.label.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (active) colors.accentText else colors.textSecondary,
            )
        }
    }
}

/** Breadcrumb on the left, search and actions on the right — `drive-header-row`. */
@Composable
private fun HeaderRow(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val gap = ZillitTheme.spacing.sm
    // The crumb and the actions share a line while the crumb keeps a readable
    // width; otherwise the actions drop beneath it, as the web's header wraps.
    // A weight would instead squeeze the crumb to nothing behind the search box.
    Layout(
        content = {
            Box {
                when {
                    state.showTrash -> TrashCrumb(state)
                    state.isSearching -> SearchCrumb(state)
                    else -> Breadcrumb(state, onEvent)
                }
            }
            Actions(state, onEvent)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val actions = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val crumbMin = CRUMB_MIN_WIDTH.roundToPx()
        val sideBySide = actions.width + gapPx + crumbMin <= width
        val crumbWidth = if (sideBySide) width - actions.width - gapPx else width
        val crumb = measurables[0].measure(
            constraints.copy(minWidth = 0, maxWidth = crumbWidth.coerceAtLeast(0), minHeight = 0),
        )
        if (sideBySide) {
            val height = maxOf(crumb.height, actions.height)
            layout(width, height) {
                crumb.placeRelative(0, (height - crumb.height) / 2)
                actions.placeRelative(width - actions.width, (height - actions.height) / 2)
            }
        } else {
            layout(width, crumb.height + gapPx + actions.height) {
                crumb.placeRelative(0, 0)
                actions.placeRelative(width - actions.width, crumb.height + gapPx)
            }
        }
    }
}

@Composable
private fun TrashCrumb(state: DriveUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.Trash, tint = ZillitTheme.colors.danger, size = ZillitTheme.spacing.lg)
        ZillitText(text = "Trash", style = ZillitTheme.typography.titleSmall)
        ZillitText(
            text = "· ${state.trash.items.size}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun SearchCrumb(state: DriveUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.Search, tint = ZillitTheme.colors.accent, size = ZillitTheme.spacing.lg)
        ZillitText(
            text = "Search results for",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = "“${state.search}”",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

/**
 * Home, then each folder — the last one plain, the others links.
 *
 * The header shares its row with the search and the action buttons, so a
 * deep trail does not fit: ancestors between the root and the last two
 * collapse into a "…" menu, and whatever still overflows scrolls to the
 * tail — the folder the user is in must be the one they can read.
 */
@Composable
@Suppress("LongMethod") // One row: back, root, the collapsed middle, the tail.
private fun Breadcrumb(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val scroll = rememberScrollState()
    val trail = listOf(DriveCrumb(null, state.rootName)) + state.breadcrumb
    val collapsed = trail.size > VISIBLE_CRUMBS + 1
    val hidden = if (collapsed) trail.subList(1, trail.size - VISIBLE_CRUMBS) else emptyList()
    val shown = if (collapsed) listOf(trail.first()) + trail.takeLast(VISIBLE_CRUMBS) else trail
    LaunchedEffect(trail, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.breadcrumb.isNotEmpty()) {
            ZillitIconButton(
                icon = ZillitIcons.ArrowLeft,
                contentDescription = "Back",
                onClick = { onEvent(DriveEvent.GoBack) },
            )
        }
        shown.forEachIndexed { index, crumb ->
            val first = index == 0
            val last = index == shown.lastIndex
            if (index > 0) {
                ZillitText(text = "/", color = ZillitTheme.colors.textMuted, style = ZillitTheme.typography.bodyMedium)
            }
            if (first && hidden.isNotEmpty()) {
                CrumbLink(crumb, first, onEvent)
                ZillitText(text = "/", color = ZillitTheme.colors.textMuted, style = ZillitTheme.typography.bodyMedium)
                HiddenCrumbs(hidden, onEvent)
            } else if (last) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xs),
                ) {
                    if (first) ZillitIcon(icon = ZillitIcons.Home, size = ZillitTheme.spacing.lg)
                    ZillitText(
                        text = crumb.name,
                        style = ZillitTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = CRUMB_MAX_WIDTH),
                    )
                }
            } else {
                CrumbLink(crumb, first, onEvent)
            }
        }
    }
}

@Composable
private fun CrumbLink(crumb: DriveCrumb, root: Boolean, onEvent: (DriveEvent) -> Unit) {
    ZillitButton(
        text = crumb.name,
        onClick = { onEvent(DriveEvent.OpenFolder(crumb.id)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = if (root) ZillitIcons.Home else null,
    )
}

/** The ancestors the trail has no room for, behind a "…" that lists them. */
@Composable
private fun HiddenCrumbs(hidden: List<DriveCrumb>, onEvent: (DriveEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = "…",
            onClick = { open = true },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = hidden.map { crumb ->
                ZillitMenuEntry.Action(crumb.name, ZillitIcons.Folder) {
                    open = false
                    onEvent(DriveEvent.OpenFolder(crumb.id))
                }
            },
        )
    }
}

/** Search, view toggle, filters, and the primary actions — `drive-primary-actions`. */
@Composable
@Suppress("LongMethod") // Every header control, in the web's order.
private fun Actions(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.showTrash) {
            ZillitSearchField(
                value = state.searchInput,
                onValueChange = { onEvent(DriveEvent.SearchInput(it)) },
                placeholder = "Search files & folders…",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
        }
        ViewToggle(state.viewMode) { onEvent(DriveEvent.SetViewMode(it)) }
        if (!state.showTrash) {
            if (state.section == DriveSection.MyDrive) {
                ZillitSelect(
                    value = state.myDriveFilter,
                    options = MyDriveFilter.entries,
                    onSelect = { onEvent(DriveEvent.FilterMyDrive(it)) },
                    label = { it.label },
                    modifier = Modifier.width(FILTER_WIDTH),
                )
            }
            ZillitTooltip(text = if (state.showFavouritesOnly) "Show all" else "Show favourites") {
                ToggleIcon(
                    icon = if (state.showFavouritesOnly) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
                    description = "Favourites filter",
                    active = state.showFavouritesOnly,
                    activeTint = ZillitTheme.colors.gold,
                    onClick = { onEvent(DriveEvent.ShowFavouritesOnly(!state.showFavouritesOnly)) },
                )
            }
            ZillitTooltip(text = "Activity log") {
                ToggleIcon(
                    icon = ZillitIcons.Clock,
                    description = "Activity log",
                    active = false,
                    activeTint = ZillitTheme.colors.accent,
                    onClick = { onEvent(DriveEvent.OpenActivityLog) },
                )
            }
            if (state.tags.isNotEmpty()) TagFilter(state.tags, state.tagFilterId, onEvent)
            if (state.canCreateHere) {
                ZillitButton(
                    text = "Upload",
                    onClick = { onEvent(DriveEvent.OpenUpload) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
                ZillitButton(
                    text = "Create folder",
                    onClick = { onEvent(DriveEvent.OpenNewFolder) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.FolderPlus,
                )
                if (state.folderId != null) {
                    ZillitTooltip(text = "Generate a public link people can use to upload files into this folder.") {
                        ZillitButton(
                            text = "Request files",
                            onClick = { onEvent(DriveEvent.OpenFileRequestsHere) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Inbox,
                        )
                    }
                }
            }
        }
        ZillitTooltip(text = if (state.showTrash) "Close trash" else "View trash") {
            ToggleIcon(
                icon = ZillitIcons.Trash,
                description = "Trash",
                active = state.showTrash,
                activeTint = ZillitTheme.colors.danger,
                onClick = { onEvent(DriveEvent.ShowTrash(!state.showTrash)) },
            )
        }
    }
}

/** List / grid — `drive-view-toggle`, two glyphs in one sunken pill. */
@Composable
private fun ViewToggle(mode: DriveViewMode, onSelect: (DriveViewMode) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(TOGGLE_INSET),
        horizontalArrangement = Arrangement.spacedBy(TOGGLE_INSET),
    ) {
        DriveViewMode.entries.forEach { option ->
            val active = option == mode
            ZillitTooltip(text = "${option.label} view") {
                Box(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .background(if (active) colors.surface else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                ) {
                    ZillitIcon(
                        icon = if (option == DriveViewMode.List) ZillitIcons.Ledger else ZillitIcons.Grid,
                        tint = if (active) colors.accent else colors.textSecondary,
                        size = ZillitTheme.spacing.lg,
                    )
                }
            }
        }
    }
}

/** An icon button that reads as "on" — the star, the trash toggle. */
@Composable
private fun ToggleIcon(
    icon: ImageVector,
    description: String,
    active: Boolean,
    activeTint: Color,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) activeTint.copy(alpha = ACTIVE_WASH) else colors.surfaceSunken)
            .border(1.dp, if (active) activeTint else colors.border, ZillitTheme.shapes.medium),
    ) {
        ZillitIconButton(
            icon = icon,
            contentDescription = description,
            onClick = onClick,
            tint = if (active) activeTint else colors.textSecondary,
            size = ZillitTheme.spacing.xxl,
        )
    }
}

/** The tag filter — a select of the project's tags, "Tag" when none is chosen. */
@Composable
private fun TagFilter(tags: List<DriveTag>, tagFilterId: String?, onEvent: (DriveEvent) -> Unit) {
    val none = DriveTag(id = "", name = "Tag")
    ZillitSelect(
        value = tags.firstOrNull { it.id == tagFilterId } ?: none,
        options = listOf(none) + tags,
        onSelect = { onEvent(DriveEvent.FilterByTag(it.id.takeIf { id -> id.isNotBlank() })) },
        label = { if (it.id.isBlank()) "Tag" else "# ${it.name}" },
        modifier = Modifier.width(TAG_WIDTH),
    )
}

/**
 * The bulk bar — `drive-bulk-bar`: the count, then only the actions the
 * selection admits. The counts on them are what the *server* will accept,
 * not what is selected — bulk operations are permission-checked per item,
 * so "Delete 17 of 20" is the honest label for a mixed selection.
 */
@Composable
private fun BulkBar(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val selected = state.selected.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.accentSoft)
            .border(1.dp, ZillitTheme.colors.accent.copy(alpha = BULK_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitStatusPill(
            label = "$selected item${if (selected == 1) "" else "s"} selected",
            tone = StatusTone.Pending,
        )
        if (state.deletableCount > 0) {
            ZillitButton(
                text = bulkLabel("Delete", state.deletableCount, selected),
                onClick = { onEvent(DriveEvent.RequestDelete(state.selectedItems.refs())) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
        if (state.movableCount > 0) {
            ZillitButton(
                text = bulkLabel("Move to…", state.movableCount, selected),
                onClick = { onEvent(DriveEvent.OpenMoveSelection) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowRight,
            )
        }
        if (state.downloadableCount > 0) {
            ZillitButton(
                text = bulkLabel("Download", state.downloadableCount, selected),
                onClick = { onEvent(DriveEvent.DownloadSelection) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
            )
        }
        Box(Modifier.weight(1f))
        ZillitButton(
            text = "Clear",
            onClick = { onEvent(DriveEvent.ClearSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
        )
    }
}

/** "Delete 17 of 20" when some rows are not the viewer's to touch. */
private fun bulkLabel(verb: String, eligible: Int, selected: Int): String =
    if (eligible == selected) verb else "$verb ($eligible of $selected)"

/**
 * Folders / Files under the header — `DriveInnerTabs`, shown only at the
 * root: there the two kinds split into tabs, inside a folder they share a
 * list. Hidden during a search and in the trash.
 */
@Composable
@Suppress("LongMethod") // Two tabs with counts and an indicator.
internal fun InnerTabs(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val (folders, files) = state.innerTotals
    val colors = ZillitTheme.colors
    Column(modifier = Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            DriveInnerTab.entries.forEach { tab ->
                val active = tab == state.innerTab
                val total = if (tab == DriveInnerTab.Folders) folders else files
                // Intrinsic width: the indicator fills its tab, and a plain
                // fillMaxWidth there would let the first tab swallow the row.
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .clickable { onEvent(DriveEvent.OpenInnerTab(tab)) }
                        .padding(top = ZillitTheme.spacing.xs),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(
                            horizontal = ZillitTheme.spacing.xs,
                            vertical = ZillitTheme.spacing.sm,
                        ),
                    ) {
                        ZillitIcon(
                            icon = if (tab == DriveInnerTab.Folders) ZillitIcons.Folder else ZillitIcons.File,
                            tint = if (active) colors.accent else colors.textSecondary,
                            size = ZillitTheme.spacing.lg,
                        )
                        ZillitText(
                            text = tab.label,
                            style = ZillitTheme.typography.label.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (active) colors.textPrimary else colors.textSecondary,
                        )
                        if (total > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(ZillitTheme.shapes.pill)
                                    .background(if (active) colors.accentSoft else colors.surfaceSunken)
                                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp),
                            ) {
                                ZillitText(
                                    text = total.toString(),
                                    style = ZillitTheme.typography.labelSmall,
                                    color = if (active) colors.accentText else colors.textSecondary,
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(INDICATOR)
                            .clip(ZillitTheme.shapes.pill)
                            .background(if (active) colors.accent else Color.Transparent),
                    )
                }
            }
        }
        ZillitDivider()
    }
}

private val SEARCH_WIDTH = 240.dp
private val FILTER_WIDTH = 150.dp
private val TAG_WIDTH = 150.dp
private val CRUMB_MAX_WIDTH = 320.dp

/** Below this the trail is unreadable, so the actions wrap under it instead. */
private val CRUMB_MIN_WIDTH = 360.dp

/** Crumbs kept at the tail before the ancestors collapse: the parent and the folder itself. */
private const val VISIBLE_CRUMBS = 2
private val TOGGLE_INSET = 3.dp
private val INDICATOR = 2.dp
private const val ACTIVE_WASH = 0.15f
private const val BULK_BORDER_ALPHA = 0.4f
