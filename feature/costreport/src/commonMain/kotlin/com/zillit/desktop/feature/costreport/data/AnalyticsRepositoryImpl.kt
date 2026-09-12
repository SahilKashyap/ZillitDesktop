package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsPage
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsQuery
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsRepository

/** The three analytics reads on the cost-report service (`/api/v2/cost-reports/analytics`). */
class AnalyticsRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : AnalyticsRepository {

    private val base = config.apiV2(ZillitService.CostReport).trimEnd('/') + "/cost-reports/analytics"

    override suspend fun modules(query: AnalyticsQuery): ZillitResult<List<AnalyticsModuleMeta>> =
        get("$base/modules", query.parameters()).mapData(::parseAnalyticsModules)

    override suspend fun overview(query: AnalyticsQuery): ZillitResult<AnalyticsPage?> =
        get("$base/overview", query.parameters()).mapData { parseAnalyticsPage(it, overview = true) }

    override suspend fun module(id: String, query: AnalyticsQuery, sub: String?): ZillitResult<AnalyticsPage?> =
        get("$base/${id.encodePath()}", query.parameters(sub)).mapData { parseAnalyticsPage(it) }

    private suspend fun get(url: String, query: Map<String, String>) = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )
}
