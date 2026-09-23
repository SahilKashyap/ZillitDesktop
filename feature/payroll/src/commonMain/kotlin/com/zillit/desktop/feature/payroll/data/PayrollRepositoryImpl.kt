package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.currencyCode
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.payroll.domain.BankAccount
import com.zillit.desktop.feature.payroll.domain.NominalAllocation
import com.zillit.desktop.feature.payroll.domain.PayrollLine
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollWeek
import com.zillit.desktop.feature.payroll.domain.PostOutcome
import com.zillit.desktop.feature.payroll.domain.Payslip
import com.zillit.desktop.feature.payroll.domain.PayslipLine
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The payroll service's weekly surface.
 *
 * ## Three bases, because payroll is three servers' worth of surface
 *
 *  - `/payroll/weekly` lists a week's timecards;
 *  - `/payroll/timecards/weekly` is where a timecard's own transitions live —
 *    paying and posting are done to timecards, not to the week;
 *  - `/payroll/timecards/accountant-payroll` carries the per-crew accountant
 *    detail: the payslip and the nominal split.
 *
 * Bank accounts come from the account hub, which is a fourth host again.
 */
class PayrollRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : PayrollRepository {

    /**
     * See [PayrollRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project — the same cross-project
     * gate the web's account-hub wrapper applies before any handler runs.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(PAYROLL_SYNC_EVENTS, PayrollSyncEnvelope.serializer())
            ?.mapNotNull { (_, envelope) ->
                Unit.takeIf { envelope.inProject(currentProjectId()) }
            }
            ?: emptyFlow()

    private val payrollHost = config.baseUrl(ZillitService.Payroll)
    private val weeklyBase = "$payrollHost/api/v2/payroll/weekly"
    private val timecardBase = "$payrollHost/api/v2/payroll/timecards/weekly"
    private val accountantBase = "$payrollHost/api/v2/payroll/timecards/accountant-payroll"
    private val metadataUrl = "$payrollHost/api/v2/payroll/metadata"
    private val bankAccountsUrl =
        "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/bank-accounts"

    override suspend fun week(weekStarting: Long): ZillitResult<PayrollWeek> =
        queue("$weeklyBase/$weekStarting/processing", weekStarting)

    // No week in the path: the server reads it from the module context and
    // names the week it answered with. Asking for a week we computed would miss
    // whenever the production's pay period is not a Monday week.
    override suspend fun currentWeek(): ZillitResult<PayrollWeek> =
        queue("$weeklyBase/processing", fallbackWeekStarting = null)

    override suspend fun isFinalApprover(): ZillitResult<Boolean> = apiClient.request(
        verb = HttpVerb.Get,
        url = metadataUrl,
        serializer = PayrollMetadataDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.isFinalApprover == true }

    override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = apiClient.request(
        verb = HttpVerb.Get,
        url = bankAccountsUrl,
        serializer = ListSerializer(BankAccountDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun markPaid(timecardIds: List<String>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$timecardBase/batch/mark-paid",
            module = RequestModule.ProjectUser,
            body = idsBody(timecardIds),
        ).map { }

    override suspend fun markUnpaid(timecardId: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$timecardBase/$timecardId/mark-unpaid",
        module = RequestModule.ProjectUser,
    ).map { }

    /**
     * Posts paid timecards to the ledger.
     *
     * `bank_id` and `effective_date` are required — the server answers 400
     * without either, and validates the date against the cost-report lock. No
     * `company_id` is sent: a timecard inherits its legal entity from the crew
     * member's deal memo when it is created, so there is nothing to choose.
     */
    override suspend fun markPosted(
        timecardIds: List<String>,
        bankId: String,
        effectiveDate: Long,
    ): ZillitResult<PostOutcome> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$timecardBase/batch/mark-posted",
        serializer = PostOutcomeDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("ids", buildJsonArray { timecardIds.forEach { add(JsonPrimitive(it)) } })
            put("bank_id", JsonPrimitive(bankId))
            put("effective_date", JsonPrimitive(effectiveDate))
        },
    ).map { it.toDomain() }

    override suspend fun nominalSplit(
        weekStarting: Long,
        crewId: String,
    ): ZillitResult<List<NominalAllocation>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$accountantBase/$weekStarting/crew/$crewId/nominal",
        serializer = ListSerializer(AllocationDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    // PUT, not PATCH: the server replaces the whole set, and a partial write
    // would leave allocations nobody can see behind the ones that were sent.
    override suspend fun saveNominalSplit(
        weekStarting: Long,
        crewId: String,
        allocations: List<NominalAllocation>,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Put,
        url = "$accountantBase/$weekStarting/crew/$crewId/nominal",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put(
                "allocations",
                buildJsonArray {
                    allocations.forEach { allocation ->
                        add(
                            buildJsonObject {
                                allocation.id?.let { put("id", JsonPrimitive(it)) }
                                put("nominal_code", JsonPrimitive(allocation.nominalCode))
                                put("description", JsonPrimitive(allocation.description))
                                put("amount", JsonPrimitive(allocation.amount))
                                allocation.departmentId?.let { put("department_id", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        },
    ).map { }

    override suspend fun payslip(weekStarting: Long, crewId: String): ZillitResult<Payslip?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$accountantBase/$weekStarting/crew/$crewId/payslip",
            serializer = PayslipDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it?.toDomain(crewId) }

    /**
     * Reads a weekly queue.
     *
     * ## The two spellings of the same route
     *
     * `/weekly/processing` answers an **object** — `{ week_starting, timezone,
     * timecards }` — while `/weekly/{ws}/processing` answers a **bare array**
     * of timecards. Same route name, different shape depending on whether the
     * week is in the path, so [TolerantWeeklyQueue] accepts either.
     *
     * That is also why [fallbackWeekStarting] exists: the array form names no
     * week, and the week we asked for is the only thing left to label it with.
     */
    private suspend fun queue(url: String, fallbackWeekStarting: Long?) = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = TolerantWeeklyQueue,
        module = RequestModule.ProjectUser,
    ).map { it.toDomain(fallbackWeekStarting) }

    private fun idsBody(ids: List<String>) = buildJsonObject {
        put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    }
}

/**
 * Reads a weekly queue whether it came as an object or as a bare array.
 *
 * The week-scoped route answers `[...]` and the unscoped one answers
 * `{ week_starting, timezone, timecards }`. An array is lifted into the object
 * form — with no week, which the caller supplies — so everything downstream
 * sees one shape and neither spelling needs a second code path.
 */
internal object TolerantWeeklyQueue :
    JsonTransformingSerializer<WeeklyQueueDto>(WeeklyQueueDto.serializer()) {

    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) buildJsonObject { put("timecards", element) } else element
}

/**
 * `{ week_starting, timezone, timecards }` — the weekly queue envelope.
 *
 * [timezone] is the zone the week boundary was struck in. It is read but not
 * carried into the domain: the week is displayed as the date the server named,
 * not re-derived locally, so there is nothing here to convert.
 */
@Serializable
internal data class WeeklyQueueDto(
    @SerialName("week_starting") val weekStarting: Long? = null,
    @SerialName("timecards") val timecards: List<LineDto>? = null,
) {
    fun toDomain(fallbackWeekStarting: Long?): PayrollWeek {
        val lines = timecards.orEmpty().mapNotNull { it.toDomain() }
        return PayrollWeek(
            // The server's answer wins over the week we asked for: on the
            // unscoped route we asked for nothing at all.
            weekStarting = weekStarting ?: fallbackWeekStarting ?: 0L,
            currency = lines.firstNotNullOfOrNull { it.currency },
            lines = lines,
        )
    }
}

/**
 * One timecard as the weekly processing list returns it.
 *
 * This is the full timecard document, the same shape the timecard tool reads —
 * payroll just looks at different fields of it. Note [deductions] is a list of
 * rows rather than a total: the week's deduction figure is their sum, and a
 * scalar read here would silently be zero.
 */
@Serializable
internal data class LineDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("crew_id") val crewId: String? = null,
    @SerialName("crew_name") val crewName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation") val designation: String? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("basic_pay") val basicPay: String? = null,
    @SerialName("overtime_pay") val overtimePay: String? = null,
    @SerialName("total_allowances") val totalAllowances: String? = null,
    @SerialName("additional_fees") val additionalFees: String? = null,
    @SerialName("deductions") val deductions: List<DeductionRowDto>? = null,
    @SerialName("total_pay") val totalPay: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("query_notes") val queryNotes: String? = null,
) {
    fun toDomain(): PayrollLine? {
        // `_id` on the Mongo-backed weekly collection, `id` on the projections.
        val identifier = (id ?: underscoreId)?.takeIf { it.isNotBlank() } ?: return null
        val crew = (crewId ?: userId)?.takeIf { it.isNotBlank() } ?: return null

        val basic = basicPay.toAmount()
        val overtime = overtimePay.toAmount()
        val allowances = totalAllowances.toAmount() + additionalFees.toAmount()
        val taken = deductions.orEmpty().sumOf { it.amount.toAmount() }
        // The persisted total when there is one, the parts when there is not —
        // a draft that has never been saved carries no total, and showing zero
        // for a week somebody has filled in reads as a bug.
        val persisted = totalPay.toAmount()
        val gross = if (persisted != 0.0) persisted else basic + overtime + allowances

        return PayrollLine(
            id = identifier,
            crewId = crew,
            crewName = crewName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            departmentId = departmentId,
            departmentName = departmentName?.takeIf { it.isNotBlank() },
            designation = designation,
            status = TimecardStatus.from(status),
            currency = currency,
            basicPay = basic,
            overtimePay = overtime,
            allowances = allowances,
            deductions = taken,
            gross = gross,
            net = gross - taken,
            nominalCode = nominalCode,
            queryNote = queryNotes,
        )
    }
}

@Serializable
internal data class DeductionRowDto(
    @SerialName("amount") val amount: String? = null,
)

/** `{ isFinalApprover }` — camelCase, unlike the rest of the payroll surface. */
@Serializable
internal data class PayrollMetadataDto(
    @SerialName("isFinalApprover") val camel: Boolean? = null,
    @SerialName("is_final_approver") val snake: Boolean? = null,
) {
    val isFinalApprover: Boolean? get() = camel ?: snake
}

@Serializable
internal data class BankAccountDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    /** A code, or the whole currency object — see [currencyCode]. */
    @SerialName("currency") val currency: JsonElement? = null,
) {
    fun toDomain(): BankAccount? {
        val identifier = (id ?: underscoreId)?.takeIf { it.isNotBlank() } ?: return null
        return BankAccount(
            id = identifier,
            name = name?.takeIf { it.isNotBlank() }
                ?: accountName?.takeIf { it.isNotBlank() }
                ?: str(S.desktop_account_fallback, identifier),
            accountNumber = accountNumber?.takeIf { it.isNotBlank() },
            currency = currency.currencyCode(),
        )
    }
}

@Serializable
internal data class PostOutcomeDto(
    @SerialName("marked") val marked: Int? = null,
    @SerialName("skipped") val skipped: Int? = null,
) {
    fun toDomain() = PostOutcome(marked = marked ?: 0, skipped = skipped ?: 0)
}

@Serializable
internal data class AllocationDto(
    @SerialName("id") val id: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
) {
    fun toDomain(): NominalAllocation? {
        val code = nominalCode?.takeIf { it.isNotBlank() } ?: return null
        return NominalAllocation(
            id = id,
            nominalCode = code,
            description = description.orEmpty(),
            amount = amount.toAmount(),
            departmentId = departmentId,
        )
    }
}

@Serializable
internal data class PayslipDto(
    @SerialName("crew_name") val crewName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("lines") val lines: List<PayslipLineDto>? = null,
    @SerialName("gross") val gross: String? = null,
    @SerialName("deductions") val deductions: String? = null,
    @SerialName("net") val net: String? = null,
) {
    fun toDomain(crewId: String): Payslip? {
        val rows = lines.orEmpty().map { it.toDomain() }
        // No lines at all means no slip yet, which is an ordinary answer before
        // a week is paid — reported as absent rather than as empty.
        if (rows.isEmpty()) return null
        return Payslip(
            crewId = crewId,
            crewName = crewName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            currency = currency,
            lines = rows,
            gross = gross.toAmount(),
            deductions = deductions.toAmount(),
            net = net.toAmount(),
        )
    }
}

@Serializable
internal data class PayslipLineDto(
    @SerialName("label") val label: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("type") val type: String? = null,
) {
    fun toDomain() = PayslipLine(
        label = label.orEmpty(),
        amount = amount.toAmount(),
        isDeduction = type?.equals("deduction", ignoreCase = true) == true,
    )
}
