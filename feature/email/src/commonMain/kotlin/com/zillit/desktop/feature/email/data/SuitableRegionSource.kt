package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.email.domain.StorageTarget
import com.zillit.desktop.feature.email.domain.StorageTargetSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Where this user's uploads should go.
 *
 * `GET preset/suitable-region`, on the core API rather than the mail one — the
 * bucket is chosen per user so a production's files sit near the people working
 * on it, and every module that uploads asks the same question.
 *
 * Cached for the session: it does not change while the app is open, and every
 * attachment would otherwise pay for it.
 */
class SuitableRegionSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : StorageTargetSource {

    private var cached: StorageTarget? = null

    override suspend fun target(): ZillitResult<StorageTarget> {
        cached?.let { return ZillitResult.Success(it) }

        return apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Core)}preset/suitable-region",
            serializer = JsonElement.serializer(),
            module = RequestModule.Device,
        ).map { payload ->
            val row = payload as? JsonObject
            StorageTarget(
                region = row?.str("aws_region").orEmpty(),
                bucket = row?.str("upload_bucket").orEmpty(),
            ).also { if (it.isUsable) cached = it }
        }
    }
}
