@file:Suppress("TooManyFunctions") // One function per REST route; the hub has many.

package com.zillit.desktop.feature.transporthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvApprover
import com.zillit.desktop.feature.transporthub.domain.AvDraft
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.ShuttleRoute
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteDraft
import com.zillit.desktop.feature.transporthub.domain.ShuttleRun
import com.zillit.desktop.feature.transporthub.domain.ShuttleRunStatus
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDetailsPatch
import com.zillit.desktop.feature.transporthub.domain.DriverDraft
import com.zillit.desktop.feature.transporthub.domain.DriverLocation
import com.zillit.desktop.feature.transporthub.domain.HubInsights
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRepository
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.HubSyncKind
import com.zillit.desktop.feature.transporthub.domain.MaintenanceDraft
import com.zillit.desktop.feature.transporthub.domain.MaintenanceRecord
import com.zillit.desktop.feature.transporthub.domain.PermanentDraft
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RideDraft
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideScope
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripDraft
import com.zillit.desktop.feature.transporthub.domain.TripEvent
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleDraft
import com.zillit.desktop.feature.transporthub.domain.VehicleFilter
import com.zillit.desktop.feature.transporthub.domain.VehicleTypeOption
import com.zillit.desktop.feature.transporthub.domain.builtInVehicleTypes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Transportation Hub's half of the transport service.
 *
 * Transcription notes, from the web's `transportationHub/api.js` and the
 * pages that call it:
 *
 *  - HTTP 200 with `status: 0` is a **failure** and `message` is a label key,
 *    so every read goes through [mapData] rather than trusting the status line.
 *  - a longitude is `long`; [HubLocation.toWire] is the only place that
 *    writes one.
 *  - several PUT validators require the record's id in the **body** as well
 *    as the path. `/ride-request/:id` wants `ride_request_id`,
 *    `/permanent-trip/:id` wants `permanent_trip_id`; both answer
 *    `<name>_required` without it. The helpers below always add it.
 *  - two DELETEs carry bodies: `/vehicle` takes `{vehicleIds}` and `/driver`
 *    takes `{driverIds}`, both camelCase inside otherwise snake_case bodies.
 *    `DELETE /ride-request/:id` carries `{reason}` and is a soft cancel.
 *  - a list arrives in one of four envelope shapes; see [rows].
 */
class HubRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : HubRepository {

    private val base = config.apiV2(ZillitService.Transportation).trimEnd('/') + "/transportation"

    /**
     * Project members, from the core host.
     *
     * Not a transportation-owned directory: the pickers must offer real
     * Zillit accounts on this production, not a parallel list.
     */
    private val projectUsers = config.apiV2() + "project/users"

    override val refreshes: Flow<HubSyncKind> =
        bus?.onAny(HUB_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.mapNotNull { message -> HUB_SYNC_KINDS[message.event] }
            ?: emptyFlow()

    // Who is looking ---------------------------------------------------------

    override suspend fun driverDesignations(): ZillitResult<List<String>> =
        get("$base/driver/get-designations").mapData { data ->
            data.rows("designations").mapNotNull { row ->
                (row["designation_name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            }.ifEmpty {
                // Some deployments answer a bare array of names.
                (data as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    .orEmpty()
            }
        }

    override suspend fun projectPeople(): ZillitResult<List<ProjectPerson>> =
        get(projectUsers).mapData { data ->
            data.rows("users").mapNotNull { it.toProjectPerson() }
        }

    // Fleet ------------------------------------------------------------------

    override suspend fun vehicles(filter: VehicleFilter): ZillitResult<List<Vehicle>> =
        get("$base/vehicle", filter.query()).mapData { data ->
            data.rows("vehicles").mapNotNull { it.toVehicle() }.filterNot { it.deleted }
        }

    override suspend fun availableVehicles(): ZillitResult<List<Vehicle>> =
        get("$base/vehicle/available").mapData { data ->
            data.rows("vehicles").mapNotNull { it.toVehicle() }.filterNot { it.deleted }
        }

    /**
     * The project's vehicle types.
     *
     * `kind` namespaces the list per tool — the Action Vehicles tool keeps its
     * own categories under the same route — so Fleet asks for `vehicle_type`.
     * `detailed=true` is what makes each row say whether it is a custom one.
     */
    override suspend fun vehicleTypes(): ZillitResult<List<VehicleTypeOption>> =
        get("$base/vehicle-types", mapOf("kind" to VEHICLE_TYPE_KIND, "detailed" to "true"))
            .mapData { data ->
                val rows = data.rows("vehicle_types").mapNotNull { it.toVehicleTypeOption() }
                rows.ifEmpty { builtInVehicleTypes.map { VehicleTypeOption(it) } }
            }

    override suspend fun addVehicleType(label: String): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/vehicle-types",
            buildJsonObject {
                put("label", label)
                put("kind", VEHICLE_TYPE_KIND)
            },
        )

    override suspend fun removeVehicleType(label: String): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            "$base/vehicle-types",
            buildJsonObject {
                put("label", label)
                put("kind", VEHICLE_TYPE_KIND)
            },
        )

    override suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?> =
        apiClient.envelope(HttpVerb.Post, "$base/vehicle", MODULE, vehicleBody(draft))
            .mapData { it.record("vehicle")?.toVehicle() }

    override suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?> =
        apiClient.envelope(HttpVerb.Put, "$base/vehicle/$id", MODULE, vehicleBody(draft))
            .mapData { it.record("vehicle")?.toVehicle() }

    override suspend fun setVehicleAllocation(
        id: String,
        allocation: VehicleAllocation,
    ): ZillitResult<Vehicle?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/vehicle/$id",
            MODULE,
            buildJsonObject { put("allocation_status", allocation.wire) },
        ).mapData { it.record("vehicle")?.toVehicle() }

    override suspend fun deleteVehicles(ids: List<String>): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            "$base/vehicle",
            buildJsonObject { put("vehicleIds", ids.toJsonArray()) },
        )

    // Drivers ----------------------------------------------------------------

    override suspend fun drivers(): ZillitResult<List<Driver>> =
        get("$base/driver").mapData { data -> data.rows("drivers").mapNotNull { it.toDriver() } }

    override suspend fun availableDrivers(): ZillitResult<List<Driver>> =
        get("$base/driver/available").mapData { data ->
            data.rows("drivers").mapNotNull { it.toDriver() }
        }

    override suspend fun incompleteDrivers(): ZillitResult<List<Driver>> =
        get("$base/driver", mapOf("details" to "incomplete")).mapData { data ->
            data.rows("drivers").mapNotNull { it.toDriver() }
        }

    override suspend fun addDriver(draft: DriverDraft): ZillitResult<Driver?> =
        apiClient.envelope(HttpVerb.Post, "$base/driver", MODULE, driverBody(draft))
            .mapData { it.record("driver")?.toDriver() }

    override suspend fun updateDriverDetails(
        id: String,
        patch: DriverDetailsPatch,
    ): ZillitResult<Driver?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/driver/$id",
            MODULE,
            buildJsonObject {
                // Only what the caller set: a patch that echoed every field
                // back would overwrite whatever another client just changed.
                patch.phone?.let { put("phone", it.trim()) }
                patch.licenseNumber?.let { put("license_number", it.trim()) }
                patch.licenseFrontUrl?.let { put("license_front_url", it) }
                patch.licenseBackUrl?.let { put("license_back_url", it) }
            },
        ).mapData { it.record("driver")?.toDriver() }

    override suspend fun setDriverDuty(id: String, onDuty: Boolean): ZillitResult<Driver?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/driver/$id/duty-status",
            MODULE,
            buildJsonObject {
                put("is_on_duty", onDuty)
                // The flag and the word, together: handlers read one or other.
                put("duty_status", if (onDuty) "on_duty" else "off_duty")
            },
        ).mapData { it.record("driver")?.toDriver() }

    override suspend fun deleteDrivers(ids: List<String>): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            "$base/driver",
            buildJsonObject { put("driverIds", ids.toJsonArray()) },
        )

    // Trips ------------------------------------------------------------------

    /**
     * The trip list.
     *
     * [driverId] is the **driver record's** id, not the user behind it, and it
     * is what scopes a driver's My Trips to theirs. Leaving it off is the
     * coordinator's read — and was the driver's too until this was fixed: a
     * driver saw the production's whole list. The list is never filtered
     * again here, because `assigned_driver` on the document is a user id and
     * would match nothing.
     */
    override suspend fun trips(driverId: String?): ZillitResult<List<Trip>> =
        get(
            "$base/trip",
            driverId?.takeIf { it.isNotBlank() }?.let { mapOf("driver_id" to it) }.orEmpty(),
        ).mapData { data -> data.rows("trips").mapNotNull { it.toTrip() } }

    override suspend fun trip(id: String): ZillitResult<Trip?> =
        get("$base/trip/$id").mapData { it.record("trip")?.toTrip() }

    override suspend fun tripEvents(id: String): ZillitResult<List<TripEvent>> =
        get("$base/trip/$id/events").mapData { data ->
            data.rows("events").map { it.toTripEvent() }
        }

    override suspend fun addTrip(draft: TripDraft): ZillitResult<String?> =
        apiClient.envelope(HttpVerb.Post, "$base/trip", MODULE, tripBody(draft))
            .mapData { data ->
                data.record("trip")?.let { record ->
                    (record["_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            }

    override suspend fun assignTrip(
        id: String,
        driverId: String,
        vehicleId: String?,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/trip/$id/assign",
            buildJsonObject {
                put("driver_id", driverId)
                vehicleId?.takeIf { it.isNotBlank() }?.let { put("vehicle_id", it) }
            },
        )

    override suspend fun setTripStatus(id: String, status: TripStatus): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/trip/$id/status",
            buildJsonObject {
                // Both spellings: handlers of different ages read one or other.
                put("trip_status", status.wire)
                put("status", status.wire)
            },
        )

    override suspend fun sendTripReminder(id: String): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/trip/$id/reminder", null)

    override suspend fun deleteTrip(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/trip/$id", null)

    // Ride requests ----------------------------------------------------------

    /**
     * The ride requests a viewer may see.
     *
     * Two reads, merged by id: the default list leaves rejected rides out
     * entirely, so the Rejected tab would always be empty on one call. The
     * second read is allowed to fail — a deployment that rejects the `status`
     * parameter should cost the tab, not the page.
     */
    override suspend fun rideRequests(scope: RideScope): ZillitResult<List<RideRequest>> {
        val query = scope.query()
        val primary = get("$base/ride-request", query).mapData { data ->
            data.rows("ride_requests").mapNotNull { it.toRideRequest() }
        }
        if (primary is ZillitResult.Failure) return primary
        val rejected = get(
            "$base/ride-request",
            query + mapOf("status" to RideStatus.Rejected.wire, "limit" to REJECTED_PAGE_SIZE),
        ).mapData { data -> data.rows("ride_requests").mapNotNull { it.toRideRequest() } }

        val rows = (primary as ZillitResult.Success).data
        val extra = (rejected as? ZillitResult.Success)?.data.orEmpty()
        return ZillitResult.Success(
            (rows + extra).associateBy { it.id }.values.toList(),
        )
    }

    override suspend fun activeCars(): ZillitResult<List<ActiveCar>> =
        get("$base/ride-request/active-cars").mapData { data ->
            data.rows("active_cars", "rides").map { it.toActiveCar() }
        }

    override suspend fun addRideRequest(draft: RideDraft): ZillitResult<String?> =
        apiClient.envelope(HttpVerb.Post, "$base/ride-request", MODULE, rideBody(draft))
            .mapData { data ->
                data.record("ride_request")?.let { record ->
                    (record["_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            }

    override suspend fun updateRideRequest(id: String, draft: RideDraft): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/ride-request/$id", rideBody(draft).withRideId(id))

    override suspend fun approveRide(id: String): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/ride-request/$id/approve", buildJsonObject { }.withRideId(id))

    override suspend fun rejectRide(id: String, reason: String): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/ride-request/$id/reject",
            buildJsonObject { put("reason", reason) }.withRideId(id),
        )

    override suspend fun assignRide(
        id: String,
        driverId: String?,
        vehicleId: String?,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/ride-request/$id/assign",
            buildJsonObject {
                driverId?.takeIf { it.isNotBlank() }?.let {
                    put("driver_id", it)
                    // Both namings go out together: the dev backend flips the
                    // status but drops the ids, and sending both means this
                    // works the moment that is fixed, with no release here.
                    put("assigned_driver", it)
                }
                vehicleId?.takeIf { it.isNotBlank() }?.let {
                    put("vehicle_id", it)
                    put("assigned_vehicle", it)
                }
            }.withRideId(id),
        )

    override suspend fun setRideStatus(id: String, status: RideStatus): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/ride-request/$id/status",
            buildJsonObject { put("status", status.wire) }.withRideId(id),
        )

    override suspend fun cancelRide(id: String, reason: String): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            "$base/ride-request/$id",
            buildJsonObject { put("reason", reason) },
        )

    override suspend fun addRideStop(id: String, stop: HubStop): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/ride-request/$id/add-stop",
            buildJsonObject { put("stop", stopBody(stop)) }.withRideId(id),
        )

    override suspend fun completeRideStop(id: String, index: Int): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/ride-request/$id/complete-stop/$index", null)

    override suspend fun addRidePassenger(id: String, passenger: HubPassenger): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/ride-request/$id/add-passenger",
            buildJsonObject {
                put(
                    "passengers",
                    buildJsonArray { add(passengerBody(passenger, forTrip = false)) },
                )
            }.withRideId(id),
        )

    // Permanent allocations --------------------------------------------------

    override suspend fun permanentTrips(): ZillitResult<List<PermanentTrip>> =
        get("$base/permanent-trip").mapData { data ->
            data.rows("permanentTrips", "permanent_trips").mapNotNull { it.toPermanentTrip() }
        }

    override suspend fun addPermanentTrip(draft: PermanentDraft): ZillitResult<PermanentTrip?> =
        apiClient.envelope(HttpVerb.Post, "$base/permanent-trip", MODULE, permanentBody(draft))
            .mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun updatePermanentTrip(
        id: String,
        draft: PermanentDraft,
    ): ZillitResult<PermanentTrip?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/permanent-trip/$id",
            MODULE,
            permanentBody(draft).withPermanentId(id),
        ).mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun activateDraft(trip: PermanentTrip): ZillitResult<PermanentTrip?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/permanent-trip/${trip.id}",
            MODULE,
            promoteBody(trip).withPermanentId(trip.id),
        ).mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun resumePermanentTrip(id: String): ZillitResult<PermanentTrip?> =
        apiClient.envelope(HttpVerb.Put, "$base/permanent-trip/$id/activate", MODULE)
            .mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun pausePermanentTrip(id: String): ZillitResult<PermanentTrip?> =
        apiClient.envelope(HttpVerb.Put, "$base/permanent-trip/$id/pause", MODULE)
            .mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun swapPermanentDriver(
        id: String,
        driverId: String,
        reason: String,
    ): ZillitResult<PermanentTrip?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/permanent-trip/$id/swap-driver",
            MODULE,
            buildJsonObject {
                put("driver_id", driverId)
                put("reason", reason)
            }.withPermanentId(id),
        ).mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun swapPermanentVehicle(
        id: String,
        vehicleId: String,
        reason: String,
    ): ZillitResult<PermanentTrip?> =
        apiClient.envelope(
            HttpVerb.Put,
            "$base/permanent-trip/$id/swap-vehicle",
            MODULE,
            buildJsonObject {
                put("vehicle_id", vehicleId)
                put("reason", reason)
            }.withPermanentId(id),
        ).mapData { it.record("permanent_trip")?.toPermanentTrip() }

    override suspend fun deletePermanentTrip(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/permanent-trip/$id", buildJsonObject { }.withPermanentId(id))

    // Dashboard --------------------------------------------------------------

    override suspend fun insights(): ZillitResult<HubInsights> =
        get("$base/insights/dashboard").mapData { it.toInsights() }

    override suspend fun pendingRideCount(): ZillitResult<Int> =
        get("$base/ride-request/pending").mapData { it.toPendingCount() }

    // Maintenance ------------------------------------------------------------

    override suspend fun maintenance(): ZillitResult<List<MaintenanceRecord>> =
        get("$base/maintenance").mapData { data ->
            data.rows("maintenance", "items").mapNotNull { it.toMaintenanceRecord() }
        }

    override suspend fun addMaintenance(draft: MaintenanceDraft): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/maintenance", maintenanceBody(draft))

    override suspend fun completeMaintenance(id: String): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/maintenance/$id/complete", null)

    override suspend fun deleteMaintenance(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/maintenance/$id", null)

    // Live tracking ----------------------------------------------------------

    override suspend fun activeLocations(): ZillitResult<List<DriverLocation>> =
        get("$base/location/active").mapData { data ->
            data.rows("locations", "drivers").mapNotNull { it.toDriverLocation() }
        }

    // Shuttles ---------------------------------------------------------------

    override suspend fun shuttleRoutes(): ZillitResult<List<ShuttleRoute>> =
        get("$base/shuttle/routes").mapData { data ->
            data.rows("routes").mapNotNull { it.toShuttleRoute() }
        }

    override suspend fun addShuttleRoute(draft: ShuttleRouteDraft): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/shuttle/routes", shuttleRouteBody(draft))

    override suspend fun updateShuttleRoute(
        id: String,
        draft: ShuttleRouteDraft,
    ): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/shuttle/routes/$id", shuttleRouteBody(draft).withRouteId(id))

    override suspend fun setShuttleRouteActive(id: String, active: Boolean): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/shuttle/routes/$id",
            // camelCase inside an otherwise snake_case body — the update
            // route's own spelling, and the only field this call sends.
            buildJsonObject { put("isActive", active) }.withRouteId(id),
        )

    override suspend fun deleteShuttleRoute(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/shuttle/routes/$id", null)

    override suspend fun generateShuttleRuns(id: String): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/shuttle/routes/$id/generate", null)

    /** The runs live under the **plural** prefix; the singular one 404s. */
    override suspend fun shuttleRuns(): ZillitResult<List<ShuttleRun>> =
        get("$base/shuttles/schedule").mapData { data ->
            data.rows("schedule", "runs").mapNotNull { it.toShuttleRun() }
        }

    override suspend fun setShuttleRunStatus(
        id: String,
        status: ShuttleRunStatus,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/shuttles/runs/$id",
            buildJsonObject { put("status", status.wire) },
        )

    // Action vehicles --------------------------------------------------------

    override suspend fun actionVehicles(): ZillitResult<List<ActionVehicle>> =
        get("$base/action-vehicle").mapData { data ->
            data.rows("action_vehicles", "vehicles").mapNotNull { it.toActionVehicle() }
        }

    override suspend fun requestActionVehicle(
        draft: AvDraft,
        timezone: String,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/action-vehicle/request",
            buildJsonObject {
                avBody(draft).forEach { (key, value) -> put(key, value) }
                // The shoot-day reminder cron computes "N days before" in the
                // requester's own zone; without it the reminders fall back to
                // UTC and go out on the wrong day.
                put("timezone", timezone)
            },
        )

    override suspend fun sourceActionVehicle(id: String, draft: AvDraft): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/action-vehicle/$id/source", avBody(draft).withAvId(id))

    override suspend fun updateActionVehicle(
        id: String,
        draft: AvDraft,
        timezone: String,
        datesOnly: Boolean,
    ): ZillitResult<Unit> {
        val body = if (datesOnly) {
            avDatesBody(draft, timezone)
        } else {
            buildJsonObject {
                avBody(draft).forEach { (key, value) -> put(key, value) }
                put("timezone", timezone)
            }
        }
        return write(HttpVerb.Put, "$base/action-vehicle/$id", body.withAvId(id))
    }

    override suspend fun setActionVehicleStatus(id: String, status: AvStatus): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/status",
            buildJsonObject { put("status", status.wire) }.withAvId(id),
        )

    override suspend fun deleteActionVehicle(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/action-vehicle/$id", null)

    override suspend fun sendActionVehicleForApproval(
        id: String,
        approvers: List<AvApprover>,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/send-for-approval",
            buildJsonObject { put("approvers", approvers.toApproverWire()) }.withAvId(id),
        )

    override suspend fun approveActionVehicle(id: String): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/action-vehicle/$id/approve", buildJsonObject { }.withAvId(id))

    override suspend fun rejectActionVehicle(id: String, reason: String): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/reject",
            buildJsonObject { put("reason", reason) }.withAvId(id),
        )

    override suspend fun requestActionVehicleReturn(
        id: String,
        approvers: List<AvApprover>,
        note: String,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/request-return",
            buildJsonObject {
                put("approvers", approvers.toApproverWire())
                put("note", note.trim())
            }.withAvId(id),
        )

    override suspend fun approveActionVehicleReturn(id: String): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/approve-return",
            buildJsonObject { }.withAvId(id),
        )

    override suspend fun rejectActionVehicleReturn(
        id: String,
        reason: String,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/reject-return",
            buildJsonObject { put("reason", reason) }.withAvId(id),
        )

    override suspend fun editActionVehicleApprovers(
        id: String,
        add: List<String>,
        remove: List<String>,
        returnFlow: Boolean,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/action-vehicle/$id/approvers",
            buildJsonObject {
                // Ids only: this route resolves the names from project
                // membership itself, unlike send-for-approval.
                if (add.isNotEmpty()) put("add", add.toJsonArray())
                if (remove.isNotEmpty()) put("remove", remove.toJsonArray())
                put("kind", if (returnFlow) "return" else "approval")
            }.withAvId(id),
        )

    override suspend fun remindActionVehicleApprover(
        id: String,
        userId: String,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/action-vehicle/$id/remind-approver",
            buildJsonObject { put("user_id", userId) }.withAvId(id),
        )

    override suspend fun addActionVehicleComment(id: String, text: String): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/action-vehicle/$id/comment",
            // The author comes from the token; sending one would be ignored.
            buildJsonObject { put("text", text.trim()) }.withAvId(id),
        )

    override suspend fun sendActionVehicleForComments(
        id: String,
        userIds: List<String>,
        message: String,
    ): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/action-vehicle/$id/send-for-comments",
            buildJsonObject {
                put("users", userIds.toJsonArray())
                put("message", message.trim())
            }.withAvId(id),
        )

    // Plumbing ---------------------------------------------------------------

    /**
     * The scope parameter, as the three ride pages send it.
     *
     * `driver_id` is the driver record's id — `me=true` would answer the rides
     * that driver *asked for*, which is a different list entirely.
     */
    private fun RideScope.query(): Map<String, Any?> = when (this) {
        RideScope.All -> emptyMap()
        RideScope.Mine -> mapOf("me" to "true")
        is RideScope.ForDriver -> mapOf("driver_id" to driverId)
    }


    private fun VehicleFilter.query(): Map<String, Any?> = buildMap {
        allocation.query?.let { put("allocation_status", it) }
        types.takeIf { it.isNotEmpty() }?.let { put("vehicle_type", it.joinToString(",")) }
        ownership.query?.let { put("ownership", it) }
        search.trim().takeIf { it.isNotBlank() }?.let { put("search", it) }
    }

    private suspend fun get(
        url: String,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, MODULE, queryParameters = query)

    private suspend fun write(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
    ): ZillitResult<Unit> = apiClient.envelope(verb, url, MODULE, body).mapData { }

    /**
     * Unwraps an envelope, treating `status: 0` as the failure it is.
     *
     * The service answers HTTP 200 on a business-rule rejection, so a call
     * that only checked the status line would read a refusal as a success and
     * show an empty list. `message` is a label key the UI translates.
     */
    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(
        transform: (JsonElement?) -> T,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> this
        is ZillitResult.Success ->
            if (data.status == 0) {
                ZillitResult.Failure(
                    ZillitError.Http(
                        status = HTTP_OK,
                        serverMessage = data.message ?: "something_went_wrong",
                    ),
                )
            } else {
                ZillitResult.Success(transform(data.data))
            }
    }

    private companion object {
        const val HTTP_OK = 200
        val MODULE = RequestModule.ProjectUser

        /** Namespaces the type list to Fleet's, not Action Vehicles'. */
        const val VEHICLE_TYPE_KIND = "vehicle_type"

        /** The rejected read is a tab's worth, not a page's. */
        const val REJECTED_PAGE_SIZE = 200
    }
}

/** Adds the `ride_request_id` the ride PUT validators require in the body. */
private fun JsonObject.withRideId(id: String): JsonObject = buildJsonObject {
    put("ride_request_id", id)
    this@withRideId.forEach { (key, value) -> put(key, value) }
}

/** Adds the `route_id` the shuttle-route update validator requires. */
private fun JsonObject.withRouteId(id: String): JsonObject = buildJsonObject {
    put("route_id", id)
    this@withRouteId.forEach { (key, value) -> put(key, value) }
}

/** Adds the `action_vehicle_id` every action-vehicle write validator requires. */
private fun JsonObject.withAvId(id: String): JsonObject = buildJsonObject {
    put("action_vehicle_id", id)
    this@withAvId.forEach { (key, value) -> put(key, value) }
}

/** Adds the `permanent_trip_id` the permanent-trip validators require. */
private fun JsonObject.withPermanentId(id: String): JsonObject = buildJsonObject {
    put("permanent_trip_id", id)
    this@withPermanentId.forEach { (key, value) -> put(key, value) }
}

private fun List<String>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
