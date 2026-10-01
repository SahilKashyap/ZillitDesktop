package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SceneWrites
import com.zillit.desktop.feature.costumesetsync.data.persistDraft
import com.zillit.desktop.feature.costumesetsync.domain.CastEdit
import com.zillit.desktop.feature.costumesetsync.domain.emptyDraft
import com.zillit.desktop.feature.costumesetsync.domain.toDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Recorder(var createdId: String = "new1", var failStep: String? = null) : SceneWrites {
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<JsonObject>()

    private fun answer(step: String, data: String = "{}"): ZillitResult<Answer> {
        calls += step
        return if (failStep == step.substringBefore(':')) {
            ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Unknown("boom"))
        } else {
            ZillitResult.Success(Answer(Json.parseToJsonElement(data), null))
        }
    }

    override suspend fun createScene(body: JsonObject) = answer("create", """{"_id":"$createdId"}""").also { bodies += body }
    override suspend fun updateScene(id: String, body: JsonObject) = answer("update:$id").also { bodies += body }
    override suspend fun setSceneCharacter(sceneId: String, characterId: String, body: JsonObject) = answer("add:$characterId")
    override suspend fun removeSceneCharacter(sceneId: String, characterId: String) = answer("remove:$characterId")
    override suspend fun updateCharacter(id: String, body: JsonObject) = answer("character:$id").also { bodies += body }
}

class SceneDraftPersistTest {
    private val scene = sceneRec(
        """{"_id":"s1","number":"4","name":"Kitchen - Day","int_ext":"INT","location":"Kitchen","time_of_day":"DAY",
        |"script_day":"Day 1","synopsis":"","shoot_date":0,"characters":[]}""".trimMargin(),
    )

    @Test
    fun writesNothingForAnUntouchedRowAndKeepsACustomName() = runTest {
        val w = Recorder()
        val out = persistDraft(w, toDraft(scene), sceneId = "s1", original = scene)
        assertTrue(out.ok)
        assertNull(out.result)
        assertTrue(w.calls.isEmpty())
    }

    @Test
    fun renamesTheSceneWithItsNewLocation() = runTest {
        val w = Recorder()
        persistDraft(w, toDraft(scene).copy(location = "Garden"), sceneId = "s1", original = scene)
        assertEquals(listOf("update:s1"), w.calls)
        assertEquals("""{"location":"Garden","name":"Garden - Day"}""", w.bodies.single().toString())
    }

    @Test
    fun namesANewSceneAfterItsLocation() = runTest {
        val w = Recorder()
        val out = persistDraft(w, emptyDraft().copy(number = "9", location = "Roof"))
        assertEquals("new1", out.sceneId)
        val body = w.bodies.single().toString()
        assertTrue(body.contains("\"number\":\"9\"") && body.contains("\"name\":\"Roof\"") && body.contains("\"status\":\"PLANNED\""))
    }

    @Test
    fun addsAndRemovesPrincipalsThenSavesCastEditsOnlyForThoseStillIn() = runTest {
        val w = Recorder()
        val original = sceneRec("""{"_id":"s1","number":"4","characters":[{"character_id":"old"}]}""")
        val draft = toDraft(original).copy(
            principals = listOf("c1"),
            cast = mapOf("c1" to CastEdit(castNumber = "7", actorId = ""), "gone" to CastEdit(castNumber = "1")),
        )
        val out = persistDraft(w, draft, sceneId = "s1", original = original)
        assertTrue(out.ok)
        assertEquals(listOf("add:c1", "remove:old", "character:c1"), w.calls)
        assertEquals("""{"cast_number":7,"actor_id":null}""", w.bodies.single().toString())
    }

    @Test
    fun aBadCastNumberFailsWithoutWritingTheCharacter() = runTest {
        val w = Recorder()
        val draft = emptyDraft().copy(number = "1", principals = listOf("c1"), cast = mapOf("c1" to CastEdit(castNumber = "x")))
        val out = persistDraft(w, draft, problemText = { "bad number" })
        assertFalse(out.ok)
        assertEquals("new1", out.sceneId)
        assertFalse(w.calls.any { it.startsWith("character") })
    }

    @Test
    fun aFailureAfterCreateKeepsTheNewSceneIdForTheRetry() = runTest {
        val w = Recorder(failStep = "add")
        val out = persistDraft(w, emptyDraft().copy(number = "2", principals = listOf("c1")))
        assertFalse(out.ok)
        assertEquals("new1", out.sceneId)
    }
}
