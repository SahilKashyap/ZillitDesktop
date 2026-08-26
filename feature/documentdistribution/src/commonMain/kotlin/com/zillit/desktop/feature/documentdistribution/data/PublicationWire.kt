package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import kotlinx.serialization.json.JsonArray
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
        put("document_ids", draft.documentIds.asJsonArray())
        // `mode` is omitted on a first publish — there is nothing live to
        // replace, and the server rejects `replace` with no target.
        if (target?.republishable == true && draft.replaceChatIds.isNotEmpty()) {
            put("mode", JsonPrimitive(draft.mode.wire))
            put("replace_chat_id", draft.replaceChatIds.asJsonArray())
        }
        if (target?.takesNote == true && draft.note.isNotBlank()) {
            put("note", JsonPrimitive(draft.note))
        }
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

private fun List<String>.asJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
