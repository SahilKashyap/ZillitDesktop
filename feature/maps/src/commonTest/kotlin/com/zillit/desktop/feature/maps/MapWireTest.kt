package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.data.cityBody
import com.zillit.desktop.feature.maps.data.locationBody
import com.zillit.desktop.feature.maps.data.mapSyncEvent
import com.zillit.desktop.feature.maps.data.parseCity
import com.zillit.desktop.feature.maps.data.parseLocation
import com.zillit.desktop.feature.maps.data.parseType
import com.zillit.desktop.feature.maps.data.reorderBody
import com.zillit.desktop.feature.maps.data.typeBody
import com.zillit.desktop.feature.maps.data.zoneBody
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.CityDraft
import com.zillit.desktop.feature.maps.domain.IntersectionStreets
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapList
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import com.zillit.desktop.feature.maps.domain.TypeDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The map service's wire, both directions, and the socket frames. */
class MapWireTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `add city sends the city only, both radii zero`() {
        assertEquals(
            json(
                """{"name":"Mumbai","description":"Maharashtra, India","coordinates":{"lat":19.07,"long":72.87},
                "center_point":{"lat":19.07,"long":72.87},"radius":0,"zone_radius":0}""",
            ),
            cityBody(CityDraft(" Mumbai ", "Maharashtra, India ", LatLng(19.07, 72.87))),
        )
        assertEquals(json("""{"newOrder":["b","a"]}"""), reorderBody(listOf("b", "a")))
    }

    @Test
    fun `a type saves its icon, sub-types as objects, and its id on an edit`() {
        assertEquals(
            json("""{"name":"Hotel","icon":"Z","sub_types":[{"name":"5 star"}],"is_active":true}"""),
            typeBody(TypeDraft("Hotel", "", listOf("5 star")), id = null),
        )
        val body = typeBody(TypeDraft("Hotel", "🏨", emptyList()), id = "t1")
        assertEquals("t1", body["location_type_id"].toString().trim('"'))
    }

    private val draft = LocationDraft(
        cityId = "c1",
        name = " Base Camp ",
        type = "Base Camp",
        subTypes = listOf("Tents"),
        description = "North gate ",
        address = "Film City Rd",
        sceneNumber = "12A",
        point = LatLng(19.16, 72.87),
    )

    @Test
    fun `a new location sends the web's body and no attachments when there are none`() {
        assertEquals(
            json(
                """{"city_id":"c1","location_name":"Base Camp","location_type":"Base Camp","description":"North gate",
                "location":{"lat":19.16,"long":72.87},"location_address":"Film City Rd","scene_number":"12A",
                "location_sub_type":[
                    "Tents",
                ],"is_studio_zone":false,"miles":0,"center_point_type":"point","distribute":false}""",
            ),
            locationBody(draft, id = null),
        )
    }

    @Test
    fun `an edit always sends attachments and the id`() {
        val edited = locationBody(draft, id = "l1")
        assertEquals("[]", edited["attachments"].toString())
        assertEquals("\"l1\"", edited["location_id"].toString())
        val photo = MapAttachment(
            media = "map/1/a.jpg",
            bucket = "b",
            region = "r",
            name = "a.jpg",
            contentType = "image",
            contentSubtype = "jpg",
        )
        val withPhoto = locationBody(draft.copy(attachments = listOf(photo)), id = null)
        assertEquals(
            json(
                """{"region":"r","bucket":"b","thumbnail":"","caption":"","height":0,"media":"map/1/a.jpg",
                "content_subtype":"jpg","width":0,"duration":0,"name":"a.jpg","content_type":"image"}""",
            ),
            (withPhoto["attachments"] as kotlinx.serialization.json.JsonArray)[0],
        )
    }

    @Test
    fun `a zone is a circle, with its streets when found from an intersection`() {
        val point = zoneBody(ZoneDraft("c1", "Andheri Zone", LatLng(19.1, 72.8), 30.0), id = null)
        assertEquals(
            json(
                """{"city_id":"c1","location_name":"Andheri Zone","location_type":"",
                "location":{"lat":19.1,"long":72.8},
                "is_studio_zone":true,"miles":30.0,"center_point_type":"point"}""",
            ),
            point,
        )
        val crossing = zoneBody(
            ZoneDraft(
                "c1",
                "MG & Ring Zone",
                LatLng(19.1, 72.8),
                12.5,
                CenterPointType.Intersection,
                IntersectionStreets("MG Road", "Ring Road"),
            ),
            id = "z1",
        )
        assertEquals(json("""{"street1":"MG Road","street2":"Ring Road"}"""), crossing["intersection_streets"])
        assertEquals("\"intersection\"", crossing["center_point_type"].toString())
        assertEquals("\"z1\"", crossing["location_id"].toString())
    }

    @Test
    fun `cities read coordinates, the center point and the count`() {
        val city = parseCity(
            json(
                """{"_id":"c1","name":"Mumbai","coordinates":{"lat":19.07,"long":72.87},
                "center_point":{"lat":19.1,"long":72.9},
                "radius":0,"location_count":4,"has_locations":true}""",
            ),
        )!!
        assertEquals(LatLng(19.07, 72.87), city.coordinates)
        assertEquals(LatLng(19.1, 72.9), city.centerPoint)
        assertEquals(4, city.locationCount)
        assertTrue(city.hasLocations)
        assertNull(parseCity(json("""{"_id":"c2","name":"Gone","deleted_on":1700000000000}""")))
    }

    @Test
    fun `types read sub-types in either spelling`() {
        val type = parseType(
            json("""{"_id":"t","name":"Hotel","icon":"🏨","sub_types":[{"name":"5 star"},"Budget"]}"""),
        )!!
        assertEquals(listOf("5 star", "Budget"), type.subTypes)
        assertEquals("🏨", type.icon)
    }

    @Test
    fun `locations read every coordinate spelling and keep their photos whole`() {
        val location = parseLocation(
            json(
                """{"_id":"l1","city_id":"c1","location_name":"Unit Base","location_type":"Base Camp",
                "location":{"lat":"19.16","lng":72.87},"location_address":"Film City","scene_number":"4",
                "location_sub_type":[
                    "Tents",
                ],"attachments":[{"media":"k","bucket":"b","region":"r","width":640,"height":480,"name":"a.jpg"}],
                "is_studio_zone":false}""",
            ),
        )!!
        assertEquals(LatLng(19.16, 72.87), location.point)
        assertEquals(640, location.attachments.single().width)
        assertEquals("a.jpg", location.attachments.single().name)
        val zone = parseLocation(
            json(
                """{"_id":"z1","city_id":"c1","location_name":"Z","latitude":1,"longitude":2,"is_studio_zone":true,
                "miles":40,
                "center_point_type":"intersection","intersection_streets":{"street1":"MG Road","street2":""}}""",
            ),
        )!!
        assertEquals(LatLng(1.0, 2.0), zone.point)
        assertEquals(40.0, zone.zoneRadiusMiles)
        assertEquals("MG Road", zone.intersection?.label)
        assertEquals(30.0, zone.copy(miles = 0.0).zoneRadiusMiles)
    }

    @Test
    fun `a frame from another production or this device is ignored`() {
        val frame = json("""{"project_id":"p2","device_id":"d1","entity_data":{"_id":"c9","name":"Pune"}}""")
        assertNull(mapSyncEvent("map:city:added", frame, projectId = "p1", deviceId = "d0"))
        val mine = json("""{"project_id":"p1","device_id":"d0","entity_data":{"_id":"c9","name":"Pune"}}""")
        assertNull(mapSyncEvent("map:city:added", mine, projectId = "p1", deviceId = "d0"))
        val theirs = json("""{"project_id":"p1","device_id":"d7","entity_data":{"_id":"c9","name":"Pune"}}""")
        val added = assertIs<MapSyncEvent.CityUpserted>(mapSyncEvent("map:city:added", theirs, "p1", "d0"))
        assertEquals("Pune", added.city.name)
        assertTrue(added.isNew)
    }

    @Test
    fun `pinned-location frames are zones unless they say otherwise`() {
        val pinned = json(
            """{"project_id":"p1",
                "entity_data":{"_id":"z1","city_id":"c1","location_name":"Z","location":{"lat":1,"long":2}}}""",
        )
        val zone = assertIs<MapSyncEvent.LocationUpserted>(mapSyncEvent("map:pinnedLocation:added", pinned, "p1", null))
        assertTrue(zone.location.isStudioZone)
        val location = assertIs<MapSyncEvent.LocationUpserted>(mapSyncEvent("map:location:updated", pinned, "p1", null))
        assertFalse(location.location.isStudioZone)
        assertFalse(location.isNew)
        val deleted = assertIs<MapSyncEvent.LocationDeleted>(
            mapSyncEvent(
                "map:location:deleted",
                json("""{"project_id":"p1","entity_data":{"_id":"l1","city_id":"c1"}}"""),"p1",
                null,
            ),
        )
        assertEquals("l1", deleted.id)
    }

    @Test
    fun `frames without a record refetch`() {
        assertEquals(
            MapSyncEvent.CitiesReordered,
            mapSyncEvent("map:city:reordered", json("""{"project_id":"p1"}"""), "p1", null),
        )
        assertEquals(
            MapSyncEvent.Refetch(MapList.Types),
            mapSyncEvent("map:locationType:deleted", json("""{"project_id":"p1"}"""), "p1", null),
        )
        assertEquals(MapSyncEvent.Refetch(MapList.Cities), mapSyncEvent("city:updated", null, "p1", null))
        assertNotNull(mapSyncEvent("map:location:added", json("""{"project_id":"p1"}"""), "p1", null))
    }
}
