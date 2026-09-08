package com.zillit.desktop.feature.chat.ui

/**
 * A line a call can be placed on, as the phones name them.
 *
 * Chat does not depend on the calls module, so the line is named here by its
 * wire word and the host maps it to a provider. Three because that is what
 * the server offers: separate plumbing each, not one line with three modes.
 */
enum class CallLine(val wire: String, val label: String) {
    /** Agora. Listed first: every deployment has it, and a plain Return picks it. */
    Two("agora", "Line 2"),

    /** The mediasoup SFU. */
    One("mediasoup", "Line 1"),

    /** LiveKit — offered only where remote config lists the production. */
    Three("livekit", "Line 3"),
    ;

    companion object {
        /** What every production has. Line 3 is added by the host when it is switched on. */
        val DEFAULT: List<CallLine> = listOf(Two, One)
    }
}
