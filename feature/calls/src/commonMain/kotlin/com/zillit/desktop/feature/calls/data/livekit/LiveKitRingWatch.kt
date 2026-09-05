package com.zillit.desktop.feature.calls.data.livekit

/**
 * Server truth for an incoming ring, from the presence socket's active-call
 * list — the heartbeat's answer, and the server's own `activeCallsChanged`.
 *
 * The phones' rule (`AppGraph`'s ActiveCallsChanged branch): a ring stays up
 * only while its call is on the list. A call that WAS listed and is gone has
 * been cancelled or has ended — dismiss on the first absence. A ring never
 * yet listed may simply have beaten the first roster update, so it gets one
 * broadcast's grace and is dismissed on the second consecutive absence. And
 * a listed call that already counts this user among its in-call
 * participants was answered on another of their devices: the ring here is
 * late, and stops.
 *
 * This is what reaches a device whose socket was down when the
 * `callCancelled` or `callHandledElsewhere` went out — the ring that used
 * to sound on for the whole timeout.
 */
class LiveKitRingWatch {
    enum class Verdict { Stale, AnsweredElsewhere }

    private var seen = false
    private var absences = 0

    /** A new ring, or none: forget the last one's history. */
    fun reset() {
        seen = false
        absences = 0
    }

    /** One broadcast, judged for the ring [callId] this device ([selfUserId]) is showing. */
    fun judge(callId: String, selfUserId: String, calls: List<LiveKitActiveCall>): Verdict? {
        val listed = calls.firstOrNull { it.callId == callId }
        return when {
            listed == null -> {
                absences++
                if (seen || absences >= ABSENCES_BEFORE_STALE) Verdict.Stale else null
            }
            selfUserId.isNotBlank() && selfUserId in listed.inCallUserIds -> Verdict.AnsweredElsewhere
            else -> {
                seen = true
                absences = 0
                null
            }
        }
    }

    companion object {
        /** An invite can beat the first roster update by one cycle. */
        const val ABSENCES_BEFORE_STALE = 2
    }
}
