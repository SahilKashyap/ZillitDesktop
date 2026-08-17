package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Which end of the list the newest item lands on. */
enum class LatestEdge {
    /** Chat-shaped lists: newest appended at the bottom. */
    Bottom,

    /** Mailbox-shaped lists: newest inserted at the top. */
    Top,
}

/**
 * Keeps a list pinned to its newest item — but only for readers already there.
 *
 * The convention every messaging list follows: a reader sitting at the latest
 * message is following the conversation, so a new arrival scrolls into view;
 * a reader who scrolled away is *reading something*, and yanking them to the
 * bottom mid-paragraph is theft. The gate is measured from the layout the
 * moment the count changes — before the scroll — so the decision reflects
 * where the reader actually was, not where the insert pushed them.
 *
 * The first fill (0 → n) counts as "at the latest", which is what makes a
 * freshly opened board or thread start on its newest entry instead of its
 * oldest.
 */
@Composable
fun FollowLatest(
    listState: LazyListState,
    itemCount: Int,
    edge: LatestEdge = LatestEdge.Bottom,
    /**
     * Identity of the content — the unit, thread or folder on show. When it
     * changes the list re-anchors to the latest unconditionally: a switched
     * tab is a fresh view, not a reader mid-scroll, and without this a tab
     * whose row count happens to match the last one never anchors at all.
     */
    contentKey: Any? = null,
) {
    val atLatest by remember(listState, edge) {
        derivedStateOf {
            val info = listState.layoutInfo
            when (edge) {
                // The previously-newest row is still on screen (one row of
                // slack, so a half-scrolled-away footer still counts).
                LatestEdge.Bottom -> {
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    lastVisible >= info.totalItemsCount - AT_LATEST_SLACK
                }
                LatestEdge.Top -> {
                    val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                    firstVisible < AT_LATEST_SLACK
                }
            }
        }
    }

    // The sentinel marks "never anchored": the first run with content jumps
    // unconditionally. A warm cache fills and lays the list out before this
    // effect ever runs, so on frame one the reader already LOOKS scrolled
    // away — measuring atLatest there concluded "don't move" and left every
    // cached board opening at its top.
    var anchoredTo by remember(listState) { mutableStateOf<Any?>(Unanchored) }
    LaunchedEffect(itemCount, contentKey) {
        if (itemCount == 0) return@LaunchedEffect
        val fresh = anchoredTo == Unanchored || anchoredTo != contentKey
        if (!fresh && !atLatest) return@LaunchedEffect
        when (edge) {
            LatestEdge.Bottom -> listState.scrollToItem(itemCount - 1)
            LatestEdge.Top -> listState.scrollToItem(0)
        }
        anchoredTo = contentKey
    }
}

/**
 * [FollowLatest] for a `reverseLayout` list, where index 0 IS the newest item
 * and the visual bottom.
 *
 * Reversed layout is the chat-thread shape: the bottom edge is pinned by
 * construction, so media bubbles above inflating as their thumbnails load
 * cannot shove the newest message out of view — the failure mode that made
 * forward chat lists open mid-history. Following is just "stay at zero":
 * unconditionally on a thread switch, and on new arrivals only for a reader
 * already at the bottom.
 */
@Composable
fun FollowLatestReversed(
    listState: LazyListState,
    itemCount: Int,
    contentKey: Any? = null,
) {
    var anchoredTo by remember(listState) { mutableStateOf<Any?>(Unanchored) }
    LaunchedEffect(itemCount, contentKey) {
        if (itemCount == 0) return@LaunchedEffect
        val fresh = anchoredTo == Unanchored || anchoredTo != contentKey
        val atLatest = listState.firstVisibleItemIndex < AT_LATEST_SLACK
        if (fresh || atLatest) {
            listState.scrollToItem(0)
            anchoredTo = contentKey
        }
    }
}

/**
 * Rows of tolerance between "at the latest" and "reading elsewhere".
 *
 * Two, not one: the newest row plus the one before it, because a date
 * separator or a tall card routinely sits between the reader and the exact
 * last index at the moment a message lands.
 */
private const val AT_LATEST_SLACK = 2

/** "No anchor yet" — distinct from any real content key, including null. */
private val Unanchored = Any()
