package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.saportal.data.SaPortalRepositoryImpl
import com.zillit.desktop.feature.saportal.domain.SaViewer
import com.zillit.desktop.feature.saportal.ui.SaPortalToolProvider
import com.zillit.desktop.feature.saportal.ui.SaPortalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    // The artiste's own bell: one unit per portal tab (`sa-portal-badge-helpers.js`),
    // each row naming its record in `level_3`.
    badges = unitEntityBadges(tool = "supporting_artistes_extras_label"),
)

/**
 * A tool whose tabs are units and whose rows each name a record in `level_3`
 * — the supporting-artiste portal and the AD dashboard (iOS
 * `SABadgeViewModel`/`ADBadgeViewModel`: `count(unit)`, `entityCount(unit,
 * id)`, `readUnit`, `readEntity(unit, id, level2)`).
 *
 * The tab counts and the whole-unit read are [tabBadges]'; on top, the
 * per-record tree (unit → `level_3` → unread) for the row dots, and the
 * per-record read: `notification:level:read` on the tool, the unit, the
 * record as `level_3` and — when given — the bucket as `level_2`. iOS stamps
 * `section` with the unit on these (`SABadgeViewModel.swift:254-266`); the
 * shared emitter sends `tools_label`, as every other level read here does.
 */
internal fun AppGraph.Ready.unitEntityBadges(tool: String): TabBadgeSource = object : TabBadgeSource {
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tabs = tabBadges("unit") { TabBadgeScope(tool = tool) }

    override val counts: Flow<Map<String, Int>> = tabs.counts

    override fun read(key: String) = tabs.read(key)

    override val entityCounts: Flow<Map<String, Map<String, Int>>> = badgeStore.counts
        .map {
            badgeStore.unreadRows(BadgeSections.TOOLS)
                .filter { it.tool == tool && it.unit.isNotBlank() && it.level3.isNotBlank() }
                .groupBy { it.unit }
                .mapValues { (_, rows) -> rows.groupingBy { it.level3 }.eachCount() }
        }
        .distinctUntilChanged()

    override fun readEntity(key: String, entityId: String, kind: String?) {
        if (key.isBlank() || entityId.isBlank()) return
        reads.launch { emitLevelRead(tool = tool, unit = key, level2 = kind, level3 = entityId) }
    }
}

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
