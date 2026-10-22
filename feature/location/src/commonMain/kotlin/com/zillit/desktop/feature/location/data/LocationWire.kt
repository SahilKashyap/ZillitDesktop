// The tool's whole wire in one file: a body builder and a parser per shape,
// each a few lines, read side by side. Splitting them would put a payload
// and the answer it provokes in different files.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.location.data

import kotlinx.serialization.json.contentOrNull
import com.zillit.desktop.feature.location.domain.LocationMessage
import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LatLng
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationPin
import com.zillit.desktop.feature.location.domain.MovePlace
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
    // Both, not one or the other: since the link became a form field
    // (web PR #6516) a photo can carry a link too, and a place picked on the
    // map sends the map's picture as `link_attachment` beside the photo.
    // A folder-only record (no media, allowed on `selected`) omits both.
    if (attachment != null) put("attachment", attachmentWire(attachment))
    if (linkPreview != null) put("link_attachment", attachmentWire(linkPreview))
}

/** `createModal(value, 'edit')` — `locationIds`, no `unique_id`, no `attachment`, episode as typed. */
internal fun editWire(draft: LocationDraft, id: String, linkPreview: MediaAttachment? = null): JsonObject =
    buildJsonObject {
        putBase(draft)
        if (draft.episodes.isNotBlank()) put("episode", draft.episodes.trim())
        // A pin moved in the form sends a fresh picture of the map; one left
        // alone sends none, and the server keeps the picture it has.
        if (linkPreview != null) put("link_attachment", attachmentWire(linkPreview))
        put("locationIds", buildJsonArray { add(JsonPrimitive(id)) })
    }

/**
 * `createModal(value, 'moveByImage')`: the record's own metadata, the OLD
 * folder as `file_name`/`scene_number`, the same folder as `new_*` (a move
 * between shortlists keeps the folder), `ids`, and `generatepdf:false`.
 * The name is NOT title-cased here — the web passes it raw.
 */
internal fun moveWire(
    ids: List<String>,
    sample: LocationMedia,
    to: LocationStatus,
    /** A place picked in the Move form; every moved entry gets it. */
    place: MovePlace? = null,
): JsonObject = buildJsonObject {
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
    // A move drops `link`, `location` and `link_attachment` by default — the
    // record keeps what it had. A place picked in the Move form puts them
    // back, onto every entry in the move (web PR #6521, `keepPlace`).
    place?.let { picked ->
        put("link", picked.link)
        put("location", pinWire(picked.pin))
        picked.mapImage?.let { put("link_attachment", attachmentWire(it)) }
    }
}


/** `location: { lat, long }` — `long`, not `lng`, which is what the wire names it. */
internal fun pinWire(pin: LatLng): JsonObject = buildJsonObject {
    put("lat", pin.lat)
    put("long", pin.long)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putBase(draft: LocationDraft) {
    put("scene_number", Folders.normaliseScene(draft.sceneNumber))
    put("city", draft.city.trim())
    put("description", draft.description.trim())
    put("status", draft.status.wire)
    put("file_name", titleCaseWords(draft.location))
    // `null` removes the link; an empty string would leave the old one
    // standing, which is how the server reads "unchanged".
    if (draft.removeLink) put("link", JsonNull) else put("link", draft.link.trim())
    draft.pin?.let { put("location", pinWire(it)) }
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
        pin = parseLatLng(obj["location"] as? JsonObject),
        status = LocationStatus.fromWire(obj.text("status")),
        uploadedBy = obj.text("uploaded_by"),
        createdMs = obj.long("created") ?: 0L,
        updatedMs = obj.long("updated") ?: 0L,
        deleted = obj.flag("deleted"),
        discussion = obj.flag("discussion"),
    )
}

/**
 * `location: { lat, long }`.
 *
 * Only a pair of real numbers is a pin: the server writes `{}` for a record
 * that never had one, and a half-filled pair would put the place in the sea.
 */
internal fun parseLatLng(obj: JsonObject?): LatLng? {
    if (obj == null) return null
    val lat = obj.number("lat") ?: return null
    val long = obj.number("long", "lng") ?: return null
    return LatLng(lat, long)
}

/**
 * One row of `map-pins`.
 *
 * A row without usable coordinates is dropped rather than drawn at (0, 0),
 * which is in the Atlantic.
 */
internal fun parseLocationPin(obj: JsonObject?): LocationPin? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    val point = parseLatLng(obj["location"] as? JsonObject) ?: return null
    return LocationPin(
        id = id,
        location = obj.text("file_name"),
        sceneNumber = Folders.normaliseScene(obj.text("scene_number")),
        address = obj.text("address"),
        city = obj.text("city"),
        description = obj.text("description"),
        link = obj.text("link"),
        pin = point,
        status = LocationStatus.fromWire(obj.text("status")),
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

/** A number that may arrive as a JSON number or as a string. */
private fun JsonObject.number(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

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
