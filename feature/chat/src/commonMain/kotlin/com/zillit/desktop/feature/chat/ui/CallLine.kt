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

    /**
     * The mediasoup SFU — shown as **Line 3** since 2026-09-26.
     *
     * The numbers the user sees and the stacks behind them were swapped on
     * request: LiveKit, the newest and now the preferred line, is "Line 1",
     * and mediasoup takes the third slot. The `wire` words are the server's
     * and did not move, which is why the mapping reads crossed here and
     * nowhere else.
     */
    Three("mediasoup", S.txt_line_three),

    /** LiveKit, shown as **Line 1** — offered only where remote config lists the production. */
    One("livekit", S.txt_line_one),
    ;

    /** The line's name as the phones show it. */
    val label: String get() = str(labelKey)

    companion object {
        /**
         * What every production has: Agora and mediasoup. LiveKit — labelled
         * Line 1 — is added by the host where remote config lists the
         * production, so it is absent here.
         */
        val DEFAULT: List<CallLine> = listOf(Two, Three)
    }
}
