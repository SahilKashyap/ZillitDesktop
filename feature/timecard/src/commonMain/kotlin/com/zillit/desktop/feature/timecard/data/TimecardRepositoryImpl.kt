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
import com.zillit.desktop.core.socket.SocketEventBus
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

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
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : TimecardRepository {

    private val base = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/timecards/weekly"
    private val metadataUrl = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/timecards/metadata"

    /** The payroll-approvers allowlist read (`payroll-metadata.js:22-27`). */
    private val payrollMetadataUrl = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/metadata"

    /**
     * See [TimecardRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project — the same cross-project
     * gate the web's account-hub wrapper applies before any handler runs.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(TIMECARD_SYNC_EVENTS, TimecardSyncEnvelope.serializer())
            ?.mapNotNull { (_, envelope) ->
                Unit.takeIf { envelope.inProject(currentProjectId()) }
            }
            ?: emptyFlow()
    // The allowance catalogue is a production setting, not a payroll one: the
    // deal-memo wizard seeds it and the timecard spends it, so it lives with
    // the rest of the project settings on the account hub. The payroll
    // service's own `timecards/config` is the approval-routing config and
    // carries no allowances at all.
    private val allowancesUrl =
        "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/project-settings/allowances-rentals"

    /**
     * Two endpoints, one answer — the split the web keeps between
     * `TimecardMetadataContext` (the timecard `/metadata`: approver /
     * accountant / completer flags plus `pay_period`) and `usePayrollMetadata`
     * (`/payroll/metadata`, the only source of `is_final_approver` —
     * `usePayrollMetadata.js:54-75`). The payroll read fails soft: a payroll
     * outage must not take the crew's own timecard pages down with it.
     */
    override suspend fun metadata(): ZillitResult<TimecardMetadata> = apiClient.request(
        verb = HttpVerb.Get,
        url = metadataUrl,
        serializer = TimecardMetadataDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { dto ->
        val payroll = apiClient.request(
            verb = HttpVerb.Get,
            url = payrollMetadataUrl,
            serializer = PayrollApproverDto.serializer(),
            module = RequestModule.ProjectUser,
        ).getOrNull()
        dto.toDomain(isFinalApprover = payroll?.isFinalApprover == true)
    }

    /**
     * My weeks.
     *
     * Alone among the list routes this one answers an object rather than an
     * array — `{ weeks, current_week, days_worked }` — and the cards inside it
     * are the slim projection: an id, a week, a status and the week's totals,
     * with no days **and no user id** (`MyTimecardsModule.jsx:126-167` reads
     * nothing about the owner). Every row is the caller's by construction —
     * the route is scoped server-side — so they are marked as owned here
     * rather than left to a userId comparison that can never match.
     */
    override suspend fun myTimecards(): ZillitResult<List<Timecard>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/my-summary",
        serializer = MySummaryDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { summary -> summary.ownedWeeks() }

    override suspend fun approvalQueue(): ZillitResult<List<Timecard>> = list("$base/approval")

    override suspend fun payrollProcessing(weekStarting: Long): ZillitResult<List<Timecard>> =
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
     * Creates or updates a week, the web's `ensureTimecardId` + `update` two
     * step (`WeeklyTimecardModule.jsx:4778-4816`, `:4855-4860`): the week's
     * id is resolved first — an exact-`week_starting` list, then a skeleton
     * CREATE on miss — and the typed week always lands as a PATCH of the
     * `buildSavePayload` shape. Answers the resolved id, which a queued
     * submit or a note rides on.
     */
    override suspend fun save(draft: TimecardDraft): ZillitResult<String?> {
        val id = when (val resolved = resolveWeekId(draft)) {
            is ZillitResult.Failure -> return resolved
            is ZillitResult.Success -> resolved.data
        }
        return apiClient.envelope(
            HttpVerb.Patch,
            "$base/$id",
            RequestModule.ProjectUser,
            draft.updateBody(),
        ).map { id }
    }

    /**
     * The web resolves a week by EXACT `week_starting` before ever creating
     * one — `timecardsApi.list({week_starting})`, the server holding one
     * timecard per (user, week) pair (`WeeklyTimecardModule.jsx:3992-4046`).
     */
    private suspend fun resolveWeekId(draft: TimecardDraft): ZillitResult<String> {
        draft.timecardId?.let { return ZillitResult.Success(it) }
        val listed = apiClient.request(
            verb = HttpVerb.Get,
            url = base,
            serializer = ListSerializer(TimecardDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("week_starting" to draft.weekStarting),
        )
        when (listed) {
            is ZillitResult.Failure -> return listed
            is ZillitResult.Success ->
                listed.data.firstOrNull()?.toDomain()?.id?.let { return ZillitResult.Success(it) }
        }
        val created = apiClient.request(
            verb = HttpVerb.Post,
            url = base,
            serializer = TimecardDto.serializer(),
            module = RequestModule.ProjectUser,
            body = draft.createBody(timezone = TimeZone.currentSystemDefault().id),
        )
        return when (created) {
            is ZillitResult.Failure -> created
            is ZillitResult.Success -> created.data.toDomain()?.id?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("the created week came back without an id"))
        }
    }

    override suspend fun addNote(id: String, note: String): ZillitResult<Unit> =
        post("$base/$id/notes", buildJsonObject { put("note", JsonPrimitive(note)) })

    // Submit takes an optional payload batching the latest save; the web
    // sends `data || {}`, never an empty request (timecards.js:165-170).
    override suspend fun submit(id: String): ZillitResult<Unit> = post("$base/$id/submit", EMPTY_BODY)

    // The web's approve is bodiless — `approve(id)` with a `{}` default
    // (ApproveTimeCardsPage.jsx:557, timecards.js:172-175).
    override suspend fun approve(id: String, note: String?): ZillitResult<Unit> =
        post("$base/$id/approve", noteBody(note) ?: EMPTY_BODY)

    override suspend fun reject(id: String, reason: String): ZillitResult<Unit> =
        post("$base/$id/reject", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun query(id: String, note: String): ZillitResult<Unit> =
        post("$base/$id/query", buildJsonObject { put("note", JsonPrimitive(note)) })

    override suspend fun finalApprove(id: String): ZillitResult<Unit> =
        post("$base/$id/final-approve", null)

    override suspend fun lock(id: String): ZillitResult<Unit> = post("$base/$id/lock", null)

    override suspend fun markPaid(id: String): ZillitResult<Unit> = post("$base/$id/mark-paid", null)

    /**
     * The deduction wire takes a rate, never an amount: `{label, rate_type,
     * rate_amount, nominal_code}`, the server computing `actual_amount` and
     * stamping `added_at`/`added_by` itself (`timecards.js:138-151`,
     * `AddDeductionModal.jsx:127-132`). A flat rate's actual amount IS the
     * rate, so the screen's amount goes out as `rate_amount`.
     */
    override suspend fun addDeduction(
        id: String,
        label: String,
        amount: Double,
        nominalCode: String?,
    ): ZillitResult<Unit> = post(
        "$base/$id/add-deduction",
        buildJsonObject {
            put("label", JsonPrimitive(label))
            put("rate_type", JsonPrimitive("flat"))
            put("rate_amount", JsonPrimitive(amount))
            put("nominal_code", JsonPrimitive(nominalCode?.trim().orEmpty()))
        },
    )

    override suspend fun removeDeduction(id: String, deductionId: String): ZillitResult<Unit> = post(
        "$base/$id/remove-deduction",
        buildJsonObject { put("deduction_id", JsonPrimitive(deductionId)) },
    )

    // Batch bodies carry `{ids}` — not `{timecard_ids}`, which the server
    // reads as an empty batch (timecards.js:216-219, :254-258).
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
        put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    }

    private companion object {
        /** What the web sends where axios defaults a body to `{}`. */
        val EMPTY_BODY = JsonObject(emptyMap())
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
    /**
     * `[{note, added_at}]` on the full document (`timecards.js:114-123`);
     * older desktop-written rows carried a bare string. Read as raw JSON so
     * either shape decodes, and resolved in [notesText].
     */
    @SerialName("notes") val notes: JsonElement? = null,
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
            notes = notesText(),
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

    /** The newest note's text — the array is append-only, so the last row is it. */
    private fun notesText(): String? = when (notes) {
        null, is JsonNull -> null
        is JsonPrimitive -> notes.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> ((notes.lastOrNull() as? JsonObject)?.get("note") as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() }

        else -> null
    }
}

/**
 * One saved day (`serializeDayToServer`'s output read back). Worked times are
 * epoch millis pinned to UTC wall-clock, hours live in `minutes_worked`, and
 * pay lines in `rates_ots[]`; the retired desktop spellings (`worked_hours`,
 * string times) are still read for rows this port itself saved before the fix.
 */
@Serializable
internal data class DayDto(
    @SerialName("date") val date: String? = null,
    @SerialName("day_type") val dayType: String? = null,
    @SerialName("call_time") val callTime: String? = null,
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("basic_hours") val basicHours: String? = null,
    @SerialName("minutes_worked") val minutesWorked: String? = null,
    @SerialName("worked_hours") val legacyWorkedHours: String? = null,
    @SerialName("rates_ots") val ratesOts: List<RateLineDto>? = null,
    @SerialName("allowances") val allowances: List<AllowanceDto>? = null,
    @SerialName("note") val note: String? = null,
) {
    fun toDomain() = TimecardDay(
        date = date.toEpochMillisOrNull(),
        dayType = DayType.from(dayType),
        callTime = wireTimeOfDay(callTime),
        wrapTime = wireTimeOfDay(wrapTime),
        workedHours = workedHours(),
        overtimeHours = overtimeHours(),
        allowances = allowances.orEmpty().map { it.toDomain() },
        note = note,
    )

    /** `minutes_worked` first (the web's Hours column), then the older shapes. */
    private fun workedHours(): Double {
        val minutes = minutesWorked.toAmountOrNull()
        if (minutes != null && minutes > 0) return minutes / WIRE_MINUTES_PER_HOUR
        return basicHours.toAmountOrNull() ?: legacyWorkedHours.toAmount()
    }

    /**
     * The OT lines' clocked minutes: every non-basic `rates_ots` row with an
     * hourly basis carries its minutes in `work_duration`
     * (`WeeklyTimecardModule.jsx:760-770` — `basis: "hour"`, `work_duration:
     * line.minutes`). Money stays on the row; only the hours are surfaced.
     */
    private fun overtimeHours(): Double = ratesOts.orEmpty()
        .filter { it.identifier != "basic" && it.basis == "hour" }
        .sumOf { it.workDuration.toAmount() } / WIRE_MINUTES_PER_HOUR
}

private const val WIRE_MINUTES_PER_HOUR = 60.0

/** One `rates_ots[]` pay line — only what the day reader needs from it. */
@Serializable
internal data class RateLineDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("basis") val basis: String? = null,
    @SerialName("work_duration") val workDuration: String? = null,
)

/**
 * A day-level claim. The wire names are `rateEntrySchema`'s — `identifier` /
 * `rate_amount` / `qty` (`WeeklyTimecardModule.jsx:586-619`); `code` /
 * `amount` / `quantity` are the retired desktop spellings, still read for
 * rows this port saved before the fix.
 */
@Serializable
internal data class AllowanceDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("code") val legacyCode: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("rate_amount") val rateAmount: String? = null,
    @SerialName("amount") val legacyAmount: String? = null,
    @SerialName("qty") val qty: String? = null,
    @SerialName("quantity") val legacyQuantity: String? = null,
) {
    fun toDomain(): Allowance {
        val code = (identifier ?: legacyCode).orEmpty()
        return Allowance(
            code = code,
            label = label?.takeIf { it.isNotBlank() } ?: code,
            amount = (rateAmount ?: legacyAmount).toAmount(),
            // An allowance with no quantity is claimed once, not zero times.
            quantity = (qty ?: legacyQuantity).toAmountOrNull() ?: 1.0,
        )
    }
}

/**
 * One deduction row: `_id` keys the remove call, and the server-computed
 * `actual_amount` is the money (falling back to `rate_amount`, which equals
 * it for flat rows — `timecards.js:138-158`).
 */
@Serializable
internal data class DeductionDto(
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("actual_amount") val actualAmount: String? = null,
    @SerialName("rate_amount") val rateAmount: String? = null,
    @SerialName("amount") val legacyAmount: String? = null,
    @SerialName("reason") val reason: String? = null,
) {
    fun toDomain() = Deduction(
        id = underscoreId ?: id,
        label = label.orEmpty(),
        amount = (actualAmount ?: rateAmount ?: legacyAmount).toAmount(),
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
) {
    /**
     * Every row is the caller's by construction — the route is scoped
     * server-side and the slim projection carries no owner field at all
     * (`MyTimecardsModule.jsx:126-167`) — so ownership is stamped here, not
     * left to a userId comparison that can never match.
     */
    fun ownedWeeks(): List<Timecard> =
        weeks.orEmpty().mapNotNull { it.toDomain()?.copy(ownedByViewer = true) }
}

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

/**
 * `GET payroll/timecards/metadata` — the flags the web's
 * `TimecardMetadataContext` defaults document (`TimecardMetadataContext.jsx:
 * 25-43`): `is_approver`, `is_accountant`, `is_completer` and `pay_period`.
 * `is_final_approver` never rides this route; it is merged in from the
 * payroll metadata read.
 */
@Serializable
internal data class TimecardMetadataDto(
    @SerialName("is_approver") val isApprover: Boolean? = null,
    @SerialName("is_accountant") val isAccountant: Boolean? = null,
    @SerialName("is_completer") val isCompleter: Boolean? = null,
    @SerialName("pay_period") val payPeriod: PayPeriodDto? = null,
    @SerialName("requires_final_approval") val requiresFinalApproval: Boolean? = null,
    @SerialName("disputes_enabled") val disputesEnabled: Boolean? = null,
) {
    fun toDomain(isFinalApprover: Boolean) = TimecardMetadata(
        isApprover = isApprover == true,
        isFinalApprover = isFinalApprover,
        isCompleter = isCompleter == true,
        isAccountant = isAccountant == true,
        payPeriodStartDay = payPeriod?.startDayOfWeek ?: 1,
        requiresFinalApproval = requiresFinalApproval == true,
        disputesEnabled = disputesEnabled == true,
    )
}

/** `{start_day_of_week, end_day_of_week}` — ISO 1=Mon … 7=Sun. */
@Serializable
internal data class PayPeriodDto(
    @SerialName("start_day_of_week") val startDayOfWeek: Int? = null,
)

/**
 * The slice of `GET /api/v2/payroll/metadata` this tool needs: whether the
 * production's payroll-approvers allowlist names the caller
 * (`usePayrollMetadata.js:54-75`, `payroll-metadata.js:22-27`).
 */
@Serializable
internal data class PayrollApproverDto(
    @SerialName("is_final_approver") val isFinalApprover: Boolean? = null,
)

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

