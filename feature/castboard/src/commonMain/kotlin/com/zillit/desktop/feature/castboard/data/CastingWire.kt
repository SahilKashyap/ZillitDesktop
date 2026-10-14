package com.zillit.desktop.feature.castboard.data

import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import com.zillit.desktop.feature.castboard.domain.BoardAttachment
import com.zillit.desktop.feature.castboard.domain.BoardCast
import com.zillit.desktop.feature.castboard.domain.BoardMessage
import com.zillit.desktop.feature.castboard.domain.BoardRecordDraft
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingMedia
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A casting row as the service sends it.
 *
 * `talent_name` arrives as a list, but a single string has been seen in older
 * rows, so it is read through [namesOf] rather than a typed field — a list
 * that is sometimes a string is the shape that breaks a strict decoder.
 */
@Serializable
internal data class CastingEntryDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("character_name") val characterName: String? = null,
    @SerialName("talent_name") val talentName: JsonElement? = null,
    @SerialName("episode") val episode: JsonElement? = null,
    @SerialName("hierarchy") val hierarchy: String? = null,
    @SerialName("gender") val gender: String? = null,
    /** Wardrobe rows only (`wardrobeApi/api.js:212`); absent on casting rows. */
    @SerialName("scene_number") val sceneNumber: JsonElement? = null,
    @SerialName("deleted") val deleted: Int? = null,
    @SerialName("attachment") val attachment: CastingMediaDto? = null,
)

@Serializable
internal data class CastingMediaDto(
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
)

/** A value that is a list of names, one name, or nothing at all. */
internal fun namesOf(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
    is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf(String::isNotBlank))
    else -> emptyList()
}

/** The same tolerance for a field that may be a number, a string, or a list. */
internal fun textOf(element: JsonElement?): String = namesOf(element).joinToString(", ")

/** [keepDeleted] is the File Cabinet, which is the one place a deleted row is meant to be seen. */
internal fun CastingEntryDto.toEntry(keepDeleted: Boolean = false): CastingEntry? {
    val identifier = id?.takeIf { it.isNotBlank() } ?: return null
    // A deleted row is filtered out by every client (`castingFunction.js:65`).
    if (deleted == 1 && !keepDeleted) return null
    return CastingEntry(
        id = identifier,
        characterName = characterName.orEmpty(),
        talentNames = namesOf(talentName),
        episode = textOf(episode),
        hierarchy = hierarchy.orEmpty(),
        gender = gender.orEmpty(),
        scenes = textOf(sceneNumber),
        media = attachment?.toMedia(),
        sceneNumbers = namesOf(sceneNumber),
    )
}

internal fun CastingMediaDto.toMedia(): CastingMedia? {
    val key = media?.takeIf { it.isNotBlank() } ?: return null
    return CastingMedia(
        media = key,
        fileName = name.orEmpty().ifBlank { key.substringAfterLast('/') },
        contentType = contentType.orEmpty(),
        bucket = bucket.orEmpty(),
        region = region.orEmpty(),
    )
}

/**
 * A new record's body.
 *
 * Casting's is `createCastingModal(value, 'create')`: a fresh `unique_id`,
 * the destination's status, and only the fields that were actually answered —
 * the web sets each key under an `if`, so a blank one is absent rather than
 * empty, and the character and talent names go up title-cased
 * (`spaceCapitalizeWords`).
 *
 * Wardrobe's is `handleCreateWardrobe`'s: the attachment, a `unique_id`, the
 * `cast_id` of the character it files under and the scene it is worn in.
 * A character the cast list does not carry yet travels as `cast`, which is
 * what the modal sends when the picker produced no id.
 */
internal fun createWire(draft: BoardRecordDraft, wardrobe: Boolean): JsonObject = buildJsonObject {
    put("unique_id", draft.uniqueId)
    put("status", draft.status.wire)
    draft.attachment?.let { put("attachment", attachmentWire(it)) }
    if (draft.episodes.isNotEmpty()) {
        // Create sends an array; edit sends the string it was typed as.
        put("episode", buildJsonArray { draft.episodes.forEach { add(JsonPrimitive(it)) } })
    }
    draft.description.trim().takeIf { it.isNotBlank() }?.let { put("description", it) }
    if (wardrobe) {
        draft.castId.takeIf { it.isNotBlank() }?.let { put("cast_id", it) }
        draft.sceneNumber.trim().takeIf { it.isNotBlank() }?.let { put("scene_number", it) }
        if (draft.castId.isBlank() && draft.characterName.isNotBlank()) {
            put(
                "cast",
                buildJsonObject {
                    put("character_name", titleCaseWords(draft.characterName))
                    put("talent_name", titleCaseWords(draft.talentName))
                    put("gender", draft.gender.trim())
                },
            )
        }
    } else {
        draft.characterName.takeIf { it.isNotBlank() }?.let { put("character_name", titleCaseWords(it)) }
        draft.talentName.takeIf { it.isNotBlank() }?.let { put("talent_name", titleCaseWords(it)) }
        draft.gender.trim().takeIf { it.isNotBlank() }?.let { put("gender", it) }
        draft.characterNumber.trim().takeIf { it.isNotBlank() }?.let { put("character_number", it) }
    }
}

/** The attachment as every film tool sends it — the web's one `dataObj`. */
private fun attachmentWire(a: BoardAttachment): JsonObject = buildJsonObject {
    put("media", a.media)
    put("thumbnail", a.thumbnail.ifBlank { a.media })
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
    raw.trim().split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) word else word.first().uppercaseChar() + word.drop(1)
    }

/** One row of a wardrobe list's cast — `cast_id` on the list, `_id` on the socket's copy. */
internal fun parseCast(obj: JsonObject?): BoardCast? {
    if (obj == null) return null
    val id = obj.line("cast_id", "_id").takeIf { it.isNotBlank() } ?: return null
    val character = obj.line("character_name")
    if (character.isBlank()) return null
    return BoardCast(castId = id, characterName = character, talentName = obj.line("talent_name"))
}

/**
 * One discussion line.
 *
 * A body that will not decrypt keeps its row rather than being dropped: the
 * thread's shape — who spoke, and when — is still true, and losing a row
 * silently renumbers a conversation people refer to by position.
 */
internal fun parseBoardMessage(
    obj: JsonObject?,
    decrypt: (String) -> String?,
    myUserId: String?,
): BoardMessage? {
    if (obj == null) return null
    val id = obj.line("_id", "id").takeIf { it.isNotBlank() } ?: return null
    val sender = obj.line("sender", "user_id", "created_by")
    val cipher = obj.line("message")
    return BoardMessage(
        id = id,
        senderId = sender,
        body = cipher.takeIf { it.isNotBlank() }?.let { decrypt(it) }.orEmpty(),
        sentAtMillis = obj.stamp("created", "created_at") ?: 0,
        isMine = myUserId != null && sender.isNotBlank() && sender == myUserId,
    )
}

private fun JsonObject.line(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.orEmpty()

private fun JsonObject.stamp(vararg names: String): Long? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.longOrNull }
