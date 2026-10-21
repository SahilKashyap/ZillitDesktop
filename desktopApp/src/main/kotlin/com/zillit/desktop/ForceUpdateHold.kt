package com.zillit.desktop

import com.zillit.desktop.feature.calls.domain.CallPhase

/**
 * Whether a mandatory update covers the frame with the force-update screen.
 *
 * Blocking means nothing in Zillit takes a click until the update is in —
 * except a call already under way. Covering it would leave someone mid-call
 * with no way to mute, share or hang up, so while one runs the update shows
 * as the red strip instead (it can still be downloaded and installed from
 * there), and the screen comes down the moment the call ends.
 *
 * Only a call we are part of holds it off: one ringing in is not ongoing, so
 * a blocked build cannot be used to answer it.
 *
 * @param packaged false under a Gradle run, which never blocks — a raised
 *   floor must not lock a developer out.
 */
internal fun forceUpdateBlocks(mandatory: Boolean, packaged: Boolean, callPhase: CallPhase): Boolean =
    mandatory && packaged && !callPhase.holdsForceUpdate

/** Placing, in, or hanging up a call — Ending included, so the screen waits for the teardown. */
private val CallPhase.holdsForceUpdate: Boolean
    get() = this == CallPhase.Outgoing || this == CallPhase.InCall || this == CallPhase.Ending
