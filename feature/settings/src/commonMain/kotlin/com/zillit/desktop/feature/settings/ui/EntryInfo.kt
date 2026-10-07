package com.zillit.desktop.feature.settings.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * What a row's ⓘ says: the web's `TooltipInfo` message, and the guide behind
 * its More and Watch video.
 *
 * Separate from [SettingsEntry.detail], which is the line the row itself shows;
 * this is the web's own wording, word for word.
 */
data class EntryInfo(val message: String, val guide: InfoGuide)

/** The web's message (a string key) and guide behind each Admin Settings row's ⓘ. */
private val ADMIN_INFO: Map<SettingsDestination, Pair<String, InfoGuide>> = mapOf(
    SettingsDestination.ApproveNewCrew to (S.approve_user_request_info to InfoGuide.ApproveNewCrew),
    SettingsDestination.ApproveProfileChanges to (S.approve_profile_change_info to InfoGuide.ApproveProfile),
    SettingsDestination.PreApprovedCrew to (S.pre_approved_users_info to InfoGuide.PreApproved),
    SettingsDestination.CrewAndAdmins to (S.user_management_info to InfoGuide.UserManagement),
    SettingsDestination.PermissionGrid to (S.permission_grid_info2 to InfoGuide.PermissionGrid),
    SettingsDestination.Departments to (S.desktop_admin_create_dept_info to InfoGuide.Departments),
    SettingsDestination.JobTitles to (S.desktop_admin_create_desg_info to InfoGuide.Designations),
    SettingsDestination.CrewListOrder to (S.set_department_priority_info to InfoGuide.CrewListOrder),
    SettingsDestination.ShootingUnits to (S.add_home_units_info to InfoGuide.HomeUnits),
    SettingsDestination.RemoteUnit to (S.desktop_admin_remote_unit_info to InfoGuide.RemoteUnit),
    SettingsDestination.JoinedUnits to (S.desktop_admin_create_join_unit_info to InfoGuide.JoinUnits),
    SettingsDestination.ToolAvailability to (S.desktop_admin_tool_customization_info to InfoGuide.ToolAvailability),
    SettingsDestination.ToolGroups to (S.manage_tool_groups_info to InfoGuide.ToolGroups),
    SettingsDestination.ProductionName to (S.edit_project_name_info to InfoGuide.ProductionName),
    SettingsDestination.ProductionSetup to (S.desktop_admin_production_setup_info to InfoGuide.ProductionSetup),
    SettingsDestination.CompanyDetails to (S.company_details_module_title to InfoGuide.CompanyDetails),
    SettingsDestination.Watermark to (S.watermark_info to InfoGuide.Watermark),
    SettingsDestination.FileCabinet to (S.file_cabinet_info to InfoGuide.FileCabinet),
    SettingsDestination.SosRecipients to (S.sos_recipients_info to InfoGuide.Sos),
    SettingsDestination.SetupNotes to (S.project_set_up_notes_info to InfoGuide.SetupNotes),
    SettingsDestination.DeleteProduction to (S.delete_project_info to InfoGuide.DeleteProduction),
)

/**
 * The ⓘ behind each Admin Settings row, from `AdminSetting.jsx` and the
 * component each tile renders. The stop-deletion button carries none, as on the
 * web.
 */
internal fun adminInfo(destination: SettingsDestination, production: ProductionFacts): EntryInfo? {
    if (destination == SettingsDestination.DeleteProduction && production.markedForDeletion) return null
    return ADMIN_INFO[destination]?.let { (message, guide) -> EntryInfo(str(message), guide) }
}

/**
 * The ⓘ behind each Profile Settings row (`EditUserProfile`, `InviteUser`,
 * `WebrecoveryMail`, `LeaveProject`). Where the web words it differently for an
 * admin — the recovery email, leaving, and the Edit Profile clip — so does this.
 * Zillit Help and Linked devices are the desktop's own rows and have no web
 * counterpart, so they carry none.
 */
internal fun profileInfo(destination: SettingsDestination, isAdmin: Boolean): EntryInfo? =
    when (destination) {
        SettingsDestination.EditProfile -> EntryInfo(
            str(S.desktop_info_edit_profile),
            if (isAdmin) InfoGuide.EditProfileAdmin else InfoGuide.EditProfile,
        )
        SettingsDestination.InviteCrew -> EntryInfo(str(S.desktop_info_invite_user), InfoGuide.InviteUser)
        SettingsDestination.RecoveryEmail -> EntryInfo(
            str(if (isAdmin) S.desktop_info_recovery_admin else S.desktop_info_recovery_user),
            InfoGuide.RecoveryEmail,
        )
        SettingsDestination.LeaveProduction -> EntryInfo(
            str(if (isAdmin) S.desktop_info_leave_admin else S.desktop_info_leave_user),
            InfoGuide.LeaveProject,
        )
        else -> null
    }

internal fun List<SettingsGroup>.withInfo(info: (SettingsDestination) -> EntryInfo?): List<SettingsGroup> =
    map { group -> group.copy(entries = group.entries.map { it.copy(info = info(it.destination)) }) }
