package com.zillit.desktop.feature.settings.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination

/**
 * Everywhere a settings row can lead.
 *
 * An identifier rather than a lambda on each row, because the catalogues below
 * are then plain data: they can be built and asserted on without a composition,
 * and the screen — not the list — decides what opening one means.
 *
 * It also gives the destinations that have not reached desktop yet a name to be
 * wired to when they land, instead of a row that has to be invented twice.
 */
enum class SettingsDestination {

    // -- personal ----------------------------------------------------------
    EditProfile,
    RecoveryEmail,
    LinkedDevices,
    InviteCrew,
    LeaveProduction,
    Help,

    // -- admin: approvals --------------------------------------------------
    ApproveNewCrew,
    ApproveProfileChanges,
    PreApprovedCrew,

    // -- admin: people -----------------------------------------------------
    CrewAndAdmins,
    PermissionGrid,
    Departments,
    JobTitles,
    CrewListOrder,

    // -- admin: units ------------------------------------------------------
    ShootingUnits,
    RemoteUnit,
    JoinedUnits,

    // -- admin: tools ------------------------------------------------------
    ToolAvailability,
    ToolGroups,

    // -- admin: the production itself --------------------------------------
    ProductionName,
    ProductionSetup,
    Watermark,
    CompanyDetails,
    SosRecipients,
    SetupNotes,
    DeleteProduction,
}

/**
 * Whether the row's destination exists on this client yet.
 *
 * Shown rather than hidden, and never silently inert. The phone and web clients
 * carry all of these today, so a coordinator who knows the product will look for
 * them here; an absent row reads as a feature that was taken away, and a row
 * that does nothing when clicked reads as a bug. [Planned] says which it is.
 */
enum class EntryStatus { Ready, Planned }

/** [Danger] rows are destructive and are always kept out of the main run. */
enum class EntryTone { Normal, Danger }

/**
 * One destination on a settings page.
 *
 * The explanation is not decoration. "Permission grid" and "Crew list order"
 * mean nothing to someone opening this page for the first time, and the phone
 * clients answer that with an info button behind a dialog — one more click for
 * something that fits on the row itself at desktop width.
 */
data class SettingsEntry(
    val destination: SettingsDestination,
    val title: String,
    val detail: String,
    val icon: ImageVector,
    /** Unread waiting behind this row — approvals queues, mostly. */
    val badge: Int = 0,
    val tone: EntryTone = EntryTone.Normal,
    val status: EntryStatus = EntryStatus.Ready,
) {
    val isOpenable: Boolean get() = status == EntryStatus.Ready

    /**
     * Matches the explanation as well as the title.
     *
     * Someone hunting for "who can download the budget" knows what they want,
     * not that it is filed under "Permission grid".
     */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        return needle.isEmpty() ||
            title.contains(needle, ignoreCase = true) ||
            detail.contains(needle, ignoreCase = true)
    }
}

/**
 * A named run of rows.
 *
 * Grouped rather than the references' single alphabetical list. Android and iOS
 * sort ~20 admin rows by title, which puts "Approve new crew" next to "Company
 * details" and leaves the reader scanning the whole page for anything. Desktop
 * has the width for headings, and the headings are what make the page findable.
 */
data class SettingsGroup(
    val title: String,
    /** Worn by the heading, matching the preference sections on the same page. */
    val icon: ImageVector,
    val entries: List<SettingsEntry>,
)

/**
 * Narrows every group to what matches, dropping the ones left empty.
 *
 * An empty heading is worse than no heading: it reads as a group whose contents
 * failed to load.
 */
fun List<SettingsGroup>.matching(query: String): List<SettingsGroup> =
    mapNotNull { group ->
        group.entries.filter { it.matches(query) }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(entries = it) }
    }

/** Rows across every group — for "nothing matched" and for counts. */
fun List<SettingsGroup>.entryCount(): Int = sumOf { it.entries.size }

/**
 * Whether anything on screen still wears a "Soon" tag.
 *
 * Asked of the *filtered* groups rather than the catalogue, so the notice
 * explaining the tag appears only when the reader can see one. Most productions
 * now have no planned rows at all.
 */
val List<SettingsGroup>.anyPlanned: Boolean
    get() = any { group -> group.entries.any { !it.isOpenable } }

/**
 * The settings page's own destinations.
 *
 * Device preferences — theme, interface size, notification switches — are not
 * here: they are settings this screen *applies*, not places it sends you, and
 * turning a switch into a row that opens a page holding one switch is how a
 * settings app grows three levels deep.
 *
 * Administration is not here either, and no longer takes an `isAdmin` argument
 * to hide: it is a rail destination of its own, offered to coordinators and
 * absent for everyone else. See [adminSettingsEntries] for what it holds.
 */
fun settingsEntries(): List<SettingsGroup> = listOf(
    accountGroup(),
    productionGroup(),
).filter { it.entries.isNotEmpty() }

private fun accountGroup(): SettingsGroup =
    SettingsGroup(
        title = str(S.desktop_your_account),
        icon = ZillitIcons.User,
        entries = listOf(
            SettingsEntry(
                destination = SettingsDestination.EditProfile,
                title = str(S.desktop_your_profile),
                detail = str(S.desktop_your_profile_detail),
                icon = ZillitIcons.User,
            ),
            SettingsEntry(
                destination = SettingsDestination.RecoveryEmail,
                title = str(S.recovery_email),
                detail = str(S.desktop_recovery_email_detail),
                icon = ZillitIcons.Mail,
            ),
            SettingsEntry(
                destination = SettingsDestination.LinkedDevices,
                title = str(S.desktop_linked_devices),
                detail = str(S.desktop_linked_devices_detail),
                icon = ZillitIcons.Monitor,
            ),
            SettingsEntry(
                destination = SettingsDestination.InviteCrew,
                title = str(S.desktop_cal_invite_crew),
                detail = str(S.desktop_invite_crew_detail),
                icon = ZillitToolIcons.IcInviteUser,
            ),
        ),
    )

private fun productionGroup(): SettingsGroup =
    SettingsGroup(
        title = str(S.desktop_this_project),
        icon = ZillitIcons.Home,
        entries = listOf(
            SettingsEntry(
                destination = SettingsDestination.Help,
                title = str(S.zillit_help),
                detail = str(S.desktop_zillit_help_detail),
                icon = ZillitIcons.Info,
            ),
            SettingsEntry(
                destination = SettingsDestination.LeaveProduction,
                title = str(S.leave_this_project),
                detail = str(S.desktop_leave_this_project_detail),
                icon = ZillitIcons.Detach,
                tone = EntryTone.Danger,
            ),
        ),
    )

/**
 * What the open production is, as far as this page is concerned.
 *
 * Only what changes which rows appear. A production's type decides whether it
 * runs shooting units at all — "other" productions (corporate, events) do not,
 * and both phone clients hide those rows rather than offering a unit that
 * cannot exist.
 */
data class ProductionFacts(
    val name: String = "",
    /** Corporate and event productions: no shooting units, no deal memos. */
    val isOtherType: Boolean = false,
    /** A personal production has no crew, so almost none of this page applies. */
    val isPersonal: Boolean = false,
    /**
     * Whether this production is itself a remote unit of another.
     *
     * A remote unit cannot spawn further units, so the two "create a unit"
     * rows go — Android reads the same `parent_project_name` for this.
     */
    val isRemoteUnit: Boolean = false,
    /**
     * A deletion already scheduled and counting down.
     *
     * Changes what the danger row does rather than whether it appears: on a
     * production in this state the only thing to offer is stopping it.
     */
    val markedForDeletion: Boolean = false,
)

/**
 * Reads the facts off the open production's record.
 *
 * Lives with the page that reads them rather than at the wiring site: which
 * `project_type` strings mean "no shooting units" is knowledge about this
 * listing, and the comparison is loose because the value arrives inconsistently
 * cased.
 *
 */
fun productionFacts(
    name: String?,
    type: String?,
    parentName: String? = null,
    markedForDeletion: Boolean = false,
): ProductionFacts = ProductionFacts(
    name = name.orEmpty(),
    isOtherType = type.equals(OTHER_PRODUCTION, ignoreCase = true),
    isPersonal = type.equals(PERSONAL_PRODUCTION, ignoreCase = true),
    isRemoteUnit = !parentName.isNullOrBlank(),
    markedForDeletion = markedForDeletion,
)

private const val OTHER_PRODUCTION = "other"
private const val PERSONAL_PRODUCTION = "personal"

/**
 * The administration page's own destinations.
 *
 * Transcribed from the three clients that already have it — Android's
 * `AdminSettingsActivity`, iOS's `AdminSettingViewController`, and the web's
 * `AdminSetting.jsx` — so a coordinator moving between them finds the same
 * things. Where the three disagree, the rule they share wins: rows a production
 * cannot use are absent, not disabled.
 */
@Suppress("LongMethod") // A catalogue. Splitting it hides what the page offers.
fun adminSettingsEntries(
    production: ProductionFacts = ProductionFacts(),
    pendingNewCrew: Int = 0,
    pendingProfileChanges: Int = 0,
): List<SettingsGroup> = listOf(
    SettingsGroup(
        title = str(S.desktop_waiting_on_you),
        icon = ZillitIcons.Clock,
        entries = listOf(
            SettingsEntry(
                destination = SettingsDestination.ApproveNewCrew,
                title = str(S.approve_new_user_request),
                detail = str(S.desktop_approve_new_crew_detail),
                icon = ZillitToolIcons.IcInviteUser,
                badge = pendingNewCrew,
            ),
            SettingsEntry(
                destination = SettingsDestination.ApproveProfileChanges,
                title = str(S.approve_user_profile),
                detail = str(S.desktop_approve_profile_detail),
                icon = ZillitIcons.User,
                badge = pendingProfileChanges,
            ),
            SettingsEntry(
                destination = SettingsDestination.PreApprovedCrew,
                title = str(S.pre_approved_users),
                detail = str(S.desktop_pre_approved_detail),
                icon = ZillitIcons.Check,
            ),
        ),
    ),
    SettingsGroup(
        title = str(S.section_people),
        icon = ZillitIcons.User,
        entries = listOf(
            SettingsEntry(
                destination = SettingsDestination.CrewAndAdmins,
                title = str(S.txt_user_managment),
                detail = str(S.desktop_user_management_detail),
                icon = ZillitToolIcons.CrewList,
            ),
            SettingsEntry(
                destination = SettingsDestination.PermissionGrid,
                title = str(S.permission_grid_2),
                detail = str(S.desktop_rights_grid_detail),
                icon = ZillitToolIcons.PostingRights,
            ),
            SettingsEntry(
                destination = SettingsDestination.Departments,
                title = str(S.create_new_department),
                detail = str(S.desktop_departments_detail),
                icon = ZillitIcons.LayoutCascade,
            ),
            SettingsEntry(
                destination = SettingsDestination.JobTitles,
                title = str(S.create_new_designation),
                detail = str(S.desktop_designations_detail),
                icon = ZillitToolIcons.Casting,
            ),
            SettingsEntry(
                destination = SettingsDestination.CrewListOrder,
                // "Crew list" is a film production's word for it; corporate and
                // event productions call the same page a staff list, and both
                // phone clients switch the label rather than the page.
                title = if (production.isOtherType) {
                    str(S.set_department_priority_staff_list)
                } else {
                    str(S.desktop_crew_list_order_crew)
                },
                detail = str(S.desktop_crew_list_order_detail),
                icon = ZillitToolIcons.AdDash,
            ),
        ),
    ),
    SettingsGroup(
        title = str(S.desktop_section_units),
        icon = ZillitIcons.Home,
        entries = listOf(
                SettingsEntry(
                    destination = SettingsDestination.ShootingUnits,
                    title = str(S.desktop_home_units_title),
                    detail = str(S.desktop_home_units_detail),
                    icon = ZillitToolIcons.IcContinuity,
                ),
                SettingsEntry(
                    destination = SettingsDestination.RemoteUnit,
                    title = str(S.create_remote_unit),
                    detail = str(S.desktop_remote_units_detail),
                    icon = ZillitToolIcons.Location,
                ),
                SettingsEntry(
                    destination = SettingsDestination.JoinedUnits,
                    title = str(S.create_join_unit),
                    detail = str(S.desktop_shooting_units_detail),
                    icon = ZillitToolIcons.PreProduction,
                ),
        ),
    ),
    SettingsGroup(
        title = str(S.tools),
        icon = ZillitIcons.Tools,
        entries = listOf(
            SettingsEntry(
                destination = SettingsDestination.ToolAvailability,
                title = str(S.project_tools_enable_disable),
                detail = str(S.desktop_tool_customisation_detail),
                icon = ZillitIcons.Tools,
            ),
            SettingsEntry(
                destination = SettingsDestination.ToolGroups,
                title = str(S.manage_tool_groups),
                detail = str(S.desktop_tool_groups_detail),
                icon = ZillitIcons.LayoutTabs,
            ),
        ),
    ),
    SettingsGroup(
        title = str(S.desktop_section_the_project),
        icon = ZillitIcons.File,
        entries = listOfNotNull(
            SettingsEntry(
                destination = SettingsDestination.ProductionName,
                title = str(S.edit_project_name),
                detail = str(S.desktop_edit_project_name_detail),
                icon = ZillitToolIcons.Script,
            ),
            SettingsEntry(
                destination = SettingsDestination.ProductionSetup,
                title = str(S.ps_production_setup),
                detail = str(S.desktop_production_setup_detail),
                // Not ZillitToolIcons.Account — Company Details, the row below
                // it, already wears that one, and two near-identical building
                // marks on adjacent rows read as a duplicate.
                icon = ZillitIcons.Bank,
            ),
            SettingsEntry(
                destination = SettingsDestination.CompanyDetails,
                title = str(S.company_details),
                detail = str(S.desktop_company_details_detail),
                icon = ZillitToolIcons.Account,
            ),
            SettingsEntry(
                destination = SettingsDestination.Watermark,
                title = str(S.water_mark_logo),
                detail = str(S.desktop_watermark_detail),
                icon = ZillitToolIcons.IcSignedDocument,
            ),
            SettingsEntry(
                destination = SettingsDestination.SosRecipients,
                title = str(S.set_sos_receivers),
                detail = str(S.desktop_sos_receivers_detail),
                icon = ZillitIcons.Phone,
            ),
            SettingsEntry(
                destination = SettingsDestination.SetupNotes,
                title = str(S.project_set_up_notes),
                detail = str(S.desktop_setup_notes_detail),
                icon = ZillitIcons.Info,
            ),
        ),
    ),
    SettingsGroup(
        title = str(S.desktop_danger_zone),
        icon = ZillitIcons.Close,
        entries = listOf(
            SettingsEntry(
                destination = SettingsDestination.DeleteProduction,
                // Two things behind one row, as on the phones: a production
                // already counting down is stopped here, not deleted twice.
                // The explanation flips with the title — describing a deletion
                // under a button that cancels one is worse than no explanation.
                title = if (production.markedForDeletion) str(S.stop_project_deletion) else str(S.delete_project),
                detail = if (production.markedForDeletion) {
                    str(S.desktop_stop_deletion_detail)
                } else {
                    str(S.desktop_delete_project_detail)
                },
                icon = ZillitIcons.Close,
                tone = EntryTone.Danger,
            ),
        ),
    ),
).availableOn(production)

/**
 * Drops the rows this production has no page for.
 *
 * Asks [AdminDestination.availableTo] rather than re-testing the production
 * type here, so the listing and the page guard cannot disagree. They did on the
 * web: its hub hides the remote-unit tile on corporate productions and the
 * route behind it still opens the page.
 *
 * Rows with no administration page of their own — the approval queues, the
 * documentation links — are kept: they are not this rule's business.
 */
private fun List<SettingsGroup>.availableOn(production: ProductionFacts): List<SettingsGroup> =
    mapNotNull { group ->
        group.entries
            .filter { entry ->
                AdminDestination.of(entry.destination)?.availableTo(production) ?: true
            }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(entries = it) }
    }

/** The admin walkthrough iOS links from its own "project setup notes" row. */
const val SETUP_NOTES_URL = "https://documentation.zillit.com/#project-setup-notes"

/**
 * The Account Hub's route, which Production Setup is a page of.
 *
 * Repeated rather than depended on: this module knows no other feature, and a
 * dependency on the accounts console to read one string would be the wrong way
 * round. `AdminSettingsRoutingTest` pins the two together.
 */
const val ACCOUNT_HUB_ROUTE = "/film-tools/account-hub"
