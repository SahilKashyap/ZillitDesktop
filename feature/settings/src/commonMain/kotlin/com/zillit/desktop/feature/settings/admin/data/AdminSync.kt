package com.zillit.desktop.feature.settings.admin.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination

/**
 * Live updates for the administration pages.
 *
 * These are the events `CrewListSync` documents as belonging elsewhere:
 * "the `project_user_*` and `department:create/update/delete` families are
 * NOT here: the crew-list page does not listen to them (they feed the
 * header, badges, and admin-settings pages)". This is those pages.
 *
 * All three clients carry them — Android's `_department*` /
 * `_isUserAccepted` / `_userAdminAccessChange` flows, iOS's
 * `.updateDepartmentNotification` / `.projectUserAccepted` /
 * `.userAdminRightsUpdate`, the web's `department_*` and `project_user_*`
 * aliases. The desktop listened for none of them (audited 2026-09-07), so an
 * admin watching the crew page did not see somebody accepted, removed or made
 * an administrator by a second coordinator — the exact page where two people
 * work at once.
 *
 * Mapped to *pages* rather than to a blanket reload: these lists are separate
 * fetches, and reloading the department tree because somebody's rights moved
 * would spin four pages for one event.
 */
val ADMIN_SYNC_PAGES: Map<SocketEventName, Set<AdminDestination>> = buildMap {
    // The department tree — three pages read the same list.
    val departmentPages = setOf(
        AdminDestination.Departments,
        AdminDestination.JobTitles,
        AdminDestination.CrewOrder,
    )
    listOf(
        "department:create",
        "department:update",
        "department:delete",
        "department:reordered",
    ).forEach { put(SocketEventName(it), departmentPages) }

    // Who is on the production. The crew page lists them; the rights grid is
    // indexed by them, so a departure leaves a row pointing at nobody.
    val crewPages = setOf(AdminDestination.Crew, AdminDestination.Rights)
    listOf(
        "project:user:accepted",
        "project:user:removed",
        "project:user:left",
        "project:user:admin:access",
        "project:user:profile:update",
        "project:user:profile:created",
        "project:user:reordered",
        "project:pre-approved:user:joined",
    ).forEach { put(SocketEventName(it), crewPages) }

    // Requests waiting to be let in, and profile changes waiting to be signed
    // off. Both queues live on the pre-approved page.
    val queuePages = setOf(AdminDestination.PreApproved, AdminDestination.Crew)
    listOf(
        "project:user:join:request:received",
        "project:user:join:request:accepted",
        "project:user:join:request:rejected",
        "project:user:profile:change:requested",
        "project:user:profile:change:accepted",
        "project:user:profile:change:rejected",
    ).forEach { put(SocketEventName(it), queuePages) }
}

val ADMIN_SYNC_EVENTS: List<SocketEventName> = ADMIN_SYNC_PAGES.keys.toList()
