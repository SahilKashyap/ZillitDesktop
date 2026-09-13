package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.maps.domain.CityDraft
import com.zillit.desktop.feature.maps.domain.CityWrite
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import com.zillit.desktop.feature.maps.domain.TypeDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The map service (`/api/v2` on the map host — `mrmiapi` since the 13→4
 * consolidation), every route the web's `map-module/services` call.
 *
 * HTTP 200 with `status: 0` is a failure and is checked on every call; the
 * server's message travels to the toast on both outcomes.
 */
@Suppress("TooManyFunctions") // One function per REST route.
class MapRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
    /** This device, so a frame caused by our own write is not applied twice. */
    private val currentDeviceId: () -> String? = { null },
) : MapRepository {

    private val base = config.apiV2(ZillitService.Map).trimEnd('/')

    override val sync: Flow<MapSyncEvent> =
        bus?.onAny(MAP_SYNC_EVENTS)
            ?.mapNotNull { message ->
                mapSyncEvent(message.event.value, message.payload, currentProjectId(), currentDeviceId())
            }
            ?: emptyFlow()

    override suspend fun cities(): ZillitResult<List<MapCity>> = read("$base/city") { parseCities(it) }

    override suspend fun createCity(draft: CityDraft): ZillitResult<CityWrite> =
        when (val sent = send(HttpVerb.Post, "$base/city", cityBody(draft))) {
            is ZillitResult.Failure -> sent
            is ZillitResult.Success -> ZillitResult.Success(
                // `response?.data?.data || response?.data` — either nesting.
                CityWrite(
                    message = sent.data.message,
                    city = (sent.data.data as? JsonObject)?.let { data ->
                        parseCity((data["data"] as? JsonObject) ?: data)
                    },
                ),
            )
        }

    override suspend fun deleteCity(id: String): ZillitResult<String?> =
        write(HttpVerb.Delete, "$base/city/$id", null)

    override suspend fun reorderCities(cityIds: List<String>): ZillitResult<String?> =
        write(HttpVerb.Put, "$base/city/reorder-cities", reorderBody(cityIds))

    override suspend fun types(): ZillitResult<List<LocationType>> = read("$base/location-type") { parseTypes(it) }

    override suspend fun createType(draft: TypeDraft): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/location-type", typeBody(draft, id = null))

    override suspend fun updateType(id: String, draft: TypeDraft): ZillitResult<String?> =
        write(HttpVerb.Put, "$base/location-type", typeBody(draft, id = id))

    override suspend fun deleteType(id: String): ZillitResult<String?> =
        write(HttpVerb.Delete, "$base/location-type/$id", null)

    /**
     * The city's locations. Zones are filtered out defensively: the route
     * answers locations only today, and a zone listed here would draw as a pin.
     */
    override suspend fun locations(cityId: String): ZillitResult<List<MapLocation>> =
        read("$base/map", mapOf("cityId" to cityId)) { data -> parseLocations(data).filterNot { it.isStudioZone } }

    override suspend fun studioZones(cityId: String?): ZillitResult<List<MapLocation>> =
        read("$base/map/studio-zones", cityId?.let { mapOf("cityId" to it) }.orEmpty()) { data ->
            parseLocations(data).map { it.copy(isStudioZone = true) }
        }

    override suspend fun createLocation(draft: LocationDraft): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/map", locationBody(draft, id = null))

    override suspend fun updateLocation(id: String, draft: LocationDraft): ZillitResult<String?> =
        write(HttpVerb.Put, "$base/map", locationBody(draft, id = id))

    override suspend fun createZone(draft: ZoneDraft): ZillitResult<String?> =
        write(HttpVerb.Post, "$base/map", zoneBody(draft, id = null))

    override suspend fun updateZone(id: String, draft: ZoneDraft): ZillitResult<String?> =
        write(HttpVerb.Put, "$base/map", zoneBody(draft, id = id))

    override suspend fun deleteLocation(id: String): ZillitResult<String?> =
        write(HttpVerb.Delete, "$base/map/$id", null)

    // Transport ------------------------------------------------------------

    private suspend fun <T> read(
        url: String,
        query: Map<String, Any?> = emptyMap(),
        transform: (JsonElement?) -> T,
    ): ZillitResult<T> =
        when (val envelope = apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser, queryParameters = query)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) {
                    ZillitResult.Failure(rejected(envelope.data))
                } else {
                    ZillitResult.Success(transform(envelope.data.data))
                }
        }

    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<String?> =
        when (val sent = send(verb, url, body)) {
            is ZillitResult.Failure -> sent
            is ZillitResult.Success -> ZillitResult.Success(sent.data.message)
        }

    private suspend fun send(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<ApiEnvelope> =
        when (val envelope = apiClient.envelope(verb, url, RequestModule.ProjectUser, body)) {
            is ZillitResult.Failure -> envelope
            is ZillitResult.Success ->
                if (envelope.data.status == 0) ZillitResult.Failure(rejected(envelope.data)) else envelope
        }

    private fun rejected(envelope: ApiEnvelope): ZillitError = ZillitError.Http(
        status = HTTP_OK,
        serverMessage = envelope.message ?: "something_went_wrong",
        messageElements = envelope.messageElements.orEmpty(),
    )

    private companion object {
        const val HTTP_OK = 200
    }
}
