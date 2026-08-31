@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.location

import kotlin.test.assertFalse
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.data.createWire
import com.zillit.desktop.feature.location.data.editWire
import com.zillit.desktop.feature.location.data.moveWire
import com.zillit.desktop.feature.location.data.parseInfo
import com.zillit.desktop.feature.location.data.parseMedia
import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.MediaAttachment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationWireTest {

    private val stored = MediaAttachment("k/a.jpg", "k/a.jpg", "image", "jpg", "a.jpg", "b", "r", "123")

    @Test
    fun `create title-cases the name, sends episodes as an array and the attachment`() {
        val body = createWire(LocationDraft("old town hall", "12", "1,2", city = "Leeds"), stored, null, "u-1")
        assertEquals("Old Town Hall", body["file_name"]!!.jsonPrimitive.content)
        assertEquals(listOf("1", "2"), body["episode"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("u-1", body["unique_id"]!!.jsonPrimitive.content)
        assertEquals("k/a.jpg", body["attachment"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertEquals("selected", body["status"]!!.jsonPrimitive.content)
        assertNull(body["link_attachment"])
    }

    @Test
    fun `edit sends locationIds, the episode string, and no unique_id or attachment`() {
        val body = editWire(LocationDraft("hall", "not assigned", "3"), "id1")
        assertEquals("id1", body["locationIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("3", body["episode"]!!.jsonPrimitive.content)
        assertEquals("", body["scene_number"]!!.jsonPrimitive.content)
        assertNull(body["unique_id"])
        assertNull(body["attachment"])
    }

    @Test
    fun `move keeps the raw folder as old and new and names the target status`() {
        val record = parseMedia(
            Json.parseToJsonElement("""{"_id":"m1","file_name":"old town hall","scene_number":"12","episode":["1"],"city":"Leeds","status":"selected"}""") as JsonObject,
        )!!
        val body = moveWire(listOf("m1", "m2"), record, LocationStatus.Shortlisted)
        assertEquals("old town hall", body["file_name"]!!.jsonPrimitive.content)
        assertEquals("old town hall", body["new_file_name"]!!.jsonPrimitive.content)
        assertEquals("shortlisted", body["status"]!!.jsonPrimitive.content)
        assertEquals("false", body["generatepdf"]!!.jsonPrimitive.content)
        assertEquals(2, body["ids"]!!.jsonArray.size)
        assertEquals("1", body["new_episode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `info parses camelCase rows and media parses snake_case rows`() {
        val info = parseInfo(
            Json.parseToJsonElement("""{"location":"Hall","sceneNumbers":"12","episode":["1","2"],"city":["Leeds"],"lastUpdate":5,"delete":0}""") as JsonObject,
        )!!
        assertEquals(listOf("12"), info.sceneNumbers)
        assertEquals(listOf("1", "2"), info.episodes)
        assertTrue(!info.deleted)
        val media = parseMedia(
            Json.parseToJsonElement("""{"_id":"m","file_name":"Hall","scene_number":"Not Assigned","created":1,"updated":2,"deleted":0,"link":"https://x"}""") as JsonObject,
        )!!
        assertEquals("", media.sceneNumber)
        assertTrue(media.isLink)
        assertTrue(media.edited)
    }

    @Test
    fun `folders group by location, scene (numeric order) and episode`() {
        val rows = listOf(
            LocationInfo("Hall", listOf("12"), listOf("2"), listOf("Leeds"), 3L, false),
            LocationInfo("Bridge", listOf("3", "12"), listOf("1"), emptyList(), 2L, false),
            LocationInfo("Bridge", listOf(""), listOf(), emptyList(), 9L, false),
        )
        val byLocation = Folders.group(rows, GroupBy.LocationName)
        assertEquals(listOf("Bridge", "Hall"), byLocation.map { it.key })
        assertEquals(9L, byLocation[0].lastUpdateMs)
        assertEquals(listOf("3", "12", ""), byLocation[0].sceneNumbers)

        val byScene = Folders.group(rows, GroupBy.SceneNo)
        assertEquals(listOf("3", "12", ""), byScene.map { it.key })
        assertEquals("Not Assigned", byScene.last().title)
        assertEquals(setOf("Bridge", "Hall"), byScene[1].locations.toSet())

        val byEpisode = Folders.group(rows, GroupBy.EpisodeNo)
        assertEquals(listOf("1", "2", ""), byEpisode.map { it.key })
        assertEquals(listOf("Hall"), Folders.search(byLocation, "leeds").map { it.key })
    }

    @Test
    fun `picks are the galleries inside a folder, one per scene, location, or pair per episode`() {
        val rows = listOf(
            LocationInfo("Hall", listOf("12"), listOf("2"), listOf("Leeds"), 3L, false),
            LocationInfo("Bridge", listOf("3", "12"), listOf("1"), emptyList(), 2L, false),
            LocationInfo("Bridge", listOf(""), listOf(), emptyList(), 9L, false),
        )
        val bridge = Folders.group(rows, GroupBy.LocationName).first { it.key == "Bridge" }
        assertEquals(
            listOf(LocationPick("Bridge", "3"), LocationPick("Bridge", "12"), LocationPick("Bridge", "")),
            Folders.picks(bridge, GroupBy.LocationName, rows),
        )
        val scene12 = Folders.group(rows, GroupBy.SceneNo).first { it.key == "12" }
        assertEquals(setOf("Bridge", "Hall"), Folders.picks(scene12, GroupBy.SceneNo, rows).map { it.location }.toSet())
        val ep1 = Folders.group(rows, GroupBy.EpisodeNo).first { it.key == "1" }
        assertEquals(
            listOf(LocationPick("Bridge", "3", "1"), LocationPick("Bridge", "12", "1")),
            Folders.picks(ep1, GroupBy.EpisodeNo, rows),
        )
        // A folder with exactly one gallery has exactly one pick — it opens straight away.
        val hall = Folders.group(rows, GroupBy.LocationName).first { it.key == "Hall" }
        assertEquals(listOf(LocationPick("Hall", "12")), Folders.picks(hall, GroupBy.LocationName, rows))
    }
    /**
     * Posting and downloading are different questions for an admin.
     *
     * Android's location pages gate posting on `postingAccess` alone, while
     * iOS gates every *download* on `getLoginUserAdminAccess() ||
     * getProjectDownloadRight(LOCATION_TOOL)` and offers to ask an admin for
     * the right otherwise. This port granted both.
     */
    @Test
    fun `an admin inherits location downloads but not posting`() {
        val admin = LocationViewer(
            canView = true, canPost = false, canDownload = false, isAdmin = true, ready = true,
        )

        assertFalse(admin.mayPost, "an admin without posting rights could post")
        assertTrue(admin.mayDownload, "iOS grants an admin the download")
    }

}
