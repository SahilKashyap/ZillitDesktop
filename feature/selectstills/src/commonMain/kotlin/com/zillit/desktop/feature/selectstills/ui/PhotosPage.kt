@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.STILLS_GROUP_SIZES
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.domain.countFor
import com.zillit.desktop.feature.selectstills.domain.sectionKey
import com.zillit.desktop.feature.selectstills.domain.stateMeta

/**
 * The gallery, laid out as the web's: on the left the shoot, the members and
 * a line of counts; on the right the one-click tabs, the quieter chip rows
 * under them, and the grid.
 *
 * What comes back depends on who asks — production sees everything, an agent
 * their own actors' photos, anybody else only cleared ones — and the service
 * decides that; the filters here only ever narrow it.
 */
@Composable
internal fun PhotosPage(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val gallery = state.gallery
    var memberQuery by remember { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val ids = remember(gallery.photos) { gallery.photos.map { it.id } }

    // Near the bottom: the next page.
    val atEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - END_MARGIN
        }
    }
    LaunchedEffect(grid) { snapshotFlow { atEnd }.collect { if (it) onEvent(StillsEvent.LoadMorePhotos) } }

    SNarrow(NARROW) { narrow ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // One sheet, as the web's page is: the heading and the filters
            // scroll away above the tiles rather than sitting in a box of
            // their own. So the grid IS the page, with its head spanning it.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (narrow) TILE_MIN_NARROW else TILE_MIN),
                state = grid,
                modifier = Modifier.widthIn(max = PAGE_MAX).fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
            ) {
                item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SPageHead(
                            title = str(S.desktop_photos),
                            lede = if (state.me.scope == ViewerScope.Cleared) str(S.desktop_stk_photos_lede_viewer) else str(S.desktop_stk_photos_lede),
                        )
                        if (narrow) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                GallerySide(state, memberQuery, { memberQuery = it }, compact = true, onEvent = onEvent)
                                GalleryFilters(state, onEvent)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Column(Modifier.width(SIDE_WIDTH)) {
                                    GallerySide(state, memberQuery, { memberQuery = it }, compact = false, onEvent = onEvent)
                                }
                                Column(Modifier.weight(1f)) { GalleryFilters(state, onEvent) }
                            }
                        }
                    }
                }

                val empty = gallery.loading && gallery.photos.isEmpty() || gallery.failed || gallery.photos.isEmpty()
                if (empty) {
                    item(key = "body", span = { GridItemSpan(maxLineSpan) }) { GalleryEmpty(state, onEvent) }
                } else {
                    items(gallery.photos, key = { it.id }) { photo ->
                        SPhotoTile(photo, onOpen = { onEvent(StillsEvent.OpenPhoto(photo.id, ids, run = false)) })
                    }
                    item(key = "foot", span = { GridItemSpan(maxLineSpan) }) {
                        SText(
                            text = if (gallery.hasMore) str(S.desktop_loading_more) else str(S.desktop_stk_thats_everything),
                            size = 13,
                            color = k.muted,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** `.stk-side` — the shoot box, the by-member list, and the counts under them. */
@Composable
private fun GallerySide(
    state: StillsUiState,
    query: String,
    onQuery: (String) -> Unit,
    compact: Boolean,
    onEvent: (StillsEvent) -> Unit,
) {
    val k = StillsTheme.c
    val gallery = state.gallery
    val summary = gallery.summary

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SField(str(S.desktop_stk_shoot_day)) {
            SInput(
                value = gallery.shootText,
                onChange = { onEvent(StillsEvent.ShootTyped(it)) },
                placeholder = str(S.desktop_stk_all_shoots),
                maxLength = 80,
            )
        }

        SText(str(S.desktop_stk_by_member).uppercase(), 12, Bold, k.muted)

        // Production gets everybody with how many photos they are in; anybody
        // else the members they can see, without counts.
        val people = summary?.members?.map { SOption(it.memberId, it.name) }
            ?: state.members.map { SOption(it.id, it.name) }.sortedBy { it.label.lowercase() }
        val counts = summary?.members.orEmpty().associate { it.memberId to it.photos }

        SMemberPicker(
            members = people,
            counts = counts,
            total = summary?.total,
            value = gallery.filters.member,
            onChange = { onEvent(StillsEvent.MemberChanged(it)) },
            query = query,
            onQuery = onQuery,
            compact = compact,
        )

        // To somebody who only ever sees cleared photos there is nothing to count here.
        if (summary != null && state.me.scope != ViewerScope.Cleared) {
            val sums = listOfNotNull(
                summary.needsNames?.let { n ->
                    if (n == 1) str(S.desktop_stk_summary_unknown_one) else str(S.desktop_stk_summary_unknown_n, n)
                },
                summary.processing?.let { str(S.desktop_stk_n_processing, it) },
                summary.failed?.let { str(S.desktop_stk_n_failed, it) },
            )
            if (sums.isNotEmpty()) SText(sums.joinToString(" · "), 13, color = k.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** `.stk-tabs` — the one-click tabs and the quieter chip rows under them. */
@Composable
private fun GalleryFilters(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val gallery = state.gallery
    val summary = gallery.summary
    val counted = gallery.counted

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // `.stk-tabs-row` — kind of photo.
        val kindLabel = str(S.desktop_stk_filter_kind)
        FlowRow(
            Modifier.fillMaxWidth().semantics { contentDescription = kindLabel },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.galleryKinds.forEach { kind ->
                STab(
                    label = str(kindLabel(kind)),
                    active = gallery.filters.kind == kind,
                    onClick = { onEvent(StillsEvent.KindChanged(kind)) },
                    count = if (counted && gallery.filters.state == null) countFor(summary, kind) else null,
                    hint = str(kindHint(kind)),
                )
            }
        }

        // Group size is a refinement of Group, not a row of its own.
        if (gallery.filters.kind == PhotoKind.Group) {
            ChipRow(str(S.desktop_stk_filter_size)) {
                SChip(
                    label = str(S.desktop_stk_size_any),
                    active = gallery.filters.size == null,
                    onClick = { onEvent(StillsEvent.GroupSizeChanged(null)) },
                    count = if (counted && gallery.filters.state == null) countFor(summary, PhotoKind.Group) else null,
                )
                STILLS_GROUP_SIZES.forEach { size ->
                    SChip(
                        label = str(sectionKey(size)),
                        active = gallery.filters.size == size,
                        onClick = { onEvent(StillsEvent.GroupSizeChanged(size)) },
                        count = if (counted && gallery.filters.state == null) countFor(summary, PhotoKind.Group, size) else null,
                    )
                }
            }
        }

        // To somebody who only ever sees cleared photos, a publication filter is noise.
        if (state.showsPublicationFilter) {
            ChipRow(str(S.desktop_stk_pub)) {
                SChip(
                    label = str(S.all),
                    active = gallery.filters.state == null,
                    onClick = { onEvent(StillsEvent.StateChanged(null)) },
                    count = if (counted && gallery.filters.kind == PhotoKind.All) summary?.total else null,
                )
                PublicState.entries.forEach { publicState ->
                    val meta = stateMeta(publicState)
                    SChip(
                        label = str(pubLabel(publicState)),
                        active = gallery.filters.state == publicState,
                        onClick = { onEvent(StillsEvent.StateChanged(publicState)) },
                        tone = when (meta.tone) {
                            com.zillit.desktop.feature.selectstills.domain.PillTone.Ok -> SChipTone.Ok
                            com.zillit.desktop.feature.selectstills.domain.PillTone.Warn -> SChipTone.Warn
                            com.zillit.desktop.feature.selectstills.domain.PillTone.Bad -> SChipTone.Bad
                        },
                        count = if (counted && gallery.filters.kind == PhotoKind.All) summary?.forState(publicState) else null,
                        hint = str(pubHint(publicState)),
                    )
                }
            }
        }

        // `.stk-gate-by` — the people who answer for somebody, for production
        // to filter by. On its own line, not in the chip row above.
        val agents = remember(state.members, state.crewById, state.canPost) {
            if (!state.canPost) {
                emptyList()
            } else {
                state.members
                    .filter { it.approvalRequired && !it.agentUserId.isNullOrBlank() }
                    .mapNotNull { it.agentUserId }
                    .distinct()
                    .map { id ->
                        val person = state.crewById[id]
                        SOption(id, person?.fullName ?: "", person?.subtitle.orEmpty())
                    }
                    .sortedBy { it.label.lowercase() }
            }
        }
        if (agents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(str(S.agent).uppercase(), 11, Bold, k.muted)
                SSelect(
                    options = agents.map { it.copy(label = it.label.ifBlank { str(S.desktop_stk_unknown_person) }) },
                    value = gallery.filters.agent.orEmpty(),
                    onChange = { onEvent(StillsEvent.AgentChanged(it.takeIf { id -> id.isNotBlank() })) },
                    modifier = Modifier.width(240.dp),
                    emptyLabel = str(S.desktop_stk_any_agent),
                    searchLabel = str(S.desktop_stk_search_agents, agents.size),
                    small = true,
                )
                if (!gallery.filters.agent.isNullOrBlank()) {
                    SBtn(str(S.desktop_tax_clear_lower), { onEvent(StillsEvent.AgentChanged(null)) }, kind = SBtnKind.Link, small = true)
                }
            }
        }

        // `.stk-tab-note` — "showing Anna Bell only · 12 photos".
        gallery.filters.member?.let { memberId ->
            val name = state.gallery.summary?.members?.firstOrNull { it.memberId == memberId }?.name
                ?: state.membersById[memberId]?.name
                ?: str(S.desktop_stk_unknown_person)
            val shown = "${gallery.photos.size}${if (gallery.hasMore) "+" else ""}"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SRich(str(if (shown == "1") S.desktop_stk_showing_one else S.desktop_stk_showing_n, name, shown))
                SBtn(str(S.desktop_tax_clear_lower), { onEvent(StillsEvent.MemberChanged(null)) }, kind = SBtnKind.Link, small = true)
            }
        }
    }
}

/** What stands in for the grid: still loading, a refusal, or nothing here yet. */
@Composable
private fun GalleryEmpty(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val gallery = state.gallery
    when {
        gallery.failed -> SAlert(SAlertTone.Error) {
            SText(str(S.desktop_stk_photos_load_failed), 15, color = k.errorFg)
            SBtn(str(S.desktop_csync_try_again), { onEvent(StillsEvent.ReloadGallery) }, small = true)
        }
        gallery.loading -> SLoading()
        else -> SCard(Modifier.fillMaxWidth()) { SText(str(S.desktop_stk_no_photos), 15, color = k.muted) }
    }
}

/** `.stk-sizes` — a quiet row of chips under the tabs, with its small heading. */
@Composable
private fun ChipRow(heading: String, content: @Composable () -> Unit) {
    val k = StillsTheme.c
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SText(heading.uppercase(), 11, Bold, k.muted, Modifier.padding(end = 4.dp, top = 4.dp))
        content()
    }
}

private fun kindLabel(kind: PhotoKind) = when (kind) {
    PhotoKind.All -> S.desktop_stk_kind_all
    PhotoKind.Solo -> S.desktop_stk_kind_solo
    PhotoKind.Group -> S.desktop_stk_kind_group
    PhotoKind.Unknown -> S.desktop_stk_kind_unknown
    PhotoKind.NoFaces -> S.desktop_stk_kind_nofaces
    PhotoKind.Processing -> S.desktop_card_processing
    PhotoKind.Failed -> S.dd_legend_failed
}

private fun kindHint(kind: PhotoKind) = when (kind) {
    PhotoKind.All -> S.desktop_stk_kind_all_hint
    PhotoKind.Solo -> S.desktop_stk_kind_solo_hint
    PhotoKind.Group -> S.desktop_stk_kind_group_hint
    PhotoKind.Unknown -> S.desktop_stk_kind_unknown_hint
    PhotoKind.NoFaces -> S.desktop_stk_kind_nofaces_hint
    PhotoKind.Processing -> S.desktop_stk_kind_processing_hint
    PhotoKind.Failed -> S.desktop_stk_kind_failed_hint
}

private fun pubLabel(state: PublicState) = when (state) {
    PublicState.Approved -> S.desktop_stk_pub_approved
    PublicState.Pending -> S.desktop_stk_pub_pending
    PublicState.Blocked -> S.desktop_stk_pub_blocked
}

private fun pubHint(state: PublicState) = when (state) {
    PublicState.Approved -> S.desktop_stk_state_public_hint
    PublicState.Pending -> S.desktop_stk_pub_pending_hint
    PublicState.Blocked -> S.desktop_stk_state_blocked_hint
}

private val NARROW = 800.dp
private val PAGE_MAX = 1280.dp
private val SIDE_WIDTH = 240.dp

/** `repeat(auto-fill, minmax(230px, 1fr))`, and 144px on a narrow window. */
private val TILE_MIN = 230.dp
private val TILE_MIN_NARROW = 144.dp
private const val END_MARGIN = 12
