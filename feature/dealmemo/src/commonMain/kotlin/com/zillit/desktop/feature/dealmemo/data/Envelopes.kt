package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.ApiEnvelope

/**
 * `status: 0` over a 200 is a refusal on this service — the web throws it
 * (`accountHubClient.js:146-152`), while `ApiClient` hands it back as a
 * success. Every deal-memo call goes through this, so a refusal such as
 * `deal_chase_invalid_status` never reads as done.
 */
internal fun ApiEnvelope.refusedOrOk(): ZillitResult<ApiEnvelope> =
    if (status == REFUSED) {
        ZillitResult.Failure(
            ZillitError.Http(
                status = HTTP_OK,
                serverMessage = message,
                messageElements = messageElements.orEmpty(),
            ),
        )
    } else {
        ZillitResult.Success(this)
    }

private const val REFUSED = 0
private const val HTTP_OK = 200
