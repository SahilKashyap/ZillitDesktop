package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.json.JsonElement
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.auth.domain.AuthSession
import com.zillit.desktop.feature.auth.domain.CodeLookup
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.resolvedSubType
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.Unit as DomainUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Projects and units.
 *
 * [onProjectChanged] is how the rest of the app learns that the active
 * production moved. Switching project is not a field update — it invalidates
 * project-scoped caches and preferences and closes project-scoped workspace
 * windows. Leaving one production's call sheet open while another is active is
 * the kind of mistake that puts the wrong information in front of a crew.
 */
class ProjectRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val updateSession: ((AuthSession?) -> AuthSession?) -> kotlin.Unit,
    private val onProjectChanged: suspend (Project?, DomainUnit?) -> kotlin.Unit,
) : ProjectRepository {

    private val endpoints = AuthEndpoints(config)

    override suspend fun listProjects(): ZillitResult<List<Project>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.projects,
            serializer = ListSerializer(ProjectDto.serializer()),
            module = RequestModule.Project,
        ).map { dtos ->
            // Deleted and disabled productions come back in the list; showing
            // them invites someone to pick a production that no longer exists.
            dtos.filter { it.isSelectable }.mapNotNull { it.toDomain() }
        }

    /**
     * Resolves a production code.
     *
     * A `PUT` with the code in the body, which reads oddly for a lookup and is
     * what the server wants — Android's `joinProjectPreApprovedApi` sends
     * exactly this, under `WITH_PROJECT_ID` headers ([RequestModule.Project]).
     *
     * The answer is read leniently rather than through a typed DTO: it carries
     * a whole user record in one branch and a whole project in the other, and
     * this endpoint only has to yield an id or two from either.
     */
    override suspend fun findByCode(code: String): ZillitResult<CodeLookup> {
        val trimmed = code.trim()
        if (trimmed.isBlank()) {
            return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_enter_a_project_code)))
        }

        return apiClient.request(
            verb = HttpVerb.Put,
            url = endpoints.joinProjectAsUser,
            serializer = JsonElement.serializer(),
            module = RequestModule.Project,
            body = jsonBody(buildJsonObject { put("code", trimmed) }),
        ).flatMapNotNull { body ->
            body.toCodeLookup()
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Validation(str(S.desktop_no_project_found_for_code)))
        }
    }

    override suspend fun create(
        draft: NewProductionDraft,
        selectedType: ProductionType?,
        confirmCode: String,
    ): ZillitResult<Project> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.projects,
            serializer = ProjectDto.serializer(),
            // Pre-project by definition — there is no project id to send yet.
            module = RequestModule.Device,
            body = jsonBody(draft.toCreateDto(selectedType, confirmCode)),
        ).flatMapNotNull { dto ->
            dto.toDomain()
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(
                    ZillitError.Serialization("the project was created but the response had no id"),
                )
        }

    override suspend fun setFavourite(projectId: String, favourite: Boolean): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = endpoints.favouriteProject,
            // MODELDATA.WITH_PROJECT_ID (`StartProjectVM.makeFavAndUnFav`).
            module = RequestModule.Project,
            body = jsonBody(FavouriteRequestDto(projectId = projectId, favourite = favourite)),
        ).map { }

    /**
     * `POST user/join-project`.
     *
     * The production is carried in the header, not the body — that is what both
     * live clients send, and the body they send is the crew member's details.
     * An earlier version of this method posted `{project_id, project_code}`,
     * which matched nothing on the server.
     */
    override suspend fun requestJoin(projectId: String, draft: JoinDraft): ZillitResult<JoinStatus> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = endpoints.joinProject,
            serializer = JoinStatusDto.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(draft.toRequestDto()),
            options = CallOptions(projectId = projectId),
        ).map { it.toDomain() }

    override suspend fun departments(projectId: String): ZillitResult<List<Department>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.departments,
            serializer = JsonElement.serializer(),
            module = RequestModule.Project,
            options = CallOptions(projectId = projectId),
        ).map { it.toDepartments() }

    override suspend fun joinStatus(projectId: String): ZillitResult<JoinStatus> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.joinStatus,
            serializer = JoinStatusDto.serializer(),
            module = RequestModule.Project,
            queryParameters = mapOf("project_id" to projectId),
        ).map { it.toDomain() }

    override suspend fun listUnits(projectId: String): ZillitResult<List<DomainUnit>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.units(projectId),
            serializer = ListSerializer(UnitDto.serializer()),
            module = RequestModule.Project,
        ).map { dtos -> dtos.mapNotNull { it.toDomain() } }

    /**
     * Switches the active production.
     *
     * The session is updated *after* [onProjectChanged] completes, so caches and
     * windows scoped to the previous project are cleared before anything can
     * read the new one — otherwise a screen can briefly render the old
     * production's data under the new production's name.
     */
    override suspend fun leaveProject(): ZillitResult<kotlin.Unit> {
        // Callback first, then the session: the callback is what clears the
        // project from the outgoing request headers, and anything that fires
        // between the two must not carry the old production.
        onProjectChanged(null, null)
        updateSession { current -> current?.copy(activeProject = null, activeUnit = null) }
        return ZillitResult.Success(kotlin.Unit)
    }

    override suspend fun selectProject(project: Project, unit: DomainUnit?): ZillitResult<kotlin.Unit> {
        onProjectChanged(project, unit)
        updateSession { current ->
            current?.copy(activeProject = project, activeUnit = unit)
        }
        return ZillitResult.Success(kotlin.Unit)
    }
}
