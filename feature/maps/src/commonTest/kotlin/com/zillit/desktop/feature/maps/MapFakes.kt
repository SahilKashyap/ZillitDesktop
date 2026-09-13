package com.zillit.desktop.feature.maps

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.domain.CityDraft
import com.zillit.desktop.feature.maps.domain.CityWrite
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapPhotos
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapShareHost
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.SharePerson
import com.zillit.desktop.feature.maps.domain.TypeDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An in-memory map service that records what was sent. */
internal class FakeMapRepository(
    var cityList: MutableList<MapCity> = mutableListOf(),
    var typeList: MutableList<LocationType> = mutableListOf(),
    val locationsByCity: MutableMap<String, List<MapLocation>> = mutableMapOf(),
    val zonesByCity: MutableMap<String, List<MapLocation>> = mutableMapOf(),
) : MapRepository {

    val syncFrames = MutableSharedFlow<MapSyncEvent>(extraBufferCapacity = 16)
    override val sync: Flow<MapSyncEvent> = syncFrames

    /** Refuses every location update and reorder while set. */
    var failWrites = false

    val createdCities = mutableListOf<CityDraft>()
    val createdLocations = mutableListOf<LocationDraft>()
    val updatedLocations = mutableListOf<Pair<String, LocationDraft>>()
    val createdZones = mutableListOf<ZoneDraft>()
    val updatedTypes = mutableListOf<Pair<String, TypeDraft>>()
    val locationCalls = mutableListOf<String>()
    val zoneCalls = mutableListOf<String?>()
    var reordered: List<String>? = null

    override suspend fun cities() = ZillitResult.Success(cityList.toList())

    override suspend fun createCity(draft: CityDraft): ZillitResult<CityWrite> {
        createdCities += draft
        val city = MapCity(id = "c${cityList.size + 1}", name = draft.name, description = draft.description, coordinates = draft.point)
        cityList += city
        return ZillitResult.Success(CityWrite("city_added", city))
    }

    override suspend fun deleteCity(id: String): ZillitResult<String?> {
        cityList.removeAll { it.id == id }
        return ZillitResult.Success(null)
    }

    override suspend fun reorderCities(cityIds: List<String>): ZillitResult<String?> {
        reordered = cityIds
        return if (failWrites) ZillitResult.Failure(ZillitError.NoConnection()) else ZillitResult.Success(null)
    }

    override suspend fun types() = ZillitResult.Success(typeList.toList())
    override suspend fun createType(draft: TypeDraft) = ZillitResult.Success<String?>(null)
    override suspend fun updateType(id: String, draft: TypeDraft): ZillitResult<String?> {
        updatedTypes += id to draft
        return ZillitResult.Success(null)
    }
    override suspend fun deleteType(id: String) = ZillitResult.Success<String?>(null)

    override suspend fun locations(cityId: String): ZillitResult<List<MapLocation>> {
        locationCalls += cityId
        return ZillitResult.Success(locationsByCity[cityId].orEmpty())
    }

    override suspend fun studioZones(cityId: String?): ZillitResult<List<MapLocation>> {
        zoneCalls += cityId
        return ZillitResult.Success(zonesByCity[cityId].orEmpty())
    }

    override suspend fun createLocation(draft: LocationDraft): ZillitResult<String?> {
        createdLocations += draft
        return ZillitResult.Success(null)
    }

    override suspend fun updateLocation(id: String, draft: LocationDraft): ZillitResult<String?> {
        updatedLocations += id to draft
        return if (failWrites) ZillitResult.Failure(ZillitError.NoConnection()) else ZillitResult.Success(null)
    }

    override suspend fun createZone(draft: ZoneDraft): ZillitResult<String?> {
        createdZones += draft
        return ZillitResult.Success(null)
    }

    override suspend fun updateZone(id: String, draft: ZoneDraft) = ZillitResult.Success<String?>(null)
    override suspend fun deleteLocation(id: String) = ZillitResult.Success<String?>(null)
}

/**
 * A map page that answers Google questions at once: every reverse geocode is
 * [geocodedAddress], every place detail is [placeAt].
 */
internal class FakeCanvasHost(
    var geocodedAddress: String = "Somewhere Rd, Andheri West, Mumbai",
    var geocodedName: String = "Andheri West",
    /** The locality the reverse geocode files the point under. */
    var geocodedCity: String = "Mumbai",
    var placeAt: LatLng = LatLng(19.1, 72.85),
    var placeName: String = "Picked Place",
) : MapCanvasHost {

    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val scripts = mutableListOf<String>()

    override fun execute(script: String) {
        scripts += script
        if (!script.startsWith("zillitMap.request(")) return
        val payload = Json.parseToJsonElement(script.removePrefix("zillitMap.request(").removeSuffix(")")).jsonPrimitive.content
        val request = Json.parseToJsonElement(payload).jsonObject
        val id = request["id"]!!.jsonPrimitive.content.toInt()
        val reply: JsonObject = when (request["op"]!!.jsonPrimitive.content) {
            "reverseGeocode" -> buildJsonObject {
                put("type", "reply"); put("id", id); put("ok", true); put("address", geocodedAddress)
                put(
                    "components",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("long_name", geocodedName)
                                put("types", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("sublocality_level_1")) })
                            },
                        )
                        add(
                            buildJsonObject {
                                put("long_name", geocodedCity)
                                put("types", buildJsonArray { add(JsonPrimitive("locality")) })
                            },
                        )
                    },
                )
            }
            "details" -> buildJsonObject {
                put("type", "reply"); put("id", id); put("ok", true); put("name", placeName)
                put("address", "1 Picked Rd"); put("lat", placeAt.lat); put("lng", placeAt.lng)
            }
            "predictions" -> buildJsonObject {
                put("type", "reply"); put("id", id); put("ok", true)
                put("items", buildJsonArray { add(buildJsonObject { put("placeId", "p1"); put("description", "1 Picked Rd"); put("main", "1 Picked Rd") }) })
            }
            "geocode" -> buildJsonObject {
                put("type", "reply"); put("id", id); put("ok", true); put("lat", placeAt.lat); put("lng", placeAt.lng); put("address", "Crossing")
            }
            else -> buildJsonObject { put("type", "reply"); put("id", id); put("ok", false) }
        }
        messages.tryEmit(reply.toString())
    }

    /** The last camera command's coordinates, when it had any. */
    fun lastCameraLat(): Double? = scripts.lastOrNull { it.startsWith("zillitMap.camera(") }?.let { script ->
        val payload = Json.parseToJsonElement(script.removePrefix("zillitMap.camera(").removeSuffix(")")).jsonPrimitive.content
        Json.parseToJsonElement(payload).jsonObject["lat"]?.jsonPrimitive?.double
    }
}

internal class FakeShare(private val crew: List<SharePerson>) : MapShareHost {
    val sent = mutableListOf<Pair<List<String>, String>>()
    override fun people(): List<SharePerson> = crew
    override suspend fun send(userIds: List<String>, text: String): ZillitResult<Int> {
        sent += userIds to text
        return ZillitResult.Success(userIds.size)
    }
}

internal class FakePhotos : MapPhotos {
    val uploaded = mutableListOf<String>()
    override suspend fun pick(onRefused: (String) -> Unit): List<PickedPhoto> = emptyList()
    override suspend fun upload(photo: PickedPhoto): ZillitResult<MapAttachment> {
        uploaded += photo.name
        return ZillitResult.Success(MapAttachment(media = "map/${photo.name}", bucket = "b", region = "r", name = photo.name))
    }
    override suspend fun read(attachment: MapAttachment, preview: Boolean) = ZillitResult.Success(ByteArray(0))
}
