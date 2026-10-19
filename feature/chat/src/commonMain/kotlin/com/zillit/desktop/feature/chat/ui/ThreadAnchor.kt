package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.feature.chat.domain.ChatMessage

/**
 * What the message list is currently pinned to: the thread on show, and the
 * newest line it was last brought down to.
 *
 * The newest line is held by id rather than by count because a count says
 * nothing about *which* end moved: a page of older history arriving grows the
 * list without changing what is newest, and following that would drag a reader
 * out of the history they just asked for.
 */
internal data class ThreadAnchor(val threadId: String?, val newestId: String?)

/**
 * Whether the thread list should come down to its newest message.
 *
 * Three rules, in the order they beat each other:
 *
 * A thread just opened or just switched has no reader mid-scroll to protect,
 * so it always lands on the newest line — the shape every messenger opens in.
 *
 * Your own message always wins the argument. Sending is the one moment the
 * reader has said, in the clearest way the UI offers, that they are done
 * reading back: they typed into the composer and pressed Enter. Treating a
 * send like any other arrival — gated on already being at the bottom — is
 * what left a reply to a quoted line sitting below the fold.
 *
 * Someone else's message only arrives into view for a reader who is already
 * at the bottom. Scrolled away means reading, and yanking a reader down
 * mid-sentence to show them a line they have not asked for is theft.
 */
internal fun followsNewest(
    anchor: ThreadAnchor?,
    threadId: String?,
    newest: ChatMessage?,
    atBottom: Boolean,
): Boolean = when {
    // Nothing to come down to; the empty thread draws its own placeholder.
    newest == null -> false
    // Never anchored, or anchored on a different conversation.
    anchor == null || anchor.threadId != threadId -> true
    // The newest line is the one we already came down to. A list that grew
    // at the other end, an edit, a receipt or the server's echo replacing an
    // optimistic bubble all land here, and none of them is an arrival.
    anchor.newestId == newest.uniqueId -> false
    newest.isMine -> true
    else -> atBottom
}

/**
 * Keeps the reversed message list on its newest line, by [followsNewest]'s
 * rules.
 *
 * Reversed layout means index 0 *is* the newest message and the visual
 * bottom, so following is simply "go back to zero". It also pins that bottom
 * by construction: media bubbles further up can inflate as their thumbnails
 * load without shoving the newest message out of view, which is the failure
 * mode a forward list has here.
 */
@Composable
internal fun FollowNewestMessage(
    listState: LazyListState,
    threadId: String?,
    messages: List<ChatMessage>,
) {
    var anchor by remember(listState) { mutableStateOf<ThreadAnchor?>(null) }
    val newest = messages.lastOrNull()
    LaunchedEffect(listState, threadId, newest?.uniqueId) {
        // Read before the scroll, and before this frame lays out: the gate has
        // to reflect where the reader was standing when the message landed,
        // not where the insert left them.
        val atBottom = listState.firstVisibleItemIndex <= AT_BOTTOM_SLACK
        if (!followsNewest(anchor, threadId, newest, atBottom)) return@LaunchedEffect
        listState.scrollToItem(0)
        anchor = ThreadAnchor(threadId, newest?.uniqueId)
    }
}

/**
 * How far above the newest line still counts as following the conversation.
 *
 * One row of slack, not none: a nudge of the wheel, or the half-row a
 * trackpad leaves behind, is not a decision to stop reading the thread —
 * scrolling a screenful is, and that clears this comfortably.
 */
private const val AT_BOTTOM_SLACK = 1
