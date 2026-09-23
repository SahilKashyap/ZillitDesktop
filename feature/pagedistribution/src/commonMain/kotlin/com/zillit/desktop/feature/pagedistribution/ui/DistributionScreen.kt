// Screens branch on the tab kind; one composable per piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.pagedistribution.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.ui.dod.DodScreen
import com.zillit.desktop.feature.pagedistribution.ui.pages.DistributionDialogs
import com.zillit.desktop.feature.pagedistribution.ui.schedule.ScheduleScreen
import com.zillit.desktop.feature.pagedistribution.ui.script.ScriptScreen

/**
 * A distribution tool: tabs, the single list or the folder grid, search, and
 * the dialogs (folder, upload, viewer, tallies, move, confirms).
 *
 * Schedule D.O.D shares the state and events but wears the web's own D.O.D
 * page — folder cards with unread counts, ribbon cards, the More pill — so
 * it is handed to [DodScreen] here.
 */
@Composable
fun DistributionScreen(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    /** A user id shown as "Name (Designation)"; null falls back to the id. */
    resolveUser: (String) -> String?,
) {
    if (state.isDod) {
        DodScreen(state, onEvent, resolveUser)
        return
    }
    if (state.isSchedule) {
        ScheduleScreen(state, onEvent, resolveUser)
        return
    }
    if (state.isScript) {
        ScriptScreen(state, onEvent, resolveUser)
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitPageHeader(
                title = if (state.mode == ListMode.History) {
                    str(S.desktop_dist_title_history, state.tool.title)
                } else {
                    state.tool.title
                },
                description = when {
                    state.mode == ListMode.History -> str(S.desktop_dist_history_description)
                    state.isDod -> str(S.desktop_dist_dod_description)
                    else -> str(S.desktop_pagedist_description)
                },
                actions = {
                    ZillitButton(
                        text = if (state.mode == ListMode.History) str(S.desktop_dist_back_to_live) else str(S.history),
                        onClick = { onEvent(DistributionEvent.ToggleHistory) },
                        variant = ButtonVariant.Tertiary,
                    )
                    ZillitButton(
                        text = str(S.refresh_text),
                        onClick = { onEvent(DistributionEvent.Refresh) },
                        variant = ButtonVariant.Tertiary,
                        loading = state.loading,
                    )
                    if (state.mode == ListMode.Live) {
                        ZillitButton(
                            text = str(S.desktop_dist_upload_pdf),
                            onClick = { onEvent(DistributionEvent.PickPdf()) },
                            leadingIcon = ZillitIcons.Upload,
                            loading = state.busy,
                        )
                    }
                },
            )
            if (state.viewer.isBlocked) ZillitNotice(text = str(S.desktop_dist_no_access, state.tool.title))
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = str(S.sync_action_dismiss),
                            onClick = { onEvent(DistributionEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            if (state.tool.tabs.size > 1) {
                ZillitTabStrip(
                    tabs = state.tool.tabs.map { ZillitTab(it.key, it.label) },
                    activeId = state.activeTabKey,
                    onSelect = { onEvent(DistributionEvent.SelectTab(it)) },
                )
            }
            if (state.isFolderTab && !state.isDod) SearchBar(state, onEvent)
            when {
                state.loading && state.documents.isEmpty() && state.folders.isEmpty() ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.searchResults != null -> SearchResults(state, onEvent)
                state.isFolderTab -> FolderGrid(state, onEvent)
                else -> DocumentList(state, onEvent, resolveUser)
            }
        }
        DistributionDialogs(state, onEvent, resolveUser)
    }
}

@Composable
private fun SearchBar(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = state.searchScene,
            onValueChange = { onEvent(DistributionEvent.SearchChanged(scene = it)) },
            label = str(S.txt_scene_number),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = state.searchEpisode,
                onValueChange = { onEvent(DistributionEvent.SearchChanged(episode = it)) },
                label = str(S.episode),
                modifier = Modifier.width(EPISODE_WIDTH),
            )
        }
        Column(Modifier.width(SEARCH_WIDTH)) {
            ZillitText(
                text = str(S.av_color),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitSelect(
                value = state.searchColour,
                options = listOf<PageColour?>(null) + PageColour.entries,
                onSelect = { onEvent(DistributionEvent.SearchColour(it)) },
                label = { it?.label ?: str(S.desktop_dist_any_colour) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitButton(
            text = str(S.search),
            onClick = { onEvent(DistributionEvent.RunSearch) },
            variant = ButtonVariant.Secondary,
        )
        if (state.isSearching || state.searchResults != null) {
            ZillitButton(
                text = str(S.clear_label),
                onClick = { onEvent(DistributionEvent.ClearSearch) },
                variant = ButtonVariant.Tertiary,
            )
        }
    }
}

@Composable
private fun DocumentList(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val rows = state.sortedDocuments
    if (rows.isEmpty()) {
        ZillitText(
            text = if (state.mode == ListMode.History) {
                str(S.desktop_dist_nothing_in_history_yet)
            } else {
                str(S.desktop_dist_no_documents_yet)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        items(rows, key = { it.id }) { document ->
            DocumentCard(state, state.activeTab, document, onEvent, resolveUser)
        }
    }
}

@Composable
private fun FolderGrid(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    if (state.folders.isEmpty()) {
        ZillitText(
            text = if (state.mode == ListMode.History) {
                str(S.desktop_dist_nothing_in_history_yet)
            } else {
                str(S.desktop_dist_no_folders_yet)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.folders, key = { it.id + it.key + it.revisionDateMs }) { folder ->
            FolderTile(state, folder, onEvent)
        }
    }
}

@Composable
private fun FolderTile(state: DistributionUiState, folder: DistFolder, onEvent: (DistributionEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .background(colors.surface, ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .clickable { onEvent(DistributionEvent.OpenFolder(folder.key)) }
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = if (state.isDod) folder.key else str(S.desktop_scene_numbered, folder.key),
            style = ZillitTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        folder.scheduleType?.let { ZillitStatusPill(label = it.label, tone = StatusTone.Neutral) }
        ZillitText(
            text = str(S.desktop_dist_uploaded_on_value, DistributionDates.dateTime(folder.createdMs)),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun SearchResults(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val tab = state.activeTab
    val groups = state.searchResults.orEmpty().groupBy { it.folderKey(tab) }
    if (groups.isEmpty()) {
        ZillitText(
            text = str(S.desktop_dist_no_pages_match),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(FOLDER_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(groups.entries.toList(), key = { it.key }) { (key, docs) ->
            val first = docs.first()
            val colors = ZillitTheme.colors
            Column(
                modifier = Modifier
                    .background(tint(first.colour, colors.surface), ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .clickable { onEvent(DistributionEvent.OpenFolder(key)) }
                    .padding(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = str(S.desktop_scene_numbered, key),
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = str(S.desktop_dist_page_count, docs.size),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** One document card: the web's ribbon-and-avatar card, tinted by its revision colour. */
@Composable
internal fun DocumentCard(
    state: DistributionUiState,
    tab: DistributionTab,
    document: DistDocument,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    val single = tab.kind is TabKind.Single
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint(document.colour, colors.surface), ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = document.attachment?.name?.ifBlank { null }
                        ?: document.originalName.ifBlank { str(S.document) },
                    style = ZillitTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                if (document.replaced) {
                    ZillitStatusPill(label = str(S.desktop_dist_replaced_pill), tone = StatusTone.Neutral)
                }
                if (document.deleted) ZillitStatusPill(label = str(S.drive_deleted_default), tone = StatusTone.Rejected)
            }
            ZillitText(
                text = str(
                    S.desktop_dist_uploaded_on_by,
                    DistributionDates.dateTime(document.createdMs),
                    resolveUser(document.createdBy) ?: document.createdBy.ifBlank { str(S.desktop_unknown) },
                ),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            val facts = buildList {
                if (state.viewer.isTelevision) add(str(S.desktop_dist_fact_episode, document.episode.ifBlank { "—" }))
                if (single) {
                    if (document.dateMs > 0) add(str(S.desktop_dist_fact_date, DistributionDates.date(document.dateMs)))
                    if (document.name.isNotBlank()) add(str(S.desktop_dist_fact_name, document.name))
                } else if (state.isDod) {
                    add(str(S.desktop_dist_fact_folder, document.name.ifBlank { "—" }))
                } else {
                    add(str(S.desktop_dist_fact_scene, document.sceneNumber.ifBlank { "—" }))
                    if (document.pageNumber.isNotBlank()) add(str(S.desktop_dist_fact_page, document.pageNumber))
                    add(
                        str(
                            S.desktop_dist_fact_page_date,
                            DistributionDates.date(document.userSelectedDateMs).ifBlank { "—" },
                        ),
                    )
                    document.scheduleType?.let { add(it.label) }
                }
            }
            ZillitText(
                text = facts.joinToString("   ·   "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        MoreMenu(state, tab, document, onEvent)
    }
}

@Composable
private fun MoreMenu(
    state: DistributionUiState,
    tab: DistributionTab,
    document: DistDocument,
    onEvent: (DistributionEvent) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val live = state.mode == ListMode.Live
    val single = tab.kind is TabKind.Single
    val folders = tab.kind as? TabKind.Folders
    Box {
        ZillitIconButton(icon = ZillitIcons.MoreHorizontal, contentDescription = str(S.more), onClick = { open = true })
        val items = buildList<Pair<String, DistributionEvent>> {
            add(str(S.view) to DistributionEvent.View(document))
            add(str(S.download) to DistributionEvent.Download(document))
            if (live && single && state.viewer.mayPost) {
                add(str(S.replace) to DistributionEvent.PickPdf(replaces = document))
            }
            val movable = folders?.canMove == true && state.viewer.mayPost
            if (live && movable) add(str(S.desktop_dist_move_ellipsis) to DistributionEvent.Move(document))
            if (live && !single) add(str(S.delete) to DistributionEvent.Delete(document))
            if (live && state.viewer.mayPublish) {
                add(str(S.dd_publish_to_distribution) to DistributionEvent.Publish(document))
            }
            if (live && state.viewer.isAdmin) {
                add(str(S.view_count) to DistributionEvent.ShowCounts(document, downloads = false))
                add(str(S.download_count) to DistributionEvent.ShowCounts(document, downloads = true))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, event) ->
                DropdownMenuItem(
                    text = { ZillitText(text = label) },
                    onClick = {
                        open = false
                        onEvent(event)
                    },
                )
            }
        }
    }
}

/** A revision colour over the surface — pale, so text stays legible; blank means the plain surface. */
internal fun tint(hex: String, surface: Color): Color {
    val colour = PageColour.fromHex(hex) ?: return surface
    if (colour == PageColour.White) return surface
    val v = colour.hex.removePrefix("#").toLongOrNull(HEX_RADIX) ?: return surface
    val page = Color(
        red = ((v shr RED_SHIFT) and CHANNEL) / CHANNEL_MAX,
        green = ((v shr GREEN_SHIFT) and CHANNEL) / CHANNEL_MAX,
        blue = (v and CHANNEL) / CHANNEL_MAX,
    )
    return page.copy(alpha = TINT_ALPHA).compositeOver(surface)
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
    )
}

private const val HEX_RADIX = 16
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFFL
private const val CHANNEL_MAX = 255f
private const val TINT_ALPHA = 0.35f
private val SEARCH_WIDTH = 180.dp
private val EPISODE_WIDTH = 120.dp
private val FOLDER_MIN_WIDTH = 220.dp
