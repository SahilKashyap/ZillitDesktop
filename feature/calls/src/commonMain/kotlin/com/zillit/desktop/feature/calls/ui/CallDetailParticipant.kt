package com.zillit.desktop.feature.calls.ui

/** One line of the sheet's roster. */
data class CallDetailParticipant(
    val userId: String,
    val name: String,
    /** "Started the call" for the host, "Added by …" for a late invite; null otherwise. */
    val subLabel: String?,
    /** Host / In call / Left / Declined / Missed / Ringing — or nothing to say. */
    val badge: String?,
    /** Attendance — "12m 4s · 2 joins" or "Not joined"; null when the row cannot say. */
    val meta: String?,
    val isGuest: Boolean,
)
