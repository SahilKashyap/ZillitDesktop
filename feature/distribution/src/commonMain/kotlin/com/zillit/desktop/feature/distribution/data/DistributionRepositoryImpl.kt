package com.zillit.desktop.feature.distribution.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.distribution.domain.DistributionRepository
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUnit
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `distribution/project` on the project host — one grid read, one toggle
 * (web `projectApi.js:52-87`, Android `ApiUrl.kt:126-127`).
 */
class DistributionRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : DistributionRepository {

    override suspend fun allAccess(): ZillitResult<List<DistributionUser>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Core)}distribution/project/user/access/all",
            serializer = ListSerializer(UserAccessDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.mapNotNull { it.toModel() } }

    override suspend fun setAccess(
        userId: String,
        unitId: String,
        enabled: Boolean,
        section: DistributionSection,
        isExternal: Boolean,
    ): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${config.apiV2(ZillitService.Core)}distribution/project",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("unit_id", unitId)
                put("distributionEnable", enabled)
                put("user_id", userId)
                put("type", section.wire)
                // Android sends it; the web omits. Sending is the superset
                // the server accepts from a shipping client.
                put("isExternal", isExternal)
            },
        ).refuseStatusZero().map { }
}

/** The service says no with `status: 0` on a 200 — surface its message. */
internal fun ZillitResult<ApiEnvelope>.refuseStatusZero(): ZillitResult<ApiEnvelope> =
    when (this) {
        is ZillitResult.Failure -> this
        is ZillitResult.Success ->
            if (data.status == 0) {
                ZillitResult.Failure(
                    ZillitError.Validation(data.message ?: "The server refused the change."),
                )
            } else {
                this
            }
    }

@Serializable
internal data class UserAccessDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_type") val userType: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("outsider") val outsider: String? = null,
    @SerialName("distributions") val distributions: List<UnitAccessDto>? = null,
) {
    fun toModel(): DistributionUser? {
        val id = userId ?: return null
        return DistributionUser(
            userId = id,
            userName = userName.orEmpty(),
            userType = userType.orEmpty(),
            status = status.orEmpty(),
            outsider = outsider.orEmpty(),
            units = distributions.orEmpty().mapNotNull { it.toModel() },
        )
    }
}

@Serializable
internal data class UnitAccessDto(
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("distribution_access") val toEnabled: Boolean? = null,
    @SerialName("distribution_cc_access") val ccEnabled: Boolean? = null,
    @SerialName("distribution_bcc_access") val bccEnabled: Boolean? = null,
    @SerialName("tool") val isTool: Boolean? = null,
    @SerialName("home") val isHome: Boolean? = null,
    @SerialName("to_updatable") val toUpdatable: Boolean? = null,
) {
    fun toModel(): DistributionUnit? {
        val id = unitId ?: return null
        return DistributionUnit(
            unitId = id,
            unitName = unitName.orEmpty(),
            toEnabled = toEnabled == true,
            ccEnabled = ccEnabled == true,
            bccEnabled = bccEnabled == true,
            isTool = isTool == true,
            isHome = isHome == true,
            // Absent means allowed — Android's reading; the flag exists to
            // lock a switch, not to grant it.
            toUpdatable = toUpdatable != false,
        )
    }
}
