package com.zillit.desktop.core.database

/**
 * Cached reference data for the open production.
 *
 * ## Its own row types, deliberately
 *
 * This module owns the schema, so it also owns the shapes that come out of it.
 * Features map to their own domain types. The alternative — returning
 * `ToolAccess` or `Notice` — would make `core:database` depend on
 * `core:permissions` and `feature:home`, which is the dependency direction the
 * whole module layout exists to prevent.
 *
 * Named `*Snapshot` rather than `Cached*` because SQLDelight already generates
 * `CachedTool` and friends from the table names, and two types with one name is
 * a confusion nobody needs at a call site.
 */
data class ProfileSnapshot(
    val userId: String,
    val fullName: String,
    /**
     * The two halves the server actually stores.
     *
     * Held alongside [fullName] rather than derived from it: the profile form
     * edits them as separate fields, and splitting a joined name on its first
     * space renames anyone whose first name has two words in it.
     */
    val firstName: String? = null,
    val lastName: String? = null,
    /**
     * Where this person sits on the production.
     *
     * Read off the profile rather than the crew list, which carries the two
     * names and neither id — so a form that has to preselect a department had
     * nothing to match on. The names are the untranslated keys
     * (`accounts_department_label`).
     */
    val departmentId: String? = null,
    val departmentName: String? = null,
    val designationId: String? = null,
    val designationName: String? = null,
    /** Producers and main cast may withhold their name from the crew list. */
    val keepNamePrivate: Boolean = false,
    val email: String?,
    val phone: String?,
    val avatarUrl: String?,
    val isAdmin: Boolean,
    /** The production unit this user is attached to, if they have picked one. */
    val joinUnitId: String? = null,
    val joinUnitName: String? = null,
    /**
     * The Home unit this person asked to land on — the profile's
     * `default_unit_id`, set from the phones' preferences. Null lands them
     * on the first tab (Android `handleDefaultUnitSelection`).
     */
    val defaultUnitId: String? = null,
)

data class ProjectSnapshot(
    val projectId: String,
    val name: String,
    val code: String,
    val type: String?,
    val subType: String?,
    val companyName: String?,
    /**
     * Where this production's files live: `BOX`, or AWS when absent.
     *
     * Decided per production, so an upload cannot be routed until the project
     * is open.
     */
    val storageType: String? = null,
    /** Box's tenant, needed to mint an upload token. */
    val enterpriseClientId: String? = null,
    /** Box folder ids by name — `chat`, `profile_pictures` and so on. */
    val storageFolders: Map<String, String> = emptyMap(),
)

data class UserSnapshot(
    val userId: String,
    val fullName: String,
    val email: String?,
    val department: String?,
    val designation: String?,
    val avatarUrl: String?,
    val isAdmin: Boolean,
    /** Their primary device id — the address a 1:1 call rings. */
    val deviceId: String? = null,
    /**
     * This crew member asked not to have their name shown.
     *
     * Chosen by them at registration, so it is honoured wherever their name
     * would otherwise be put in front of the rest of the unit.
     */
    val keepNamePrivate: Boolean = false,
)

data class ToolSnapshot(
    val identifier: String,
    val groupIdentifier: String?,
    val unitId: String?,
    val unitName: String?,
    val enabled: Boolean,
    val canView: Boolean,
    val canPost: Boolean,
    val canDownload: Boolean,
    val isTool: Boolean,
    val onHome: Boolean,
)

data class NoticeSnapshot(
    val noticeId: String,
    val unitId: String,
    val body: String,
    val authorName: String,
    val authorId: String?,
    val createdAt: Long,
    val isEdited: Boolean,
    val isPinned: Boolean,
    val attachmentCount: Int,
    val commentCount: Int,
)

/**
 * Reads and writes the production cache.
 *
 * Every method is scoped by production, and [clearProject] is what a project
 * switch calls — cached rights and notices from one production must never be
 * visible inside another.
 */
class ProjectCache(database: ZillitDatabase, private val nowMillis: () -> Long) {

    private val queries = database.projectCacheQueries

    // -- profile -----------------------------------------------------------

    fun saveProfile(projectId: String, profile: ProfileSnapshot) {
        queries.upsertProfile(
            userId = profile.userId,
            projectId = projectId,
            fullName = profile.fullName,
            firstName = profile.firstName,
            lastName = profile.lastName,
            departmentId = profile.departmentId,
            departmentName = profile.departmentName,
            designationId = profile.designationId,
            designationName = profile.designationName,
            keepNamePrivate = profile.keepNamePrivate.toDb(),
            email = profile.email,
            phone = profile.phone,
            avatarUrl = profile.avatarUrl,
            isAdmin = profile.isAdmin.toDb(),
            joinUnitId = profile.joinUnitId,
            joinUnitName = profile.joinUnitName,
            defaultUnitId = profile.defaultUnitId,
            cachedAt = nowMillis(),
        )
    }

    fun profile(projectId: String): ProfileSnapshot? =
        queries.selectProfile(projectId).executeAsOneOrNull()?.let {
            ProfileSnapshot(
                userId = it.userId,
                fullName = it.fullName,
                firstName = it.firstName,
                lastName = it.lastName,
                departmentId = it.departmentId,
                departmentName = it.departmentName,
                designationId = it.designationId,
                designationName = it.designationName,
                keepNamePrivate = it.keepNamePrivate.toBool(),
                email = it.email,
                phone = it.phone,
                avatarUrl = it.avatarUrl,
                isAdmin = it.isAdmin.toBool(),
                joinUnitId = it.joinUnitId,
                joinUnitName = it.joinUnitName,
                defaultUnitId = it.defaultUnitId,
            )
        }

    // -- project -----------------------------------------------------------

    fun saveProject(project: ProjectSnapshot) {
        queries.upsertProject(
            projectId = project.projectId,
            name = project.name,
            code = project.code,
            type = project.type,
            subType = project.subType,
            companyName = project.companyName,
            storageType = project.storageType,
            enterpriseClientId = project.enterpriseClientId,
            // `name=id;name=id`. Neither part can contain either separator:
            // ids are numeric and names come from a fixed server list.
            storageFolders = project.storageFolders.entries
                .joinToString(";") { (name, id) -> "$name=$id" },
            cachedAt = nowMillis(),
        )
    }

    fun project(projectId: String): ProjectSnapshot? =
        queries.selectProject(projectId).executeAsOneOrNull()?.let {
            ProjectSnapshot(
                projectId = it.projectId,
                name = it.name,
                code = it.code,
                type = it.type,
                subType = it.subType,
                companyName = it.companyName,
                storageType = it.storageType,
                enterpriseClientId = it.enterpriseClientId,
                storageFolders = it.storageFolders
                    .split(';')
                    .filter { entry -> entry.contains('=') }
                    .associate { entry -> entry.substringBefore('=') to entry.substringAfter('=') },
            )
        }

    // -- users -------------------------------------------------------------

    /** Replaces the whole list: a user removed from the production must disappear. */
    fun saveUsers(projectId: String, users: List<UserSnapshot>) {
        queries.transaction {
            queries.deleteProjectUsers(projectId)
            users.forEach { user ->
                queries.upsertProjectUser(
                    userId = user.userId,
                    projectId = projectId,
                    fullName = user.fullName,
                    email = user.email,
                    department = user.department,
                    designation = user.designation,
                    avatarUrl = user.avatarUrl,
                    deviceId = user.deviceId,
                    isAdmin = user.isAdmin.toDb(),
                    keepNamePrivate = user.keepNamePrivate.toDb(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    fun users(projectId: String): List<UserSnapshot> =
        queries.selectProjectUsers(projectId).executeAsList().map {
            UserSnapshot(
                userId = it.userId,
                fullName = it.fullName,
                email = it.email,
                department = it.department,
                designation = it.designation,
                avatarUrl = it.avatarUrl,
                isAdmin = it.isAdmin.toBool(),
                deviceId = it.deviceId,
                keepNamePrivate = it.keepNamePrivate.toBool(),
            )
        }

    // -- tools -------------------------------------------------------------

    /**
     * Replaces the tool list, preserving server order in [ToolSnapshot.identifier]
     * order of arrival.
     *
     * A wholesale replace, not a merge: a right that was **revoked** disappears
     * from the response, and merging would leave the user holding it.
     */
    fun saveTools(projectId: String, tools: List<ToolSnapshot>) {
        queries.transaction {
            queries.deleteTools(projectId)
            tools.forEachIndexed { index, tool ->
                queries.upsertTool(
                    identifier = tool.identifier,
                    projectId = projectId,
                    groupIdentifier = tool.groupIdentifier,
                    unitId = tool.unitId,
                    unitName = tool.unitName,
                    enabled = tool.enabled.toDb(),
                    canView = tool.canView.toDb(),
                    canPost = tool.canPost.toDb(),
                    canDownload = tool.canDownload.toDb(),
                    isTool = tool.isTool.toDb(),
                    onHome = tool.onHome.toDb(),
                    ordinal = index.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    fun tools(projectId: String): List<ToolSnapshot> =
        queries.selectTools(projectId).executeAsList().map {
            ToolSnapshot(
                identifier = it.identifier,
                groupIdentifier = it.groupIdentifier,
                unitId = it.unitId,
                unitName = it.unitName,
                enabled = it.enabled.toBool(),
                canView = it.canView.toBool(),
                canPost = it.canPost.toBool(),
                canDownload = it.canDownload.toBool(),
                isTool = it.isTool.toBool(),
                onHome = it.onHome.toBool(),
            )
        }

    // -- notices -----------------------------------------------------------

    fun saveNotices(projectId: String, unitId: String, notices: List<NoticeSnapshot>) {
        queries.transaction {
            // Replace the unit's page rather than the whole board: pagination
            // will add older pages, and a merge across pages would need cursors
            // this cache does not have yet.
            queries.deleteNoticesForUnit(projectId, unitId)
            notices.forEach { notice ->
                queries.upsertNotice(
                    noticeId = notice.noticeId,
                    projectId = projectId,
                    unitId = unitId,
                    body = notice.body,
                    authorName = notice.authorName,
                    authorId = notice.authorId,
                    createdAt = notice.createdAt,
                    isEdited = notice.isEdited.toDb(),
                    isPinned = notice.isPinned.toDb(),
                    attachmentCount = notice.attachmentCount.toLong(),
                    commentCount = notice.commentCount.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    fun notices(projectId: String, unitId: String): List<NoticeSnapshot> =
        queries.selectNotices(projectId, unitId).executeAsList().map {
            NoticeSnapshot(
                noticeId = it.noticeId,
                unitId = it.unitId,
                body = it.body,
                authorName = it.authorName,
                authorId = it.authorId,
                createdAt = it.createdAt,
                isEdited = it.isEdited.toBool(),
                isPinned = it.isPinned.toBool(),
                attachmentCount = it.attachmentCount.toInt(),
                commentCount = it.commentCount.toInt(),
            )
        }

    /**
     * Drops everything held for a production.
     *
     * Called on project switch and sign-out. Cached rights surviving a switch
     * would let one production's permissions answer questions asked inside
     * another — the worst failure this cache could produce.
     */
    fun clearProject(projectId: String) {
        queries.transaction {
            queries.deleteTools(projectId)
            queries.deleteProjectUsers(projectId)
            queries.deleteNoticeData(projectId)
            queries.deleteProfileData(projectId)
            queries.deleteProjectData(projectId)
        }
    }
}

private fun Boolean.toDb(): Long = if (this) 1 else 0
private fun Long.toBool(): Boolean = this != 0L
