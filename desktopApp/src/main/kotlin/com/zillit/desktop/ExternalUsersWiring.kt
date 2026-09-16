package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.externalusers.data.ExternalUsersRepositoryImpl
import com.zillit.desktop.feature.externalusers.domain.Creator
import com.zillit.desktop.feature.externalusers.domain.DialCode
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersViewer
import com.zillit.desktop.feature.externalusers.ui.DepartmentOption
import com.zillit.desktop.feature.externalusers.ui.DesignationOption
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersToolProvider
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersViewModel

/**
 * External Users: the outside-contact directory (`user/external-user` on the
 * project host). Departments ride the admin repository's existing read — the
 * form's crew pickers need names and job titles, which it already carries.
 * The crew comes from the open production's context, so a card's "Created
 * By" can name the coordinator rather than print an id; the dial codes are
 * the ISD preset the Account Hub already fetches, or the web's bundled copy.
 */
internal fun AppGraph.Ready.buildExternalUsers(
    permissions: () -> ProjectPermissions,
): ExternalUsersViewModel = ExternalUsersViewModel(
    repository = ExternalUsersRepositoryImpl(apiClient, config),
    rights = rightsRequests,
    // A guest added or removed by another coordinator lands live.
    events = socketEvents,
    // Which production the rows belong to — sampled per open, so a roster
    // fetched under one production is wiped before another's window shows.
    projectId = { projectContext?.context?.value?.project?.projectId },
    resolveViewer = {
        ExternalUsersViewer.from(
            permissions(),
            projectContext?.context?.value?.profile?.userId.orEmpty(),
        )
    },
    loadDepartments = {
        when (val got = adminRepository.departments()) {
            is ZillitResult.Success -> got.data.map { department ->
                DepartmentOption(
                    id = department.id,
                    name = department.name,
                    designations = department.jobTitles.map { DesignationOption(it.id, it.name) },
                )
            }
            is ZillitResult.Failure -> emptyList()
        }
    },
    loadCrew = {
        projectContext?.context?.value?.users.orEmpty().map { member ->
            Creator(userId = member.userId, fullName = member.fullName, designation = member.designation.orEmpty())
        }
    },
    // The service's list, or the web's own copy when the service cannot be reached.
    loadDialCodes = {
        accountHubRepository.isdCodes().getOrNull().orEmpty().ifEmpty { IsdCountries.bundled }
            .filter { it.dialCode.isNotBlank() }
            .map { DialCode(name = it.name, dialCode = it.dialCode, isoCode = it.code) }
    },
    nowMillis = System::currentTimeMillis,
)

/**
 * The tool with the one thing its screen borrows from the app: a clicked
 * address raises the mail composer — queued on the mailbox so it opens
 * whether the mail window is already up or opens now. On a personal
 * production there is no Zillit mailbox, so the OS mail client gets it, as
 * the web's `EmailOpener` falls back to `mailto:`.
 */
internal fun AppGraph.Ready.externalUsersProvider(
    viewModel: ExternalUsersViewModel,
    viewModels: AppViewModels,
): ExternalUsersToolProvider = ExternalUsersToolProvider(
    viewModel = viewModel,
    onEmail = { address, navigator ->
        val personal = projectContext?.context?.value?.project?.type.equals(PERSONAL_PRODUCTION_TYPE, ignoreCase = true)
        val mail = viewModels.email
        if (personal || mail == null) {
            openMailClient(address)
        } else {
            mail.composeRequests.post(address)
            navigator.openInNewWindow(WorkspaceRoute.Tool(EMAIL_TOOL_ROUTE))
        }
    },
)

/** Hands [address] to whatever the OS registered for mail. */
private fun openMailClient(address: String) {
    runCatching {
        val desktop = java.awt.Desktop.getDesktop().takeIf { java.awt.Desktop.isDesktopSupported() }
        if (desktop?.isSupported(java.awt.Desktop.Action.MAIL) == true) {
            desktop.mail(java.net.URI("mailto:$address"))
        }
    }
}

private const val PERSONAL_PRODUCTION_TYPE = "personal"
private const val EMAIL_TOOL_ROUTE = "/email"
