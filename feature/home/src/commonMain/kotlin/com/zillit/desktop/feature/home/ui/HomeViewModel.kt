package com.zillit.desktop.feature.home.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.domain.ToolCatalogue
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository

data class HomeUiState(
    /**
     * Starts [ProjectPermissions.Empty] — the gap between opening a production
     * and the tools call returning must not be a moment where everything is
     * briefly allowed.
     */
    val permissions: ProjectPermissions = ProjectPermissions.Empty,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** The production's sections, server order; empty until they load. */
    val groups: List<ToolGroup> = emptyList(),
) {
    val gridTools: List<ToolPresentation>
        get() = permissions.gridTools.map(ToolCatalogue::present)

    /**
     * The grid as the production arranged it: one section per group, in the
     * server's order, each holding the tools that belong to it.
     *
     * Tools whose group is unknown — a group this user cannot see, or a tool
     * the server left ungrouped — gather at the end rather than vanishing.
     * Empty sections are dropped: a heading over nothing reads as a fault.
     */
    val sections: List<ToolSection>
        get() {
            val byGroup = permissions.gridTools.groupBy { it.groupIdentifier?.takeIf(String::isNotBlank) }
            val named = groups.mapNotNull { group ->
                byGroup[group.identifier]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ToolSection(group.name, it.map(ToolCatalogue::present)) }
            }
            val placed = groups.map { it.identifier }.toSet()
            val rest = byGroup.filterKeys { it == null || it !in placed }.values.flatten()
            return if (rest.isEmpty()) {
                named
            } else {
                named + ToolSection(OTHER_TOOLS, rest.map(ToolCatalogue::present))
            }
        }

    val hasLoaded: Boolean get() = permissions.visibleTools.isNotEmpty() || (!isBusy && error == null)
}

/** One titled run of tiles in the grid. */
data class ToolSection(val title: String, val tools: List<ToolPresentation>)

/** Where tools with no section of their own gather. */
const val OTHER_TOOLS = "Other tools"

sealed interface HomeEvent {
    data object Reload : HomeEvent
    data class OpenTool(val route: WorkspaceRoute) : HomeEvent
}

sealed interface HomeEffect {
    data class Open(val route: WorkspaceRoute) : HomeEffect
}

/**
 * Loads the production's tools and the rights that go with them.
 *
 * Owns the permission set for the session: every later feature asks
 * [HomeUiState.permissions], so it is fetched once here rather than per tool.
 */
class HomeViewModel(
    private val toolsRepository: ToolsRepository,
) : ZillitViewModel<HomeUiState, HomeEvent, HomeEffect>(HomeUiState()) {

    // Deliberately no eager load. The tools call carries project and user in
    // its `moduledata`, so firing it at construction — before a production is
    // open — sends an empty context and the server answers 406. The host calls
    // [HomeEvent.Reload] when a production is selected.

    override fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.Reload -> load()
            is HomeEvent.OpenTool -> sendEffect(HomeEffect.Open(event.route))
        }
    }

    private fun load() {
        setState { copy(isBusy = true, error = null) }

        // Sections are cosmetic: a failure here leaves one ungrouped grid
        // rather than an empty screen, so it never blocks the tools call.
        launchResult(
            block = { toolsRepository.loadGroups() },
            onSuccess = { groups -> setState { copy(groups = groups) } },
            onError = { },
        )

        launchResult(
            block = { toolsRepository.loadPermissions() },
            onSuccess = { permissions ->
                setState { copy(isBusy = false, permissions = permissions) }
            },
            onError = { error ->
                // Permissions are not partially applied on failure: the state
                // keeps whatever it had, which for a first load is Empty. A
                // failed rights call must never widen access.
                setState { copy(isBusy = false, error = error.localised()) }
            },
        )
    }
}
