@file:Suppress("TooManyFunctions") // One parser or body-builder per wire shape.

package com.zillit.desktop.feature.transporthub.data

import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDraft
import com.zillit.desktop.feature.transporthub.domain.HubAttachment
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubScheduleEntry
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.PermanentDraft
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RideDraft
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.StopAction
import com.zillit.desktop.feature.transporthub.domain.StoredPicture
import com.zillit.desktop.feature.transporthub.domain.SwapEntry
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripDraft
import com.zillit.desktop.feature.transporthub.domain.TripEvent
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleDraft
import com.zillit.desktop.feature.transporthub.domain.VehicleTypeOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Reading the hub's answers.
 *
 * Three shapes carry a list, and which one arrives depends on the route and on
 * how old its handler is: a bare array, `{ data: [...] }`, or the paged
 * `{ data: { data: [...], pagination } }`. A few older routes name the array
 * after the entity instead (`{ vehicles: [...] }`). [rows] walks all four, in
 * the order the web's `pickArr` does, so a route that changes shape under us
 * does not empty a list.
 */

/**
 * The rows inside whichever envelope shape arrived.
 *
 * [keys] are the entity names an older handler might have used instead of
 * `data` — pass every spelling the route has been seen to answer with.
 */
internal fun JsonElement?.rows(vararg keys: String): List<JsonObject> {
    val element = this ?: return emptyList()
    (element as? JsonArray)?.let { return it.objects() }
    val obj = element as? JsonObject ?: return emptyList()
    val inner = obj["data"] as? JsonObject
    // In order: the entity's own name at either depth, then `data` at either —
    // the four shapes the hub's routes have been seen to answer with.
    val candidates = keys.map { obj[it] } +
        listOf(obj["data"]) +
        keys.map { inner?.get(it) } +
        listOf(inner?.get("data"))
    return candidates.firstNotNullOfOrNull { it as? JsonArray }?.objects() ?: emptyList()
}

/**
 * The single record inside whichever envelope shape arrived.
 *
 * A mutation answers either the record itself or `{ data: <record> }`, and a
 * handful wrap it again under its own name (`{ trip: … }`, `{ run: … }`).
 */
internal fun JsonElement?.record(vararg names: String): JsonObject? {
    val obj = this as? JsonObject ?: return null
    val inner = obj["data"] as? JsonObject
    val named = (names.map { obj[it] } + names.map { inner?.get(it) })
        .firstNotNullOfOrNull { it as? JsonObject }
    // `data` itself is the record unless it is a paged envelope, whose own
    // `data` is an array; failing all that, the record came back bare, which
    // an id and no envelope keys is how it is recognised.
    return named
        ?: inner?.takeIf { it["data"] !is JsonArray }
        ?: obj.takeIf { it["_id"] != null || it["id"] != null }
}

private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

private fun JsonObject.str(vararg keys: String): String {
    keys.forEach { key ->
        val value = this[key]
        if (value == null || value is JsonNull) return@forEach
        (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

private fun JsonObject.bool(vararg keys: String): Boolean {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.booleanOrNull?.let { return it }
        // Several flags arrive as the strings "true"/"false", and a few as 1/0.
        when (primitive.content.trim().lowercase()) {
            "true", "1" -> return true
            "false", "0" -> return false
        }
    }
    return false
}

private fun JsonObject.long(vararg keys: String): Long {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.longOrNull?.let { return it }
        // Dates round-trip as epoch-ms *strings* on the permanent-trip routes.
        primitive.content.trim().toLongOrNull()?.let { return it }
    }
    return 0
}

private fun JsonObject.int(vararg keys: String): Int? {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.content.trim().toIntOrNull()?.let { return it }
    }
    return null
}

private fun JsonObject.double(vararg keys: String): Double {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.doubleOrNull?.let { return it }
        primitive.content.trim().toDoubleOrNull()?.let { return it }
    }
    return 0.0
}

private fun JsonObject.obj(vararg keys: String): JsonObject? {
    keys.forEach { key -> (this[key] as? JsonObject)?.let { return it } }
    return null
}

private fun JsonObject.array(vararg keys: String): List<JsonObject> {
    keys.forEach { key -> (this[key] as? JsonArray)?.let { return it.objects() } }
    return emptyList()
}

private fun JsonObject.strings(vararg keys: String): List<String> {
    keys.forEach { key ->
        (this[key] as? JsonArray)?.let { array ->
            return array.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        }
    }
    return emptyList()
}

private fun JsonObject.id(): String = str("_id", "id")

/**
 * The id behind a reference field.
 *
 * A reference arrives either as a bare id string or as the whole populated
 * document, and which one depends on the route — so every read of
 * `driver_id`, `vehicle_id` or `user_id` has to cope with both.
 */
private fun JsonObject.refId(vararg keys: String): String? {
    keys.forEach { key ->
        when (val value = this[key]) {
            is JsonPrimitive -> value.content.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            is JsonObject -> value.id().takeIf { it.isNotBlank() }?.let { return it }
            else -> Unit
        }
    }
    return null
}

/** The display name behind a reference field, when the document came populated. */
private fun JsonObject.refName(vararg keys: String): String {
    keys.forEach { key ->
        (this[key] as? JsonObject)?.let { doc ->
            doc.str("full_name", "driver_name", "name").takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return ""
}

private fun JsonObject.refVehicleLabel(vararg keys: String): String {
    keys.forEach { key ->
        (this[key] as? JsonObject)?.let { doc ->
            doc.str("vehicle_name", "name", "vehicle_number", "license_plate")
                .takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return ""
}

/**
 * A location.
 *
 * `long` is the server's longitude. `lng` appears only where a map picker
 * wrote it, so it is accepted on the way in and never produced on the way out.
 * A plain string is a legacy row that stored the address alone.
 */
internal fun JsonElement?.toLocation(): HubLocation = when (this) {
    is JsonPrimitive -> HubLocation(address = content.takeIf { it != "null" }.orEmpty())
    is JsonObject -> HubLocation(
        address = str("address", "name", "formatted_address"),
        lat = double("lat", "latitude"),
        long = double("long", "lng", "longitude"),
    )
    else -> HubLocation.Empty
}

private fun JsonObject.picture(vararg keys: String): StoredPicture? {
    keys.forEach { key ->
        val doc = this[key] as? JsonObject ?: return@forEach
        val media = doc.str("media", "key").takeIf { it.isNotBlank() } ?: return@forEach
        return StoredPicture(
            media = media,
            bucket = doc.str("bucket").takeIf { it.isNotBlank() },
            region = doc.str("region").takeIf { it.isNotBlank() },
            thumbnail = doc.str("thumbnail").takeIf { it.isNotBlank() },
        )
    }
    return null
}

internal fun JsonObject.toPassenger(): HubPassenger = HubPassenger(
    id = id().takeIf { it.isNotBlank() },
    userId = refId("user_id"),
    name = str("name", "full_name", "passenger_name"),
    contactNumber = str("contact_number", "phone", "mobile_number"),
    pickupName = str("pickup_name"),
    // A passenger's own ends are spelled both ways across the two routes:
    // `pickup_locations` on a trip row, `pickup_location` on a ride's.
    pickup = (this["pickup_location"] ?: this["pickup_locations"]).toLocation(),
    dropoff = (this["dropoff_location"] ?: this["dropoff_locations"]).toLocation(),
    scheduledPickupTimeMs = long("scheduled_pickup_time").takeIf { it > 0 },
    keepNamePrivate = bool("keep_name_private"),
    status = str("status", "passenger_status"),
    profilePicture = picture("profile_picture", "profilePicture"),
)

internal fun JsonObject.toStop(index: Int): HubStop = HubStop(
    id = id().takeIf { it.isNotBlank() },
    location = (this["location"] ?: this["stop_location"]).toLocation(),
    // The server calls it `stop_type` on the add route and `action` on a row.
    action = StopAction.from(str("stop_type", "action", "type")),
    order = int("order") ?: index,
    passengerName = str("passenger_name"),
    passengers = array("passengers").map { it.toPassenger() },
    isCompleted = bool("is_completed", "completed"),
)

internal fun JsonObject.toAttachment(): HubAttachment? {
    val media = str("media").takeIf { it.isNotBlank() } ?: return null
    return HubAttachment(
        media = media,
        name = str("name", "original_file_name"),
        thumbnail = str("thumbnail").takeIf { it.isNotBlank() },
        contentType = str("content_type").ifBlank { "image" },
        contentSubtype = str("content_subtype", "file_extension"),
        bucket = str("bucket").takeIf { it.isNotBlank() },
        region = str("region").takeIf { it.isNotBlank() },
        caption = str("caption", "description"),
        fileSize = str("file_size", "size").ifBlank { "0" },
        width = int("width"),
        height = int("height"),
        uniqueId = str("unique_id").takeIf { it.isNotBlank() },
    )
}

internal fun JsonObject.toVehicle(): Vehicle? {
    val id = id().takeIf { it.isNotBlank() } ?: return null
    return Vehicle(
        id = id,
        name = str("name", "vehicle_name"),
        licensePlate = str("license_plate", "vehicle_number"),
        vehicleType = str("vehicle_type"),
        allocation = VehicleAllocation.from(str("allocation_status", "status")),
        capacity = int("capacity", "seating_capacity"),
        fuelType = str("fuel_type"),
        make = str("make"),
        model = str("model"),
        year = int("year"),
        color = str("color", "colour"),
        // A record with no flag at all is the production's own car: that is
        // what `is_private` defaults to on create.
        isPrivate = this["is_private"]?.let { bool("is_private") } ?: true,
        ownerName = str("owner_name"),
        ownerContactNumber = str("owner_contact_number"),
        attachments = array("attachments").mapNotNull { it.toAttachment() },
        deleted = bool("deleted"),
    )
}

internal fun JsonObject.toVehicleTypeOption(): VehicleTypeOption? {
    val label = str("label", "name", "vehicle_type").takeIf { it.isNotBlank() } ?: return null
    return VehicleTypeOption(label = label, isCustom = bool("is_custom"))
}

internal fun JsonObject.toDriver(): Driver? {
    val id = id().takeIf { it.isNotBlank() } ?: return null
    return Driver(
        id = id,
        userId = refId("user_id"),
        fullName = str("full_name", "driver_name", "name").ifBlank { refName("user_id") },
        phone = str("phone", "contact_number", "mobile_number"),
        licenseNumber = str("license_number", "licence_number"),
        designation = str("designation", "designation_name"),
        isOnDuty = bool("is_on_duty"),
        isTempDriver = bool("is_temp_driver"),
        // Absent means available: only an explicit false takes a driver out.
        isAvailable = this["is_available"]?.let { bool("is_available") } ?: true,
        pendingDetails = bool("pending_details"),
        missingFields = strings("missing_fields"),
        addedBy = str("added_by").ifBlank { refName("added_by") },
        licenseFrontUrl = str("license_front_url"),
        licenseBackUrl = str("license_back_url"),
        profilePicture = picture("profile_picture", "profilePicture"),
        assignedVehicleId = refId("assigned_vehicle", "vehicle_id"),
        rating = double("rating").takeIf { it > 0 },
        // `{0,0}` is "never reported", not the Gulf of Guinea — drop it.
        lastLocation = (this["last_location"] ?: this["driver_location"]).toLocation()
            .takeIf { it.hasCoordinates },
        createdMs = long("created", "created_at"),
    )
}

internal fun JsonObject.toRideRequest(): RideRequest? {
    val id = id().takeIf { it.isNotBlank() } ?: return null
    val requester = obj("requester_snapshot", "requester")
    val passengers = array("passengers").map { it.toPassenger() }
    return RideRequest(
        id = id,
        status = RideStatus.from(str("status", "request_status")),
        priority = RidePriority.from(str("priority")),
        pickup = (this["pickup_locations"] ?: this["pickup_location"]).toLocation(),
        dropoff = (this["dropoff_locations"] ?: this["dropoff_location"]).toLocation(),
        passengers = passengers,
        stops = array("stops").mapIndexed { index, stop -> stop.toStop(index) }
            .sortedBy { it.order },
        notes = str("notes"),
        // The same cascade the card header resolves a row's owner by, so
        // "My requests" matches who the row is attributed to on screen.
        requesterUserId = requester?.refId("user_id")
            ?: requester?.id()?.takeIf { it.isNotBlank() }
            ?: passengers.firstOrNull()?.userId,
        requesterName = requester?.str("full_name", "name").orEmpty(),
        assignedDriverId = refId("assigned_driver", "driver_id"),
        assignedDriverName = refName("assigned_driver", "driver_id"),
        assignedVehicleId = refId("assigned_vehicle", "vehicle_id"),
        assignedVehicleLabel = refVehicleLabel("assigned_vehicle", "vehicle_id"),
        scheduledPickupTimeMs = long("scheduled_pickup_time", "pickup_time").takeIf { it > 0 },
        createdMs = long("created", "created_at"),
        rejectionReason = str("rejection_reason", "reject_reason"),
        cancellationReason = str("cancellation_reason", "cancel_reason", "reason"),
        etaMinutes = int("eta_minutes", "eta"),
    )
}

internal fun JsonObject.toTrip(): Trip? {
    val id = id().takeIf { it.isNotBlank() } ?: return null
    return Trip(
        id = id,
        title = str("title"),
        kind = TripResourceKind.from(str("resource_type")),
        status = TripStatus.from(str("trip_status", "status")),
        priority = RidePriority.from(str("priority")),
        tripType = str("trip_type").ifBlank { "one_time" },
        passengers = array("passengers").map { it.toPassenger() },
        stops = array("stops").mapIndexed { index, stop -> stop.toStop(index) }.sortedBy { it.order },
        pickup = (this["pickup_locations"] ?: this["pickup_location"]).toLocation(),
        dropoff = (this["dropoff_locations"] ?: this["dropoff_location"]).toLocation(),
        driverId = refId("assigned_driver", "driver_id", "driver"),
        driverName = refName("assigned_driver", "driver_id", "driver"),
        vehicleId = refId("assigned_vehicle", "vehicle_id", "vehicle"),
        vehicleLabel = refVehicleLabel("assigned_vehicle", "vehicle_id", "vehicle"),
        scheduledPickupTimeMs = long("scheduled_pickup_time", "pickup_time").takeIf { it > 0 },
        notes = str("notes"),
        // `active_days` is an integer array here (Mon=1 … Sun=0 or 7) and a
        // list of day names on the permanent-trip routes. [HubDay.from] takes
        // either, so the two never need telling apart at the call site.
        activeDays = activeDays(),
        createdMs = long("created", "created_at"),
    )
}

private fun JsonObject.activeDays(): List<HubDay> {
    val array = this["active_days"] as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            primitive.longOrNull?.let { HubDay.fromIndex(it.toInt()) } ?: HubDay.from(primitive.content)
        }
        .distinct()
        .sortedBy { HubDay.weekOrder.indexOf(it) }
}

internal fun JsonObject.toTripEvent(): TripEvent = TripEvent(
    id = id(),
    type = str("event_type", "type"),
    description = str("description", "message", "note"),
    atMs = long("created", "created_at", "at", "timestamp"),
    actorName = str("actor_name").ifBlank { refName("actor", "actor_id", "created_by") },
)

internal fun JsonObject.toScheduleEntry(): HubScheduleEntry? {
    val day = HubDay.from(str("day")) ?: return null
    return HubScheduleEntry(
        day = day,
        startTimeMs = long("start_time"),
        endTime = str("end_time"),
        // Absent means the row is on; only an explicit false turns a day off.
        isActive = this["is_active"]?.let { bool("is_active") } ?: true,
    )
}

internal fun JsonObject.toSwapEntry(): SwapEntry = SwapEntry(
    atMs = long("created", "created_at", "at", "swapped_at"),
    reason = str("reason"),
    fromId = refId("from", "from_id", "old_driver_id", "old_vehicle_id").orEmpty(),
    toId = refId("to", "to_id", "new_driver_id", "new_vehicle_id").orEmpty(),
    isDriver = this["old_driver_id"] != null || this["new_driver_id"] != null ||
        str("type").equals("driver", ignoreCase = true),
    byName = str("by_name").ifBlank { refName("swapped_by", "created_by") },
)

internal fun JsonObject.toPermanentTrip(): PermanentTrip? {
    val id = id().takeIf { it.isNotBlank() } ?: return null
    return PermanentTrip(
        id = id,
        title = str("title"),
        status = str("status"),
        tripStatus = str("trip_status"),
        pickup = this["pickup_location"].toLocation(),
        dropoff = this["dropoff_location"].toLocation(),
        driverId = refId("driver_id", "driver"),
        vehicleId = refId("vehicle_id", "vehicle"),
        passengers = array("passengers").map { it.toPassenger() },
        startDateMs = long("start_date"),
        endDateMs = long("end_date"),
        scheduledTimeMs = long("scheduled_time"),
        schedule = array("schedule").mapNotNull { it.toScheduleEntry() },
        isFullDayTrip = bool("full_day_trip"),
        selfManage = bool("self_manage"),
        isExclusive = bool("is_exclusive"),
        notes = str("notes"),
        swapHistory = array("swap_history").map { it.toSwapEntry() },
        rawFields = scalarFields(),
    )
}

/**
 * Every scalar the server sent, kept as strings.
 *
 * Promoting a draft re-sends the whole record so the server's validator can
 * check it, and the record has keys this client never models. Keeping the
 * scalars means they round-trip instead of being dropped on the way back.
 * The server's own keys are excluded — they are rebuilt server-side, and
 * echoing them back makes the validator reject the write.
 */
private fun JsonObject.scalarFields(): Map<String, String> = entries
    .filter { (key, value) -> key !in SERVER_OWNED_KEYS && value is JsonPrimitive && value !is JsonNull }
    .associate { (key, value) -> key to (value as JsonPrimitive).content }

private val SERVER_OWNED_KEYS = setOf(
    "_id", "__v", "project_id", "created_by", "created", "updated", "deleted",
    "trip_status", "swap_history", "reminder", "cc_users", "start_time", "end_time",
)

internal fun JsonObject.toActiveCar(): ActiveCar = ActiveCar(
    rideId = str("ride_request_id", "_id", "id"),
    driverId = refId("assigned_driver", "driver_id", "driver"),
    driverName = refName("assigned_driver", "driver_id", "driver"),
    vehicleId = refId("assigned_vehicle", "vehicle_id", "vehicle"),
    vehicleLabel = refVehicleLabel("assigned_vehicle", "vehicle_id", "vehicle"),
    status = RideStatus.from(str("status", "request_status")),
    passengers = array("passengers").map { it.toPassenger() },
    pickup = (this["pickup_locations"] ?: this["pickup_location"]).toLocation(),
    dropoff = (this["dropoff_locations"] ?: this["dropoff_location"]).toLocation(),
    etaMinutes = int("eta_minutes", "eta"),
)

internal fun JsonObject.toProjectPerson(): ProjectPerson? {
    // A project-user row is keyed by `user_id`; the join record's own `_id`
    // exists but nothing points at it.
    val userId = refId("user_id") ?: id().takeIf { it.isNotBlank() } ?: return null
    val name = str("full_name", "name").ifBlank {
        listOf(str("first_name"), str("last_name")).filter { it.isNotBlank() }.joinToString(" ")
    }
    return ProjectPerson(
        userId = userId,
        name = name.ifBlank { str("email") },
        email = str("email", "zillit_email"),
        phone = str("phone", "mobile_number"),
        designation = str("designation_name", "designation", "designation_identifier"),
        profilePicture = picture("profile_picture", "profilePicture"),
    )
}

/*
 * Writing to the hub.
 *
 * A location goes out as `{ address, lat, long }` and nothing else. Any field
 * the coordinator left blank is omitted rather than sent empty, because
 * several validators treat `""` as a value and reject it.
 */

internal fun HubLocation.toWire(): JsonObject = buildJsonObject {
    put("address", address)
    put("lat", lat)
    put("long", long)
}

private fun JsonObject.orNull(location: HubLocation): JsonObject? = takeIf { !location.isEmpty }

internal fun vehicleBody(draft: VehicleDraft): JsonObject = buildJsonObject {
    put("name", draft.name)
    // The model's own field and the legacy alias both go out: handlers of
    // different ages read one or the other, and `vehicle_number` is required.
    put("vehicle_name", draft.name)
    put("vehicle_number", draft.licensePlate)
    put("license_plate", draft.licensePlate)
    put("vehicle_type", draft.vehicleType)
    draft.capacity?.let {
        put("capacity", it)
        put("seating_capacity", it)
    }
    draft.fuelType.takeIf { it.isNotBlank() }?.let { put("fuel_type", it) }
    put("is_private", draft.isPrivate)
    draft.color.takeIf { it.isNotBlank() }?.let { put("color", it) }
    draft.make.takeIf { it.isNotBlank() }?.let { put("make", it) }
    draft.model.takeIf { it.isNotBlank() }?.let { put("model", it) }
    draft.year?.let { put("year", it) }
    // Required together, and only when the production does not own the car.
    if (!draft.isPrivate) {
        put("owner_name", draft.ownerName)
        put("owner_contact_number", draft.ownerContactNumber)
    }
    if (draft.attachments.isNotEmpty()) {
        put(
            "attachments",
            buildJsonArray { draft.attachments.forEach { add(it.toWire()) } },
        )
    }
}

internal fun HubAttachment.toWire(): JsonObject = buildJsonObject {
    put("media", media)
    put("thumbnail", thumbnail ?: media)
    put("name", name.ifBlank { media })
    put("content_type", contentType)
    put("content_subtype", contentSubtype)
    bucket?.let { put("bucket", it) }
    region?.let { put("region", it) }
    put("caption", caption)
    // A string, not a number — the route's own example sends it quoted.
    put("file_size", fileSize)
    width?.let { put("width", it) }
    height?.let { put("height", it) }
    uniqueId?.let { put("unique_id", it) }
}

internal fun driverBody(draft: DriverDraft): JsonObject = buildJsonObject {
    put("user_id", draft.userId)
    put("full_name", draft.fullName)
    put("is_temp_driver", draft.isTempDriver)
    // Strings always: the route answers 406 on a non-string, so a blank is "".
    put("phone", draft.phone.trim())
    put("license_number", draft.licenseNumber.trim())
    put("license_front_url", draft.licenseFrontUrl)
    put("license_back_url", draft.licenseBackUrl)
    if (draft.pendingDetails) put("pending_details", true)
    draft.designation.takeIf { it.isNotBlank() }?.let { put("designation", it) }
}

internal fun passengerBody(passenger: HubPassenger, forTrip: Boolean): JsonObject = buildJsonObject {
    // An id round-trips so the server updates the row in place instead of
    // adding a duplicate. A row the coordinator just typed has none.
    passenger.id?.let { put("_id", it) }
    // Only a project member carries one. A guest row with `user_id: ""` is
    // rejected by the required-field validator.
    passenger.userId?.takeIf { it.isNotBlank() }?.let { put("user_id", it) }
    put("name", passenger.name)
    passenger.contactNumber.takeIf { it.isNotBlank() }?.let { put("contact_number", it) }
    passenger.pickupName.takeIf { it.isNotBlank() }?.let { put("pickup_name", it) }
    if (forTrip) {
        // Singular embedded objects despite the plural names — an array here
        // throws ObjectExpectedError.
        passenger.pickup.toWire().orNull(passenger.pickup)?.let { put("pickup_locations", it) }
        passenger.dropoff.toWire().orNull(passenger.dropoff)?.let { put("dropoff_locations", it) }
    } else {
        passenger.pickup.toWire().orNull(passenger.pickup)?.let { put("pickup_location", it) }
        passenger.dropoff.toWire().orNull(passenger.dropoff)?.let { put("dropoff_location", it) }
    }
    passenger.scheduledPickupTimeMs?.takeIf { it > 0 }?.let { put("scheduled_pickup_time", it) }
    if (passenger.keepNamePrivate) put("keep_name_private", true)
}

internal fun stopBody(stop: HubStop): JsonObject = buildJsonObject {
    stop.id?.let { put("_id", it) }
    put("location", stop.location.toWire())
    // `stop_type` on the add-stop route; a row read back calls it `action`.
    put("stop_type", stop.action.wire)
    put("action", stop.action.wire)
    put("order", stop.order)
    val flatName = stop.passengerName.ifBlank { stop.passengers.firstOrNull()?.name.orEmpty() }
    if (flatName.isNotBlank()) put("passenger_name", flatName)
    if (stop.passengers.isNotEmpty()) {
        put(
            "passengers",
            buildJsonArray {
                stop.passengers.forEach { passenger ->
                    add(
                        buildJsonObject {
                            passenger.id?.let { put("_id", it) }
                            passenger.userId?.takeIf { it.isNotBlank() }?.let { put("user_id", it) }
                            put("name", passenger.name)
                        },
                    )
                }
            },
        )
    }
}

internal fun rideBody(draft: RideDraft): JsonObject = buildJsonObject {
    put("call_type", "coordinator")
    put("priority", draft.priority.wire)
    put("pickup_location", draft.pickup.toWire())
    put("dropoff_location", draft.dropoff.toWire())
    put(
        "passengers",
        buildJsonArray { draft.passengers.forEach { add(passengerBody(it, forTrip = false)) } },
    )
    put("passenger_count", draft.passengers.size)
    put("notes", draft.notes)
    // The whole desired set, every time: a stop left out of this array is
    // deleted server-side, and one with an `_id` is updated in place.
    put(
        "stops",
        buildJsonArray {
            draft.stops
                .filter { !it.location.isEmpty }
                .forEachIndexed { index, stop -> add(stopBody(stop.copy(order = index))) }
        },
    )
    draft.scheduledPickupTimeMs?.takeIf { it > 0 }?.let {
        put("pickup_time", it)
        put("scheduled_pickup_time", it)
    }
}

internal fun tripBody(draft: TripDraft): JsonObject = buildJsonObject {
    put("trip_type", draft.tripType)
    put("priority", draft.priority.wire)
    put(
        "passengers",
        buildJsonArray { draft.passengers.forEach { add(passengerBody(it, forTrip = true)) } },
    )
    // The trip's own time is the first passenger's — the form collects it there.
    draft.passengers.firstNotNullOfOrNull { it.scheduledPickupTimeMs?.takeIf { time -> time > 0 } }
        ?.let { put("scheduled_pickup_time", it) }
    draft.notes.takeIf { it.isNotBlank() }?.let { put("notes", it) }
    draft.title.trim().takeIf { it.isNotBlank() }?.let { put("title", it) }
    draft.driverId?.takeIf { it.isNotBlank() }?.let { put("driver_id", it) }
    draft.vehicleId?.takeIf { it.isNotBlank() }?.let { put("vehicle_id", it) }
}

internal fun permanentBody(draft: PermanentDraft): JsonObject = buildJsonObject {
    put("title", draft.title)
    put("pickup_location", draft.pickup.toWire())
    put("dropoff_location", draft.dropoff.toWire())
    // Explicit null, not omitted: that is how the allocation is cleared.
    draft.driverId?.takeIf { it.isNotBlank() }?.let { put("driver_id", it) } ?: put("driver_id", JsonNull)
    draft.vehicleId?.takeIf { it.isNotBlank() }?.let { put("vehicle_id", it) }
        ?: put("vehicle_id", JsonNull)
    put(
        "passengers",
        buildJsonArray {
            draft.passengers
                .filter { it.userId != null || it.name.isNotBlank() || !it.pickup.isEmpty || !it.dropoff.isEmpty }
                .forEach { add(passengerBody(it, forTrip = false)) }
        },
    )
    // Epoch ms as *strings*: the validator rejects raw numbers here with
    // "endDate must be a string". An unset date is "", never "0".
    put("start_date", draft.startDateMs.takeIf { it > 0 }?.toString().orEmpty())
    put("end_date", draft.endDateMs.takeIf { it > 0 }?.toString().orEmpty())
    put("scheduled_time", draft.scheduledTimeMs.takeIf { it > 0 }?.toString().orEmpty())
    // `schedule[].start_time` stays numeric — that path takes either.
    put(
        "schedule",
        buildJsonArray {
            draft.activeDays.forEach { day ->
                add(
                    buildJsonObject {
                        put("day", day.wire)
                        put("start_time", draft.scheduledTimeMs.coerceAtLeast(0))
                        put("is_active", true)
                    },
                )
            }
        },
    )
    put("full_day_trip", draft.isFullDayTrip)
    put("self_manage", draft.selfManage)
    put("is_exclusive", draft.isExclusive)
    put("notes", draft.notes)
    put("status", draft.state.wire.ifBlank { PermanentState.Draft.wire })
}

/**
 * The body that promotes a draft: the record as the server sent it, with
 * `status` overridden. See [HubRepository.activateDraft] for why the whole
 * record goes back rather than just the status.
 */
internal fun promoteBody(trip: PermanentTrip): JsonObject = buildJsonObject {
    trip.rawFields.forEach { (key, value) -> put(key, value) }
    // The nested shapes are not scalars, so they are rebuilt here.
    put("pickup_location", trip.pickup.toWire())
    put("dropoff_location", trip.dropoff.toWire())
    put(
        "passengers",
        buildJsonArray { trip.passengers.forEach { add(passengerBody(it, forTrip = false)) } },
    )
    put(
        "schedule",
        buildJsonArray {
            trip.schedule.forEach { entry ->
                add(
                    buildJsonObject {
                        put("day", entry.day.wire)
                        put("start_time", entry.startTimeMs)
                        put("is_active", entry.isActive)
                    },
                )
            }
        },
    )
    put("status", PermanentState.Active.wire)
}
