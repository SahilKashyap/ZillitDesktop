package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.Composable

/**
 * The call history's two halves, supplied by the host.
 *
 * A pair rather than one slot because WhatsApp's Calls tab has two places:
 * the list in the directory column, and the pane beside it that shows the
 * picked call. Both read one history, so the host builds them together —
 * this module knows nothing about calls, and the app composes the two, the
 * same arrangement as the thread header's call buttons.
 */
interface CallsPanes {
    /**
     * The list. [inlineDetail] is true when [Side] is on screen beside it: a
     * row click then picks the call for the side pane instead of ringing.
     */
    @Composable
    fun List(inlineDetail: Boolean)

    /** Beside the list: "Voice and video calling", or the picked call's info. */
    @Composable
    fun Side(onStartCall: () -> Unit)
}
