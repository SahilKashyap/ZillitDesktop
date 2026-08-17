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

    Departments("departments", "Departments"),
    JobTitles("job-titles", "Job titles"),
    CrewOrder("crew-order", "Crew list order"),
    Crew("crew", "Crew and admins"),
    Rights("rights", "Permission grid"),
    PreApproved("pre-approved", "Pre-approved crew"),
    ToolAvailability("tools", "Tools on this production"),
    ToolGroups("tool-groups", "Tool groups"),
    ProductionName("name", "Production name"),
    CompanyDetails("company", "Company details"),
    Watermark("watermark", "Watermark"),
    Sos("sos", "SOS recipients"),
    HomeUnits("home-units", "Home units"),
    RemoteUnits("remote-units", "Remote unit"),
    ShootingUnits("shooting-units", "Shooting units"),
    DeleteProduction("delete", "Delete this production"),
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
        // splinter, so the two shooting-unit pages go. The dashboard sections
        // stay: every production has a home screen, whatever it is shooting.
        ShootingUnits, RemoteUnits -> !production.isOtherType && !production.isPersonal

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
            SettingsDestination.DealMemoOnboarding,
            SettingsDestination.EditProfile,
            SettingsDestination.RecoveryEmail,
            SettingsDestination.LinkedDevices,
            SettingsDestination.InviteCrew,
            SettingsDestination.LeaveProduction,
            -> null
        }
    }
}
