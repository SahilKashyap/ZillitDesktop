package com.zillit.desktop.feature.sides

import com.zillit.desktop.feature.sides.data.autoGenerateWire
import com.zillit.desktop.feature.sides.data.generateWire
import com.zillit.desktop.feature.sides.data.parseCallSheet
import com.zillit.desktop.feature.sides.data.parseSceneBody
import com.zillit.desktop.feature.sides.data.parseScenePage
import com.zillit.desktop.feature.sides.data.parseSides
import com.zillit.desktop.feature.sides.data.scenePageWire
import com.zillit.desktop.feature.sides.domain.AutoPlan
import com.zillit.desktop.feature.sides.domain.ManualPlan
import com.zillit.desktop.feature.sides.domain.PageSelection
import com.zillit.desktop.feature.sides.domain.SceneDisplayMode
import com.zillit.desktop.feature.sides.domain.ScenePageDraft
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.domain.VersionScenes
import com.zillit.desktop.feature.sides.domain.parseSceneList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The generation bodies and the lenient parsers — all wire contracts. */
class SidesWireTest {

    private fun JsonArray.strings() = map { (it as JsonPrimitive).content }

    @Test
    fun `manual body carries the web's handleSubmit payload`() {
        val body = generateWire(
            ManualPlan(
                scriptId = "sc1",
                title = "Day 5",
                versionScenes = listOf(VersionScenes("v9", listOf("12", "9", "14A"))),
                pageSelections = listOf(PageSelection("p1", listOf("3")), PageSelection("p2")),
                displayMode = SceneDisplayMode.HIDE,
            ),
        )

        assertEquals("sc1", (body["scriptId"] as JsonPrimitive).content)
        assertEquals("manual", (body["mode"] as JsonPrimitive).content)
        assertEquals("hide", (body["sceneDisplayMode"] as JsonPrimitive).content)
        assertEquals("false", (body["publish"] as JsonPrimitive).content)
        val versionScenes = body["versionScenes"]!!.jsonArray[0].jsonObject
        assertEquals("v9", (versionScenes["versionId"] as JsonPrimitive).content)
        assertEquals(listOf("12", "9", "14A"), versionScenes["sceneNumbers"]!!.jsonArray.strings())
        val pages = body["pageSelections"]!!.jsonArray
        assertEquals("p1", (pages[0].jsonObject["pageId"] as JsonPrimitive).content)
        assertTrue(pages[1].jsonObject["sceneNumbers"]!!.jsonArray.isEmpty(), "a whole-PDF page sends no scenes")
        assertNull(body["orderedScenes"], "no order given, no orderedScenes flag")
    }

    @Test
    fun `a custom order regroups the version picks and flags orderedScenes`() {
        val body = generateWire(
            ManualPlan(
                scriptId = "sc1",
                versionScenes = listOf(VersionScenes("v1", listOf("9", "12")), VersionScenes("v2", listOf("3"))),
                sceneOrder = listOf("3", "12", "9"),
            ),
        )

        assertEquals("true", (body["orderedScenes"] as JsonPrimitive).content)
        assertEquals(listOf("3", "12", "9"), body["sceneOrder"]!!.jsonArray.strings())
        val groups = body["versionScenes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("v2", "v1"), groups.map { (it["versionId"] as JsonPrimitive).content })
        assertEquals(listOf("12", "9"), groups[1]["sceneNumbers"]!!.jsonArray.strings())
        assertFalse("pageSelections" in body, "no pages picked, no key")
    }

    @Test
    fun `a blank title is omitted entirely`() {
        val body = generateWire(ManualPlan(scriptId = "s", versionScenes = listOf(VersionScenes("v", listOf("1")))))
        assertFalse("title" in body)
    }

    @Test
    fun `autogenerate body joins scenes into one string and pins the flags`() {
        val body = autoGenerateWire(
            AutoPlan(
                scriptId = "sc",
                callSheetId = "cs",
                scheduleId = "sh",
                sceneNumbers = listOf("12", "9"),
                title = "Sides - Day 1",
            ),
        )

        assertEquals("12, 9", (body["sceneNumbers"] as JsonPrimitive).content)
        assertEquals("true", (body["includeCallSheet"] as JsonPrimitive).content)
        assertEquals("false", (body["includeCallSheetScenes"] as JsonPrimitive).content)
        assertEquals("true", (body["orderedScenes"] as JsonPrimitive).content)
        assertEquals("false", (body["publish"] as JsonPrimitive).content)
        assertEquals("sh", (body["scheduleId"] as JsonPrimitive).content)
        assertEquals("crossout", (body["sceneDisplayMode"] as JsonPrimitive).content)
        assertNull(autoGenerateWire(AutoPlan("sc", "cs", sceneNumbers = listOf("1")))["scheduleId"])
    }

    @Test
    fun `page editor body spells colour the British way and omits a kept file`() {
        val kept = scenePageWire(ScenePageDraft("12A", "#e53935", "note"))
        assertEquals("#e53935", (kept["colour"] as JsonPrimitive).content)
        assertNull(kept["attachment"])

        val replaced = scenePageWire(
            ScenePageDraft("12A", "#e53935", "", StoredAttachment(media = "k", name = "a.fdx", contentSubtype = "fdx")),
        )
        assertEquals("fdx", (replaced["attachment"]!!.jsonObject["content_subtype"] as JsonPrimitive).content)
        assertEquals("document", (replaced["attachment"]!!.jsonObject["content_type"] as JsonPrimitive).content)
    }

    @Test
    fun `scene parse follows the web's split rule`() {
        assertEquals(listOf("12", "9", "14A"), parseSceneList("12, 9;  14A"))
        assertTrue(parseSceneList("  ,; ").isEmpty())
    }

    @Test
    fun `raw scene bodies parse enveloped, bare, or wrapped in data`() {
        val wrapped = parseSceneBody(
            """{"scenes":[{"sceneNumber":"1A","heading":"EXT. LOT","intExt":"EXT",
                "timeOfDay":"DAY","pageStart":1,"pageEnd":3}]}""",
        )
        assertEquals(1, wrapped.size)
        assertEquals("1A", wrapped[0].sceneNumber)
        assertEquals(3, wrapped[0].pageEnd)

        assertEquals("2B", parseSceneBody("""{"scenes":[{"scene_number":"2B"}]}""")[0].sceneNumber)
        assertEquals("3C", parseSceneBody("""[{"sceneNumber":"3C"}]""")[0].sceneNumber)
        assertEquals(
            "4D",
            parseSceneBody("""{"status":1,"message":"ok","data":{"scenes":[{"sceneNumber":"4D"}]}}""")[0].sceneNumber,
        )
        assertTrue(parseSceneBody("not json").isEmpty())
        assertTrue(parseSceneBody("""{"scenes":[{}]}""").isEmpty(), "nameless scenes drop")
    }

    @Test
    fun `a sides row reads its folders, creator and file`() {
        val row = parseSides(
            Json.parseToJsonElement(
                """{"_id":"s1","title":"Day 5","status":"ready","sceneNumbers":["12","9"],"totalScenes":2,
                "script":{"_id":"sc","title":"Ep 1"},"scriptVersion":{"versionNumber":2},
                "generatedBy":{"_id":"u1","name":"Aisha"},"downloadCount":3,"createdAt":"2026-09-15T10:00:00Z",
                "attachment":{"name":"day5.pdf","file_size":2048},
                "sceneFolders":[{"scene_number":"7","page_colour_code":"#1e88e5","sceneNumbers":["7A"]}]}""",
            ).jsonObject,
        )!!

        assertEquals("Ep 1_v2", row.scriptLabel)
        assertEquals("day5.pdf", row.fileLabel)
        assertEquals("Aisha", row.generatedByName)
        assertEquals(2048L, row.attachmentSize)
        assertEquals("7", row.pageRefs.single().sceneNumber)
        assertEquals("#1e88e5", row.pageRefs.single().color)
    }

    @Test
    fun `pages and call sheets read backend spellings`() {
        val page = parseScenePage(
            Json.parseToJsonElement(
                """{"_id":"p1","scene_number":"12A","page_colour_code":"#fb8c00","pageCount":4,
                "attachment":{"media":"a/b/scene12.pdf"}}""",
            ).jsonObject,
        )!!
        assertEquals("12A", page.sceneNumber)
        assertEquals("#fb8c00", page.color)
        assertEquals("scene12.pdf", page.fileName, "no name → the key's basename")

        val sheet = parseCallSheet(
            Json.parseToJsonElement(
                """{"_id":"c1","title":"Day 3","source":"uploaded",
                "scenes":[{"sceneNumber":"4"},"5",{"scene_number":"6"}]}""",
            ).jsonObject,
        )!!
        assertTrue(sheet.uploaded)
        assertEquals(listOf("4", "5", "6"), sheet.scenes)
    }
}
