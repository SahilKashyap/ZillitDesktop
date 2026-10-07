package com.zillit.desktop.feature.selectstills.domain

import com.zillit.desktop.core.strings.S

/**
 * How a photo's states read on screen, in the web's words — its
 * `lib/status.js`. Pure lookups → string keys.
 */

/** The pill a photo wears: its text, its tooltip, and which variant it is. */
data class StateMeta(val key: String, val hint: String, val tone: PillTone)

/** A pill's colouring, as the stylesheet's `gate-ok` / `gate-wait` / `gate-no`. */
enum class PillTone { Ok, Warn, Bad }

fun stateMeta(state: PublicState): StateMeta = when (state) {
    PublicState.Approved -> StateMeta(S.desktop_stk_state_public, S.desktop_stk_state_public_hint, PillTone.Ok)
    PublicState.Pending -> StateMeta(S.desktop_stk_state_awaiting, S.desktop_stk_state_awaiting_hint, PillTone.Warn)
    PublicState.Blocked -> StateMeta(S.desktop_stk_state_blocked, S.desktop_stk_state_blocked_hint, PillTone.Bad)
}

/** An agent's own decision on one row: undecided · kept · discarded. */
fun decisionKey(state: Decision): String = when (state) {
    Decision.Pending -> S.desktop_stk_decision_undecided
    Decision.Approved -> S.desktop_stk_decision_kept
    Decision.Rejected -> S.desktop_stk_decision_discarded
}

/** Why a photo could not be processed, in words; a general line for anything else. */
fun errorKey(code: String): String = when (code) {
    "too_large" -> S.desktop_stk_error_too_large
    "unsupported_type", "heic_off" -> S.desktop_stk_error_unsupported_type
    "unreadable" -> S.desktop_stk_error_unreadable
    "region_not_supported" -> S.desktop_stk_error_region
    "recognition_not_allowed" -> S.desktop_stk_error_recognition
    "daily_cap" -> S.desktop_stk_error_daily_cap
    else -> S.desktop_stk_error_other
}
