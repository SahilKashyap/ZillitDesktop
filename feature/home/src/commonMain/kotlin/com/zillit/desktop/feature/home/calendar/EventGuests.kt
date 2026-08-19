package com.zillit.desktop.feature.home.calendar

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Everyone on an event, and the body that carries them.
 *
 * Split from the repository because the guest list wears three spellings
 * between the two clients that write it and the one that reads it back, and
 * the reconciling is worth reading on its own.
 */

/**
 * The event, as this server wants it.
 *
 * Both invitee spellings travel: `invited_users` is the web's and `invitees`
 * iOS's, and this server requires the second even when nobody is invited
 * ("invitees" is required — found live).
 */
internal fun eventBody(draft: EventDraft, times: EventTimes, zone: TimeZone): JsonObject =
    buildJsonObject {
        put("title", draft.title.trim())
        // Edits carry their scope — iOS's `edit_type`. "all" is
        // the whole event; the 406 without it was found live when
        // the first drag-reschedule hit the edit route.
        if (draft.isEdit) put("edit_type", "all")
        // 0 = a members event, 1 = personal — iOS's `EventType`.
        // Chosen on the form rather than derived: the server
        // refuses a members event with nobody on it
        // (`calendar_members_event_needs_invitee` — found live),
        // which the form now checks before it gets here.
        put("type", draft.audience.wireValue)
        // A personal event has nobody to call, and the web sends
        // the string "none" rather than omitting the field.
        put(
            "call_type",
            if (draft.isForMembers) draft.callType?.wireValue ?: NO_CALL else NO_CALL,
        )
        // The web's `createUser_exclude`: a meeting arranged by
        // someone who is not themselves attending it.
        put("createUser_exclude", draft.excludeOrganiser)
        put("start_datetime", times.startMillis)
        put("end_datetime", times.endMillis)
        put("dayStartDate", times.startMillis)
        put("full_day", draft.isAllDay)
        put("description", draft.description.trim())
        put("location_description", draft.location.trim())
        put("notify", draft.reminderMinutes)
        // The web sends white when the user picked nothing.
        put("color", draft.colorHex.ifBlank { "#ffffff" })
        // The zone the typed times were meant in — the web always
        // sends one, falling back to the device's.
        put("timezone", draft.effectiveZone(zone).id)
        put("recurrence_rule", draft.recurrence.toPayload(zone))
        // The reminder flag and its minutes are separate fields;
        // sending minutes without the flag leaves it switched off.
        put("reminder_status", draft.reminderMinutes > 0)
        put(
            "invited_users",
            buildJsonArray {
                draft.crewInvitees().forEach { userId ->
                    add(buildJsonObject { put("user_id", userId) })
                }
            },
        )
        // iOS's spelling, and this server requires the key even
        // when nobody is invited ("invitees" is required — found
        // live). invited_users above is the web's; both travel.
        put(
            "invitees",
            buildJsonArray {
                draft.crewInvitees().forEach { userId ->
                    add(
                        buildJsonObject {
                            put("user_id", userId)
                            put("type", "project_user")
                        },
                    )
                }
                // Outside guests travel in the same array under
                // their address, as the web's payload builder
                // appends them.
                draft.outsideGuests().forEach { email ->
                    add(
                        buildJsonObject {
                            put("email", email)
                            put("type", EXTERNAL_GUEST)
                        },
                    )
                }
            },
        )
    }

/**
 * Members or personal.
 *
 * Rows written before the field existed carry no `type`; an event with guests
 * on it was a members event, and one without was somebody's own note. Guessing
 * beats defaulting here — defaulting every old row to "members" would make the
 * form demand invitees for a note the user only opened to retitle.
 */
internal fun JsonObject.readAudience(): EventAudience {
    val declared = prim("type")?.let { it.longOrNull?.toInt() ?: it.content.toIntOrNull() }
    if (declared != null) return EventAudience.of(declared)
    return if (inviteeIds().isEmpty() && externalEmails().isEmpty()) {
        EventAudience.Personal
    } else {
        EventAudience.Members
    }
}

/**
 * Everyone on the event, under either spelling.
 *
 * `invitees` is iOS's key and `invited_users` the web's; a row may carry one or
 * both, and the same person appearing in each is deduplicated by the set and
 * the `distinct` below.
 */
private fun JsonObject.guestRows(): List<JsonObject> =
    ((this["invitees"] as? JsonArray).orEmpty() + (this["invited_users"] as? JsonArray).orEmpty())
        .filterIsInstance<JsonObject>()

internal fun JsonObject.inviteeIds(): Set<String> =
    guestRows()
        .filter { it.str("type") != EXTERNAL_GUEST }
        .mapNotNull { it.str("user_id") ?: it.str("userId") ?: it.str("_id") }
        .toSet()

internal fun JsonObject.externalEmails(): List<String> =
    guestRows()
        .filter { it.str("type") == EXTERNAL_GUEST }
        .mapNotNull { it.str("email") }
        .distinct()

/**
 * Nobody is invited to a personal event.
 *
 * The form hides the invitee list once "Personal" is chosen, but a draft
 * switched over after people were ticked still holds them — and sending them
 * would circulate an event the user just marked private.
 */
internal fun EventDraft.crewInvitees(): Set<String> =
    if (isForMembers) inviteeIds else emptySet()

internal fun EventDraft.outsideGuests(): List<String> =
    if (isForMembers) externalEmails else emptyList()

/** The web's marker for someone invited by address rather than crew record. */
private const val EXTERNAL_GUEST = "external"

/** The web's `call_type` for an event nobody dials into. */
private const val NO_CALL = "none"
