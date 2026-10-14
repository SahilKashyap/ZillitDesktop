@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.STILLS_GROUP_SIZES
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.domain.countFor
import com.zillit.desktop.feature.selectstills.domain.sectionKey
import com.zillit.desktop.feature.selectstills.domain.stateMeta
import kotlin.math.roundToInt

/**
 * The gallery, laid out as the web's: on the left the shoot, the members and
 * a line of counts; on the right the one-click tabs, the quieter chip rows
 * under them, and the grid.
 *
 * What comes back depends on who asks — production sees everything, an agent
 * their own actors' photos, anybody else only cleared ones — and the service
 * decides that; the filters here only ever narrow it.
 *
 * Production can also clear out photos in bulk: "Select photos" turns the
 * tiles into tick boxes (shift-click takes a run of them, "Select all shown"
 * takes what is loaded, not the whole production), and one confirmed Delete
 * removes everything picked. The bar that counts and deletes stays in sight
 * while the grid scrolls.
 *
 * One sheet scrolls, as the web's `.stk-main` does: the heading, the side
 * column and the filters scroll away together. The photos start under the
 * filters, beside the cast list, and take the whole width once the list ends
 * (see [SFloatGrid]); they are windowed against that one scroll, so a shoot of
 * thousands still composes only the rows in view.
 */
@Composable
internal fun PhotosPage(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val gallery = state.gallery
    var memberQuery by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val page = remember { StillsPageCoords() }
    val ids = remember(gallery.photos) { gallery.photos.map { it.id } }

    // Production only, and only with photos to pick from.
    val selectable = state.canPost && gallery.photos.isNotEmpty() && !gallery.failed
    // Where the bar sits on the page: once the page scrolls past its top, a
    // copy is pinned there instead (`position: sticky; top: 0`).
    var barAt by remember { mutableStateOf<Pair<Offset, Int>?>(null) }
    val barGone by remember { derivedStateOf { barAt?.let { (at, _) -> scroll.value > at.y } ?: false } }

    SNarrow(NARROW) { narrow ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .zillitVerticalScroll(scroll)
                    .then(page.modifier)
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SPageHead(
                    title = str(S.desktop_stk_nav_photos),
                    lede = if (state.me.scope == ViewerScope.Cleared) str(S.desktop_stk_photos_lede_viewer) else str(S.desktop_stk_photos_lede),
                )
                // The right column's top: the filters, the select bar, and
                // whatever stands in for the photos when there are none.
                val head: @Composable () -> Unit = {
                    if (narrow) {
                        GallerySide(state, memberQuery, { memberQuery = it }, compact = true, onEvent = onEvent)
                        Spacer(Modifier.height(10.dp))
                    }
                    GalleryFilters(state, onEvent)
                    Spacer(Modifier.height(16.dp))
                    if (selectable) {
                        SelectBar(
                            state,
                            onEvent,
                            Modifier.onGloballyPositioned { at -> page.offsetOf(at)?.let { barAt = it to at.size.width } },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (gallery.photos.isEmpty() || gallery.failed) GalleryEmpty(state, onEvent)
                }
                // `.stk-layout`, with the photos flowing round the cast list:
                // beside it while it lasts, the whole width under it. Under 800
                // the list is a dropdown over the filters and every row is whole.
                SFloatGrid(
                    items = if (gallery.failed) emptyList() else gallery.photos,
                    keyOf = { it.id },
                    scroll = scroll,
                    page = page,
                    side = if (narrow) null else { { GallerySide(state, memberQuery, { memberQuery = it }, compact = false, onEvent = onEvent) } },
                    head = head,
                    foot = {
                        SText(
                            text = if (gallery.hasMore) str(S.desktop_loading_more) else str(S.desktop_stk_thats_everything),
                            size = 13,
                            color = k.muted,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    },
                    onEnd = { onEvent(StillsEvent.LoadMorePhotos) },
                    layout = FloatGridSizes(side = SIDE_WIDTH, minCell = if (narrow) TILE_MIN_NARROW else TILE_MIN),
                ) { photo ->
                    SPhotoTile(
                        photo = photo,
                        onOpen = { onEvent(StillsEvent.OpenPhoto(photo.id, ids, run = false)) },
                        picking = gallery.picking,
                        picked = photo.id in gallery.picked,
                        onPick = { shift -> onEvent(StillsEvent.PickPhoto(photo.id, shift)) },
                    )
                }
            }

            ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))

            // Pinned over the top of the grid's column, exactly where the bar
            // was, once the page has scrolled past it.
            val pinned = barAt?.takeIf { selectable && gallery.picking && barGone }
            if (pinned != null) {
                val (at, width) = pinned
                Box(
                    Modifier
                        .offset { IntOffset(at.x.roundToInt(), 0) }
                        .width(with(LocalDensity.current) { width.toDp() }),
                ) { SelectBar(state, onEvent) }
            }
        }
    }

    if (gallery.askingDelete) {
        val n = gallery.chosen.size
        SConfirmDialog(
            title = if (n == 1) str(S.desktop_stk_delete_selected_title_one) else str(S.desktop_stk_delete_selected_title_n, n),
            message = str(S.desktop_stk_delete_selected_body),
            confirmLabel = gallery.removing?.let { (done, total) -> str(S.desktop_stk_deleting_n, done, total) }
                ?: if (n == 1) str(S.desktop_stk_delete_selected_one) else str(S.desktop_stk_delete_selected_n, n),
            busy = gallery.removing != null,
            onConfirm = { onEvent(StillsEvent.DeletePicked) },
            onClose = { onEvent(StillsEvent.AskDeletePicked(false)) },
        )
    }
}

/**
 * `.stk-select-bar` — "Select photos"; then, while picking, what is picked and
 * what to do with it.
 */
@Composable
private fun SelectBar(state: StillsUiState, onEvent: (StillsEvent) -> Unit, modifier: Modifier = Modifier) {
    val k = StillsTheme.c
    val gallery = state.gallery
    if (!gallery.picking) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SBtn(str(S.desktop_stk_select_photos), { onEvent(StillsEvent.Picking(true)) }, small = true)
        }
        return
    }
    val chosen = gallery.chosen.size
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    FlowRow(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(k.panel)
            .border(androidx.compose.foundation.BorderStroke(1.dp, k.line), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SText(str(S.desktop_n_selected, chosen), 15, Bold, maxLines = 1)
        SBtn(
            text = str(S.desktop_stk_select_all_shown, gallery.photos.size),
            onClick = { onEvent(StillsEvent.PickAllShown) },
            kind = SBtnKind.Link,
            small = true,
            enabled = chosen < gallery.photos.size && gallery.removing == null,
        )
        if (chosen > 0) {
            SBtn(str(S.desktop_ce_inbox_clear_selection), { onEvent(StillsEvent.ClearPicked) }, kind = SBtnKind.Link, small = true, enabled = gallery.removing == null)
        } else {
            SText(str(S.desktop_stk_select_hint), 13, color = k.muted)
        }
        Spacer(Modifier.weight(1f))
        SBtn(
            text = if (chosen == 1) str(S.desktop_stk_delete_selected_one) else str(S.desktop_stk_delete_selected_n, chosen),
            onClick = { onEvent(StillsEvent.AskDeletePicked(true)) },
            kind = SBtnKind.Danger,
            small = true,
            enabled = chosen > 0 && gallery.removing == null,
        )
        SBtn(str(S.ah_done), { onEvent(StillsEvent.Picking(false)) }, small = true, enabled = gallery.removing == null)
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
private val SIDE_WIDTH = 240.dp

/** `repeat(auto-fill, minmax(230px, 1fr))`, and 144px on a narrow window. */
private val TILE_MIN = 230.dp
private val TILE_MIN_NARROW = 144.dp
