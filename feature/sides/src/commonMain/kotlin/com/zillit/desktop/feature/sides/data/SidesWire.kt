@file:Suppress("TooManyFunctions") // One parser per wire record, one builder per body.

package com.zillit.desktop.feature.sides.data

import com.zillit.desktop.feature.sides.domain.AutoPlan
import com.zillit.desktop.feature.sides.domain.CallSheetRef
import com.zillit.desktop.feature.sides.domain.ManualPlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.ScenePageDraft
import com.zillit.desktop.feature.sides.domain.ScheduleRef
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesPageRef
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Lenient readers ----------------------------------------------------------

internal fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

internal fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.longOrNull

internal fun JsonObject.int(vararg names: String): Int = long(*names)?.toInt() ?: 0

internal fun JsonObject.obj(vararg names: String): JsonObject? = firstOf(*names) as? JsonObject

internal fun JsonObject.array(vararg names: String): List<JsonElement> =
    (firstOf(*names) as? JsonArray)?.toList() ?: emptyList()

internal fun JsonElement?.objects(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

internal fun List<JsonElement>.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** A scene number list arrives as strings or as `{sceneNumber}` objects. */
internal fun List<JsonElement>.sceneNumbers(): List<String> = mapNotNull { element ->
    when (element) {
        is JsonPrimitive -> element.content
        is JsonObject -> element.text("sceneNumber", "scene_number")
        else -> null
    }?.takeIf { it.isNotBlank() }
}

// Parsers ------------------------------------------------------------------

internal fun parseAttachment(obj: JsonObject?): StoredAttachment {
    if (obj == null) return StoredAttachment()
    return StoredAttachment(
        media = obj.text("media"),
        name = obj.text("name"),
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        fileSizeBytes = obj.long("file_size", "fileSize") ?: 0,
        contentSubtype = obj.text("content_subtype", "contentSubtype").ifBlank { "pdf" },
    )
}

internal fun parseVersion(obj: JsonObject?): ScriptVersion? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return ScriptVersion(
        id = id,
        versionNumber = obj.int("versionNumber", "version_number"),
        versionLabel = obj.text("versionLabel", "version_label"),
        pageCount = obj.int("pageCount", "page_count"),
        createdAt = obj.text("createdAt", "created_at"),
        fileName = obj.obj("attachment")?.text("name").orEmpty(),
    )
}

internal fun parseScript(obj: JsonObject?): Script? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    // `currentVersion` is populated on most routes; a bare id string means
    // "has a version" without its details.
    val current = obj["currentVersion"]
    val version = parseVersion(current as? JsonObject)
        ?: (current as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let {
            ScriptVersion(id = it, versionNumber = 0, versionLabel = "", pageCount = 0, createdAt = "", fileName = "")
        }
    return Script(
        id = id,
        title = obj.text("title"),
        description = obj.text("description"),
        status = obj.text("status"),
        format = obj.text("format"),
        createdAt = obj.text("createdAt", "created_at"),
        updatedAt = obj.text("updatedAt", "updated_at"),
        currentVersion = version,
    )
}

/**
 * The pages GET answers backend field names (`scene_number`,
 * `page_colour_code`); the FE keys are read first in case a future response
 * carries them, exactly as the web maps them.
 */
internal fun parseScenePage(obj: JsonObject?): ScenePage? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return ScenePage(
        id = id,
        sceneNumber = obj.text("sceneNumber", "scene_number"),
        color = obj.text("color", "colour", "page_colour_code"),
        description = obj.text("description"),
        pageCount = obj.int("pageCount", "page_count"),
        attachment = parseAttachment(obj.obj("attachment")),
    )
}

internal fun parseCallSheet(obj: JsonObject?): CallSheetRef? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return CallSheetRef(
        id = id,
        title = obj.text("title"),
        source = obj.text("source"),
        scenes = obj.array("scenes").sceneNumbers(),
        attachment = parseAttachment(obj.obj("attachment")),
    )
}

internal fun parseSchedule(obj: JsonObject?): ScheduleRef? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    return ScheduleRef(
        id = id,
        title = obj.text("title"),
        source = obj.text("source"),
        totalDays = obj.int("totalDays", "total_days"),
        totalScenes = obj.int("totalScenes", "total_scenes"),
    )
}

internal fun parseSides(obj: JsonObject?): SidesRecord? {
    if (obj == null) return null
    val id = obj.text("_id", "id")
    if (id.isBlank()) return null
    val rawStatus = obj.text("status")
    val attachment = obj.obj("attachment")
    return SidesRecord(
        id = id,
        title = obj.text("title"),
        status = SidesStatus.fromWire(rawStatus),
        rawStatus = rawStatus,
        error = obj.text("error"),
        sceneNumbers = obj.array("sceneNumbers", "scene_numbers").sceneNumbers(),
        totalScenes = obj.int("totalScenes", "total_scenes"),
        scriptId = obj.obj("script")?.text("_id", "id") ?: (obj["script"] as? JsonPrimitive)?.content.orEmpty(),
        scriptTitle = obj.obj("script")?.text("title").orEmpty(),
        versionLabel = obj.obj("scriptVersion")?.text("versionLabel", "version_label").orEmpty(),
        versionNumber = obj.obj("scriptVersion")?.int("versionNumber", "version_number") ?: 0,
        callSheetTitle = obj.obj("callSheet")?.text("title").orEmpty(),
        generatedByName = obj.obj("generatedBy")?.text("name").orEmpty(),
        generatedById = obj.obj("generatedBy")?.text("_id", "id").orEmpty(),
        downloadCount = obj.int("downloadCount", "download_count"),
        createdAt = obj.text("createdAt", "created_at"),
        attachmentName = attachment?.text("name").orEmpty(),
        attachmentSize = attachment?.long("file_size", "fileSize") ?: 0,
        sceneFolders = obj.array("sceneFolders", "scene_folders").objects().map { folder ->
            SidesPageRef(
                sceneNumber = folder.text("sceneNumber", "scene_number"),
                color = folder.text("color", "colour", "page_colour_code"),
                sceneNumbers = folder.array("sceneNumbers", "scene_numbers").sceneNumbers(),
            )
        },
    )
}

/**
 * A scene list arrives as RAW JSON — `{scenes:[...]}`, a bare array, or
 * (for the page route) an envelope with `data.scenes`; snake and camel
 * spellings both occur. Unreadable bodies parse to nothing rather than an
 * error: the picker shows an empty list, the tool stays up.
 */
internal fun parseSceneBody(body: String): List<SceneInfo> {
    val element = runCatching { Json.parseToJsonElement(body) }.getOrNull()
    val root = element as? JsonObject
    val scenes = (root?.get("scenes") as? JsonArray)
        ?: (root?.obj("data")?.get("scenes") as? JsonArray)
        ?: (root?.get("data") as? JsonArray)
        ?: (element as? JsonArray)
    return scenes.objects().mapNotNull { obj ->
        SceneInfo(
            sceneNumber = obj.text("sceneNumber", "scene_number"),
            heading = obj.text("heading"),
            intExt = obj.text("intExt", "int_ext"),
            timeOfDay = obj.text("timeOfDay", "time_of_day"),
            pageStart = obj.int("pageStart", "page_start"),
            pageEnd = obj.int("pageEnd", "page_end"),
        ).takeIf { it.sceneNumber.isNotBlank() }
    }
}

// Bodies -------------------------------------------------------------------

/**
 * The attachment descriptor every upload carries — S3 first, then this as
 * JSON. `content_subtype` says `fdx` for a Final Draft file so the backend
 * converts it.
 */
internal fun StoredAttachment.toWire(): JsonObject = buildJsonObject {
    put("media", media)
    put("name", name)
    put("content_type", "document")
    put("content_subtype", contentSubtype)
    put("bucket", bucket)
    put("region", region)
    put("file_size", fileSizeBytes)
}

private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

/** The exact manual-mode generation body — the web's `handleSubmit` payload. */
internal fun generateWire(plan: ManualPlan): JsonObject = buildJsonObject {
    put("scriptId", plan.scriptId)
    if (plan.title.isNotBlank()) put("title", plan.title)
    put("mode", "manual")
    put(
        "versionScenes",
        buildJsonArray {
            plan.wireVersionScenes.forEach { group ->
                add(
                    buildJsonObject {
                        put("versionId", group.versionId)
                        put("sceneNumbers", strings(group.sceneNumbers))
                    },
                )
            }
        },
    )
    if (plan.pageSelections.isNotEmpty()) {
        put(
            "pageSelections",
            buildJsonArray {
                plan.pageSelections.forEach { selection ->
                    add(
                        buildJsonObject {
                            put("pageId", selection.pageId)
                            put("sceneNumbers", strings(selection.sceneNumbers))
                        },
                    )
                }
            },
        )
    }
    put("sceneDisplayMode", plan.displayMode)
    put("publish", false)
    if (plan.sceneOrder.isNotEmpty()) {
        put("orderedScenes", true)
        put("sceneOrder", strings(plan.sceneOrder))
    }
}

/**
 * The call-sheet-driven body — the web's `AutogenerateSidesModal
 * .handleGenerate`: scene numbers ride as ONE comma-joined string here,
 * the call sheet is always included and its scene pages never are.
 */
internal fun autoGenerateWire(plan: AutoPlan): JsonObject = buildJsonObject {
    put("scriptId", plan.scriptId)
    put("callSheetId", plan.callSheetId)
    if (plan.scheduleId.isNotBlank()) put("scheduleId", plan.scheduleId)
    put("sceneNumbers", plan.sceneNumbers.joinToString(", "))
    put("includeCallSheet", true)
    put("includeCallSheetScenes", false)
    put("orderedScenes", true)
    put("publish", false)
    put("sceneDisplayMode", plan.displayMode)
    if (plan.title.isNotBlank()) put("title", plan.title)
}

/** The page editor's body; `attachment` is omitted on an edit that keeps the file. */
internal fun scenePageWire(draft: ScenePageDraft): JsonObject = buildJsonObject {
    put("sceneNumber", draft.sceneNumber)
    put("colour", draft.color)
    put("description", draft.description)
    draft.attachment?.let { put("attachment", it.toWire()) }
}

/** Call sheets and schedules upload the same way: attachment, title, script. */
internal fun uploadDocWire(scriptId: String, title: String, attachment: StoredAttachment): JsonObject =
    buildJsonObject {
        put("attachment", attachment.toWire())
        put("title", title)
        put("scriptId", scriptId)
    }
