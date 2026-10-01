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

    /** The mediasoup SFU, shown as **Line 1**, as Android shows it. */
    One("mediasoup", S.txt_line_one),

    /**
     * LiveKit, shown as **Line 3** — offered only where remote config lists
     * the production.
     *
     * The number matches the phones (Android maps `CALLING_LIVEKIT` to
     * `txt_line_three`) and the internal names here, which all say "line
     * three" for LiveKit: `LineThreeGate`, `line_three_enabled_in`. These were
     * briefly crossed (2026-09-26 – 2026-10-01) so that LiveKit read "Line 1";
     * that made one call carry two different numbers on two clients.
     */
    Three("livekit", S.txt_line_three),
    ;

    /** The line's name as the phones show it. */
    val label: String get() = str(labelKey)

    companion object {
        /**
         * What every production has: Agora and mediasoup. LiveKit — labelled
         * Line 3 — is added by the host where remote config lists the
         * production, so it is absent here.
         */
        val DEFAULT: List<CallLine> = listOf(Two, One)
    }
}
