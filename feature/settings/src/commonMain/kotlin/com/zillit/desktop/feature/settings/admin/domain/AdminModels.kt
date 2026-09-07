package com.zillit.desktop.feature.settings.admin.domain

/**
 * Everything the administration pages read and write.
 *
 * One file rather than one per page: these are wire rows with almost no
 * behaviour, and the thing worth seeing at a glance is how few of them there
 * actually are behind twenty destinations.
 *
 * ## `systemDefined` is the recurring rule
 *
 * Departments, job titles and tool groups all ship with a set the production
 * did not create and cannot delete. All three clients express that the same
 * way — a `system_defined` flag on the row, a trash icon that is simply absent
 * when it is true. Modelled here rather than re-derived per page, because
 * getting it wrong means offering a delete the server will refuse.
 */

/** A department on this production. Crew choose one when they join. */
data class Department(
    val id: String,
    /**
     * Usually a translation key (`transportation_department_label`).
     *
     * Left as it arrived; [com.zillit.desktop.core.localization.localised]
     * resolves it at the point of display, so search and sort still work on
     * what the reader sees.
     */
    val name: String,
    val systemDefined: Boolean = false,
    /**
     * Where it sits in the crew list.
     *
     * A string on the wire, and sometimes `"_all_"`. Kept verbatim; the
     * ordering page works on list position rather than this value.
     */
    val priority: String? = null,
    val jobTitles: List<JobTitle> = emptyList(),
) {
    val isDeletable: Boolean get() = !systemDefined
}

/** A role inside a department — "designation" on the wire. */
data class JobTitle(
    val id: String,
    val name: String,
    val systemDefined: Boolean = false,
) {
    val isDeletable: Boolean get() = !systemDefined
}

/**
 * Someone on the crew, as the administration pages see them.
 *
 * Richer than the session's `UserSnapshot`: this page needs [status] and
 * [deviceId] to enable and disable people, and neither is on the cached crew
 * list. Fetched rather than read from the session for that reason.
 */
data class CrewMember(
    val userId: String,
    val fullName: String,
    val email: String? = null,
    val phone: String? = null,
    val department: String? = null,
    val designation: String? = null,
    val isAdmin: Boolean = false,
    /**
     * Needed to change someone's status.
     *
     * `POST user/status` is keyed on the person *and* the device they joined
     * with — the same shape the join queue uses. Absent for crew added by an
     * admin rather than by a join request, and the action is withheld then
     * rather than sent without it.
     */
    val deviceId: String? = null,
    val status: CrewStatus = CrewStatus.Accepted,
    val keepNamePrivate: Boolean = false,
) {
    val isActive: Boolean get() = status == CrewStatus.Accepted

    /** Both switches are refused on someone already off the production. */
    val isActionable: Boolean get() = isActive && deviceId != null

    fun matches(query: String): Boolean {
        val needle = query.trim()
        return needle.isEmpty() ||
            fullName.contains(needle, ignoreCase = true) ||
            email.orEmpty().contains(needle, ignoreCase = true) ||
            designation.orEmpty().contains(needle, ignoreCase = true) ||
            department.orEmpty().contains(needle, ignoreCase = true)
    }
}

/**
 * Where a person stands on the production.
 *
 * [Unknown] rather than a decode failure: this is a server-side vocabulary that
 * grows, and a crew list that fails to load because one row says `invited` is
 * worse than one that shows the row with an unfamiliar label.
 */
enum class CrewStatus(val wire: String) {
    Accepted("accepted"),
    Removed("removed"),
    Pending("pending"),
    Unknown(""),
    ;

    companion object {
        fun from(wire: String?): CrewStatus =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Unknown
    }
}

/**
 * Someone let straight in when they use the production code.
 *
 * Read-only here. The other clients offer no edit either — a pre-approval is
 * either waiting or it has been used, and changing one in place would mean
 * changing a placement the person may already be holding.
 */
data class PreApprovedCrew(
    val id: String,
    val fullName: String,
    val email: String? = null,
    val phone: String? = null,
    val departmentName: String? = null,
    val designationName: String? = null,
    val unitName: String? = null,
) {
    fun matches(query: String): Boolean {
        val needle = query.trim()
        return needle.isEmpty() ||
            fullName.contains(needle, ignoreCase = true) ||
            email.orEmpty().contains(needle, ignoreCase = true) ||
            designationName.orEmpty().contains(needle, ignoreCase = true)
    }
}

/** What a new pre-approval carries. Contact details are optional. */
data class NewPreApproval(
    val firstName: String,
    val lastName: String,
    val departmentId: String,
    val designationId: String,
    val unitId: String? = null,
    val email: String? = null,
    val countryCode: String? = null,
    val phone: String? = null,
    val keepNamePrivate: Boolean = false,
)

/**
 * One tool, as the two tools pages see it.
 *
 * The same row serves both: "Tools on this project" reads [enabled] and
 * writes it back, "Tool groups" reads [groupIdentifier] and moves it. One
 * fetch, two pages — the server offers the whole list either way.
 */
data class ProductionTool(
    val identifier: String,
    val name: String,
    val enabled: Boolean = true,
    /** Empty when the tool sits outside every group. */
    val groupIdentifier: String = "",
    /**
     * Tools the production may not switch off.
     *
     * The permission grid and the info tool are how an admin fixes a mistake
     * made on this very page, so both phone clients refuse to disable them.
     */
    val isLocked: Boolean = false,
) {
    val isGrouped: Boolean get() = groupIdentifier.isNotBlank()
}

/** A heading on the Film Tools grid. Six ship with the product; admins add more. */
data class ToolGroup(
    val id: String,
    val identifier: String,
    val name: String,
    val systemDefined: Boolean = false,
    val order: Int = 0,
) {
    /**
     * Renaming is allowed on every group; deleting only on custom ones.
     *
     * The server enforces both, and refuses a delete on a group that still has
     * tools in it (`tool_group_in_use`). The page checks that itself first so
     * the reader is told to move the tools rather than shown a server error.
     */
    val isDeletable: Boolean get() = !systemDefined
}

/**
 * Who is alerted when someone raises an SOS.
 *
 * Two kinds, and the difference is not cosmetic: a [CrewRecipient] is someone
 * on the production and is named by their user id, an outsider is a phone
 * number the production types in — a unit nurse, a local fixer, a hospital.
 */
data class SosRecipient(
    val id: String,
    val name: String,
    val entryType: SosEntryType,
    /** Set for [SosEntryType.Crew]; the row nests the person under `user`. */
    val userId: String? = null,
    val phone: String? = null,
    val countryCode: String? = null,
    /**
     * How an outsider is related to the production — "unit nurse", "hospital".
     *
     * A translation key on the wire, like the department and role names.
     */
    val relationship: String? = null,
    val designation: String? = null,
) {
    val dialled: String?
        get() = listOfNotNull(countryCode?.takeIf { it.isNotBlank() }, phone?.takeIf { it.isNotBlank() })
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
}

/**
 * Which kind of recipient a row is.
 *
 * The wire words are the row's own `type`, and they are **not** what the list
 * endpoint's `entry_type` query takes — that one is `admin` or `user`, and
 * decides whose recipients are being asked for rather than what kind they are.
 * Two similarly named fields meaning different things is exactly why this note
 * is here; see [com.zillit.desktop.feature.settings.admin.data.AdminEndpoints].
 */
enum class SosEntryType(val wire: String) {
    /** Someone on the crew. Named by user id; the server fills in the rest. */
    Crew("internal"),

    /** A number outside the production — a nurse, a fixer, a hospital. */
    Outsider("external"),
    ;

    companion object {
        fun from(wire: String?): SosEntryType =
            if (wire.equals(Outsider.wire, ignoreCase = true)) Outsider else Crew
    }
}

/**
 * What a new SOS recipient carries.
 *
 * The two kinds send genuinely different bodies — a crew recipient is one field
 * (`user_id`) and the server resolves the name and number from the crew list;
 * an outsider is four, because there is nowhere else to get them from.
 */
data class NewSosRecipient(
    val entryType: SosEntryType,
    /** The crew member, for [SosEntryType.Crew]. */
    val userId: String? = null,
    /** The typed-in name, for [SosEntryType.Outsider]. */
    val name: String = "",
    val countryCode: String = "",
    val phone: String = "",
    val relationship: String = "",
) {
    /**
     * Whether this is worth sending.
     *
     * Checked here rather than on the form so the same rule covers both the
     * button's enabled state and the submit path — a form that enables its
     * button on one rule and validates on another is a form that can be
     * submitted into a rejection.
     */
    val isComplete: Boolean
        get() = when (entryType) {
            SosEntryType.Crew -> !userId.isNullOrBlank()
            SosEntryType.Outsider ->
                name.isNotBlank() &&
                    relationship.isNotBlank() &&
                    countryCode.isNotBlank() &&
                    phone.trim().length in PHONE_LENGTH
        }

    private companion object {
        /** The range the phone clients enforce before the server sees it. */
        val PHONE_LENGTH = 5..20
    }
}

/**
 * The company block printed at the head of the crew list.
 *
 * Every field is optional and every field is sent — the server treats an absent
 * key as "leave it alone" and an empty string as "clear it", which is the
 * difference between saving a form with one field blanked and silently keeping
 * the old value.
 *
 * The logo is deliberately not here. It saves on its own call at upload time
 * and clears through a dedicated DELETE, because `company_logo: ""` is refused
 * with a 406 — see [AdminRepository.clearCompanyLogo].
 */
data class CompanyDetails(
    val name: String = "",
    val address: String = "",
    val registeredAddress: String = "",
    val countryCode: String = "",
    val phone: String = "",
    val email: String = "",
    val companyNumber: String = "",
    val customFields: List<CompanyField> = emptyList(),
    /** Read-only here; shown so an admin knows whether there is one to clear. */
    val logoUrl: String? = null,
) {
    val hasLogo: Boolean get() = !logoUrl.isNullOrBlank()
}

/** One extra line on the crew list header — "Insurance", "VAT", whatever. */
data class CompanyField(val label: String, val value: String)

/**
 * A production unit, for the three pages that manage them.
 *
 * The three are separate endpoints on two services and the phone clients give
 * each its own screen, but the row is the same everywhere: a name, sometimes a
 * switch, and whether it may be removed.
 */
data class AdminUnit(
    val id: String,
    val name: String,
    val kind: UnitKind,
    val enabled: Boolean = true,
    /**
     * Some units are the production's own and cannot be removed or renamed.
     *
     * iOS answers a delete on one with "cannot be deleted"; this withholds the
     * button instead.
     */
    val locked: Boolean = false,
) {
    val isRemovable: Boolean get() = !locked
}

enum class UnitKind {
    /**
     * The sections of the production's dashboard — `home/unit`.
     *
     * "Home unit" is the server's word and it is not a shooting unit: these are
     * Bulletin, Calendar, Call Sheet and the like. Reading the route name as
     * "the project's units" is the mistake this comment exists to stop; the
     * page called them shooting units until someone opened it and found the
     * dashboard.
     */
    Home,

    /** A unit shooting away from the production — `project/remote-unit`. */
    Remote,

    /**
     * The shooting units crew attach themselves to — `join/unit`.
     *
     * Main unit, second unit, splinter. Called "join units" on the wire because
     * joining is when a crew member picks one; every client labels them
     * shooting units in the interface.
     */
    Shooting,
}

/**
 * Whether this production is scheduled for deletion, and when.
 *
 * Deletion is not immediate on any client: an admin picks a delay, and the
 * production stays visible with a way to call it off until the clock runs out.
 */
data class DeletionSchedule(
    val isScheduled: Boolean = false,
    val hours: Int? = null,
) {
    companion object {
        /** What the confirmation offers, matching the phone clients' alert. */
        val OFFERED_HOURS = listOf(12, 24, 48)
    }
}

/**
 * Which half of the rights page a tool belongs to.
 *
 * The same person has separate rights over the dashboard and over the film
 * tools, and the write route carries the section in its path — so a row cannot
 * be saved without knowing which one it came from.
 */
enum class RightsSection(val wire: String, val label: String) {
    Home("home", "Dashboard"),
    Tools("tools", "Film tools"),
}

/**
 * What one person may do with one tool.
 *
 * Three independent rights, and independent is the point: a driver views the
 * call sheet without posting to it, an accountant downloads a report they
 * cannot edit.
 *
 * ## Why this is per person rather than a grid
 *
 * The web renders every crew member against every tool as one wide table, read
 * from a route that answers `{ headers, rows }` with the rows as bare arrays
 * and the columns as whatever tools the production runs. Both phone clients ask
 * a different route — `user/access/{userId}` — for one person at a time, and
 * get a plainly typed list back.
 *
 * This follows the phones. A grid of forty crew against thirty tools is 3,600
 * checkboxes that nobody can read across anyway, and the array-pair shape puts
 * every right on the wrong tool the first time headers and cells disagree in
 * length. Picking a person and seeing their rights is the same task with a
 * shape that cannot silently misalign.
 */
data class ToolRights(
    val toolIdentifier: String,
    val toolName: String,
    val unitId: String? = null,
    val section: RightsSection = RightsSection.Tools,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    /**
     * Rights the server says are not this admin's to change.
     *
     * Some tools derive their access from another one — a department budget
     * follows the main budget — and the server refuses a direct write. Shown
     * as a disabled box rather than one that silently springs back.
     */
    val viewLocked: Boolean = false,
    val postLocked: Boolean = false,
    val downloadLocked: Boolean = false,
) {
    fun granted(access: AccessType): Boolean = when (access) {
        AccessType.View -> canView
        AccessType.Post -> canPost
        AccessType.Download -> canDownload
    }

    fun locked(access: AccessType): Boolean = when (access) {
        AccessType.View -> viewLocked
        AccessType.Post -> postLocked
        AccessType.Download -> downloadLocked
    }

    fun with(access: AccessType, on: Boolean): ToolRights = when (access) {
        AccessType.View -> copy(canView = on)
        AccessType.Post -> copy(canPost = on)
        AccessType.Download -> copy(canDownload = on)
    }
}

/** The three rights a grid cell can grant. `access_type` on the wire. */
enum class AccessType(val wire: String, val label: String) {
    View("view", "View"),
    Post("post", "Post"),
    Download("download", "Download"),
}

/**
 * A change to one right, as the server takes it.
 *
 * Carried as a value rather than five loose parameters because the page applies
 * it to its own state *and* sends it, and the two must not be able to disagree
 * about which right moved. [cascadeFrom] builds the follow-up changes the
 * server does not make for you.
 */
data class RightsChange(
    val userId: String,
    val unitId: String,
    val section: RightsSection,
    val access: AccessType,
    val enable: Boolean,
)

/**
 * What one click really has to send.
 *
 * The three rights are not independent in one direction: **downloading implies
 * viewing**, and taking viewing away has to take posting and downloading with
 * it. The server does not do this — Android chains the calls by hand from each
 * success handler, and a failure part-way leaves the production with a right
 * nobody asked for.
 *
 * Computed up front instead, as an ordered list, so the whole consequence of a
 * click is one value the page can send, retry, and reason about. The order is
 * the safe one: rights are **removed before** the one they depend on, and
 * **added after** it, so an interrupted run never leaves posting rights on a
 * tool the person cannot see.
 */
fun RightsChange.cascadeFrom(current: ToolRights): List<RightsChange> = when {
    access == AccessType.View && !enable -> listOfNotNull(
        // Strip the dependants first: if this run stops after one call, the
        // person has lost a right rather than kept an orphaned one.
        takeIf { current.canDownload }?.copy(access = AccessType.Download, enable = false),
        takeIf { current.canPost }?.copy(access = AccessType.Post, enable = false),
        this,
    )

    // Downloading something you cannot see is not a state the server keeps, so
    // granting it grants viewing too rather than silently doing nothing.
    access == AccessType.Download && enable && !current.canView ->
        listOf(copy(access = AccessType.View, enable = true), this)

    access == AccessType.Post && enable && !current.canView ->
        listOf(copy(access = AccessType.View, enable = true), this)

    else -> listOf(this)
}
