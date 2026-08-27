package com.zillit.desktop.feature.location.data

import kotlinx.serialization.json.contentOrNull
import com.zillit.desktop.feature.location.domain.LocationMessage
import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.MediaAttachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Bodies -------------------------------------------------------------------

/** The web's `createModal(value, 'create')`. */
internal fun createWire(
    draft: LocationDraft,
    attachment: MediaAttachment?,
    linkPreview: MediaAttachment?,
    uniqueId: String,
): JsonObject = buildJsonObject {
    putBase(draft)
    put("unique_id", uniqueId)
    if (draft.episodeList.isNotEmpty()) {
        // Create sends the episodes as an ARRAY; edit sends the string.
        put("episode", buildJsonArray { draft.episodeList.forEach { add(JsonPrimitive(it)) } })
    }
    when {
        attachment != null -> put("attachment", attachmentWire(attachment))
        linkPreview != null -> put("link_attachment", attachmentWire(linkPreview))
        // A folder-only record (no media, allowed on `selected`) omits both.
        else -> Unit
    }
}

/** `createModal(value, 'edit')` — `locationIds`, no `unique_id`, no `attachment`, episode as typed. */
internal fun editWire(draft: LocationDraft, id: String): JsonObject = buildJsonObject {
    putBase(draft)
    if (draft.episodes.isNotBlank()) put("episode", draft.episodes.trim())
    put("locationIds", buildJsonArray { add(JsonPrimitive(id)) })
}

/**
 * `createModal(value, 'moveByImage')`: the record's own metadata, the OLD
 * folder as `file_name`/`scene_number`, the same folder as `new_*` (a move
 * between shortlists keeps the folder), `ids`, and `generatepdf:false`.
 * The name is NOT title-cased here — the web passes it raw.
 */
internal fun moveWire(ids: List<String>, sample: LocationMedia, to: LocationStatus): JsonObject = buildJsonObject {
    put("scene_number", sample.sceneNumber)
    put("city", sample.city)
    put("description", sample.description)
    put("status", to.wire)
    put("file_name", sample.location)
    put("country_code", sample.countryCode)
    put("phone", sample.phone)
    put("contact_name", sample.contactName)
    put("email", sample.email)
    put("address", sample.address)
    put("generatepdf", false)
    put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    put("new_scene_number", sample.sceneNumber)
    put("new_file_name", sample.location)
    sample.episodes.singleOrNull()?.let { ep ->
        put("new_episode", ep)
        put("episode", ep)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putBase(draft: LocationDraft) {
    put("scene_number", Folders.normaliseScene(draft.sceneNumber))
    put("city", draft.city.trim())
    put("description", draft.description.trim())
    put("status", draft.status.wire)
    put("file_name", titleCaseWords(draft.location))
    put("link", draft.link.trim())
    put("country_code", draft.countryCode.trim())
    put("phone", draft.phone.trim())
    put("contact_name", draft.contactName.trim())
    put("email", draft.email.trim())
    put("address", draft.address.trim())
}

internal fun attachmentWire(a: MediaAttachment): JsonObject = buildJsonObject {
    put("media", a.media)
    put("thumbnail", a.thumbnail)
    put("content_type", a.contentType)
    put("content_subtype", a.contentSubtype)
    put("caption", "")
    put("height", 0)
    put("width", 0)
    put("duration", 0)
    put("bucket", a.bucket)
    put("region", a.region)
    put("name", a.name.trim())
    put("file_size", a.fileSize)
}

/** `spaceCapitalizeWords`: the first letter of each word up. */
internal fun titleCaseWords(raw: String): String =
    raw.trim().split(' ').joinToString(" ") { w -> if (w.isEmpty()) w else w.first().uppercaseChar() + w.drop(1) }

// Parsers ------------------------------------------------------------------

internal fun parseInfo(obj: JsonObject?): LocationInfo? {
    if (obj == null) return null
    return LocationInfo(
        location = obj.text("location", "file_name"),
        sceneNumbers = obj.strings("sceneNumbers", "scene_number"),
        episodes = obj.strings("episode"),
        cities = obj.strings("city"),
        lastUpdateMs = obj.long("lastUpdate", "updated", "created") ?: 0L,
        deleted = obj.flag("delete") || obj.flag("deleted"),
    )
}

internal fun parseMedia(obj: JsonObject?): LocationMedia? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return LocationMedia(
        id = id,
        location = obj.text("file_name"),
        sceneNumber = Folders.normaliseScene(obj.text("scene_number")),
        episodes = obj.strings("episode"),
        city = obj.text("city"),
        address = obj.text("address"),
        description = obj.text("description"),
        contactName = obj.text("contact_name"),
        email = obj.text("email"),
        phone = obj.text("phone"),
        countryCode = obj.text("country_code"),
        link = obj.text("link"),
        attachment = parseAttachment(obj["attachment"] as? JsonObject),
        linkAttachment = parseAttachment(obj["link_attachment"] as? JsonObject),
        status = LocationStatus.fromWire(obj.text("status")),
        uploadedBy = obj.text("uploaded_by"),
        createdMs = obj.long("created") ?: 0L,
        updatedMs = obj.long("updated") ?: 0L,
        deleted = obj.flag("deleted"),
        discussion = obj.flag("discussion"),
    )
}

internal fun parseAttachment(obj: JsonObject?): MediaAttachment? {
    if (obj == null) return null
    val media = obj.text("media").takeIf { it.isNotBlank() } ?: return null
    return MediaAttachment(
        media = media,
        thumbnail = obj.text("thumbnail").ifBlank { media },
        contentType = obj.text("content_type"),
        contentSubtype = obj.text("content_subtype"),
        name = obj.text("name"),
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        fileSize = obj.text("file_size"),
    )
}

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

/** A field that is a string OR an array of strings — episodes and scenes are both. */
private fun JsonObject.strings(vararg names: String): List<String> = when (val v = firstOf(*names)) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.isNotEmpty() }
    is JsonPrimitive -> v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.flag(name: String): Boolean =
    (firstOf(name) as? JsonPrimitive)?.let { p -> p.content.equals("true",
        true) || (p.longOrNull ?: 0L) != 0L } ?: false

/**
 * One discussion line.
 *
 * A body that will not decrypt is kept as an empty line rather than dropped:
 * the thread's shape — who spoke, and when — is still true, and dropping the
 * row would silently renumber a conversation people refer to by position.
 */
internal fun parseLocationMessage(
    obj: JsonObject?,
    decrypt: (String) -> String?,
    myUserId: String?,
): LocationMessage? {
    if (obj == null) return null
    val id = obj.messageText("_id", "id").takeIf { it.isNotBlank() } ?: return null
    val sender = obj.messageText("sender", "user_id", "created_by")
    val cipher = obj.messageText("message")
    return LocationMessage(
        id = id,
        senderId = sender,
        body = cipher.takeIf { it.isNotBlank() }?.let { decrypt(it) }.orEmpty(),
        sentAtMillis = obj.messageLong("created", "created_at") ?: 0,
        isMine = myUserId != null && sender.isNotBlank() && sender == myUserId,
    )
}

private fun JsonObject.messageText(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.orEmpty()

private fun JsonObject.messageLong(vararg names: String): Long? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.longOrNull }
