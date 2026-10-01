package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The Create / Edit Actor form's model, apart from the dialog so it can be
 * tested on its own (the web's `lib/actorForm.js`).
 *
 * The form holds the name as first + last, dates as epoch ms (0 = not set —
 * the service's own convention, and what it echoes back), and measurements
 * split into the ten standard ones plus any the costume team names themselves.
 */
internal val ActorMeasures = listOf("height", "chest", "bust", "waist", "hips", "inseam", "sleeve", "collar", "shoe", "head")

/** A user-named row: a measurement ("Thigh") or a talent-rep detail ("Assistant"). */
internal data class LabelValue(val label: String = "", val value: String = "")

internal data class ActorFormState(
    val first: String = "",
    val last: String = "",
    val gender: String = "",
    val age: String = "",
    val characterIds: List<String> = emptyList(),
    val notes: String = "",
    val nextFittingAt: Long = 0,
    val fittingComment: String = "",
    val phone: String = "",
    val phone2: String = "",
    val email: String = "",
    val email2: String = "",
    val startWorkDate: Long = 0,
    val agency: String = "",
    val talentRep: String = "",
    val talentRepEmail: String = "",
    val talentRepPhone: String = "",
    val talentRepDetails: List<LabelValue> = emptyList(),
    val measurements: Map<String, String> = emptyMap(),
    val extraMeasures: List<LabelValue> = emptyList(),
)

/** `(3) Priya` — the actor form and table's way of naming a character. */
internal fun charLabel(c: Rec): String =
    (if (c.has("cast_number")) "(${c.str("cast_number")}) " else "") + c.str("name")

/** An empty form; opened for one character, that character stays ticked. */
internal fun newActorForm(forCharacterId: String? = null): ActorFormState =
    ActorFormState(characterIds = listOfNotNull(forCharacterId?.takeIf { it.isNotBlank() }))

private fun Long.setOrZero(): Long = if (this > 0) this else 0

/** An actor from the service → the form. The first word is the first name. */
internal fun toActorForm(actor: Rec?): ActorFormState {
    if (actor == null) return ActorFormState()
    val words = actor.str("name").trim().split(Regex("\\s+"))
    val measurements = actor.rec("measurements")
    val measured = measurements?.keys.orEmpty().associateWith { measurements?.str(it).orEmpty() }
    return ActorFormState(
        first = words.firstOrNull().orEmpty(),
        last = words.drop(1).joinToString(" "),
        gender = actor.str("gender"),
        age = actor.str("age"),
        characterIds = actor.recs("characters").map { it.id },
        notes = actor.str("notes"),
        nextFittingAt = actor.long("next_fitting_at").setOrZero(),
        fittingComment = actor.str("fitting_comment"),
        phone = actor.str("phone"),
        phone2 = actor.str("phone2"),
        email = actor.str("email"),
        email2 = actor.str("email2"),
        startWorkDate = actor.long("start_work_date").setOrZero(),
        agency = actor.str("agency"),
        talentRep = actor.str("talent_rep"),
        talentRepEmail = actor.str("talent_rep_email"),
        talentRepPhone = actor.str("talent_rep_phone"),
        talentRepDetails = actor.recs("talent_rep_details").map { LabelValue(it.str("label"), it.str("value")) },
        measurements = measured.filterKeys { it in ActorMeasures },
        extraMeasures = measured.filterKeys { it !in ActorMeasures }.map { LabelValue(it.key, it.value) },
    )
}

/**
 * The form → the service's body. Empty text is sent as `""` rather than null,
 * which is how the service stores an unset string and echoes it back; a blank
 * measurement or detail row is dropped rather than saved as an empty value.
 */
internal fun toActorBody(f: ActorFormState): JsonObject {
    val age = f.age.trim().toDoubleOrNull()?.let { if (it % 1.0 == 0.0) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
    val measures = (f.measurements.map { it.key to it.value } + f.extraMeasures.map { it.label.trim() to it.value.trim() })
        .filter { (k, v) -> k.isNotEmpty() && v.trim().isNotEmpty() }
    return buildJsonObject {
        put("name", JsonPrimitive("${f.first.trim()} ${f.last.trim()}".trim()))
        put("gender", JsonPrimitive(f.gender))
        put("age", age ?: JsonNull)
        put("character_ids", JsonArray(f.characterIds.map(::JsonPrimitive)))
        put("notes", JsonPrimitive(f.notes))
        put("next_fitting_at", JsonPrimitive(f.nextFittingAt.setOrZero()))
        put("fitting_comment", JsonPrimitive(f.fittingComment))
        put("phone", JsonPrimitive(f.phone))
        put("phone2", JsonPrimitive(f.phone2))
        put("email", JsonPrimitive(f.email))
        put("email2", JsonPrimitive(f.email2))
        put("start_work_date", JsonPrimitive(f.startWorkDate.setOrZero()))
        put("agency", JsonPrimitive(f.agency))
        put("talent_rep", JsonPrimitive(f.talentRep))
        put("talent_rep_email", JsonPrimitive(f.talentRepEmail))
        put("talent_rep_phone", JsonPrimitive(f.talentRepPhone))
        put(
            "talent_rep_details",
            JsonArray(
                f.talentRepDetails.map { LabelValue(it.label.trim(), it.value.trim()) }.filter { it.label.isNotEmpty() }
                    .map { buildJsonObject { put("label", JsonPrimitive(it.label)); put("value", JsonPrimitive(it.value)) } },
            ),
        )
        put("measurements", buildJsonObject { measures.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
    }
}

/** What typing in a cast-number box means. */
internal sealed interface CastChange {
    /** The box still holds the current number: nothing to send. */
    data object Unchanged : CastChange

    /** Send this number; null clears it. */
    data class To(val number: Long?) : CastChange

    /** Not a whole number of 0 or more: refused before any write. */
    data object Invalid : CastChange
}

/** A cast number typed on the list (0 is a valid number, as in the reference). */
internal fun typedCastNumber(raw: String, current: Long?): CastChange {
    val v = raw.trim()
    val next: Long? = if (v.isEmpty()) null else v.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it >= 0 }?.toLong() ?: return CastChange.Invalid
    return if (next == current) CastChange.Unchanged else CastChange.To(next)
}

// -- ms <-> the form's date and time boxes --------------------------------

/** `YYYY-MM-DD` of epoch ms in [zone], blank when unset. */
internal fun actorDateText(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    if (ms <= 0) "" else Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date.toString()

/** `HH:mm` of epoch ms in [zone], blank when unset. */
internal fun actorTimeText(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (ms <= 0) return ""
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** Epoch ms of a `YYYY-MM-DD` date plus an optional `HH:mm`; 0 when the date is blank or unreadable. */
internal fun actorDateMs(date: String, time: String = "", zone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: return 0
    val parts = time.trim().split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 }
    val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    if (hour == null) return day.atStartOfDayIn(zone).toEpochMilliseconds()
    return LocalDateTime(day.year, day.monthNumber, day.dayOfMonth, hour, minute).toInstant(zone).toEpochMilliseconds()
}
