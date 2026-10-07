package com.zillit.desktop.feature.home.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.feature.home.data.ToolsOfflineCopy
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.domain.ToolCatalogue
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.domain.ADMIN_GROUP
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
    /** Whether this user administers the production — the customise entry shows only then. */
    val isAdmin: Boolean = false,
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
    /**
     * Manage Tool Groups is offered: an admin, on a production whose Remote
     * Config says `show_manage_tool_groups = true` (the web's
     * `useToolOrganize.canOrganize`). Unset or unreachable means hidden.
     */
    val canOrganize: Boolean = false,
    /** Organize mode is on — drag between groups, rename, add, delete. */
    val organizing: Boolean = false,
    /** Tools moved this session, painted at once ahead of the server: identifier → group key. */
    val moves: Map<String, String> = emptyMap(),
    /** The group a rename, create or delete is in flight for ([NEW_GROUP_KEY] for a create). */
    val busyGroupKey: String? = null,
    /** The heading being renamed — one at a time. */
    val renamingGroup: String? = null,
    /** A group just created, until the page has scrolled to it. */
    val createdGroupKey: String? = null,
    /** The custom group waiting on a "Delete this group?" answer. */
    val confirmingDelete: ToolGroup? = null,
    /** A floating message — the web's `message.success` / `message.error`. */
    val toast: HomeToast? = null,
) {
    val gridTools: List<ToolPresentation>
        get() = permissions.gridTools.map(ToolCatalogue::present)

    /**
     * The grid as the production arranged it — the web's `groupedTools`:
     * one section per live group, in this user's order then the production's
     * for groups their order does not name, each holding its tools.
     *
     * A tool whose group is not in the live list is filed under Admin, as the
     * web files it, so no tile is dropped; a tool taken out of every group
     * (`group_identifier: ""`) goes to a trailing **Ungrouped** section.
     * Empty sections are dropped — except while organizing, when a group
     * with nothing in it is exactly the target the admin needs.
     */
    val sections: List<ToolSection>
        get() {
            val live = groups.map { it.identifier }.toSet()
            val keyed = permissions.gridTools.groupBy { tool ->
                val served = tool.groupIdentifier?.takeIf(String::isNotBlank)
                val settled = when {
                    served == null -> UNGROUPED_KEY
                    live.isNotEmpty() && served !in live -> ADMIN_GROUP
                    else -> served
                }
                moves[tool.identifier] ?: settled
            }
            val named = groups.orderedBy(groupOrder).mapNotNull { group ->
                val tools = keyed[group.identifier].orEmpty()
                if (tools.isEmpty() && !organizing) return@mapNotNull null
                ToolSection(group.name, tools.map(ToolCatalogue::present), group.identifier)
            }
            // Before the live list lands every served key stands on its own,
            // so nothing waits on the groups call to be drawn.
            val unplaced = if (live.isEmpty()) {
                keyed.filterKeys { it != UNGROUPED_KEY }.values.flatten()
            } else {
                emptyList()
            }
            val ungrouped = keyed[UNGROUPED_KEY].orEmpty() + unplaced
            val served = if (ungrouped.isEmpty()) {
                named
            } else {
                named + ToolSection(UNGROUPED_TOOLS, ungrouped.map(ToolCatalogue::present), ungrouped = true)
            }
            return served + localSections
        }

    /** Whether [key] names a group an admin may rename — any live group, never Ungrouped. */
    fun isRenameable(key: String?): Boolean =
        canOrganize && key != null && groups.any { it.identifier == key && it.id != null }

    /** Custom groups only; the six defaults and Ungrouped are never deletable. */
    fun isDeletable(key: String?): Boolean =
        canOrganize && key != null && groups.any { it.identifier == key && it.id != null && !it.systemDefined }

    val hasLoaded: Boolean get() = permissions.visibleTools.isNotEmpty() || (!isBusy && error == null)
}

/** A floating message on the grid. */
data class HomeToast(val text: String, val success: Boolean)

/** The grid's key for the trailing Ungrouped section — its group id on the wire is "". */
const val UNGROUPED_KEY = "ungrouped"

/** [HomeUiState.busyGroupKey] while a new group is being created. */
const val NEW_GROUP_KEY = "__new__"

/**
 * One titled run of tiles in the grid; [identifier] null for the leftovers
 * section and for the desktop's own local sections. [ungrouped] marks the
 * leftovers — a real drop target while organizing, whose group id is "".
 */
data class ToolSection(
    val title: String,
    val tools: List<ToolPresentation>,
    val identifier: String? = null,
    val ungrouped: Boolean = false,
) {
    /** Where a tool dropped here goes; null for a section nothing can be moved into. */
    val dropKey: String? get() = identifier ?: UNGROUPED_KEY.takeIf { ungrouped }
}

/**
 * Where tools with no section of their own gather.
 *
 * The word both phones use — Android's `R.string.ungrouped`, iOS's hardcoded
 * `"Ungrouped"` — and on a production that leaves `group_identifier` blank it
 * is the biggest heading on the page, so it is worth spelling the same way.
 */
val UNGROUPED_TOOLS: String get() = str(S.ungrouped)

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

    /** Manage Tool Groups — admin only, behind the Remote Config switch. */
    data object ToggleOrganize : HomeEvent
    data class MoveTool(val toolIdentifier: String, val toolName: String, val toGroup: String) : HomeEvent
    data class StartRename(val groupKey: String) : HomeEvent
    data object CancelRename : HomeEvent
    data class RenameGroup(val groupKey: String, val name: String) : HomeEvent
    data class CreateGroup(val name: String) : HomeEvent
    data class AskDeleteGroup(val groupKey: String) : HomeEvent
    data object ConfirmDeleteGroup : HomeEvent
    data object CancelDeleteGroup : HomeEvent
    data object CreatedGroupShown : HomeEvent
    data object DismissToast : HomeEvent
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
    /** Whether this user administers the production; sampled on each load. */
    private val isAdmin: () -> Boolean = { false },
    /** Sections the desktop adds itself; see [HomeUiState.localSections]. */
    localSections: List<ToolSection> = emptyList(),
    /** Remote Config's `show_manage_tool_groups`; only an explicit "true" turns it on. */
    private val organizeSwitch: suspend () -> Boolean = { false },
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
            HomeEvent.DismissToast -> setState { copy(toast = null) }
            HomeEvent.CreatedGroupShown -> setState { copy(createdGroupKey = null) }
            else -> organize(event)
        }
    }

    // -- Manage Tool Groups -------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One branch per organize gesture.
    private fun organize(event: HomeEvent) {
        if (!currentState.canOrganize) {
            setState { copy(organizing = false) }
            return
        }
        when (event) {
            HomeEvent.ToggleOrganize ->
                setState { copy(organizing = !organizing, renamingGroup = null) }
            is HomeEvent.MoveTool -> moveTool(event)
            is HomeEvent.StartRename -> setState { copy(renamingGroup = event.groupKey) }
            HomeEvent.CancelRename -> setState { copy(renamingGroup = null) }
            is HomeEvent.RenameGroup -> renameGroup(event.groupKey, event.name)
            is HomeEvent.CreateGroup -> createGroup(event.name)
            is HomeEvent.AskDeleteGroup -> setState {
                copy(confirmingDelete = groups.firstOrNull { it.identifier == event.groupKey && !it.systemDefined })
            }
            HomeEvent.ConfirmDeleteGroup -> deleteGroup()
            HomeEvent.CancelDeleteGroup -> setState { copy(confirmingDelete = null) }
            else -> Unit
        }
    }

    /**
     * A drop: painted where it landed at once, then written. A refusal puts
     * the tile back and says so. Assignment is per production, so the toast
     * names both the tool and where it went — a drop nobody meant is then
     * recognised as one.
     */
    private fun moveTool(event: HomeEvent.MoveTool) {
        val state = currentState
        val from = state.sections.firstOrNull { section ->
            section.tools.any { it.identifier == event.toolIdentifier }
        }?.dropKey
        if (from == event.toGroup) return
        val previous = state.moves[event.toolIdentifier]
        val groupName = state.groups.firstOrNull { it.identifier == event.toGroup }?.name ?: UNGROUPED_TOOLS
        setState { copy(moves = moves + (event.toolIdentifier to event.toGroup)) }
        launchResult(
            block = {
                val wireGroup = if (event.toGroup == UNGROUPED_KEY) "" else event.toGroup
                toolsRepository.moveTool(event.toolIdentifier, wireGroup)
            },
            onSuccess = {
                setState { copy(toast = HomeToast(str(S.desktop_ft_tool_moved, event.toolName, groupName), true)) }
            },
            onError = {
                setState {
                    copy(
                        moves = if (previous == null) {
                            moves - event.toolIdentifier
                        } else {
                            moves + (event.toolIdentifier to previous)
                        },
                        toast = HomeToast(str(S.desktop_ft_move_failed), false),
                    )
                }
            },
        )
    }

    private fun renameGroup(groupKey: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            setState { copy(toast = HomeToast(str(S.desktop_ft_name_required), false)) }
            return
        }
        val group = currentState.groups.firstOrNull { it.identifier == groupKey } ?: return
        val id = group.id ?: return
        if (trimmed == group.name) {
            setState { copy(renamingGroup = null) }
            return
        }
        setState { copy(busyGroupKey = groupKey) }
        launchResult(
            block = { toolsRepository.renameGroup(id, trimmed) },
            onSuccess = {
                setState {
                    copy(
                        busyGroupKey = null,
                        renamingGroup = null,
                        groups = groups.map { if (it.identifier == groupKey) it.copy(name = trimmed) else it },
                    )
                }
                refreshGroups()
            },
            onError = {
                setState { copy(busyGroupKey = null, toast = HomeToast(str(S.desktop_ft_rename_failed), false)) }
            },
        )
    }

    private fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            setState { copy(toast = HomeToast(str(S.desktop_ft_name_required), false)) }
            return
        }
        setState { copy(busyGroupKey = NEW_GROUP_KEY) }
        launchResult(
            block = { toolsRepository.createGroup(trimmed) },
            onSuccess = { created ->
                setState { copy(busyGroupKey = null, createdGroupKey = created) }
                refreshGroups()
            },
            onError = {
                setState { copy(busyGroupKey = null, toast = HomeToast(str(S.desktop_ft_create_failed), false)) }
            },
        )
    }

    /**
     * The backend decides whether a group still holds tools — tools this
     * user cannot see never reached the grid, so an empty-looking section may
     * not be empty — and its refusal (`tool_group_in_use`) is what is shown.
     */
    private fun deleteGroup() {
        val group = currentState.confirmingDelete ?: return
        val id = group.id ?: return
        setState { copy(confirmingDelete = null, busyGroupKey = group.identifier) }
        launchResult(
            block = { toolsRepository.deleteGroup(id) },
            onSuccess = {
                setState { copy(busyGroupKey = null, toast = HomeToast(str(S.mtg_group_deleted), true)) }
                refreshGroups()
            },
            onError = { error ->
                val said = (error as? ZillitError.Http)?.serverMessage?.localised()
                setState {
                    copy(busyGroupKey = null, toast = HomeToast(said ?: str(S.desktop_ft_delete_failed), false))
                }
                refreshGroups()
            },
        )
    }

    private fun refreshGroups() {
        launchResult(
            block = { toolsRepository.loadGroups() },
            onSuccess = { groups -> setState { copy(groups = groups) } },
            onError = { },
        )
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
        val admin = isAdmin()
        setState { copy(isBusy = true, error = null, isAdmin = admin) }

        // The Remote Config switch, asked on each load so a rollout (or a
        // pull-back) reaches an open grid; losing it closes the mode.
        if (admin) {
            launch {
                val on = runCatching { organizeSwitch() }.getOrDefault(false)
                setState { copy(canOrganize = on, organizing = organizing && on) }
            }
        } else {
            setState { copy(canOrganize = false, organizing = false) }
        }

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
                    // The server's word now includes every move made here, so
                    // the painted-ahead overrides have nothing left to say.
                    setState { copy(isBusy = false, permissions = loaded.data, staleSince = null, moves = emptyMap()) }
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
