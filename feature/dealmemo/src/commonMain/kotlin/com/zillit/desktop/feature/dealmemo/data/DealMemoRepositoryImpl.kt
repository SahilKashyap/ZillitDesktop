package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.dealmemo.domain.Agreement
import com.zillit.desktop.feature.dealmemo.domain.BasicRateDetails
import com.zillit.desktop.feature.dealmemo.domain.RateCardEntry
import com.zillit.desktop.feature.dealmemo.domain.RateTier
import com.zillit.desktop.feature.dealmemo.domain.Deal
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.dealmemo.domain.DealRates
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.NewDeal
import com.zillit.desktop.feature.dealmemo.domain.Union
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Every `/api/v2/deal-memo` route. */
class DealMemoRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : DealMemoRepository {

    private val base = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"

    private companion object {
        /** The whole card in one call — it is browsed, not paged. */
        const val RATE_LIMIT = 500
    }

    override suspend fun deals(status: DealStatus?): ZillitResult<List<Deal>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/deals",
        serializer = ListSerializer(DealDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = status?.let { mapOf("status" to it.wire) }.orEmpty(),
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * The viewer's own deal.
     *
     * Absent is an ordinary answer — plenty of crew are on a production before
     * their deal is written — so this returns null rather than an error, and
     * the screen says so plainly.
     */
    override suspend fun myDeal(): ZillitResult<Deal?> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = "$base/deal",
        serializer = DealDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it?.toDomain() }

    override suspend fun deal(id: String): ZillitResult<Deal> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/deals/$id",
        serializer = DealDto.serializer(),
        module = RequestModule.ProjectUser,
    ).flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("deal $id came back without an id"))
    }

    override suspend fun history(id: String): ZillitResult<List<DealHistoryEntry>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/deals/$id/history",
        serializer = ListSerializer(DealHistoryDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(deal: NewDeal, notify: Boolean): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/deals",
            module = RequestModule.ProjectUser,
            body = deal.body(),
            // Whether the crew member is emailed is the caller's decision, not
            // a side effect of saving: a correction typed twice should not
            // notify twice.
            queryParameters = mapOf("notify" to notify),
        ).map { }

    override suspend fun update(id: String, deal: NewDeal, notify: Boolean): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/deals/$id",
            module = RequestModule.ProjectUser,
            body = deal.body(),
            queryParameters = mapOf("notify" to notify),
        ).map { }

    override suspend fun acknowledge(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/deal/acknowledge-amendment",
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("deal_id", JsonPrimitive(id)) },
    ).map { }

    override suspend fun unions(): ZillitResult<List<Union>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/unions",
        serializer = ListSerializer(UnionDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun agreements(unionId: String?): ZillitResult<List<Agreement>> = apiClient.request(
        verb = HttpVerb.Get,
        url = unionId?.let { "$base/unions/$it/agreements" } ?: "$base/agreements",
        serializer = ListSerializer(AgreementDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun rateCard(
        unionId: String?,
        departmentIdentifier: String?,
        productionType: String?,
    ): ZillitResult<List<RateCardEntry>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/designation-rates",
        serializer = ListSerializer(RateEntryDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap {
            unionId?.let { put("union_identifier", it) }
            departmentIdentifier?.let { put("department_identifier", it) }
            productionType?.let { put("production_type", it) }
            put("limit", RATE_LIMIT)
        },
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun resolveRate(
        departmentIdentifier: String,
        designationIdentifier: String,
        productionType: String,
        agreementId: String?,
        unionId: String?,
        budget: Double?,
    ): ZillitResult<RateCardEntry?> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = "$base/designation-rates/resolve",
        serializer = RateEntryDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap {
            put("department_identifier", departmentIdentifier)
            put("designation_identifier", designationIdentifier)
            put("production_type", productionType)
            agreementId?.let { put("agreement_identifier", it) }
            unionId?.let { put("union_identifier", it) }
            // Omitted rather than sent as null: that axis then matches
            // anything, which is what an unstated budget means. Experience is
            // not sent at all — the resolver has no experience axis.
            budget?.let { put("budget", it) }
        },
        // A role the card publishes no rate for answers with no data, and that
        // is the ordinary case for an unnegotiated role: the cascade then falls
        // back to the agreement's own scale, which is the whole point of it.
    ).map { it?.toDomain() }

    override suspend fun basicRateDetails(agreementId: String): ZillitResult<BasicRateDetails?> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/agreements/$agreementId",
            serializer = AgreementDetailDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it.basicRateDetails?.toDomain() }

    private fun NewDeal.body(): JsonObject = buildJsonObject {
        put("user_id", JsonPrimitive(userId))
        putIfPresent("department_id", departmentId)
        putIfPresent("designation", designation)
        putIfPresent("currency", currency)
        putIfPresent("union_id", unionId)
        putIfPresent("agreement_id", agreementId)
        putIfPresent("nominal_code", nominalCode)
        putIfPresent("notes", notes)
        startDate?.let { put("start_date", JsonPrimitive(it)) }
        endDate?.let { put("end_date", JsonPrimitive(it)) }
        put("weekly_rate", JsonPrimitive(rates.weeklyRate))
        put("daily_rate", JsonPrimitive(rates.dailyRate))
        put("hourly_rate", JsonPrimitive(rates.hourlyRate))
        put("overtime_rate", JsonPrimitive(rates.overtimeRate))
        put("standard_hours", JsonPrimitive(rates.standardHours))
        put("days_per_week", JsonPrimitive(rates.daysPerWeek))
        put("box_rental", JsonPrimitive(rates.boxRental))
        put("vehicle_allowance", JsonPrimitive(rates.vehicleAllowance))
    }
}

@Serializable
internal data class DealDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("crew_name") val crewName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation") val designation: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("weekly_rate") val weeklyRate: String? = null,
    @SerialName("daily_rate") val dailyRate: String? = null,
    @SerialName("hourly_rate") val hourlyRate: String? = null,
    @SerialName("overtime_rate") val overtimeRate: String? = null,
    @SerialName("standard_hours") val standardHours: String? = null,
    @SerialName("days_per_week") val daysPerWeek: String? = null,
    @SerialName("box_rental") val boxRental: String? = null,
    @SerialName("vehicle_allowance") val vehicleAllowance: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("union_name") val unionName: String? = null,
    @SerialName("agreement_name") val agreementName: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("amended_at") val amendedAt: String? = null,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(): Deal? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return Deal(
            id = identifier,
            userId = userId.orEmpty(),
            crewName = crewName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            email = email,
            departmentId = departmentId,
            departmentName = departmentName,
            designation = designation,
            status = DealStatus.from(status),
            currency = currency,
            rates = DealRates(
                weeklyRate = weeklyRate.toAmount(),
                dailyRate = dailyRate.toAmount(),
                hourlyRate = hourlyRate.toAmount(),
                overtimeRate = overtimeRate.toAmount(),
                standardHours = standardHours.toAmount(),
                daysPerWeek = daysPerWeek.toAmount(),
                boxRental = boxRental.toAmount(),
                vehicleAllowance = vehicleAllowance.toAmount(),
            ),
            startDate = startDate.toEpochMillisOrNull(),
            endDate = endDate.toEpochMillisOrNull(),
            unionName = unionName,
            agreementName = agreementName,
            nominalCode = nominalCode,
            notes = notes,
            amendedAt = amendedAt.toEpochMillisOrNull(),
            acknowledgedAt = acknowledgedAt.toEpochMillisOrNull(),
            createdAt = createdAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class RateEntryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("department_identifier") val departmentIdentifier: String? = null,
    @SerialName("designation_identifier") val designationIdentifier: String? = null,
    @SerialName("branch_identifier") val branchIdentifier: String? = null,
    @SerialName("union_identifier") val unionIdentifier: String? = null,
    @SerialName("production_type") val productionType: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("min_budget") val minBudget: String? = null,
    @SerialName("max_budget") val maxBudget: String? = null,
    /** Arrays: a card may publish several tiers per role. See [RateCascade]. */
    @SerialName("hourly") val hourly: List<TierDto>? = null,
    @SerialName("daily") val daily: List<TierDto>? = null,
    @SerialName("weekly") val weekly: List<TierDto>? = null,
) {
    fun toDomain(): RateCardEntry? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        val department = departmentIdentifier?.takeIf { it.isNotBlank() } ?: return null
        val designation = designationIdentifier?.takeIf { it.isNotBlank() } ?: return null
        return RateCardEntry(
            id = identifier,
            departmentIdentifier = department,
            designationIdentifier = designation,
            branchIdentifier = branchIdentifier,
            unionIdentifier = unionIdentifier,
            productionType = productionType,
            currency = currency,
            minBudget = minBudget.toAmountOrNull(),
            maxBudget = maxBudget.toAmountOrNull(),
            // The first published tier stands in until an agreement says which
            // day length this deal is under — see RateCascade.pickTier.
            hourly = hourly?.firstOrNull()?.toDomain(),
            daily = daily?.firstOrNull()?.toDomain(),
            weekly = weekly?.firstOrNull()?.toDomain(),
        )
    }

    /** Every published tier, for the caller that knows the agreement's hours. */
    fun tiers(): Triple<List<RateTier>, List<RateTier>, List<RateTier>> = Triple(
        hourly.orEmpty().map { it.toDomain() },
        daily.orEmpty().map { it.toDomain() },
        weekly.orEmpty().map { it.toDomain() },
    )
}

@Serializable
internal data class TierDto(
    @SerialName("base_rate") val baseRate: String? = null,
    @SerialName("min_rate") val minRate: String? = null,
    @SerialName("max_rate") val maxRate: String? = null,
    @SerialName("work_hrs") val workHours: String? = null,
    @SerialName("day_type") val dayType: String? = null,
) {
    fun toDomain() = RateTier(
        baseRate = baseRate.toAmountOrNull(),
        minRate = minRate.toAmountOrNull(),
        maxRate = maxRate.toAmountOrNull(),
        workHours = workHours.toAmountOrNull(),
        dayType = dayType,
    )
}

@Serializable
internal data class AgreementDetailDto(
    @SerialName("basic_rate_details") val basicRateDetails: BasicRateDetailsDto? = null,
)

@Serializable
internal data class BasicRateDetailsDto(
    @SerialName("hourly") val hourly: TierDto? = null,
    @SerialName("daily") val daily: TierDto? = null,
    @SerialName("weekly") val weekly: TierDto? = null,
    @SerialName("day_type") val dayType: String? = null,
) {
    fun toDomain() = BasicRateDetails(
        hourly = hourly?.toDomain(),
        daily = daily?.toDomain(),
        weekly = weekly?.toDomain(),
        dayType = dayType,
    )
}

@Serializable
internal data class UnionDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("agreement_count") val agreementCount: Int? = null,
) {
    fun toDomain(): Union? {
        val resolved = (identifier ?: id)?.takeIf { it.isNotBlank() } ?: return null
        return Union(
            id = resolved,
            name = name?.takeIf { it.isNotBlank() } ?: resolved,
            agreementCount = agreementCount ?: 0,
        )
    }
}

@Serializable
internal data class AgreementDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("union_id") val unionId: String? = null,
    @SerialName("effective_from") val effectiveFrom: String? = null,
) {
    fun toDomain(): Agreement? {
        val resolved = (identifier ?: id)?.takeIf { it.isNotBlank() } ?: return null
        return Agreement(
            id = resolved,
            name = name?.takeIf { it.isNotBlank() } ?: resolved,
            unionId = unionId,
            effectiveFrom = effectiveFrom.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class DealHistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain() = DealHistoryEntry(
        action = action ?: status.orEmpty(),
        userId = userId,
        note = note,
        at = createdAt.toEpochMillisOrNull(),
    )
}

/** Adds [key] only when [value] has something in it. */
internal fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    val trimmed = value?.trim()
    if (!trimmed.isNullOrEmpty()) put(key, JsonPrimitive(trimmed))
}
