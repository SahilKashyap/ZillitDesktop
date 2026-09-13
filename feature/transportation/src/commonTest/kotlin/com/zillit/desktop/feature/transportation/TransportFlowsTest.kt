package com.zillit.desktop.feature.transportation

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.transportation.data.driverDetailsWire
import com.zillit.desktop.feature.transportation.data.parseUser
import com.zillit.desktop.feature.transportation.data.parseVehicle
import com.zillit.desktop.feature.transportation.data.tempDriverVehicleWire
import com.zillit.desktop.feature.transportation.domain.AllocationType
import com.zillit.desktop.feature.transportation.domain.DriverDetailsUpdate
import com.zillit.desktop.feature.transportation.domain.GeoPlace
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportRepository
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripDraft
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import com.zillit.desktop.feature.transportation.ui.AllocationFilter
import com.zillit.desktop.feature.transportation.ui.AssignTarget
import com.zillit.desktop.feature.transportation.ui.DriverDetailsEditor
import com.zillit.desktop.feature.transportation.ui.DriverFilter
import com.zillit.desktop.feature.transportation.ui.TransportConfirm
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState
import com.zillit.desktop.feature.transportation.ui.TransportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web's modal rules, held by the desktop's one view model: who a picked
 * vehicle brings along, who a picked driver pushes out, which act asks first,
 * what a save refuses, and who each driver list shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportFlowsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val now = 1_789_000_000_000L

    @Suppress("LongParameterList") // A fixture row, column for column.
    private fun user(id: String, name: String, des: String = "director_label", temp: Boolean = false,
        vehicle: String? = null, assigned: Boolean = false, perm: Boolean = false, fullDay: Boolean = false,
        status: String = "accepted") =
        TransportUser(id, name, des, "d", status, "", temp, vehicle, assigned, perm, fullDay)

    private val me = user("me", "Me", "transportation_coordinator_label")
    private val driverA = user("dA", "Driver A", "driver_label", vehicle = "v1")
    private val driverB = user("dB", "Driver B", "driver_label", assigned = true)
    private val driverC = user("dC", "Driver C", "driver_label", perm = true, fullDay = true)
    private val temp = user("tmp", "Temp", "producer_label", temp = true, vehicle = "vp")
    private val pax = user("p1", "Pax One")
    private val crew = listOf(me, driverA, driverB, driverC, temp, pax, user("gone", "Gone", status = "left"))
    private val vehicles = listOf(
        Vehicle("v1", "Innova", "MH1", "SUV", 6, "", "", "", "", AllocationType.Remained, "dA", false, 0L),
        Vehicle("vp", "Own", "DL1", "Car", 4, "", "", "", "", AllocationType.Remained, "tmp", true, 0L),
    )

    @Suppress("TooManyFunctions")
    private class Fake(val crew: List<TransportUser>, val vehicles: List<Vehicle>) : TransportRepository {
        val driverUpdates = mutableListOf<DriverDetailsUpdate>()
        val deleted = mutableListOf<List<String>>()
        val tripUpdates = mutableListOf<TripUpdate>()
        val raised = mutableListOf<TripDraft>()
        override suspend fun vehicles() = ZillitResult.Success(vehicles)
        override suspend fun vehicleTypes() = ZillitResult.Success(listOf("Car"))
        override suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?> = ZillitResult.Success(null)
        override suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?> =
            ZillitResult.Success(null)
        override suspend fun deleteVehicles(ids: List<String>): ZillitResult<Unit> {
            deleted += ids
            return ZillitResult.Success(Unit)
        }
        override suspend fun driverDesignations() = ZillitResult.Success(listOf("driver_label"))
        override suspend fun crew() = ZillitResult.Success(crew)
        override suspend fun postingRightUsers() = ZillitResult.Success(listOf("me"))
        override suspend fun updateDriverDetails(update: DriverDetailsUpdate): ZillitResult<Unit> {
            driverUpdates += update
            return ZillitResult.Success(Unit)
        }
        override suspend fun pendingDocumentReminder(userId: String, type: String, message: String?) =
            ZillitResult.Success(Unit)
        override suspend fun trips(status: TripStatus, asDriver: Boolean, userId: String, coordinator: Boolean):
            ZillitResult<List<TripRequest>> = ZillitResult.Success(emptyList())
        override suspend fun raiseTrip(draft: TripDraft, coordinator: Boolean): ZillitResult<Unit> {
            raised += draft
            return ZillitResult.Success(Unit)
        }
        override suspend fun updateTrip(update: TripUpdate, nowMs: Long): ZillitResult<Unit> {
            tripUpdates += update
            return ZillitResult.Success(Unit)
        }
        override suspend fun sendReminder(tripId: String) = ZillitResult.Success(Unit)
        override suspend fun permanentTrips(asDriver: Boolean, userId: String, coordinator: Boolean,
            status: PermanentStatus?): ZillitResult<List<PermanentTrip>> = ZillitResult.Success(emptyList())
        override suspend fun myPermanentTrips(asDriver: Boolean, userId: String): ZillitResult<List<PermanentTrip>> =
            ZillitResult.Success(emptyList())
        override suspend fun createPermanent(draft: PermanentDraft) = ZillitResult.Success(Unit)
        override suspend fun updatePermanent(id: String, draft: PermanentDraft, status: PermanentStatus) =
            ZillitResult.Success(Unit)
        override suspend fun unassignPermanent(trip: PermanentTrip) = ZillitResult.Success(Unit)
        override suspend fun deletePermanent(id: String) = ZillitResult.Success(Unit)
        override suspend fun setSelfManage(id: String, selfManage: Boolean) = ZillitResult.Success(Unit)
    }

    private fun started(coordinator: Boolean = true): Pair<TransportViewModel, Fake> {
        val fake = Fake(crew, vehicles)
        val model = TransportViewModel(
            repository = fake,
            resolveViewer = { TransportViewer(userId = "me", canPost = coordinator, ready = true) },
            nowMillis = { now },
        )
        model.start()
        dispatcher.scheduler.runCurrent()
        return model to fake
    }

    private fun passenger(id: String, name: String = id, at: Long = now + 3_600_000L) =
        TripPassenger(id, name, "d", at, "", GeoPlace("A", 1.0, 2.0), GeoPlace("B", 3.0, 4.0))

    // Pickers ----------------------------------------------------------------

    @Test
    fun `a picked vehicle brings its driver, and a picked driver leaves the passenger and CC lists`() =
        runTest(dispatcher) {
        val (model, _) = started()
        model.onEvent(TransportEvent.NewRequest)
        model.onEvent(TransportEvent.OpenVehiclePicker(AssignTarget.Raise))
        model.onEvent(TransportEvent.PickVehicle("v1"))
        assertEquals("dA", model.currentState.raise?.driverId, "the vehicle's driver rides along")
        assertNull(model.currentState.vehiclePicker)

        model.onEvent(TransportEvent.RaiseChanged(model.currentState.raise!!.copy(
            passengers = listOf(passenger("dB")), ccUsers = listOf("p1", "dB"))))
        model.onEvent(TransportEvent.OpenDriverPicker(AssignTarget.Raise))
        model.onEvent(TransportEvent.PickDriver("dB"))
        val raise = model.currentState.raise!!
        assertEquals("dB", raise.driverId)
        assertTrue(raise.passengers.none { it.userId == "dB" }, "a driver cannot also ride")
        assertEquals(listOf("p1"), raise.ccUsers)
    }

    @Test
    fun `changing a private vehicle's own driver asks first`() = runTest(dispatcher) {
        val (model, _) = started()
        model.onEvent(TransportEvent.NewRequest)
        model.onEvent(TransportEvent.RaiseChanged(model.currentState.raise!!.copy(vehicleId = "vp", driverId = "tmp")))
        model.onEvent(TransportEvent.OpenDriverPicker(AssignTarget.Raise))
        assertTrue(model.currentState.confirm is TransportConfirm.ChangePrivateDriver)
        assertNull(model.currentState.driverPicker)
        model.onEvent(TransportEvent.AcceptConfirm)
        assertEquals(AssignTarget.Raise, model.currentState.driverPicker)
    }

    // Raise ------------------------------------------------------------------

    @Test
    fun `self assign opens the passenger form on the viewer, and the request is raised in self mode`() =
        runTest(dispatcher) {
            val (model, fake) = started()
            model.onEvent(TransportEvent.NewRequest)
            model.onEvent(TransportEvent.RaiseSelfAssign(true))
            val dialog = assertNotNull(model.currentState.passengerDialog)
            assertEquals("me", dialog.userId)
            assertTrue(dialog.selfAssign)
            model.onEvent(TransportEvent.PassengerChanged(dialog.copy(dateYmd = "2026-09-20", time = "09:30",
                pickupAddress = "A", pickupLat = "1", pickupLng = "2", dropAddress = "B", dropLat = "3",
                dropLng = "4")))
            model.onEvent(TransportEvent.SubmitPassenger)
            assertNull(model.currentState.passengerDialog)
            assertEquals(listOf("me"), model.currentState.raise?.passengers?.map { it.userId })

            val raise = assertNotNull(model.currentState.raise)
            model.onEvent(TransportEvent.RaiseChanged(raise.copy(vehicleId = "v1", driverId = "dA")))
            model.onEvent(TransportEvent.SubmitRaise)
            runCurrent()
            assertTrue(fake.raised.single().selfAssign)
            assertNull(model.currentState.raise)
            assertEquals(TripStatus.Assigned, model.currentState.tripStatus, "a coordinator's request lands assigned")
        }

    @Test
    fun `a passenger needs a future time and both places with coordinates`() = runTest(dispatcher) {
        val (model, _) = started()
        model.onEvent(TransportEvent.NewRequest)
        model.onEvent(TransportEvent.OpenPassenger(forOpenTrip = false))
        val dialog = assertNotNull(model.currentState.passengerDialog)
        val filled = dialog.copy(userId = "p1", dateYmd = "2026-01-01", time = "09:00", pickupAddress = "A",
            pickupLat = "1", pickupLng = "2", dropAddress = "B", dropLat = "3", dropLng = "4")
        model.onEvent(TransportEvent.PassengerChanged(filled))
        model.onEvent(TransportEvent.SubmitPassenger)
        assertEquals("The pickup time is in the past", model.currentState.error)

        model.onEvent(TransportEvent.PassengerChanged(filled.copy(dateYmd = "2030-01-01", pickupLat = "")))
        model.onEvent(TransportEvent.SubmitPassenger)
        assertEquals("Pickup and drop-off need latitude and longitude (from Google Maps)", model.currentState.error)

        model.onEvent(TransportEvent.PassengerChanged(filled.copy(dateYmd = "2030-01-01")))
        model.onEvent(TransportEvent.SubmitPassenger)
        assertNull(model.currentState.passengerDialog)
        assertEquals("p1", model.currentState.raise?.passengers?.single()?.userId)
    }

    // Trip acts --------------------------------------------------------------

    private val pending = TripRequest("t1", "p1", "High", TripStatus.Pending, "others", listOf(passenger("p1")),
        emptyList(), null, null, 0, 0, now)

    @Test
    fun `approve refuses without a vehicle and driver, reject asks first and then goes through`() =
        runTest(dispatcher) {
        val (model, fake) = started()
        model.onEvent(TransportEvent.OpenTrip(pending))
        model.onEvent(TransportEvent.TripAct(TripAction.Approve))
        assertEquals("Assign a vehicle", model.currentState.error)
        assertTrue(fake.tripUpdates.isEmpty())

        model.onEvent(TransportEvent.TripAct(TripAction.Reject))
        assertEquals(TripAction.Reject, model.currentState.openTrip?.confirm, "a reject asks first")
        assertTrue(fake.tripUpdates.isEmpty())
        model.onEvent(TransportEvent.TripAct(TripAction.Reject))
        runCurrent()
        assertEquals(TripAction.Reject, fake.tripUpdates.single().action)
        assertNull(model.currentState.openTrip)
    }

    @Test
    fun `an edited passenger list travels with the update, and the driver may not ride`() =
        runTest(dispatcher) {
        val (model, fake) = started()
        model.onEvent(TransportEvent.OpenTrip(pending.copy(status = TripStatus.Assigned, vehicleId = "v1",
            driverId = "dA")))
        model.onEvent(TransportEvent.OpenPassenger(forOpenTrip = true))
        val dialog = assertNotNull(model.currentState.passengerDialog)
        assertTrue(dialog.forOpenTrip)
        model.onEvent(TransportEvent.PassengerChanged(dialog.copy(userId = "dA", dateYmd = "2030-01-01", time = "10:00",
            pickupAddress = "A", pickupLat = "1", pickupLng = "2", dropAddress = "B", dropLat = "3", dropLng = "4")))
        model.onEvent(TransportEvent.SubmitPassenger)
        model.onEvent(TransportEvent.TripAct(TripAction.Update))
        assertEquals("Driver and passengers cannot be the same", model.currentState.error)

        model.onEvent(TransportEvent.TripRemovePassenger("dA"))
        model.onEvent(TransportEvent.TripAct(TripAction.Update))
        runCurrent()
        assertEquals(listOf("p1"), fake.tripUpdates.single().passengers.map { it.userId })
    }

    // Drivers ----------------------------------------------------------------

    @Test
    fun `making a temporary driver asks about their car, and unmaking one deletes it`() =
        runTest(dispatcher) {
        val (model, fake) = started()
        model.onEvent(TransportEvent.OpenTempDrivers)
        model.onEvent(TransportEvent.ToggleTempDriver(pax, on = true))
        assertTrue(model.currentState.confirm is TransportConfirm.TempDriverVehicle)
        model.onEvent(TransportEvent.DeclineConfirm)
        runCurrent()
        assertEquals(true, fake.driverUpdates.single().isTempDriver, "No still makes them a driver, carless")
        assertEquals("p1", fake.driverUpdates.single().userId)

        model.onEvent(TransportEvent.ToggleTempDriver(pax, on = true))
        model.onEvent(TransportEvent.AcceptConfirm)
        assertEquals("p1", model.currentState.vehicleEditor?.forTempDriverId, "Yes opens their vehicle's form")

        model.onEvent(TransportEvent.ToggleTempDriver(temp, on = false))
        runCurrent()
        assertEquals(false, fake.driverUpdates.last().isTempDriver)
        assertEquals(listOf(listOf("vp")), fake.deleted, "their private car leaves with them")
    }

    @Test
    fun `the coordinator's save is the vehicle, the driver's save is their details`() = runTest(dispatcher) {
        val (model, fake) = started()
        model.onEvent(TransportEvent.OpenDriver(driverB))
        model.onEvent(TransportEvent.SaveDriverDetails)
        assertEquals("Assign a vehicle", model.currentState.error)

        model.onEvent(TransportEvent.OpenVehiclePicker(AssignTarget.DriverDetails))
        model.onEvent(TransportEvent.PickVehicle("v1"))
        model.onEvent(TransportEvent.SaveDriverDetails)
        runCurrent()
        val coordinatorSave = fake.driverUpdates.single()
        assertEquals("v1", coordinatorSave.vehicleId)
        assertNull(coordinatorSave.phone, "the coordinator does not write the driver's phone")
        assertNull(model.currentState.driverDetails)

        val self = DriverDetailsEditor.from(driverB, self = true, coordinator = false)
            .copy(phone = "12345", countryCode = "+91", address = "Home",
                documents = listOf(StoredMedia(media = "k", name = "insurance.pdf", contentType = "application/pdf")))
        model.onEvent(TransportEvent.DriverDetailsChanged(self))
        model.onEvent(TransportEvent.SaveDriverDetails)
        runCurrent()
        val driverSave = fake.driverUpdates.last()
        assertEquals("12345", driverSave.phone)
        assertEquals("+91", driverSave.countryCode)
        assertEquals(1, driverSave.documents?.size)
        assertNull(driverSave.vehicleId, "the driver does not assign their own vehicle")
        assertNotNull(model.currentState.driverDetails, "the driver's page stays open after a save")
    }

    @Test
    fun `the driver lists split by vehicle and by allocation, and search narrows them`() {
        val state = TransportUiState(viewer = TransportViewer("me", canPost = true, ready = true), crew = crew,
            driverDesignations = listOf("driver_label"))
        assertEquals(listOf("dA", "dB", "dC", "tmp"), state.filteredDrivers().map { it.userId })
        assertEquals(listOf("dA", "tmp"), state.copy(driverFilter = DriverFilter.WithVehicle).filteredDrivers()
            .map { it.userId })
        assertEquals(listOf("dB", "dC"), state.copy(driverFilter = DriverFilter.WithoutVehicle).filteredDrivers()
            .map { it.userId })
        val allocated = state.copy(driverFilter = DriverFilter.Allocated)
        assertEquals(listOf("dC"), allocated.filteredDrivers().map { it.userId })
        assertEquals(listOf("dB"), allocated.copy(allocationFilter = AllocationFilter.ForJob).filteredDrivers()
            .map { it.userId })
        assertEquals(listOf("dA", "tmp"), allocated.copy(allocationFilter = AllocationFilter.Available)
            .filteredDrivers().map { it.userId })
        assertEquals(listOf("dB"), state.copy(driverQuery = "driver b").filteredDrivers().map { it.userId })
    }

    @Test
    fun `an unknown rights list keeps every candidate, a known one narrows them`() {
        val open = TransportUiState(viewer = TransportViewer("me", ready = true), crew = crew)
        assertTrue(open.passengerCandidates.any { it.userId == "p1" })
        assertFalse(open.passengerCandidates.any { it.userId == "gone" }, "someone who left is never offered")
        val narrowed = open.copy(viewingRightIds = setOf("dA"))
        assertEquals(listOf("dA"), narrowed.passengerCandidates.map { it.userId })
    }

    // Wire -------------------------------------------------------------------

    @Test
    fun `driver details send only the keys that were set, and the temp driver's car carries its flags`() {
        val body = driverDetailsWire(DriverDetailsUpdate("u", phone = "123", licencePictures = listOf(
            StoredMedia(media = "f", caption = "Front"))))
        assertEquals("123", body["phone"]?.jsonPrimitive?.content)
        assertNull(body["address"])
        assertNull(body["documents"])
        assertEquals("Front", body["license_picture"]!!.jsonArray[0].jsonObject["caption"]?.jsonPrimitive?.content)

        val car = tempDriverVehicleWire("u", VehicleDraft("Own", "dl1", "Car", 4))
        assertEquals("true", car["is_private"]?.jsonPrimitive?.content)
        assertEquals("true", car["is_temp_driver"]?.jsonPrimitive?.content)
        assertEquals("DL1", car["vehicle_number"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a crew row reads its face, licence, documents and reminder, a vehicle its photographs`() {
        val row = Json.parseToJsonElement(
            """{"user_id":"u","full_name":"A","status":"accepted","profile_picture":{"media":"pp","bucket":"b"},
               "license_picture":[{"media":"lf","caption":"Front"}],"documents":[{"media":"d","name":"x.pdf",
               "content_type":"application/pdf"}],"trip_reminder_message":"PUC","address":"Home",
               "last_location":{"lat":0,"long":0}}""",
        ) as JsonObject
        val user = assertNotNull(parseUser(row))
        assertEquals("pp", user.avatar?.media)
        assertEquals("Front", user.licencePictures.single().caption)
        assertFalse(user.documents.single().isImage)
        assertEquals("PUC", user.tripReminderMessage)
        assertNull(user.lastLocation, "0,0 is never reported, not a place")

        val vehicle = assertNotNull(parseVehicle(Json.parseToJsonElement(
            """{"_id":"v","attachment":[{"media":"a.jpg","content_type":"image/jpeg"},{"nope":1}]}""") as JsonObject))
        assertEquals(1, vehicle.attachments.size)
        assertTrue(vehicle.attachments.single().isImage)
    }

    @Test
    fun `the open trip keeps a driver already named when a vehicle is picked`() = runTest(dispatcher) {
        val (model, _) = started()
        model.onEvent(TransportEvent.OpenTrip(pending.copy(driverId = "dB")))
        model.onEvent(TransportEvent.OpenVehiclePicker(AssignTarget.Trip))
        model.onEvent(TransportEvent.PickVehicle("v1"))
        assertEquals("v1", model.currentState.openTrip?.vehicleId)
        assertEquals("dB", model.currentState.openTrip?.driverId, "a named driver keeps the seat")

        model.onEvent(TransportEvent.OpenTrip(pending))
        model.onEvent(TransportEvent.OpenVehiclePicker(AssignTarget.Trip))
        model.onEvent(TransportEvent.PickVehicle("v1"))
        assertEquals("dA", model.currentState.openTrip?.driverId, "an empty seat takes the vehicle's driver")
    }
}
