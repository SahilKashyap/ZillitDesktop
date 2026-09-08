package com.zillit.desktop

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.distribution.data.DistributionRepositoryImpl
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import com.zillit.desktop.feature.distribution.ui.DistributionViewModel

/**
 * The Distribution List (`distribution/project` on the project host) — the
 * per-user × per-unit email opt-in matrix, refreshed live when another device
 * flips a switch. Not the page-distribution engine in `DistributionWiring.kt`
 * — the two tools are one letter apart and share nothing.
 */
internal fun AppGraph.Ready.buildDistributionList(
    permissions: () -> ProjectPermissions,
): DistributionViewModel = DistributionViewModel(
    repository = DistributionRepositoryImpl(apiClient, config),
    resolveViewer = { DistributionViewer.from(permissions()) },
    changes = socketEvents.signals(ZillitSocketEvents.Distribution.AccessUpdate),
    rights = rightsRequests,
)
