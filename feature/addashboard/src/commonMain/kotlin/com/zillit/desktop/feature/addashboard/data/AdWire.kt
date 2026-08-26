package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `ad-dashboard` service's shapes. Everything nullable — see [AdWire]. */

@Serializable
internal data class PersonalDetailsDto(
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
)

@Serializable
internal data class UnionDetailsDto(
    /** The agreement's *name*, not an enum — saved verbatim from the picker. */
    @SerialName("union") val union: String? = null,
)

@Serializable
internal data class ArtisteDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("is_external") val isExternal: Boolean? = null,
    @SerialName("ref_number") val refNumber: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("engagement_type") val engagementType: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("agency_name") val agencyName: String? = null,
    @SerialName("personal_details") val personal: PersonalDetailsDto? = null,
    @SerialName("union_details") val union: UnionDetailsDto? = null,
) {
    /**
     * [resolveName] supplies the crew name for an internal artiste.
     *
     * An internal artiste *is* a project user, and their name lives on that
     * user rather than on the artiste record — the web resolves it the same
     * way. An external one has only what was typed for them.
     */
    fun toDomain(resolveName: (String) -> String?): Artiste? {
        val resolved = id ?: return null
        val external = isExternal == true
        val typed = listOfNotNull(personal?.firstName, personal?.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val crewName = userId?.takeIf { !external }?.let(resolveName)
        return Artiste(
            id = resolved,
            name = when {
                external -> typed.ifBlank { "Unnamed" }
                else -> crewName ?: typed.ifBlank { "Unknown user" }
            },
            userId = userId,
            isExternal = external,
            refNumber = refNumber.orEmpty(),
            email = personal?.email.orEmpty(),
            category = ArtisteCategory.from(category),
            engagement = EngagementType.from(engagementType, external),
            union = union?.union.orEmpty(),
            status = ArtisteStatus.from(status),
            agencyName = agencyName.orEmpty(),
        )
    }
}

@Serializable
internal data class ShootDayDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("shoot_date") val shootDate: Double? = null,
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("location") val location: String? = null,
) {
    fun toDomain(): AdShootDay? {
        val resolved = id ?: return null
        return AdShootDay(
            id = resolved,
            shootDate = shootDate?.toLong(),
            dayNumber = dayNumber,
            status = AdDayStatus.from(status),
            unitName = unitName.orEmpty(),
            location = location.orEmpty(),
        )
    }
}

@Serializable
internal data class SaDayDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("artiste_id") val artisteId: String? = null,
    @SerialName("artiste_name") val artisteName: String? = null,
    @SerialName("shoot_date") val shootDate: Double? = null,
    @SerialName("call_time") val callTime: String? = null,
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("attendance_status") val attendance: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("gross") val gross: Double? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("sign_status") val signStatus: String? = null,
) {
    fun toDomain(): SupportingArtistDay? {
        val resolved = id ?: return null
        return SupportingArtistDay(
            id = resolved,
            artisteId = artisteId.orEmpty(),
            artisteName = artisteName.orEmpty(),
            shootDate = shootDate?.toLong(),
            callTime = callTime.orEmpty(),
            wrapTime = wrapTime.orEmpty(),
            category = ArtisteCategory.from(category),
            attendance = AttendanceStatus.from(attendance),
            status = status.orEmpty(),
            gross = gross ?: 0.0,
            currency = currency.orEmpty(),
            signStatus = signStatus.orEmpty(),
        )
    }
}
