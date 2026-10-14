package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.GlobalSearchModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

class GlobalSearchModelTest {

    @Test
    fun `search matches scenes by number prefix and characters by cast number`() {
        val scenes = listOf(
            rec("""{"_id":"s1","number":"12","name":"Kitchen"}"""),
            rec("""{"_id":"s2","number":"3","location":"Garden 12"}"""),
        )
        assertEquals(listOf("ss1", "ss2"), GlobalSearchModel.sceneHits(scenes, "12", "Sc", "Scene").map { it.key })
        val chars = listOf(rec("""{"_id":"c1","name":"Meera","cast_number":4}"""))
        assertEquals("4. Meera", GlobalSearchModel.characterHits(chars, "4", "Character").single().label)
    }
}
