package com.zillit.desktop.feature.costumesetsync.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costumesetsync.domain.CastProblem
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneDraft
import com.zillit.desktop.feature.costumesetsync.domain.castNumberProblem
import com.zillit.desktop.feature.costumesetsync.domain.diffBody
import com.zillit.desktop.feature.costumesetsync.domain.sceneBody
import com.zillit.desktop.feature.costumesetsync.domain.toDraft
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The five writes a scene draft can need, so the save logic is testable without a network. */
interface SceneWrites {
    suspend fun createScene(body: JsonObject): ZillitResult<Answer>
    suspend fun updateScene(id: String, body: JsonObject): ZillitResult<Answer>
    suspend fun setSceneCharacter(sceneId: String, characterId: String, body: JsonObject): ZillitResult<Answer>
    suspend fun removeSceneCharacter(sceneId: String, characterId: String): ZillitResult<Answer>
    suspend fun updateCharacter(id: String, body: JsonObject): ZillitResult<Answer>
}

/** [SceneWrites] over the project-scoped client, at the paths the web's `syncOnsetApi.js` writes. */
class ApiSceneWrites(private val api: SyncOnsetApi) : SceneWrites {
    override suspend fun createScene(body: JsonObject) = api.post("/scenes", body)
    override suspend fun updateScene(id: String, body: JsonObject) = api.patch("/scenes/$id", body)
    override suspend fun setSceneCharacter(sceneId: String, characterId: String, body: JsonObject) =
        api.put("/scenes/$sceneId/characters/$characterId", body)

    override suspend fun removeSceneCharacter(sceneId: String, characterId: String) = api.delete("/scenes/$sceneId/characters/$characterId")
    override suspend fun updateCharacter(id: String, body: JsonObject) = api.patch("/characters/$id", body)
}

/**
 * What a save did. It reports, it does not throw: [result] is always what the
 * caller should toast (null when nothing needed writing, so there is no
 * envelope), and [sceneId] is set even on a failure that happened AFTER the
 * scene was created, so a retry updates instead of creating a second one.
 * Branch on [ok], never on whether a toast was shown.
 */
data class DraftOutcome(val ok: Boolean, val result: ZillitResult<Answer>?, val sceneId: String?)

private fun ok(res: ZillitResult<Answer>?, sceneId: String?) = DraftOutcome(true, res, sceneId)
private fun failed(res: ZillitResult<Answer>?, sceneId: String?) = DraftOutcome(false, res, sceneId)

/** A map of strings and numbers as the service's JSON: a blank string is SENT (it clears the field). */
fun Map<String, Any>.toJsonBody(): JsonObject = buildJsonObject {
    forEach { (key, value) ->
        when (value) {
            is Number -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}

/**
 * Write one draft: the scene itself, then the principals that were added or
 * removed, then the cast edits. The web's `persistDraft`.
 *
 * A cast number and the actor playing a part belong to the CHARACTER, not the
 * scene, so they are saved against the character — only for the people still in
 * the scene, and only where the user actually typed something.
 *
 * [problemText] turns a [CastProblem] into the words the user reads.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount") // One linear chain whose first failure stops it, as the web writes it.
suspend fun persistDraft(
    writes: SceneWrites,
    draft: SceneDraft,
    sceneId: String? = null,
    original: Rec? = null,
    revision: String? = null,
    problemText: (CastProblem) -> String = { it.name },
): DraftOutcome {
    var id = sceneId
    var last: ZillitResult<Answer>? = null

    if (id == null) {
        val body = sceneBody(draft, null).toMutableMap<String, Any>().apply {
            put("status", "PLANNED")
            revision?.takeIf { it.isNotEmpty() }?.let { put("revision", it) }
        }
        val res = writes.createScene(body.toJsonBody())
        if (res !is ZillitResult.Success) return failed(res, null)
        id = res.data.rec?.str("_id")?.ifEmpty { null } ?: res.data.rec?.rec("scene")?.str("_id")?.ifEmpty { null }
        if (id == null) return failed(res, null)
        last = res
    } else {
        val body = if (original != null) diffBody(sceneBody(draft, original), sceneBody(toDraft(original), original)) else sceneBody(draft, null)
        if (body.isNotEmpty()) {
            val res = writes.updateScene(id, body.toJsonBody())
            if (res !is ZillitResult.Success) return failed(res, id)
            last = res
        }
    }

    val before = original?.recs("characters").orEmpty().map { it.str("character_id") }
    for (cid in draft.principals.filter { it !in before }) {
        val res = writes.setSceneCharacter(id, cid, JsonObject(emptyMap()))
        if (res !is ZillitResult.Success) return failed(res, id)
        last = res
    }
    for (cid in before.filter { it !in draft.principals }) {
        val res = writes.removeSceneCharacter(id, cid)
        if (res !is ZillitResult.Success) return failed(res, id)
        last = res
    }

    for ((characterId, edit) in draft.cast) {
        if (characterId !in draft.principals) continue
        val body = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        edit.castNumber?.let { typed ->
            val raw = typed.trim()
            castNumberProblem(raw)?.let { return failed(ZillitResult.Failure(ZillitError.Validation(problemText(it))), id) }
            body["cast_number"] = if (raw.isEmpty()) JsonNull else JsonPrimitive(raw.toLong())
        }
        edit.actorId?.let { body["actor_id"] = if (it.isEmpty()) JsonNull else JsonPrimitive(it) }
        if (body.isNotEmpty()) {
            val res = writes.updateCharacter(characterId, JsonObject(body))
            if (res !is ZillitResult.Success) return failed(res, id)
            last = res
        }
    }
    return ok(last, id)
}
