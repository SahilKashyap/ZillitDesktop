package com.zillit.desktop.feature.transportation.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The Transportation tool — vehicles, drivers, pickup requests and permanent
 * allocations on the transport service. NOT the newer Transportation Hub,
 * which is a different tool on the same host.
 */
data class Vehicle(
    val id: String,
    val name: String,
    val number: String,
    val type: String,
    val seats: Int,
    val ownerName: String,
    val ownerContact: String,
    val ownerAddress: String,
    val countryCode: String,
    val allocation: AllocationType,
    val driverId: String?,
    /** A temp driver's personal vehicle. */
    val isPrivate: Boolean,
    /** Epoch ms when soft-deleted; 0 while live. */
    val deletedMs: Long,
    /** The photographs the form attached (`attachment`), first one is the card's picture. */
    val attachments: List<StoredMedia> = emptyList(),
) {
    val isLive: Boolean get() = deletedMs == 0L

    /** The web's rule for edit/delete: only unassigned vehicles, and never a personal one for delete. */
    val editable: Boolean get() = allocation == AllocationType.Remained || allocation == AllocationType.Allocated
    val deletable: Boolean get() = editable && !isPrivate

    /** "Transit · AB12 CDE", or whichever half exists. */
    val label: String get() = listOf(name, number).filter { it.isNotBlank() }.joinToString(" · ")
}

enum class AllocationType(val wire: String, val label: String) {
    Remained("remained", "Available for trips"),
    Allocated("allocated", "Available for trips"),
    Assigned("assigned", "Assigned to trip"),
    Permanent("permanent", "Assigned permanently"),
    ;

    companion object {
        fun fromWire(value: String?): AllocationType =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Remained
    }
}

data class VehicleDraft(
    val name: String,
    val number: String,
    val type: String,
    val seats: Int,
    val ownerName: String = "",
    val ownerContact: String = "",
    val ownerAddress: String = "",
    val countryCode: String = "",
    val attachments: List<StoredMedia> = emptyList(),
) {
    /** The web's form rules, in its order. */
    fun problem(): String? = when {
        name.isBlank() -> "A brand name is required"
        name.length > NAME_MAX -> "The brand name is at most $NAME_MAX characters"
        number.isBlank() -> "A vehicle number is required"
        number.length > NAME_MAX -> "The vehicle number is at most $NAME_MAX characters"
        seats < SEATS_MIN || seats > SEATS_MAX -> "Seats must be between $SEATS_MIN and $SEATS_MAX"
        ownerName.length > OWNER_MAX -> "The owner name is at most $OWNER_MAX characters"
        ownerContact.isNotBlank() && ownerContact.length !in CONTACT_RANGE -> "The contact number is 5 to 20 characters"
        ownerAddress.length > ADDRESS_MAX -> "The address is at most $ADDRESS_MAX characters"
        attachments.size > IMAGES_MAX -> "At most $IMAGES_MAX images"
        else -> null
    }

    companion object {
        const val IMAGES_MAX = 5
        private const val NAME_MAX = 20
        private const val OWNER_MAX = 40
        private const val ADDRESS_MAX = 50
        private const val SEATS_MIN = 2
        private const val SEATS_MAX = 100
        private val CONTACT_RANGE = 5..20
    }
}

/**
 * A file in the production's object store, as every transport record spells
 * one: `{media, thumbnail, bucket, region, caption, content_type,
 * content_subtype, name}`. A licence picture is captioned `Front`/`Back`;
 * a vehicle photograph and a driver's document carry their file name.
 */
data class StoredMedia(
    val media: String,
    val thumbnail: String = "",
    val bucket: String = "",
    val region: String = "",
    val caption: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
    val name: String = "",
) {
    /** Whether this is a picture — the web branches on `content_type` and falls back to the extension. */
    val isImage: Boolean
        get() = contentType.startsWith("image", ignoreCase = true) ||
            (contentType.isBlank() && extension in IMAGE_EXTENSIONS)

    val extension: String
        get() = contentSubtype.lowercase().ifBlank { (name.ifBlank { media }).substringAfterLast('.', "").lowercase() }

    val displayName: String get() = name.ifBlank { media.substringAfterLast('/') }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    }
}

/**
 * A crew member as the transport tool sees them — `project/users` carries
 * the driver flags the desktop's own crew snapshot never reads.
 */
@Suppress("LongParameterList") // A wire row, column for column.
data class TransportUser(
    val userId: String,
    val fullName: String,
    val designation: String,
    val department: String,
    /** `accepted`, `pending`, `left`, `removed`. */
    val status: String,
    val phone: String,
    val isTempDriver: Boolean,
    val vehicleId: String?,
    val isTripAssigned: Boolean,
    val permanentTrip: Boolean,
    val fullDayTrip: Boolean,
    val countryCode: String = "",
    val address: String = "",
    val deviceId: String = "",
    val avatar: StoredMedia? = null,
    /** Front and back, captioned so; the driver uploads them from Fill in details. */
    val licencePictures: List<StoredMedia> = emptyList(),
    val documents: List<StoredMedia> = emptyList(),
    /** What the coordinator asked the driver for, shown to the driver until they upload. */
    val tripReminderMessage: String = "",
    val licenceVerified: Boolean = false,
    val lastLocation: GeoPlace? = null,
) {
    val isAccepted: Boolean get() = status.equals("accepted", ignoreCase = true)

    /** The web's `Disabled` tag: someone who left or was removed. */
    val isGone: Boolean get() = status.equals("left", ignoreCase = true) || status.equals("removed", ignoreCase = true)

    /** The web's `getUserStatus`: a driver by designation, or a temporary one. */
    fun isDriver(driverDesignations: List<String>): Boolean =
        isTempDriver || driverDesignations.any { it.equals(designation, ignoreCase = true) }

    /** The web's `getStatusAllotmentValue`. */
    val availability: String
        get() = when {
            permanentTrip -> "Permanent allocated"
            isTripAssigned -> "Assigned to trip"
            else -> "Available"
        }

    val isAvailable: Boolean get() = !permanentTrip && !isTripAssigned

    /** "driver_label" → "Driver"; the web strips the suffix and capitalises. */
    val designationLabel: String get() = designation.humanLabel()

    val hasLicence: Boolean get() = licencePictures.isNotEmpty()
    val hasDocuments: Boolean get() = documents.isNotEmpty()

    val phoneLine: String get() = listOf(countryCode, phone).filter { it.isNotBlank() }.joinToString(" ")
}

/** `some_label_key_label` → "Some Label Key" — the web's `capitalizeWords(name.replace('label', ''))`. */
fun String.humanLabel(): String = removeSuffix("_label").split('_').filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** `{address, lat, long}` — the wire spells longitude `long`. */
data class GeoPlace(val address: String = "", val lat: Double? = null, val long: Double? = null) {
    val hasCoordinates: Boolean get() = lat != null && long != null

    /** Where a browser shows this place — the web's passenger rows link here. */
    val mapsUrl: String? get() = if (hasCoordinates) "https://www.google.com/maps?q=$lat,$long" else null
}

enum class TripStatus(val wire: String, val label: String) {
    Pending("pending", "Pending"),
    Assigned("assigned", "Assigned"),
    InProgress("inprogress", "In Progress"),
    Completed("completed", "Completed"),
    Cancelled("cancelled", "Cancelled"),
    ;

    /** Whether a coordinator may still change passengers, vehicle and driver. */
    val isOpen: Boolean get() = this == Pending || this == Assigned || this == InProgress

    companion object {
        fun fromWire(value: String?): TripStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Pending
    }
}

enum class TripPriority(val wire: String) { Urgent("Urgent"), High("High"), Medium("Medium"), Low("Low") }

data class TripPassenger(
    val userId: String,
    val name: String,
    val designation: String,
    /** Recce-style: the pickup date + clock as epoch ms. */
    val pickupMs: Long,
    /** The web sends the clock a second time as text (`h:mm a`). */
    val pickupTimeText: String,
    val pickup: GeoPlace,
    val dropOff: GeoPlace,
    /** The driver's progress on this passenger while the trip runs — blank until they set one. */
    val driverStatus: String = "",
)

data class TripRequest(
    val id: String,
    val raisedBy: String,
    val priority: String,
    val status: TripStatus,
    val mode: String,
    val passengers: List<TripPassenger>,
    val ccUsers: List<String>,
    val vehicleId: String?,
    val driverId: String?,
    val startMs: Long,
    val endMs: Long,
    val createdMs: Long,
    /** Where the driver last reported from, while the trip runs. */
    val driverLocation: GeoPlace? = null,
) {
    val firstPickupMs: Long get() = passengers.minOfOrNull { it.pickupMs } ?: 0L
}

/** What a new pickup request carries; the status is decided by who raises it. */
data class TripDraft(
    val priority: TripPriority,
    val selfAssign: Boolean,
    val passengers: List<TripPassenger>,
    val ccUsers: List<String>,
    val vehicleId: String?,
    val driverId: String?,
)

/** A trip's next state — approve/reject/start/end — with optional reassignment. */
data class TripUpdate(
    val tripId: String,
    val action: TripAction,
    val passengers: List<TripPassenger>,
    val ccUsers: List<String>,
    val current: TripRequest,
    val vehicleId: String?,
    val driverId: String?,
)

enum class TripAction(val wireStatus: String?) {
    Approve("assigned"),
    Reject("cancelled"),
    Start("inprogress"),
    End("completed"),
    Cancel("cancelled"),
    Update(null),
}

enum class PermanentStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Permanent("permanent", "Allocated"),
    Completed("completed", "Completed"),
    ;

    companion object {
        fun fromWire(value: String?): PermanentStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Draft
    }
}

data class PermanentPassenger(val userId: String, val name: String)

data class PermanentTrip(
    val id: String,
    val status: PermanentStatus,
    val passengers: List<PermanentPassenger>,
    val ccUsers: List<String>,
    val vehicleId: String?,
    val driverId: String?,
    val startMs: Long,
    val endMs: Long,
    val fullDay: Boolean,
    val selfManage: Boolean,
    val createdMs: Long,
)

data class PermanentDraft(
    val passengers: List<PermanentPassenger>,
    val ccUsers: List<String>,
    val vehicleId: String?,
    val driverId: String?,
    val startMs: Long,
    val endMs: Long,
    val fullDay: Boolean,
    val asDraft: Boolean,
) {
    /** The web's `validatePermanenRequest`, in its order; drafts skip it. */
    fun problem(vehicleSeats: Int?): String? = when {
        asDraft -> null
        startMs <= 0L -> "A start date is required"
        passengers.isEmpty() -> "Add at least one passenger"
        vehicleId == null -> "Assign a vehicle"
        driverId == null -> "Assign a driver"
        vehicleSeats != null && vehicleSeats < passengers.size -> "The vehicle has too few seats for these passengers"
        endMs > 0L && endMs < startMs -> "The end date cannot be before the start date"
        else -> null
    }
}

/**
 * What `driver/update-details` changes — the web's `updateUserDetails`
 * sends only the keys it has, so every field here is "leave alone" when
 * null. The driver fills in phone, address, licence and documents; the
 * coordinator sets the vehicle, availability and the temporary flag.
 */
data class DriverDetailsUpdate(
    val userId: String,
    val phone: String? = null,
    val countryCode: String? = null,
    val address: String? = null,
    val vehicleId: String? = null,
    val isTripAssigned: Boolean? = null,
    val isTempDriver: Boolean? = null,
    val licencePictures: List<StoredMedia>? = null,
    val documents: List<StoredMedia>? = null,
) {
    /** The web's phone rule: optional, but 5 to 20 characters when given. */
    fun problem(): String? = when {
        phone != null && phone.isNotBlank() && phone.length !in PHONE_RANGE -> "The phone number is 5 to 20 characters"
        documents != null && documents.size > DOCUMENTS_MAX -> "At most $DOCUMENTS_MAX documents"
        else -> null
    }

    companion object {
        const val DOCUMENTS_MAX = 5
        private val PHONE_RANGE = 5..20
    }
}

/** A dialling code the phone fields offer — the web's country list. */
data class DialCountry(val name: String, val dialCode: String) {
    val label: String get() = "$dialCode $name"
}

/** The tool's rights: view; posting makes a coordinator; a driver is decided by designation. */
data class TransportViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView

    /**
     * The coordinator: `posting_access` on the tool, as this comment always
     * said — the `|| isAdmin` beneath it did not match.
     *
     * Android resolves the role in a documented priority chain that never
     * looks at `isAdmin` (`TransportationActivity.resolveTransportRole`):
     * posting access wins over driver designation, then driver, then view
     * access as passenger, then out. An admin without posting rights is a
     * passenger there, and was a coordinator here.
     */
    val isCoordinator: Boolean get() = canPost

    companion object {
        const val TOOL_IDENTIFIER = "transportation_tool"

        fun from(permissions: ProjectPermissions, userId: String): TransportViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return TransportViewer(userId = userId, ready = false)
            }
            return TransportViewer(
                userId = userId,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

/**
 * A driver asking for their licence details to be changed.
 *
 * The transport manager approves or rejects it, which is what puts the new
 * licence on the crew row. This client listened for the socket events and
 * reloaded the crew, but had no way to answer a request — the decision could
 * only be made from the web (`DocumentRequests.jsx`, `DocumentDetailsModal.jsx`).
 */
data class LicenceRequest(
    val id: String,
    val userId: String,
    /** The picture the driver submitted, as a storage key. */
    val licencePicture: String = "",
    val createdAtMs: Long = 0,
    /** Null while nobody has answered it. */
    val verified: Boolean? = null,
)
