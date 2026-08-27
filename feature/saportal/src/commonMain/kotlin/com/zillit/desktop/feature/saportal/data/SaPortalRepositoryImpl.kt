package com.zillit.desktop.feature.saportal.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.saportal.domain.ArtisteQuery
import com.zillit.desktop.feature.saportal.domain.PayStatement
import com.zillit.desktop.feature.saportal.domain.SaPortalRepository
import com.zillit.desktop.feature.saportal.domain.SaProfile
import com.zillit.desktop.feature.saportal.domain.SaSummary
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherDetail
import com.zillit.desktop.feature.saportal.domain.VoucherStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The artiste's own side, on `sae-server`.
 *
 * ## This service's envelope is not the estate's
 *
 * A successful answer carries **no `status` field** — just `{message, data}`.
 * Every other module here treats `status != 1` inside a 200 as a failure; do
 * that on this service and every good answer is refused. Failure still looks
 * normal (`{status: 0, …}`), and the HTTP code carries it, which is what
 * [ApiClient] already reads. So success is simply a 2xx.
 *
 * ## Everything is scoped to the signed-in artiste
 *
 * There is no id to pass: the server resolves the artiste from
 * `{projectId, userId}`. A user with no artiste record on this production
 * gets `artiste_not_found_for_user`, which is an ordinary answer for most
 * crew rather than a fault — see [SaPortalViewModel]'s handling.
 */
class SaPortalRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : SaPortalRepository {

    private val base = "${config.baseUrl(ZillitService.SupportingArtists)}/api/v2/sa-portal"

    override suspend fun summary(): ZillitResult<SaSummary> =
        read("$base/summary", SummaryDto.serializer()).map { it.toDomain() }

    override suspend fun profile(): ZillitResult<SaProfile> =
        read("$base/me", ProfileDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("artiste record had no id"))
        }

    override suspend fun vouchers(status: VoucherStatus?): ZillitResult<List<Voucher>> =
        read(
            "$base/vouchers",
            ListSerializer(VoucherDto.serializer()),
            // Unknown is this client's "unrecognised", not a bucket the server
            // has — asking for it would filter everything out.
            query = mapOf("status" to status?.wire?.takeIf { it.isNotBlank() }),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun voucher(id: String): ZillitResult<VoucherDetail> =
        read("$base/vouchers/$id", VoucherDetailDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("voucher had no id"))
        }

    override suspend fun sign(
        id: String,
        typedName: String,
        consentAccuracy: Boolean,
        consentESign: Boolean,
    ): ZillitResult<Unit> = mutate(
        "$base/vouchers/$id/sign",
        buildJsonObject {
            put("typed_name", JsonPrimitive(typedName.trim()))
            put("consent_accuracy", JsonPrimitive(consentAccuracy))
            put("consent_esign", JsonPrimitive(consentESign))
        },
    )

    override suspend fun pay(): ZillitResult<PayStatement> =
        read("$base/pay", PayDto.serializer()).map { it.toDomain() }

    override suspend fun queries(): ZillitResult<List<ArtisteQuery>> =
        read("$base/queries", ListSerializer(QueryDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun query(id: String): ZillitResult<ArtisteQuery> =
        read("$base/queries/$id", QueryDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("query had no id"))
        }

    /**
     * A query always names the day it is about — the server rejects one
     * without a `voucher_id`, and appends to the existing thread when that
     * day already has one rather than opening a second.
     */
    override suspend fun raiseQuery(voucherId: String, text: String): ZillitResult<Unit> = mutate(
        "$base/queries",
        buildJsonObject {
            put("voucher_id", JsonPrimitive(voucherId))
            put("text", JsonPrimitive(text.trim()))
        },
    )

    override suspend fun replyToQuery(id: String, text: String): ZillitResult<Unit> = mutate(
        "$base/queries/$id/reply",
        buildJsonObject { put("text", JsonPrimitive(text.trim())) },
    )

    override suspend fun resolveQuery(id: String): ZillitResult<Unit> =
        mutate("$base/queries/$id/resolve", null)

    // -- plumbing ------------------------------------------------------------

    private suspend fun <T> read(
        url: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun mutate(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = url,
            module = RequestModule.ProjectUser,
            body = body,
        ).asUnit()

    /** Success is a 2xx and nothing more — see the class doc on the envelope. */
    private fun ZillitResult<ApiEnvelope>.asUnit(): ZillitResult<Unit> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(Unit)
        is ZillitResult.Failure -> ZillitResult.Failure(error)
    }

    private inline fun <T, R> ZillitResult<T>.flatMap(
        transform: (T) -> ZillitResult<R>,
    ): ZillitResult<R> = when (this) {
        is ZillitResult.Success -> transform(data)
        is ZillitResult.Failure -> ZillitResult.Failure(error)
    }
}
