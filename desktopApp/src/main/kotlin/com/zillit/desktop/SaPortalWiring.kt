package com.zillit.desktop

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.saportal.data.SaPortalRepositoryImpl
import com.zillit.desktop.feature.saportal.domain.SaViewer
import com.zillit.desktop.feature.saportal.ui.SaPortalToolProvider
import com.zillit.desktop.feature.saportal.ui.SaPortalViewModel

/**
 * The supporting artiste's own portal.
 *
 * One view model serves both catalogue entries: the web mounts this surface
 * behind `supporting_artistes_extras_tool` and Android injects the same
 * portal as `sa_portal_tool`. A production granting either opens the same
 * screens against the same service, so building two would mean two caches of
 * one artiste's vouchers.
 */
internal fun AppGraph.Ready.buildSaPortal(permissions: () -> ProjectPermissions) = SaPortalViewModel(
    repository = SaPortalRepositoryImpl(apiClient, config),
    viewer = { saViewer(permissions()) },
    events = socketEvents,
    // The artiste's own bell: one unit per portal tab (`sa-portal-badge-helpers.js`).
    badges = tabBadges("unit") { TabBadgeScope(tool = "supporting_artistes_extras_label") },
)

private fun AppGraph.Ready.saViewer(permissions: ProjectPermissions): SaViewer {
    val context = projectContext?.context?.value
    return SaViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        // The signed-in profile's name, not the artiste record's — the spec is
        // explicit that the two can disagree and this is the one the person
        // recognises as themselves.
        displayName = context?.profile?.fullName.orEmpty(),
    )
}

/**
 * The two routes the catalogue already carries for this one surface.
 *
 * Registered separately rather than aliased because the workspace matches a
 * window to its provider by path, and a tool opened from either grid tile
 * must find one.
 */
internal fun saPortalProviders(viewModel: SaPortalViewModel): List<SaPortalToolProvider> = listOf(
    SaPortalToolProvider(viewModel, SaPortalToolProvider.SA_PORTAL_PATH),
    SaPortalToolProvider(viewModel, SaPortalToolProvider.EXTRAS_PATH),
)
