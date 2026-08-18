package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.feature.pagedistribution.data.parseDocument
import com.zillit.desktop.feature.pagedistribution.data.parseFolder
import com.zillit.desktop.feature.pagedistribution.data.publishWire
import com.zillit.desktop.feature.pagedistribution.data.titleCaseWords
import com.zillit.desktop.feature.pagedistribution.data.uploadWire
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.FolderKey
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DistributionWireTest {

    private val zone = TimeZone.of("Europe/London")
    private val stored = StoredPdf(
        media = "p/actual/a_1.pdf",
        name = "a.pdf",
        bucket = "b",
        region = "eu-west-2",
        fileSize = "1234",
    )

    // 2026-03-06T10:15 London → midnight is 2026-03-06T00:00Z (GMT in March before the 29th).
    private val now = 1_772_792_100_000L
    private val midnight = 1_772_755_200_000L

    private fun body(tool: DistributionTool, tabKey: String, draft: UploadDraft): JsonObject =
        uploadWire(tool, tool.tabs.first { it.key == tabKey }, draft, stored, "uid-1", now, zone)

    @Test
    fun `full schedule create sends the date or the empty string, the name, and no page fields`() {
        val withDate = body(
            DistributionTool.ScheduleDistribution,
            "full_script",
            UploadDraft("a.pdf", ByteArray(0), dateYmd = "2026-03-06", episode = "2"),
        )
        assertEquals(midnight.toString(), withDate["schedule_date"]!!.jsonPrimitive.content)
        assertEquals("", withDate["schedule_name"]!!.jsonPrimitive.content)
        assertEquals("", withDate["schedule_type"]!!.jsonPrimitive.content)
        assertEquals("2", withDate["episode"]!!.jsonPrimitive.content)
        assertNull(withDate["scene_number"])
        assertNull(withDate["page_colour_code"])
        assertNull(withDate["schedule_page_number"])

        val noDate = body(DistributionTool.ScheduleDistribution, "full_script", UploadDraft("a.pdf", ByteArray(0)))
        assertEquals("", noDate["schedule_date"]!!.jsonPrimitive.content)
        assertEquals("document", noDate["attachment"]!!.jsonObject["caption"]!!.jsonPrimitive.content)
        assertEquals("1234", noDate["attachment"]!!.jsonObject["file_size"]!!.jsonPrimitive.content)
    }

    @Test
    fun `single replace carries the parent pointer and drops the name`() {
        val body = body(
            DistributionTool.ScriptDistribution,
            "full_script",
            UploadDraft("a.pdf", ByteArray(0), replaces = "old"),
        )
        assertEquals("old", body["parent_script_id"]!!.jsonPrimitive.content)
        assertNull(body["script_name"])
        assertNull(body["schedule_type"])
        assertEquals("", body["script_date"]!!.jsonPrimitive.content)
    }

    @Test
    fun `schedule pages create stamps revision_date at local midnight, shifted for one-line pages`() {
        val full = body(
            DistributionTool.ScheduleDistribution, "page",
            UploadDraft(
                "a.pdf",
                ByteArray(0),
                sceneNumber = "12",
                pageNumber = "3",
                colour = PageColour.Blue,
                scheduleType = ScheduleType.FullSchedulePages,
                dateYmd = "2026-03-06",
            ),
        )
        assertEquals(midnight.toString(), full["revision_date"]!!.jsonPrimitive.content)
        assertEquals(midnight.toString(), full["user_selected_date"]!!.jsonPrimitive.content)
        assertEquals("#ADD8E6", full["page_colour_code"]!!.jsonPrimitive.content)
        assertEquals("12", full["scene_number"]!!.jsonPrimitive.content)
        assertEquals("3", full["schedule_page_number"]!!.jsonPrimitive.content)
        assertEquals("full_schedule_pages", full["schedule_type"]!!.jsonPrimitive.content)

        val oneLine = body(
            DistributionTool.ScheduleDistribution, "page",
            UploadDraft("a.pdf", ByteArray(0), sceneNumber = "12", scheduleType = ScheduleType.OneLinePages),
        )
        assertEquals((midnight + 5_000L).toString(), oneLine["revision_date"]!!.jsonPrimitive.content)
        assertNull(oneLine["user_selected_date"])
    }

    @Test
    fun `script pages use script_page_number and never schedule_type`() {
        val body = body(
            DistributionTool.ScriptDistribution,
            "page",
            UploadDraft("a.pdf", ByteArray(0), sceneNumber = "4", pageNumber = "9"),
        )
        assertEquals("9", body["script_page_number"]!!.jsonPrimitive.content)
        assertNull(body["schedule_page_number"])
        assertNull(body["schedule_type"])
    }

    @Test
    fun `dod carries a title-cased name, user_selected_date 0 and today's midnight`() {
        val typed = body(DistributionTool.ScheduleDod, "dod", UploadDraft("a.pdf", ByteArray(0), name = "WEEK one dod"))
        assertEquals("Week One Dod", typed["name"]!!.jsonPrimitive.content)
        assertEquals("0", typed["user_selected_date"]!!.jsonPrimitive.content)
        assertEquals(midnight.toString(), typed["revision_date"]!!.jsonPrimitive.content)
        assertNull(typed["schedule_type"])

        val picked = body(
            DistributionTool.ScheduleDod,
            "dod",
            UploadDraft("a.pdf", ByteArray(0), name = "WEEK ONE DOD", nameFromPick = true),
        )
        assertEquals("WEEK ONE DOD", picked["name"]!!.jsonPrimitive.content)
        assertEquals("Week One", titleCaseWords("  week ONE "))
    }

    @Test
    fun `publish body files under root, sub-folder and the episode leaf with a YYYY-MM-DD date`() {
        val doc = parseDocument(
            Json.parseToJsonElement(
                """{"_id":"d1","created":1,"created_by":"u","episode":"3","scene_number":"12","schedule_date":$midnight,
                    "attachment":{"media":"k","name":"sched","bucket":"b","region":"r","file_size":"9"}}""",
            ) as JsonObject,
        )!!
        val tool = DistributionTool.ScheduleDistribution
        val body = publishWire(tool, tool.tabs[0], doc, "2026-08-17", zone)
        assertEquals("sched.pdf", body["original_name"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("Schedule Full & One Line", "Schedule Full", "3"),
            body["folder_path"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("2026-03-06", body["folder_date"]!!.jsonPrimitive.content)
        assertEquals("k", body["media"]!!.jsonPrimitive.content)
        assertEquals("9", body["file_size"]!!.jsonPrimitive.content)

        val dod = DistributionTool.ScheduleDod
        val dodBody = publishWire(
            dod,
            dod.tabs[0],
            doc.copy(episode = "", dateMs = 0L, revisionDateMs = 0L),
            "2026-08-17",
            zone,
        )
        assertEquals(
            listOf("Schedule D.O.D", "12"),
            dodBody["folder_path"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("2026-08-17", dodBody["folder_date"]!!.jsonPrimitive.content)
    }

    @Test
    fun `records and folders parse the wire's numeric flags`() {
        val doc = parseDocument(
            Json.parseToJsonElement(
                """{"_id":"x","deleted":0,"replaced":1,"page_colour_code":"#FFB6C1",
                    "schedule_type":"one_line_schedule_pages"}""",
            ) as JsonObject,
        )!!
        assertFalse(doc.deleted)
        assertTrue(doc.replaced)
        assertEquals(ScheduleType.OneLinePages, doc.scheduleType)
        val folderJson = Json.parseToJsonElement("""{"_id":"f","name":"Week One","deleted":0}""") as JsonObject
        val folder = parseFolder(folderJson, FolderKey.Name)!!
        assertEquals("Week One", folder.key)
        val sceneJson = Json.parseToJsonElement("""{"_id":"f2","scene_number":"7"}""") as JsonObject
        val scene = parseFolder(sceneJson, FolderKey.SceneNumber)!!
        assertEquals("7", scene.key)
    }
}
