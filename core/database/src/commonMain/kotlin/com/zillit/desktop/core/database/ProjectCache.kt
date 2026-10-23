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
    /**
     * The identifiers behind the two names (`department_accounts`,
     * `designation_financial_controller_accounts`) — what role checks
     * should compare. Not cached: null on an offline start, when readers
     * fall back to the names.
     */
    val departmentIdentifier: String? = null,
    val designationIdentifier: String? = null,
    /** Producers and main cast may withhold their name from the crew list. */
    val keepNamePrivate: Boolean = false,
    /**
     * Consent to show the Zillit mailbox address on the crew list
     * (`zillit_email_enable`); null = the server's default, ON.
     */
    val showMailboxInCrewList: Boolean? = null,
    /** The Zillit mailbox this user was given, when one exists (`mail_box_detail.email_address`). */
    val mailboxAddress: String? = null,
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
    /**
     * The production this one is a remote unit of, when it is one.
     *
     * Null on an ordinary production. A remote unit may not spawn further
     * units, which is the one thing Admin Settings reads this for — the same
     * `parent_project_name` check Android makes.
     */
    val parentName: String? = null,
    /**
     * `mark_deleted`: a deletion has been scheduled and is counting down.
     *
     * Not gone yet, and stoppable — the admin row says which of the two things
     * pressing it will do.
     */
    val markedForDeletion: Boolean = false,
    /**
     * `language_code`: the production's working language (`en`, `fr`…).
     * Null when the server sent none; readers fall back to English, as the
     * web's `getProjectLanguage` does.
     */
    val languageCode: String? = null,
    /**
     * The company's contact block — `company_address`, `company_phone`,
     * `company_email` — for documents the production issues (a sales
     * invoice's header). Not cached: null until the details are read.
     */
    val companyAddress: String? = null,
    val companyPhone: String? = null,
    val companyEmail: String? = null,
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
    /**
     * When they last used the app — `last_activity`, else `last_visited_on` —
     * the "Last Entry" line the web's C&C rows wear (`UserCard.jsx:357-362`).
     */
    val lastActiveMillis: Long? = null,
    /**
     * Their standing on the production — "accepted", "approved", "pending",
     * "left", "removed", "rejected". Null on older cached rows; treated as
     * present-and-active, never as a reason to hide someone.
     */
    val status: String? = null,
    /**
     * Their project mailbox (`mail_box_detail.email_address`) — where
     * production mail to them should go. Most members set no personal
     * [email], while every member carries a mailbox. Not cached: an offline
     * start falls back to [email] until the network answers.
     */
    val mailboxAddress: String? = null,
    /**
     * The department's stable identifier (`department_identifier`, such as
     * `department_accounts`) — what the web's `ACCOUNTS_TEAM_USERS` is filtered on,
     * where [department] is a display name that is translated. Not cached, like
     * [mailboxAddress]: offline, callers fall back to the name.
     */
    val departmentIdentifier: String? = null,
    /**
     * This crew member is on a deal — the production requires them to sign a
     * deal memo (`signing_required`).
     *
     * Payroll's Production Report board rosters the crew WITH A DEAL rather
     * than the crew with a timecard, which is what lets it estimate someone
     * who has not filled a card in yet. Not cached, like [mailboxAddress]: an
     * offline start simply has no roster until the crew list answers, which is
     * better than one built from a stale flag.
     */
    val signingRequired: Boolean = false,
    /**
     * When the signed-in user and this crew member last exchanged a message —
     * `sorting_activity` on the users list, per viewer. Android lists a DM
     * whenever this is above zero (`MembersVM.kt:215-217`), so it is what
     * proves a conversation the socket's `user:list` or this computer's cache
     * happens not to know. Not cached: offline, the kept listing stands in.
     */
    val sortingActivity: Long = 0L,
    /**
     * Where they last were, and the schedule that governs who may look —
     * `last_location` plus the `show_location` block on `project/users`.
     * Not cached, like [mailboxAddress]: a stale position is worse than
     * none, so offline this is null and the surfaces that read it say so.
     */
    val location: UserLocation? = null,
)

/**
 * A crew member's shared position and the window it may be read in.
 *
 * The same four wire fields the Settings card writes for the signed-in user
 * (`feature:settings`' `LocationSharing`), kept here as the server sends them
 * because this is the *other* side of that card: what one person scheduled is
 * what everyone else is allowed to see.
 *
 * [everydayStart]/[everydayEnd] and a day's stamps are whole epoch
 * milliseconds carrying the date they were saved on — the web writes
 * `dayjs().valueOf()`. Only their TIME OF DAY means anything; Android maps
 * each onto today before comparing (`Extensions.getTimeCalendar`), and so
 * does the reader here. Zero on both ends is the server's "never scheduled",
 * not midnight.
 */
data class UserLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** `show_location` — they have turned sharing on at all. */
    val shared: Boolean = false,
    /** `location_tracking_mode`: "Everyday" or "SelectDays". */
    val mode: String? = null,
    val everydayStart: Long = 0L,
    val everydayEnd: Long = 0L,
    /** `select_days`, each keyed by the server's `day_of_week` — 0 = Sunday. */
    val days: List<UserLocationDay> = emptyList(),
)

/** One scheduled day of [UserLocation.days]. */
data class UserLocationDay(
    val dayOfWeek: Int,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
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
            departmentIdentifier = profile.departmentIdentifier,
            designationIdentifier = profile.designationIdentifier,
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
                departmentIdentifier = it.departmentIdentifier,
                designationIdentifier = it.designationIdentifier,
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
            parentName = project.parentName,
            markedForDeletion = if (project.markedForDeletion) 1L else 0L,
            languageCode = project.languageCode,
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
                parentName = it.parentName,
                markedForDeletion = it.markedForDeletion != 0L,
                languageCode = it.languageCode,
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
            queries.deleteProfileData(projectId)
            queries.deleteProjectData(projectId)
        }
    }
}

private fun Boolean.toDb(): Long = if (this) 1 else 0
private fun Long.toBool(): Boolean = this != 0L
