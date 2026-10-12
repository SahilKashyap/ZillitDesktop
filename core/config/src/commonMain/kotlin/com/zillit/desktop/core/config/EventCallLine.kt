package com.zillit.desktop.core.config

/**
 * Which calling line a Calendar or Box Schedule event's call runs on — the
 * events' own `line` field, written by the create and edit forms.
 *
 * Only the exact string `"line_1"` is Line 1 (a LiveKit MEET, joined through
 * the lobby). Everything else is Line 2, the CnC / Agora room every event used
 * before the field existed: a missing key, `null`, a blank, a different case,
 * a typo. Every event saved before the field shipped answers with no `line`
 * at all, so defaulting the other way would move old events onto a stack
 * their room was never created on. Nothing else decides it either — not the
 * room id's shape, the call type, or whether a group id is present.
 *
 * One reader, used by every read path (list, detail, socket, the Box Schedule
 * mapper), so no call site compares strings itself.
 */
enum class EventCallLine(val wire: String, val engine: String) {
    /** LiveKit MEET. The event's room id is the meetId. */
    Line1("line_1", CallEngines.LIVEKIT),

    /** The existing CnC / Agora room. */
    Line2("line_2", CallEngines.AGORA),
    ;

    companion object {
        /**
         * What a NEW event's form starts on. A product default, not a missing
         * key — kept apart from [of] on purpose: an edit reads the event's own
         * value, and an old event without one must open on [Line2].
         */
        val CREATE_DEFAULT: EventCallLine = Line1

        /** The call types that can have a meeting, in both tools' spelling. */
        val MEETING_CALL_TYPES: Set<String> = setOf("audio", "video", "meet_in_person_call")

        /** The line a stored value names; anything but `"line_1"` exactly is [Line2]. */
        fun of(raw: String?): EventCallLine = if (raw == Line1.wire) Line1 else Line2

        /**
         * Whether an event of [callType] has a line at all — the ONE condition
         * behind both the form's picker and the payload's `line` key, so the
         * two can never disagree. Meet-in-person, none, notes and personal
         * calendar events have no meeting and carry no key.
         *
         * Includes `meet_in_person_call`: the first version on another client
         * said "audio or video only", which left that type joinable but stuck
         * on Line 2 — a Join button and no picker.
         */
        fun applies(callType: String?, personal: Boolean = false): Boolean =
            !personal && callType in MEETING_CALL_TYPES
    }
}
