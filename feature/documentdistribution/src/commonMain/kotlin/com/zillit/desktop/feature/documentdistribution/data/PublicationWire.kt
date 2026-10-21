package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The body of a publish, carrying only the keys the chosen destination reads.
 *
 * The receiving endpoints are strict in both directions: `mode` sent to a
 * destination that does not republish is refused with
 * `publication_mode_not_supported`, and a page destination with no
 * `scene_number` is refused too. Sending every key always is not an option, so
 * the target's own flags decide what goes — see [PublishTarget].
 *
 * An unknown category yields the bare body rather than throwing: the server
 * rejects it with `invalid_publication_category`, which is the honest answer,
 * and a client-side crash would be a worse one.
 */
internal fun publicationWire(category: String, draft: PublishDraft): JsonObject {
    val target = PublishTarget.of(category)
    return buildJsonObject {
        put("category", JsonPrimitive(category))
        // Null rather than absent, as the web sends it: a publish from the
        // library root has no folder and still says so.
        put("folder_id", draft.folderId?.let(::JsonPrimitive) ?: JsonNull)
        putDocuments(target, draft)
        putMode(target, draft)
        // Always present on the destinations that have the field, even empty —
        // the web spreads `{ note }` whenever `showNote`.
        if (target?.takesNote == true) put("note", JsonPrimitive(draft.note))
        if (target?.needsScene == true) {
            put("scene_number", JsonPrimitive(draft.sceneNumber.trim()))
        }
        if (target?.needsScheduleDate == true && draft.scheduleDate != null) {
            put("schedule_date", JsonPrimitive(draft.scheduleDate))
        }
        if (target?.needsScheduleType == true) {
            put("schedule_type", JsonPrimitive(draft.scheduleType))
        }
        if (target?.needsName == true) put("name", JsonPrimitive(draft.name.trim()))
        if (target?.needsEpisodeOnTelevision == true && draft.episode.isNotBlank()) {
            put("episode", JsonPrimitive(draft.episode.trim()))
        }
    }
}

/**
 * `documents` on the two republishing destinations, `document_ids` elsewhere —
 * never both (ZL-20245).
 *
 * The richer shape exists only to carry an optional display name per file; the
 * server appends the original extension itself, so nothing here touches one.
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putDocuments(
    target: PublishTarget?,
    draft: PublishDraft,
) {
    if (target?.republishable != true) {
        put("document_ids", JsonArray(draft.documentIds.map(::JsonPrimitive)))
        return
    }
    put(
        "documents",
        JsonArray(
            draft.documentIds.map { id ->
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    val chosen = draft.displayNames[id]?.trim().orEmpty()
                    if (chosen.isNotEmpty()) put("name", JsonPrimitive(chosen))
                }
            },
        ),
    )
}

/**
 * `mode` goes only where something is already live.
 *
 * [PublishDraft.mode] is null on a first publish, which is the whole gate:
 * there is nothing to replace or move to History, and the server rejects the
 * key on a destination that does not republish at all.
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putMode(
    target: PublishTarget?,
    draft: PublishDraft,
) {
    val mode = draft.mode ?: return
    if (target?.republishable != true) return
    put("mode", JsonPrimitive(mode.wire))
    // One live file for one new one — a string, not a list. A list reaches the
    // backend as an id it cannot match and the publish lands as an addition.
    if (mode == PublishMode.Replace) {
        draft.replaceChatId?.takeIf { it.isNotBlank() }?.let { put("replace_chat_id", JsonPrimitive(it)) }
    }
}
