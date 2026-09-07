package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.feature.settings.ui.ProductionFacts
import com.zillit.desktop.feature.settings.ui.SettingsDestination

/**
 * Every administration page, as a route.
 *
 * An enum rather than free-form strings so the window cannot navigate somewhere
 * that does not render, and so [availableTo] is the single place that decides
 * which pages a production has at all. The reference clients spread that
 * decision across a hub's row list, a route guard and each screen's own empty
 * state, and they have disagreed — the web hides its remote-unit tile on
 * corporate productions but the route still opens the page.
 *
 * [slug] hangs off `/settings/admin/`, matching the paths the other clients use
 * where they have one, so a deep link means the same thing on both.
 */
enum class AdminDestination(val slug: String, val title: String) {

    Departments("departments", "Create New Department"),
    JobTitles("job-titles", "Create New Designation"),
    CrewOrder("crew-order", "Change Department Listing Order"),
    Crew("crew", "User Management"),
    Rights("rights", "User Viewing & Posting Rights Grid"),
    PreApproved("pre-approved", "Pre-Approved Users"),
    ToolAvailability("tools", "Customization of tools"),
    ToolGroups("tool-groups", "Manage Tool Groups"),
    ProductionName("name", "Edit Project Name"),
    CompanyDetails("company", "Company Details"),
    Watermark("watermark", "Watermark Logo of Company"),
    Sos("sos", "Set/View SOS Receivers"),
    HomeUnits("home-units", "Create/Update Home Units"),
    RemoteUnits("remote-units", "Create Remote Shooting Units"),
    ShootingUnits("shooting-units", "Create Additional Shooting Unit"),
    DeleteProduction("delete", "Delete Project"),
    ;

    /**
     * Whether this production has this page at all.
     *
     * Absent, not disabled — the rule all three clients share. A corporate or
     * event production runs no shooting units, and a picker offering a unit
     * that cannot exist is worse than no picker.
     *
     * Note this is about the *production*, not the reader: whether someone may
     * be here at all is decided once, by the admin flag, before any of these
     * are offered.
     */
    fun availableTo(production: ProductionFacts): Boolean = when (this) {
        // "Other" productions — corporate, events — run no second unit and no
        // splinter, so the two shooting-unit pages go. Nor can a remote unit
        // spawn units of its own, which is the check Android makes on
        // `parent_project_name`. The dashboard sections stay: every production
        // has a home screen, whatever it is shooting.
        ShootingUnits, RemoteUnits ->
            !production.isOtherType && !production.isPersonal && !production.isRemoteUnit

        // A personal production has no crew, which takes most of the page with
        // it: nobody to approve, nobody to rank, nobody to grant rights to.
        Crew, CrewOrder, Rights, PreApproved, Sos -> !production.isPersonal

        else -> true
    }

    companion object {
        fun fromSlug(slug: String?): AdminDestination? =
            entries.firstOrNull { it.slug == slug }

        /**
         * Where a settings row leads.
         *
         * The listing is plain data — see [SettingsDestination] — so this is
         * the one place that turns a row into a page, and a destination with no
         * page yet answers null and is offered as "Soon" rather than as a row
         * that does nothing.
         */
        @Suppress("CyclomaticComplexMethod") // A lookup table; branching is the content.
        fun of(destination: SettingsDestination): AdminDestination? = when (destination) {
            SettingsDestination.Departments -> Departments
            SettingsDestination.JobTitles -> JobTitles
            SettingsDestination.CrewListOrder -> CrewOrder
            SettingsDestination.CrewAndAdmins -> Crew
            SettingsDestination.PermissionGrid -> Rights
            SettingsDestination.PreApprovedCrew -> PreApproved
            SettingsDestination.ToolAvailability -> ToolAvailability
            SettingsDestination.ToolGroups -> ToolGroups
            SettingsDestination.ProductionName -> ProductionName
            SettingsDestination.CompanyDetails -> CompanyDetails
            SettingsDestination.Watermark -> Watermark
            SettingsDestination.SosRecipients -> Sos
            // The two are not what their names suggest — see UnitKind.
            SettingsDestination.ShootingUnits -> HomeUnits
            SettingsDestination.RemoteUnit -> RemoteUnits
            SettingsDestination.JoinedUnits -> ShootingUnits
            SettingsDestination.DeleteProduction -> DeleteProduction

            // Handled elsewhere: the approval queues have their own screens,
            // the documentation rows open a browser, and the rest are the
            // reader's own settings rather than the production's.
            SettingsDestination.ApproveNewCrew,
            SettingsDestination.ApproveProfileChanges,
            SettingsDestination.Help,
            SettingsDestination.SetupNotes,
            SettingsDestination.ProductionSetup,
            SettingsDestination.EditProfile,
            SettingsDestination.RecoveryEmail,
            SettingsDestination.LinkedDevices,
            SettingsDestination.InviteCrew,
            SettingsDestination.LeaveProduction,
            -> null
        }
    }
}
