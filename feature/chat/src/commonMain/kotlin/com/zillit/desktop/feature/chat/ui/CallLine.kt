package com.zillit.desktop.feature.chat.ui

import com.zillit.desktop.core.config.CallEngines
import com.zillit.desktop.core.config.CallLineNumbering
import com.zillit.desktop.core.strings.callLineLabel

/**
 * A line a call can be placed on, named by its engine.
 *
 * Chat does not depend on the calls module, so the line is named here by its
 * wire word and the host maps it to a provider. Three because that is what
 * the server offers: separate plumbing each, not one line with three modes.
 *
 * The number a user sees is not fixed to the engine: Remote Config's
 * `calling_lines` sets it (LiveKit Line 1, Agora Line 2, mediasoup Line 3
 * when it says nothing), and the host decides which lines a production
 * offers and in what order.
 */
enum class CallLine(val wire: String) {
    Agora(CallEngines.AGORA),
    Mediasoup(CallEngines.MEDIASOUP),
    LiveKit(CallEngines.LIVEKIT),
    ;

    /** "Line N", as every client numbers this engine right now. */
    val label: String get() = callLineLabel(CallLineNumbering.numberOf(wire))

    companion object {
        /** The line for an engine word, or null for one the desktop does not run. */
        fun ofWire(raw: String?): CallLine? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }

        /** A stand-in for previews and tests; the host always passes the real list. */
        val DEFAULT: List<CallLine> = listOf(Agora, Mediasoup)
    }
}
