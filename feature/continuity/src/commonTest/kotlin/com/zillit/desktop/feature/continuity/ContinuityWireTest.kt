package com.zillit.desktop.feature.continuity

import com.zillit.desktop.feature.continuity.data.createWire
import com.zillit.desktop.feature.continuity.data.editWire
import com.zillit.desktop.feature.continuity.data.parseScene
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.domain.TalentInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuityWireTest {

    private val attachment = ContinuityAttachment(
        media = "p/film-tools/continuity/actual/a_1.jpg",
        thumbnail = "p/film-tools/continuity/actual/a_1.jpg",
        contentType = "image",
        contentSubtype = "jpg",
        name = "a.jpg",
        bucket = "b",
        region = "eu-west-2",
        fileSize = "12",
    )

    @Test
    fun `create strips leading zeros, sends every attachment key and episode as a string`() {
        val body = createWire(
            SceneDraft(
                sceneNumber = "007A",
                episode = "3",
                notes = " n ",
                talentInfo = listOf(TalentInfo("Costume", "Blue")),
            ),
            attachment,
            uniqueId = "u1",
        )
        assertEquals("7A", body["scene_number"]!!.jsonPrimitive.content)
        assertEquals("n", body["scene_notes"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("3"), body["episode"])
        assertEquals("u1", body["unique_id"]!!.jsonPrimitive.content)
        val a = body["attachment"]!!.jsonObject
        assertEquals(
            setOf("media", "thumbnail", "content_type", "content_subtype", "caption", "height", "width", "duration",
                "bucket", "region", "name", "file_size"),
            a.keys,
        )
        assertEquals("Costume", body["talent_info"]!!.jsonArray.first().jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edit omits a blank episode and drops empty detail rows`() {
        val body = editWire(SceneDraft(sceneNumber = "12", notes = "x", talentInfo = listOf(TalentInfo(" ", ""))))
        assertNull(body["episode"])
        assertEquals(0, body["talent_info"]!!.jsonArray.size)
    }

    @Test
    fun `parse reads visibility flags in either shape and picks the newer timestamp as cursor`() {
        val obj = Json.parseToJsonElement(
            """{"_id":"s1","scene_number":"12","scene_notes":"n","department_id":"d1","uploaded_by":"u9",
               "visibility":{"intra":1,"all":"true"},"deleted":{"intra":0,"all":false},
               "created":100,"updated":200,"talent_info":[{"label":"Prop","value":"Cup"}],
               "attachment":{"media":"m","thumbnail":"t","content_type":"video","content_subtype":"MP4","name":"v.mp4",
                 "bucket":"b","region":"r","file_size":"9","duration":4}}""",
        ) as JsonObject
        val scene = assertNotNull(parseScene(obj))
        assertTrue(scene.shownOn(ContinuityTab.MyDepartment))
        assertTrue(scene.shownOn(ContinuityTab.AllDepartments))
        assertEquals(200L, scene.cursorMs)
        assertEquals("mp4", scene.attachment!!.contentSubtype)
        assertTrue(scene.attachment!!.isVideo)
        assertEquals(listOf(TalentInfo("Prop", "Cup")), scene.talentInfo)
    }

    @Test
    fun `parse treats missing visibility as intra-only and honours deletes`() {
        val obj = Json.parseToJsonElement("""{"id":"s2","scene_number":"3","deleted":{"intra":1}}""") as JsonObject
        val scene = assertNotNull(parseScene(obj))
        assertFalse(scene.shownOn(ContinuityTab.MyDepartment))
        assertFalse(scene.shownOn(ContinuityTab.AllDepartments))
        assertNull(scene.attachment)
    }
}
