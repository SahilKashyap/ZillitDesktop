@file:Suppress("TooManyFunctions") // One reader per record the service sends, plus the lenient field readers.

package com.zillit.desktop.feature.boxschedule.data

import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdf
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.DiaryRevision
import com.zillit.desktop.feature.boxschedule.domain.GuestEmail
import com.zillit.desktop.feature.boxschedule.domain.HistoryEntry
import com.zillit.desktop.feature.boxschedule.domain.HistorySnapshot
import com.zillit.desktop.feature.boxschedule.domain.PresetMember
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

// Readers ----------------------------------------------------------------

internal fun parseType(obj: JsonObject?): ScheduleType? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return ScheduleType(
        id = id,
        title = obj.text("title"),
        color = obj.text("color"),
        systemDefined = obj.bool("systemDefined"),
    )
}

internal fun parseBlock(obj: JsonObject?): ScheduleBlock? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val typeField = obj["typeId"]
    val typeId = (typeField as? JsonObject)?.text("_id", "id") ?: (typeField as? JsonPrimitive)?.content.orEmpty()
    return ScheduleBlock(
        id = id,
        title = obj.text("title"),
        typeId = typeId,
        typeName = obj.text("typeName").ifBlank { (typeField as? JsonObject)?.text("title").orEmpty() },
        color = obj.text("color").ifBlank { (typeField as? JsonObject)?.text("color").orEmpty() },
        calendarDays = (obj["calendarDays"] as? JsonArray).items().mapNotNull { it.epochMs() },
        startDate = obj.long("startDate") ?: 0,
        endDate = obj.long("endDate") ?: 0,
        numberOfDays = obj.count("numberOfDays") ?: 0,
        dateRangeType = obj.text("dateRangeType"),
        createdAt = obj.stamp("createdAt", "created_at"),
        version = obj.count("version") ?: 0,
    )
}

internal fun parseEvent(obj: JsonObject?): DiaryEvent? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val kind = DiaryKind.fromWire(obj.text("eventType"))
    return DiaryEvent(
        id = id,
        kind = kind,
        title = obj.text("title"),
        body = if (kind == DiaryKind.Note) obj.text("notes", "description") else obj.text("description", "notes"),
        date = obj.long("date") ?: 0,
        startDateTime = obj.long("startDateTime") ?: 0,
        endDateTime = obj.long("endDateTime") ?: 0,
        fullDay = obj.bool("fullDay"),
        location = obj.text("location"),
        locationLat = obj.double("locationLat"),
        locationLng = obj.double("locationLng"),
        color = obj.text("color"),
        scheduleDayId = obj.idOf("scheduleDayId"),
        noteType = obj.text("noteType"),
        repeatStatus = obj.text("repeatStatus"),
        occurrenceId = obj.text("occurrenceId"),
        masterEventId = obj.text("masterEventId"),
        isRecurringInstance = obj.bool("isRecurringInstance"),
        calendarEventId = obj.text("calendarEventId"),
        callType = obj.text("callType"),
        cncCallGroupId = obj.text("cncCallGroupId"),
        reminder = obj.text("reminder"),
        timezone = obj.text("timezone"),
        textColor = obj.text("textColor"),
        repeatEndDate = obj.long("repeatEndDate") ?: 0,
        audience = obj.audience(),
        externalEmails = (obj["externalEmails"] as? JsonArray).items().mapNotNull { it.guest() },
        organizerExcluded = obj.bool("organizerExcluded"),
        createdByName = (obj["createdBy"] as? JsonObject)?.personName().orEmpty(),
        createdAt = obj.stamp("createdAt", "created_at"),
    )
}

internal fun parseConflicts(data: JsonElement?): List<DateConflict> {
    val holder = data as? JsonObject ?: return emptyList()
    return (holder["conflicts"] as? JsonArray).items().mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        DateConflict(
            date = obj.long("date") ?: return@mapNotNull null,
            existingType = obj.text("existingType"),
            existingColor = obj.text("existingColor"),
            existingTitle = obj.text("existingTitle"),
        )
    }
}

/** `data.logs[]` of `GET /activity-log`. */
internal fun parseHistory(data: JsonElement?): List<HistoryEntry> {
    val rows = ((data as? JsonObject)?.get("logs") as? JsonArray) ?: (data as? JsonArray)
    return rows.items().mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val performer = obj["performedBy"]
        HistoryEntry(
            id = obj.text("_id", "id").ifBlank { return@mapNotNull null },
            action = obj.text("action"),
            targetType = obj.text("targetType"),
            targetId = obj.idOf("targetId"),
            targetTitle = obj.text("targetTitle"),
            details = obj.text("details"),
            performedById = when (performer) {
                is JsonObject -> performer.text("userId", "_id", "user_id", "id")
                is JsonPrimitive -> performer.content
                else -> ""
            },
            performedByName = (performer as? JsonObject)?.personName().orEmpty(),
            createdAt = obj.stamp("createdAt", "created_at"),
        )
    }
}

/** `data[]` of `GET /revisions`. */
internal fun parseRevisions(data: JsonElement?): List<DiaryRevision> =
    ((data as? JsonArray) ?: ((data as? JsonObject)?.get("revisions") as? JsonArray)).items().mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val targetId = obj.idOf("targetId").ifBlank { return@mapNotNull null }
        DiaryRevision(
            targetId = targetId,
            createdAt = obj.stamp("createdAt", "created_at"),
            number = obj.count("revisionNumber") ?: 0,
            snapshot = parseSnapshot(obj).overlaidWith((obj["snapshot"] as? JsonObject)?.let(::parseSnapshot)),
        )
    }

/** Whatever of a record's fields a history row or its snapshot carries. */
internal fun parseSnapshot(obj: JsonObject): HistorySnapshot = HistorySnapshot(
    title = obj.text("title", "name"),
    eventType = obj.text("eventType"),
    description = obj.text("description", "notes"),
    start = obj.long("startDateTime", "start", "startsAt", "date") ?: 0,
    end = obj.long("endDateTime", "end", "endsAt") ?: 0,
    fullDay = obj.bool("fullDay"),
    timezone = obj.text("timezone"),
    location = obj.text("location", "address"),
    reminder = obj.text("reminder"),
    repeatStatus = obj.text("repeatStatus"),
    repeatEndDate = obj.long("repeatEndDate") ?: 0,
    callType = obj.text("callType"),
    color = obj.text("color"),
    audience = obj.audience(),
    externalEmails = (obj["externalEmails"] as? JsonArray).items().mapNotNull { it.guest()?.mail },
    calendarDays = (obj["calendarDays"] as? JsonArray).items().mapNotNull { it.epochMs() },
    organizerExcluded = obj.bool("organizerExcluded"),
    scheduleDayId = obj.idOf("scheduleDayId"),
    typeId = obj.idOf("typeId"),
)

/** `data[]` of `GET /user-preset` — the web's `mapPreset`. */
internal fun parsePresets(data: JsonElement?): List<UserPreset> = (data as? JsonArray).items().mapNotNull { row ->
    val obj = row as? JsonObject ?: return@mapNotNull null
    val id = obj.text("_id", "id").ifBlank { return@mapNotNull null }
    UserPreset(
        id = id,
        name = obj.text("preset_name", "name"),
        members = (obj["users"] as? JsonArray).items().mapNotNull { member ->
            val person = member as? JsonObject ?: return@mapNotNull null
            PresetMember(
                id = person.text("user_id", "_id").ifBlank { return@mapNotNull null },
                fullName = person.personName(),
                designation = person.text("designation", "designation_name").unwrapLabelKey(),
            )
        },
    )
}

/** The staged file off the attachment shape, or null when the answer names no storage key. */
internal fun parseDiaryPdf(obj: JsonObject?): DiaryPdf? {
    val media = obj?.text("media")?.takeIf { it.isNotBlank() } ?: return null
    return DiaryPdf(
        media = media,
        name = obj.text("name").ifBlank { DEFAULT_PDF_NAME },
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        contentType = obj.text("content_type"),
        contentSubtype = obj.text("content_subtype"),
        thumbnail = obj.text("thumbnail"),
        caption = obj.text("caption"),
        fileSizeBytes = obj.text("file_size").toDoubleOrNull()?.toLong() ?: 0L,
    )
}

// Writers ----------------------------------------------------------------

/**
 * The event/note body — the web's `buildEventPayload` and note payload,
 * pinned by test.
 *
 *  - `scheduleDayId` is OMITTED, never null, when unlinked;
 *  - the four Distribute-To keys travel on every write, arrays never null;
 *  - a Personal Note distributes to nobody;
 *  - `createEventInCalendar` goes out only on create, and only once asked.
 */
internal fun eventWire(draft: DiaryDraft, create: Boolean): JsonObject = buildJsonObject {
    if (draft.scheduleDayId.isNotBlank()) put("scheduleDayId", draft.scheduleDayId)
    put("date", draft.date)
    put("eventType", draft.kind.wire)
    put("title", draft.title.trim())
    put("color", draft.color)
    if (draft.kind == DiaryKind.Note) {
        put("notes", draft.body)
        put("noteType", draft.noteType)
    } else {
        put("description", draft.body)
        put("startDateTime", draft.startDateTime)
        put("endDateTime", draft.endDateTime)
        put("fullDay", draft.fullDay)
        put("location", draft.location)
        // Beside the text, as the web sends them (`CreateEventModal.jsx:540`):
        // null when the place was typed rather than picked, never omitted.
        put("locationLat", draft.locationLat)
        put("locationLng", draft.locationLng)
        put("reminder", draft.reminder.ifBlank { "none" })
        put("repeatStatus", draft.repeatStatus)
        put("repeatEndDate", draft.repeatEndDate)
        if (draft.timezone.isNotBlank()) put("timezone", draft.timezone)
        // What the event already had, not a blank: the write sends the whole
        // event, so hardcoding "" here erased a call set on another client.
        put("callType", draft.callType)
        put("textColor", draft.textColor)
        put(
            "externalEmails",
            buildJsonArray {
                draft.externalEmails.forEach { guest ->
                    add(buildJsonObject { put("_id", guest.id); put("mail", guest.mail) })
                }
            },
        )
        put("organizerExcluded", draft.organizerExcluded)
        put("advancedEnabled", true)
        if (create) draft.createInCalendar?.let { put("createEventInCalendar", it) }
    }
    audienceWire(draft.audience)
}

private fun JsonObjectBuilder.audienceWire(audience: DiaryAudience) {
    put("distributeTo", audience.mode.wire)
    put("distributeUserIds", buildJsonArray { audience.userIds.forEach { add(JsonPrimitive(it)) } })
    put("distributeDepartmentIds", buildJsonArray { audience.departmentIds.forEach { add(JsonPrimitive(it)) } })
    put("userPresetId", audience.presetId?.takeIf { audience.mode == AudienceMode.Presets && it.isNotBlank() })
}

/**
 * The `/pdf` query, as the web sends it: `watermark`, `action` and
 * `format` always, and `includePersonalNotes=false` only when the user
 * chose Without. Omitting it is the server's include-everything default, so
 * the default request is byte-identical to the one sent before the option
 * existed (box-schedule-personal-notes.md §2.1).
 */
internal fun diaryPdfQuery(options: DiaryPdfOptions, action: DiaryPdfAction, watermark: String): Map<String, Any?> =
    buildMap {
        put("watermark", watermark)
        put("action", action.wire)
        put("format", options.layout.wire)
        if (!options.includePersonalNotes) put("includePersonalNotes", "false")
    }

// Lenient readers --------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

/** An instant: epoch milliseconds, with seconds scaled up. Never a count — see [count]. */
private fun JsonObject.long(vararg names: String): Long? = firstOf(*names)?.epochMs()

/** A plain number — a day count, a version — read as sent; the seconds rule would make 3 into 3000. */
private fun JsonObject.count(vararg names: String): Int? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.toInt()

/** Coordinates arrive as numbers or numeric strings, like every other value here. */
private fun JsonObject.double(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonObject.bool(vararg names: String): Boolean =
    (firstOf(*names) as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

/** An id sent bare or as a populated document. */
private fun JsonObject.idOf(name: String): String = when (val field = this[name]) {
    is JsonObject -> field.text("_id", "id")
    is JsonPrimitive -> if (field is JsonNull) "" else field.content
    else -> ""
}

private fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray).items().mapNotNull { item ->
    when (item) {
        is JsonObject -> item.text("_id", "id", "user_id").takeIf { it.isNotBlank() }
        is JsonPrimitive -> item.content.takeIf { item !is JsonNull && it.isNotBlank() }
        else -> null
    }
}

private fun JsonObject.audience(): DiaryAudience = DiaryAudience(
    mode = AudienceMode.fromWire(text("distributeTo")),
    userIds = strings("distributeUserIds"),
    departmentIds = strings("distributeDepartmentIds"),
    presetId = idOf("userPresetId").takeIf { it.isNotBlank() },
)

private fun JsonElement.guest(): GuestEmail? = when (this) {
    is JsonObject -> text("mail", "email").takeIf { it.isNotBlank() }?.let {
        GuestEmail(text("_id", "id"), it.lowercase())
    }
    is JsonPrimitive -> content.takeIf { this !is JsonNull && it.isNotBlank() }?.let { GuestEmail("", it.lowercase()) }
    else -> null
}

/** The web's `pickName`: full name, name, first + last, else the email. */
private fun JsonObject.personName(): String =
    text("full_name", "fullName", "name").ifBlank {
        listOf(text("first_name"), text("last_name")).filter { it.isNotBlank() }.joinToString(" ")
    }.ifBlank { text("email") }

/**
 * A timestamp in any of the spellings this service has used: epoch ms, epoch
 * seconds, a numeric string, or ISO-8601.
 */
private fun JsonObject.stamp(vararg names: String): Long {
    val primitive = firstOf(*names) as? JsonPrimitive ?: return 0
    primitive.epochMs()?.let { return it }
    return runCatching { Instant.parse(primitive.content).toEpochMilliseconds() }.getOrDefault(0)
}

/**
 * The web's `parseEpochMs`: numbers or numeric strings; values at or below
 * 1e12 are SECONDS and are scaled up.
 */
private fun JsonElement.epochMs(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val value = primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: return null
    return if (value in 1..SECONDS_CEILING) value * MS_PER_SECOND else value
}

internal fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()

/** `{designation:driver_label}` → `driver_label` — the web's `resolveLabelKey`. */
internal fun String.unwrapLabelKey(): String =
    Regex("""^\{[^:]+:([^}]+)}$""").matchEntire(trim())?.groupValues?.get(1) ?: this

private const val SECONDS_CEILING = 1_000_000_000_000L
private const val MS_PER_SECOND = 1_000L
private const val DEFAULT_PDF_NAME = "Box Schedule.pdf"
