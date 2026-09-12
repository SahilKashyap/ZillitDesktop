package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CostReportSync
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrLockState
import com.zillit.desktop.feature.costreport.domain.CrWrite
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.EtcVersionLine
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.SnapshotPost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The cost-report service (`/api/v2/cost-reports` on its own host) plus the
 * reference data it leans on: chart of accounts, budgets, companies and
 * currencies on the Account Hub host, and the currency catalogue on core.
 *
 * Every read answers the standard envelope; `status != 1` inside a 200 is a
 * failure and its `message` is what the user sees.
 */
class CostReportRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : CostReportRepository {

    private val base = config.apiV2(ZillitService.CostReport).trimEnd('/') + "/cost-reports"
    private val hubBase = config.apiV2(ZillitService.AccountHub).trimEnd('/') + "/account-hub"
    private val presetBase = config.apiV2(ZillitService.Core).trimEnd('/') + "/preset"

    /**
     * See [CostReportRepository.syncs]. Another production's frame is dropped
     * when both sides can name a project — the same cross-project gate the
     * web's account-hub wrapper applies before any handler runs.
     */
    override val syncs: Flow<CostReportSync> =
        bus?.onAny(CR_SYNC_EVENTS, CrSyncEnvelope.serializer())
            ?.mapNotNull { (event, envelope) ->
                costReportSyncFor(event).takeIf { envelope.inProject(currentProjectId()) }
            }
            ?: emptyFlow()

    override suspend fun chartOfAccounts(): ZillitResult<List<CoaRow>> =
        get("$hubBase/chart-of-accounts", mapOf("active_only" to "false")).mapData(::parseCoaRows)

    override suspend fun budgets(): ZillitResult<List<BudgetVersion>> =
        get("$hubBase/budgets").mapData(::parseBudgets)

    override suspend fun companies(): ZillitResult<List<CrCompany>> =
        get("$hubBase/project-settings/companies").mapData(::parseCompanies)

    override suspend fun currencies(): ZillitResult<CurrencyOptions> =
        get("$hubBase/project-settings/project-currencies").mapData(::parseCurrencyOptions)

    override suspend fun currencyCatalogue(): ZillitResult<List<CrCurrency>> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = "$presetBase/currencies",
        module = RequestModule.Device,
    ).mapData(::parseCurrencyCatalogue)

    override suspend fun live(
        periodStartMs: Long,
        periodEndMs: Long,
        budgetVersionId: String?,
        companyId: String?,
        currency: String?,
    ): ZillitResult<LiveReport> = get(
        "$base/live",
        mapOf(
            "period_start" to periodStartMs.toString(),
            "period_end" to periodEndMs.toString(),
            "budget_version_id" to budgetVersionId?.takeIf { it.isNotBlank() },
            "company_id" to companyId?.takeIf { it.isNotBlank() },
            "currency" to currency?.takeIf { it.isNotBlank() },
        ),
    ).mapData(::parseLiveReport)

    override suspend fun snapshots(cadence: SnapshotCadence?): ZillitResult<List<SnapshotHeader>> =
        get("$base/snapshots", mapOf("cadence" to cadence?.wire)).mapData(::parseSnapshotList)

    override suspend fun snapshot(id: String): ZillitResult<SnapshotDetail> =
        get("$base/snapshots/$id").mapData { data -> parseSnapshotDetail(data) }.let { result ->
            when (result) {
                is ZillitResult.Failure -> result
                is ZillitResult.Success -> result.data?.let { ZillitResult.Success(it) }
                    ?: ZillitResult.Failure(ZillitError.Serialization("snapshot $id had no header"))
            }
        }

    override suspend fun accountLineItems(
        code: String,
        type: LedgerType?,
        source: String?,
        currency: String?,
    ): ZillitResult<LedgerResult> = get(
        "$base/account-line-items",
        mapOf(
            "code" to code,
            "type" to type?.wire,
            "source" to source?.takeIf { it.isNotBlank() },
            "currency" to currency?.takeIf { it.isNotBlank() },
        ),
    ).mapData { parseLedger(it, code) }

    // -- the accountant's worksheet -------------------------------------------

    override suspend fun etcVersions(weekEnding: String): ZillitResult<List<EtcVersion>> =
        get("$base/weekly-etc/versions", mapOf("week_ending" to weekEnding)).mapData(::parseEtcVersions)

    override suspend fun etcVersion(versionId: String): ZillitResult<List<EtcVersionLine>> =
        get("$base/weekly-etc/versions/${versionId.encodePath()}").mapData(::parseEtcVersionLines)

    override suspend fun createEtcVersion(
        weekEnding: String,
        label: String,
        lines: List<EtcVersionLine>,
        currency: String?,
    ): ZillitResult<CrWrite<String?>> =
        send(HttpVerb.Post, "$base/weekly-etc/versions", etcVersionBody(weekEnding, label, lines, currency))
            .mapWrite(::parseCreatedVersionId)

    override suspend fun updateEtcVersion(
        versionId: String,
        lines: List<EtcVersionLine>,
        currency: String?,
    ): ZillitResult<CrWrite<Unit>> =
        send(
            HttpVerb.Patch,
            "$base/weekly-etc/versions/${versionId.encodePath()}",
            etcVersionPatchBody(lines, currency),
        )
            .mapWrite { }

    override suspend fun lockState(): ZillitResult<CrLockState> =
        get("$base/lock-period").mapData(::parseLockState)

    override suspend fun lockPeriod(asOfMs: Long): ZillitResult<CrWrite<Unit>> =
        send(HttpVerb.Post, "$base/lock-period", buildJsonObject { put("as_of", asOfMs) }).mapWrite { }

    override suspend fun postSnapshot(post: SnapshotPost): ZillitResult<CrWrite<SnapshotHeader?>> =
        send(HttpVerb.Post, "$base/snapshots", snapshotPostBody(post))
            .mapWrite { data -> (data as? JsonObject)?.let(::parseSnapshotHeader) }

    // -- plumbing ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()) = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun send(verb: HttpVerb, url: String, body: JsonObject) = apiClient.envelope(
        verb = verb,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    )
}

/** A write's value plus the server's message, which the screen shows as the confirmation. */
private inline fun <T> ZillitResult<ApiEnvelope>.mapWrite(transform: (JsonElement?) -> T): ZillitResult<CrWrite<T>> =
    when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> if (data.status == 1) {
            ZillitResult.Success(CrWrite(transform(data.data), data.message?.takeIf { it.isNotBlank() }))
        } else {
            ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = data.message))
        }
    }

internal inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
    when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> if (data.status == 1) {
            ZillitResult.Success(transform(data.data))
        } else {
            ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = data.message))
        }
    }

/** Version ids are opaque; the web percent-encodes them into the path, so this does too. */
internal fun String.encodePath(): String = buildString {
    this@encodePath.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if ((byte >= 0 && char.isLetterOrDigit()) || char in "-_.~") append(char) else append("%" + byte.hex())
    }
}

private fun Byte.hex(): String = (toInt() and BYTE_MASK).toString(HEX_RADIX).uppercase().padStart(2, '0')

private const val BYTE_MASK = 0xFF
private const val HEX_RADIX = 16
