package com.zillit.desktop.feature.settings.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue

/**
 * Where the Admin Settings tab and the pages under it live.
 *
 * All served by [SettingsToolProvider]: administration is the second tab of the
 * Settings window, as it is on the web, rather than a window of its own.
 */

/** Matches the web app's `/settings?s=admin` tab, as a path. */
const val ADMIN_SETTINGS_PATH = "$SETTINGS_PATH/admin"

/**
 * Where each administration page lives.
 *
 * Built from the destination's own slug rather than listed here, so adding a
 * page cannot forget its route.
 */
val AdminDestination.path: String get() = "$ADMIN_SETTINGS_PATH/$slug"

/** Where each queue lives, so a deep link opens the right one. */
val ApprovalQueue.path: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "$ADMIN_SETTINGS_PATH/new-crew"
        ApprovalQueue.ProfileChanges -> "$ADMIN_SETTINGS_PATH/profile-changes"
    }

internal val ApprovalQueue.tabTitle: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> str(S.desktop_approve_new_crew)
        ApprovalQueue.ProfileChanges -> str(S.desktop_approve_profile_changes)
    }
