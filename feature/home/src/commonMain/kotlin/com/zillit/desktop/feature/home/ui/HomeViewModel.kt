package com.zillit.desktop.feature.home.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.feature.home.data.ToolsOfflineCopy
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.domain.ToolCatalogue
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import kotlinx.serialization.json.Json

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
    /**
     * This user's own section order — group identifiers, top first. Empty
     * means the production's order stands. Saved for this user only, on the
     * server, as both phones and the web do it.
     */
    val groupOrder: List<String> = emptyList(),
    /** The reorder dialog is open. */
    val isReordering: Boolean = false,
    /** When the grid was last fetched, if it is a saved copy shown because the network is gone. */
    val staleSince: Long? = null,
    /**
     * Tools this desktop provides itself, without a server entry — Zillit
     * Draft, which keeps its scripts locally. Shown as their own section at
     * the end of the grid, on every production, regardless of rights.
     */
    val localSections: List<ToolSection> = emptyList(),
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
            // The user's own order first, then the production's for anything
            // the user's list does not name (a group created since) —
            // Android's `ToolsGroupedAdapter.kt:114-119`.
            val named = groups.orderedBy(groupOrder).mapNotNull { group ->
                byGroup[group.identifier]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ToolSection(group.name, it.map(ToolCatalogue::present), group.identifier) }
            }
            val placed = groups.map { it.identifier }.toSet()
            val rest = byGroup.filterKeys { it == null || it !in placed }.values.flatten()
            val served = if (rest.isEmpty()) {
                named
            } else {
                named + ToolSection(UNGROUPED_TOOLS, rest.map(ToolCatalogue::present))
            }
            return served + localSections
        }

    val hasLoaded: Boolean get() = permissions.visibleTools.isNotEmpty() || (!isBusy && error == null)
}

/** One titled run of tiles in the grid; [identifier] null for the leftovers section. */
data class ToolSection(val title: String, val tools: List<ToolPresentation>, val identifier: String? = null)

/**
 * Where tools with no section of their own gather.
 *
 * The word both phones use — Android's `R.string.ungrouped`, iOS's hardcoded
 * `"Ungrouped"` — and on a production that leaves `group_identifier` blank it
 * is the biggest heading on the page, so it is worth spelling the same way.
 */
const val UNGROUPED_TOOLS = "Ungrouped"

/**
 * The groups in the user's chosen order, then the rest in the production's:
 * a saved order that names a group no longer here is ignored, and one that
 * misses a new group puts it after the ones it knows.
 */
internal fun List<ToolGroup>.orderedBy(order: List<String>): List<ToolGroup> {
    if (order.isEmpty()) return this
    val byId = associateBy { it.identifier }
    val chosen = order.mapNotNull { byId[it] }
    val chosenIds = chosen.map { it.identifier }.toSet()
    return chosen + filterNot { it.identifier in chosenIds }
}

sealed interface HomeEvent {
    data object Reload : HomeEvent
    data class OpenTool(val route: WorkspaceRoute) : HomeEvent

    /** The reorder dialog. Save sends the full list to the server, top first. */
    data object StartReorder : HomeEvent
    data object CancelReorder : HomeEvent
    data class SaveGroupOrder(val order: List<String>) : HomeEvent
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
    /**
     * With this wired, the grid as last answered is kept per production and
     * drawn when the network is gone — the way into every tool that works
     * offline. Null leaves the grid network-only, as before.
     */
    private val offline: OfflineSupport? = null,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** Sections the desktop adds itself; see [HomeUiState.localSections]. */
    localSections: List<ToolSection> = emptyList(),
) : ZillitViewModel<HomeUiState, HomeEvent, HomeEffect>(HomeUiState(localSections = localSections)) {

    private val json = Json { ignoreUnknownKeys = true }

    // Deliberately no eager load. The tools call carries project and user in
    // its `moduledata`, so firing it at construction — before a production is
    // open — sends an empty context and the server answers 406. The host calls
    // [HomeEvent.Reload] when a production is selected.

    override fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.Reload -> load()
            is HomeEvent.OpenTool -> sendEffect(HomeEffect.Open(event.route))
            HomeEvent.StartReorder -> setState { copy(isReordering = true) }
            HomeEvent.CancelReorder -> setState { copy(isReordering = false) }
            is HomeEvent.SaveGroupOrder -> saveGroupOrder(event.order)
        }
    }

    /**
     * Applies the new order at once and tells the server; a refusal puts the
     * old order back with the error alongside. The list sent names every
     * current group exactly once, which is what the server validates
     * (Android `reconcileGroupOrder`, ZL-20167).
     */
    private fun saveGroupOrder(order: List<String>) {
        val before = currentState.groupOrder
        val known = currentState.groups.map { it.identifier }
        val reconciled = order.filter { it in known } + known.filterNot { it in order }
        setState { copy(isReordering = false, groupOrder = reconciled) }
        launchResult(
            block = { toolsRepository.saveGroupOrder(reconciled) },
            onSuccess = { },
            onError = { error -> setState { copy(groupOrder = before, error = error.localised()) } },
        )
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
        // The user's own order for them — a failure leaves the production's.
        launchResult(
            block = { toolsRepository.loadGroupOrder() },
            onSuccess = { order -> setState { copy(groupOrder = order) } },
            onError = { },
        )

        launch {
            when (val loaded = toolsRepository.loadPermissions()) {
                is ZillitResult.Success -> {
                    setState { copy(isBusy = false, permissions = loaded.data, staleSince = null) }
                    rememberGrid()
                }

                is ZillitResult.Failure -> {
                    // Permissions are not partially applied on failure: the state
                    // keeps whatever it had, which for a first load is Empty. A
                    // failed rights call must never widen access. The one thing
                    // that may stand in is this production's own last answer,
                    // and only when the failure is the network, not the server.
                    val saved = recallGrid(loaded.error)
                    if (saved != null) {
                        setState {
                            copy(
                                isBusy = false,
                                permissions = saved.first.permissions(),
                                groups = groups.ifEmpty { saved.first.toolGroups() },
                                groupOrder = groupOrder.ifEmpty { saved.first.groupOrder },
                                staleSince = saved.second,
                            )
                        }
                    } else {
                        setState { copy(isBusy = false, error = loaded.error.localised()) }
                    }
                }
            }
        }
    }

    // -- the grid, kept for offline ---------------------------------------------

    /** Kept a moment after the answer, so the sections and order have usually landed too. */
    private suspend fun rememberGrid() {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        val state = currentState
        val copy = ToolsOfflineCopy.of(state.permissions, state.groups, state.groupOrder)
        support.cache.put(scope, GRID_CACHE, json.encodeToString(ToolsOfflineCopy.serializer(), copy), nowMillis())
    }

    private suspend fun recallGrid(error: ZillitError): Pair<ToolsOfflineCopy, Long>? {
        val unreachable = error is ZillitError.NoConnection || error is ZillitError.Timeout
        val scope = offline?.currentScope()
        if (!unreachable || scope == null) return null
        val cached = offline?.cache?.get(scope, GRID_CACHE) ?: return null
        return runCatching { json.decodeFromString(ToolsOfflineCopy.serializer(), cached.json) }.getOrNull()
            ?.let { it to cached.fetchedAt }
    }

    companion object {
        const val GRID_CACHE = "home.tools"
    }
}
