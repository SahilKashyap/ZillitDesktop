package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.payroll.domain.BatchOutcome
import com.zillit.desktop.feature.payroll.domain.PayrollAdjustmentRepository
import com.zillit.desktop.feature.payroll.domain.PayrollJournalRepository
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollSettingsRepository
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PostOutcome
import com.zillit.desktop.feature.payroll.domain.ProcessingQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The payroll service's weekly surface.
 *
 * ## Which host answers what
 *
 *  - payroll: `/payroll/weekly/…` (the queues), `/payroll/timecards/weekly/…`
 *    (every transition is done to timecards), `/payroll/metadata`,
 *    `/payroll/runs/journal-ledger` and the exports;
 *  - account hub: bank accounts, `/payroll-settings`, the chart of accounts,
 *    the companies and the project settings' copy of the cost-report lock;
 *  - cost report: `/cost-reports/lock-period`;
 *  - deal memo: the crew member's active deal, for the nominal fallback;
 *  - cash expenses: the claim batches routed to payroll.
 *
 * The accountant-payroll routes the web declares
 * (`/payroll/timecards/accountant-payroll/{ws}/crew/{id}/nominal|payslip|audit`,
 * `/post`, `/post-all`, `/correction`, `/queue`) answer 404 on every verb and
 * the web never calls them; nothing here does either.
 */
class PayrollRepositoryImpl(
    apiClient: ApiClient,
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

    private val http = PayrollHttp(apiClient)
    private val payroll = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll"
    private val weekly = "$payroll/weekly"
    private val timecards = "$payroll/timecards/weekly"

    override val journal: PayrollJournalRepository = PayrollJournalSource(apiClient, payroll)

    override val adjustments: PayrollAdjustmentRepository = PayrollAdjustmentSource(
        apiClient = apiClient,
        timecards = timecards,
        cashExpenses = "${config.baseUrl(ZillitService.CashExpenses)}/api/v2/cash-expenses",
    )

    override val settings: PayrollSettingsRepository = PayrollSettingsSource(http, config, payroll)

    override suspend fun paidCrew(weekStarting: Long): ZillitResult<List<PayrollTimecard>> =
        http.get("$weekly/$weekStarting/paid").map { data -> data.rows().mapNotNull { it.toTimecard() } }

    override suspend fun runQueue(weekStarting: Long): ZillitResult<List<PayrollTimecard>> =
        http.get("$weekly/$weekStarting/processing").map { data ->
            data.rows("timecards").mapNotNull { it.toTimecard() }
        }

    /**
     * `/weekly/processing` answers an object — `{ week_starting, timezone,
     * timecards }` — where the week-scoped route answers a bare array; both
     * spellings are read.
     */
    override suspend fun processingQueue(): ZillitResult<ProcessingQueue> =
        http.get("$weekly/processing").map { data ->
            ProcessingQueue(
                weekStarting = data.obj()?.millis("week_starting"),
                timecards = data.rows("timecards").mapNotNull { it.toTimecard() },
            )
        }

    override suspend fun outstanding(): ZillitResult<List<PayrollTimecard>> =
        http.get("$timecards/payroll-processing/outstanding").map { data ->
            data.rows("timecards").mapNotNull { it.toTimecard() }
        }

    override suspend fun outstandingFor(userId: String): ZillitResult<List<PayrollTimecard>> =
        http.get("$payroll/outstanding/$userId").map { data ->
            data.rows("timecards").mapNotNull { it.toTimecard() }.sortedByDescending { it.weekStarting ?: 0L }
        }

    override suspend fun timecard(timecardId: String): ZillitResult<PayrollTimecard> =
        when (val result = http.get("$timecards/$timecardId")) {
            is ZillitResult.Failure -> result
            is ZillitResult.Success -> result.data.obj()?.let { it.obj("data") ?: it }?.toTimecard()
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("timecard $timecardId: no document"))
        }

    override suspend fun finalApprove(timecardIds: List<String>): ZillitResult<BatchOutcome> =
        http.batch("$timecards/batch/final-approve", timecardIds)

    override suspend fun lock(timecardIds: List<String>): ZillitResult<BatchOutcome> =
        http.batch("$timecards/batch/lock", timecardIds)

    override suspend fun unlock(timecardId: String): ZillitResult<String?> =
        http.write("$timecards/$timecardId/unlock", JsonObject(emptyMap()))

    // The reason travels only when there is one — `{}` otherwise, as the web sends it.
    override suspend fun override(timecardId: String, reason: String): ZillitResult<String?> =
        http.write("$timecards/$timecardId/override", overrideBody(reason))

    override suspend fun markPaid(timecardId: String): ZillitResult<String?> =
        http.write("$timecards/$timecardId/mark-paid", body = null)

    override suspend fun markPaidBatch(timecardIds: List<String>): ZillitResult<BatchOutcome> =
        http.batch("$timecards/batch/mark-paid", timecardIds)

    override suspend fun markUnpaid(timecardId: String): ZillitResult<String?> =
        http.write("$timecards/$timecardId/mark-unpaid", body = null)

    /**
     * `bank_id` and `effective_date` are required — the server answers 400
     * without either and checks the date against the cost-report lock. No
     * `company_id`: a timecard inherits its legal entity from the deal memo.
     */
    override suspend fun markPosted(
        timecardIds: List<String>,
        bankId: String,
        effectiveDate: Long,
    ): ZillitResult<PostOutcome> =
        http.post("$timecards/batch/mark-posted", markPostedBody(timecardIds, bankId, effectiveDate)).map { envelope ->
            val data = envelope.data.obj()
            PostOutcome(
                marked = data?.number("marked")?.toInt() ?: data?.array("marked")?.size ?: 0,
                skipped = data?.number("skipped")?.toInt() ?: data?.array("skipped")?.size ?: 0,
            )
        }
}

/** The three call shapes this module makes, over the signed envelope client. */
internal class PayrollHttp(private val apiClient: ApiClient) {

    suspend fun get(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<JsonElement?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = query,
        )

    /** A POST whose `status: 0` is a refusal rather than a quiet success. */
    suspend fun post(url: String, body: JsonObject?) = apiClient.envelope(
        verb = HttpVerb.Post,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    ).refusedOnStatusZero()

    /** A single-row transition: the server's message on success. */
    suspend fun write(url: String, body: JsonObject?): ZillitResult<String?> = post(url, body).map { it.message }

    suspend fun batch(url: String, ids: List<String>): ZillitResult<BatchOutcome> =
        post(url, idsBody(ids)).map { envelope -> envelope.toBatchOutcome() }
}

internal fun idsBody(ids: List<String>) = buildJsonObject {
    put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
}

internal fun overrideBody(reason: String): JsonObject = reason.trim().takeIf { it.isNotEmpty() }
    ?.let { buildJsonObject { put("reason", JsonPrimitive(it)) } }
    ?: JsonObject(emptyMap())

internal fun markPostedBody(ids: List<String>, bankId: String, effectiveDate: Long) = buildJsonObject {
    put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    put("bank_id", JsonPrimitive(bankId))
    put("effective_date", JsonPrimitive(effectiveDate))
}
