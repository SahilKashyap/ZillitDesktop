@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.STILLS_SECTIONS
import com.zillit.desktop.feature.selectstills.domain.allowanceWords
import com.zillit.desktop.feature.selectstills.domain.decisionKey
import com.zillit.desktop.feature.selectstills.domain.discardBlocked
import com.zillit.desktop.feature.selectstills.domain.sectionKey

/**
 * The web's Publisher review page: the stills the reader's actors appear in,
 * to keep or discard.
 *
 * Discard does NOT delete. The photo stays in the production and stays in this
 * list, so a decision can be explained and undone, and the crew can see what
 * was refused rather than wonder where a shot went.
 *
 * Built for a run: Keep or Discard on a card, or open a photo and use
 * K / D / ← →; after a decision the lightbox moves on to the next photo by
 * itself.
 */
@Composable
internal fun ReviewPage(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val review = state.review
    val list = rememberLazyListState()
    val ids = remember(review.photos) { review.photos.map { it.id } }

    val atEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - END_MARGIN
        }
    }
    LaunchedEffect(list) { snapshotFlow { atEnd }.collect { if (it) onEvent(StillsEvent.LoadMoreReview) } }

    // `.stk-rv-grid`: as many 290px cards as fit, one on a narrow window — and
    // the columns are worked out here, so every row agrees on them.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val page = minOf(maxWidth, PAGE_MAX)
        val room = page - PAGE_GUTTER * 2
        val columns = if (maxWidth <= NARROW) 1 else maxOf(1, ((room + GAP) / (CARD_MIN + GAP)).toInt())
        val rows = remember(review.photos, columns) { review.photos.chunked(columns) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Windowed by ROW, not by card: a review card grows with what it
            // has to say (the other agents' lines, a warning, the reason box),
            // and a grid of equal cells cannot hold that.
            LazyColumn(
                state = list,
                modifier = Modifier.widthIn(max = PAGE_MAX).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
            ) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SPageHead(str(S.desktop_stk_nav_review), str(S.desktop_stk_review_lede))
                        Represents(state, onEvent)
                        val tabsLabel = str(S.desktop_stk_review_tabs)
                        FlowRow(
                            Modifier.fillMaxWidth().semantics { contentDescription = tabsLabel },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReviewTab.entries.forEach { tab ->
                                STab(
                                    label = str(tabLabel(tab)),
                                    active = review.tab == tab,
                                    onClick = { onEvent(StillsEvent.ReviewTabChanged(tab)) },
                                    count = review.counts?.forTab(tab) ?: 0,
                                    hint = str(tabHint(tab)),
                                )
                            }
                        }
                    }
                }

                if (review.loading && review.photos.isEmpty() || review.failed || review.photos.isEmpty()) {
                    item(key = "body") {
                        when {
                            review.failed -> SAlert(SAlertTone.Error) {
                                SText(str(S.desktop_stk_photos_load_failed), 15, color = k.errorFg)
                                SBtn(str(S.desktop_csync_try_again), { onEvent(StillsEvent.ReloadReview) }, small = true)
                            }
                            review.loading -> SLoading()
                            else -> SCard(Modifier.fillMaxWidth()) {
                                SText(str(emptyWords(review.tab)), 15, color = k.muted)
                            }
                        }
                    }
                } else {
                    items(rows, key = { row -> row.first().id }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { photo ->
                                Box(Modifier.weight(1f)) { ReviewCard(state, photo, ids, onEvent) }
                            }
                            repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                    item(key = "foot") {
                        SText(
                            text = if (review.hasMore) str(S.desktop_loading_more) else str(S.desktop_stk_thats_everything),
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

/**
 * `.stk-rv-bar` — who the reader answers for.
 *
 * There is no "Reviewing as" picker beside it: the queue is the signed-in
 * person's, and the service will not hand out anybody else's. With more than
 * one actor the names are chips, and one narrows the queue to that actor.
 * Under them, what is left of each allowance, for the sections that are capped.
 */
@Composable
private fun Represents(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val clients = state.clients
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SText(str(S.desktop_stk_represents), 13, color = k.muted)
        when {
            clients.isEmpty() -> SText(str(S.desktop_stk_represents_nobody), 15, color = k.muted, italic = true)
            clients.size == 1 -> SText(clients.first().name, 15)
            else -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SChip(str(S.desktop_stk_review_all_actors), state.review.member == null, { onEvent(StillsEvent.ReviewMemberChanged(null)) })
                clients.forEach { client ->
                    SChip(client.name, state.review.member == client.memberId, { onEvent(StillsEvent.ReviewMemberChanged(client.memberId)) })
                }
            }
        }
        // The sections that are capped, with what is left of each.
        clients.forEach { client ->
            STILLS_SECTIONS.filter { client.allowance[it]?.limit != null }.forEach { section ->
                val words = allowanceWords(client.allowance[section])
                AllowanceLine(
                    prefix = if (clients.size > 1) "${client.name} · " else "",
                    section = section,
                    words = words,
                )
            }
        }
    }
}

/** `.stk-dl-left` — "Solo: 2 of 3 discards left", in the colour its news deserves. */
@Composable
internal fun AllowanceLine(
    prefix: String,
    section: String,
    words: com.zillit.desktop.feature.selectstills.domain.AllowanceWords,
    modifier: Modifier = Modifier,
    long: Boolean = false,
) {
    val k = StillsTheme.c
    val text = buildString {
        append(prefix)
        append(str(sectionKey(section)))
        append(": ")
        append(allowanceText(words))
        if (long && words.key == S.desktop_stk_allow_spent) append(". ${str(S.desktop_stk_allow_spent_free)}")
    }
    SText(text, 13, color = if (words.bad) k.warn else k.muted, modifier = modifier)
}

/** An allowance's words, with its own numbers filled in. */
@Composable
internal fun allowanceText(words: com.zillit.desktop.feature.selectstills.domain.AllowanceWords): String = when (words.key) {
    S.desktop_stk_allow_over -> str(words.key, words.used, words.limit)
    S.desktop_stk_allow_spent -> str(words.key, words.limit)
    S.desktop_stk_allow_left -> str(words.key, words.remaining, words.limit)
    else -> str(words.key)
}

/**
 * `.stk-rv-card` — one photo in the queue: the picture (4:3, stamped "kept" or
 * "discarded"), whose photo it is to the reader, what the other agents said,
 * what is left of the allowance, and Keep / Discard / Undo right there.
 *
 * A photo with two of the reader's actors in it is opened to be decided: one
 * pair of buttons cannot answer for two people.
 */
@Composable
private fun ReviewCard(state: StillsUiState, photo: PhotoTile, ids: List<String>, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val review = state.review
    val rows = photo.myRows
    val one = rows.singleOrNull()
    val decision = one?.state ?: Decision.Pending
    val free = photo.discardIsFree
    val allowance = one?.let { row -> state.clients.firstOrNull { it.memberId == row.memberId }?.allowance }
    val section = photo.section?.let { allowance?.get(it) }
    // Shown only where there is a cap — and not when another agent already
    // refused the photo: agreeing with a refusal is free.
    val words = section?.takeIf { it.limit != null && !free }?.let { allowanceWords(it) }
    val blocked = one?.let { discardBlocked(allowance, photo.section, it.state, free) } == true
    val refused = photo.others.any { it.state == Decision.Rejected }
    val shape = RoundedCornerShape(12.dp)
    val edge = when {
        one != null && decision == Decision.Approved -> k.ok.copy(alpha = 0.5f)
        one != null && decision == Decision.Rejected -> com.zillit.desktop.feature.selectstills.domain.PublicState.Blocked.let { k.warn.copy(alpha = 0.5f) }
        else -> k.line
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(k.panel)
            .border(BorderStroke(1.dp, edge), shape),
    ) {
        // `.stk-rv-img` — click to open full size.
        ZillitTooltip(text = str(S.desktop_stk_open_full_size)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(k.stageBg)
                .clickable { onEvent(StillsEvent.OpenPhoto(photo.id, ids, run = true)) },
        ) {
            SImage(
                url = photo.thumbUrl,
                modifier = Modifier.fillMaxSize().then(if (decision == Decision.Rejected) Modifier.alpha(0.45f) else Modifier),
                contentScale = ContentScale.Crop,
                description = photo.originalName,
            ) {
                SText(str(S.desktop_stk_no_preview), 13, color = k.muted)
            }
            if (one != null && decision != Decision.Pending) {
                SPill(
                    text = str(decisionKey(decision)),
                    kind = if (decision == Decision.Approved) SPillKind.GateOk else SPillKind.GateNo,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
        }
        }

        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SText(
                    text = rows.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name.ifBlank { str(S.desktop_stk_unknown_person) } } ?: "—",
                    size = 15,
                    weight = Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                SText(peopleWords(photo.people), 13, color = k.muted, maxLines = 1)
            }

            // In a group the other agents' decisions matter: a photo already
            // refused by somebody else will not go public whatever you choose.
            if (photo.others.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    photo.others.forEach { row ->
                        SDecisionChip(row.name.ifBlank { str(S.desktop_stk_unknown_person) }, row.state)
                    }
                }
            }

            if (refused && decision != Decision.Rejected) {
                SText(str(S.desktop_stk_discarded_by_other), 13, color = k.accentInk)
            }

            if (one != null && one.note.isNotBlank() && decision == Decision.Rejected) {
                SText(str(S.desktop_stk_your_reason, one.note), 13, color = k.warn, italic = true)
            }

            words?.let { AllowanceLine("", photo.section.orEmpty(), it) }

            if (one != null) {
                SDecisionBar(
                    state = one.state,
                    where = SDecideWhere.Card,
                    blocked = blocked,
                    busy = review.busyId == photo.id,
                    asking = review.draftFor == photo.id,
                    note = review.draftNote,
                    onAsk = { onEvent(StillsEvent.CardDraft(photo.id, one.note)) },
                    onCancel = { onEvent(StillsEvent.CardDraft(null, "")) },
                    onNote = { onEvent(StillsEvent.CardDraft(photo.id, it)) },
                    onDecide = { next, note -> onEvent(StillsEvent.DecideOnCard(photo.id, one.memberId, next, note)) },
                )
            } else {
                SChip(str(S.desktop_stk_open_to_decide), active = false, onClick = { onEvent(StillsEvent.OpenPhoto(photo.id, ids, run = true)) })
            }
        }
    }
}

private fun tabLabel(tab: ReviewTab) = when (tab) {
    ReviewTab.Pending -> S.desktop_stk_review_tab_pending
    ReviewTab.Approved -> S.desktop_stk_review_tab_approved
    ReviewTab.Rejected -> S.desktop_stk_review_tab_rejected
    ReviewTab.All -> S.desktop_stk_review_tab_all
}

private fun tabHint(tab: ReviewTab) = when (tab) {
    ReviewTab.Pending -> S.desktop_waiting_on_you
    ReviewTab.Approved -> S.desktop_stk_review_tab_approved_hint
    ReviewTab.Rejected -> S.desktop_stk_review_tab_rejected_hint
    ReviewTab.All -> S.desktop_stk_review_tab_all_hint
}

private fun emptyWords(tab: ReviewTab) = when (tab) {
    ReviewTab.Pending -> S.desktop_stk_review_empty_pending
    ReviewTab.Approved -> S.desktop_stk_review_empty_approved
    ReviewTab.Rejected -> S.desktop_stk_review_empty_rejected
    ReviewTab.All -> S.desktop_stk_review_empty_approved
}

private val NARROW: Dp = 760.dp
private val PAGE_MAX = 1280.dp

/** `.stk-page`'s side padding, and the grid's own gap. */
private val PAGE_GUTTER = 24.dp
private val GAP = 14.dp
private val CARD_MIN = 290.dp
private const val END_MARGIN = 6
