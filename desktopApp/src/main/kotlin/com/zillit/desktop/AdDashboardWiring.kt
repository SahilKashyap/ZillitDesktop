package com.zillit.desktop

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.addashboard.data.AdRepositoryImpl
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.ui.AdToolProvider
import com.zillit.desktop.feature.addashboard.ui.AdViewModel

/**
 * The AD dashboard — the production's side of supporting artistes.
 *
 * The name resolver turns an internal artiste's `user_id` into a crew name:
 * an internal artiste *is* a project user, and their name lives on that user
 * rather than on the artiste record. The web resolves it the same way, off
 * the same list.
 */
internal fun AppGraph.Ready.buildAdDashboard(permissions: () -> ProjectPermissions) = AdViewModel(
    repository = AdRepositoryImpl(
        apiClient = apiClient,
        config = config,
        resolveName = { userId -> crewName(userId) },
    ),
    viewer = { adViewer(permissions()) },
    now = { System.currentTimeMillis() },
    rights = rightsRequests,
    events = socketEvents,
    // Every AD event files under the tool with the dashboard tab as its unit
    // (`constants.js`, "AD Dashboard module"); the tile itself never counts them.
    badges = tabBadges("unit") { TabBadgeScope(tool = "ad_dashboard_label") },
)

private fun AppGraph.Ready.crewName(userId: String): String? =
    projectContext?.context?.value?.users
        ?.firstOrNull { it.userId == userId }
        ?.fullName
        ?.takeIf { it.isNotBlank() }

private fun AppGraph.Ready.adViewer(permissions: ProjectPermissions): AdViewer = AdViewer.from(
    permissions = permissions,
    userId = projectContext?.context?.value?.profile?.userId.orEmpty(),
)

internal fun adDashboardProvider(viewModel: AdViewModel) = AdToolProvider(viewModel)
