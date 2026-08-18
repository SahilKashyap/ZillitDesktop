@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.transportation

import com.zillit.desktop.feature.transportation.data.parseTrip
import com.zillit.desktop.feature.transportation.data.parseUser
import com.zillit.desktop.feature.transportation.data.parseVehicle
import com.zillit.desktop.feature.transportation.data.permanentWire
import com.zillit.desktop.feature.transportation.data.tripUpdateWire
import com.zillit.desktop.feature.transportation.data.tripWire
import com.zillit.desktop.feature.transportation.data.vehicleWire
import com.zillit.desktop.feature.transportation.domain.AllocationType
import com.zillit.desktop.feature.transportation.domain.GeoPlace
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripDraft
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripPriority
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportWireTest {

    private val passenger = TripPassenger(
        userId = "u1",
        name = "Aisha",
        designation = "1st AD",
        pickupMs = 2_000L,
        pickupTimeText = "8:30 am",
        pickup = GeoPlace("Gate", 51.5, -0.1),
        dropOff = GeoPlace("Set", null, null),
    )

    @Test
    fun `vehicle body is snake_case with vehicleId on update and the number upper-cased`() {
        val body = vehicleWire(VehicleDraft("Toyota", "ab12 cde", "Car", 4, "Owner", "0770", "Addr", "+44"), id = "v1")
        assertEquals("AB12 CDE", body["vehicle_number"]!!.jsonPrimitive.content)
        assertEquals("4", body["seating_capacity"]!!.jsonPrimitive.content)
        assertEquals("v1", body["vehicleId"]!!.jsonPrimitive.content)
        assertNull(vehicleWire(VehicleDraft("T", "N", "Car", 4), id = null)["vehicleId"])
    }

    @Test
    fun `a coordinator's request is assigned with vehicle and driver, anyone else's is pending without`() {
        val draft = TripDraft(TripPriority.High, selfAssign = true, listOf(passenger), listOf("cc1"), "v1", "d1")
        val coordinator = tripWire(draft, coordinator = true)
        assertEquals("assigned", coordinator["trip_status"]!!.jsonPrimitive.content)
        assertEquals("self", coordinator["mode"]!!.jsonPrimitive.content)
        assertEquals("v1", coordinator["vehicle_id"]!!.jsonPrimitive.content)
        val p = coordinator["passengers"]!!.jsonArray[0].jsonObject
        assertEquals("-0.1", p["pickup_locations"]!!.jsonObject["long"]!!.jsonPrimitive.content)
        assertEquals("8:30 am", p["pickup_time_string"]!!.jsonPrimitive.content)

        val crew = tripWire(draft, coordinator = false)
        assertEquals("pending", crew["trip_status"]!!.jsonPrimitive.content)
        assertNull(crew["vehicle_id"])
        assertNull(crew["driver_id"])
    }

    @Test
    fun `trip update stamps times, sets status only on a move, and sends vehicle or driver only when changed`() {
        val trip = parseTrip(
            Json.parseToJsonElement(
                """{"_id":"t1","trip_status":"assigned","vehicle_id":"v1","driver_id":"d1","start_time":0,"end_time":0,"passengers":[]}""",
            ) as JsonObject,
        )!!
        val start = tripUpdateWire(
            TripUpdate("t1", TripAction.Start, listOf(passenger), emptyList(), trip, vehicleId = "v1", driverId = "d2"),
            nowMs = 9_000L,
        )
        assertEquals("inprogress", start["trip_status"]!!.jsonPrimitive.content)
        assertEquals("9000", start["start_time"]!!.jsonPrimitive.content)
        assertNull(start["vehicle_id"])
        assertEquals("d2", start["driver_id"]!!.jsonPrimitive.content)

        val update = tripUpdateWire(
            TripUpdate("t1", TripAction.Update, listOf(passenger), emptyList(), trip, vehicleId = "v1", driverId = "d1"),
            nowMs = 9_000L,
        )
        assertNull(update["trip_status"])
        assertEquals("0", update["start_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `permanent body keeps absent keys absent and drafts as draft`() {
        val draft = PermanentDraft(listOf(PermanentPassenger("u1", "Aisha")), emptyList(), null, null, 0L, 0L, fullDay = false, asDraft = true)
        val body = permanentWire(draft, id = null, status = null)
        assertEquals("draft", body["trip_status"]!!.jsonPrimitive.content)
        assertNull(body["vehicle_id"])
        assertNull(body["start_time"])
        assertEquals("", body["passengers"]!!.jsonArray[0].jsonObject["pickup_name"]!!.jsonPrimitive.content)
        assertNull(draft.problem(null))
        val live = draft.copy(asDraft = false)
        assertEquals("A start date is required", live.problem(null))
        val submitted = permanentWire(live.copy(startMs = 5L, vehicleId = "v", driverId = "d"), id = "p1", status = PermanentStatus.Permanent)
        assertEquals("p1", submitted["tripRequestId"]!!.jsonPrimitive.content)
        assertEquals("permanent", submitted["trip_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `records parse — soft-deleted vehicles, seconds-valued pickups, driver flags`() {
        val vehicle = parseVehicle(
            Json.parseToJsonElement("""{"_id":"v","vehicle_name":"T","allocation_type":"assigned","deleted":0,"is_private":true}""") as JsonObject,
        )!!
        assertEquals(AllocationType.Assigned, vehicle.allocation)
        assertTrue(vehicle.isLive)
        assertTrue(!vehicle.deletable)

        val trip = parseTrip(
            Json.parseToJsonElement("""{"_id":"t","trip_status":"inprogress","passengers":[{"user_id":"u","pickup_time":1700000000}]}""") as JsonObject,
        )!!
        assertEquals(TripStatus.InProgress, trip.status)
        assertEquals(1_700_000_000_000L, trip.passengers[0].pickupMs)

        val user = parseUser(
            Json.parseToJsonElement("""{"user_id":"u","first_name":"A","last_name":"B","designation_name":"Driver","status":"accepted","is_temp_driver":true}""") as JsonObject,
        )!!
        assertEquals("A B", user.fullName)
        assertTrue(user.isDriver(emptyList()))
    }
}
