package com.zillit.desktop.feature.crewlist.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.crewlist.domain.CrewDepartment
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `crewlist` on the units host (web `unitApi.js:9-66`, Android
 * `ApiUrl.kt:195-196`): the roster at `crewlist/list`, the PDF at a bare
 * `POST crewlist` whose answer is the stored file's S3 identity.
 */
class CrewListRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
) : CrewListRepository {

    /**
     * See [CrewListRepository.refreshes]. Another production's frame is
     * dropped when both sides can name a project, as the web handler does.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(CREW_LIST_SYNC_EVENTS)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { }
            ?: emptyFlow()

    override suspend fun roster(): ZillitResult<List<CrewUnit>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Units)}crewlist/list",
            serializer = ListSerializer(UnitDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.map { it.toModel() } }

    override suspend fun generate(hideExternalLabel: Boolean): ZillitResult<CrewListPdf> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${config.apiV2(ZillitService.Units)}crewlist",
            module = RequestModule.ProjectUser,
            // The phones' whole body. The web adds its header-designer keys;
            // omitted they render the default letterhead, which is exactly
            // the phones' output.
            body = buildJsonObject { put("hide_external_label", hideExternalLabel) },
        ).let { outcome ->
            when (outcome) {
                is ZillitResult.Failure -> outcome
                is ZillitResult.Success -> {
                    val pdf = (outcome.data.data as? JsonObject)
                        ?.let { json.decodeFromJsonElement(PdfDto.serializer(), it) }
                        ?.toModel()
                    if (outcome.data.status != 0 && pdf != null && pdf.media.isNotBlank()) {
                        ZillitResult.Success(pdf)
                    } else {
                        ZillitResult.Failure(
                            ZillitError.Validation(outcome.data.message ?: "The server answered no PDF."),
                        )
                    }
                }
            }
        }

    private companion object {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

@Serializable
internal data class UnitDto(
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("departments") val departments: List<DepartmentDto>? = null,
) {
    fun toModel() = CrewUnit(
        unitName = unitName.orEmpty(),
        departments = departments.orEmpty().map { it.toModel() },
    )
}

@Serializable
internal data class DepartmentDto(
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("users") val users: List<MemberDto>? = null,
) {
    fun toModel() = CrewDepartment(
        departmentName = departmentName.orEmpty(),
        members = users.orEmpty().mapNotNull { it.toModel() },
    )
}

@Serializable
internal data class MemberDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("primary_email") val primaryEmail: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("is_external_user") val isExternal: Boolean? = null,
) {
    fun toModel(): CrewMember? {
        val id = userId ?: return null
        return CrewMember(
            userId = id,
            fullName = fullName.orEmpty(),
            designationName = designationName.orEmpty(),
            phone = phone.orEmpty(),
            countryCode = countryCode.orEmpty(),
            primaryEmail = primaryEmail ?: email.orEmpty(),
            isExternal = isExternal == true,
        )
    }
}

@Serializable
internal data class PdfDto(
    @SerialName("media") val media: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
) {
    fun toModel() = CrewListPdf(
        media = media.orEmpty(),
        bucket = bucket.orEmpty(),
        region = region.orEmpty(),
        // The web's derivation: `original_name || name || 'Crew List.pdf'`.
        name = originalName ?: name ?: "Crew List.pdf",
    )
}
