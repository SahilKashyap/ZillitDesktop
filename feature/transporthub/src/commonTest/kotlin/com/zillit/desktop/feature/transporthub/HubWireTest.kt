package com.zillit.desktop.feature.transporthub

import com.zillit.desktop.feature.transporthub.data.shuttleRouteBody
import com.zillit.desktop.feature.transporthub.data.avBody
import com.zillit.desktop.feature.transporthub.data.toActionVehicle
import com.zillit.desktop.feature.transporthub.data.toShuttleRoute
import com.zillit.desktop.feature.transporthub.data.toShuttleRun
import com.zillit.desktop.feature.transporthub.domain.AvDraft
import com.zillit.desktop.feature.transporthub.domain.AvSourceType
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.AvVariant
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteDraft
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteType
import com.zillit.desktop.feature.transporthub.domain.ShuttleRunStatus
import com.zillit.desktop.feature.transporthub.domain.ShuttleStop
import com.zillit.desktop.feature.transporthub.data.record
import com.zillit.desktop.feature.transporthub.data.rows
import com.zillit.desktop.feature.transporthub.data.toDriver
import com.zillit.desktop.feature.transporthub.data.toLocation
import com.zillit.desktop.feature.transporthub.data.toPermanentTrip
import com.zillit.desktop.feature.transporthub.data.toRideRequest
import com.zillit.desktop.feature.transporthub.data.toTrip
import com.zillit.desktop.feature.transporthub.data.toVehicle
import com.zillit.desktop.feature.transporthub.data.toWire
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the hub's wire layer has to survive.
 *
 * Every case here is a shape the service actually answers with, not a
 * hypothetical: the four envelope layouts, `long` for a longitude, dates as
 * epoch-ms strings, references that arrive either populated or as bare ids,
 * and the `{0,0}` fix that means "never reported".
 */
class HubWireTest {

    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    // Envelopes --------------------------------------------------------------

    @Test
    fun `rows reads a bare array`() {
        val data = Json.parseToJsonElement("""[{"_id":"a"},{"_id":"b"}]""")
        assertEquals(2, data.rows().size)
    }

    @Test
    fun `rows reads the paged envelope`() {
        val data = Json.parseToJsonElement(
            """{"data":{"data":[{"_id":"a"}],"pagination":{"total":1}}}""",
        )
        assertEquals(1, data.rows().size)
    }

    @Test
    fun `rows reads a list named after its entity`() {
        val data = Json.parseToJsonElement("""{"vehicles":[{"_id":"a"},{"_id":"b"}]}""")
        assertEquals(2, data.rows("vehicles").size)
    }

    @Test
    fun `rows is empty rather than throwing on an unexpected shape`() {
        assertTrue(Json.parseToJsonElement("""{"message":"nope"}""").rows("vehicles").isEmpty())
        assertTrue(null.rows("vehicles").isEmpty())
    }

    @Test
    fun `record reads a mutation's echo under data`() {
        val data = Json.parseToJsonElement("""{"data":{"_id":"v1","name":"Van"}}""")
        assertEquals("v1", data.record()?.get("_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `record does not mistake a paged list for the record`() {
        val data = Json.parseToJsonElement("""{"data":{"data":[{"_id":"a"}]}}""")
        assertNull(data.record())
    }

    // Locations --------------------------------------------------------------

    @Test
    fun `a location reads the server's long and writes it back`() {
        val parsed = obj("""{"address":"Gate 3","lat":19.07,"long":72.87}""").let {
            Json.parseToJsonElement(it.toString()).toLocation()
        }
        assertEquals(19.07, parsed.lat)
        assertEquals(72.87, parsed.long)
        // `long`, never `lng`: the server rejects the other spelling.
        val wire = parsed.toWire()
        assertTrue("long" in wire.keys)
        assertFalse("lng" in wire.keys)
    }

    @Test
    fun `a location accepts lng from a map picker`() {
        val parsed = Json.parseToJsonElement("""{"address":"x","lat":1.0,"lng":2.0}""").toLocation()
        assertEquals(2.0, parsed.long)
    }

    @Test
    fun `a location that is only a string keeps its address`() {
        val parsed = Json.parseToJsonElement(""""Unit base"""").toLocation()
        assertEquals("Unit base", parsed.address)
        assertFalse(parsed.hasCoordinates)
    }

    // Vehicles ---------------------------------------------------------------

    @Test
    fun `a vehicle reads either spelling of its name and plate`() {
        val legacy = obj("""{"_id":"v1","vehicle_name":"Van 1","vehicle_number":"MH01AB1234"}""")
            .toVehicle()!!
        assertEquals("Van 1", legacy.name)
        assertEquals("MH01AB1234", legacy.licensePlate)
    }

    @Test
    fun `a vehicle with no ownership flag is the production's own`() {
        assertTrue(obj("""{"_id":"v1"}""").toVehicle()!!.isPrivate)
        assertFalse(obj("""{"_id":"v1","is_private":false}""").toVehicle()!!.isPrivate)
    }

    @Test
    fun `a vehicle's allocation parses leniently`() {
        assertEquals(
            VehicleAllocation.Maintenance,
            obj("""{"_id":"v1","allocation_status":"maintenance"}""").toVehicle()!!.allocation,
        )
        // A status this build has not met must not blank the row.
        assertEquals(
            VehicleAllocation.Unknown,
            obj("""{"_id":"v1","allocation_status":"impounded"}""").toVehicle()!!.allocation,
        )
    }

    @Test
    fun `a row without an id is dropped rather than drawn empty`() {
        assertNull(obj("""{"name":"Van"}""").toVehicle())
    }

    // Drivers ----------------------------------------------------------------

    @Test
    fun `a driver reads a populated user reference`() {
        val driver = obj(
            """{"_id":"d1","user_id":{"_id":"u1","full_name":"Asha"},"phone":"900"}""",
        ).toDriver()!!
        assertEquals("u1", driver.userId)
        assertEquals("Asha", driver.fullName)
    }

    @Test
    fun `a driver reads a bare user reference`() {
        val driver = obj("""{"_id":"d1","user_id":"u1","full_name":"Asha"}""").toDriver()!!
        assertEquals("u1", driver.userId)
    }

    @Test
    fun `a zero-zero fix is never reported, not the Gulf of Guinea`() {
        val driver = obj("""{"_id":"d1","last_location":{"lat":0,"long":0}}""").toDriver()!!
        assertNull(driver.lastLocation)
    }

    @Test
    fun `a driver missing a phone or a licence is on the chase-list`() {
        assertTrue(obj("""{"_id":"d1","license_number":"L1"}""").toDriver()!!.detailsIncomplete)
        assertTrue(obj("""{"_id":"d1","phone":"900"}""").toDriver()!!.detailsIncomplete)
        assertFalse(
            obj("""{"_id":"d1","phone":"900","license_number":"L1"}""")
                .toDriver()!!.detailsIncomplete,
        )
    }

    @Test
    fun `a flag sent as a string still reads as a boolean`() {
        // Several of the service's flags arrive quoted.
        assertTrue(obj("""{"_id":"d1","is_on_duty":"true"}""").toDriver()!!.isOnDuty)
        assertFalse(obj("""{"_id":"d1","is_on_duty":"false"}""").toDriver()!!.isOnDuty)
    }

    // Ride requests ----------------------------------------------------------

    @Test
    fun `a ride's in-flight status reads all three spellings`() {
        listOf("in_transit", "in_progress", "in-progress").forEach { wire ->
            assertEquals(
                RideStatus.InTransit,
                obj("""{"_id":"r1","status":"$wire"}""").toRideRequest()!!.status,
                "status $wire",
            )
        }
    }

    @Test
    fun `a ride resolves its owner from the requester snapshot`() {
        val ride = obj(
            """{"_id":"r1","requester_snapshot":{"user_id":"u9","full_name":"Dev"}}""",
        ).toRideRequest()!!
        assertTrue(ride.isOwnedBy("u9"))
        assertFalse(ride.isOwnedBy("u1"))
    }

    @Test
    fun `a ride with no requester falls back to its first passenger`() {
        val ride = obj(
            """{"_id":"r1","passengers":[{"user_id":"u3","name":"Kim"}]}""",
        ).toRideRequest()!!
        assertTrue(ride.isOwnedBy("u3"))
    }

    @Test
    fun `a ride's stops come back in the server's order`() {
        val ride = obj(
            """{"_id":"r1","stops":[
                {"_id":"s2","order":1,"location":{"address":"B"}},
                {"_id":"s1","order":0,"location":{"address":"A"}}
            ]}""",
        ).toRideRequest()!!
        assertEquals(listOf("A", "B"), ride.stops.map { it.location.address })
    }

    // Trips ------------------------------------------------------------------

    @Test
    fun `a trip row says whether it is really a ride request`() {
        val ride = obj("""{"_id":"t1","resource_type":"ride_request"}""").toTrip()!!
        assertEquals(TripResourceKind.RideRequest, ride.kind)
        // A trip with no `resource_type` is a trip: that is what the route owns.
        assertEquals(TripResourceKind.Trip, obj("""{"_id":"t2"}""").toTrip()!!.kind)
    }

    @Test
    fun `a terminal ride request offers no delete`() {
        val done = obj(
            """{"_id":"t1","resource_type":"ride_request","trip_status":"completed"}""",
        ).toTrip()!!
        assertTrue(done.deleteIsPointless)
        val live = obj(
            """{"_id":"t2","resource_type":"ride_request","trip_status":"assigned"}""",
        ).toTrip()!!
        assertFalse(live.deleteIsPointless)
    }

    @Test
    fun `a trip's active days parse from integers, Sunday either way`() {
        val trip = obj("""{"_id":"t1","active_days":[1,2,0]}""").toTrip()!!
        assertEquals(listOf("monday", "tuesday", "sunday"), trip.activeDays.map { it.wire })
        // Sunday is 0 on some rows and 7 on others; both are Sunday.
        val seven = obj("""{"_id":"t2","active_days":[7]}""").toTrip()!!
        assertEquals(listOf("sunday"), seven.activeDays.map { it.wire })
    }

    @Test
    fun `a trip reads its driver from either field`() {
        assertEquals(
            "d1",
            obj("""{"_id":"t1","assigned_driver":{"_id":"d1","full_name":"A"}}""")
                .toTrip()!!.driverId,
        )
        assertEquals("d2", obj("""{"_id":"t2","driver_id":"d2"}""").toTrip()!!.driverId)
    }

    @Test
    fun `a trip status the build has not met does not blank the row`() {
        assertEquals(TripStatus.Unknown, obj("""{"_id":"t1","trip_status":"towed"}""").toTrip()!!.status)
    }

    // Permanent allocations --------------------------------------------------

    @Test
    fun `a fresh draft is a draft even when its runtime status says permanent`() {
        val trip = obj("""{"_id":"p1","status":"draft","trip_status":"permanent"}""")
            .toPermanentTrip()!!
        assertEquals(PermanentState.Draft, trip.state)
    }

    @Test
    fun `the legacy permanent status reads as active`() {
        val trip = obj("""{"_id":"p1","status":"active","trip_status":"permanent"}""")
            .toPermanentTrip()!!
        assertEquals(PermanentState.Active, trip.state)
    }

    @Test
    fun `dates arrive as epoch-ms strings`() {
        val trip = obj("""{"_id":"p1","start_date":"1760000000000","end_date":1761000000000}""")
            .toPermanentTrip()!!
        assertEquals(1_760_000_000_000, trip.startDateMs)
        assertEquals(1_761_000_000_000, trip.endDateMs)
    }

    @Test
    fun `a promote keeps the server's own keys out and the rest in`() {
        val trip = obj(
            """{"_id":"p1","status":"draft","title":"Cast run","self_manage":true,
                "project_id":"proj","created_by":"u1","__v":3,"trip_status":"permanent"}""",
        ).toPermanentTrip()!!
        // The scalars the client does not model still round-trip…
        assertEquals("true", trip.rawFields["self_manage"])
        assertEquals("Cast run", trip.rawFields["title"])
        // …and the server's own keys do not, because echoing them back is
        // what makes the validator refuse the write.
        assertFalse("project_id" in trip.rawFields)
        assertFalse("created_by" in trip.rawFields)
        assertFalse("_id" in trip.rawFields)
        assertFalse("trip_status" in trip.rawFields)
    }

    @Test
    fun `a schedule row with no is_active flag is on`() {
        val trip = obj(
            """{"_id":"p1","schedule":[{"day":"monday","start_time":100},
                {"day":"tuesday","start_time":100,"is_active":false}]}""",
        ).toPermanentTrip()!!
        assertEquals(listOf("monday"), trip.activeDays.map { it.wire })
    }

    @Test
    fun `an empty location writes nothing worth sending`() {
        assertTrue(HubLocation.Empty.isEmpty)
        assertFalse(HubLocation(address = "Base").isEmpty)
        assertFalse(HubLocation(lat = 1.0).isEmpty)
    }

    // Shuttles ---------------------------------------------------------------

    @Test
    fun `a route reads its schedule flat or nested`() {
        val nested = obj(
            """
            {"_id":"r1","name":"Base → Set","type":"cast",
             "schedule":{"frequency":20,"start_time":"06:30","end_time":"21:00",
                         "days_of_week":["1","2","0"]},
             "capacity":12}
            """.trimIndent(),
        ).toShuttleRoute()!!
        assertEquals(20, nested.frequencyMinutes)
        assertEquals("06:30–21:00", nested.timeWindow)
        assertEquals(ShuttleRouteType.Cast, nested.type)
        // `days_of_week` is the JS getDay() numbering — Sunday 0 — which is
        // HubDay's own, so nothing needs remapping.
        assertEquals(listOf(HubDay.Monday, HubDay.Tuesday, HubDay.Sunday), nested.daysActive)

        val flat = obj(
            """{"_id":"r2","name":"A → B","frequency":45,"start_time":"07:00"}""",
        ).toShuttleRoute()!!
        assertEquals(45, flat.frequencyMinutes)
        assertEquals("07:00", flat.timeWindow)
    }

    @Test
    fun `a route is active unless it says otherwise`() {
        assertTrue(obj("""{"_id":"r1"}""").toShuttleRoute()!!.isActive)
        // The list answers snake_case, the update route camelCase.
        assertFalse(obj("""{"_id":"r1","is_active":false}""").toShuttleRoute()!!.isActive)
        assertFalse(obj("""{"_id":"r1","isActive":false}""").toShuttleRoute()!!.isActive)
    }

    @Test
    fun `a route cannot generate runs without both a driver and a vehicle`() {
        val half = obj("""{"_id":"r1","driver_id":"d1"}""").toShuttleRoute()!!
        assertFalse(half.canGenerateRuns)
        val whole = obj(
            """{"_id":"r1","driver_id":"d1","vehicle_id":"v1"}""",
        ).toShuttleRoute()!!
        assertTrue(whole.canGenerateRuns)
    }

    @Test
    fun `a route body nests the schedule and spells the longitude long`() {
        val body = shuttleRouteBody(
            ShuttleRouteDraft(
                name = "Base → Set",
                type = ShuttleRouteType.Crew,
                capacity = 15,
                frequencyMinutes = 30,
                startTime = "06:00",
                endTime = "22:00",
                daysActive = listOf(HubDay.Monday, HubDay.Sunday),
                stops = listOf(
                    ShuttleStop(
                        name = "Gate 3",
                        location = HubLocation(address = "Gate 3", lat = 19.07, long = 72.87),
                    ),
                    // A blank time is omitted, not sent as "": a strict
                    // validator reads the empty string as a bad time.
                    ShuttleStop(name = "Set 4"),
                ),
            ),
        )
        val schedule = body["schedule"] as JsonObject
        assertEquals("30", schedule["frequency"]!!.jsonPrimitive.content)
        assertEquals("[1,0]", schedule["days_of_week"].toString())
        val stops = body["stops"].toString()
        assertTrue(stops.contains("\"long\":72.87"))
        assertTrue(stops.contains("\"estimated_time\":\"\"") == false)
    }

    @Test
    fun `a run reads its populated route and its departure`() {
        val run = obj(
            """
            {"_id":"run1","status":"in-transit","scheduled_departure":1793000000000,
             "route_details":{"_id":"r1","name":"Base → Set","type":"vip"},
             "driver_details":{"full_name":"Asha"},
             "vehicle_details":{"vehicle_name":"Bus 1"},
             "occupancy":4,"capacity":20}
            """.trimIndent(),
        ).toShuttleRun()!!
        assertEquals("Base → Set", run.routeName)
        assertEquals(ShuttleRouteType.Vip, run.routeType)
        // Kebab and snake spellings both arrive for the same status.
        assertEquals(ShuttleRunStatus.InTransit, run.status)
        assertEquals("Asha", run.driverName)
        assertEquals(4, run.occupancy)
    }

    // Action vehicles --------------------------------------------------------

    @Test
    fun `a record collapses the fields that change shape`() {
        // make/model/colour are arrays on a request and scalars once sourced.
        val asRequest = obj(
            """{"_id":"a1","make":["Ford","Chevy"],"category":"hero"}""",
        ).toActionVehicle()!!
        assertEquals("Ford", asRequest.make)
        assertEquals(listOf("hero"), asRequest.categories)

        val asSourced = obj(
            """{"_id":"a1","make":"Ford","category":["hero","stunt"]}""",
        ).toActionVehicle()!!
        assertEquals("Ford", asSourced.make)
        assertEquals(listOf("hero", "stunt"), asSourced.categories)
    }

    @Test
    fun `a record reads its rounds and the viewer's place in them`() {
        val vehicle = obj(
            """
            {"_id":"a1","display_name":"Hero Car","status":"pending_approval",
             "approvers":[{"user_id":"u1","name":"Dana","status":"approved"},
                          {"user_id":"u2","name":"Sam"}],
             "my":{"is_approver":true,"in_comments":true},
             "comments":[{"id":"c1","text":"ok","created_at":1000,"updated_at":1000}]}
            """.trimIndent(),
        ).toActionVehicle()!!
        assertEquals(AvStatus.PendingApproval, vehicle.status)
        assertTrue(vehicle.my.isApprover)
        assertTrue(vehicle.my.inComments)
        // An absent status is pending, not approved.
        assertEquals("pending", vehicle.approvers[1].status)
        // `updated_at` equal to `created_at` is creation, not an edit — the
        // record writes the two together.
        assertEquals(0, vehicle.comments.single().editedMs)
    }

    @Test
    fun `a comment counts as edited only when updated is meaningfully later`() {
        val holder = obj(
            """{"_id":"a1","comments":[{"id":"c1","text":"x","created_at":1000,"updated_at":5000}]}""",
        ).toActionVehicle()!!
        assertEquals(5000, holder.comments.single().editedMs)
    }

    @Test
    fun `a requester's body carries options and no sourced car`() {
        val body = avBody(
            AvDraft(
                displayName = "Hero Car",
                variants = listOf(AvVariant(make = "Ford", model = "Mustang", year = 1968)),
                make = "ignored",
                isRequesterSubmit = true,
            ),
        )
        assertEquals("", body["make"]!!.jsonPrimitive.content)
        assertTrue(body["variants"].toString().contains("Mustang"))
    }

    @Test
    fun `a production-owned car clears the terms rather than omitting them`() {
        val body = avBody(
            AvDraft(
                displayName = "Hero Car",
                make = "Ford",
                sourceType = AvSourceType.ProductionOwned,
                ownerName = "Was rented from",
                rate = 500.0,
            ),
        )
        // Switching a rental to owned has to erase what was agreed with the
        // owner; an omitted field would leave the old terms in place.
        assertEquals("", body["owner_name"]!!.jsonPrimitive.content)
        assertEquals("null", body["rate"].toString())
    }
}
