package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallLogParticipant
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Call activity sheet's words, worked out away from the composable so
 * they can be tested — Android's `CallActivityDetailSheet` (`bottomNav/
 * chatAndCall/fragment/CallActivityDetailSheet.kt`), which the desktop dialog
 * mirrors row for row: identity header, three chips, a roster with a status
 * badge per person.
 */

/** "Audio call · Line 2" — the header's second line (`CallActivityDetailSheet.kt:88-101`). */
fun CallLogEntry.detailSubtitle(): String {
    val kind = if (type == CallType.Audio) "Audio call" else "Video call"
    return "$kind · ${line.label}"
}

/** The direction chip's word (`:105-109`): a miss outranks the direction. */
fun CallLogEntry.directionLabel(): String = when {
    missed -> "Missed"
    direction == CallLogDirection.Outgoing -> "Outgoing"
    else -> "Incoming"
}

/**
 * The duration chip, or null to hide it (`:127-137`).
 *
 * A Line 3 row's `call_duration` cannot be read on its own — it arrives
 * negative when no end time was stamped — so its wall clock is rebuilt from
 * the roster's answer times (`liveKitCallDurationMs`, `:265-279`). The other
 * lines read the row's own duration, in the units the list uses.
 */
fun CallLogEntry.detailDuration(): String? =
    if (line == CallLine.Three) {
        liveKitDurationMillis()?.let(::formatMillis)
    } else {
        durationMillis.takeIf { it > 0 }?.let(::formatDuration)
    }

/**
 * `:265-279`: from the first answer to the call's end (`start_time +
 * call_duration`), which survives leave-and-rejoin gaps; failing a usable
 * end, the longest single stay. Null when nobody ever answered — the
 * direction chip already says "Missed".
 */
private fun CallLogEntry.liveKitDurationMillis(): Long? {
    val firstAnswer = participants.map { it.answeredAtMillis }.filter { it > 0 }.minOrNull() ?: return null
    val endTime = startedAtMillis + durationMillis
    if (durationMillis > 0 && endTime > firstAnswer) return endTime - firstAnswer
    return participants.maxOfOrNull { it.totalMillis }?.takeIf { it > 0 }
}

/**
 * The roster (`:172-176`): the rich `participants` list when the row has one
 * — Line 3 carries per-person attendance — else the legacy shape rebuilt
 * from `call_users` and the row's own ends.
 */
fun CallLogEntry.detailParticipants(
    selfUserId: String?,
    nameFor: (String) -> String?,
): List<CallDetailParticipant> {
    val detailed = participants.filter { it.userId.isNotBlank() }
    if (detailed.isNotEmpty()) return detailed.map { detailedRow(it, selfUserId, nameFor) }
    return legacyParticipants(selfUserId, nameFor)
}

/**
 * `:184-226`. The directory's name wins over `display_name` for real users —
 * the log froze a name at call time and the directory reflects a rename —
 * but a guest has no directory entry, so `display_name` is all they have.
 */
private fun CallLogEntry.detailedRow(
    person: CallLogParticipant,
    selfUserId: String?,
    nameFor: (String) -> String?,
): CallDetailParticipant {
    val isSelf = person.userId == selfUserId
    val directoryName = if (person.isGuest) "" else nameFor(person.userId).orEmpty()
    val name = when {
        isSelf -> "You"
        directoryName.isNotBlank() -> directoryName
        person.displayName.isNotBlank() -> person.displayName
        else -> UNKNOWN_NAME
    }
    val inviter = person.invitedBy.takeIf { it.isNotBlank() && !person.isCaller }?.let { id ->
        nameFor(id)?.takeIf { it.isNotBlank() }
            ?: participants.firstOrNull { it.userId == id }?.displayName?.takeIf { it.isNotBlank() }
    }
    return CallDetailParticipant(
        userId = person.userId,
        name = name,
        subLabel = when {
            person.isCaller -> STARTED_THE_CALL
            inviter != null -> "Added by $inviter"
            else -> null
        },
        badge = detailedBadge(person),
        // A missed call never connected: nobody has time on it, the caller
        // included, and "0s" there reads as if something happened.
        meta = if (missed) null else attendanceLine(person),
        isGuest = person.isGuest,
    )
}

/** `:296-312` — the server's `missed` verdict outranks a status still reading "ringing". */
private fun detailedBadge(person: CallLogParticipant): String? = when {
    person.isCaller -> HOST
    person.missed -> MISSED
    else -> statusBadge(person.status) ?: LEFT.takeIf { person.totalMillis > 0 }
}

/**
 * `:234-247`: "12m 4s · 2 joins · 1 leave". Counts appear only past one —
 * a single clean join and leave is every ordinary call and would be noise.
 */
private fun attendanceLine(person: CallLogParticipant): String {
    if (person.totalMillis <= 0 && person.joinCount <= 0) return NOT_JOINED
    val parts = buildList {
        formatMillis(person.totalMillis)?.let(::add)
        if (person.joinCount > 1) add("${person.joinCount} joins")
        if (person.leaveCount > 1) add("${person.leaveCount} leaves")
    }
    return parts.joinToString(" · ").ifBlank { NOT_JOINED }
}

/**
 * `:319-349`: the caller first (they started it), then everyone `call_users`
 * names, then — for a 1:1 row whose roster is missing or only carries the
 * caller — the other party from the row itself. A later entry never blanks a
 * status an earlier one gave.
 */
private fun CallLogEntry.legacyParticipants(
    selfUserId: String?,
    nameFor: (String) -> String?,
): List<CallDetailParticipant> {
    val callerId = callerUserId.takeIf { it.isNotBlank() }
    val statuses = linkedMapOf<String, String?>()
    callerId?.let { statuses[it] = null }
    callUsers.forEach { user ->
        if (user.userId.isBlank()) return@forEach
        statuses[user.userId] = user.status.takeIf { it.isNotBlank() } ?: statuses[user.userId]
    }
    if (mode != CallMode.Group) {
        calleeUserId.takeIf { it.isNotBlank() }?.let { statuses.putIfAbsent(it, null) }
    }
    return statuses.map { (userId, status) ->
        val isCaller = userId == callerId
        CallDetailParticipant(
            userId = userId,
            name = when {
                userId == selfUserId -> "You"
                else -> nameFor(userId)?.takeIf { it.isNotBlank() } ?: UNKNOWN_NAME
            },
            subLabel = STARTED_THE_CALL.takeIf { isCaller },
            badge = legacyBadge(status, isCaller),
            meta = null,
            isGuest = false,
        )
    }
}

/**
 * `:357-371`: the caller is always the host; everyone else wears their last
 * reported status, or — on rows the backend sent none for — what the row
 * itself proves: a missed call means nobody picked up, a duration means the
 * other side joined and later left.
 */
private fun CallLogEntry.legacyBadge(status: String?, isCaller: Boolean): String? = when {
    isCaller -> HOST
    else -> statusBadge(status) ?: when {
        missed -> MISSED
        durationMillis > 0 -> LEFT
        else -> null
    }
}

/** The status words both rosters share (`:304-309`, `:359-364`). */
private fun statusBadge(status: String?): String? = when (status?.trim()?.lowercase()) {
    "in_call", "incall" -> "In call"
    "left", "leave" -> LEFT
    "declined" -> "Declined"
    "missed" -> MISSED
    "ringing", "requested" -> "Ringing"
    else -> null
}

/**
 * `:282-294` — pinned to milliseconds by the field name (`total_ms`), unlike
 * the row's `call_duration`. Null for nothing; "0s" for under a second.
 */
fun formatMillis(totalMillis: Long): String? {
    if (totalMillis <= 0) return null
    val totalSeconds = totalMillis / MILLIS_PER_SECOND
    if (totalSeconds <= 0) return "0s"
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** The time chip — Android's `getTimeFormats("hh:mm a")` (`:121-125`): "03:45 PM". */
fun clock12h(atMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
    if (atMillis <= 0) return null
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val hour = at.hour % HOURS_ON_A_CLOCK
    val shown = if (hour == 0) HOURS_ON_A_CLOCK else hour
    val half = if (at.hour < HOURS_ON_A_CLOCK) "AM" else "PM"
    return "${shown.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')} $half"
}

/** "3 Participants", "1 Participant" — the roster's heading (`R.plurals.txt_participants_count`). */
fun participantsHeading(count: Int): String = if (count == 1) "1 Participant" else "$count Participants"

private const val HOST = "Host"
private const val LEFT = "Left"
private const val MISSED = "Missed"
private const val NOT_JOINED = "Not joined"
private const val STARTED_THE_CALL = "Started the call"
private const val UNKNOWN_NAME = "Unknown"
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val HOURS_ON_A_CLOCK = 12
