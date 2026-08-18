package com.zillit.desktop.feature.pagedistribution.data

import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.FolderKey
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The upload body — the web's `changeHandler`, branch for branch.
 *
 * Three shapes, decided by the tab and by whether this replaces:
 *  - a single-list create carries the tool's date key (epoch ms, or the
 *    EMPTY STRING when no date — never null, never 0) and its name key, and
 *    OMITS scene / page number / colour;
 *  - a single-list replace adds the parent pointer and drops the name;
 *  - a folder create carries scene / page number / colour, `revision_date`
 *    at local midnight TODAY (+5 s for one-line schedule pages — that is
 *    how the server keeps the two kinds in separate folders), and the
 *    picked date as `user_selected_date` only when there is one; D.O.D
 *    carries `name` and `user_selected_date: 0` for none.
 *
 * `undefined` on the web means the key is absent, and the port omits the
 * same keys rather than sending null.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per web branch — the contract, spelled out.
internal fun uploadWire(
    tool: DistributionTool,
    tab: DistributionTab,
    draft: UploadDraft,
    stored: StoredPdf,
    uniqueId: String,
    nowMs: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): JsonObject = buildJsonObject {
    val kind = tab.kind
    val pickedDate = dayMillis(draft.dateYmd, zone)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    val todayMidnight = today.atStartOfDayIn(zone).toEpochMilliseconds()
    val replacing = draft.replaces != null

    when (kind) {
        is TabKind.Single -> {
            put("episode", draft.episode)
            put("attachment", attachmentWire(stored))
            put("unique_id", uniqueId)
            if (tool.sendsScheduleType) put("schedule_type", "")
            // `scriptDateEpoch || ''` — the empty string stands in for "no date".
            if (pickedDate > 0) put(kind.dateKey, pickedDate) else put(kind.dateKey, "")
            if (replacing) {
                put(kind.parentKey, draft.replaces!!)
            } else {
                put(kind.nameKey, draft.name)
            }
        }
        is TabKind.Folders -> {
            put(tool.pageNumberKey, draft.pageNumber)
            put("scene_number", draft.sceneNumber)
            put("episode", draft.episode)
            put("page_colour_code", draft.colour.hex)
            put("attachment", attachmentWire(stored))
            put("unique_id", uniqueId)
            if (kind.folderKey == FolderKey.Name) {
                // D.O.D: the folder is a typed name; a typed (not picked)
                // name is title-cased word by word, as the web does.
                put("name", if (draft.nameFromPick) draft.name else titleCaseWords(draft.name))
                put("user_selected_date", if (pickedDate > 0) pickedDate else 0L)
                put("revision_date", todayMidnight)
            } else if (replacing) {
                put("parent_page_id", draft.replaces!!)
                if (tool.sendsScheduleType) put("schedule_type", draft.scheduleType?.wire.orEmpty())
            } else {
                if (tool.sendsScheduleType) put("schedule_type", draft.scheduleType?.wire.orEmpty())
                if (pickedDate > 0) put("user_selected_date", pickedDate)
                val shift = if (draft.scheduleType == ScheduleType.OneLinePages) ONE_LINE_SHIFT_MS else 0L
                put("revision_date", todayMidnight + shift)
            }
        }
    }
}

/** The `attachment` object — the same twelve keys the web sends. */
internal fun attachmentWire(stored: StoredPdf): JsonObject = buildJsonObject {
    put("media", stored.media)
    put("thumbnail", stored.thumbnail)
    put("name", stored.name)
    put("content_type", "document")
    put("content_subtype", stored.contentSubtype.ifBlank { "pdf" })
    put("caption", "document")
    put("height", 0)
    put("width", 0)
    put("duration", 0)
    put("bucket", stored.bucket)
    put("region", stored.region)
    put("file_size", stored.fileSize)
}

/**
 * The Document Distribution `documents/from-tool` body. The leaf is the
 * episode when set, else the scene number; `folder_date` is `YYYY-MM-DD`,
 * the one date on this wire that is a string.
 */
internal fun publishWire(
    tool: DistributionTool,
    tab: DistributionTab,
    document: DistDocument,
    todayYmd: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): JsonObject = buildJsonObject {
    val attachment = document.attachment
    val rawName = attachment?.name?.ifBlank { null } ?: document.originalName.ifBlank { "${tool.publishRoot}.pdf" }
    val originalName = if (Regex("""\.[^./\\]+$""").containsMatchIn(rawName)) rawName else "$rawName.pdf"
    put("original_name", originalName)
    put("media", attachment?.media.orEmpty())
    put("media_type", "document")
    val leaf = document.episode.trim().ifEmpty { document.sceneNumber.trim() }
    put(
        "folder_path",
        kotlinx.serialization.json.buildJsonArray {
            listOfNotNull(tool.publishRoot, tab.publishSubFolder, leaf.ifEmpty { null })
                .forEach { add(JsonPrimitive(it)) }
        },
    )
    val dateMs = document.dateMs.takeIf { it > 0 } ?: document.revisionDateMs
    put("folder_date", if (dateMs > 0) ymd(dateMs, zone) else todayYmd)
    attachment?.bucket?.takeIf { it.isNotBlank() }?.let { put("bucket", it) }
    attachment?.region?.takeIf { it.isNotBlank() }?.let { put("region", it) }
    put("content_type", "document")
    put("content_subtype", attachment?.contentSubtype?.ifBlank { null } ?: "pdf")
    attachment?.thumbnail?.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
    attachment?.fileSize?.takeIf { it.isNotBlank() }?.let { put("file_size", it) }
}

/** `capitalizeWordsForUnits`: each space-separated word, first letter up, rest down. */
internal fun titleCaseWords(raw: String): String =
    raw.trim().split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) word else word.first().uppercaseChar() + word.drop(1).lowercase()
    }

internal fun dayMillis(ymd: String, zone: TimeZone): Long {
    val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").matchEntire(ymd.trim()) ?: return 0L
    return runCatching {
        LocalDate(m.groupValues[YEAR].toInt(), m.groupValues[MONTH].toInt(), m.groupValues[DAY].toInt())
            .atStartOfDayIn(zone).toEpochMilliseconds()
    }.getOrDefault(0L)
}

internal fun ymd(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (epochMs <= 0) return ""
    val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
    return "${d.year}-${d.monthNumber.toString().padStart(PAD, '0')}-${d.dayOfMonth.toString().padStart(PAD, '0')}"
}

private const val PAD = 2
private const val YEAR = 1
private const val MONTH = 2
private const val DAY = 3

private const val ONE_LINE_SHIFT_MS = 5_000L

// Parsers ------------------------------------------------------------------

internal fun parseDocument(obj: JsonObject?): DistDocument? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return DistDocument(
        id = id,
        createdMs = obj.long("created") ?: 0L,
        createdBy = obj.text("created_by", "user_id"),
        episode = obj.text("episode"),
        sceneNumber = obj.text("scene_number"),
        pageNumber = obj.text("schedule_page_number", "script_page_number"),
        colour = obj.text("page_colour_code"),
        dateMs = obj.long("schedule_date", "script_date") ?: 0L,
        revisionDateMs = obj.long("revision_date") ?: 0L,
        userSelectedDateMs = obj.long("user_selected_date") ?: 0L,
        scheduleType = ScheduleType.fromWire(obj.text("schedule_type")),
        name = obj.text("name", "schedule_name", "script_name"),
        originalName = obj.text("original_name"),
        deleted = obj.flag("deleted"),
        replaced = obj.flag("replaced"),
        attachment = parseAttachment(obj["attachment"] as? JsonObject),
    )
}

internal fun parseFolder(obj: JsonObject?, key: FolderKey): DistFolder? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    val folderKey = when (key) {
        FolderKey.SceneNumber -> obj.text("scene_number")
        FolderKey.Name -> obj.text("name")
    }
    if (id.isBlank() && folderKey.isBlank()) return null
    return DistFolder(
        id = id.ifBlank { folderKey },
        key = folderKey,
        createdMs = obj.long("created") ?: 0L,
        revisionDateMs = obj.long("revision_date") ?: 0L,
        scheduleType = ScheduleType.fromWire(obj.text("schedule_type")),
        colour = obj.text("page_colour_code"),
        deleted = obj.flag("deleted"),
    )
}

internal fun parseAttachment(obj: JsonObject?): StoredPdf? {
    if (obj == null) return null
    val media = obj.text("media").takeIf { it.isNotBlank() } ?: return null
    return StoredPdf(
        media = media,
        name = obj.text("name"),
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        fileSize = obj.text("file_size"),
        thumbnail = obj.text("thumbnail").ifBlank { media },
        contentSubtype = obj.text("content_subtype").ifBlank { "pdf" },
    )
}

internal fun parseCount(obj: JsonObject?): CountRow? {
    if (obj == null) return null
    val userId = obj.text("user_id").takeIf { it.isNotBlank() } ?: return null
    return CountRow(userId, obj.int("view_count") ?: 0, obj.int("download_count") ?: 0)
}

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.int(vararg names: String): Int? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

/** `deleted` / `replaced` are numbers (0/1) on the wire; read booleans and strings too. */
private fun JsonObject.flag(name: String): Boolean =
    (firstOf(name) as? JsonPrimitive)?.let { p ->
        p.content.equals("true", ignoreCase = true) || (p.longOrNull ?: 0L) != 0L
    } ?: false
