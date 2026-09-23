package com.zillit.desktop.feature.chat.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A line a call can be placed on, as the phones name them.
 *
 * Chat does not depend on the calls module, so the line is named here by its
 * wire word and the host maps it to a provider. Three because that is what
 * the server offers: separate plumbing each, not one line with three modes.
 */
enum class CallLine(val wire: String, private val labelKey: String) {
    /** Agora. Listed first: every deployment has it, and a plain Return picks it. */
    Two("agora", S.txt_line_two),

    /** The mediasoup SFU. */
    One("mediasoup", S.txt_line_one),

    /** LiveKit — offered only where remote config lists the production. */
    Three("livekit", S.txt_line_three),
    ;

    /** The line's name as the phones show it. */
    val label: String get() = str(labelKey)

    companion object {
        /** What every production has. Line 3 is added by the host when it is switched on. */
        val DEFAULT: List<CallLine> = listOf(Two, One)
    }
}
