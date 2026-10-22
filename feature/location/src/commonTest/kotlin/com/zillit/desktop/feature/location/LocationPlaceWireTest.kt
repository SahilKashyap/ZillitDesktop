@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.location

import com.zillit.desktop.feature.location.data.createWire
import com.zillit.desktop.feature.location.data.editWire
import com.zillit.desktop.feature.location.data.moveWire
import com.zillit.desktop.feature.location.data.parseLocationPin
import com.zillit.desktop.feature.location.data.parseMedia
import com.zillit.desktop.feature.location.domain.LatLng
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.MovePlace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A location's place and its link, on the wire.
 *
 * The web moved the link out of the attach menu into the form and then
 * replaced it with a map pin (PRs #6516, #6517, #6521). Three rules came out
 * of that and none of them is guessable from the payload: a record may carry
 * a file *and* a link, an empty `link` means "leave it alone" while `null`
 * means "remove it", and a move drops the place unless it is sent again.
 */
class LocationPlaceWireTest {

    private val photo = MediaAttachment("k/a.jpg", "k/a.jpg", "image", "jpg", "a.jpg", "b", "r", "123")
    private val mapPicture = MediaAttachment("k/map.png", "k/map.png", "image", "png", "map.png", "b", "r", "9")
    private val pin = LatLng(51.5079, -0.0877)

    @Test
    fun `a create carries the photo and the map picture together`() {
        // Before #6516 these were exclusive: a record was a file or a link.
        val body = createWire(
            LocationDraft("millennium bridge", pin = pin, link = "https://www.google.com/maps?q=51.5079,-0.0877"),
            photo,
            mapPicture,
            "u-1",
        )
        assertEquals("k/a.jpg", body["attachment"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertEquals("k/map.png", body["link_attachment"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertEquals("https://www.google.com/maps?q=51.5079,-0.0877", body["link"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pin rides as location with lat and long`() {
        // `long`, not `lng`: Google's spelling is not this service's.
        val body = createWire(LocationDraft("bridge", pin = pin), null, null, "u-2")
        val place = body["location"]!!.jsonObject
        assertEquals("51.5079", place["lat"]!!.jsonPrimitive.content)
        assertEquals("-0.0877", place["long"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unpinned draft sends no location at all`() {
        // Not a zero pair, which is a real place in the Atlantic.
        assertNull(createWire(LocationDraft("bridge"), null, null, "u-3")["location"])
    }

    @Test
    fun `an empty link keeps the stored one and null removes it`() {
        assertEquals("", editWire(LocationDraft("hall"), "id1")["link"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, editWire(LocationDraft("hall", removeLink = true), "id1")["link"])
    }

    @Test
    fun `an edit sends a map picture only when the pin moved`() {
        assertNull(editWire(LocationDraft("hall"), "id1")["link_attachment"])
        assertEquals(
            "k/map.png",
            editWire(LocationDraft("hall", pin = pin), "id1", mapPicture)["link_attachment"]!!
                .jsonObject["media"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a move carries a picked place onto every record`() {
        val record = parseMedia(
            Json.parseToJsonElement("""{"_id":"m1","file_name":"bridge","scene_number":"12","status":"selected"}""") as JsonObject,
        )!!
        val place = MovePlace(pin, "https://www.google.com/maps?q=51.5079,-0.0877", mapPicture)
        val body = moveWire(listOf("m1", "m2"), record, LocationStatus.Published, place)
        assertEquals("https://www.google.com/maps?q=51.5079,-0.0877", body["link"]!!.jsonPrimitive.content)
        assertEquals("51.5079", body["location"]!!.jsonObject["lat"]!!.jsonPrimitive.content)
        assertEquals("k/map.png", body["link_attachment"]!!.jsonObject["media"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a move with no place picked sends none`() {
        val record = parseMedia(
            Json.parseToJsonElement("""{"_id":"m1","file_name":"bridge","scene_number":"12","status":"selected"}""") as JsonObject,
        )!!
        val body = moveWire(listOf("m1"), record, LocationStatus.Published)
        assertNull(body["link"])
        assertNull(body["location"])
        assertNull(body["link_attachment"])
    }

    @Test
    fun `a record with media and a link is media, not a link entry`() {
        val record = parseMedia(
            Json.parseToJsonElement("""{"_id":"m1","file_name":"bridge","link":"https://example.com/listing","attachment":{"media":"k/a.jpg","content_type":"image"}}""") as JsonObject,
        )!!
        assertFalse(record.isLink, "a photo with a link is still a photo")
        assertTrue(record.hasLink, "but its link is there to offer")
    }

    @Test
    fun `a record with a link and no media of its own is a link entry`() {
        val record = parseMedia(
            Json.parseToJsonElement("""{"_id":"m2","file_name":"bridge","link":"https://example.com/listing","link_attachment":{"media":"k/p.jpg","content_type":"image"}}""") as JsonObject,
        )!!
        assertTrue(record.isLink)
    }

    @Test
    fun `a saved pin is read back`() {
        val record = parseMedia(
            Json.parseToJsonElement("""{"_id":"m3","file_name":"bridge","location":{"lat":51.5079,"long":-0.0877}}""") as JsonObject,
        )!!
        assertEquals(pin, record.pin)
    }

    @Test
    fun `a map-pins row without coordinates is dropped`() {
        // The server writes an empty object for a record that never had one.
        assertNull(
            parseLocationPin(
                Json.parseToJsonElement("""{"_id":"p1","file_name":"bridge","location":{}}""") as JsonObject,
            ),
        )
    }

    @Test
    fun `a map-pins row carries its folder and its shortlist`() {
        val row = parseLocationPin(
            Json.parseToJsonElement("""{"_id":"p2","file_name":"Millennium Bridge","scene_number":"14","address":"Bankside","status":"published","location":{"lat":51.5079,"long":-0.0877}}""") as JsonObject,
        )!!
        assertEquals("Millennium Bridge", row.location)
        assertEquals("14", row.sceneNumber)
        assertEquals(LocationStatus.Published, row.status)
        assertEquals(pin, row.pin)
    }
}
