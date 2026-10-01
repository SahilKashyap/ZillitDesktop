package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.feature.costumesetsync.domain.ContinuityModel
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.body
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The take form's draft: one per open scene + character, so switching away starts a fresh one. */
@Stable
internal class TakeDraft {
    var takeNumber by mutableStateOf("1")
    var details by mutableStateOf(listOf<Pair<String, String>>())
    var accessories by mutableStateOf(listOf<Pair<String, Boolean>>())
    var notes by mutableStateOf("")
    var media by mutableStateOf(listOf<MediaEntry>())

    /** Edited by hand: a fresh read of the last take no longer overwrites it. */
    var touched by mutableStateOf(false)
    var saving by mutableStateOf(false)

    fun apply(fill: ContinuityModel.TakeFill) {
        takeNumber = fill.takeNumber
        details = fill.details
        accessories = fill.accessories
        notes = ""
    }

    inline fun edit(block: TakeDraft.() -> Unit) {
        touched = true
        block()
    }

    /** The `POST /continuity` body for this draft. */
    fun toBody(sceneId: String, characterId: String, changeId: String): JsonObject = body(
        "scene_id" to sceneId,
        "character_id" to characterId,
        "change_id" to changeId,
        "take_number" to (takeNumber.trim().toDoubleOrNull()?.toLong() ?: 1L),
        "notes" to JsonPrimitive(notes),
        "details" to buildJsonObject {
            details.filter { it.first.isNotBlank() }.forEach { (k, v) -> put(k.trim(), JsonPrimitive(v)) }
        },
        "accessories" to JsonArray(
            accessories.filter { it.first.isNotBlank() }.map { (name, present) ->
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("present", JsonPrimitive(present))
                }
            },
        ),
    )
}
