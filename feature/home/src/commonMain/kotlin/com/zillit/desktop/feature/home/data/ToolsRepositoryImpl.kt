package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `GET project/tools` — the production's tool list and this user's rights to it.
 *
 * One call answers both "what is on the grid" and "what may this person do",
 * because on the backend they are the same list. Splitting them client-side
 * would invite the two to disagree.
 */
class ToolsRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Admins bypass access checks; the flag comes from the selected production. */
    private val isAdmin: () -> Boolean,
    /**
     * Which production the rights are asked for — the open one by default.
     * The Drive widget asks for another production's without switching to it.
     */
    private val callOptions: () -> CallOptions = { CallOptions() },
) : ToolsRepository {

    private val toolsUrl = "${config.apiV2()}project/tools"
    private val groupsUrl = "${config.apiV2()}project/tools/groups"
    private val groupOrderUrl = "${config.apiV2()}project/tools/group/order"

    override suspend fun loadPermissions(): ZillitResult<ProjectPermissions> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = toolsUrl,
            serializer = ListSerializer(ToolInfoDto.serializer()),
            // `MODELDATA.WITH_PROJECT_USER_ID` (`CommonApis:357`) — rights are
            // per-person, per-production. Sending a lighter header returns 406.
            module = RequestModule.ProjectUser,
            options = callOptions(),
        ).map { dtos ->
            ProjectPermissions(
                tools = dtos.mapNotNull { it.toAccess() },
                isAdmin = isAdmin(),
            )
        }

    /**
     * `GET project/tools/groups` — the sections and their display names.
     *
     * Read tolerantly and returned in server order: the production's own
     * arrangement is the one the crew has learned, and re-sorting it here
     * would put a desktop user's tools somewhere their phone does not.
     */
    override suspend fun loadGroups(): ZillitResult<List<ToolGroup>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = groupsUrl,
            serializer = ListSerializer(ToolGroupDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { dtos ->
            dtos.mapNotNull { dto ->
                dto.groupIdentifier?.takeIf { it.isNotBlank() }?.let { id ->
                    // Names ship as translation keys, the same as unit and tool
                    // labels do — read as such rather than printed raw.
                    val label = dto.groupName?.takeIf(String::isNotBlank) ?: id
                    ToolGroup(identifier = id, name = label.localised())
                }
            }
        }

    /** `{is_default, groups: [{group_identifier, order}]}` — sorted by `order`. */
    override suspend fun loadGroupOrder(): ZillitResult<List<String>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = groupOrderUrl,
            serializer = ToolGroupOrderDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { dto ->
            if (dto.isDefault == true) {
                emptyList()
            } else {
                dto.groups.orEmpty()
                    .sortedBy { it.order ?: Int.MAX_VALUE }
                    .mapNotNull { it.groupIdentifier?.takeIf(String::isNotBlank) }
            }
        }

    /** The PUT answers with the same shape; nothing in it the caller does not already hold. */
    override suspend fun saveGroupOrder(order: List<String>): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = groupOrderUrl,
            serializer = ToolGroupOrderDto.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(ToolGroupOrderRequestDto(order)),
        ).map { }
}

/** Android's `ToolGroupOrderModels.kt:13-38`, field for field. */
@Serializable
internal data class ToolGroupOrderDto(
    @SerialName("is_default") val isDefault: Boolean? = null,
    @SerialName("groups") val groups: List<ToolGroupOrderRowDto>? = null,
)

@Serializable
internal data class ToolGroupOrderRowDto(
    @SerialName("group_identifier") val groupIdentifier: String? = null,
    @SerialName("order") val order: Int? = null,
)

/** `{"order": ["group_admin", …]}` — position is priority, index 0 the top. */
@Serializable
internal data class ToolGroupOrderRequestDto(
    @SerialName("order") val order: List<String>,
)

/**
 * Matches Android's `ToolsInfo` field for field.
 *
 * Every flag is nullable on the wire and defaults to **false** here, not true.
 * A right the server omitted is one the user does not have.
 */
@Serializable
internal data class ToolInfoDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("group_identifier") val groupIdentifier: String? = null,
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
    @SerialName("tool") val tool: Boolean? = null,
    @SerialName("home") val home: Boolean? = null,
) {
    fun toAccess(): ToolAccess? {
        val id = identifier?.takeIf { it.isNotBlank() } ?: return null
        return ToolAccess(
            identifier = id,
            groupIdentifier = groupIdentifier?.takeIf { it.isNotBlank() },
            unitId = unitId?.takeIf { it.isNotBlank() },
            unitName = unitName?.takeIf { it.isNotBlank() },
            // `enabled` absent means on: the field marks a tool switched *off*,
            // and treating silence as "off" would empty the grid.
            enabled = enabled ?: true,
            canView = viewAccess == true,
            canPost = postingAccess == true,
            canDownload = downloadAccess == true,
            isTool = tool ?: true,
            onHome = home == true,
        )
    }
}

/** One row of `project/tools/groups`. */
@Serializable
internal data class ToolGroupDto(
    @SerialName("group_identifier") val groupIdentifier: String? = null,
    @SerialName("group_name") val groupName: String? = null,
)
