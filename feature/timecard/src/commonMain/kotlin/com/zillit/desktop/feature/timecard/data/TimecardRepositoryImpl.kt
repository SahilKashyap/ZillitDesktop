package com.zillit.desktop.feature.timecard.data

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
import com.zillit.desktop.feature.timecard.domain.Allowance
import com.zillit.desktop.feature.timecard.domain.AllowanceBasis
import com.zillit.desktop.feature.timecard.domain.AllowanceScope
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.Deduction
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardHistoryEntry
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Every `/api/v2/payroll/timecards` route, on the payroll service.
 *
 * Timecards live under payroll on the wire because that is where they are
 * paid from, even though the tool is its own product surface.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface.
class TimecardRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : TimecardRepository {

    private val base = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/timecards/weekly"
    private val metadataUrl = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/timecards/metadata"
    // The allowance catalogue is a production setting, not a payroll one: the
    // deal-memo wizard seeds it and the timecard spends it, so it lives with
    // the rest of the project settings on the account hub. The payroll
    // service's own `timecards/config` is the approval-routing config and
    // carries no allowances at all.
    private val allowancesUrl =
        "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/project-settings/allowances-rentals"

    override suspend fun metadata(): ZillitResult<TimecardMetadata> = apiClient.request(
        verb = HttpVerb.Get,
        url = metadataUrl,
        serializer = TimecardMetadataDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toDomain() }

    /**
     * My weeks.
     *
     * Alone among the list routes this one answers an object rather than an
     * array — `{ weeks, current_week, days_worked }` — and the cards inside it
     * are the slim projection: an id, a week, a status and the week's totals,
     * with no days. Opening one calls [timecard] for the full card.
     */
    override suspend fun myTimecards(): ZillitResult<List<Timecard>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/my-summary",
        serializer = MySummaryDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { summary -> summary.weeks.orEmpty().mapNotNull { it.toDomain() } }

    override suspend fun approvalQueue(): ZillitResult<List<Timecard>> = list("$base/approval")

    override suspend fun payrollProcessing(weekStarting: String): ZillitResult<List<Timecard>> =
        list("$base/payroll-processing/$weekStarting")

    override suspend fun outstanding(): ZillitResult<List<Timecard>> =
        list("$base/payroll-processing/outstanding")

    override suspend fun timecard(id: String): ZillitResult<Timecard> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/$id",
        serializer = TimecardDto.serializer(),
        module = RequestModule.ProjectUser,
    ).flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("timecard $id came back without an id"))
    }

    override suspend fun history(id: String): ZillitResult<List<TimecardHistoryEntry>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/$id/history",
            serializer = ListSerializer(TimecardHistoryDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.map { it.toDomain() } }

    /**
     * Creates or updates a week.
     *
     * One method for both because the screen is the same either way: an
     * unsaved week has no id and a saved one does, and making the caller pick
     * the verb pushes that distinction into every call site.
     */
    override suspend fun save(draft: TimecardDraft): ZillitResult<Unit> {
        val body = buildJsonObject {
            draft.weekStarting?.let { put("week_starting", JsonPrimitive(it)) }
            putIfPresent("notes", draft.notes)
            put(
                "days",
                buildJsonArray {
                    draft.days.forEach { day ->
                        add(
                            buildJsonObject {
                                day.date?.let { put("date", JsonPrimitive(it)) }
                                put("day_type", JsonPrimitive(day.dayType.wire))
                                putIfPresent("call_time", day.callTime)
                                putIfPresent("wrap_time", day.wrapTime)
                                put("break_minutes", JsonPrimitive(day.breakMinutes))
                                put("worked_hours", JsonPrimitive(day.workedHours))
                                put("overtime_hours", JsonPrimitive(day.overtimeHours))
                                putIfPresent("note", day.note)
                                put(
                                    "allowances",
                                    buildJsonArray {
                                        day.allowances.forEach { allowance ->
                                            add(
                                                buildJsonObject {
                                                    put("code", JsonPrimitive(allowance.code))
                                                    put("label", JsonPrimitive(allowance.label))
                                                    put("amount", JsonPrimitive(allowance.amount))
                                                    put("quantity", JsonPrimitive(allowance.quantity))
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        return if (draft.timecardId == null) {
            apiClient.envelope(HttpVerb.Post, base, RequestModule.ProjectUser, body).map { }
        } else {
            apiClient.envelope(
                HttpVerb.Patch,
                "$base/${draft.timecardId}",
                RequestModule.ProjectUser,
                body,
            ).map { }
        }
    }

    override suspend fun submit(id: String): ZillitResult<Unit> = post("$base/$id/submit", null)

    override suspend fun approve(id: String, note: String?): ZillitResult<Unit> =
        post("$base/$id/approve", noteBody(note))

    override suspend fun reject(id: String, reason: String): ZillitResult<Unit> =
        post("$base/$id/reject", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun query(id: String, note: String): ZillitResult<Unit> =
        post("$base/$id/query", buildJsonObject { put("note", JsonPrimitive(note)) })

    override suspend fun finalApprove(id: String): ZillitResult<Unit> =
        post("$base/$id/final-approve", null)

    override suspend fun lock(id: String): ZillitResult<Unit> = post("$base/$id/lock", null)

    override suspend fun markPaid(id: String): ZillitResult<Unit> = post("$base/$id/mark-paid", null)

    override suspend fun addDeduction(
        id: String,
        label: String,
        amount: Double,
        reason: String?,
    ): ZillitResult<Unit> = post(
        "$base/$id/add-deduction",
        buildJsonObject {
            put("label", JsonPrimitive(label))
            put("amount", JsonPrimitive(amount))
            putIfPresent("reason", reason)
        },
    )

    override suspend fun removeDeduction(id: String, deductionId: String): ZillitResult<Unit> = post(
        "$base/$id/remove-deduction",
        buildJsonObject { put("deduction_id", JsonPrimitive(deductionId)) },
    )

    override suspend fun approveAll(ids: List<String>): ZillitResult<Unit> =
        post("$base/batch/approve", idsBody(ids))

    override suspend fun lockAll(ids: List<String>): ZillitResult<Unit> =
        post("$base/batch/lock", idsBody(ids))

    override suspend fun allowanceTypes(): ZillitResult<List<AllowanceType>> = apiClient.request(
        verb = HttpVerb.Get,
        url = allowancesUrl,
        serializer = AllowancesRentalsDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { slice ->
        slice.allowances.orEmpty()
            // A row switched off is one the production has withdrawn; offering
            // it would produce a claim payroll then has to reject.
            .filter { it.enable != false }
            .mapNotNull { it.toDomain() }
    }

    private suspend fun list(url: String) = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = ListSerializer(TimecardDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Post, url, RequestModule.ProjectUser, body).map { }

    private fun noteBody(note: String?): JsonObject? =
        note?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("note", JsonPrimitive(it)) } }

    private fun idsBody(ids: List<String>) = buildJsonObject {
        put("timecard_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    }
}

@Serializable
internal data class TimecardDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("crew_name") val crewName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("designation") val designation: String? = null,
    @SerialName("week_starting") val weekStarting: String? = null,
    @SerialName("week_number") val weekNumber: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("days") val days: List<DayDto>? = null,
    @SerialName("basic_pay") val basicPay: String? = null,
    @SerialName("overtime_pay") val overtimePay: String? = null,
    @SerialName("total_allowances") val totalAllowances: String? = null,
    @SerialName("additional_fees") val additionalFees: String? = null,
    @SerialName("deductions") val deductions: List<DeductionDto>? = null,
    @SerialName("total_pay") val totalPay: String? = null,
    @SerialName("total_days") val totalDays: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("query_notes") val queryNotes: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("last_approved_by") val lastApprovedBy: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("locked") val locked: Boolean? = null,
) {
    fun toDomain(): Timecard? {
        // Both spellings appear: `_id` on the Mongo-backed weekly collection,
        // `id` on the summary projections.
        val identifier = (id ?: underscoreId)?.takeIf { it.isNotBlank() } ?: return null
        val status = TimecardStatus.from(status)
        return Timecard(
            id = identifier,
            userId = userId.orEmpty(),
            crewName = crewName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            departmentId = departmentId,
            designation = designation,
            weekStarting = weekStarting.toEpochMillisOrNull(),
            weekNumber = weekNumber,
            status = status,
            currency = currency,
            days = days.orEmpty().map { it.toDomain() },
            basicPay = basicPay.toAmount(),
            overtimePay = overtimePay.toAmount(),
            totalAllowances = totalAllowances.toAmount(),
            additionalFees = additionalFees.toAmount(),
            deductions = deductions.orEmpty().map { it.toDomain() },
            totalPay = totalPay.toAmount(),
            totalDays = totalDays.toAmount(),
            notes = notes,
            queryNote = queryNotes,
            rejectionReason = rejectionReason,
            lastApprovedBy = lastApprovedBy,
            paidAt = paidAt.toEpochMillisOrNull(),
            updatedAt = updatedAt.toEpochMillisOrNull(),
            // A week the server calls locked is locked; so is one that has
            // reached a status past editing, whether or not the flag came.
            locked = locked == true || status == TimecardStatus.Locked || status.isPaid,
        )
    }
}

@Serializable
internal data class DayDto(
    @SerialName("date") val date: String? = null,
    @SerialName("day_type") val dayType: String? = null,
    @SerialName("call_time") val callTime: String? = null,
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("break_minutes") val breakMinutes: Int? = null,
    @SerialName("worked_hours") val workedHours: String? = null,
    @SerialName("overtime_hours") val overtimeHours: String? = null,
    @SerialName("allowances") val allowances: List<AllowanceDto>? = null,
    @SerialName("note") val note: String? = null,
) {
    fun toDomain() = TimecardDay(
        date = date.toEpochMillisOrNull(),
        dayType = DayType.from(dayType),
        callTime = callTime,
        wrapTime = wrapTime,
        breakMinutes = breakMinutes ?: 0,
        workedHours = workedHours.toAmount(),
        overtimeHours = overtimeHours.toAmount(),
        allowances = allowances.orEmpty().map { it.toDomain() },
        note = note,
    )
}

@Serializable
internal data class AllowanceDto(
    @SerialName("code") val code: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("quantity") val quantity: String? = null,
) {
    fun toDomain() = Allowance(
        code = code.orEmpty(),
        label = label?.takeIf { it.isNotBlank() } ?: code.orEmpty(),
        amount = amount.toAmount(),
        // An allowance with no quantity is claimed once, not zero times.
        quantity = quantity.toAmountOrNull() ?: 1.0,
    )
}

@Serializable
internal data class DeductionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("reason") val reason: String? = null,
) {
    fun toDomain() = Deduction(
        id = id,
        label = label.orEmpty(),
        amount = amount.toAmount(),
        reason = reason,
    )
}

/**
 * `{ weeks, current_week, days_worked }` — the my-summary envelope.
 *
 * `days_worked` and `current_week` are the server's own aggregates. They are
 * read here but not carried into the domain: both are re-derived from the
 * weeks, and a header that disagrees with the rows under it is worse than one
 * that takes a moment longer to compute.
 */
@Serializable
internal data class MySummaryDto(
    @SerialName("weeks") val weeks: List<TimecardDto>? = null,
)

/**
 * The allowances-and-rentals project-settings slice.
 *
 * ## The read is wrapped and the write is not
 *
 * `GET` answers `{"data":{"value":{"allowances":[],"rentals":[]}}}` — the slice
 * sits under `value` — while the documented `PATCH` body is the bare
 * `{allowances, rentals}`. Reading `data.allowances` therefore finds nothing
 * and fails **silently**, leaving an empty catalogue rather than an error, so
 * the unwrapped form is accepted too in case the read is ever squared up with
 * the write.
 *
 * Rentals are in the same slice but are not ours: they belong to the deal, not
 * the timecard.
 */
@Serializable
internal data class AllowancesRentalsDto(
    @SerialName("value") val value: AllowancesSliceDto? = null,
    @SerialName("allowances") val unwrapped: List<AllowanceTypeDto>? = null,
) {
    val allowances: List<AllowanceTypeDto>? get() = value?.allowances ?: unwrapped
}

@Serializable
internal data class AllowancesSliceDto(
    @SerialName("allowances") val allowances: List<AllowanceTypeDto>? = null,
)

/**
 * One configured allowance.
 *
 * Two legacy spellings are read: `rate` predates `amount`, and `on` predates
 * `enable`. Both still occur in saved documents, so both are accepted.
 */
@Serializable
internal data class AllowanceTypeDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("enable") private val enableNew: Boolean? = null,
    @SerialName("on") private val enableOld: Boolean? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("rate") val rate: String? = null,
    @SerialName("basis") val basis: String? = null,
    @SerialName("applies_to") val appliesTo: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
) {
    val enable: Boolean? get() = enableNew ?: enableOld

    fun toDomain(): AllowanceType? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return AllowanceType(
            code = identifier,
            label = name?.takeIf { it.isNotBlank() } ?: identifier,
            // Null rather than zero: a type with no set amount is one the
            // claimant states, which is different from one worth nothing.
            defaultAmount = (amount ?: rate).toAmountOrNull(),
            basis = AllowanceBasis.from(basis),
            appliesTo = AllowanceScope.from(appliesTo),
            nominalCode = nominalCode?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class TimecardMetadataDto(
    @SerialName("is_approver") val isApprover: Boolean? = null,
    @SerialName("is_final_approver") val isFinalApprover: Boolean? = null,
    @SerialName("is_completer") val isCompleter: Boolean? = null,
    @SerialName("requires_final_approval") val requiresFinalApproval: Boolean? = null,
    @SerialName("disputes_enabled") val disputesEnabled: Boolean? = null,
) {
    fun toDomain() = TimecardMetadata(
        isApprover = isApprover == true,
        isFinalApprover = isFinalApprover == true,
        isCompleter = isCompleter == true,
        requiresFinalApproval = requiresFinalApproval == true,
        disputesEnabled = disputesEnabled == true,
    )
}

@Serializable
internal data class TimecardHistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain() = TimecardHistoryEntry(
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
