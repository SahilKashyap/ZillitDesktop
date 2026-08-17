package com.zillit.desktop.feature.sides

import com.zillit.desktop.feature.sides.data.generateWire
import com.zillit.desktop.feature.sides.data.parseSceneBody
import com.zillit.desktop.feature.sides.domain.GeneratePlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The generation body and the raw scene parse — both wire contracts. */
class SidesWireTest {

    @Test
    fun `generate body carries the manual-mode contract`() {
        val body = generateWire(
            GeneratePlan(
                scriptId = "sc1",
                title = "Day 5",
                versionId = "v9",
                sceneNumbers = listOf("12", "9", "14A"),
                displayMode = GeneratePlan.DISPLAY_HIDE,
            ),
        )

        assertEquals("sc1", (body["scriptId"] as JsonPrimitive).content)
        assertEquals("manual", (body["mode"] as JsonPrimitive).content)
        assertEquals("hide", (body["sceneDisplayMode"] as JsonPrimitive).content)
        assertEquals("false", (body["publish"] as JsonPrimitive).content)
        val versionScenes = body["versionScenes"]!!.jsonArray[0].jsonObject
        assertEquals("v9", (versionScenes["versionId"] as JsonPrimitive).content)
        assertEquals(
            listOf("12", "9", "14A"),
            (versionScenes["sceneNumbers"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertNull(body["orderedScenes"], "no order given, no orderedScenes flag")
    }

    @Test
    fun `a typed scene order switches the run to orderedScenes`() {
        val body = generateWire(
            GeneratePlan(
                scriptId = "sc1",
                versionId = "v9",
                sceneNumbers = listOf("9", "12"),
                sceneOrder = listOf("12", "9"),
            ),
        )

        assertEquals("true", (body["orderedScenes"] as JsonPrimitive).content)
        assertEquals(
            listOf("12", "9"),
            (body["sceneOrder"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `a blank title is omitted entirely`() {
        val body = generateWire(
            GeneratePlan(scriptId = "s", versionId = "v", sceneNumbers = listOf("1")),
        )
        assertFalse("title" in body)
    }

    @Test
    fun `scene parse follows the web's split rule`() {
        assertEquals(listOf("12", "9", "14A"), GeneratePlan.parseScenes("12, 9;  14A"))
        assertTrue(GeneratePlan.parseScenes("  ,; ").isEmpty())
    }

    @Test
    fun `raw scene bodies parse enveloped or bare`() {
        val wrapped = parseSceneBody(
            """{"scenes":[{"sceneNumber":"1A","heading":"EXT. LOT","intExt":"EXT",
                "timeOfDay":"DAY","pageStart":1,"pageEnd":3}]}""",
        )
        assertEquals(1, wrapped.size)
        assertEquals("1A", wrapped[0].sceneNumber)
        assertEquals(3, wrapped[0].pageEnd)

        assertEquals("2B", parseSceneBody("""{"scenes":[{"scene_number":"2B"}]}""")[0].sceneNumber)
        assertEquals("3C", parseSceneBody("""[{"sceneNumber":"3C"}]""")[0].sceneNumber)
        assertTrue(parseSceneBody("not json").isEmpty())
        assertTrue(parseSceneBody("""{"scenes":[{}]}""").isEmpty(), "nameless scenes drop")
    }
}
