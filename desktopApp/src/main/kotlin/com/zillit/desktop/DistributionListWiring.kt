package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.crewlist.data.CREW_LIST_SYNC_EVENTS
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider
import com.zillit.desktop.feature.distribution.data.DistributionRepositoryImpl
import com.zillit.desktop.feature.distribution.domain.DistributionDirectory
import com.zillit.desktop.feature.distribution.domain.DistributionPerson
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import com.zillit.desktop.feature.distribution.ui.DistributionToolProvider
import com.zillit.desktop.feature.distribution.ui.DistributionViewModel
import com.zillit.desktop.feature.externalusers.data.ExternalUsersRepositoryImpl
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import kotlinx.coroutines.flow.map

/**
 * The Distribution List (`distribution/project` on the project host) — the
 * per-user × per-unit email opt-in matrix, refreshed live when another device
 * flips a switch or an admin reorders the departments. Not the page-distribution
 * engine in `DistributionWiring.kt` — the two tools are one letter apart and
 * share nothing.
 */
internal fun AppGraph.Ready.buildDistributionList(
    permissions: () -> ProjectPermissions,
): DistributionViewModel = DistributionViewModel(
    repository = DistributionRepositoryImpl(apiClient, config),
    resolveViewer = { DistributionViewer.from(permissions()) },
    directory = distributionDirectory(),
    changes = socketEvents.signals(ZillitSocketEvents.Distribution.AccessUpdate),
    reorders = socketEvents.onAny(CREW_LIST_SYNC_EVENTS).map { },
    rights = rightsRequests,
)

/**
 * The web's `usersList` + `useExternalUsers()`: the crew from the production
 * context the app already holds, the outsiders from one page of the
 * external-users directory — the same single fetch the web hook makes.
 */
private fun AppGraph.Ready.distributionDirectory(): DistributionDirectory {
    val externals = ExternalUsersRepositoryImpl(apiClient, config)
    return DistributionDirectory {
        val crew = projectContext?.context?.value?.users.orEmpty().map { user ->
            DistributionPerson(
                userId = user.userId,
                fullName = user.fullName,
                email = user.email.orEmpty(),
                department = user.department.orEmpty(),
                designation = user.designation.orEmpty(),
                status = user.status.orEmpty(),
            )
        }
        val outsiders = when (
            val got = externals.list(ExternalUserBucket.All, System.currentTimeMillis(), older = false)
        ) {
            is ZillitResult.Success -> got.data.map { user ->
                DistributionPerson(
                    userId = user.id,
                    fullName = user.fullName,
                    email = user.email,
                    isExternal = true,
                )
            }
            // The grid still has the wire's `user_name` for them; only the
            // email line under it goes missing.
            is ZillitResult.Failure -> emptyList()
        }
        crew + outsiders
    }
}

/**
 * The tool, with its three seams: the documentation site for "MUST READ",
 * the crew list's listing-order editor for the admin banner's "Click Here"
 * (the web mounts `ChangePriorityList` in place; here the editor belongs to
 * the Crew List tool, so it opens there), and the profile pictures.
 */
internal fun AppGraph.Ready.distributionListProvider(
    viewModel: DistributionViewModel,
    viewModels: AppViewModels,
): DistributionToolProvider = DistributionToolProvider(
    viewModel = viewModel,
    onOpenUrl = ::openInBrowser,
    onOpenListingOrder = viewModels.crewList?.let { crew ->
        { navigator ->
            // Resolve the crew list's viewer first: its admin controller refuses
            // the event until `start()` has read the role, and the window's own
            // start runs a frame after it opens.
            crew.start()
            crew.onEvent(CrewListEvent.Admin.OpenDepartments)
            navigator.openInNewWindow(WorkspaceRoute.Tool(CrewListToolProvider.CREW_LIST_PATH))
        }
    },
    faces = crewFaceLoader(this),
)
