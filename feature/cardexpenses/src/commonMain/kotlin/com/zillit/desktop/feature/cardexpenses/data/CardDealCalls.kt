package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cardexpenses.domain.DealRate
import kotlinx.serialization.json.JsonObject

/**
 * The one read the card module makes of the Deal Memo service: the requester's
 * own active deal, which a salary-basis request cap multiplies
 * (`GET /deal-memo/deal?status=active`, self-scoped by the auth headers).
 */
internal class CardDealCalls(private val apiClient: ApiClient, config: AppConfig) {

    private val deal = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo/deal"

    /** Null with no active deal; the envelope's `data` is the deal itself. */
    suspend fun activeRate(): ZillitResult<DealRate?> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = deal,
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("status" to "active"),
    ).map { envelope -> DealRate.of(envelope.data as? JsonObject) }
}
