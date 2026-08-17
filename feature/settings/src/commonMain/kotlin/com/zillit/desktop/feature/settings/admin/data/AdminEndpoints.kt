package com.zillit.desktop.feature.settings.admin.data

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService

/**
 * Where each administration route lives.
 *
 * Gathered here rather than inlined at the call sites because the interesting
 * fact about this surface is not any one path — it is that **three services**
 * serve what looks like one settings page, and which one is not guessable from
 * the path:
 *
 * | Service | Routes |
 * |---|---|
 * | Core (project) | departments, crew, tools, the production record, deletion, rights |
 * | Units | shooting units and join units |
 * | Core (SOS prefix) | SOS recipients |
 *
 * Remote units are the trap: `project/remote-unit` is on **core**, while the
 * other two unit kinds are on the units service. Putting all three together
 * because the pages sit next to each other is a 404 on a path that exists.
 */
internal class AdminEndpoints(private val config: AppConfig) {

    private val core get() = config.apiV2(ZillitService.Core)
    private val units get() = config.apiV2(ZillitService.Units)

    // -- departments --------------------------------------------------------

    /**
     * `GET` list · `POST` create.
     *
     * `designations=true` nests the job titles, which is what makes the job
     * titles page a single call instead of one per department. Both other
     * clients ask for it on the create call too, so the response carries the
     * refreshed tree — this client reloads instead, but the query is kept so
     * the request is byte-identical to theirs.
     */
    val departments get() = "${core}departments?designations=true"

    fun department(departmentId: String) = "${core}departments/$departmentId"

    /** `POST` — a job title inside one department. */
    fun jobTitles(departmentId: String) = "${core}departments/$departmentId/designation"

    /** `DELETE` — note the id pair sits in the path with no `designation` segment. */
    fun jobTitle(departmentId: String, jobTitleId: String) =
        "${core}departments/$departmentId/$jobTitleId"

    /** `PUT` — the whole crew-list order at once. */
    val reorderDepartments get() = "${core}departments/reorder-departments"

    // -- crew ---------------------------------------------------------------

    /** `GET` — everyone on the production, whatever their status. */
    val crew get() = "${core}project/users"

    /** `POST` — grant or revoke administering this production. */
    val adminAccess get() = "${core}user/admin-access"

    /** `POST` — take someone off the production, or put them back. */
    val crewStatus get() = "${core}user/status"

    val preApproved get() = "${core}user/pre-approved"

    val preApprovedList get() = "${core}project/pre-approved/users"

    // -- tools ---------------------------------------------------------------

    /** `GET` — the tools this production may switch on and off. */
    val adminTools get() = "${core}project/tools/admin"

    /**
     * `GET` — as [adminTools], plus the tools nobody may switch off.
     *
     * The grouping page needs them: an always-on tool still belongs to a group
     * and an admin may want it somewhere else.
     */
    val allTools get() = "${core}project/tools/admin/all"

    /** `PUT` — the complete on/off list. */
    val enableTools get() = "${core}project/enable-tools"

    /** `PUT` — moves one tool between groups. */
    val toolGroup get() = "${core}project/tools/group"

    val toolGroups get() = "${core}project/tools/groups"

    fun toolGroupById(toolGroupId: String) = "${core}project/tools/groups/$toolGroupId"

    // -- the production ---------------------------------------------------------

    /**
     * `PATCH` — the production record.
     *
     * One route for the name, the company block and the watermark. Absent keys
     * are left alone, which is what makes a three-field form safe to send.
     */
    val project get() = "${core}project"

    fun projectById(projectId: String) = "${core}project/$projectId"

    val watermark get() = "${core}project/watermark"

    val companyLogo get() = "${core}project/company-logo"

    /** `DELETE` schedules · `PATCH` calls it off. */
    val efface get() = "${core}project/efface"

    // -- SOS ---------------------------------------------------------------------

    /**
     * `GET` — the production's SOS recipients.
     *
     * `entry_type` is **whose** list this is, not what kind the rows are:
     * `admin` returns the production's recipients, `user` returns the caller's
     * own. The kind of each row — crew or outsider — arrives as `type` on the
     * row itself. The two fields read alike and mean different things.
     */
    fun sosContacts(entryType: String) = "${core}sos/contacts?entry_type=$entryType"

    val createSosContact get() = "${core}sos/contact/create"

    fun sosContact(recipientId: String) = "${core}sos/contact/$recipientId"

    // -- units ---------------------------------------------------------------------

    /** `GET` admin listing · `POST` create — shooting units, on the units service. */
    val shootingUnitsAdmin get() = "${units}home/unit/admin"

    val shootingUnits get() = "${units}home/unit"

    fun shootingUnit(unitId: String) = "${units}home/unit/$unitId"

    /** `PUT` — the shooting unit's own on/off switch. */
    fun shootingUnitVisibility(unitId: String) = "${units}home/unit/visibility/$unitId"

    /**
     * `GET` — the join units a crew member attaches to.
     *
     * The read has no `/remote` segment and the writes all do. Not a
     * transcription slip: every client does exactly this, and adding `/remote`
     * to the read 404s.
     */
    val joinedUnitsList get() = "${units}join/unit"

    /** `POST` create. */
    val joinedUnits get() = "${units}join/unit/remote"

    /** `PUT` rename · `DELETE`. */
    fun joinedUnit(unitId: String) = "${units}join/unit/remote/$unitId"

    /**
     * `GET` list · `POST` create.
     *
     * On **core**, not the units service, unlike the other two kinds.
     */
    val remoteUnits get() = "${core}project/remote-unit"

    // -- rights grid -----------------------------------------------------------------

    /**
     * `GET` — one person's rights over every tool, as a flat list.
     *
     * Not the web's `permissions/crewlist/tools/access`, which answers a whole
     * spreadsheet. Both phone clients read this one instead, and so does this
     * client — see `ToolRights` for why.
     */
    fun userAccess(userId: String) = "${core}user/access/$userId"

    /**
     * `POST` — grants or revokes one right.
     *
     * `section` is which half of the page the row came from (`home`, `tools`);
     * a tool that appears in both has separate rights in each, written here
     * separately.
     */
    fun writeAccess(section: String) = "${core}permissions/users/$section/access"
}
