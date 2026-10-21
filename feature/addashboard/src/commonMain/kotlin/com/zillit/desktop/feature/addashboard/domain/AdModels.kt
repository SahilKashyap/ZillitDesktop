package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The production side of supporting artistes: the register, the shoot day,
 * and who was on it.
 *
 * ## Three records, three names
 *
 * An *artiste* on the register is an [Artiste] (`/artistes`). One artiste
 * booked on one shoot day is a [SupportingArtistDay] — the web's "SA-day"
 * (`/supporting-artist-days`) — which carries attendance, times, meals,
 * allowances, pay and status, and *is* that artiste's day voucher. The day
 * itself is an [AdShootDay] header (`/ad-shoot-days`) holding the day status,
 * day meals, crew call and wrap, the PO list and the AD's signature. Most bugs
 * in this area come from writing to the wrong one.
 *
 * Shapes from the `ad-dashboard` service, probed live on develop 2026-10-21.
 * The artiste's own view of the same days is a different service entirely, in
 * `feature:saportal`.
 */

/** What an artiste is booked as. The server's enum, with the web's labels. */
enum class ArtisteCategory(val wire: String, private val labelKey: String) {
    Chaperone("chaperone", S.desktop_ad_cat_chaperone),
    Crowd("crowd", S.desktop_ad_cat_crowd),
    FeaturedArtist("featured_artist", S.desktop_ad_cat_featured_artist),
    GeneralSa("general_sa", S.desktop_ad_cat_general_sa),
    PhotoDouble("photo_double", S.desktop_ad_cat_photo_double),
    SpecialAbility("special_ability", S.desktop_ad_cat_special_ability),
    StandIn("stand_in", S.desktop_ad_cat_stand_in),
    WalkOn("walk_on", S.desktop_ad_cat_walk_on),
    ;

    val label: String get() = str(labelKey)

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
enum class EngagementType(val wire: String, private val labelKey: String) {
    DirectPaye("direct_paye", S.desktop_ad_engagement_direct_paye),
    Agency("agency", S.desktop_dm_agency),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /**
         * External artistes come through an agency by default, internal ones
         * on the production's own payroll — the web's rule when the field is
         * absent. It decides whether the artiste lands on an agency PO or on a
         * voucher, so the fallback is not cosmetic.
         */
        fun from(wire: String?, isExternal: Boolean): EngagementType {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value }
                ?: if (isExternal) Agency else DirectPaye
        }
    }
}

/** Where an artiste is in onboarding. */
enum class ArtisteStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    Pending("pending", S.pending),
    Verified("verified", S.ah_verified),
    Blocked("blocked", S.desktop_ad_status_blocked),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): ArtisteStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Pending
        }
    }
}

/**
 * One manual money line on a day, or one option in an artiste's deal.
 *
 * The pay engine never computes these: a ticked allowance or fee is flat cash
 * added on top of the engine's gross, which is why [flatAmount] is what the
 * totals add up and a percentage line contributes nothing.
 */
data class RateLine(
    val identifier: String = "",
    val label: String = "",
    /** `flat` or `pct` / `percentage`; anything else reads as flat. */
    val rateType: String = "flat",
    val rateAmount: Double = 0.0,
    val nominalCode: String = "",
) {
    val isPercentage: Boolean
        get() = rateType.equals("pct", ignoreCase = true) ||
            rateType.equals("percentage", ignoreCase = true)

    /** Cash this line adds to a day's total: nothing, when it is a percentage. */
    val flatAmount: Double get() = if (isPercentage) 0.0 else rateAmount

    val isCustom: Boolean get() = identifier == CUSTOM_IDENTIFIER

    companion object {
        /** The one hand-typed payment per day, upserted under this identifier. */
        const val CUSTOM_IDENTIFIER = "custom"
    }
}

/** A day type an artiste may be booked on — the pay engine's basic→overtime boundary. */
data class DayTypeOption(
    val dayType: String,
    val workMinutes: Int = 0,
    val mealBreakMinutes: Int = 0,
) {
    companion object {
        /** The web's `PRESET_DAY_TYPES`; anything else is saved as a custom type. */
        val PRESETS: List<DayTypeOption> = listOf(
            DayTypeOption("SWD", workMinutes = 600, mealBreakMinutes = 60),
            DayTypeOption("CWD", workMinutes = 660, mealBreakMinutes = 60),
            DayTypeOption("SCWD", workMinutes = 720, mealBreakMinutes = 60),
        )
    }
}

/**
 * What the server worked out this artiste is owed and may claim.
 *
 * Built server-side from the rate config; the client only offers its
 * catalogues as the tick options on Attendance and Additionals.
 */
data class ArtisteDeal(
    val dayTypes: List<DayTypeOption> = emptyList(),
    val allowances: List<RateLine> = emptyList(),
    val additionalFees: List<RateLine> = emptyList(),
)

/** One document the AD asks an artiste to upload. */
data class ArtisteDocument(
    val id: String = "",
    val label: String = "",
    val description: String = "",
    val fileName: String = "",
    val fileKey: String = "",
) {
    val uploaded: Boolean get() = fileKey.isNotBlank() || fileName.isNotBlank()
}

/** The artiste's or an agency's bank details, as the forms collect them. */
data class BankDetails(
    val id: String = "",
    val accountName: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
    val ibanNumber: String = "",
    val swiftCode: String = "",
    val bankName: String = "",
) {
    /**
     * An IBAN alone satisfies the bank requirement.
     *
     * International artistes often bank somewhere that quotes no local account
     * number, so only *neither* field is an error. Deliberately not a global
     * rule: the production's own accounts still require an account number.
     */
    val hasNumber: Boolean get() = accountNumber.isNotBlank() || ibanNumber.isNotBlank()

    val isEmpty: Boolean
        get() = !hasNumber && accountName.isBlank() && sortCode.isBlank() &&
            swiftCode.isBlank() && bankName.isBlank()
}

/** One person on the production's artiste register. */
@Suppress("LongParameterList")
data class Artiste(
    val id: String,
    val name: String,
    val userId: String? = null,
    val isExternal: Boolean = false,
    val refNumber: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val designation: String = "",
    val category: ArtisteCategory = ArtisteCategory.GeneralSa,
    val engagement: EngagementType = EngagementType.DirectPaye,
    /** The agreement's name, not an enum — the rate-config picker saves it verbatim. */
    val union: String = "",
    val status: ArtisteStatus = ArtisteStatus.Pending,
    val agencyName: String = "",
    /** The registered agency this artiste is linked to; the PO's vendor. */
    val agencyId: String = "",
    val isMinor: Boolean = false,
    val dateOfBirth: String = "",
    val address: String = "",
    val rateTemplate: String = "",
    val bank: BankDetails = BankDetails(),
    val documents: List<ArtisteDocument> = emptyList(),
    val deal: ArtisteDeal = ArtisteDeal(),
    /** The server's hint that a draft artiste may already be sent a portal link. */
    val portalLinkEligible: Boolean = false,
) {
    /**
     * A verified artiste is not deletable.
     *
     * The web hides the control for exactly this reason: a verified record
     * has been through onboarding and may already carry signed days.
     */
    val deletable: Boolean get() = status != ArtisteStatus.Verified

    val blocked: Boolean get() = status == ArtisteStatus.Blocked

    /**
     * Whether a portal link can be minted for them.
     *
     * Only external artistes have one at all — an internal artiste signs in
     * the Supporting Artistes tool with their own account.
     */
    val portalLinkable: Boolean
        get() = isExternal && (
            status == ArtisteStatus.Pending ||
                status == ArtisteStatus.Verified ||
                (status == ArtisteStatus.Draft && portalLinkEligible)
            )

    /** Two initials for the avatar; `?` when there is nothing to take them from. */
    val initials: String
        get() = name.trim().split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
}

/**
 * The shoot day's own state, which decides what may still be edited.
 *
 * [locked] is the single source of truth the web keeps in `AD_DAY_STATUS`:
 * once a day is submitted it is read-only everywhere, and each editor asks
 * this rather than testing the enum itself. Add a new status here, never at a
 * call site.
 */
enum class AdDayStatus(val wire: String, private val labelKey: String?, val locked: Boolean) {
    Draft("draft", S.draft, locked = false),
    InProgress("in_progress", S.in_progress, locked = false),

    /** Never written by any client; the server may still set it. */
    Wrapped("wrapped", S.desktop_ad_day_status_wrapped, locked = false),
    Submitted("submitted", S.txt_submitted, locked = true),
    Approved("approved", S.approved, locked = true),
    Published("published", S.cs_published, locked = true),
    Unknown("", null, locked = false),
    ;

    val label: String get() = labelKey?.let { str(it) } ?: EM_DASH

    companion object {
        fun from(wire: String?): AdDayStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** Which report affordance a day offers. */
enum class DayReportAction { Generate, View, None }

/** One meal break on the day, or on one artiste's day. */
data class MealBreak(
    val order: Int = 1,
    val type: String = "",
    /** UTC wall-clock `HH:mm`, as [AdDates] stores every time. */
    val startTime: String = "",
    val endTime: String = "",
    /** Only per-artiste meals carry a penalty. */
    val penalty: Double? = null,
) {
    val isRunning: Boolean get() = startTime.isNotBlank() && endTime.isBlank()
}

/** A purchase order raised for one agency on one day, as the day records it. */
data class DayPurchaseOrder(
    val poId: String = "",
    val agency: String = "",
    val agencyId: String = "",
    val currency: String = "",
    val amount: Double = 0.0,
)

/** The AD's sign-off on a day. */
data class DaySignature(
    val typedName: String = "",
    val signedAt: Long? = null,
    /** `typed` for records signed before e-signing; `e-sign` for everything since. */
    val signStatus: String = "",
    val imageKey: String = "",
) {
    val present: Boolean get() = typedName.isNotBlank() || imageKey.isNotBlank()
}

/** One day of shooting, as the AD department runs it. */
@Suppress("LongParameterList")
data class AdShootDay(
    val id: String,
    val shootDate: Long? = null,
    val dayNumber: Int? = null,
    val status: AdDayStatus = AdDayStatus.Unknown,
    val unitName: String = "",
    val location: String = "",
    /** The unit's crew call — what timecards read as Unit Call. */
    val callTime: String = "",
    val wrapTime: String = "",
    val dayType: String = "",
    val sceneSchedule: String = "",
    val sceneCompleted: String = "",
    val sceneUncompleted: String = "",
    val meals: List<MealBreak> = emptyList(),
    val purchaseOrders: List<DayPurchaseOrder> = emptyList(),
    val poReference: String = "",
    /** `standard` or `urgent` — flags the PO and nothing else. */
    val poPostType: String = "",
    val signature: DaySignature = DaySignature(),
    /** A published report's PDF, when the day has one. */
    val reportAttachment: String = "",
) {
    /** Nothing on a locked day may be changed — see [AdDayStatus.locked]. */
    val editable: Boolean get() = !status.locked

    /**
     * Which report button the day offers.
     *
     * A `submitted` day may generate; a `published` one may be viewed.
     * `approved` offers nothing, because nothing writes it.
     */
    val reportAction: DayReportAction
        get() = when (status) {
            AdDayStatus.Published -> DayReportAction.View
            AdDayStatus.Submitted -> DayReportAction.Generate
            else -> DayReportAction.None
        }
}

/** Whether the artiste turned up. */
enum class AttendanceStatus(val wire: String, private val labelKey: String?) {
    Booked("booked", S.desktop_ad_attendance_booked),
    Present("present", S.desktop_call_present),
    Absent("absent", S.desktop_ad_absent),
    NoShow("no_show", S.desktop_ad_attendance_no_show),
    Cancelled("cancelled", S.cancelled),
    Unknown("", null),
    ;

    val label: String get() = labelKey?.let { str(it) } ?: EM_DASH

    companion object {
        fun from(wire: String?): AttendanceStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/**
 * One artiste on one shoot day — the row an AD fills in, and the voucher the
 * artiste later signs.
 *
 * The times and the category matter because they are what gets paid.
 */
@Suppress("LongParameterList")
data class SupportingArtistDay(
    val id: String,
    val artisteId: String = "",
    val artisteName: String = "",
    val userId: String? = null,
    val isExternal: Boolean = false,
    val refNumber: String = "",
    val designation: String = "",
    val shootDate: Long? = null,
    /** UTC wall-clock `HH:mm` — the arrival the AD marked. */
    val callTime: String = "",
    val wrapTime: String = "",
    val category: ArtisteCategory = ArtisteCategory.GeneralSa,
    val engagement: EngagementType = EngagementType.DirectPaye,
    val union: String = "",
    val rateTemplate: String = "",
    val agencyName: String = "",
    val agencyId: String = "",
    val attendance: AttendanceStatus = AttendanceStatus.Unknown,
    /** The row's own lifecycle: `draft`, `pending`, `blocked`, `submitted`, `signed`, `approved`. */
    val status: String = "",
    val artisteStatus: ArtisteStatus = ArtisteStatus.Pending,
    val gross: Double = 0.0,
    val currency: String = "",
    val signStatus: String = "",
    val dayType: String = "",
    val dayTypes: List<DayTypeOption> = emptyList(),
    val dealAllowances: List<RateLine> = emptyList(),
    val dealAdditionalFees: List<RateLine> = emptyList(),
    val allowances: List<RateLine> = emptyList(),
    val additionalFees: List<RateLine> = emptyList(),
    val meals: List<MealBreak> = emptyList(),
    val notes: String = "",
    val isMinor: Boolean = false,
    val blockReason: String = "",
) {
    /**
     * The amount every screen shows.
     *
     * The pay engine's gross plus the day's flat manual lines: the engine never
     * computes a ticked allowance or fee, so a page that showed `gross` alone
     * would under-report whatever the AD added.
     */
    val payTotal: Double
        get() = gross +
            allowances.sumOf { it.flatAmount } +
            additionalFees.sumOf { it.flatAmount }

    /** The one hand-typed payment, when the day carries it. */
    val customFee: RateLine? get() = additionalFees.firstOrNull { it.isCustom }

    val isDraft: Boolean get() = status.equals("draft", ignoreCase = true)

    val isBlocked: Boolean get() = status.equals("blocked", ignoreCase = true)

    /**
     * Present means marked present **or** carrying a call time.
     *
     * One rule, in one place: the web defines it twice — in `AttendanceRow`
     * and privately in `useAdPhases` — so a change to one never reaches the
     * phase stepper.
     */
    val isPresent: Boolean get() = attendance == AttendanceStatus.Present || callTime.isNotBlank()

    /** Handled means their attendance has been settled one way or another. */
    val isHandled: Boolean
        get() = isPresent ||
            attendance == AttendanceStatus.Absent ||
            attendance == AttendanceStatus.NoShow ||
            isBlocked

    val isWrapped: Boolean get() = wrapTime.isNotBlank()

    /** Submitted, in the sense the phase stepper means: sent and not awaiting the AD. */
    val isSubmitted: Boolean
        get() = status.equals("submitted", ignoreCase = true) ||
            status.equals("approved", ignoreCase = true)

    /** Signed by the artiste, one way or the other. */
    val signed: Boolean
        get() = status.equals("signed", ignoreCase = true) ||
            status.equals("approved", ignoreCase = true) ||
            signStatus.equals("typed", ignoreCase = true) ||
            signStatus.equals("external_sign", ignoreCase = true)

    /** How they signed, for the column that says so. */
    val signMethod: String
        get() = when {
            signStatus.equals("typed", ignoreCase = true) -> str(S.desktop_ad_typed_signature)
            signStatus.equals("external_sign", ignoreCase = true) -> str(S.desktop_ad_external_signature)
            else -> ""
        }

    /**
     * Whether this row has a voucher to open.
     *
     * Direct PAYE only, not draft or blocked, and wrapped — an agency artiste
     * is paid through their agency's PO and never gets one.
     */
    val hasVoucher: Boolean
        get() = engagement == EngagementType.DirectPaye && !isDraft && !isBlocked && isWrapped

    /**
     * Whether the server e-mails this artiste an approval link on submission.
     *
     * External direct-PAYE artistes only: internal ones approve in the
     * Supporting Artistes tool, and agency ones are covered by their agency's
     * manifest countersign.
     */
    val needsApprovalLink: Boolean
        get() = isExternal && engagement == EngagementType.DirectPaye

    val initials: String
        get() = artisteName.trim().split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
}

/** The dash a missing value shows, rather than an empty cell. */
internal const val EM_DASH = "—"
