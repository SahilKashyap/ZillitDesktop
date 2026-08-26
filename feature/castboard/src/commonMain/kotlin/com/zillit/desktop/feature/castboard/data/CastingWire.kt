package com.zillit.desktop.feature.castboard.data

import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import com.zillit.desktop.feature.castboard.domain.BoardMessage
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingMedia
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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

internal fun CastingEntryDto.toEntry(): CastingEntry? {
    val identifier = id?.takeIf { it.isNotBlank() } ?: return null
    // A deleted row is filtered out by every client (`castingFunction.js:65`).
    if (deleted == 1) return null
    return CastingEntry(
        id = identifier,
        characterName = characterName.orEmpty(),
        talentNames = namesOf(talentName),
        episode = textOf(episode),
        hierarchy = hierarchy.orEmpty(),
        gender = gender.orEmpty(),
        scenes = textOf(sceneNumber),
        media = attachment?.toMedia(),
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
