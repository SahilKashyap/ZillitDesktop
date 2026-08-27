package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The AD dashboard's data layer.
 *
 * One service behind three route families — `artistes`, `ad-shoot-days` and
 * `supporting-artist-days` — which the web routes together by prefix and
 * which therefore share a host here too.
 *
 * [resolveName] turns an internal artiste's `user_id` into a crew name. It is
 * a seam rather than a call because the crew list belongs to the open
 * production, not to this service — the same arrangement every other module
 * that names a person uses.
 */
class AdRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val resolveName: (String) -> String?,
) : AdRepository {

    private val root = "${config.baseUrl(ZillitService.AdDashboard)}/api/v2"
    private val artistes = "$root/artistes"
    private val shootDays = "$root/ad-shoot-days"
    private val saDays = "$root/supporting-artist-days"

    override suspend fun artistes(): ZillitResult<List<Artiste>> =
        read(artistes, ListSerializer(ArtisteDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain(resolveName) } }

    override suspend fun verify(artisteId: String): ZillitResult<Unit> =
        post("$artistes/$artisteId/verify", null)

    override suspend fun block(artisteId: String, reason: String?): ZillitResult<Unit> = post(
        "$artistes/$artisteId/block",
        buildJsonObject {
            // The server takes a null reason; sending "" instead would record
            // an empty explanation rather than none.
            val given = reason?.trim()?.takeIf { it.isNotEmpty() }
            put("reason", given?.let(::JsonPrimitive) ?: JsonNull)
        },
    )

    override suspend fun unblock(artisteId: String): ZillitResult<Unit> =
        post("$artistes/$artisteId/unblock", null)

    override suspend fun deleteArtiste(artisteId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$artistes/$artisteId", null)

    // -- the day --------------------------------------------------------------

    override suspend fun today(shootDate: Long): ZillitResult<AdShootDay> =
        read(
            "$shootDays/today",
            ShootDayDto.serializer(),
            query = mapOf("shoot_date" to shootDate),
        ).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("shoot day had no id"))
        }

    override suspend fun shootDays(): ZillitResult<List<AdShootDay>> =
        read(shootDays, ListSerializer(ShootDayDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun dayList(shootDate: Long): ZillitResult<List<SupportingArtistDay>> =
        read(
            saDays,
            ListSerializer(SaDayDto.serializer()),
            query = mapOf("shoot_date" to shootDate),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun addToDay(
        artisteIds: List<String>,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit> = post(
        "$saDays/bulk",
        buildJsonObject {
            put("artiste_ids", JsonArray(artisteIds.map(::JsonPrimitive)))
            put("shoot_date", JsonPrimitive(shootDate))
            callTime?.takeIf { it.isNotBlank() }?.let { put("call_time", JsonPrimitive(it)) }
        },
    )

    /**
     * Only the fields actually being changed are sent.
     *
     * A PATCH that carries every field would clear a wrap time somebody else
     * entered between this screen loading and the save.
     */
    override suspend fun updateDayEntry(
        id: String,
        callTime: String?,
        wrapTime: String?,
        attendance: AttendanceStatus?,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        "$saDays/$id",
        buildJsonObject {
            callTime?.let { put("call_time", JsonPrimitive(it)) }
            wrapTime?.let { put("wrap_time", JsonPrimitive(it)) }
            attendance?.takeIf { it != AttendanceStatus.Unknown }
                ?.let { put("attendance_status", JsonPrimitive(it.wire)) }
        },
    )

    override suspend fun removeFromDay(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$saDays/$id", null)

    override suspend fun submitDay(shootDate: Long): ZillitResult<Unit> = post(
        "$shootDays/submit",
        buildJsonObject { put("shoot_date", JsonPrimitive(shootDate)) },
    )

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

    private suspend fun post(url: String, body: JsonObject?) = mutate(HttpVerb.Post, url, body)

    private suspend fun mutate(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = verb,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    ).asUnit()

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
