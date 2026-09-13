@file:Suppress("TooManyFunctions") // One function per REST route.

package com.zillit.desktop.feature.transportation.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.feature.transportation.domain.DriverDetailsUpdate
import com.zillit.desktop.feature.transportation.domain.LicenceRequest
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.TransportRepository
import com.zillit.desktop.feature.transportation.domain.TransportSyncKind
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TripDraft
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
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
 * The transport service (`transportapi`, `/api/v2/transportation`).
 *
 * Transcription notes, from the web's `transportationApi.js` and its
 * modals: HTTP 200 + `status:0` is a failure and `message` is a label key;
 * every date is epoch ms; longitude is `long`; the two DELETEs carry
 * bodies (`{vehicleIds}`, `{tripRequestId}`); ids inside bodies are
 * camelCase (`vehicleId`, `tripRequestId`, `vehicleIds`) while everything
 * around them is snake_case; a non-coordinator's list is scoped with
 * `user_id`, a driver's with `driver_id`, a coordinator's with neither.
 */
class TransportRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : TransportRepository {

    private val base = config.apiV2(ZillitService.Transportation).trimEnd('/') + "/transportation"

    /**
     * See [TransportRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project, as every web handler
     * does; the event name picks the kind via [TRANSPORT_SYNC_KINDS].
     */
    override val refreshes: Flow<TransportSyncKind> =
        bus?.onAny(TRANSPORT_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.mapNotNull { message -> TRANSPORT_SYNC_KINDS[message.event] }
            ?: emptyFlow()

    /** `project/users` on the core host — the driver flags live there. */
    private val users = config.apiV2() + "project/users"

    /** `access/users` on the core host — who may open the tool. */
    private val access = config.apiV2() + "access/users"

    // Vehicles ---------------------------------------------------------------

    override suspend fun vehicles(): ZillitResult<List<Vehicle>> =
        get("$base/vehicle").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseVehicle(it as? JsonObject) }.filter { it.isLive }
        }

    override suspend fun vehicleTypes(): ZillitResult<List<String>> =
        get("$base/vehicle/vehicle-types").mapData { data ->
            ((data as? JsonObject)?.get("vehicle_type") as? JsonArray).items()
                .mapNotNull { (it as? JsonPrimitive)?.content }
        }

    override suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?> =
        apiClient.envelope(HttpVerb.Post, "$base/vehicle", RequestModule.ProjectUser, vehicleWire(draft, id = null))
            .mapData { parseVehicle(it as? JsonObject) }

    override suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?> =
        apiClient.envelope(HttpVerb.Put, "$base/vehicle", RequestModule.ProjectUser, vehicleWire(draft, id = id))
            .mapData { parseVehicle(it as? JsonObject) }

    override suspend fun deleteVehicles(ids: List<String>): ZillitResult<Unit> =
        write(
            HttpVerb.Delete,
            "$base/vehicle",
            buildJsonObject { put("vehicleIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) },
        )

    override suspend fun createTempDriverVehicle(userId: String, draft: VehicleDraft): ZillitResult<Vehicle?> =
        apiClient.envelope(
            HttpVerb.Post,
            "$base/driver/temporary-driver-vehicle",
            RequestModule.ProjectUser,
            tempDriverVehicleWire(userId, draft),
        ).mapData { parseVehicle(it as? JsonObject) }

    // Drivers ----------------------------------------------------------------

    override suspend fun driverDesignations(): ZillitResult<List<String>> =
        get("$base/driver/get-designations").mapData { data ->
            (data as? JsonArray).items().mapNotNull { (it as? JsonObject)?.text("designation_name")?.ifBlank { null } }
        }

    override suspend fun crew(): ZillitResult<List<TransportUser>> =
        get(users).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseUser(it as? JsonObject) }
        }

    override suspend fun postingRightUsers(): ZillitResult<List<String>> =
        get("$base/posting-rights-users").mapData { data ->
            (data as? JsonArray).items().mapNotNull { (it as? JsonPrimitive)?.content }
        }

    /**
     * `GET access/users?toolIdentifier=transportation_tool&viewing_access=true`
     * — the web's `fetchuserapproveringrights({ view_access: true })`. The
     * answer is an id array; ids that arrive as objects read by `user_id`.
     */
    override suspend fun viewingRightUserIds(): ZillitResult<Set<String>> =
        get(access, mapOf("toolIdentifier" to TOOL, "viewing_access" to "true")).mapData { data ->
            (data as? JsonArray).items().mapNotNullTo(mutableSetOf()) { element ->
                when (element) {
                    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                    is JsonObject -> element.text("user_id", "_id").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        }

    override suspend fun updateDriverDetails(update: DriverDetailsUpdate): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/driver/update-details", driverDetailsWire(update))

    override suspend fun pendingDocumentReminder(userId: String, type: String, message: String?): ZillitResult<Unit> =
        write(
            HttpVerb.Post,
            "$base/request/pending-document-reminder",
            buildJsonObject {
                put("user_id", userId)
                put("trip_reminder_type", type)
                message?.takeIf { it.isNotBlank() && type == "document" }?.let { put("trip_reminder_message", it) }
            },
        )

    /**
     * `GET /v2/transportation/driver/change-requests` — the requests waiting
     * on a manager (`transportationApi.js:61-68`).
     */
    override suspend fun licenceRequests(): ZillitResult<List<LicenceRequest>> =
        get("$base/driver/change-requests").mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseLicenceRequest(it as? JsonObject) }
        }

    /**
     * `PUT` the same route (`transportationApi.js:98-106`).
     *
     * `is_licence_verified` is spelled the British way while the path is
     * spelled the American way; both spellings appear in this service and
     * they are not interchangeable.
     */
    override suspend fun decideLicenceRequest(requestId: String, approved: Boolean): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/driver/change-requests",
            buildJsonObject {
                put("request_id", requestId)
                put("is_licence_verified", approved)
            },
        )

    // Trip requests ----------------------------------------------------------

    override suspend fun trips(
        status: TripStatus,
        asDriver: Boolean,
        userId: String,
        coordinator: Boolean,
    ): ZillitResult<List<TripRequest>> {
        val query = buildMap<String, Any?> {
            put("trip_status", status.wire)
            when {
                asDriver -> put("driver_id", userId)
                !coordinator -> put("user_id", userId)
            }
        }
        return get("$base/request", query).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parseTrip(it as? JsonObject) }
        }
    }

    override suspend fun raiseTrip(draft: TripDraft, coordinator: Boolean): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/request", tripWire(draft, coordinator))

    override suspend fun updateTrip(update: TripUpdate, nowMs: Long): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/request", tripUpdateWire(update, nowMs))

    override suspend fun sendReminder(tripId: String): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/request/send-reminder", buildJsonObject { put("tripRequestId", tripId) })

    // Permanent allocations --------------------------------------------------

    override suspend fun permanentTrips(
        asDriver: Boolean,
        userId: String,
        coordinator: Boolean,
        status: PermanentStatus?,
    ): ZillitResult<List<PermanentTrip>> {
        val query = buildMap<String, Any?> {
            if (!coordinator) put(if (asDriver) "driver_id" else "user_id", userId)
            status?.let { put("trip_status", it.wire) }
        }
        return get("$base/request/permanent-trip", query).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parsePermanent(it as? JsonObject) }
        }
    }

    override suspend fun myPermanentTrips(asDriver: Boolean, userId: String): ZillitResult<List<PermanentTrip>> =
        get(
            "$base/request/permanent-trip-by-passenger",
            mapOf((if (asDriver) "driver_id" else "user_id") to userId),
        ).mapData { data ->
            (data as? JsonArray).items().mapNotNull { parsePermanent(it as? JsonObject) }
        }

    override suspend fun createPermanent(draft: PermanentDraft): ZillitResult<Unit> =
        write(HttpVerb.Post, "$base/request/permanent-trip", permanentWire(draft, id = null, status = null))

    override suspend fun updatePermanent(id: String, draft: PermanentDraft,
        status: PermanentStatus): ZillitResult<Unit> =
        write(HttpVerb.Put, "$base/request/permanent-trip", permanentWire(draft, id = id, status = status))

    override suspend fun unassignPermanent(trip: PermanentTrip): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/request/permanent-trip",
            buildJsonObject {
                put("tripRequestId", trip.id)
                put("trip_status", PermanentStatus.Completed.wire)
                trip.vehicleId?.let { put("vehicle_id", it) }
                trip.driverId?.let { put("driver_id", it) }
            },
        )

    override suspend fun deletePermanent(id: String): ZillitResult<Unit> =
        write(HttpVerb.Delete, "$base/request/permanent-trip", buildJsonObject { put("tripRequestId", id) })

    override suspend fun setSelfManage(id: String, selfManage: Boolean): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/request/permanent-trip",
            buildJsonObject {
                put("tripRequestId", id)
                put("self_manage", selfManage)
            },
        )

    // Transport ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser, queryParameters = query)

    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(verb, url, RequestModule.ProjectUser, body).mapData { }

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success ->
                if (data.status == 0) {
                    ZillitResult.Failure(
                        ZillitError.Http(status = HTTP_OK, serverMessage = data.message ?: "something_went_wrong"),
                    )
                } else {
                    ZillitResult.Success(transform(data.data))
                }
        }

    private companion object {
        const val HTTP_OK = 200
        const val TOOL = "transportation_tool"
    }
}

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()
