package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteDeal
import com.zillit.desktop.feature.addashboard.domain.ArtisteDocument
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.BankDetails
import com.zillit.desktop.feature.addashboard.domain.DayPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.DaySignature
import com.zillit.desktop.feature.addashboard.domain.DayTypeOption
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.MealBreak
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `ad-dashboard` service's shapes.
 *
 * Everything nullable: this service omits a field as often as it sends it
 * empty, and a missing key must not blank a record. Times come back as epoch
 * millis — wall-clock epochs, not instants — and are rendered through
 * [AdDates.epochToHhMm] so they read the same in every zone.
 */

@Serializable
internal data class PersonalDetailsDto(
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("phone") val phone: String? = null,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    @SerialName("address") val address: String? = null,
)

@Serializable
internal data class UnionDetailsDto(
    /** The agreement's *name*, not an enum — saved verbatim from the picker. */
    @SerialName("union") val union: String? = null,
    @SerialName("union_id") val unionId: String? = null,
)

@Serializable
internal data class BankDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("account_number") val accountNumber: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("sort_code") val sortCode: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("iban_number") val ibanNumber: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("swift_code") val swiftCode: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
) {
    fun toDomain(): BankDetails = BankDetails(
        id = id.orEmpty(),
        accountName = accountName.orEmpty(),
        accountNumber = accountNumber.orEmpty(),
        sortCode = sortCode.orEmpty(),
        ibanNumber = ibanNumber.orEmpty(),
        swiftCode = swiftCode.orEmpty(),
        bankName = bankName.orEmpty(),
    )
}

@Serializable
internal data class ArtisteDocumentDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("file_key") val fileKey: String? = null,
) {
    fun toDomain(): ArtisteDocument = ArtisteDocument(
        id = id.orEmpty(),
        label = label.orEmpty(),
        description = description.orEmpty(),
        fileName = fileName.orEmpty(),
        fileKey = fileKey.orEmpty(),
    )
}

/** One money line, in the `allowanceSchema` shape both sides of the deal use. */
@Serializable
internal data class RateLineDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("rate_type") val rateType: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("rate_amount") val rateAmount: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("rate_pct") val ratePct: Double? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
) {
    fun toDomain(): RateLine = RateLine(
        // The identifier is what an upsert keys on; the id is the row's own.
        identifier = identifier ?: id.orEmpty(),
        label = label ?: name.orEmpty(),
        rateType = rateType ?: "flat",
        rateAmount = rateAmount ?: amount ?: ratePct ?: 0.0,
        nominalCode = nominalCode.orEmpty(),
    )
}

@Serializable
internal data class DayTypeDto(
    @SerialName("day_type") val dayType: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("work_min") val workMinutes: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("meal_break_min") val mealBreakMinutes: Int? = null,
) {
    fun toDomain(): DayTypeOption? {
        val name = dayType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DayTypeOption(
            dayType = name,
            workMinutes = workMinutes ?: 0,
            mealBreakMinutes = mealBreakMinutes ?: 0,
        )
    }
}

@Serializable
internal data class DealDto(
    @SerialName("day_types") val dayTypes: List<DayTypeDto>? = null,
    @SerialName("allowances") val allowances: List<RateLineDto>? = null,
    @SerialName("additional_fees") val additionalFees: List<RateLineDto>? = null,
) {
    fun toDomain(): ArtisteDeal = ArtisteDeal(
        dayTypes = dayTypes?.mapNotNull { it.toDomain() }.orEmpty(),
        allowances = allowances?.map { it.toDomain() }.orEmpty(),
        additionalFees = additionalFees?.map { it.toDomain() }.orEmpty(),
    )
}

@Serializable
internal data class ArtisteDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("user_id") val userId: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("is_external") val isExternal: Boolean? = null,
    @Serializable(with = LooseString::class)
    @SerialName("ref_number") val refNumber: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("artiste_ref") val artisteRef: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("engagement_type") val engagementType: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("agency_name") val agencyName: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("agency_id") val agencyId: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("is_minor") val isMinor: Boolean? = null,
    @SerialName("rate_template") val rateTemplate: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("portal_link_eligible") val portalLinkEligible: Boolean? = null,
    @SerialName("personal_details") val personal: PersonalDetailsDto? = null,
    @SerialName("union_details") val union: UnionDetailsDto? = null,
    @SerialName("bank") val bank: BankDto? = null,
    @Serializable(with = LooseString::class)
    @SerialName("bank_id") val bankId: String? = null,
    @SerialName("additional_documents") val documents: List<ArtisteDocumentDto>? = null,
    @SerialName("deal") val deal: DealDto? = null,
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
                external -> typed.ifBlank { str(S.desktop_unnamed) }
                else -> crewName ?: typed.ifBlank { str(S.unkone_user) }
            },
            userId = userId,
            isExternal = external,
            refNumber = refNumber ?: artisteRef.orEmpty(),
            firstName = personal?.firstName.orEmpty(),
            lastName = personal?.lastName.orEmpty(),
            email = personal?.email.orEmpty(),
            phone = personal?.phone.orEmpty(),
            dateOfBirth = personal?.dateOfBirth.orEmpty(),
            address = personal?.address.orEmpty(),
            category = ArtisteCategory.from(category),
            engagement = EngagementType.from(engagementType, external),
            union = union?.union.orEmpty(),
            status = ArtisteStatus.from(status),
            agencyName = agencyName.orEmpty(),
            agencyId = agencyId.orEmpty(),
            isMinor = isMinor == true,
            rateTemplate = rateTemplate.orEmpty(),
            bank = bank?.toDomain() ?: BankDetails(id = bankId.orEmpty()),
            documents = documents?.map { it.toDomain() }.orEmpty(),
            deal = deal?.toDomain() ?: ArtisteDeal(),
            portalLinkEligible = portalLinkEligible == true,
        )
    }
}

@Serializable
internal data class MealDto(
    @Serializable(with = LooseInt::class)
    @SerialName("order") val order: Int? = null,
    @SerialName("type") val type: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("start_time") val startTime: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("end_time") val endTime: String? = null,
    /** The per-artiste shape spells the same two fields differently. */
    @Serializable(with = LooseString::class)
    @SerialName("meal_start_time") val mealStartTime: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("meal_end_time") val mealEndTime: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("meal_penalty") val penalty: Double? = null,
) {
    fun toDomain(index: Int): MealBreak = MealBreak(
        order = order ?: (index + 1),
        type = type.orEmpty(),
        startTime = AdDates.epochToHhMm(startTime ?: mealStartTime),
        endTime = AdDates.epochToHhMm(endTime ?: mealEndTime),
        penalty = penalty,
    )
}

@Serializable
internal data class DayPurchaseOrderDto(
    @Serializable(with = LooseString::class)
    @SerialName("po_id") val poId: String? = null,
    @SerialName("agency") val agency: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("agency_id") val agencyId: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
) {
    fun toDomain(): DayPurchaseOrder = DayPurchaseOrder(
        poId = poId.orEmpty(),
        agency = agency.orEmpty(),
        agencyId = agencyId.orEmpty(),
        currency = currency.orEmpty(),
        amount = amount ?: 0.0,
    )
}

@Serializable
internal data class DaySignatureDto(
    @SerialName("typed_name") val typedName: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("signed_at") val signedAt: Double? = null,
    @Serializable(with = LooseString::class)
    @SerialName("signature_image") val signatureImage: String? = null,
) {
    fun toDomain(signStatus: String?): DaySignature = DaySignature(
        typedName = typedName.orEmpty(),
        signedAt = signedAt?.toLong(),
        signStatus = signStatus.orEmpty(),
        imageKey = signatureImage.orEmpty(),
    )
}

@Serializable
internal data class ShootDayDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("shoot_date") val shootDate: Double? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("location") val location: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("call_time") val callTime: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("day_type") val dayType: String? = null,
    @SerialName("scene_schedule") val sceneSchedule: List<String>? = null,
    @SerialName("scene_completed") val sceneCompleted: List<String>? = null,
    @SerialName("scene_uncompleted") val sceneUncompleted: List<String>? = null,
    @SerialName("meals") val meals: List<MealDto>? = null,
    @SerialName("purchase_orders") val purchaseOrders: List<DayPurchaseOrderDto>? = null,
    @SerialName("po_reference") val poReference: String? = null,
    @SerialName("po_post_type") val poPostType: String? = null,
    @SerialName("sign_status") val signStatus: String? = null,
    @SerialName("signature") val signature: DaySignatureDto? = null,
    @Serializable(with = LooseString::class)
    @SerialName("report_attachment") val reportAttachment: String? = null,
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
            callTime = AdDates.epochToHhMm(callTime),
            wrapTime = AdDates.epochToHhMm(wrapTime),
            dayType = dayType.orEmpty(),
            sceneSchedule = sceneSchedule?.joinToString(", ").orEmpty(),
            sceneCompleted = sceneCompleted?.joinToString(", ").orEmpty(),
            sceneUncompleted = sceneUncompleted?.joinToString(", ").orEmpty(),
            meals = meals?.mapIndexed { index, meal -> meal.toDomain(index) }.orEmpty(),
            purchaseOrders = purchaseOrders?.map { it.toDomain() }.orEmpty(),
            poReference = poReference.orEmpty(),
            poPostType = poPostType.orEmpty(),
            signature = signature?.toDomain(signStatus) ?: DaySignature(signStatus = signStatus.orEmpty()),
            reportAttachment = reportAttachment.orEmpty(),
        )
    }
}

/** `GET /ad-shoot-days/day-number` answers the number, bare or wrapped. */
@Serializable
internal data class DayNumberDto(
    @Serializable(with = LooseInt::class)
    @SerialName("day_number") val dayNumber: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("value") val value: Int? = null,
) {
    val resolved: Int? get() = dayNumber ?: value
}

@Serializable
internal data class SaDayDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    /**
     * Populated server-side, so it arrives as the whole artiste record — but
     * on some routes as a bare id, which is why both are read.
     */
    @SerialName("artiste_id") val artiste: ArtisteDto? = null,
    @Serializable(with = LooseString::class)
    @SerialName("artiste") val artisteRefId: String? = null,
    @SerialName("artiste_name") val artisteName: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("shoot_date") val shootDate: Double? = null,
    @Serializable(with = LooseString::class)
    @SerialName("call_time") val callTime: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("attendance_status") val attendance: String? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("gross_amount") val grossAmount: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("gross") val gross: Double? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("sign_status") val signStatus: String? = null,
    @SerialName("day_type") val dayType: String? = null,
    @SerialName("allowances") val allowances: List<RateLineDto>? = null,
    @SerialName("additional_fees") val additionalFees: List<RateLineDto>? = null,
    @SerialName("rates_ots") val ratesOts: List<RateLineDto>? = null,
    @SerialName("meals") val meals: List<MealDto>? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("block_reason") val blockReason: String? = null,
) {
    fun toDomain(resolveName: (String) -> String?): SupportingArtistDay? {
        val resolved = id ?: return null
        val person = artiste
        val external = person?.isExternal == true
        val typed = listOfNotNull(person?.personal?.firstName, person?.personal?.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val crewName = person?.userId?.takeIf { !external }?.let(resolveName)
        val name = artisteName?.takeIf { it.isNotBlank() } ?: when {
            person == null -> str(S.unkone_user)
            external -> typed.ifBlank { str(S.desktop_unnamed) }
            else -> crewName ?: typed.ifBlank { str(S.unkone_user) }
        }
        return SupportingArtistDay(
            id = resolved,
            artisteId = person?.id ?: artisteRefId.orEmpty(),
            artisteName = name,
            userId = person?.userId,
            isExternal = external,
            refNumber = person?.refNumber ?: person?.artisteRef.orEmpty(),
            shootDate = shootDate?.toLong(),
            callTime = AdDates.epochToHhMm(callTime),
            wrapTime = AdDates.epochToHhMm(wrapTime),
            category = ArtisteCategory.from(category ?: person?.category),
            engagement = EngagementType.from(person?.engagementType, external),
            union = person?.union?.union.orEmpty(),
            rateTemplate = person?.rateTemplate.orEmpty(),
            agencyName = person?.agencyName.orEmpty(),
            agencyId = person?.agencyId.orEmpty(),
            attendance = AttendanceStatus.from(attendance),
            status = status.orEmpty(),
            artisteStatus = ArtisteStatus.from(person?.status),
            gross = grossAmount ?: gross ?: 0.0,
            // Each row carries its own currency: the agreement's, or the
            // project default for a non-union day. Never the project's alone.
            currency = currency ?: person?.currency.orEmpty(),
            signStatus = signStatus.orEmpty(),
            dayType = dayType.orEmpty(),
            dayTypes = person?.deal?.dayTypes?.mapNotNull { it.toDomain() }.orEmpty(),
            dealAllowances = person?.deal?.allowances?.map { it.toDomain() }.orEmpty(),
            dealAdditionalFees = person?.deal?.additionalFees?.map { it.toDomain() }.orEmpty(),
            allowances = allowances?.map { it.toDomain() }.orEmpty(),
            additionalFees = additionalFees?.map { it.toDomain() }.orEmpty(),
            meals = meals?.mapIndexed { index, meal -> meal.toDomain(index) }.orEmpty(),
            notes = notes.orEmpty(),
            isMinor = person?.isMinor == true,
            blockReason = blockReason.orEmpty(),
        )
    }

    /** The engine's own rate lines, which only the voucher and a query show. */
    fun engineLines(): List<RateLine> = ratesOts?.map { it.toDomain() }.orEmpty()
}
