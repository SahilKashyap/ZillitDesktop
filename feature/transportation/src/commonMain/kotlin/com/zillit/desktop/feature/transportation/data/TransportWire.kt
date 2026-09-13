@file:Suppress("TooManyFunctions") // One body builder and one parser per record type.

package com.zillit.desktop.feature.transportation.data

import com.zillit.desktop.feature.transportation.domain.AllocationType
import com.zillit.desktop.feature.transportation.domain.DriverDetailsUpdate
import com.zillit.desktop.feature.transportation.domain.GeoPlace
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripDraft
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Bodies -------------------------------------------------------------------

/** The web's `payloadToAddVehicle`; `vehicleId` (camelCase) rides along on update. */
internal fun vehicleWire(draft: VehicleDraft, id: String?): JsonObject = buildJsonObject {
    put("vehicle_name", draft.name.trim())
    put("vehicle_number", draft.number.trim().uppercase())
    put("vehicle_type", draft.type)
    put("seating_capacity", draft.seats)
    put("owner_name", draft.ownerName.trim())
    put("owner_contact_number", draft.ownerContact.trim())
    put("owner_address", draft.ownerAddress.trim())
    put("country_code", draft.countryCode.trim())
    put("attachment", mediaListWire(draft.attachments))
    if (id != null) put("vehicleId", id)
}

/**
 * `POST driver/temporary-driver-vehicle` — the vehicle body plus the flags
 * that make its owner a temporary driver (`VehicleForm.jsx:213-218`).
 */
internal fun tempDriverVehicleWire(userId: String, draft: VehicleDraft): JsonObject = buildJsonObject {
    put("user_id", userId)
    put("is_private", true)
    put("is_temp_driver", true)
    vehicleWire(draft, id = null).forEach { (key, value) -> put(key, value) }
}

/**
 * `PUT driver/update-details` — only the keys that were set
 * (`FillInDetailsModal.jsx:handleUpdateDetails`). The web sends
 * `license_picture` and `documents` whenever the form is saved by a driver,
 * which is why both are lists here rather than "add one".
 */
internal fun driverDetailsWire(update: DriverDetailsUpdate): JsonObject = buildJsonObject {
    put("user_id", update.userId)
    update.address?.let { put("address", it.trim()) }
    update.phone?.let { put("phone", it.trim()) }
    update.countryCode?.let { put("country_code", it.trim()) }
    update.vehicleId?.let { put("vehicle_id", it) }
    update.isTripAssigned?.let { put("is_trip_assigned", it) }
    update.isTempDriver?.let { put("is_temp_driver", it) }
    update.licencePictures?.let { put("license_picture", mediaListWire(it)) }
    update.documents?.let { put("documents", mediaListWire(it)) }
}

internal fun mediaListWire(items: List<StoredMedia>): JsonArray =
    buildJsonArray { items.forEach { add(mediaWire(it)) } }

/** A stored file as every transport record spells one. */
internal fun mediaWire(media: StoredMedia): JsonObject = buildJsonObject {
    put("media", media.media)
    put("thumbnail", media.thumbnail)
    put("bucket", media.bucket)
    put("region", media.region)
    put("caption", media.caption)
    put("content_type", media.contentType)
    put("content_subtype", media.contentSubtype)
    put("name", media.name)
}

/**
 * `POST request` — the coordinator's request is born `assigned` and names
 * vehicle and driver; anyone else's is `pending` and names neither.
 */
internal fun tripWire(draft: TripDraft, coordinator: Boolean): JsonObject = buildJsonObject {
    put("mode", if (draft.selfAssign) "self" else "others")
    put("passengers", buildJsonArray { draft.passengers.forEach { add(passengerWire(it)) } })
    put("priority", draft.priority.wire)
    put("trip_status", if (coordinator) TripStatus.Assigned.wire else TripStatus.Pending.wire)
    put("cc_users", buildJsonArray { draft.ccUsers.forEach { add(JsonPrimitive(it)) } })
    if (coordinator) {
        draft.vehicleId?.let { put("vehicle_id", it) }
        draft.driverId?.let { put("driver_id", it) }
    }
}

/**
 * `PUT request` — passengers sorted by pickup, `trip_status` only when the
 * action moves state, `start_time`/`end_time` stamped by start/end, and
 * vehicle/driver only when they CHANGED. The driver's last location rides
 * along untouched, as the web sends it back (`TripDetailsModal.jsx:160`).
 */
internal fun tripUpdateWire(update: TripUpdate, nowMs: Long): JsonObject = buildJsonObject {
    put("tripRequestId", update.tripId)
    put("start_time", if (update.action == TripAction.Start) nowMs else update.current.startMs)
    put("end_time", if (update.action == TripAction.End) nowMs else update.current.endMs)
    put(
        "passengers",
        buildJsonArray { update.passengers.sortedBy { it.pickupMs }.forEach { add(passengerWire(it)) } },
    )
    put("cc_users", buildJsonArray { update.ccUsers.forEach { add(JsonPrimitive(it)) } })
    update.action.wireStatus?.let { put("trip_status", it) }
    update.current.driverLocation?.let { put("driver_location", placeWire(it)) }
    if (update.vehicleId != update.current.vehicleId) update.vehicleId?.let { put("vehicle_id", it) }
    if (update.driverId != update.current.driverId) update.driverId?.let { put("driver_id", it) }
}

internal fun passengerWire(p: TripPassenger): JsonObject = buildJsonObject {
    put("user_id", p.userId)
    put("name", p.name)
    put("designation", p.designation)
    put("pickup_time", p.pickupMs)
    put("pickup_locations", placeWire(p.pickup))
    put("dropoff_locations", placeWire(p.dropOff))
    put("pickup_time_string", p.pickupTimeText)
    put("driver_status", p.driverStatus)
}

private fun placeWire(place: GeoPlace): JsonObject = buildJsonObject {
    put("address", place.address)
    if (place.lat != null) put("lat", place.lat) else put("lat", JsonNull)
    if (place.long != null) put("long", place.long) else put("long", JsonNull)
}

/**
 * `POST/PUT request/permanent-trip` — a draft may be blank; a missing start
 * on a draft is stamped "now" as the web does (ZL-17606). Absent keys stay
 * absent (`undefined`), never null.
 */
internal fun permanentWire(draft: PermanentDraft, id: String?, status: PermanentStatus?): JsonObject = buildJsonObject {
    if (id != null) put("tripRequestId", id)
    put(
        "passengers",
        buildJsonArray {
            draft.passengers.forEach { p ->
                add(
                    buildJsonObject {
                        put("user_id", p.userId)
                        put("name", p.name)
                        put("pickup_name", "")
                        put("keep_name_private", false)
                    },
                )
            }
        },
    )
    put("trip_status",
        status?.wire ?: if (draft.asDraft) PermanentStatus.Draft.wire else PermanentStatus.Permanent.wire)
    draft.vehicleId?.let { put("vehicle_id", it) }
    draft.driverId?.let { put("driver_id", it) }
    if (draft.startMs > 0L) put("start_time", draft.startMs)
    if (draft.endMs > 0L) put("end_time", draft.endMs)
    put("cc_users", buildJsonArray { draft.ccUsers.forEach { add(JsonPrimitive(it)) } })
    put("full_day_trip", draft.fullDay)
}

// Parsers ------------------------------------------------------------------

internal fun parseVehicle(obj: JsonObject?): Vehicle? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return Vehicle(
        id = id,
        name = obj.text("vehicle_name"),
        number = obj.text("vehicle_number"),
        type = obj.text("vehicle_type"),
        seats = obj.int("seating_capacity") ?: 0,
        ownerName = obj.text("owner_name"),
        ownerContact = obj.text("owner_contact_number"),
        ownerAddress = obj.text("owner_address"),
        countryCode = obj.text("country_code"),
        allocation = AllocationType.fromWire(obj.text("allocation_type")),
        driverId = obj.text("driver_id").takeIf { it.isNotBlank() },
        isPrivate = obj.bool("is_private"),
        deletedMs = obj.long("deleted") ?: 0L,
        attachments = obj.mediaList("attachment"),
    )
}

internal fun parseUser(obj: JsonObject?): TransportUser? {
    if (obj == null) return null
    val id = obj.text("user_id", "_id").takeIf { it.isNotBlank() } ?: return null
    val name = obj.text("full_name", "name").ifBlank {
        listOf(obj.text("first_name"), obj.text("last_name")).filter { it.isNotBlank() }.joinToString(" ")
    }
    return TransportUser(
        userId = id,
        fullName = name,
        designation = obj.text("designation_name", "designation"),
        department = obj.text("department_name", "department"),
        status = obj.text("status"),
        phone = obj.text("phone"),
        isTempDriver = obj.bool("is_temp_driver"),
        vehicleId = obj.text("vehicle_id").takeIf { it.isNotBlank() },
        isTripAssigned = obj.bool("is_trip_assigned"),
        permanentTrip = obj.bool("permanent_trip"),
        fullDayTrip = obj.bool("full_day_trip"),
        countryCode = obj.text("country_code"),
        address = obj.text("address"),
        deviceId = obj.text("device_id"),
        avatar = parseMedia(obj["profile_picture"] as? JsonObject),
        licencePictures = obj.mediaList("license_picture"),
        documents = obj.mediaList("documents"),
        tripReminderMessage = obj.text("trip_reminder_message"),
        licenceVerified = obj.bool("is_licence_verified"),
        lastLocation = (obj["last_location"] as? JsonObject)?.let(::parsePlace)?.takeIf { place ->
            // `{lat: 0, long: 0}` is the service's "never reported", not the Gulf of Guinea.
            place.hasCoordinates && (place.lat != 0.0 || place.long != 0.0)
        },
    )
}

/** A stored file; a row with no `media` key is nothing to fetch and is dropped. */
internal fun parseMedia(obj: JsonObject?): StoredMedia? {
    if (obj == null) return null
    val media = obj.text("media").takeIf { it.isNotBlank() } ?: return null
    return StoredMedia(
        media = media,
        thumbnail = obj.text("thumbnail"),
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        caption = obj.text("caption"),
        contentType = obj.text("content_type"),
        contentSubtype = obj.text("content_subtype"),
        name = obj.text("name"),
    )
}

private fun JsonObject.mediaList(name: String): List<StoredMedia> =
    (this[name] as? JsonArray).items().mapNotNull { parseMedia(it as? JsonObject) }

internal fun parseTrip(obj: JsonObject?): TripRequest? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return TripRequest(
        id = id,
        raisedBy = obj.text("uploaded_by"),
        priority = obj.text("priority"),
        status = TripStatus.fromWire(obj.text("trip_status")),
        mode = obj.text("mode"),
        passengers = (obj["passengers"] as? JsonArray).items().mapNotNull { parsePassenger(it as? JsonObject) },
        ccUsers = (obj["cc_users"] as? JsonArray).items().mapNotNull { (it as? JsonPrimitive)?.content },
        vehicleId = obj.text("vehicle_id").takeIf { it.isNotBlank() },
        driverId = obj.text("driver_id").takeIf { it.isNotBlank() },
        startMs = obj.long("start_time") ?: 0L,
        endMs = obj.long("end_time") ?: 0L,
        createdMs = obj.long("created") ?: 0L,
        driverLocation = (obj["driver_location"] as? JsonObject)?.let(::parsePlace)?.takeIf { it.hasCoordinates },
    )
}

private fun parsePassenger(obj: JsonObject?): TripPassenger? {
    if (obj == null) return null
    // The backend has answered seconds for these at least once; the web's
    // `dateTimeForTripRequest` sniffs a 10-digit value and scales it.
    val pickup = obj.long("pickup_time")?.let { if (it in 1..SECONDS_CEILING) it * MILLIS else it } ?: 0L
    return TripPassenger(
        userId = obj.text("user_id"),
        name = obj.text("name"),
        designation = obj.text("designation"),
        pickupMs = pickup,
        pickupTimeText = obj.text("pickup_time_string"),
        pickup = parsePlace(obj["pickup_locations"] as? JsonObject),
        dropOff = parsePlace(obj["dropoff_locations"] as? JsonObject),
        driverStatus = obj.text("driver_status"),
    )
}

private fun parsePlace(obj: JsonObject?): GeoPlace =
    if (obj == null) GeoPlace() else GeoPlace(obj.text("address"), obj.double("lat"), obj.double("long", "lng"))

internal fun parsePermanent(obj: JsonObject?): PermanentTrip? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return PermanentTrip(
        id = id,
        status = PermanentStatus.fromWire(obj.text("trip_status")),
        passengers = (obj["passengers"] as? JsonArray).items().mapNotNull { row ->
            (row as? JsonObject)?.let { PermanentPassenger(it.text("user_id"), it.text("name")) }
        },
        ccUsers = (obj["cc_users"] as? JsonArray).items().mapNotNull { (it as? JsonPrimitive)?.content },
        vehicleId = obj.text("vehicle_id").takeIf { it.isNotBlank() },
        driverId = obj.text("driver_id").takeIf { it.isNotBlank() },
        startMs = obj.long("start_time") ?: 0L,
        endMs = obj.long("end_time") ?: 0L,
        fullDay = obj.bool("full_day_trip"),
        selfManage = obj.bool("self_manage"),
        createdMs = obj.long("created") ?: 0L,
    )
}

private const val SECONDS_CEILING = 9_999_999_999L
private const val MILLIS = 1_000L

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.int(vararg names: String): Int? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

private fun JsonObject.double(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun JsonObject.bool(name: String): Boolean =
    (firstOf(name) as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()
