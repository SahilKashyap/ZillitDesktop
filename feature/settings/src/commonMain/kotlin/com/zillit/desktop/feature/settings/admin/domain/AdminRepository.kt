package com.zillit.desktop.feature.settings.admin.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Everything the administration pages ask the server for.
 *
 * ## One interface for sixteen pages
 *
 * Split by page it would be sixteen interfaces, thirteen of which have two
 * methods, all sixteen constructed from the same host and header set and all
 * sixteen handed to the same view model. The seam that actually earns its
 * keep is this one — between "the administration pages" and "the network" —
 * and it is what the tests replace.
 *
 * Every method returns [ZillitResult]; nothing here throws. Mutations answer
 * `Unit` rather than the updated row, because the server's answer to a mutation
 * is inconsistent across these routes — some echo the record, some send `{}` —
 * and the view model reloads the list afterwards regardless.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see above.
interface AdminRepository {

    // -- departments and job titles ----------------------------------------

    /** Departments with their job titles nested — one call, not one per row. */
    suspend fun departments(): ZillitResult<List<Department>>

    suspend fun createDepartment(name: String): ZillitResult<Unit>

    suspend fun deleteDepartment(departmentId: String): ZillitResult<Unit>

    /**
     * Saves the crew list's department order.
     *
     * Takes the whole order rather than a move, because that is what the server
     * takes: position is derived from list index, so sending one department's
     * new place would leave every other one where it was.
     */
    suspend fun reorderDepartments(departmentIds: List<String>): ZillitResult<Unit>

    suspend fun createJobTitle(departmentId: String, name: String): ZillitResult<Unit>

    suspend fun deleteJobTitle(departmentId: String, jobTitleId: String): ZillitResult<Unit>

    // -- crew ---------------------------------------------------------------

    /** Everyone on the production, including people who have been removed. */
    suspend fun crew(): ZillitResult<List<CrewMember>>

    /** Grants or revokes the right to administer this production. */
    suspend fun setAdminAccess(userId: String, isAdmin: Boolean): ZillitResult<Unit>

    /**
     * Takes someone off the production, or puts them back.
     *
     * Keyed on the device as well as the person — the server's shape, matching
     * the join queue. A crew member with no device id cannot be moved this way
     * and the page withholds the switch rather than sending a partial body.
     */
    suspend fun setCrewStatus(
        userId: String,
        deviceId: String,
        status: CrewStatus,
    ): ZillitResult<Unit>

    // -- pre-approved crew ---------------------------------------------------

    suspend fun preApproved(): ZillitResult<List<PreApprovedCrew>>

    suspend fun addPreApproved(request: NewPreApproval): ZillitResult<Unit>

    // -- tools ---------------------------------------------------------------

    /**
     * Every tool the production could run.
     *
     * [includeAlwaysOn] asks the admin-only route that keeps the tools nobody
     * may switch off. The grouping page wants them — an always-on tool still
     * sits in a group and can be moved — and the availability page does not,
     * because a checkbox that refuses to clear reads as broken.
     */
    suspend fun tools(includeAlwaysOn: Boolean = false): ZillitResult<List<ProductionTool>>

    /**
     * Switches tools on and off for the whole production.
     *
     * Sends the complete list, not a delta: the server replaces what it has, so
     * an omitted tool is a tool switched off.
     */
    suspend fun setToolsEnabled(tools: List<ProductionTool>): ZillitResult<Unit>

    suspend fun toolGroups(): ZillitResult<List<ToolGroup>>

    suspend fun createToolGroup(name: String): ZillitResult<Unit>

    suspend fun renameToolGroup(toolGroupId: String, name: String): ZillitResult<Unit>

    suspend fun deleteToolGroup(toolGroupId: String): ZillitResult<Unit>

    /** Moves a tool between groups. A blank [groupIdentifier] ungroups it. */
    suspend fun moveTool(identifier: String, groupIdentifier: String): ZillitResult<Unit>

    // -- the production itself ------------------------------------------------

    suspend fun renameProduction(name: String): ZillitResult<Unit>

    suspend fun companyDetails(): ZillitResult<CompanyDetails>

    suspend fun saveCompanyDetails(details: CompanyDetails): ZillitResult<Unit>

    /**
     * Clears the company logo.
     *
     * Its own route because `company_logo: ""` on the details PATCH is refused
     * with a 406 — the field is an attachment object and the empty string is
     * not one.
     */
    suspend fun clearCompanyLogo(): ZillitResult<Unit>

    /** The watermark stamped on documents, or null when the production has none. */
    suspend fun watermarkUrl(): ZillitResult<String?>

    suspend fun clearWatermark(): ZillitResult<Unit>

    // -- SOS -------------------------------------------------------------------

    suspend fun sosRecipients(): ZillitResult<List<SosRecipient>>

    suspend fun addSosRecipient(request: NewSosRecipient): ZillitResult<Unit>

    suspend fun removeSosRecipient(recipientId: String): ZillitResult<Unit>

    // -- units -------------------------------------------------------------------

    suspend fun units(kind: UnitKind): ZillitResult<List<AdminUnit>>

    suspend fun createUnit(kind: UnitKind, name: String): ZillitResult<Unit>

    suspend fun renameUnit(kind: UnitKind, unitId: String, name: String): ZillitResult<Unit>

    suspend fun deleteUnit(kind: UnitKind, unitId: String): ZillitResult<Unit>

    /** Shooting units only; the other two kinds have no switch. */
    suspend fun setUnitEnabled(unitId: String, enabled: Boolean): ZillitResult<Unit>

    // -- rights -----------------------------------------------------------------

    /**
     * What one person may see, post to and download, across every tool.
     *
     * Per person rather than the whole production at once — see [ToolRights]
     * for why this follows the phone clients rather than the web's grid.
     */
    suspend fun rights(userId: String): ZillitResult<List<ToolRights>>

    /**
     * Applies one right.
     *
     * One call per right. A single click can be more than one of these — see
     * [cascadeFrom] — and the caller sends them in the order it gives.
     */
    suspend fun changeRights(change: RightsChange): ZillitResult<Unit>

    // -- deletion ---------------------------------------------------------------

    /**
     * Schedules this production's deletion.
     *
     * Not immediate on any client, and this one keeps that: the production
     * stays until the delay elapses, and [cancelDeletion] calls it off.
     */
    suspend fun scheduleDeletion(hours: Int): ZillitResult<Unit>

    suspend fun cancelDeletion(): ZillitResult<Unit>
}
