package com.zillit.desktop.core.session

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.database.ProfileSnapshot
import com.zillit.desktop.core.database.ProjectCache
import com.zillit.desktop.core.database.ProjectSnapshot
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything the app needs to know about the production it just opened.
 *
 * Fetched once on selection and cached, because the same four answers are
 * needed by nearly every screen: who am I, which production is this, who else
 * is on it, and what may I do. Asking per screen would mean four calls per
 * window opened.
 *
 * ## Cache-first, network-second
 *
 * The cache answers immediately so a returning user sees a populated app rather
 * than four spinners, then the network refreshes it. A failed refresh leaves
 * the cached answer standing — stale crew names are more useful than none, and
 * the alternative is an empty app whenever the connection is poor.
 */
class ProjectContextLoader(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /**
     * Null when the local database could not open (no keychain, first run on
     * a new runtime). The loader's real job is the network fetch — running
     * without the cache costs cold-start warmth, never the crew list. This
     * nullability exists because the one time it was a hard dependency, a
     * keychain refusal silently emptied every crew list in the app.
     */
    private val cache: ProjectCache?,
) {

    private val state = MutableStateFlow(ProjectContext())
    val context: StateFlow<ProjectContext> = state.asStateFlow()

    private val api get() = config.apiV2()

    /**
     * Which production the state currently belongs to. Refreshes check it
     * before publishing: with refreshes running behind an already-open shell,
     * a quick project switch would otherwise let production A's late answers
     * land inside production B's screens.
     */
    private var currentProject: String? = null

    /**
     * Whether this production can be opened without the network: its profile
     * and its own record were saved on a previous visit. Nothing is published.
     */
    fun hasCached(projectId: String): Boolean =
        cache?.profile(projectId) != null && cache.project(projectId) != null

    /**
     * Publishes whatever is cached and answers whether it was enough to open
     * on. "Enough" is the profile and the production itself — screens degrade
     * gracefully without the crew list, which follows with the refresh.
     */
    fun publishCached(projectId: String): Boolean {
        currentProject = projectId
        val cached = ProjectContext(
            profile = cache?.profile(projectId),
            project = cache?.project(projectId),
            users = cache?.users(projectId).orEmpty(),
            isFromCache = true,
        )
        state.value = cached
        return cached.profile != null && cached.project != null
    }

    /**
     * The three refreshes, in parallel: different endpoints on different
     * services, with no ordering between them — serially they were most of
     * the time a project took to open.
     */
    suspend fun refresh(projectId: String) = coroutineScope {
        launch { refreshProfile(projectId) }
        launch { refreshProject(projectId) }
        launch { refreshUsers(projectId) }
    }

    /** Publishes whatever is cached, then refreshes each part independently. */
    suspend fun load(projectId: String) {
        publishCached(projectId)
        refresh(projectId)
    }

    /** Applies a refresh only if [projectId] is still the open production. */
    private inline fun publishIfCurrent(projectId: String, update: (ProjectContext) -> ProjectContext) {
        if (currentProject == projectId) state.value = update(state.value)
    }

    /**
     * Each part refreshes on its own.
     *
     * A failing users call must not cost the profile: they are different
     * endpoints on different services, and one being down is not a reason to
     * show an empty production.
     */
    private suspend fun refreshProfile(projectId: String) {
        when (val result = fetch("${api}user/profile", ProfileDto.serializer())) {
            is ZillitResult.Success -> result.data.toSnapshot()?.let { profile ->
                cache?.saveProfile(projectId, profile)
                publishIfCurrent(projectId) { it.copy(profile = profile, isFromCache = false) }
            }
            is ZillitResult.Failure -> warn("profile", result)
        }
    }

    private suspend fun refreshProject(projectId: String) {
        when (val result = fetch("${api}project/$projectId", ProjectDetailDto.serializer())) {
            is ZillitResult.Success -> result.data.toSnapshot(projectId).let { project ->
                cache?.saveProject(project)
                publishIfCurrent(projectId) { it.copy(project = project, isFromCache = false) }
            }
            is ZillitResult.Failure -> warn("project details", result)
        }
    }

    /**
     * Another production's crew, without moving this loader onto it.
     *
     * The Chat widget needs names and designations for the production it is
     * showing, which is not necessarily the one the app is open on. The call
     * names that production **and the user's id on it** — a project override
     * without the matching identity answers for the wrong person.
     */
    /**
     * Another production's details — its storage above all.
     *
     * Which bucket, or which Box enterprise and folder, a file belongs in is a
     * fact about the production it is posted to, so a widget uploading into
     * another production has to ask that production, not the open one.
     */
    suspend fun projectOf(projectId: String, userId: String): ZillitResult<ProjectSnapshot> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}project/$projectId",
            serializer = ProjectDetailDto.serializer(),
            module = RequestModule.ProjectUser,
            options = CallOptions(projectId = projectId, userId = userId),
        ).map { it.toSnapshot(projectId) }

    suspend fun usersOf(projectId: String, userId: String): ZillitResult<List<UserSnapshot>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}project/users",
            serializer = ListSerializer(ProjectUserDto.serializer()),
            module = RequestModule.ProjectUser,
            options = CallOptions(projectId = projectId, userId = userId),
        ).map { rows -> rows.mapNotNull { it.toSnapshot() } }

    private suspend fun refreshUsers(projectId: String) {
        val result = apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}project/users",
            serializer = ListSerializer(ProjectUserDto.serializer()),
            module = RequestModule.ProjectUser,
        )
        when (result) {
            is ZillitResult.Success -> {
                val users = result.data.mapNotNull { it.toSnapshot() }
                cache?.saveUsers(projectId, users)
                publishIfCurrent(projectId) { it.copy(users = users, isFromCache = false) }
            }
            is ZillitResult.Failure -> warn("crew list", result)
        }
    }

    private suspend fun <T : Any> fetch(url: String, serializer: kotlinx.serialization.KSerializer<T>) =
        apiClient.request(
            verb = HttpVerb.Get,
            url = url,
            serializer = serializer,
            module = RequestModule.ProjectUser,
        )

    private fun warn(what: String, failure: ZillitResult.Failure) {
        // Not fatal, and said out loud: a screen showing stale crew names with
        // nothing in the log is how "why is this list wrong" becomes unanswerable.
        ZillitLog.w(TAG) { "could not refresh $what: ${failure.error.technical ?: failure.error.userMessage}" }
    }

    /** Project switch and sign-out. */
    fun clear(projectId: String?) {
        projectId?.let { cache?.clearProject(it) }
        currentProject = null
        state.value = ProjectContext()
    }

    private companion object {
        const val TAG = "ProjectContext"
    }
}

/** Who I am, where I am, and who else is here. */
data class ProjectContext(
    val profile: ProfileSnapshot? = null,
    val project: ProjectSnapshot? = null,
    val users: List<UserSnapshot> = emptyList(),
    /** True while showing cached answers the network has not yet confirmed. */
    val isFromCache: Boolean = false,
) {
    fun user(userId: String?): UserSnapshot? =
        userId?.let { id -> users.firstOrNull { it.userId == id } }

    val isAdmin: Boolean get() = profile?.isAdmin == true
}

@Serializable
internal data class ProfileDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("profile_picture") val avatar: JsonElement? = null,
    @SerialName("is_admin") val isAdmin: Boolean? = null,
    /**
     * Where this person sits on the production.
     *
     * All four are on the profile and none of them are on `project/users`,
     * which carries a `department`/`designation` pair of names and no ids at
     * all. The settings form needs the ids to preselect its pickers, and
     * matching the crew list's names against the department catalogue does not
     * work — they are not the same strings.
     */
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean? = null,
    /** ZL-21078: show the Zillit mailbox address on the crew list. Absent means the server default, ON. */
    @SerialName("zillit_email_enable") val zillitEmailEnable: Boolean? = null,
    @SerialName("mail_box_detail") val mailBoxDetail: MailBoxDetailDto? = null,
    // Which production unit this user is on. Set from the unit picker in
    // settings; the web reads the same two fields back to seed it.
    @SerialName("join_unit_id") val joinUnitId: String? = null,
    @SerialName("join_unit_name") val joinUnitName: String? = null,
    /** The Home tab to land on — Android's `UserData.defaultUnitId`, same key. */
    @SerialName("default_unit_id") val defaultUnitId: String? = null,
) {
    fun toSnapshot(): ProfileSnapshot? {
        val resolved = userId ?: id ?: return null
        return ProfileSnapshot(
            userId = resolved,
            firstName = firstName?.takeIf { it.isNotBlank() },
            lastName = lastName?.takeIf { it.isNotBlank() },
            departmentId = departmentId?.takeIf { it.isNotBlank() },
            departmentName = departmentName?.takeIf { it.isNotBlank() },
            designationId = designationId?.takeIf { it.isNotBlank() },
            designationName = designationName?.takeIf { it.isNotBlank() },
            keepNamePrivate = keepNamePrivate == true,
            showMailboxInCrewList = zillitEmailEnable,
            mailboxAddress = mailBoxDetail?.emailAddress?.takeIf { it.isNotBlank() },
            fullName = listOfNotNull(firstName, lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { email.orEmpty() },
            email = email,
            phone = phone,
            avatarUrl = avatar.toImageUrl(),
            isAdmin = isAdmin == true,
            joinUnitId = joinUnitId?.takeIf { it.isNotBlank() },
            joinUnitName = joinUnitName?.takeIf { it.isNotBlank() },
            defaultUnitId = defaultUnitId?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class ProjectDetailDto(
    @SerialName("project_name") val name: String? = null,
    @SerialName("project_code") val code: String? = null,
    @SerialName("project_type") val type: String? = null,
    @SerialName("project_sub_type") val subType: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    /** `BOX` for productions on Box storage; anything else means AWS. */
    @SerialName("storage_type") val storageType: String? = null,
    @SerialName("enterprise_client_id") val enterpriseClientId: String? = null,
    /**
     * Read tolerantly, not typed.
     *
     * It arrives as an *object* wrapping an `entries` array —
     * `{"id":"","name":"","entries":[…]}` — and typing it as a list made the
     * whole project-details decode fail, taking the production's name and code
     * with it. Found against QA, not in review.
     */
    @SerialName("storage_folders") val storageFolders: JsonElement? = null,
    /** Set only on a remote unit, naming the production it hangs off. */
    @SerialName("parent_project_name") val parentProjectName: String? = null,
    /** A scheduled deletion, still counting down and still stoppable. */
    @SerialName("mark_deleted") val markDeleted: Boolean? = null,
) {
    fun toSnapshot(projectId: String) = ProjectSnapshot(
        projectId = projectId,
        name = name.orEmpty(),
        code = code.orEmpty(),
        type = type,
        subType = subType,
        companyName = companyName?.takeIf { it.isNotBlank() },
        storageType = storageType?.takeIf { it.isNotBlank() },
        enterpriseClientId = enterpriseClientId?.takeIf { it.isNotBlank() },
        storageFolders = readStorageFolders(storageFolders),
        parentName = parentProjectName?.takeIf { it.isNotBlank() },
        markedForDeletion = markDeleted == true,
    )
}

/**
 * Box folder ids by name.
 *
 * Accepts the wrapper object the server actually sends and a bare array, since
 * only one of those is documented and neither is guaranteed. Anything else
 * yields no folders, which costs a fallback to Box's root rather than a failed
 * decode of the whole production.
 */
private fun readStorageFolders(payload: JsonElement?): Map<String, String> {
    val entries = when (payload) {
        is JsonArray -> payload
        is JsonObject -> payload["entries"] as? JsonArray ?: return emptyMap()
        else -> return emptyMap()
    }

    return entries.mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val id = row.text("id") ?: return@mapNotNull null
        val name = row.text("name") ?: return@mapNotNull null
        name to id
    }.toMap()
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

@Serializable
internal data class ProjectUserDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    // The wire's spellings are `department_name`/`designation_name` (Android
    // `JoinProjectResponse.kt:92-95`, web `UserCard.jsx:352`); the bare forms
    // are kept as fallbacks for older payloads. Reading only the bare form
    // left every designation blank across the chat and contact lists.
    @SerialName("department") val department: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation") val designation: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("profile_picture") val avatar: JsonElement? = null,
    @SerialName("is_admin") val isAdmin: Boolean? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean? = null,
    @SerialName("last_activity") val lastActivity: Long? = null,
    @SerialName("last_visited_on") val lastVisitedOn: Long? = null,
    // "accepted", "approved", "pending", "left", "removed", "rejected" —
    // Android `JoinProjectResponse.kt:120`. The lists decide who shows by it.
    @SerialName("status") val status: String? = null,
) {
    fun toSnapshot(): UserSnapshot? {
        val resolved = userId ?: id ?: return null
        val full = name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        return UserSnapshot(
            userId = resolved,
            // A crew member with no name is still someone to attribute a post
            // to; falling back keeps the board readable.
            fullName = full.ifBlank { email ?: "Unknown" },
            email = email,
            department = (departmentName ?: department)?.takeIf { it.isNotBlank() },
            designation = (designationName ?: designation)?.takeIf { it.isNotBlank() },
            avatarUrl = avatar.toImageUrl(),
            isAdmin = isAdmin == true,
            deviceId = deviceId?.takeIf { it.isNotBlank() },
            keepNamePrivate = keepNamePrivate == true,
            // The web's preference order (`UserCard.jsx:357-362`), zeros as absent.
            lastActiveMillis = lastActivity?.takeIf { it > 0 } ?: lastVisitedOn?.takeIf { it > 0 },
            status = status?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Reads an avatar URL from a field that is sometimes a string and sometimes an
 * attachment object.
 *
 * QA returns `{"profile_picture": {...}}` where the Android model declares a
 * string; a typed decode rejects the whole response, which cost the profile
 * *and* the crew list on the first live run. An avatar is decoration — it must
 * never be the reason a crew list fails to load.
 */
private fun JsonElement?.toImageUrl(): String? = when (this) {
    null -> null
    is JsonPrimitive -> if (isString) content.takeIf { it.isNotBlank() } else null
    is JsonObject -> IMAGE_KEYS.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
    else -> null
}

/** In preference order — a thumbnail is the right size for an avatar. */
private val IMAGE_KEYS = listOf("thumbnail", "media", "url", "path", "file_name")

/** The profile's mailbox block — only the address is read here; credentials live in the email module. */
@Serializable
internal data class MailBoxDetailDto(
    @SerialName("email_address") val emailAddress: String? = null,
)
