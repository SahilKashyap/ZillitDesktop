package com.zillit.desktop.feature.sos.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * One SOS alert on the production's `sos_label` feed.
 *
 * The wire row is the notification service's `NotificationDataModel` with
 * `section = sos_label` (Android `SosListResponseModel.data`,
 * `bottomNav/sos/model/SosListResponseModel.kt:9-18`). The sender's name and
 * the map link ride `reference_data.messageElements[0]` and `[1]` — the web
 * reads them off those indexes (`SOSMain.jsx:339` and `:521`).
 */
data class SosAlert(
    val id: String,
    val uuid: String,
    val senderId: String,
    /** The name the alert itself carries, for a sender no longer on the crew list. */
    val senderNameHint: String,
    /** The decoded message. */
    val text: String,
    /** The `https://maps…` link the backend put in the second element, when it did. */
    val mapsUrl: String,
    /** `sos_alert_entertainment` on the entertainment-industry variant, which shows a GSM note. */
    val action: String,
    /** `reference_data.contact_info` — a phone number the sender left, when they did. */
    val contactInfo: String,
    val createdMillis: Long,
    val updatedMillis: Long,
    /** Set when the server tombstoned the row; Android drops these (`Sos.kt:mergeSosResponse`). */
    val deleted: Boolean,
) {
    val timeLabel: String get() = createdMillis.toStampLabel()

    /** The web's GSM note (`SOSMain.jsx:486`). */
    val isEntertainment: Boolean get() = action == "sos_alert_entertainment"
}

/** A saved SOS receiver — a crew member or an outside number. */
data class SosContact(
    val id: String,
    val kind: SosContactKind,
    /** `entry_type` — `admin` or `user`, whose list this sits on. */
    val entryType: String,
    /** Internal: the crew member's user id. */
    val userId: String,
    /** Internal: `user.full_name`, `user.designation_name` (a label key). */
    val userFullName: String,
    val userDesignation: String,
    /** External: the four fields the outsider form takes. */
    val contactName: String,
    /** A department key from `sos/departments`, translated for display. */
    val relation: String,
    val countryCode: String,
    val phoneNumber: String,
    val createdMillis: Long,
) {
    /** What the row is headed with — the web's `display_name` (`SOS.jsx:341-346`). */
    val displayName: String get() = if (kind == SosContactKind.External) contactName else userFullName

    /** `+44 7700900000` for an outsider; blank for a member (their number is on their profile). */
    val phoneLabel: String
        get() = if (kind == SosContactKind.External) {
            listOf(countryCode, phoneNumber).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            ""
        }
}

/** `type` on the wire — `internal` for a crew member, `external` for an outsider. */
enum class SosContactKind(val wire: String) {
    Internal("internal"),
    External("external"),
    Unknown(""),
    ;

    companion object {
        fun fromWire(raw: String): SosContactKind =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(raw.trim(), ignoreCase = true) } ?: Unknown
    }
}

/** A relationship an outsider can have to the crew member — `sos/departments` rows. */
data class SosRelation(
    val id: String,
    /** The label key (`department_name`); shown translated. */
    val name: String,
    val identifier: String,
)

/** One dialling code — `preset/isd-codes` rows (`CountryISD`, `model/CountryListResponse.kt:55-62`). */
data class IsdCode(
    val name: String,
    val dialCode: String,
    val code: String,
) {
    val label: String get() = "$name ($dialCode)"
}

/** The outsider form, validated the way the web validates it (`SOS.jsx:207-239`). */
data class ExternalContactDraft(
    val contactName: String,
    val relation: String,
    val countryCode: String,
    val phoneNumber: String,
) {
    /** The first thing wrong with the draft, or null when it can be sent. */
    fun problem(): String? = when {
        contactName.isBlank() -> "Enter the contact's name."
        relation.isBlank() -> "Pick a relationship."
        countryCode.isBlank() -> "Pick a country code."
        phoneNumber.isBlank() -> "Enter a mobile number."
        !PHONE.matches(phoneNumber) -> "Enter a valid mobile number — digits only, 5 to 20 of them."
        else -> null
    }

    private companion object {
        /** `isValidPhoneNumber` on the web: `/^\d{5,20}$/` (`SOS.jsx:273-276`). */
        val PHONE = Regex("""^\d{5,20}$""")
    }
}

/** A crew member the Member tab can pick — supplied by the host from the session's crew list. */
data class SosCrewMember(
    val userId: String,
    val fullName: String,
    /** A label key, or a plain name — shown through the dictionary either way. */
    val designation: String,
)

/** Who is looking, and what the page shows because of it. */
data class SosViewer(
    val userId: String = "",
    /** Chooses the `entry_type` the contacts list is asked for (`SOS.jsx:118-126`). */
    val isAdmin: Boolean = false,
    /** Personal productions have no designations to show (`SOS.jsx:560`). */
    val isPersonalProject: Boolean = false,
    /** Blank means the outsider form is refused until a number is on the profile (`SOS.jsx:129-135`). */
    val phone: String = "",
) {
    val entryType: String get() = if (isAdmin) "admin" else "user"
}

/** A GPS fix, in the shape the alert body takes. */
data class SosFix(val lat: Double, val long: Double)

/** `12 Aug 2026, 14:32`, in the machine's zone. Blank for a missing stamp. */
fun Long.toStampLabel(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (this <= 0) return ""
    val moment = runCatching { Instant.fromEpochMilliseconds(this).toLocalDateTime(zone) }.getOrNull()
        ?: return ""
    val month = MONTHS[moment.month.ordinal]
    return "${moment.day.pad()} $month ${moment.year}, ${moment.hour.pad()}:${moment.minute.pad()}"
}

private fun Int.pad(): String = toString().padStart(2, '0')

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
