package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import kotlinx.serialization.json.JsonElement

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
) : CostReportRepository {

    private val base = config.apiV2(ZillitService.CostReport).trimEnd('/') + "/cost-reports"
    private val hubBase = config.apiV2(ZillitService.AccountHub).trimEnd('/') + "/account-hub"
    private val presetBase = config.apiV2(ZillitService.Core).trimEnd('/') + "/preset"

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

    // -- plumbing ------------------------------------------------------------

    private suspend fun get(url: String, query: Map<String, Any?> = emptyMap()) = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> ZillitResult.Failure(error)
            is ZillitResult.Success -> if (data.status == 1) {
                ZillitResult.Success(transform(data.data))
            } else {
                ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = data.message))
            }
        }
}
