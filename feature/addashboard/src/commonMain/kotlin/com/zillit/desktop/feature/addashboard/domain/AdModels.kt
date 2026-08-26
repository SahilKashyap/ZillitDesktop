package com.zillit.desktop.feature.addashboard.domain

/**
 * The production side of supporting artistes: the register, the shoot day,
 * and who was on it.
 *
 * Shapes from the `ad-dashboard` service (`/api/v2/{artistes,ad-shoot-days,
 * supporting-artist-days}`), confirmed live on develop 2026-08-26. This is
 * the AD's roster — the artiste's own view of the same days is a different
 * service entirely, in `feature:saportal`.
 */

/** What an artiste is booked as. The server's enum, with the web's labels. */
enum class ArtisteCategory(val wire: String, val label: String) {
    Chaperone("chaperone", "Chaperone"),
    Crowd("crowd", "Crowd"),
    FeaturedArtist("featured_artist", "Featured Artist"),
    GeneralSa("general_sa", "General SA"),
    PhotoDouble("photo_double", "Photo Double"),
    SpecialAbility("special_ability", "Special Ability"),
    StandIn("stand_in", "Stand-in"),
    WalkOn("walk_on", "Walk-on"),
    ;

    companion object {
        /**
         * An unrecognised category reads as General SA — the web's fallback.
         *
         * Not Unknown: every artiste is booked as *something*, and a blank
         * cell in the register is less use than the commonest booking.
         */
        fun from(wire: String?): ArtisteCategory {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: GeneralSa
        }
    }
}

/** How the artiste is engaged, and therefore how they are paid. */
enum class EngagementType(val wire: String, val label: String) {
    DirectPaye("direct_paye", "Direct PAYE"),
    Agency("agency", "Agency"),
    ;

    companion object {
        /**
         * External artistes come through an agency by default, internal ones
         * on the production's own payroll — the web's rule when the field is
         * absent.
         */
        fun from(wire: String?, isExternal: Boolean): EngagementType {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value }
                ?: if (isExternal) Agency else DirectPaye
        }
    }
}

/** Where an artiste is in onboarding. */
enum class ArtisteStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Pending("pending", "Pending"),
    Verified("verified", "Verified"),
    Blocked("blocked", "Blocked"),
    ;

    companion object {
        fun from(wire: String?): ArtisteStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Pending
        }
    }
}

/** One person on the production's artiste register. */
data class Artiste(
    val id: String,
    val name: String,
    val userId: String? = null,
    val isExternal: Boolean = false,
    val refNumber: String = "",
    val email: String = "",
    val designation: String = "",
    val category: ArtisteCategory = ArtisteCategory.GeneralSa,
    val engagement: EngagementType = EngagementType.DirectPaye,
    /** The agreement's name, not an enum — the rate-config picker saves it verbatim. */
    val union: String = "",
    val status: ArtisteStatus = ArtisteStatus.Pending,
    val agencyName: String = "",
) {
    /**
     * A verified artiste is not deletable.
     *
     * The web hides the control for exactly this reason: a verified record
     * has been through onboarding and may already carry signed days.
     */
    val deletable: Boolean get() = status != ArtisteStatus.Verified

    val blocked: Boolean get() = status == ArtisteStatus.Blocked
}

/**
 * The shoot day's own state, which decides what may still be edited.
 *
 * [locked] is the single source of truth the web keeps in `AD_DAY_STATUS`:
 * once a day is submitted it is read-only everywhere, and each editor asks
 * this rather than testing the enum itself.
 */
enum class AdDayStatus(val wire: String, val label: String, val locked: Boolean) {
    Draft("draft", "Draft", locked = false),
    InProgress("in_progress", "In progress", locked = false),
    Wrapped("wrapped", "Wrapped", locked = false),
    Submitted("submitted", "Submitted", locked = true),
    Approved("approved", "Approved", locked = true),
    Published("published", "Published", locked = true),
    Unknown("", "—", locked = false),
    ;

    companion object {
        fun from(wire: String?): AdDayStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** One day of shooting, as the AD department runs it. */
data class AdShootDay(
    val id: String,
    val shootDate: Long? = null,
    val dayNumber: Int? = null,
    val status: AdDayStatus = AdDayStatus.Unknown,
    val unitName: String = "",
    val location: String = "",
) {
    /** Nothing on a locked day may be changed — see [AdDayStatus.locked]. */
    val editable: Boolean get() = !status.locked
}

/** Whether the artiste turned up. */
enum class AttendanceStatus(val wire: String, val label: String) {
    Booked("booked", "Booked"),
    Present("present", "Present"),
    NoShow("no_show", "No show"),
    Cancelled("cancelled", "Cancelled"),
    Unknown("", "—"),
    ;

    companion object {
        fun from(wire: String?): AttendanceStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/**
 * One artiste on one shoot day — the row an AD fills in.
 *
 * The same record the artiste later signs as a voucher, which is why the
 * times and the category matter: they are what gets paid.
 */
data class SupportingArtistDay(
    val id: String,
    val artisteId: String = "",
    val artisteName: String = "",
    val shootDate: Long? = null,
    val callTime: String = "",
    val wrapTime: String = "",
    val category: ArtisteCategory = ArtisteCategory.GeneralSa,
    val attendance: AttendanceStatus = AttendanceStatus.Unknown,
    val status: String = "",
    val gross: Double = 0.0,
    val currency: String = "",
    val signStatus: String = "",
) {
    /** Signed by the artiste, one way or the other. */
    val signed: Boolean
        get() = signStatus.equals("typed", ignoreCase = true) ||
            signStatus.equals("external_sign", ignoreCase = true)

    /** How they signed, for the column that says so. */
    val signMethod: String
        get() = when {
            signStatus.equals("typed", ignoreCase = true) -> "Typed signature"
            signStatus.equals("external_sign", ignoreCase = true) -> "External signature"
            else -> ""
        }
}
