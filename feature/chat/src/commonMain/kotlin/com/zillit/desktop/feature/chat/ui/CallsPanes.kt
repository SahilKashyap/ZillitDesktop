package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.chat.domain.CrewContact
import kotlinx.coroutines.flow.Flow

/**
 * The call history's two halves, supplied by the host.
 *
 * A pair rather than one slot because WhatsApp's Calls tab has two places:
 * the list in the directory column, and the pane beside it that shows the
 * picked call. Both read one history, so the host builds them together —
 * this module knows nothing about calls, and the app composes the two, the
 * same arrangement as the thread header's call buttons.
 *
 * Both take `onOpenChat`, the way back from a call to its conversation: the
 * screen switches to Chats and opens [CrewContact.userId]'s thread — a room
 * id when `isGroup`, the thread header's `onCall` convention.
 */
interface CallsPanes {
    /**
     * The list. [inlineDetail] is true when [Side] is on screen beside it: a
     * row click then picks the call for the side pane instead of ringing.
     * Without it the list's own detail sheet offers [onOpenChat].
     *
     * [missedBadge] is the unread missed calls the list's own Missed chip
     * wears — the number the screen already holds for the Calls tab
     * ([ChatUiState.callsBadge]), passed down rather than counted twice, so
     * the chip and the tab can never disagree.
     */
    @Composable
    fun List(
        inlineDetail: Boolean,
        missedBadge: Int,
        onOpenChat: (peer: CrewContact, isGroup: Boolean) -> Unit,
    )

    /** Beside the list: "Voice and video calling", or the picked call's info. */
    @Composable
    fun Side(onStartCall: () -> Unit, onOpenChat: (peer: CrewContact, isGroup: Boolean) -> Unit)

    /**
     * Whether [List] is on its Missed view — what reads a missed call. iOS
     * reads them on the Missed segment only (`MissedCallViewController.swift:376-378, 399`),
     * never on All.
     *
     * Null is a host that does not say: the tab then reads the missed calls
     * once as it comes up, which is what it always did.
     */
    val missedOnly: Flow<Boolean>? get() = null
}
