package com.zillit.desktop.feature.transportation

import com.zillit.desktop.feature.transportation.domain.AllocationType
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import com.zillit.desktop.feature.transportation.ui.TransportUiState
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transport forms' own rules, held at their boundaries.
 *
 * These mirror the web's validators field for field and in its order, so a
 * coordinator filling the same form on either client is stopped at the same
 * place with the same words. The boundaries matter most: a 20-character
 * brand name is the longest the service stores, and letting a 21st through
 * means a silently truncated vehicle nobody can find by number afterwards.
 */
class TransportValidationTest {

    private fun vehicle(
        name: String = "Transit",
        number: String = "AB12 CDE",
        seats: Int = 8,
        ownerName: String = "",
        ownerContact: String = "",
        ownerAddress: String = "",
    ) = VehicleDraft(
        name = name,
        number = number,
        type = "Van",
        seats = seats,
        ownerName = ownerName,
        ownerContact = ownerContact,
        ownerAddress = ownerAddress,
    )

    @Test
    fun `a filled vehicle has no problem`() {
        assertNull(vehicle().problem())
    }

    @Test
    fun `the brand name is required before its length is judged`() {
        assertEquals("A brand name is required", vehicle(name = "").problem())
        assertEquals("A brand name is required", vehicle(name = "   ").problem())
    }

    @Test
    fun `the brand name stops at twenty characters`() {
        assertNull(vehicle(name = "a".repeat(20)).problem(), "twenty is allowed")
        assertEquals(
            "The brand name is at most 20 characters",
            vehicle(name = "a".repeat(21)).problem(),
        )
    }

    @Test
    fun `the vehicle number is required and capped alike`() {
        assertEquals("A vehicle number is required", vehicle(number = " ").problem())
        assertNull(vehicle(number = "b".repeat(20)).problem())
        assertEquals(
            "The vehicle number is at most 20 characters",
            vehicle(number = "b".repeat(21)).problem(),
        )
    }

    @Test
    fun `seats run from two to a hundred inclusive`() {
        assertNull(vehicle(seats = 2).problem())
        assertNull(vehicle(seats = 100).problem())
        assertEquals("Seats must be between 2 and 100", vehicle(seats = 1).problem())
        assertEquals("Seats must be between 2 and 100", vehicle(seats = 101).problem())
        assertEquals("Seats must be between 2 and 100", vehicle(seats = 0).problem())
    }

    /** A one-seat vehicle carries only its driver, which is not a trip. */
    @Test
    fun `a single seat is refused rather than rounded up`() {
        assertEquals("Seats must be between 2 and 100", vehicle(seats = 1).problem())
    }

    @Test
    fun `owner details are optional but bounded`() {
        assertNull(vehicle(ownerName = "", ownerAddress = "").problem())
        assertNull(vehicle(ownerName = "o".repeat(40)).problem())
        assertEquals(
            "The owner name is at most 40 characters",
            vehicle(ownerName = "o".repeat(41)).problem(),
        )
        assertNull(vehicle(ownerAddress = "a".repeat(50)).problem())
        assertEquals(
            "The address is at most 50 characters",
            vehicle(ownerAddress = "a".repeat(51)).problem(),
        )
    }

    /**
     * The contact number is judged only when given — an empty box is a
     * skipped optional field, not a four-character failure.
     */
    @Test
    fun `a blank contact number is skipped, a short one is not`() {
        assertNull(vehicle(ownerContact = "").problem())
        assertEquals(
            "The contact number is 5 to 20 characters",
            vehicle(ownerContact = "0123").problem(),
        )
        assertNull(vehicle(ownerContact = "01234").problem())
        assertNull(vehicle(ownerContact = "0".repeat(20)).problem())
        assertEquals(
            "The contact number is 5 to 20 characters",
            vehicle(ownerContact = "0".repeat(21)).problem(),
        )
    }

    @Test
    fun `the first problem in the form's order is the one reported`() {
        val everythingWrong = vehicle(name = "", number = "", seats = 0, ownerName = "o".repeat(41))

        assertEquals("A brand name is required", everythingWrong.problem(), "name comes first")
    }

    private fun permanent(
        passengers: List<PermanentPassenger> = listOf(PermanentPassenger("u1", "Ada")),
        vehicleId: String? = "v1",
        driverId: String? = "d1",
        startMs: Long = 1_700_000_000_000,
        endMs: Long = 0L,
        asDraft: Boolean = false,
    ) = PermanentDraft(
        passengers = passengers,
        ccUsers = emptyList(),
        vehicleId = vehicleId,
        driverId = driverId,
        startMs = startMs,
        endMs = endMs,
        fullDay = true,
        asDraft = asDraft,
    )

    @Test
    fun `a complete permanent request has no problem`() {
        assertNull(permanent().problem(vehicleSeats = 8))
    }

    /** A draft is a parking place for an unfinished form, so nothing is demanded of it. */
    @Test
    fun `a draft skips every rule`() {
        val empty = permanent(
            passengers = emptyList(),
            vehicleId = null,
            driverId = null,
            startMs = 0L,
            asDraft = true,
        )

        assertNull(empty.problem(vehicleSeats = null))
    }

    @Test
    fun `the permanent rules are reported in the form's order`() {
        assertEquals("A start date is required", permanent(startMs = 0L).problem(8))
        assertEquals("Add at least one passenger", permanent(passengers = emptyList()).problem(8))
        assertEquals("Assign a vehicle", permanent(vehicleId = null).problem(8))
        assertEquals("Assign a driver", permanent(driverId = null).problem(8))
    }

    @Test
    fun `passengers may not outnumber the seats`() {
        val four = (1..4).map { PermanentPassenger("u$it", "Rider $it") }

        assertNull(permanent(passengers = four).problem(vehicleSeats = 4), "exactly full is fine")
        assertEquals(
            "The vehicle has too few seats for these passengers",
            permanent(passengers = four).problem(vehicleSeats = 3),
        )
    }

    /** An uncosted vehicle is not checked against, rather than treated as seatless. */
    @Test
    fun `an unknown seat count does not block the request`() {
        val many = (1..40).map { PermanentPassenger("u$it", "Rider $it") }

        assertNull(permanent(passengers = many).problem(vehicleSeats = null))
    }

    @Test
    fun `an end date before the start is refused, and an absent one is allowed`() {
        assertNull(permanent(endMs = 0L).problem(8), "open-ended is a permanent allocation")
        assertNull(permanent(startMs = 1_000, endMs = 1_000).problem(8), "same day is allowed")
        assertEquals(
            "The end date cannot be before the start date",
            permanent(startMs = 2_000, endMs = 1_000).problem(8),
        )
    }

    @Test
    fun `allocation type falls back to remained, not to permanent`() {
        assertEquals(AllocationType.Permanent, AllocationType.fromWire("permanent"))
        assertEquals(AllocationType.Permanent, AllocationType.fromWire("PERMANENT"))
        assertEquals(AllocationType.Remained, AllocationType.fromWire("elsewhere"))
        assertEquals(AllocationType.Remained, AllocationType.fromWire(null))
    }

    private fun user(
        designation: String = "Runner",
        isTempDriver: Boolean = false,
        status: String = "accepted",
        assigned: Boolean = false,
        permanent: Boolean = false,
    ) = TransportUser(
        userId = "u1",
        fullName = "Ada",
        designation = designation,
        department = "Production",
        status = status,
        phone = "",
        isTempDriver = isTempDriver,
        vehicleId = null,
        isTripAssigned = assigned,
        permanentTrip = permanent,
        fullDayTrip = false,
    )

    @Test
    fun `a driver is one by designation or by being drafted in`() {
        val designations = listOf("Driver", "Unit Driver")

        assertTrue(user(designation = "driver").isDriver(designations), "case is ignored")
        assertTrue(user(designation = "Unit Driver").isDriver(designations))
        assertFalse(user(designation = "Runner").isDriver(designations))
        assertTrue(
            user(designation = "Runner", isTempDriver = true).isDriver(designations),
            "a temporary driver drives whatever their designation says",
        )
    }

    @Test
    fun `with no driver designations configured only temporary drivers drive`() {
        assertFalse(user(designation = "Driver").isDriver(emptyList()))
        assertTrue(user(isTempDriver = true).isDriver(emptyList()))
    }

    @Test
    fun `a permanent allocation outranks a single trip in the availability label`() {
        assertEquals("Available", user().availability)
        assertEquals("Assigned to trip", user(assigned = true).availability)
        assertEquals("Permanent allocated", user(permanent = true).availability)
        assertEquals(
            "Permanent allocated",
            user(assigned = true, permanent = true).availability,
            "the standing allocation is the one worth saying",
        )
    }

    // -- who may be made a temporary driver -----------------------------------

    private fun state(
        crew: List<TransportUser>,
        coordinators: List<String> = emptyList(),
        designations: List<String> = listOf("Driver"),
    ) = TransportUiState(
        crew = crew,
        driverDesignations = designations,
        coordinatorIds = coordinators,
        viewer = TransportViewer(userId = "me"),
    )

    private fun crewMember(id: String, designation: String = "Runner", status: String = "accepted") =
        user(designation = designation, status = status).copy(userId = id, fullName = id)

    @Test
    fun `ordinary accepted crew may be drafted in`() {
        val candidates = state(listOf(crewMember("u1"), crewMember("u2"))).temporaryDriverCandidates()

        assertEquals(listOf("u1", "u2"), candidates.map { it.userId })
    }

    @Test
    fun `someone who already drives is not offered`() {
        val crew = listOf(crewMember("u1"), crewMember("u2", designation = "Driver"))

        assertEquals(listOf("u1"), state(crew).temporaryDriverCandidates().map { it.userId })
    }

    @Test
    fun `the viewer is not offered themselves`() {
        val crew = listOf(crewMember("me"), crewMember("u1"))

        assertEquals(listOf("u1"), state(crew).temporaryDriverCandidates().map { it.userId })
    }

    @Test
    fun `crew who have not accepted are not offered`() {
        val crew = listOf(crewMember("u1", status = "pending"), crewMember("u2"))

        assertEquals(listOf("u2"), state(crew).temporaryDriverCandidates().map { it.userId })
    }

    /**
     * A coordinator allocates the trips. Driving them too would put one
     * person on both sides of their own allocation, which is why the web
     * excludes them and why this does.
     */
    @Test
    fun `a coordinator is not offered as a temporary driver`() {
        val crew = listOf(crewMember("u1"), crewMember("u2"))

        val candidates = state(crew, coordinators = listOf("u2")).temporaryDriverCandidates()

        assertEquals(listOf("u1"), candidates.map { it.userId })
    }

    /** An unreadable coordinator list must not empty the picker. */
    @Test
    fun `with no coordinators known everyone eligible is still offered`() {
        val crew = listOf(crewMember("u1"), crewMember("u2"))

        assertEquals(2, state(crew, coordinators = emptyList()).temporaryDriverCandidates().size)
    }

    @Test
    fun `only accepted crew count as on the production`() {
        assertTrue(user(status = "accepted").isAccepted)
        assertTrue(user(status = "Accepted").isAccepted)
        assertFalse(user(status = "pending").isAccepted)
        assertFalse(user(status = "left").isAccepted)
        assertFalse(user(status = "removed").isAccepted)
    }
}
