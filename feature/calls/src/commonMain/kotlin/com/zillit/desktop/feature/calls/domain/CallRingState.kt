package com.zillit.desktop.feature.calls.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * How far one callee's ring has got — the web's `RingState` / `RING_TEXT`
 * (`CallOverlays.tsx:132-142`), which is what the caller's screen reads out.
 *
 * Deliberately NOT [CallStatus]. A roster row is `Ringing` from the instant we
 * dial, so the roster cannot tell "the server has been asked" from "their
 * device is showing a popup" — and those are the two states the person holding
 * the phone most wants told apart. Keeping them separate is why the outgoing
 * screen can say `Calling…` and then `Ringing…`, instead of sitting on the
 * first word for the whole ring (reported 2026-09-26).
 *
 * The negative ones are the diagnosis, not a failure: `Busy` is their other
 * call, `Unavailable` means the server found no device registered for that
 * identity at all, and `NoAnswer` is the ring timing out.
 */
enum class CallRingState(private val labelKey: String) {
    /** We asked the server to ring them; nothing has come back yet. */
    Calling(S.desktop_call_calling_ellipsis),

    /** Their device acknowledged the ring — `callRinging`. */
    Ringing(S.desktop_call_ringing_ellipsis),

    /** They answered; we are on our way into the room. */
    Accepted(S.desktop_call_joining),

    Declined(S.txt_badge_declined),

    /** Already on another call — `callBusy`. */
    Busy(S.txt_busy),

    /** No device registered for that identity — `callUnreachable`. */
    Unavailable(S.unavailable),

    /** The ring timed out — `callMissed`. */
    NoAnswer(S.desktop_no_answer),
    ;

    /** What the outgoing screen says for this state. */
    val label: String get() = str(labelKey)

    /** True while their device could still pick up. */
    val isPending: Boolean get() = this == Calling || this == Ringing

    companion object {
        /**
         * The best of several callees' states, as the web's `bestRing` picks
         * it: MONOTONIC, so a group ring reads forward — answered beats
         * ringing, ringing beats calling — and never flickers backwards
         * because one person's device was slower to acknowledge.
         *
         * With nobody pending and nobody in, the first negative is the whole
         * story; with nothing at all there is no ring to describe.
         */
        fun best(states: Collection<CallRingState>): CallRingState? = when {
            states.isEmpty() -> null
            Accepted in states -> Accepted
            Ringing in states -> Ringing
            Calling in states -> Calling
            else -> states.first()
        }

        /**
         * The status a roster/ring event moves a callee to.
         *
         * [CallStatus] is what every line already speaks, so the mapping is
         * made here rather than teaching the line a second vocabulary.
         * [busy] and [unreachable] are the two distinctions [CallStatus]
         * cannot carry — it collapses them into Declined and NotAnswered —
         * which is why they arrive as flags.
         */
        fun of(status: CallStatus, busy: Boolean = false, unreachable: Boolean = false): CallRingState? = when {
            busy -> Busy
            unreachable -> Unavailable
            status == CallStatus.Ringing -> Ringing
            status == CallStatus.InCall || status == CallStatus.Caller -> Accepted
            status == CallStatus.Declined -> Declined
            status == CallStatus.NotAnswered -> NoAnswer
            // Left is somebody who WAS in the call; it is not a ring verdict.
            else -> null
        }
    }
}
