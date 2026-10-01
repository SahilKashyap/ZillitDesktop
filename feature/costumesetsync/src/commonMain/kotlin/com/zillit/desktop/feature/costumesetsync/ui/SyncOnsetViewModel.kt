package com.zillit.desktop.feature.costumesetsync.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.NoHost
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SyncHost
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class SyncHostState(
    val viewer: SyncViewer = SyncViewer(),
    /** `GET /meta`, once. */
    val meta: Rec? = null,
    val project: SyncProject = SyncProject(null, emptySet()),
    /** The production record has answered, either way — the shell holds its loader until then. */
    val projectReady: Boolean = false,
    /** `/dashboard` `counts`, behind the Costumes menu's figures. */
    val counts: Rec? = null,
)

sealed interface SyncHostEvent {
    /** The tool opened, or the production changed. */
    data object Load : SyncHostEvent

    /** The production record changed (setup saved). */
    data object ReloadProject : SyncHostEvent

    /** A write succeeded: the nav counts should catch up. */
    data object Changed : SyncHostEvent

    data class AskRights(val kind: RightsKind) : SyncHostEvent
}

/**
 * What every Costumes & Set Sync screen shares: the rights gate, the service's
 * enum feed, the production record and the nav counts — the web's
 * `SyncOnsetProvider` plus `useNavCounts`.
 *
 * Rights first: the service answers `403 costume_set_sync_tool_not_enabled` for
 * a production without the tool, and a 403 anywhere in this app is read as "no
 * longer a member", so nothing calls until the tool's row has confirmed it.
 * Built once per graph, so the viewer is a lambda resolved on every [Load].
 */
class SyncOnsetViewModel(
    val api: SyncOnsetApi,
    private val viewer: () -> SyncViewer,
    private val rights: RightsRequestBus? = null,
    private val events: SocketEventBus? = null,
    val projectId: () -> String?,
    val userId: () -> String = { "" },
    val host: SyncHost = NoHost,
) : ZillitViewModel<SyncHostState, SyncHostEvent, Nothing>(SyncHostState()) {

    private var rightsPoll: Job? = null
    private var countsJob: Job? = null
    private var loadedFor: String? = null

    /** Every frame the service emits; screens filter their own. */
    val frames: Flow<SocketMessage> = events?.onAny(SyncEvents.allNames) ?: emptyFlow()

    override fun onEvent(event: SyncHostEvent) {
        when (event) {
            SyncHostEvent.Load -> load()
            SyncHostEvent.ReloadProject -> loadProject()
            SyncHostEvent.Changed -> refreshCounts(afterMs = CHANGED_DELAY_MS)
            is SyncHostEvent.AskRights -> rights?.ask(str(S.desktop_csync_tool_name), event.kind)
        }
    }

    private fun load() {
        val now = viewer()
        setState { copy(viewer = now) }
        if (!now.resolved) {
            rightsPoll?.cancel()
            rightsPoll = launch {
                repeat(RIGHTS_POLLS) {
                    delay(RIGHTS_POLL_MS)
                    val again = viewer()
                    if (again.resolved) {
                        setState { copy(viewer = again) }
                        if (again.canCall) loadAll()
                        return@launch
                    }
                }
            }
            return
        }
        if (now.canCall) loadAll()
    }

    private fun loadAll() {
        val project = projectId()
        // A different production is a different tool: start again.
        if (loadedFor != project) {
            loadedFor = project
            setState { copy(meta = null, project = SyncProject(null, emptySet()), projectReady = false, counts = null) }
        }
        launch {
            (api.meta() as? ZillitResult.Success)?.data?.rec?.let { meta -> setState { copy(meta = meta) } }
            loadProject()
        }
        startCounts()
    }

    private fun loadProject() {
        if (!currentState.viewer.canCall) return
        val asked = projectId()
        launch {
            val answer = api.get("")
            // A late answer for the production the user has since left is dropped.
            if (asked != projectId()) return@launch
            val record = (answer as? ZillitResult.Success)?.data?.rec
            setState {
                copy(
                    project = SyncProject(record ?: project.rec, meta?.strings("finance_roles").orEmpty().toSet()),
                    projectReady = true,
                )
            }
        }
    }

    /** The Costumes menu's figures: every minute, and ~1 s after any write. */
    private fun startCounts() {
        countsJob?.cancel()
        countsJob = launch {
            while (true) {
                fetchCounts()
                delay(COUNTS_POLL_MS)
            }
        }
    }

    private fun refreshCounts(afterMs: Long) {
        if (!currentState.viewer.canCall) return
        launch {
            delay(afterMs)
            fetchCounts()
        }
    }

    private suspend fun fetchCounts() {
        val answer = api.get("/dashboard")
        (answer as? ZillitResult.Success)?.data?.rec?.rec("counts")?.let { counts -> setState { copy(counts = counts) } }
    }

    override fun onCleared() {
        countsJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val RIGHTS_POLLS = 20
        const val RIGHTS_POLL_MS = 500L
        const val COUNTS_POLL_MS = 60_000L
        const val CHANGED_DELAY_MS = 1_000L
    }
}
